import json
import os
import tempfile
import unittest
from datetime import date, datetime, timezone
from decimal import Decimal
from pathlib import Path

from pydantic import ValidationError

from app.data_quality import (
    AdjustType,
    DataQualityConfig,
    DataQualityDecision,
    DataQualityIssue,
    DataQualityManifest,
    DataQualityReport,
    DataQualityStatus,
    DataSnapshotContext,
    EnforcementMode,
    IssueOutcome,
    IssueSeverity,
    ProductType,
    load_data_quality_config,
)


class DataQualityConfigTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.TemporaryDirectory()
        self.addCleanup(self.root.cleanup)
        self.config_dir = Path(self.root.name) / "config"
        self.config_dir.mkdir()
        self.storage_root = Path(self.root.name) / "market-data"
        self.storage_root.mkdir()
        self.previous_root = os.environ.get("ANALYSIS_DATA_ROOT")
        os.environ["ANALYSIS_DATA_ROOT"] = str(self.storage_root)
        self.addCleanup(self._restore_root)

    def _restore_root(self):
        if self.previous_root is None:
            os.environ.pop("ANALYSIS_DATA_ROOT", None)
        else:
            os.environ["ANALYSIS_DATA_ROOT"] = self.previous_root

    def _write_config(self, payload=None, filename="data-quality-v1.json"):
        (self.config_dir / filename).write_text(json.dumps(payload or self._valid_payload()), encoding="utf-8")

    @staticmethod
    def _valid_payload(version="data-quality-v1"):
        return {
            "version": version,
            "ruleSet": "data-quality-v1",
            "schemaVersion": "market-data-schema-v1",
            "enforcementMode": "OBSERVE",
            "storageRootEnv": "ANALYSIS_DATA_ROOT",
            "orphanRetentionHours": 24,
            "warningFailuresToBlock": 3,
            "stock": {
                "maxStaleTradingDays": 1, "maxMissingRatio": 0.02, "crossSourceEnabled": True,
                "minimumOverlapDays": 1, "maxPriceDeviationRatio": 0.01,
                "extremeReturnRatio": 0.20, "corporateActionEvidenceReturnRatio": 0.30,
                "extremeVolumeMultiplier": 20,
            },
            "fund": {"maxStaleCalendarDays": 3, "maxMissingNavRatio": 0.05},
        }

    def test_loads_exact_filename_when_v1_and_v10_coexist(self):
        self._write_config()
        self._write_config(self._valid_payload("data-quality-v10"), "data-quality-v10.json")

        config = load_data_quality_config("v1", self.config_dir)

        self.assertEqual("data-quality-v1", config.version)
        self.assertEqual(EnforcementMode.OBSERVE, config.enforcement_mode)
        self.assertEqual(self.storage_root.resolve(), config.storage_root)
        self.assertEqual(Decimal("0.02"), config.stock.max_missing_ratio)

    def test_rejects_missing_version_keys_values_and_storage_root(self):
        with self.assertRaisesRegex(ValueError, "exactly one"):
            load_data_quality_config("v1", self.config_dir)
        invalid = self._valid_payload()
        invalid.pop("stock")
        self._write_config(invalid)
        with self.assertRaises(ValidationError):
            load_data_quality_config("v1", self.config_dir)
        invalid = self._valid_payload()
        invalid["version"] = "data-quality-v2"
        self._write_config(invalid)
        with self.assertRaisesRegex(ValueError, "version"):
            load_data_quality_config("v1", self.config_dir)
        self._write_config()
        os.environ.pop("ANALYSIS_DATA_ROOT")
        with self.assertRaisesRegex(ValueError, "ANALYSIS_DATA_ROOT"):
            load_data_quality_config("v1", self.config_dir)

    def test_config_is_strict_uses_decimal_ratios_and_only_observe_or_enforce(self):
        payload = self._valid_payload() | {"storageRoot": self.storage_root, "unknown": True}
        with self.assertRaises(ValidationError):
            DataQualityConfig.model_validate(payload)
        payload = self._valid_payload() | {"storageRoot": self.storage_root, "enforcementMode": "BLOCK"}
        with self.assertRaises(ValidationError):
            DataQualityConfig.model_validate(payload)
        payload = self._valid_payload() | {"storageRoot": self.storage_root}
        payload["stock"]["maxMissingRatio"] = "0.02"
        with self.assertRaises(ValidationError):
            DataQualityConfig.model_validate(payload)
        payload["stock"]["maxMissingRatio"] = Decimal("0.02")
        payload["warningFailuresToBlock"] = 0
        with self.assertRaises(ValidationError):
            DataQualityConfig.model_validate(payload)

    def test_snapshot_context_has_complete_daily_request_contract(self):
        context = DataSnapshotContext(
            product_type=ProductType.STOCK, market="CN", code="000001", frequency="DAY",
            adjust_type=AdjustType.QFQ, provider="tencent", adapter_version="v2",
            requested_start_date=date(2026, 1, 1), requested_end_date=date(2026, 1, 31),
            fetched_at=datetime(2026, 2, 1, tzinfo=timezone.utc),
        )
        self.assertEqual("DAY", context.frequency)
        with self.assertRaises(ValidationError):
            DataSnapshotContext(product_type="MUTUAL_FUND", market="CN", code="000001", frequency="DAY",
                                adjust_type="QFQ", provider="eastmoney", adapter_version="v1",
                                requested_start_date=date.today(), requested_end_date=date.today(),
                                fetched_at=datetime.now(timezone.utc))
        with self.assertRaises(ValidationError):
            DataSnapshotContext(product_type="STOCK", market="CN", code="000001", frequency="WEEK",
                                adjust_type="HFQ", provider="tencent", adapter_version="v1",
                                requested_start_date=date.today(), requested_end_date=date.today(),
                                fetched_at=datetime.now(timezone.utc))

    def test_manifest_issue_and_report_expose_full_immutable_contracts(self):
        timestamp = datetime(2026, 2, 1, tzinfo=timezone.utc)
        manifest = DataQualityManifest(
            dataset_version="dataset-v1", product_type="STOCK", market="CN", code="000001", frequency="DAY",
            adjust_type="QFQ", provider="tencent", adapter_version="v2", requested_start_date=date(2026, 1, 1),
            requested_end_date=date(2026, 1, 31), sample_start_date=date(2026, 1, 2), sample_end_date=date(2026, 1, 30),
            record_count=20, fetched_at=timestamp, content_hash="content", parquet_file_hash="parquet",
            storage_format="PARQUET", storage_uri="file:///market-data/a.parquet", schema_version="market-data-schema-v1",
        )
        issue = DataQualityIssue(rule_code="STALE_DATA", severity="WARNING", outcome="FAIL", message="stale",
                                 observed={"staleDays": 2}, expected={"maximum": 1}, affected_dates=(date(2026, 1, 30),))
        report = DataQualityReport(
            dataset_version="dataset-v1", quality_rule_set_version="data-quality-v1", status="WARN", decision="ALLOW",
            enforcement_mode="OBSERVE", evaluated_at=timestamp, issues=(issue,), summary={"warningFailures": 1},
            evidence_eligibility=None,
        )
        self.assertEqual(IssueSeverity.WARNING, issue.severity)
        self.assertEqual(IssueOutcome.FAIL, issue.outcome)
        self.assertEqual(DataQualityStatus.WARN, report.status)
        self.assertEqual(DataQualityDecision.ALLOW, report.decision)
        with self.assertRaises(ValidationError):
            manifest.record_count = 0
        with self.assertRaises(ValidationError):
            report.status = "PASS"

    def test_manifest_rejects_adjusted_mutual_funds(self):
        with self.assertRaises(ValidationError):
            DataQualityManifest(
                dataset_version="dataset-v1", product_type="MUTUAL_FUND", market="CN", code="000001", frequency="DAY",
                adjust_type="QFQ", provider="eastmoney", adapter_version="v1", requested_start_date=date(2026, 1, 1),
                requested_end_date=date(2026, 1, 31), sample_start_date=date(2026, 1, 2), sample_end_date=date(2026, 1, 30),
                record_count=20, fetched_at=datetime(2026, 2, 1, tzinfo=timezone.utc), content_hash="content",
                parquet_file_hash="parquet", storage_format="PARQUET", storage_uri="file:///market-data/a.parquet",
                schema_version="market-data-schema-v1",
            )

    def test_issue_and_report_json_data_are_deeply_immutable_but_dump_as_json(self):
        issue = DataQualityIssue(
            rule_code="MISSING", severity="WARNING", outcome="FAIL", message="missing values",
            observed={"dates": ["2026-01-02"]}, expected={"ratio": 0.02},
        )
        report = DataQualityReport(
            dataset_version="dataset-v1", quality_rule_set_version="data-quality-v1", status="WARN", decision="ALLOW",
            enforcement_mode="OBSERVE", evaluated_at=datetime(2026, 2, 1, tzinfo=timezone.utc), issues=(issue,),
            summary={"counts": {"warning": 1}, "dates": ["2026-01-02"]}, evidence_eligibility="QUALITY_OBSERVE_ONLY",
        )
        with self.assertRaises(TypeError):
            report.summary["new"] = 1
        with self.assertRaises(TypeError):
            report.summary["counts"]["warning"] = 2
        with self.assertRaises(AttributeError):
            report.summary["dates"].append("2026-01-03")
        with self.assertRaises(TypeError):
            issue.observed["dates"] = ()
        with self.assertRaises(AttributeError):
            issue.observed["dates"].append("2026-01-03")
        dumped = report.model_dump(by_alias=True, mode="json")
        self.assertEqual({"warning": 1}, dumped["summary"]["counts"])
        self.assertEqual(["2026-01-02"], dumped["issues"][0]["observed"]["dates"])
        with self.assertRaises(ValidationError):
            DataQualityReport(
                dataset_version="dataset-v1", quality_rule_set_version="data-quality-v1", status="PASS", decision="ALLOW",
                enforcement_mode="OBSERVE", evaluated_at=datetime(2026, 2, 1, tzinfo=timezone.utc), issues=(), summary={},
                evidence_eligibility=True,
            )


if __name__ == "__main__":
    unittest.main()
