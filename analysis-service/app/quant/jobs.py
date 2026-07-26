from __future__ import annotations

import json
import re
import threading
import uuid
from concurrent.futures import ThreadPoolExecutor
from dataclasses import asdict, replace
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Mapping, Sequence

from .backtest import simulate_a_share_long_only
from .config import QuantConfig, with_experiment_parameters
from .engine import (
    BenchmarkUnavailable,
    QuantDomainError,
    QuantEngine,
    TrainingSample,
)
from .factor_store import FactorSnapshotStore
from .model_store import ImmutableModelStore
from .risk import size_target_weight
from .validation import tradable_target_weight


_JOB_ID = re.compile(r"^[0-9a-f]{32}$")


class QuantJobService:
    def __init__(self, storage_root: Path, config: QuantConfig, max_workers: int | None = None):
        self.storage_root = storage_root.resolve()
        self.config = config
        self.jobs_root = self.storage_root / "quant-jobs"
        self.models_root = self.storage_root / "quant-models"
        self.jobs_root.mkdir(parents=True, exist_ok=True)
        self.models_root.mkdir(parents=True, exist_ok=True)
        self.model_store = ImmutableModelStore(self.models_root)
        self.factor_store = FactorSnapshotStore(self.storage_root)
        workers = max_workers or config.integer("jobs.maximumWorkers")
        self.executor = ThreadPoolExecutor(max_workers=workers, thread_name_prefix="quant-job")
        self._lock = threading.Lock()
        self._futures: dict[str, Any] = {}

    def submit(self, request: Mapping[str, Any]) -> dict[str, Any]:
        job_type = str(request.get("type", "")).strip().upper()
        if job_type not in {
            "FACTOR_ANALYSIS",
            "TRAIN_PREDICT",
            "AUTO_SEARCH",
            "PREDICT",
            "BACKTEST",
        }:
            raise ValueError("unsupported quant job type")
        job_id = uuid.uuid4().hex
        now = _now()
        state = {
            "jobId": job_id,
            "type": job_type,
            "status": "QUEUED",
            "configVersion": self.config.version,
            "datasetVersion": request.get("datasetVersion"),
            "createdAt": now,
            "updatedAt": now,
            "result": None,
        }
        self._write(state)
        future = self.executor.submit(self._run, job_id, dict(request))
        with self._lock:
            self._futures[job_id] = future
        return state

    def get(self, job_id: str) -> dict[str, Any]:
        path = self._path(job_id)
        if not path.is_file():
            raise FileNotFoundError(f"quant job {job_id} was not found")
        return json.loads(path.read_text(encoding="utf-8"))

    def cancel(self, job_id: str) -> dict[str, Any]:
        state = self.get(job_id)
        if state["status"] in {"SUCCEEDED", "FAILED", "CANCELLED"}:
            return state
        with self._lock:
            future = self._futures.get(job_id)
            if future is not None:
                future.cancel()
        state.update(
            status="CANCELLED",
            updatedAt=_now(),
            finishedAt=_now(),
            errorCode=None,
            errorSummary=None,
            userMessage="量化任务已取消",
        )
        self._write(state)
        return state

    def _run(self, job_id: str, request: dict[str, Any]) -> None:
        state = self.get(job_id)
        if state["status"] == "CANCELLED":
            return
        state.update(status="RUNNING", updatedAt=_now())
        self._write(state)
        try:
            result = self._execute(str(state["type"]), request)
            if self.get(job_id)["status"] == "CANCELLED":
                return
            state.update(status="SUCCEEDED", updatedAt=_now(), result=result)
        except Exception as error:
            if self.get(job_id)["status"] == "CANCELLED":
                return
            state.update(
                status="FAILED",
                updatedAt=_now(),
                **_failure_payload(error),
            )
        self._write(state)

    def _execute(self, job_type: str, request: dict[str, Any]) -> dict[str, Any]:
        config = with_experiment_parameters(
            self.config,
            request.get("experimentParameters"),
        )
        if job_type == "BACKTEST":
            result = simulate_a_share_long_only(
                request["records"],
                request["signals"],
                config,
            )
            payload = asdict(result)
            payload["equity_curve"] = list(result.equity_curve)
            return payload
        product_type = str(request["productType"]).strip().upper()
        model_family = str(
            request.get("modelFamily")
            or ("A_SHARE_STOCK" if product_type == "STOCK" else "ACTIVE_FUND")
        ).strip().upper()
        horizon_days = int(request["horizonDays"])
        records = request["records"]
        benchmark = request.get("benchmarkRecords") or []
        benchmark_code, benchmark_flags = _benchmark_contract(
            request.get("benchmarkCode"),
            benchmark,
        )
        if product_type == "MUTUAL_FUND" and not benchmark_code:
            raise BenchmarkUnavailable(
                "official benchmark records are required for fund training"
            )
        fundamentals = request.get("fundamentals") or []
        engine = QuantEngine(config)
        factor = engine.factor_row(records, product_type, horizon_days, benchmark, fundamentals)
        factor_context = {
            "datasetVersion": request.get("datasetVersion"),
            "quantConfigVersion": config.version,
            "experimentFingerprint": request.get("experimentFingerprint"),
            "researchUniverseVersion": request.get("researchUniverseVersion"),
            "modelFamily": model_family,
            "algorithm": request.get("algorithm") or "VALIDATED_ENSEMBLE",
            "benchmarkProfileVersion": request.get("benchmarkProfileVersion"),
            "productType": product_type,
            "horizonProfileVersion": request.get("horizonProfileVersion"),
            "horizonCode": request.get("horizonCode"),
            "horizonDays": horizon_days,
        }
        latest_factor_row = {
            **factor_context,
            "asOfDate": factor.as_of_date,
            "sampleRole": "PREDICTION",
            **factor.values,
        }
        samples = None
        factor_rows = [latest_factor_row]
        if job_type in {"TRAIN_PREDICT", "AUTO_SEARCH"}:
            samples = engine.training_samples(records, product_type, horizon_days, benchmark, fundamentals)
            member_batches: list[tuple[str, Sequence[TrainingSample]]] = []
            for position, member in enumerate(request.get("universeRecords") or []):
                member_records = member.get("records") or []
                if len(member_records) < 2:
                    continue
                member_type = str(member.get("productType") or product_type).strip().upper()
                member_samples = engine.training_samples(
                    member_records,
                    member_type,
                    horizon_days,
                    member.get("benchmarkRecords") or [],
                    member.get("fundamentals") or [],
                )
                series_id = str(
                    member.get("seriesId")
                    or member.get("code")
                    or f"universe-member-{position}"
                )
                member_batches.append((series_id, member_samples))
            samples = _merge_panel_samples(samples, member_batches)
            factor_rows = [{
                **factor_context,
                "asOfDate": sample.as_of_date,
                "seriesId": sample.series_id,
                "sampleRole": "TRAINING",
                "netReturn": sample.net_return,
                "positiveReturn": sample.positive_return,
                "negativeReturn": sample.negative_return,
                "netExcessReturn": sample.net_excess_return,
                "positiveExcess": sample.positive_excess,
                **sample.features,
            } for sample in samples]
            factor_rows.append(latest_factor_row)
        factor_manifest = self.factor_store.write(factor_rows, factor_context)
        common = {
            "datasetVersion": request.get("datasetVersion"),
            "featureSetVersion": factor_manifest["featureSetVersion"],
            "featureArtifactUri": factor_manifest["featureArtifactUri"],
            "featureArtifactHash": factor_manifest["featureArtifactHash"],
            "featureSchema": factor_manifest["featureSchema"],
            "quantConfigVersion": config.version,
            "experimentFingerprint": request.get("experimentFingerprint"),
            "researchUniverseVersion": request.get("researchUniverseVersion"),
            "modelFamily": model_family,
            "benchmarkProfileVersion": request.get("benchmarkProfileVersion"),
            "horizonProfileVersion": request.get("horizonProfileVersion"),
            "horizonCode": request.get("horizonCode"),
            "horizonDays": horizon_days,
            "asOfDate": factor.as_of_date,
            "marketRegime": self._regime(factor.values, config),
            "benchmarkCode": benchmark_code,
            "roundTripCostBps": config.number(f"labels.roundTripCostBps.{product_type}"),
        }
        if job_type == "FACTOR_ANALYSIS":
            top_count = config.integer("prediction.topFactorCount")
            ranked = sorted(factor.values.items(), key=lambda item: abs(item[1]), reverse=True)[:top_count]
            return common | {
                "modelVersion": None,
                "strategyVersion": None,
                "probabilityPositiveExcess": None,
                "expectedExcessReturn": None,
                "profitProbability": None,
                "lossProbability": None,
                "expectedNetReturn": None,
                "predictionInterval": None,
                "confidence": "LOW",
                "action": "NO_TRADE",
                "targetWeight": None,
                "topFactors": [{"name": name, "value": value} for name, value in ranked],
                "riskFlags": ["MODEL_UNAVAILABLE", *benchmark_flags],
                "backtestSummary": None,
            }
        if job_type == "PREDICT":
            from .inference import SavedModelInferenceService

            prediction = SavedModelInferenceService(self.model_store).predict(
                str(request["modelVersion"]),
                model_hash=str(request["modelFileHash"]),
                config_version=str(request["modelConfigVersion"]),
                features=factor.values,
            )
            target_weight = size_target_weight(
                product_type=product_type,
                action=prediction.action,
                confidence=prediction.confidence,
                market_regime=common["marketRegime"],
                annualized_volatility=factor.values.get("realized_volatility", 0.0),
                current_weight=float(request.get("currentWeight") or 0.0),
                config=config,
            )
            return common | {
                "modelVersion": request["modelVersion"],
                "modelFileHash": request["modelFileHash"],
                "modelStatus": request["modelStatus"],
                "strategyVersion": request["strategyVersion"],
                "profitProbability": prediction.probability_positive_excess,
                "lossProbability": 1.0 - prediction.probability_positive_excess,
                "expectedNetReturn": prediction.expected_excess_return,
                "probabilityPositiveExcess": None,
                "expectedExcessReturn": None,
                "predictionInterval": list(prediction.prediction_interval),
                "confidence": prediction.confidence,
                "action": prediction.action,
                "targetWeight": target_weight,
                "topFactors": list(prediction.top_factors),
                "featureVector": factor.values,
                "riskFlags": list(benchmark_flags),
                "validationReport": None,
                "backtestSummary": None,
                "searchSummary": None,
            }
        assert samples is not None
        from .models import attach_event_backtest, predict_ensemble, train_ensemble

        def train_candidate(
            candidate_samples: Sequence[TrainingSample],
            candidate_config: QuantConfig,
            **training_options: Any,
        ) -> Any:
            candidate = train_ensemble(
                candidate_samples,
                candidate_config,
                **training_options,
            )
            if product_type == "STOCK":
                event_result = simulate_a_share_long_only(
                    _a_share_records(records),
                    _validation_signals(
                        len(records),
                        candidate.validation_as_of_indices,
                        candidate.validation_target_weights,
                    ),
                    candidate_config,
                )
                attach_event_backtest(candidate, event_result, candidate_config)
            return candidate

        search_summary = None
        if job_type == "AUTO_SEARCH":
            from .auto_search import AutoSearchEngine

            search_result = AutoSearchEngine(
                config,
                trainer=train_candidate,
            ).search(
                samples,
                benchmark_available=bool(benchmark_code and benchmark),
            )
            artifact = search_result.artifact
            search_summary = search_result.summary
        else:
            artifact = train_candidate(
                samples,
                config,
                benchmark_available=bool(benchmark_code and benchmark),
                algorithm=str(request.get("algorithm") or "VALIDATED_ENSEMBLE"),
            )
        prediction = predict_ensemble(artifact, factor.values)
        model_file_hash = self._save_model(artifact)
        risk_flags = [] if artifact.status == "VALIDATED" else ["MODEL_NOT_VALIDATED"]
        risk_flags.extend(benchmark_flags)
        validation_report = artifact.metrics.get("validationReport")
        if isinstance(validation_report, dict):
            risk_flags.extend(
                str(value) for value in validation_report.get("failureCodes", [])
            )
        if product_type == "STOCK" and factor.values.get("fundamental_availability", 0.0) < 1.0:
            risk_flags.append("FUNDAMENTALS_UNAVAILABLE")
        target_weight = size_target_weight(
            product_type=product_type,
            action=prediction.action,
            confidence=prediction.confidence,
            market_regime=common["marketRegime"],
            annualized_volatility=factor.values.get("realized_volatility", 0.0),
            current_weight=float(request.get("currentWeight") or 0.0),
            config=config,
        )
        return common | {
            "modelVersion": artifact.model_version,
            "modelFileHash": model_file_hash,
            "modelStatus": artifact.status,
            "strategyVersion": f"strategy-{artifact.model_version[:16]}",
            "probabilityPositiveExcess": None,
            "expectedExcessReturn": None,
            "profitProbability": prediction.probability_positive_excess,
            "lossProbability": 1.0 - prediction.probability_positive_excess,
            "expectedNetReturn": prediction.expected_excess_return,
            "predictionInterval": list(prediction.prediction_interval),
            "confidence": prediction.confidence,
            "action": prediction.action,
            "targetWeight": _visible_target_weight(artifact.status, target_weight),
            "topFactors": list(prediction.top_factors),
            "featureVector": factor.values,
            "riskFlags": risk_flags,
            "validationReport": validation_report,
            "backtestSummary": artifact.metrics,
            "searchSummary": search_summary,
        }

    def _regime(self, factors: Mapping[str, float], config: QuantConfig) -> str:
        if factors.get("realized_volatility", 0.0) >= config.number("regime.highVolatility"):
            return "HIGH_VOLATILITY"
        slope = factors.get("trend_slope", 0.0)
        if slope >= config.number("regime.positiveTrend"):
            return "UPTREND"
        if slope <= config.number("regime.negativeTrend"):
            return "DOWNTREND"
        return "RANGE"

    def _save_model(self, artifact: Any) -> str:
        return self.model_store.save(artifact)

    def _path(self, job_id: str) -> Path:
        if not _JOB_ID.fullmatch(job_id):
            raise ValueError("invalid quant job id")
        return self.jobs_root / f"{job_id}.json"

    def _write(self, state: Mapping[str, Any]) -> None:
        path = self._path(str(state["jobId"]))
        temporary = path.with_suffix(".tmp")
        payload = json.dumps(state, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        with self._lock:
            temporary.write_text(payload, encoding="utf-8")
            temporary.replace(path)


def default_quant_storage_root(config: QuantConfig) -> Path:
    env_name = config.text("jobs.storageRootEnv")
    configured = os.getenv(env_name)
    return Path(configured) if configured else Path(__file__).resolve().parents[2] / ".data"


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _a_share_records(records: list[Mapping[str, Any]]) -> list[dict[str, Any]]:
    normalized: list[dict[str, Any]] = []
    for index, raw in enumerate(records):
        record = dict(raw)
        if record.get("previous_close") in {None, ""}:
            previous = records[index - 1] if index > 0 else raw
            record["previous_close"] = previous["close"]
        normalized.append(record)
    return normalized


def _validation_signals(length: int,
                        indices: tuple[int, ...],
                        target_weights: tuple[float, ...]) -> list[float]:
    if len(indices) != len(target_weights):
        raise ValueError("validation indices and target weights must have equal length")
    signals = [0.0] * length
    pointer = 0
    current = 0.0
    for index in range(length):
        while pointer < len(indices) and indices[pointer] <= index:
            current = target_weights[pointer]
            pointer += 1
        signals[index] = current
    return signals


def _benchmark_contract(
    benchmark_code: Any,
    benchmark_records: list[Mapping[str, Any]],
) -> tuple[str | None, list[str]]:
    normalized = str(benchmark_code or "").strip()
    if normalized and benchmark_records:
        return normalized, []
    return None, ["BENCHMARK_UNAVAILABLE"]


def _merge_panel_samples(
    target_samples: Sequence[TrainingSample],
    member_batches: Sequence[tuple[str, Sequence[TrainingSample]]],
) -> list[TrainingSample]:
    merged = list(target_samples)
    for series_id, samples in member_batches:
        normalized_id = str(series_id).strip()
        if not normalized_id or normalized_id == "TARGET":
            raise ValueError("universe member seriesId must be non-empty and distinct from TARGET")
        merged.extend(replace(sample, series_id=normalized_id) for sample in samples)
    return sorted(merged, key=lambda item: (item.as_of_date, item.series_id))


def _failure_payload(error: Exception) -> dict[str, Any]:
    summary = f"{type(error).__name__}: {str(error).strip() or 'unknown error'}"
    error_code = (
        error.error_code
        if isinstance(error, QuantDomainError)
        else "JOB_FAILED"
    )
    user_message = (
        error.user_message
        if isinstance(error, QuantDomainError)
        else "量化任务执行失败，请查看错误原因后重试。"
    )
    return {
        "errorCode": error_code,
        "errorSummary": summary[:500],
        "result": {
            "action": "PAUSE",
            "riskFlags": [error_code],
            "userMessage": user_message,
        },
    }


def _visible_target_weight(model_status: str, target_weight: float) -> float | None:
    return tradable_target_weight(model_status, target_weight)
