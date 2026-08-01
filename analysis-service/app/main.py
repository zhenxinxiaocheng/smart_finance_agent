from __future__ import annotations

import os
import threading
from datetime import date, datetime, timezone
from typing import Any, Literal

from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import AliasChoices, BaseModel, ConfigDict, Field, field_validator, model_validator

from .analysis import analyze_fund, analyze_fundamentals, analyze_technical, backtest_horizons
from .data_quality import (
    AdjustType,
    DataQualityService,
    ProductType,
    SnapshotIntegrityError,
    SnapshotNotFoundError,
)

from .providers import (
    ProviderRegistry,
    ProviderUnavailable,
    akshare_fx_rates,
    fetch_a_share_trade_calendar,
    fetch_benchmark_history,
    discover_fund_research_universe,
    fetch_realtime_stock_quote,
    fetch_stock_fundamentals,
    resolve_product_metadata,
)
from .quant.config import load_quant_config
from .quant.jobs import QuantJobService, default_quant_storage_root
from .quant.runtime_manifest import build_runtime_manifest

app = FastAPI(title="Smart Finance Analysis Service", version="1.0.0")
registry = ProviderRegistry()
quality_service = DataQualityService(
    registry,
    fetch_a_share_trade_calendar,
    lambda: datetime.now(timezone.utc),
)
_quant_job_service: QuantJobService | None = None
_quant_job_lock = threading.Lock()


class QuoteRequest(BaseModel):
    code: str = Field(min_length=1, max_length=40)
    market: str = Field(min_length=2, max_length=20)
    product_type: str = Field(min_length=2, max_length=30)
    start_date: date
    end_date: date


class BenchmarkHistoryRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    benchmark_code: str = Field(
        alias="benchmarkCode",
        min_length=1,
        max_length=80,
    )
    start_date: date = Field(alias="startDate")
    end_date: date = Field(alias="endDate")


class FundResearchUniverseRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    model_family: Literal[
        "INDEX_FUND",
        "ACTIVE_FUND",
        "QDII_INDEX_FUND",
        "COMMODITY_FUND",
    ] = Field(alias="modelFamily")
    benchmark_code: str = Field(alias="benchmarkCode", min_length=1, max_length=80)
    target_code: str = Field(alias="targetCode", min_length=6, max_length=6)
    start_date: date = Field(alias="startDate")
    end_date: date = Field(alias="endDate")
    limit: int = Field(default=12, ge=1, le=50)
    minimum_records: int = Field(default=250, alias="minimumRecords", ge=2)
    selection_rule: dict[str, Any] = Field(alias="selectionRule")


class DataQualityValidateRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    product_type: ProductType = Field(alias="productType")
    code: str = Field(min_length=1, max_length=40)
    market: str = Field(min_length=2, max_length=20)
    frequency: Literal["DAY"]
    adjust_type: AdjustType = Field(alias="adjustType")
    start_date: date = Field(alias="startDate")
    end_date: date = Field(alias="endDate")
    quality_config_version: str = Field(alias="qualityConfigVersion", min_length=1)


class DataQualityReplayRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    dataset_version: str = Field(alias="datasetVersion", pattern=r"^[0-9a-f]{64}$")
    secondary_dataset_version: str | None = Field(
        default=None, alias="secondaryDatasetVersion", pattern=r"^[0-9a-f]{64}$"
    )
    quality_config_version: str = Field(alias="qualityConfigVersion", min_length=1)


class DataQualityClaimRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    dataset_version: str = Field(alias="datasetVersion", pattern=r"^[0-9a-f]{64}$")
    quality_config_version: str = Field(alias="qualityConfigVersion", min_length=1)


class FxRequest(BaseModel):
    base_currency: str = Field(min_length=3, max_length=3)
    quote_currency: str = Field(min_length=3, max_length=3)
    start_date: date
    end_date: date


class ProductResolveRequest(BaseModel):
    product_type: str = Field(min_length=4, max_length=30)
    code: str = Field(min_length=1, max_length=40)


class RealtimeQuoteRequest(BaseModel):
    code: str = Field(min_length=6, max_length=6)
    market: str = Field(min_length=3, max_length=20)


class AnalysisRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)


class MarketSnapshot(AnalysisRequest):
    turnover_rate: float | None = Field(
        default=None,
        validation_alias=AliasChoices("turnover_rate", "turnoverRate"),
        serialization_alias="turnoverRate",
    )
    volume_ratio: float | None = Field(
        default=None,
        validation_alias=AliasChoices("volume_ratio", "volumeRatio"),
        serialization_alias="volumeRatio",
    )
    amplitude: float | None = None


class TechnicalAnalysisRequest(AnalysisRequest):
    records: list[dict[str, Any]]
    horizons: dict[str, list[int]]
    primary_horizon: str = Field(
        validation_alias=AliasChoices("primary_horizon", "primaryHorizon"),
        serialization_alias="primaryHorizon",
    )
    market_snapshot: MarketSnapshot | None = Field(
        default=None,
        validation_alias=AliasChoices("market_snapshot", "marketSnapshot"),
        serialization_alias="marketSnapshot",
    )

    @field_validator("horizons")
    @classmethod
    def validate_horizons(cls, value: dict[str, list[int]]) -> dict[str, list[int]]:
        return _validated_horizons(value)

    @model_validator(mode="after")
    def validate_primary_horizon(self) -> "TechnicalAnalysisRequest":
        if self.primary_horizon not in self.horizons:
            raise ValueError("primaryHorizon must exist in horizons")
        return self


class FundamentalAnalysisRequest(AnalysisRequest):
    periods: list[dict[str, Any]] = Field(default_factory=list)
    code: str | None = Field(default=None, min_length=6, max_length=6)
    market: str | None = Field(default=None, max_length=20)


class FundAnalysisRequest(AnalysisRequest):
    records: list[dict[str, Any]]


class BacktestRequest(AnalysisRequest):
    records: list[dict[str, Any]]
    horizons: dict[str, list[int]]

    @field_validator("horizons")
    @classmethod
    def validate_horizons(cls, value: dict[str, list[int]]) -> dict[str, list[int]]:
        return _validated_horizons(value)


class QuantJobRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    type: Literal[
        "FACTOR_ANALYSIS",
        "TRAIN_PREDICT",
        "AUTO_SEARCH",
        "PREDICT",
        "BACKTEST",
    ]
    dataset_version: str | None = Field(
        default=None, alias="datasetVersion", pattern=r"^[0-9a-f]{64}$"
    )
    request_fingerprint: str | None = Field(
        default=None, alias="requestFingerprint", pattern=r"^[0-9a-f]{64}$"
    )
    runtime_version: str | None = Field(
        default=None, alias="runtimeVersion", pattern=r"^[0-9a-f]{64}$"
    )
    product_type: Literal["STOCK", "MUTUAL_FUND"] | None = Field(default=None, alias="productType")
    model_family: Literal[
        "A_SHARE_STOCK",
        "INDEX_FUND",
        "ACTIVE_FUND",
        "QDII_INDEX_FUND",
        "COMMODITY_FUND",
    ] | None = Field(default=None, alias="modelFamily")
    horizon_profile_version: str | None = Field(default=None, alias="horizonProfileVersion")
    horizon_code: str | None = Field(default=None, alias="horizonCode", min_length=1, max_length=32)
    horizon_days: int | None = Field(default=None, alias="horizonDays", ge=1)
    benchmark_code: str | None = Field(
        default=None, alias="benchmarkCode", min_length=1, max_length=32
    )
    benchmark_profile_version: str | None = Field(
        default=None, alias="benchmarkProfileVersion", min_length=1, max_length=120
    )
    experiment_fingerprint: str | None = Field(
        default=None, alias="experimentFingerprint", pattern=r"^[0-9a-f]{64}$"
    )
    research_universe_version: str | None = Field(
        default=None, alias="researchUniverseVersion", pattern=r"^[0-9a-f]{64}$"
    )
    experiment_parameters: dict[str, float | int] = Field(
        default_factory=dict, alias="experimentParameters"
    )
    model_version: str | None = Field(
        default=None, alias="modelVersion", pattern=r"^[0-9a-f]{64}$"
    )
    model_file_hash: str | None = Field(
        default=None, alias="modelFileHash", pattern=r"^[0-9a-f]{64}$"
    )
    model_config_version: str | None = Field(
        default=None, alias="modelConfigVersion", min_length=1, max_length=160
    )
    model_status: Literal["VALIDATED", "PAPER_VERIFIED"] | None = Field(
        default=None, alias="modelStatus"
    )
    strategy_version: str | None = Field(
        default=None, alias="strategyVersion", min_length=1, max_length=80
    )
    algorithm: Literal[
        "ELASTIC_NET",
        "XGBOOST",
        "EXTRA_TREES",
        "TREND_VOLATILITY",
        "RISK_FILTERED_MEAN_REVERSION",
        "REGIME_ENSEMBLE",
        # One-cycle compatibility aliases for persisted research requests.
        "GRADIENT_BOOSTING",
        "VALIDATED_ENSEMBLE",
    ] = "REGIME_ENSEMBLE"
    current_weight: float | None = Field(default=None, alias="currentWeight", ge=0, le=1)
    records: list[dict[str, Any]] = Field(default_factory=list)
    benchmark_records: list[dict[str, Any]] = Field(default_factory=list, alias="benchmarkRecords")
    universe_records: list[dict[str, Any]] = Field(
        default_factory=list, alias="universeRecords"
    )
    fundamentals: list[dict[str, Any]] = Field(default_factory=list)
    prices: list[float] = Field(default_factory=list)
    signals: list[float] = Field(default_factory=list)

    @model_validator(mode="after")
    def validate_job_payload(self) -> "QuantJobRequest":
        if self.type in {
            "FACTOR_ANALYSIS",
            "TRAIN_PREDICT",
            "AUTO_SEARCH",
            "PREDICT",
        }:
            if not self.dataset_version or not self.product_type or self.horizon_days is None:
                raise ValueError("analysis jobs require datasetVersion, productType and horizonDays")
            if len(self.records) < 2:
                raise ValueError("analysis jobs require market records")
        if self.type == "PREDICT" and (
            not self.model_version
            or not self.model_file_hash
            or not self.model_config_version
            or not self.model_status
            or not self.strategy_version
        ):
            raise ValueError(
                "prediction jobs require deployed model version, hash, config and strategy"
            )
        if self.type == "BACKTEST":
            if len(self.records) < 2 or len(self.records) != len(self.signals):
                raise ValueError("backtest jobs require equal market records and signals")
        return self


def _validated_horizons(value: dict[str, list[int]]) -> dict[str, list[int]]:
    if not value:
        raise ValueError("at least one horizon is required")
    result: dict[str, list[int]] = {}
    for raw_code, bounds in value.items():
        code = str(raw_code).strip()
        if not code:
            raise ValueError("horizon code cannot be blank")
        if len(bounds) != 2:
            raise ValueError(f"horizon {code} must contain [minimum, maximum]")
        minimum, maximum = int(bounds[0]), int(bounds[1])
        if minimum < 1 or maximum < minimum:
            raise ValueError(f"horizon {code} must use positive days with minimum <= maximum")
        result[code] = [minimum, maximum]
    return result


def internal_auth(x_internal_token: str | None = Header(default=None)) -> None:
    configured = os.getenv("ANALYSIS_INTERNAL_TOKEN", "dev-analysis-token")
    if x_internal_token != configured:
        raise HTTPException(status_code=401, detail="invalid internal token")


def quant_jobs() -> QuantJobService:
    global _quant_job_service
    if _quant_job_service is None:
        with _quant_job_lock:
            if _quant_job_service is None:
                config = load_quant_config()
                _quant_job_service = QuantJobService(default_quant_storage_root(config), config)
    return _quant_job_service


@app.get("/health")
def health():
    return {"status": "UP", "service": "analysis-service"}


@app.get("/internal/v1/quant/runtime-manifest", dependencies=[Depends(internal_auth)])
def quant_runtime_manifest():
    return build_runtime_manifest(load_quant_config())


@app.post("/internal/v1/products/resolve", dependencies=[Depends(internal_auth)])
def resolve_product(request: ProductResolveRequest):
    try:
        return resolve_product_metadata(request.product_type, request.code)
    except (ProviderUnavailable, ValueError) as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc


@app.post("/internal/v1/quotes/realtime", dependencies=[Depends(internal_auth)])
def realtime_quote(request: RealtimeQuoteRequest):
    try:
        return fetch_realtime_stock_quote(request.code, request.market)
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    except ProviderUnavailable as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc


@app.post("/internal/v1/market-data/quotes/daily", dependencies=[Depends(internal_auth)])
def daily_quotes(request: QuoteRequest):
    if request.end_date < request.start_date:
        raise HTTPException(status_code=400, detail="end_date must not be before start_date")
    try:
        records, warnings = registry.daily_quotes(
            request.code, request.market, request.product_type, request.start_date, request.end_date
        )
    except ProviderUnavailable as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    latest = max(item.data_date for item in records)
    return {
        "provider": records[0].provider,
        "dataDate": latest.isoformat(),
        "fetchedAt": records[0].fetched_at.isoformat(),
        "adapterVersion": records[0].adapter_version,
        "records": [item.json_dict() for item in records],
        "warnings": warnings,
    }


@app.post("/internal/v1/market-data/benchmarks/daily", dependencies=[Depends(internal_auth)])
def daily_benchmark(request: BenchmarkHistoryRequest):
    try:
        records = fetch_benchmark_history(
            request.benchmark_code,
            request.start_date,
            request.end_date,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except ProviderUnavailable as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    return {
        "benchmarkCode": request.benchmark_code,
        "provider": records[0]["provider"],
        "adapterVersion": records[0]["adapter_version"],
        "dataDate": records[-1]["data_date"],
        "records": records,
        "warnings": [],
    }


@app.post("/internal/v1/market-data/research-universes/funds", dependencies=[Depends(internal_auth)])
def fund_research_universe(request: FundResearchUniverseRequest):
    try:
        return discover_fund_research_universe(
            model_family=request.model_family,
            benchmark_code=request.benchmark_code,
            target_code=request.target_code,
            start_date=request.start_date,
            end_date=request.end_date,
            limit=request.limit,
            minimum_records=request.minimum_records,
            selection_rule=request.selection_rule,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except ProviderUnavailable as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc


@app.post("/internal/v1/data-quality/validate", dependencies=[Depends(internal_auth)])
def validate_data_quality(request: DataQualityValidateRequest):
    try:
        return quality_service.validate(
            product_type=request.product_type,
            code=request.code,
            market=request.market,
            frequency=request.frequency,
            adjust_type=request.adjust_type,
            start_date=request.start_date,
            end_date=request.end_date,
            quality_config_version=request.quality_config_version,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except ProviderUnavailable as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    except SnapshotIntegrityError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc


@app.post("/internal/v1/data-quality/replay", dependencies=[Depends(internal_auth)])
def replay_data_quality(request: DataQualityReplayRequest):
    try:
        return quality_service.replay(
            dataset_version=request.dataset_version,
            secondary_dataset_version=request.secondary_dataset_version,
            quality_config_version=request.quality_config_version,
        )
    except SnapshotNotFoundError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except SnapshotIntegrityError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.post("/internal/v1/data-quality/claim", dependencies=[Depends(internal_auth)])
def claim_data_quality_snapshot(request: DataQualityClaimRequest):
    try:
        return quality_service.claim(
            dataset_version=request.dataset_version,
            quality_config_version=request.quality_config_version,
        )
    except SnapshotNotFoundError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except SnapshotIntegrityError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.post("/internal/v1/market-data/fx/daily", dependencies=[Depends(internal_auth)])
def daily_fx(request: FxRequest):
    if request.end_date < request.start_date:
        raise HTTPException(status_code=400, detail="end_date must not be before start_date")
    try:
        records = akshare_fx_rates(request.base_currency, request.quote_currency, request.start_date, request.end_date)
    except Exception as exc:
        raise HTTPException(status_code=503, detail=f"AKSHARE: {exc}") from exc
    if not records:
        raise HTTPException(status_code=503, detail="AKSHARE: no FX records")
    latest = max(item["data_date"] for item in records)
    return {"provider": "AKSHARE", "dataDate": latest, "fetchedAt": records[0]["fetched_at"],
            "adapterVersion": "1", "records": records, "warnings": []}


@app.get("/internal/v1/market-data/calendar", dependencies=[Depends(internal_auth)])
def market_calendar(year: int, market: str = "A_SHARE"):
    if market.upper() != "A_SHARE":
        raise HTTPException(status_code=400, detail="only A_SHARE calendar is supported")
    try:
        trading_dates = fetch_a_share_trade_calendar(year)
    except ProviderUnavailable as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    return {
        "market": "A_SHARE",
        "year": year,
        "provider": "AKSHARE",
        "tradingDates": trading_dates,
    }


@app.post("/internal/v1/analysis/technical", dependencies=[Depends(internal_auth)])
def technical_analysis(request: TechnicalAnalysisRequest):
    market_snapshot = (
        request.market_snapshot.model_dump(by_alias=True, exclude_none=True)
        if request.market_snapshot is not None
        else None
    )
    return analyze_technical(
        request.records,
        request.horizons,
        request.primary_horizon,
        market_snapshot,
    )


@app.post("/internal/v1/analysis/fundamental", dependencies=[Depends(internal_auth)])
def fundamental_analysis(request: FundamentalAnalysisRequest):
    periods = request.periods
    warnings: list[str] = []
    if not periods and request.code:
        try:
            periods, warnings = fetch_stock_fundamentals(request.code)
        except ProviderUnavailable as exc:
            return {"status": "INSUFFICIENT", "verdict": "INSUFFICIENT", "coverage": 0,
                    "dimensions": {}, "warnings": [str(exc)]}
    result = analyze_fundamentals(periods)
    result["warnings"] = warnings
    return result


@app.post("/internal/v1/analysis/fund", dependencies=[Depends(internal_auth)])
def fund_analysis(request: FundAnalysisRequest):
    return analyze_fund(request.records)


@app.post("/internal/v1/analysis/backtest", dependencies=[Depends(internal_auth)])
def backtest_analysis(request: BacktestRequest):
    return backtest_horizons(request.records, request.horizons)


@app.post("/internal/v1/quant/jobs", dependencies=[Depends(internal_auth)])
def create_quant_job(request: QuantJobRequest):
    try:
        return quant_jobs().submit(request.model_dump(by_alias=True, exclude_none=True))
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.get("/internal/v1/quant/jobs/{jobId}", dependencies=[Depends(internal_auth)])
def get_quant_job(jobId: str):
    try:
        return quant_jobs().get(jobId)
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="quant job was not found") from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail="invalid quant job id") from exc


@app.post(
    "/internal/v1/quant/jobs/{jobId}/cancel",
    dependencies=[Depends(internal_auth)],
)
def cancel_quant_job(jobId: str):
    try:
        return quant_jobs().cancel(jobId)
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail="quant job was not found") from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail="invalid quant job id") from exc
