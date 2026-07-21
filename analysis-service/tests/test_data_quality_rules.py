import copy
import unittest
from datetime import date, datetime, timezone
from decimal import Decimal
from pathlib import Path

from app.data_quality import (
    AdjustType,
    DataQualityConfig,
    DataQualityDecision,
    DataQualityEngine,
    DataQualityManifest,
    DataQualityStatus,
    EnforcementMode,
    IssueOutcome,
    ProductType,
)


RULE_CODES = (
    "COMMON_REQUIRED_FIELDS",
    "COMMON_UNIQUE_ORDERED_DATES",
    "COMMON_POSITIVE_VALUES",
    "MANIFEST_CONTENT_INTEGRITY",
    "STOCK_OHLC_RELATION",
    "STOCK_ADJUSTMENT_CONSISTENCY",
    "STOCK_STALENESS",
    "STOCK_UNEXPLAINED_TRADING_GAPS",
    "STOCK_EXTREME_RETURN",
    "STOCK_EXTREME_VOLUME",
    "STOCK_CORPORATE_ACTION_EVIDENCE",
    "STOCK_SECONDARY_SOURCE_AVAILABILITY",
    "STOCK_CROSS_SOURCE_RECONCILIATION",
    "FUND_NAV_TYPE_CONSISTENCY",
    "FUND_ESTIMATED_NAV_FORBIDDEN",
    "FUND_STALENESS",
    "FUND_UNEXPLAINED_NAV_GAPS",
)


class DataQualityRuleEngineTest(unittest.TestCase):
    def setUp(self):
        self.now = datetime(2026, 1, 6, 8, tzinfo=timezone.utc)
        self.config = self._config()

    def _config(self, *, enforcement="OBSERVE", warning_threshold=3, minimum_overlap=2):
        return DataQualityConfig(
            version="data-quality-v1",
            rule_set="data-quality-v1",
            schema_version="market-data-schema-v1",
            enforcement_mode=enforcement,
            storage_root_env="ANALYSIS_DATA_ROOT",
            storage_root=Path(".").resolve(),
            orphan_retention_hours=24,
            warning_failures_to_block=warning_threshold,
            stock={
                "maxStaleTradingDays": 1,
                "maxMissingRatio": Decimal("0.20"),
                "crossSourceEnabled": True,
                "minimumOverlapDays": minimum_overlap,
                "maxPriceDeviationRatio": Decimal("0.01"),
                "extremeReturnRatio": Decimal("0.20"),
                "corporateActionEvidenceReturnRatio": Decimal("0.30"),
                "extremeVolumeMultiplier": 20,
            },
            fund={"maxStaleCalendarDays": 3, "maxMissingNavRatio": Decimal("0.20")},
        )

    @staticmethod
    def _stock_row(day, close="10.00", volume="100", *, adjust="QFQ", provider="primary", **changes):
        close_value = Decimal(close)
        row = {
            "product_code": "000001",
            "product_type": "STOCK",
            "market": "CN",
            "frequency": "DAY",
            "adjust_type": adjust,
            "provider": provider,
            "adapter_version": "v1",
            "data_date": day,
            "observed_at": datetime.combine(day, datetime.min.time(), timezone.utc),
            "open": close_value,
            "high": close_value + Decimal("0.20"),
            "low": close_value - Decimal("0.20"),
            "close": close_value,
            "volume": Decimal(volume),
            "nav": None,
            "trading_status": "NORMAL",
            "adjustment_factor": Decimal("1"),
            "corporate_action_reference": None,
            "nav_type": None,
            "estimated": False,
        }
        row.update(changes)
        return row

    @staticmethod
    def _fund_row(day, nav="1.00", *, nav_type="UNIT_NAV", estimated=False, **changes):
        row = {
            "product_code": "110022",
            "product_type": "MUTUAL_FUND",
            "market": "CN",
            "frequency": "DAY",
            "adjust_type": "NONE",
            "provider": "primary",
            "adapter_version": "v1",
            "data_date": day,
            "observed_at": datetime.combine(day, datetime.min.time(), timezone.utc),
            "open": None,
            "high": None,
            "low": None,
            "close": None,
            "volume": None,
            "nav": Decimal(nav),
            "trading_status": None,
            "adjustment_factor": None,
            "corporate_action_reference": None,
            "nav_type": nav_type,
            "estimated": estimated,
        }
        row.update(changes)
        return row

    @staticmethod
    def _manifest(records, product_type, *, start=None, end=None):
        days = [row["data_date"] for row in records]
        is_stock = product_type is ProductType.STOCK
        return DataQualityManifest(
            dataset_version="dataset-v1",
            product_type=product_type,
            market="CN",
            code="000001" if is_stock else "110022",
            frequency="DAY",
            adjust_type=AdjustType.QFQ if is_stock else AdjustType.NONE,
            provider="primary",
            adapter_version="v1",
            requested_start_date=start or min(days),
            requested_end_date=end or max(days),
            sample_start_date=min(days),
            sample_end_date=max(days),
            record_count=len(records),
            fetched_at=datetime(2026, 1, 6, tzinfo=timezone.utc),
            content_hash="verified-content",
            parquet_file_hash="verified-parquet",
            storage_format="PARQUET",
            storage_uri="snapshots/dataset-v1.parquet",
            schema_version="market-data-schema-v1",
        )

    def _evaluate(self, records, product_type, expected_dates, *, config=None, integrity_verified=True,
                  secondary_records=None, now=None, start=None, end=None):
        engine = DataQualityEngine(config or self.config, lambda: now or self.now)
        return engine.evaluate(
            self._manifest(records, product_type, start=start, end=end),
            records,
            expected_dates,
            integrity_verified=integrity_verified,
            secondary_records=secondary_records,
        )

    @staticmethod
    def _issue(report, code):
        return next(issue for issue in report.issues if issue.rule_code == code)

    def test_valid_stock_emits_every_rule_once_with_fund_rules_isolated(self):
        records = [
            self._stock_row(date(2026, 1, 2), "10.00", "100"),
            self._stock_row(date(2026, 1, 5), "10.50", "120"),
        ]

        report = self._evaluate(records, ProductType.STOCK, (date(2026, 1, 2), date(2026, 1, 5)))

        self.assertEqual(RULE_CODES, tuple(issue.rule_code for issue in report.issues))
        self.assertEqual(len(RULE_CODES), len(set(issue.rule_code for issue in report.issues)))
        self.assertEqual(DataQualityStatus.PASS, report.status)
        self.assertEqual(DataQualityDecision.ALLOW, report.decision)
        for code in RULE_CODES[-4:]:
            self.assertEqual(IssueOutcome.NOT_APPLICABLE, self._issue(report, code).outcome)

    def test_valid_fund_emits_stock_rules_as_not_applicable(self):
        records = [self._fund_row(date(2026, 1, 2)), self._fund_row(date(2026, 1, 5), "1.01")]

        report = self._evaluate(records, ProductType.MUTUAL_FUND, (date(2026, 1, 2), date(2026, 1, 5)))

        self.assertEqual(DataQualityStatus.PASS, report.status)
        for code in RULE_CODES[4:13]:
            self.assertEqual(IssueOutcome.NOT_APPLICABLE, self._issue(report, code).outcome)
        for code in RULE_CODES[13:]:
            self.assertEqual(IssueOutcome.PASS, self._issue(report, code).outcome)

    def test_snapshot_store_api_safe_canonical_records_are_supported(self):
        records = [
            self._stock_row(date(2026, 1, 2), "10.00", "100"),
            self._stock_row(date(2026, 1, 5), "10.50", "120"),
        ]
        api_records = copy.deepcopy(records)
        for row in api_records:
            row["data_date"] = row["data_date"].isoformat()
            row["observed_at"] = row["observed_at"].isoformat().replace("+00:00", "Z")
            for field in ("open", "high", "low", "close", "volume", "adjustment_factor"):
                row[field] = str(row[field])

        expected = (date(2026, 1, 2), date(2026, 1, 5))
        typed_report = self._evaluate(records, ProductType.STOCK, expected)
        report = self._evaluate(api_records, ProductType.STOCK, expected)

        self.assertEqual(DataQualityStatus.PASS, report.status)
        self.assertEqual(typed_report, report)

        malformed = copy.deepcopy(api_records)
        malformed[0]["close"] = "not-a-decimal"
        malformed_report = DataQualityEngine(self.config, lambda: self.now).evaluate(
            self._manifest(records, ProductType.STOCK), malformed, expected, integrity_verified=True,
        )
        self.assertEqual(IssueOutcome.FAIL,
                         self._issue(malformed_report, "COMMON_POSITIVE_VALUES").outcome)

    def test_common_structural_rules_detect_missing_nonpositive_duplicate_and_unsorted_data(self):
        base = [self._stock_row(date(2026, 1, 2)), self._stock_row(date(2026, 1, 5), "10.10")]
        missing = copy.deepcopy(base)
        missing[0]["close"] = None
        report = self._evaluate(missing, ProductType.STOCK, (date(2026, 1, 2), date(2026, 1, 5)))
        self.assertEqual(IssueOutcome.FAIL, self._issue(report, "COMMON_REQUIRED_FIELDS").outcome)

        nonpositive = copy.deepcopy(base)
        nonpositive[0].update(open=Decimal("0"), high=Decimal("0"), low=Decimal("0"), close=Decimal("0"))
        report = self._evaluate(nonpositive, ProductType.STOCK, (date(2026, 1, 2), date(2026, 1, 5)))
        self.assertEqual(IssueOutcome.FAIL, self._issue(report, "COMMON_POSITIVE_VALUES").outcome)

        duplicate = [base[0], copy.deepcopy(base[0])]
        report = self._evaluate(duplicate, ProductType.STOCK, (date(2026, 1, 2),))
        self.assertEqual(IssueOutcome.FAIL, self._issue(report, "COMMON_UNIQUE_ORDERED_DATES").outcome)

        report = self._evaluate(list(reversed(base)), ProductType.STOCK,
                                (date(2026, 1, 2), date(2026, 1, 5)))
        self.assertEqual(IssueOutcome.FAIL, self._issue(report, "COMMON_UNIQUE_ORDERED_DATES").outcome)

    def test_manifest_integrity_is_an_explicit_critical_gate(self):
        records = [self._stock_row(date(2026, 1, 5))]

        report = self._evaluate(records, ProductType.STOCK, (date(2026, 1, 5),), integrity_verified=False)

        issue = self._issue(report, "MANIFEST_CONTENT_INTEGRITY")
        self.assertEqual(IssueOutcome.FAIL, issue.outcome)
        self.assertEqual("CRITICAL", issue.severity.value)
        self.assertEqual(DataQualityStatus.BLOCKED, report.status)

    def test_stock_ohlc_relation_rejects_invalid_bars_but_accepts_positive_one_price_limit_day(self):
        invalid = [self._stock_row(date(2026, 1, 5), high=Decimal("9.90"))]
        report = self._evaluate(invalid, ProductType.STOCK, (date(2026, 1, 5),))
        self.assertEqual(IssueOutcome.FAIL, self._issue(report, "STOCK_OHLC_RELATION").outcome)

        valid = [self._stock_row(
            date(2026, 1, 5), open=Decimal("10"), high=Decimal("10"),
            low=Decimal("10"), close=Decimal("10"),
        )]
        report = self._evaluate(valid, ProductType.STOCK, (date(2026, 1, 5),))
        self.assertEqual(IssueOutcome.PASS, self._issue(report, "STOCK_OHLC_RELATION").outcome)

    def test_adjusted_provider_rows_can_be_verified_without_fabricated_factors(self):
        records = [
            self._stock_row(date(2026, 1, 2), adjustment_factor=None),
            self._stock_row(date(2026, 1, 5), "10.10", adjustment_factor=None),
        ]
        payload = self.config.model_dump()
        payload["stock"]["require_adjustment_factor"] = False
        config = DataQualityConfig.model_validate(payload)

        report = self._evaluate(
            records,
            ProductType.STOCK,
            (date(2026, 1, 2), date(2026, 1, 5)),
            config=config,
        )

        self.assertEqual(
            IssueOutcome.PASS,
            self._issue(report, "STOCK_ADJUSTMENT_CONSISTENCY").outcome,
        )

    def test_stock_staleness_and_missing_ratio_use_explicit_trading_calendar(self):
        stale = [self._stock_row(date(2026, 1, 2))]
        report = self._evaluate(
            stale,
            ProductType.STOCK,
            (date(2026, 1, 2), date(2026, 1, 5), date(2026, 1, 6)),
            start=date(2026, 1, 2),
            end=date(2026, 1, 2),
        )
        self.assertEqual(IssueOutcome.FAIL, self._issue(report, "STOCK_STALENESS").outcome)

        gapped = [self._stock_row(date(2026, 1, 2)), self._stock_row(date(2026, 1, 6), "10.10")]
        report = self._evaluate(
            gapped,
            ProductType.STOCK,
            (date(2026, 1, 2), date(2026, 1, 5), date(2026, 1, 6)),
        )
        issue = self._issue(report, "STOCK_UNEXPLAINED_TRADING_GAPS")
        self.assertEqual(IssueOutcome.FAIL, issue.outcome)
        self.assertEqual((date(2026, 1, 5),), issue.affected_dates)

    def test_stock_missing_ratio_uses_full_requested_window_when_only_last_day_is_returned(self):
        records = [self._stock_row(date(2026, 1, 6))]
        expected = (date(2026, 1, 2), date(2026, 1, 5), date(2026, 1, 6), date(2026, 1, 7))

        report = self._evaluate(records, ProductType.STOCK, expected,
                                start=date(2026, 1, 2), end=date(2026, 1, 6))

        issue = self._issue(report, "STOCK_UNEXPLAINED_TRADING_GAPS")
        self.assertEqual(IssueOutcome.FAIL, issue.outcome)
        self.assertEqual(3, issue.observed["expectedDateCount"])
        self.assertEqual((date(2026, 1, 2), date(2026, 1, 5)), issue.affected_dates)

    def test_fund_missing_ratio_uses_full_requested_window_when_only_last_day_is_returned(self):
        records = [self._fund_row(date(2026, 1, 6))]
        expected = (date(2026, 1, 2), date(2026, 1, 5), date(2026, 1, 6), date(2026, 1, 7))

        report = self._evaluate(records, ProductType.MUTUAL_FUND, expected,
                                start=date(2026, 1, 2), end=date(2026, 1, 6))

        issue = self._issue(report, "FUND_UNEXPLAINED_NAV_GAPS")
        self.assertEqual(IssueOutcome.FAIL, issue.outcome)
        self.assertEqual(3, issue.observed["expectedDateCount"])
        self.assertEqual((date(2026, 1, 2), date(2026, 1, 5)), issue.affected_dates)

    def test_cross_source_overlap_uses_requested_range_not_sample_range(self):
        records = [self._stock_row(date(2026, 1, 2)), self._stock_row(date(2026, 1, 5), "10.5")]
        secondary = [self._stock_row(date(2026, 1, 2), provider="secondary"),
                     self._stock_row(date(2026, 1, 5), "10.5", provider="secondary")]
        manifest = self._manifest(records, ProductType.STOCK).model_copy(update={
            "sample_start_date": date(2026, 1, 5),
        })

        report = DataQualityEngine(self.config, lambda: self.now).evaluate(
            manifest, records, (date(2026, 1, 2), date(2026, 1, 5)),
            integrity_verified=True, secondary_records=secondary,
        )

        issue = self._issue(report, "STOCK_CROSS_SOURCE_RECONCILIATION")
        self.assertEqual(IssueOutcome.PASS, issue.outcome)
        self.assertEqual(2, issue.observed["overlapDays"])

    def test_suspended_stock_allows_zero_volume_when_positive_ohlc_is_valid(self):
        records = [self._stock_row(date(2026, 1, 5), volume=Decimal("0"), trading_status="SUSPENDED")]

        report = self._evaluate(records, ProductType.STOCK, (date(2026, 1, 5),))

        self.assertEqual(IssueOutcome.PASS, self._issue(report, "COMMON_POSITIVE_VALUES").outcome)
        self.assertEqual(IssueOutcome.PASS, self._issue(report, "STOCK_OHLC_RELATION").outcome)

    def test_normal_stock_rejects_zero_volume_as_critical(self):
        records = [self._stock_row(date(2026, 1, 5), volume=Decimal("0"), trading_status="NORMAL")]

        report = self._evaluate(records, ProductType.STOCK, (date(2026, 1, 5),))

        issue = self._issue(report, "COMMON_POSITIVE_VALUES")
        self.assertEqual(IssueOutcome.FAIL, issue.outcome)
        self.assertEqual("CRITICAL", issue.severity.value)

    def test_stock_extreme_return_volume_and_corporate_action_evidence_are_independent(self):
        records = [
            self._stock_row(date(2026, 1, 2), "10", "100"),
            self._stock_row(date(2026, 1, 5), "15", "2500"),
        ]
        report = self._evaluate(records, ProductType.STOCK, (date(2026, 1, 2), date(2026, 1, 5)))
        for code in ("STOCK_EXTREME_RETURN", "STOCK_EXTREME_VOLUME", "STOCK_CORPORATE_ACTION_EVIDENCE"):
            self.assertEqual(IssueOutcome.FAIL, self._issue(report, code).outcome)

        with_evidence = copy.deepcopy(records)
        with_evidence[1]["corporate_action_reference"] = "SPLIT-2026-01-05"
        report = self._evaluate(with_evidence, ProductType.STOCK,
                                (date(2026, 1, 2), date(2026, 1, 5)))
        self.assertEqual(IssueOutcome.FAIL, self._issue(report, "STOCK_EXTREME_RETURN").outcome)
        self.assertEqual(IssueOutcome.FAIL, self._issue(report, "STOCK_EXTREME_VOLUME").outcome)
        self.assertEqual(IssueOutcome.PASS, self._issue(report, "STOCK_CORPORATE_ACTION_EVIDENCE").outcome)

    def test_cross_source_reconciliation_compares_same_adjustment_and_fails_on_price_deviation(self):
        primary = [
            self._stock_row(date(2026, 1, 2), "10"),
            self._stock_row(date(2026, 1, 5), "10.50"),
        ]
        secondary = [
            self._stock_row(date(2026, 1, 2), "10", provider="secondary"),
            self._stock_row(date(2026, 1, 5), "10.70", provider="secondary"),
        ]

        report = self._evaluate(primary, ProductType.STOCK,
                                (date(2026, 1, 2), date(2026, 1, 5)), secondary_records=secondary)

        issue = self._issue(report, "STOCK_CROSS_SOURCE_RECONCILIATION")
        self.assertEqual(IssueOutcome.FAIL, issue.outcome)
        self.assertEqual("CRITICAL", issue.severity.value)
        self.assertEqual((date(2026, 1, 5),), issue.affected_dates)

    def test_mixed_adjustment_is_critical_and_never_cross_source_compared(self):
        primary = [self._stock_row(date(2026, 1, 5))]
        secondary = [self._stock_row(date(2026, 1, 5), adjust="HFQ", provider="secondary")]

        report = self._evaluate(primary, ProductType.STOCK, (date(2026, 1, 5),),
                                secondary_records=secondary)

        self.assertEqual(IssueOutcome.FAIL, self._issue(report, "STOCK_ADJUSTMENT_CONSISTENCY").outcome)
        cross_source = self._issue(report, "STOCK_CROSS_SOURCE_RECONCILIATION")
        self.assertEqual(IssueOutcome.NOT_APPLICABLE, cross_source.outcome)
        self.assertIn("adjust", cross_source.message.lower())

    def test_cross_source_insufficient_overlap_is_not_applicable_with_evidence(self):
        primary = [self._stock_row(date(2026, 1, 2)), self._stock_row(date(2026, 1, 5), "10.5")]
        secondary = [self._stock_row(date(2026, 1, 5), "10.5", provider="secondary")]

        report = self._evaluate(primary, ProductType.STOCK,
                                (date(2026, 1, 2), date(2026, 1, 5)), secondary_records=secondary)

        issue = self._issue(report, "STOCK_CROSS_SOURCE_RECONCILIATION")
        self.assertEqual(IssueOutcome.NOT_APPLICABLE, issue.outcome)
        self.assertEqual(1, issue.observed["overlapDays"])
        self.assertEqual(2, issue.expected["minimumOverlapDays"])

    def test_cross_source_requires_one_nonblank_provider_distinct_from_primary(self):
        primary = [self._stock_row(date(2026, 1, 2)), self._stock_row(date(2026, 1, 5), "10.5")]
        variants = {
            "same-provider": [self._stock_row(date(2026, 1, 2)),
                              self._stock_row(date(2026, 1, 5), "10.5")],
            "mixed-providers": [self._stock_row(date(2026, 1, 2), provider="secondary"),
                                self._stock_row(date(2026, 1, 5), "10.5", provider="tertiary")],
            "blank-provider": [self._stock_row(date(2026, 1, 2), provider=""),
                               self._stock_row(date(2026, 1, 5), "10.5", provider="")],
        }

        for name, secondary in variants.items():
            with self.subTest(name=name):
                report = self._evaluate(primary, ProductType.STOCK,
                                        (date(2026, 1, 2), date(2026, 1, 5)),
                                        secondary_records=secondary)
                issue = self._issue(report, "STOCK_CROSS_SOURCE_RECONCILIATION")
                self.assertEqual(IssueOutcome.NOT_APPLICABLE, issue.outcome)
                self.assertFalse(issue.observed["providerEligible"])
                self.assertEqual("primary", issue.observed["primaryProvider"])

    def test_fund_nav_type_estimated_staleness_and_missing_date_rules(self):
        invalid = [
            self._fund_row(date(2026, 1, 2), nav_type="UNIT_NAV"),
            self._fund_row(date(2026, 1, 6), "1.02", nav_type="ACCUMULATED_NAV", estimated=True),
        ]
        report = self._evaluate(invalid, ProductType.MUTUAL_FUND,
                                (date(2026, 1, 2), date(2026, 1, 5), date(2026, 1, 6)))
        self.assertEqual(IssueOutcome.FAIL, self._issue(report, "FUND_NAV_TYPE_CONSISTENCY").outcome)
        self.assertEqual(IssueOutcome.FAIL, self._issue(report, "FUND_ESTIMATED_NAV_FORBIDDEN").outcome)
        self.assertEqual(IssueOutcome.FAIL, self._issue(report, "FUND_UNEXPLAINED_NAV_GAPS").outcome)

        stale = [self._fund_row(date(2026, 1, 5))]
        report = self._evaluate(stale, ProductType.MUTUAL_FUND, (date(2026, 1, 5),),
                                now=datetime(2026, 1, 10, tzinfo=timezone.utc))
        self.assertEqual(IssueOutcome.FAIL, self._issue(report, "FUND_STALENESS").outcome)

    def test_malformed_common_contract_values_fail_closed_without_comparison_exceptions(self):
        valid = self._stock_row(date(2026, 1, 5))
        manifest = self._manifest([valid], ProductType.STOCK)
        malformed_values = (
            Decimal("NaN"), Decimal("Infinity"), "NaN", "Infinity",
        )

        for invalid_number in malformed_values:
            with self.subTest(invalid_number=repr(invalid_number)):
                malformed = copy.deepcopy(valid)
                malformed.update(high=invalid_number, close=invalid_number, volume=invalid_number)
                try:
                    report = DataQualityEngine(self.config, lambda: self.now).evaluate(
                        manifest, [malformed], (date(2026, 1, 5),), integrity_verified=True,
                    )
                except Exception as error:
                    self.fail(f"non-finite numeric value escaped rule evaluation: {error!r}")
                self.assertEqual(IssueOutcome.FAIL,
                                 self._issue(report, "COMMON_POSITIVE_VALUES").outcome)
                self.assertEqual(IssueOutcome.FAIL, self._issue(report, "STOCK_OHLC_RELATION").outcome)

    def test_unparseable_or_naive_timestamp_and_invalid_identity_types_fail_required_fields(self):
        valid = self._stock_row(date(2026, 1, 5))
        manifest = self._manifest([valid], ProductType.STOCK)
        variants = {
            "unparseable-timestamp": {"observed_at": "not-a-timestamp"},
            "naive-timestamp": {"observed_at": datetime(2026, 1, 5)},
            "numeric-provider": {"provider": 123},
            "numeric-product-type": {"product_type": 123},
            "invalid-date": {"data_date": "not-a-date"},
        }

        for name, changes in variants.items():
            with self.subTest(name=name):
                malformed = copy.deepcopy(valid)
                malformed.update(changes)
                report = DataQualityEngine(self.config, lambda: self.now).evaluate(
                    manifest, [malformed], (date(2026, 1, 5),), integrity_verified=True,
                )
                self.assertEqual(IssueOutcome.FAIL,
                                 self._issue(report, "COMMON_REQUIRED_FIELDS").outcome)

    def test_fund_estimated_requires_a_strict_boolean(self):
        records = [self._fund_row(date(2026, 1, 5), estimated="false")]

        report = self._evaluate(records, ProductType.MUTUAL_FUND, (date(2026, 1, 5),))

        self.assertEqual(IssueOutcome.FAIL, self._issue(report, "COMMON_REQUIRED_FIELDS").outcome)
        self.assertEqual(IssueOutcome.FAIL, self._issue(report, "FUND_ESTIMATED_NAV_FORBIDDEN").outcome)

    def test_aggregate_observe_and_enforce_at_exact_warning_threshold(self):
        records = [
            self._stock_row(date(2026, 1, 2), "10", "100"),
            self._stock_row(date(2026, 1, 5), "15", "2500"),
        ]
        expected = (date(2026, 1, 2), date(2026, 1, 5))
        observe = self._evaluate(records, ProductType.STOCK, expected,
                                 config=self._config(enforcement="OBSERVE", warning_threshold=3))
        self.assertEqual(DataQualityStatus.BLOCKED, observe.status)
        self.assertEqual(DataQualityDecision.ALLOW, observe.decision)
        self.assertEqual("QUALITY_OBSERVE_ONLY", observe.evidence_eligibility)

        enforce = self._evaluate(records, ProductType.STOCK, expected,
                                 config=self._config(enforcement="ENFORCE", warning_threshold=3))
        self.assertEqual(DataQualityStatus.BLOCKED, enforce.status)
        self.assertEqual(DataQualityDecision.BLOCK, enforce.decision)
        self.assertIsNone(enforce.evidence_eligibility)

        below_threshold = self._evaluate(records, ProductType.STOCK, expected,
                                         config=self._config(warning_threshold=4))
        self.assertEqual(DataQualityStatus.WARN, below_threshold.status)
        self.assertEqual(DataQualityDecision.ALLOW, below_threshold.decision)
        self.assertIsNone(below_threshold.evidence_eligibility)

    def test_fixed_clock_is_repeatable_requires_timezone_and_does_not_mutate_inputs(self):
        records = [self._stock_row(date(2026, 1, 2)), self._stock_row(date(2026, 1, 5), "10.5")]
        secondary = [self._stock_row(date(2026, 1, 2), provider="secondary"),
                     self._stock_row(date(2026, 1, 5), "10.5", provider="secondary")]
        expected = [date(2026, 1, 2), date(2026, 1, 5)]
        original_records = copy.deepcopy(records)
        original_secondary = copy.deepcopy(secondary)
        original_expected = copy.deepcopy(expected)
        manifest = self._manifest(records, ProductType.STOCK)
        engine = DataQualityEngine(self.config, lambda: self.now)

        first = engine.evaluate(manifest, records, expected, integrity_verified=True,
                                secondary_records=secondary)
        second = engine.evaluate(manifest, records, expected, integrity_verified=True,
                                 secondary_records=secondary)

        self.assertEqual(first, second)
        self.assertEqual(original_records, records)
        self.assertEqual(original_secondary, secondary)
        self.assertEqual(original_expected, expected)
        with self.assertRaisesRegex(ValueError, "timezone-aware"):
            DataQualityEngine(self.config, lambda: datetime(2026, 1, 6)).evaluate(
                manifest, records, expected, integrity_verified=True,
            )


if __name__ == "__main__":
    unittest.main()
