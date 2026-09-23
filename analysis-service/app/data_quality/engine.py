from __future__ import annotations

from collections.abc import Callable, Iterable, Mapping, Sequence
from datetime import date, datetime
from decimal import Decimal, InvalidOperation
from typing import Any
from zoneinfo import ZoneInfo

from .models import (
    AdjustType,
    DataQualityConfig,
    DataQualityDecision,
    DataQualityIssue,
    DataQualityManifest,
    DataQualityReport,
    DataQualityStatus,
    EnforcementMode,
    IssueOutcome,
    ProductType,
)
from .rules import RULES, RULE_BY_CODE


Record = Mapping[str, Any]


class DataQualityEngine:
    def __init__(self, config: DataQualityConfig, clock: Callable[[], datetime]):
        if not isinstance(config, DataQualityConfig):
            raise ValueError("config must be a DataQualityConfig")
        if not callable(clock):
            raise ValueError("clock must be callable")
        self._config = config
        self._clock = clock

    def evaluate(
        self,
        manifest: DataQualityManifest,
        records: Sequence[Record],
        expected_dates: Iterable[date],
        *,
        integrity_verified: bool,
        secondary_records: Sequence[Record] | None = None,
    ) -> DataQualityReport:
        if not isinstance(manifest, DataQualityManifest):
            raise ValueError("manifest must be a DataQualityManifest")
        if not isinstance(records, Sequence) or isinstance(records, (str, bytes)) or not records:
            raise ValueError("records must be a non-empty sequence")
        if type(integrity_verified) is not bool:
            raise ValueError("integrity_verified must be a boolean")
        if secondary_records is not None and (
            not isinstance(secondary_records, Sequence) or isinstance(secondary_records, (str, bytes))
        ):
            raise ValueError("secondary_records must be a sequence when provided")
        calendar = tuple(expected_dates)
        if any(isinstance(day, datetime) or not isinstance(day, date) for day in calendar):
            raise ValueError("expected_dates must contain dates without times")
        if tuple(sorted(set(calendar))) != calendar:
            raise ValueError("expected_dates must be unique and ordered")
        evaluated_at = self._clock()
        if not isinstance(evaluated_at, datetime) or evaluated_at.tzinfo is None or evaluated_at.utcoffset() is None:
            raise ValueError("clock must return a timezone-aware datetime")

        primary = tuple(_normalize_record(record) for record in records)
        secondary = tuple(_normalize_record(record) for record in secondary_records) \
            if secondary_records is not None else None
        evaluators = self._evaluators(manifest, primary, calendar, integrity_verified, secondary, evaluated_at)
        issues: list[DataQualityIssue] = []
        for rule in RULES:
            if rule.product_type is not None and rule.product_type is not manifest.product_type:
                issues.append(self._issue(rule.code, IssueOutcome.NOT_APPLICABLE,
                                          f"{rule.code} does not apply to {manifest.product_type.value}"))
            else:
                issues.append(evaluators[rule.code]())

        critical_failures = sum(
            issue.outcome is IssueOutcome.FAIL and issue.severity.value == "CRITICAL" for issue in issues
        )
        warning_failures = sum(
            issue.outcome is IssueOutcome.FAIL and issue.severity.value == "WARNING" for issue in issues
        )
        blocking_warning_groups = {
            RULE_BY_CODE[issue.rule_code].blocking_group or issue.rule_code
            for issue in issues
            if issue.outcome is IssueOutcome.FAIL and issue.severity.value == "WARNING"
        }
        blocking_warning_count = (len(blocking_warning_groups) if self._config.evidence_aware_rules
                                  else warning_failures)
        failure_count = critical_failures + warning_failures
        if critical_failures or blocking_warning_count >= self._config.warning_failures_to_block:
            status = DataQualityStatus.BLOCKED
        elif failure_count:
            status = DataQualityStatus.WARN
        else:
            status = DataQualityStatus.PASS
        decision = (
            DataQualityDecision.BLOCK
            if status is DataQualityStatus.BLOCKED and self._config.enforcement_mode is EnforcementMode.ENFORCE
            else DataQualityDecision.ALLOW
        )
        eligibility = (
            "QUALITY_OBSERVE_ONLY"
            if status is DataQualityStatus.BLOCKED and self._config.enforcement_mode is EnforcementMode.OBSERVE
            else None
        )
        summary = {
            "totalRules": len(issues),
            "passedRules": sum(issue.outcome is IssueOutcome.PASS for issue in issues),
            "failedRules": failure_count,
            "notApplicableRules": sum(issue.outcome is IssueOutcome.NOT_APPLICABLE for issue in issues),
            "criticalFailures": critical_failures,
            "warningFailures": warning_failures,
        }
        if self._config.evidence_aware_rules:
            summary["blockingWarningGroups"] = blocking_warning_count
        return DataQualityReport(
            dataset_version=manifest.dataset_version,
            quality_rule_set_version=self._config.rule_set,
            status=status,
            decision=decision,
            enforcement_mode=self._config.enforcement_mode,
            evaluated_at=evaluated_at,
            issues=tuple(issues),
            summary=summary,
            evidence_eligibility=eligibility,
        )

    def _evaluators(
        self,
        manifest: DataQualityManifest,
        records: tuple[Record, ...],
        calendar: tuple[date, ...],
        integrity_verified: bool,
        secondary: tuple[Record, ...] | None,
        evaluated_at: datetime,
    ) -> dict[str, Callable[[], DataQualityIssue]]:
        return {
            "COMMON_REQUIRED_FIELDS": lambda: self._required_fields(manifest, records),
            "COMMON_UNIQUE_ORDERED_DATES": lambda: self._unique_ordered_dates(records),
            "COMMON_POSITIVE_VALUES": lambda: self._positive_values(manifest, records),
            "MANIFEST_CONTENT_INTEGRITY": lambda: self._manifest_integrity(integrity_verified),
            "STOCK_OHLC_RELATION": lambda: self._stock_ohlc(records),
            "STOCK_ADJUSTMENT_CONSISTENCY": lambda: self._stock_adjustment(manifest, records, secondary),
            "STOCK_STALENESS": lambda: self._stock_staleness(records, calendar, evaluated_at),
            "STOCK_UNEXPLAINED_TRADING_GAPS": lambda: self._missing_dates(
                "STOCK_UNEXPLAINED_TRADING_GAPS", manifest, records, calendar,
                self._config.stock.max_missing_ratio, evaluated_at,
            ),
            "STOCK_EXTREME_RETURN": lambda: self._stock_extreme_returns(manifest, records),
            "STOCK_EXTREME_VOLUME": lambda: self._stock_extreme_volumes(records),
            "STOCK_CORPORATE_ACTION_EVIDENCE": lambda: self._corporate_action_evidence(manifest, records),
            "STOCK_SECONDARY_SOURCE_AVAILABILITY": lambda: self._secondary_source_availability(
                manifest, records, secondary,
            ),
            "STOCK_CROSS_SOURCE_RECONCILIATION": lambda: self._cross_source(manifest, records, secondary),
            "FUND_NAV_TYPE_CONSISTENCY": lambda: self._fund_nav_type(records),
            "FUND_ESTIMATED_NAV_FORBIDDEN": lambda: self._fund_estimated(records),
            "FUND_STALENESS": lambda: self._fund_staleness(records, evaluated_at),
            "FUND_UNEXPLAINED_NAV_GAPS": lambda: self._missing_dates(
                "FUND_UNEXPLAINED_NAV_GAPS", manifest, records, calendar,
                self._config.fund.max_missing_nav_ratio, evaluated_at,
            ),
        }

    def _required_fields(self, manifest: DataQualityManifest, records: tuple[Record, ...]) -> DataQualityIssue:
        identity_fields = (
            "product_code", "product_type", "market", "frequency", "adjust_type", "provider",
            "adapter_version",
        )
        missing: list[str] = []
        for index, row in enumerate(records):
            for field in identity_fields:
                if not _nonblank(row.get(field)):
                    missing.append(f"{index}:{field}")
            if _record_date(row) is None:
                missing.append(f"{index}:data_date")
            observed_at = row.get("observed_at")
            if not isinstance(observed_at, datetime) or observed_at.tzinfo is None or observed_at.utcoffset() is None:
                missing.append(f"{index}:observed_at")
            if manifest.product_type is ProductType.STOCK:
                for field in ("open", "high", "low", "close", "volume"):
                    if row.get(field) is None:
                        missing.append(f"{index}:{field}")
            else:
                if row.get("nav") is None:
                    missing.append(f"{index}:nav")
                if not _nonblank(row.get("nav_type")):
                    missing.append(f"{index}:nav_type")
                if type(row.get("estimated")) is not bool:
                    missing.append(f"{index}:estimated")
        if missing:
            return self._issue("COMMON_REQUIRED_FIELDS", IssueOutcome.FAIL, "required canonical fields are missing",
                               observed={"missing": missing}, expected={"missingCount": 0})
        return self._issue("COMMON_REQUIRED_FIELDS", IssueOutcome.PASS, "all required canonical fields are present",
                           observed={"recordCount": len(records)}, expected={"missingCount": 0})

    def _unique_ordered_dates(self, records: tuple[Record, ...]) -> DataQualityIssue:
        days = [row.get("data_date") for row in records]
        valid = all(isinstance(day, date) and not isinstance(day, datetime) for day in days)
        unique_ordered = valid and all(left < right for left, right in zip(days, days[1:]))
        if not unique_ordered:
            rendered = [day.isoformat() if isinstance(day, date) else str(day) for day in days]
            return self._issue("COMMON_UNIQUE_ORDERED_DATES", IssueOutcome.FAIL,
                               "record dates must be unique and strictly ascending",
                               observed={"dates": rendered}, expected={"uniqueAndOrdered": True})
        return self._issue("COMMON_UNIQUE_ORDERED_DATES", IssueOutcome.PASS,
                           "record dates are unique and strictly ascending",
                           observed={"recordCount": len(days)}, expected={"uniqueAndOrdered": True})

    def _positive_values(self, manifest: DataQualityManifest, records: tuple[Record, ...]) -> DataQualityIssue:
        affected: list[date] = []
        invalid_fields: list[str] = []
        for row in records:
            if manifest.product_type is ProductType.STOCK:
                row_invalid = [field for field in ("open", "high", "low", "close")
                               if not _is_positive(row.get(field))]
                volume = row.get("volume")
                suspended_zero = row.get("trading_status") == "SUSPENDED" and _is_zero(volume)
                if not _is_positive(volume) and not suspended_zero:
                    row_invalid.append("volume")
            else:
                row_invalid = [] if _is_positive(row.get("nav")) else ["nav"]
            if row_invalid:
                invalid_fields.extend(row_invalid)
                day = _record_date(row)
                if day is not None:
                    affected.append(day)
        if invalid_fields:
            return self._issue("COMMON_POSITIVE_VALUES", IssueOutcome.FAIL,
                               "product numeric values must be positive",
                               observed={"invalidValueCount": len(invalid_fields)}, expected={"minimumExclusive": "0"},
                               affected_dates=affected)
        return self._issue("COMMON_POSITIVE_VALUES", IssueOutcome.PASS, "product numeric values are positive",
                           observed={"recordCount": len(records)}, expected={"minimumExclusive": "0"})

    def _manifest_integrity(self, integrity_verified: bool) -> DataQualityIssue:
        outcome = IssueOutcome.PASS if integrity_verified else IssueOutcome.FAIL
        message = "snapshot manifest integrity was verified" if integrity_verified else "snapshot manifest integrity was not verified"
        return self._issue("MANIFEST_CONTENT_INTEGRITY", outcome, message,
                           observed={"integrityVerified": integrity_verified}, expected={"integrityVerified": True})

    def _stock_ohlc(self, records: tuple[Record, ...]) -> DataQualityIssue:
        affected: list[date] = []
        invalid_count = 0
        for row in records:
            values = tuple(row.get(field) for field in ("open", "high", "low", "close"))
            valid = all(_is_finite_decimal(value) for value in values)
            if valid:
                open_value, high, low, close = values
                valid = low <= min(open_value, close) <= max(open_value, close) <= high
            if not valid:
                invalid_count += 1
                if (day := _record_date(row)) is not None:
                    affected.append(day)
        outcome = IssueOutcome.FAIL if invalid_count else IssueOutcome.PASS
        return self._issue("STOCK_OHLC_RELATION", outcome,
                           "invalid OHLC relation detected" if invalid_count else "OHLC relations are valid",
                           observed={"invalidBarCount": invalid_count},
                           expected={"relation": "low <= open,close <= high"}, affected_dates=affected)

    def _stock_adjustment(
        self, manifest: DataQualityManifest, records: tuple[Record, ...], secondary: tuple[Record, ...] | None,
    ) -> DataQualityIssue:
        batches = records + (secondary or ())
        invalid: list[date] = []
        for row in batches:
            adjust_matches = row.get("adjust_type") == manifest.adjust_type.value
            factor = row.get("adjustment_factor")
            factor_valid = (
                manifest.adjust_type is AdjustType.NONE
                or _is_positive(factor)
                or (factor is None and not self._config.stock.require_adjustment_factor)
            )
            if not adjust_matches or not factor_valid:
                if (day := _record_date(row)) is not None:
                    invalid.append(day)
        outcome = IssueOutcome.FAIL if invalid else IssueOutcome.PASS
        return self._issue("STOCK_ADJUSTMENT_CONSISTENCY", outcome,
                           "mixed or invalid adjustment data detected" if invalid else "adjustment data is consistent",
                           observed={"invalidRecordCount": len(invalid), "adjustType": manifest.adjust_type.value},
                           expected={"adjustType": manifest.adjust_type.value,
                                     "positiveFactorRequired": self._config.stock.require_adjustment_factor},
                           affected_dates=invalid)

    def _stock_staleness(
        self, records: tuple[Record, ...], calendar: tuple[date, ...], evaluated_at: datetime,
    ) -> DataQualityIssue:
        actual_days = [day for row in records if (day := _record_date(row)) is not None]
        if not actual_days or not calendar:
            return self._issue("STOCK_STALENESS", IssueOutcome.NOT_APPLICABLE,
                               "stock staleness needs actual and expected trading dates",
                               observed={"actualDates": len(actual_days), "expectedDates": len(calendar)},
                               expected={"maximumTradingDays": self._config.stock.max_stale_trading_days})
        latest = max(actual_days)
        stale_days = sum(latest < day <= evaluated_at.date() for day in calendar)
        failed = stale_days > self._config.stock.max_stale_trading_days
        return self._issue("STOCK_STALENESS", IssueOutcome.FAIL if failed else IssueOutcome.PASS,
                           "stock data is stale" if failed else "stock data is current for the supplied calendar",
                           observed={"staleTradingDays": stale_days, "latestDate": latest.isoformat()},
                           expected={"maximumTradingDays": self._config.stock.max_stale_trading_days},
                           affected_dates=(latest,) if failed else ())

    def _missing_dates(
        self, code: str, manifest: DataQualityManifest, records: tuple[Record, ...], calendar: tuple[date, ...],
        maximum_ratio: Decimal, evaluated_at: datetime,
    ) -> DataQualityIssue:
        window_start = manifest.requested_start_date
        window_end = manifest.requested_end_date
        # The current session's daily bar/NAV may not have been published yet.
        market_date = evaluated_at.astimezone(ZoneInfo(
            self._config.market_time_zones.get(manifest.market, "UTC"))).date()
        expected = tuple(day for day in calendar if window_start <= day <= window_end
                         and (not self._config.evidence_aware_rules or day < market_date))
        if not expected:
            return self._issue(code, IssueOutcome.NOT_APPLICABLE, "no expected dates fall in the requested range",
                               observed={"expectedDateCount": 0}, expected={"maximumMissingRatio": str(maximum_ratio)})
        actual = {_record_date(row) for row in records}
        missing = tuple(day for day in expected if day not in actual)
        ratio = Decimal(len(missing)) / Decimal(len(expected))
        failed = ratio > maximum_ratio
        return self._issue(code, IssueOutcome.FAIL if failed else IssueOutcome.PASS,
                           "unexplained dates exceed the configured ratio" if failed else "date coverage is within the configured ratio",
                           observed={"missingDateCount": len(missing), "expectedDateCount": len(expected),
                                     "missingRatio": str(ratio)},
                           expected={"maximumMissingRatio": str(maximum_ratio)}, affected_dates=missing)

    def _stock_extreme_returns(self, manifest: DataQualityManifest, records: tuple[Record, ...]) -> DataQualityIssue:
        if self._config.evidence_aware_rules and manifest.adjust_type is not AdjustType.NONE and any(
            not _is_positive(row.get("adjustment_factor")) for row in records
        ):
            return self._issue("STOCK_EXTREME_RETURN", IssueOutcome.NOT_APPLICABLE,
                               "adjusted prices lack factors needed to verify historical returns",
                               observed={"adjustType": manifest.adjust_type.value,
                                         "missingFactorCount": sum(not _is_positive(row.get("adjustment_factor"))
                                                                   for row in records)},
                               expected={"positiveAdjustmentFactors": True})
        affected = _ratio_change_dates(records, "close", self._config.stock.extreme_return_ratio)
        return self._issue("STOCK_EXTREME_RETURN", IssueOutcome.FAIL if affected else IssueOutcome.PASS,
                           "extreme stock returns detected" if affected else "stock returns are within the configured ratio",
                           observed={"extremeReturnCount": len(affected)},
                           expected={"maximumAbsoluteReturnRatio": str(self._config.stock.extreme_return_ratio)},
                           affected_dates=affected)

    def _stock_extreme_volumes(self, records: tuple[Record, ...]) -> DataQualityIssue:
        ordered = _ordered_records(records)
        affected: list[date] = []
        for previous, current in zip(ordered, ordered[1:]):
            previous_volume, current_volume = previous.get("volume"), current.get("volume")
            if _is_positive(previous_volume) and _is_positive(current_volume) and \
                    current_volume > previous_volume * self._config.stock.extreme_volume_multiplier:
                if (day := _record_date(current)) is not None:
                    affected.append(day)
        return self._issue("STOCK_EXTREME_VOLUME", IssueOutcome.FAIL if affected else IssueOutcome.PASS,
                           "extreme stock volume detected" if affected else "stock volumes are within the configured multiplier",
                           observed={"extremeVolumeCount": len(affected)},
                           expected={"maximumMultiplier": self._config.stock.extreme_volume_multiplier},
                           affected_dates=affected)

    def _corporate_action_evidence(self, manifest: DataQualityManifest,
                                   records: tuple[Record, ...]) -> DataQualityIssue:
        if self._config.evidence_aware_rules and manifest.adjust_type is not AdjustType.NONE and any(
            not _is_positive(row.get("adjustment_factor")) for row in records
        ) and not any(_nonblank(row.get("corporate_action_reference")) for row in records):
            return self._issue("STOCK_CORPORATE_ACTION_EVIDENCE", IssueOutcome.NOT_APPLICABLE,
                               "provider supplied neither adjustment factors nor corporate-action evidence",
                               observed={"adjustType": manifest.adjust_type.value,
                                         "evidenceAvailable": False},
                               expected={"evidenceAvailable": True})
        extreme_dates = set(_ratio_change_dates(
            records, "close", self._config.stock.corporate_action_evidence_return_ratio,
        ))
        missing = tuple(
            day for row in _ordered_records(records)
            if (day := _record_date(row)) in extreme_dates and not _nonblank(row.get("corporate_action_reference"))
        )
        return self._issue("STOCK_CORPORATE_ACTION_EVIDENCE", IssueOutcome.FAIL if missing else IssueOutcome.PASS,
                           "corporate-action evidence is missing" if missing else "corporate-action evidence requirements are satisfied",
                           observed={"missingEvidenceCount": len(missing)},
                           expected={"evidenceReturnRatio": str(self._config.stock.corporate_action_evidence_return_ratio)},
                           affected_dates=missing)

    def _secondary_source_availability(
        self, manifest: DataQualityManifest, records: tuple[Record, ...], secondary: tuple[Record, ...] | None,
    ) -> DataQualityIssue:
        code = "STOCK_SECONDARY_SOURCE_AVAILABILITY"
        if not self._config.stock.cross_source_enabled:
            return self._issue(code, IssueOutcome.NOT_APPLICABLE, "cross-source reconciliation is disabled",
                               observed={"enabled": False}, expected={"enabled": True})
        if secondary is None:
            return self._issue(code, IssueOutcome.FAIL, "secondary market-data source is unavailable",
                               observed={"secondaryBatch": False}, expected={"secondaryBatch": True})
        primary_providers = tuple(row.get("provider") for row in records)
        secondary_providers = tuple(row.get("provider") for row in secondary)
        secondary_provider = secondary_providers[0] if secondary_providers else None
        identity = ("product_code", "product_type", "market", "frequency")
        expected_identity = (manifest.code, manifest.product_type.value, manifest.market, manifest.frequency)
        eligible = (
            all(provider == manifest.provider for provider in primary_providers)
            and _nonblank(secondary_provider)
            and all(provider == secondary_provider for provider in secondary_providers)
            and secondary_provider != manifest.provider
            and all(tuple(row.get(field) for field in identity) == expected_identity for row in secondary)
        )
        return self._issue(
            code,
            IssueOutcome.PASS if eligible else IssueOutcome.FAIL,
            "secondary market-data source is available" if eligible
            else "secondary market-data source is invalid or does not match the primary dataset",
            observed={"secondaryBatch": True, "providerEligible": eligible,
                      "secondaryProviders": sorted(set(map(str, secondary_providers)))},
            expected={"singleDistinctSecondaryProvider": True},
        )

    def _cross_source(
        self, manifest: DataQualityManifest, records: tuple[Record, ...], secondary: tuple[Record, ...] | None,
    ) -> DataQualityIssue:
        code = "STOCK_CROSS_SOURCE_RECONCILIATION"
        threshold = self._config.stock
        if not threshold.cross_source_enabled:
            return self._issue(code, IssueOutcome.NOT_APPLICABLE, "cross-source reconciliation is disabled",
                               observed={"enabled": False}, expected={"enabled": True})
        if secondary is None:
            return self._issue(code, IssueOutcome.NOT_APPLICABLE, "secondary-source batch was not supplied",
                               observed={"secondaryBatch": False}, expected={"secondaryBatch": True})
        identity = ("product_code", "product_type", "market", "frequency")
        expected_identity = (manifest.code, manifest.product_type.value, manifest.market, manifest.frequency)
        if any(tuple(row.get(field) for field in identity) != expected_identity for row in secondary):
            return self._issue(code, IssueOutcome.NOT_APPLICABLE, "secondary-source identity does not match",
                               observed={"identityMatched": False}, expected={"identityMatched": True})
        primary_provider_values = tuple(row.get("provider") for row in records)
        secondary_provider_values = tuple(row.get("provider") for row in secondary)
        secondary_provider = secondary_provider_values[0] if secondary_provider_values else None
        provider_eligible = (
            all(provider == manifest.provider for provider in primary_provider_values)
            and _nonblank(secondary_provider)
            and all(provider == secondary_provider for provider in secondary_provider_values)
            and secondary_provider != manifest.provider
        )
        if not provider_eligible:
            return self._issue(code, IssueOutcome.NOT_APPLICABLE,
                               "secondary provider must be one non-blank source distinct from primary",
                               observed={
                                   "providerEligible": False,
                                   "primaryProvider": manifest.provider,
                                   "primaryProviders": sorted(set(map(str, primary_provider_values))),
                                   "secondaryProviders": sorted(set(map(str, secondary_provider_values))),
                               },
                               expected={"singleDistinctSecondaryProvider": True})
        if any(row.get("adjust_type") != manifest.adjust_type.value for row in records + secondary):
            return self._issue(code, IssueOutcome.NOT_APPLICABLE, "mixed adjustment data is never compared",
                               observed={"adjustmentMatched": False}, expected={"adjustType": manifest.adjust_type.value})
        if self._config.evidence_aware_rules and manifest.adjust_type is not AdjustType.NONE and any(
            not _is_positive(row.get("adjustment_factor")) for row in records + secondary
        ):
            return self._issue(code, IssueOutcome.NOT_APPLICABLE,
                               "adjusted prices cannot be compared without matching adjustment evidence",
                               observed={"adjustmentEvidenceAvailable": False},
                               expected={"adjustmentEvidenceAvailable": True})

        window_start = manifest.requested_start_date
        window_end = manifest.requested_end_date
        primary_by_date = _positive_values_by_date(records, "close", window_start, window_end)
        secondary_by_date = _positive_values_by_date(secondary, "close", window_start, window_end)
        overlap = tuple(sorted(primary_by_date.keys() & secondary_by_date.keys()))
        if len(overlap) < threshold.minimum_overlap_days:
            return self._issue(code, IssueOutcome.NOT_APPLICABLE, "cross-source overlap is below the configured minimum",
                               observed={"overlapDays": len(overlap)},
                               expected={"minimumOverlapDays": threshold.minimum_overlap_days})
        deviating = tuple(
            day for day in overlap
            if abs(primary_by_date[day] - secondary_by_date[day]) / primary_by_date[day]
            > threshold.max_price_deviation_ratio
        )
        return self._issue(code, IssueOutcome.FAIL if deviating else IssueOutcome.PASS,
                           "cross-source price deviation exceeds the configured ratio" if deviating
                           else "cross-source prices are within the configured ratio",
                           observed={"overlapDays": len(overlap), "deviatingDays": len(deviating)},
                           expected={"minimumOverlapDays": threshold.minimum_overlap_days,
                                     "maximumPriceDeviationRatio": str(threshold.max_price_deviation_ratio)},
                           affected_dates=deviating)

    def _fund_nav_type(self, records: tuple[Record, ...]) -> DataQualityIssue:
        nav_types = {row.get("nav_type") for row in records if _nonblank(row.get("nav_type"))}
        missing = sum(not _nonblank(row.get("nav_type")) for row in records)
        failed = len(nav_types) != 1 or missing > 0
        return self._issue("FUND_NAV_TYPE_CONSISTENCY", IssueOutcome.FAIL if failed else IssueOutcome.PASS,
                           "fund NAV types are mixed or missing" if failed else "fund NAV type is consistent",
                           observed={"navTypes": sorted(str(value) for value in nav_types), "missingCount": missing},
                           expected={"distinctNavTypeCount": 1})

    def _fund_estimated(self, records: tuple[Record, ...]) -> DataQualityIssue:
        invalid_rows = tuple(
            row for row in records if type(row.get("estimated")) is not bool or row.get("estimated") is True
        )
        affected = tuple(day for row in invalid_rows if (day := _record_date(row)) is not None)
        return self._issue("FUND_ESTIMATED_NAV_FORBIDDEN", IssueOutcome.FAIL if invalid_rows else IssueOutcome.PASS,
                           "estimated or invalid fund NAV flags are forbidden" if invalid_rows else "fund NAV values are not estimated",
                           observed={"estimatedOrInvalidCount": len(invalid_rows)},
                           expected={"estimated": False, "strictBoolean": True},
                           affected_dates=affected)

    def _fund_staleness(self, records: tuple[Record, ...], evaluated_at: datetime) -> DataQualityIssue:
        days = [day for row in records if (day := _record_date(row)) is not None]
        if not days:
            return self._issue("FUND_STALENESS", IssueOutcome.NOT_APPLICABLE, "fund staleness needs an actual NAV date",
                               observed={"actualDates": 0},
                               expected={"maximumCalendarDays": self._config.fund.max_stale_calendar_days})
        latest = max(days)
        stale_days = max((evaluated_at.date() - latest).days, 0)
        failed = stale_days > self._config.fund.max_stale_calendar_days
        return self._issue("FUND_STALENESS", IssueOutcome.FAIL if failed else IssueOutcome.PASS,
                           "fund NAV data is stale" if failed else "fund NAV data is current",
                           observed={"staleCalendarDays": stale_days, "latestDate": latest.isoformat()},
                           expected={"maximumCalendarDays": self._config.fund.max_stale_calendar_days},
                           affected_dates=(latest,) if failed else ())

    @staticmethod
    def _issue(
        code: str,
        outcome: IssueOutcome,
        message: str,
        *,
        observed: dict[str, Any] | None = None,
        expected: dict[str, Any] | None = None,
        affected_dates: Iterable[date] = (),
    ) -> DataQualityIssue:
        return DataQualityIssue(
            rule_code=code,
            severity=RULE_BY_CODE[code].severity,
            outcome=outcome,
            message=message,
            observed=observed,
            expected=expected,
            affected_dates=tuple(sorted(set(affected_dates))),
        )


def _record_date(record: Record) -> date | None:
    value = record.get("data_date")
    return value if isinstance(value, date) and not isinstance(value, datetime) else None


def _normalize_record(record: Record) -> dict[str, Any]:
    normalized = dict(record)
    raw_date = normalized.get("data_date")
    if isinstance(raw_date, str):
        try:
            normalized["data_date"] = date.fromisoformat(raw_date)
        except ValueError:
            pass
    raw_timestamp = normalized.get("observed_at")
    if isinstance(raw_timestamp, str):
        try:
            normalized["observed_at"] = datetime.fromisoformat(raw_timestamp.replace("Z", "+00:00"))
        except ValueError:
            pass
    for field in ("open", "high", "low", "close", "volume", "nav", "adjustment_factor"):
        raw_value = normalized.get(field)
        if isinstance(raw_value, str):
            try:
                normalized[field] = Decimal(raw_value)
            except (InvalidOperation, ValueError):
                pass
    return normalized


def _is_positive(value: Any) -> bool:
    return _is_finite_decimal(value) and value > 0


def _is_zero(value: Any) -> bool:
    return _is_finite_decimal(value) and value == 0


def _is_finite_decimal(value: Any) -> bool:
    return isinstance(value, Decimal) and value.is_finite()


def _nonblank(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip())


def _ordered_records(records: tuple[Record, ...]) -> tuple[Record, ...]:
    return tuple(sorted(records, key=lambda row: _record_date(row) or date.min))


def _ratio_change_dates(records: tuple[Record, ...], field: str, threshold: Decimal) -> tuple[date, ...]:
    affected: list[date] = []
    ordered = _ordered_records(records)
    for previous, current in zip(ordered, ordered[1:]):
        previous_value, current_value = previous.get(field), current.get(field)
        if _is_positive(previous_value) and isinstance(current_value, Decimal) and current_value.is_finite():
            if abs(current_value / previous_value - Decimal("1")) > threshold:
                if (day := _record_date(current)) is not None:
                    affected.append(day)
    return tuple(affected)


def _positive_values_by_date(
    records: tuple[Record, ...], field: str, start: date, end: date,
) -> dict[date, Decimal]:
    result: dict[date, Decimal] = {}
    for row in records:
        day, value = _record_date(row), row.get(field)
        if day is not None and start <= day <= end and _is_positive(value):
            result[day] = value
    return result
