from __future__ import annotations

from datetime import date, datetime
from decimal import Decimal
from enum import StrEnum
from pathlib import Path
from types import MappingProxyType
from typing import Annotated, Literal
from collections.abc import Mapping

from pydantic import (BaseModel, ConfigDict, Field, JsonValue, StrictBool,
                      field_serializer, field_validator, model_validator)


PositiveCount = Annotated[int, Field(strict=True, gt=0)]
Ratio = Annotated[Decimal, Field(strict=True, gt=Decimal("0"), le=Decimal("1"))]
NonBlank = Annotated[str, Field(min_length=1)]


def _freeze_json(value: JsonValue) -> JsonValue:
    if isinstance(value, dict):
        return MappingProxyType({key: _freeze_json(item) for key, item in value.items()})
    if isinstance(value, list):
        return tuple(_freeze_json(item) for item in value)
    return value


def _json_value(value: JsonValue) -> JsonValue:
    if isinstance(value, Mapping):
        return {key: _json_value(item) for key, item in value.items()}
    if isinstance(value, tuple):
        return [_json_value(item) for item in value]
    return value


class StrictContract(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)


class ProductType(StrEnum):
    STOCK = "STOCK"
    MUTUAL_FUND = "MUTUAL_FUND"


class AdjustType(StrEnum):
    QFQ = "QFQ"
    HFQ = "HFQ"
    NONE = "NONE"


class EnforcementMode(StrEnum):
    OBSERVE = "OBSERVE"
    ENFORCE = "ENFORCE"


class IssueSeverity(StrEnum):
    INFO = "INFO"
    WARNING = "WARNING"
    CRITICAL = "CRITICAL"


class IssueOutcome(StrEnum):
    PASS = "PASS"
    FAIL = "FAIL"
    NOT_APPLICABLE = "NOT_APPLICABLE"


class DataQualityStatus(StrEnum):
    PASS = "PASS"
    WARN = "WARN"
    BLOCKED = "BLOCKED"


class DataQualityDecision(StrEnum):
    ALLOW = "ALLOW"
    BLOCK = "BLOCK"


class DataSnapshotContext(StrictContract):
    product_type: ProductType = Field(alias="productType")
    market: NonBlank
    code: NonBlank
    frequency: Literal["DAY"]
    adjust_type: AdjustType = Field(alias="adjustType")
    provider: NonBlank
    adapter_version: NonBlank = Field(alias="adapterVersion")
    requested_start_date: date = Field(alias="requestedStartDate")
    requested_end_date: date = Field(alias="requestedEndDate")
    fetched_at: datetime = Field(alias="fetchedAt")

    @model_validator(mode="after")
    def fund_requires_unadjusted_nav(self) -> "DataSnapshotContext":
        if self.product_type is ProductType.MUTUAL_FUND and self.adjust_type is not AdjustType.NONE:
            raise ValueError("MUTUAL_FUND requests require adjustType=NONE")
        if self.requested_start_date > self.requested_end_date:
            raise ValueError("requested start date cannot be after requested end date")
        if self.fetched_at.tzinfo is None or self.fetched_at.utcoffset() is None:
            raise ValueError("fetchedAt must be timezone-aware")
        return self


class StockQualityThresholds(StrictContract):
    max_stale_trading_days: PositiveCount = Field(alias="maxStaleTradingDays")
    max_missing_ratio: Ratio = Field(alias="maxMissingRatio")
    cross_source_enabled: StrictBool = Field(alias="crossSourceEnabled")
    minimum_overlap_days: PositiveCount = Field(alias="minimumOverlapDays")
    max_price_deviation_ratio: Ratio = Field(alias="maxPriceDeviationRatio")
    extreme_return_ratio: Ratio = Field(alias="extremeReturnRatio")
    corporate_action_evidence_return_ratio: Ratio = Field(alias="corporateActionEvidenceReturnRatio")
    extreme_volume_multiplier: PositiveCount = Field(alias="extremeVolumeMultiplier")
    require_adjustment_factor: StrictBool = Field(default=True, alias="requireAdjustmentFactor")


class FundQualityThresholds(StrictContract):
    max_stale_calendar_days: PositiveCount = Field(alias="maxStaleCalendarDays")
    max_missing_nav_ratio: Ratio = Field(alias="maxMissingNavRatio")


class QualityProviderSettings(StrictContract):
    enabled: tuple[NonBlank, ...] = Field(min_length=1)
    maximum_batches: PositiveCount = Field(alias="maximumBatches")

    @field_validator("enabled", mode="after")
    @classmethod
    def normalize_enabled(cls, value: tuple[str, ...]) -> tuple[str, ...]:
        normalized = tuple(item.strip().upper() for item in value)
        if any(not item for item in normalized):
            raise ValueError("enabled quality providers must be non-blank")
        if len(set(normalized)) != len(normalized):
            raise ValueError("enabled quality providers must be unique")
        return normalized


class DataQualityConfig(StrictContract):
    version: NonBlank
    rule_set: NonBlank = Field(alias="ruleSet")
    schema_version: NonBlank = Field(alias="schemaVersion")
    enforcement_mode: EnforcementMode = Field(alias="enforcementMode")
    storage_root_env: NonBlank = Field(alias="storageRootEnv")
    storage_root: Path = Field(alias="storageRoot")
    orphan_retention_hours: PositiveCount = Field(alias="orphanRetentionHours")
    warning_failures_to_block: PositiveCount = Field(alias="warningFailuresToBlock")
    providers: QualityProviderSettings | None = None
    stock: StockQualityThresholds
    fund: FundQualityThresholds


class DataQualityManifest(StrictContract):
    dataset_version: NonBlank = Field(alias="datasetVersion")
    product_type: ProductType = Field(alias="productType")
    market: NonBlank
    code: NonBlank
    frequency: Literal["DAY"]
    adjust_type: AdjustType = Field(alias="adjustType")
    provider: NonBlank
    adapter_version: NonBlank = Field(alias="adapterVersion")
    requested_start_date: date = Field(alias="requestedStartDate")
    requested_end_date: date = Field(alias="requestedEndDate")
    sample_start_date: date = Field(alias="sampleStartDate")
    sample_end_date: date = Field(alias="sampleEndDate")
    record_count: PositiveCount = Field(alias="recordCount")
    fetched_at: datetime = Field(alias="fetchedAt")
    content_hash: NonBlank = Field(alias="contentHash")
    parquet_file_hash: NonBlank = Field(alias="parquetFileHash")
    storage_format: Literal["PARQUET"] = Field(alias="storageFormat")
    storage_uri: NonBlank = Field(alias="storageUri")
    schema_version: NonBlank = Field(alias="schemaVersion")

    @model_validator(mode="after")
    def ranges_are_ordered(self) -> "DataQualityManifest":
        if self.product_type is ProductType.MUTUAL_FUND and self.adjust_type is not AdjustType.NONE:
            raise ValueError("MUTUAL_FUND manifests require adjustType=NONE")
        if self.requested_start_date > self.requested_end_date:
            raise ValueError("requested start date cannot be after requested end date")
        if self.sample_start_date > self.sample_end_date:
            raise ValueError("sample start date cannot be after sample end date")
        return self


class DataQualityIssue(StrictContract):
    rule_code: NonBlank = Field(alias="ruleCode")
    severity: IssueSeverity
    outcome: IssueOutcome
    message: NonBlank
    observed: JsonValue | None = None
    expected: JsonValue | None = None
    affected_dates: tuple[date, ...] = Field(default=(), alias="affectedDates")

    @field_validator("observed", "expected", mode="after")
    @classmethod
    def freeze_json_fields(cls, value: JsonValue | None) -> JsonValue | None:
        return _freeze_json(value) if value is not None else None

    @field_serializer("observed", "expected", when_used="json")
    def serialize_json_fields(self, value: JsonValue | None) -> JsonValue | None:
        return _json_value(value) if value is not None else None


class DataQualityReport(StrictContract):
    dataset_version: NonBlank = Field(alias="datasetVersion")
    quality_rule_set_version: NonBlank = Field(alias="qualityRuleSetVersion")
    status: DataQualityStatus
    decision: DataQualityDecision
    enforcement_mode: EnforcementMode = Field(alias="enforcementMode")
    evaluated_at: datetime = Field(alias="evaluatedAt")
    issues: tuple[DataQualityIssue, ...]
    summary: Mapping[str, JsonValue]
    evidence_eligibility: Literal["QUALITY_OBSERVE_ONLY"] | None = Field(default=None, alias="evidenceEligibility")

    @field_validator("summary", mode="after")
    @classmethod
    def freeze_summary(cls, value: Mapping[str, JsonValue]) -> Mapping[str, JsonValue]:
        return MappingProxyType({key: _freeze_json(item) for key, item in value.items()})

    @field_serializer("summary", when_used="json")
    def serialize_summary(self, value: Mapping[str, JsonValue]) -> dict[str, JsonValue]:
        return {key: _json_value(item) for key, item in value.items()}
