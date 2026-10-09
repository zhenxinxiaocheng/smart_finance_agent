import json
import os
import shutil
import tempfile
import unittest
from datetime import date, datetime, timezone
from dataclasses import replace
from decimal import Decimal
from pathlib import Path
from unittest.mock import Mock, patch

from app.data_quality import AdjustType, ProductType
from app.data_quality.service import DataQualityService
from app.data_quality.snapshot_store import SnapshotStore
from app.providers import NormalizedQuote, ProviderBatch
from app.quote_availability import QuoteAvailability


class DataQualityServiceTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.fixture = Path(__file__).parent / "fixtures" / "market-data-v1"
        self.expected = json.loads((self.fixture / "replay-v5.json").read_text(encoding="utf-8"))
        shutil.copytree(self.fixture / "snapshots", self.root / "snapshots")
        environment = patch.dict(os.environ, ANALYSIS_DATA_ROOT=str(self.root))
        environment.start()
        self.addCleanup(environment.stop)
        days = [date(2026, 1, 2), date(2026, 1, 3)]
        availability = patch("app.data_quality.service.quote_availability",
                             QuoteAvailability(lambda name, year: days if year == 2026 else [date(2025, 12, 31)] if year == 2025 else []))
        availability.start()
        self.addCleanup(availability.stop)
        self.now = datetime(2026, 2, 1, 12, 30, tzinfo=timezone.utc)
        self.registry = Mock()
        self.service = DataQualityService(self.registry, lambda year: [day.isoformat() for day in days],
                                          lambda: self.now)

    def test_frozen_v1_replays_exact_old_response_and_remains_readable_under_current_rules(self):
        paths = list((self.root / "snapshots").rglob("*.*"))
        before = {path: path.read_bytes() for path in paths}
        old = self.service.replay(dataset_version=self.expected["datasetVersion"],
                                  quality_config_version="data-quality-v5")
        current = self.service.replay(dataset_version=self.expected["datasetVersion"],
                                      quality_config_version="data-quality-v6")

        self.assertEqual(self.expected, old)
        self.assertEqual(old["records"], current["records"])
        self.assertEqual(old["manifest"], current["manifest"])
        self.assertEqual("data-quality-v6", current["qualityReport"]["qualityRuleSetVersion"])
        self.service.claim(dataset_version=old["datasetVersion"], quality_config_version="data-quality-v6")
        self.assertEqual(before, {path: path.read_bytes() for path in paths})
        self.registry.daily_quality_batches.assert_not_called()

    def _batch(self, product_type=ProductType.STOCK):
        fund = product_type is ProductType.MUTUAL_FUND
        record = NormalizedQuote(
            product_code="000001", market="FUND_CN" if fund else "SZSE", data_date=date(2026, 1, 2),
            open=Decimal("10"), high=Decimal("10.3"), low=Decimal("9.9"), close=Decimal("10.1"),
            volume=Decimal("1000"), provider="TENCENT", adapter_version="2", fetched_at=self.now,
            amount=None if fund else Decimal("12345.5"), turnover_rate=None if fund else Decimal("1.25"),
        )
        return ProviderBatch(product_type=product_type, code=record.product_code, market=record.market,
                             frequency="DAY", adjust_type=AdjustType.NONE if fund else AdjustType.QFQ,
                             provider=record.provider, adapter_version=record.adapter_version,
                             fetched_at=self.now, records=(record,))

    def _validate(self, batch, version):
        self.registry.daily_quality_batches.return_value = ([batch], ())
        return self.service.validate(product_type=batch.product_type, code=batch.code, market=batch.market,
                                     frequency=batch.frequency, adjust_type=batch.adjust_type,
                                     start_date=date(2026, 1, 1), end_date=date(2026, 1, 31),
                                     quality_config_version=version)

    def test_v6_validation_and_replay_preserve_liquidity_while_v5_keeps_its_old_contract(self):
        batch = self._batch()
        current = self._validate(batch, "data-quality-v6")
        old = self._validate(batch, "data-quality-v5")

        self.assertEqual("market-data-schema-v2", current["manifest"]["schemaVersion"])
        self.assertEqual("12345.5", current["records"][0]["amount"])
        self.assertEqual("1.25", current["records"][0]["turnover_rate"])
        self.assertEqual("market-data-schema-v1", old["manifest"]["schemaVersion"])
        self.assertNotIn("amount", old["records"][0])
        self.assertNotIn("turnover_rate", old["records"][0])
        self.assertNotEqual(old["datasetVersion"], current["datasetVersion"])
        self.assertEqual(current, self.service.replay(dataset_version=current["datasetVersion"],
                                                      quality_config_version="data-quality-v6"))
        self.assertEqual(old, self.service.replay(dataset_version=old["datasetVersion"],
                                                  quality_config_version="data-quality-v5"))

    def test_v2_fund_snapshot_keeps_missing_liquidity_null(self):
        response = self._validate(self._batch(ProductType.MUTUAL_FUND), "data-quality-v6")
        row = response["records"][0]
        self.assertIsNone(row["amount"])
        self.assertIsNone(row["turnover_rate"])
        self.assertIsNone(row["volume"])
        self.assertEqual("10.1", row["nav"])
        self.assertEqual("UNIT_NAV", row["nav_type"])

    def _stored_slice(self, day, close, *, provider="TENCENT", adjust_type=AdjustType.NONE):
        batch = self._batch()
        record = replace(batch.records[0], data_date=day, open=Decimal(close), high=Decimal(close),
                         low=Decimal(close), close=Decimal(close), provider=provider)
        batch = replace(batch, records=(record,), adjust_type=adjust_type, provider=provider)
        return SnapshotStore(self.root, "market-data-schema-v2").write(
            batch.to_snapshot_rows("market-data-schema-v2"), batch.snapshot_context(day, day)).dataset_version

    def test_replay_rechecks_cross_slice_returns_without_fetching_quotes(self):
        first = self._stored_slice(date(2026, 1, 2), "10")
        second = self._stored_slice(date(2026, 1, 3), "100")
        result = self.service.replay(dataset_version=first, continuation_dataset_versions=[second],
                                     start_date=date(2026, 1, 2), end_date=date(2026, 1, 3),
                                     quality_config_version="data-quality-v6")

        self.assertEqual(["2026-01-02", "2026-01-03"], [row["data_date"] for row in result["records"]])
        issues = {issue["ruleCode"]: issue for issue in result["qualityReport"]["issues"]}
        self.assertEqual("FAIL", issues["STOCK_EXTREME_RETURN"]["outcome"])
        self.assertEqual([first, second], result["preparedDatasetVersions"])
        replayed = self.service.replay(dataset_version=result["datasetVersion"], quality_config_version="data-quality-v6")
        self.assertEqual(result["records"], replayed["records"])
        self.registry.daily_quality_batches.assert_not_called()

    def test_combined_secondary_source_checks_the_new_tail_as_well(self):
        first = self._stored_slice(date(2026, 1, 2), "10")
        tail = self._stored_slice(date(2026, 1, 3), "11")
        secondary = self._stored_slice(date(2026, 1, 2), "10", provider="SINA")
        secondary_tail = self._stored_slice(date(2026, 1, 3), "15", provider="SINA")
        result = self.service.replay(dataset_version=first, continuation_dataset_versions=[tail],
            secondary_dataset_version=secondary, continuation_secondary_versions=[secondary_tail],
            start_date=date(2026, 1, 2), end_date=date(2026, 1, 3), quality_config_version="data-quality-v6")
        issue = next(item for item in result["qualityReport"]["issues"] if item["ruleCode"] == "STOCK_CROSS_SOURCE_RECONCILIATION")
        self.assertEqual("FAIL", issue["outcome"])
        self.assertIn("2026-01-03", issue["affectedDates"])
        self.registry.daily_quality_batches.assert_not_called()

    def test_prepared_replay_rejects_changed_adjustment_basis_or_source_identity(self):
        first = self._stored_slice(date(2026, 1, 2), "10", adjust_type=AdjustType.QFQ)
        changed = self._stored_slice(date(2026, 1, 2), "11", adjust_type=AdjustType.QFQ)
        other = self._stored_slice(date(2026, 1, 3), "11", provider="SINA", adjust_type=AdjustType.QFQ)
        for continuation in (changed, other):
            with self.subTest(continuation=continuation), self.assertRaisesRegex(ValueError, "basis|identity"):
                self.service.replay(dataset_version=first, continuation_dataset_versions=[continuation],
                    start_date=date(2026, 1, 2), end_date=date(2026, 1, 2), quality_config_version="data-quality-v6")
        self.registry.daily_quality_batches.assert_not_called()

    def test_replay_cannot_invent_proof_for_an_unprepared_gap(self):
        first = self._stored_slice(date(2026, 1, 2), "10")
        second = self._stored_slice(date(2026, 1, 4), "11")
        with self.assertRaisesRegex(ValueError, "unprepared"):
            self.service.replay(dataset_version=first, continuation_dataset_versions=[second],
                                start_date=date(2026, 1, 2), end_date=date(2026, 1, 4),
                                quality_config_version="data-quality-v6")
        self.registry.daily_quality_batches.assert_not_called()
