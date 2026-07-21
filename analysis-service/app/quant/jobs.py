from __future__ import annotations

import hashlib
import json
import os
import pickle
import re
import threading
import uuid
from concurrent.futures import ThreadPoolExecutor
from dataclasses import asdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Mapping

from .backtest import simulate_long_only
from .config import QuantConfig
from .engine import QuantEngine
from .factor_store import FactorSnapshotStore
from .risk import size_target_weight


_JOB_ID = re.compile(r"^[0-9a-f]{32}$")


class QuantJobService:
    def __init__(self, storage_root: Path, config: QuantConfig, max_workers: int | None = None):
        self.storage_root = storage_root.resolve()
        self.config = config
        self.jobs_root = self.storage_root / "quant-jobs"
        self.models_root = self.storage_root / "quant-models"
        self.jobs_root.mkdir(parents=True, exist_ok=True)
        self.models_root.mkdir(parents=True, exist_ok=True)
        self.factor_store = FactorSnapshotStore(self.storage_root)
        workers = max_workers or config.integer("jobs.maximumWorkers")
        self.executor = ThreadPoolExecutor(max_workers=workers, thread_name_prefix="quant-job")
        self._lock = threading.Lock()

    def submit(self, request: Mapping[str, Any]) -> dict[str, Any]:
        job_type = str(request.get("type", "")).strip().upper()
        if job_type not in {"FACTOR_ANALYSIS", "TRAIN_PREDICT", "BACKTEST"}:
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
        self.executor.submit(self._run, job_id, dict(request))
        return state

    def get(self, job_id: str) -> dict[str, Any]:
        path = self._path(job_id)
        if not path.is_file():
            raise FileNotFoundError(f"quant job {job_id} was not found")
        return json.loads(path.read_text(encoding="utf-8"))

    def _run(self, job_id: str, request: dict[str, Any]) -> None:
        state = self.get(job_id)
        state.update(status="RUNNING", updatedAt=_now())
        self._write(state)
        try:
            result = self._execute(str(state["type"]), request)
            state.update(status="SUCCEEDED", updatedAt=_now(), result=result)
        except Exception:
            state.update(
                status="FAILED",
                updatedAt=_now(),
                result={
                    "action": "NO_TRADE",
                    "riskFlags": ["QUANT_JOB_FAILED"],
                    "userMessage": "量化分析暂时无法完成，系统将保留上一份有效结果。",
                },
            )
        self._write(state)

    def _execute(self, job_type: str, request: dict[str, Any]) -> dict[str, Any]:
        if job_type == "BACKTEST":
            result = simulate_long_only(request["prices"], request["signals"], self.config)
            payload = asdict(result)
            payload["equity_curve"] = list(result.equity_curve)
            return payload
        product_type = str(request["productType"]).strip().upper()
        horizon_days = int(request["horizonDays"])
        records = request["records"]
        benchmark = request.get("benchmarkRecords") or []
        fundamentals = request.get("fundamentals") or []
        engine = QuantEngine(self.config)
        factor = engine.factor_row(records, product_type, horizon_days, benchmark, fundamentals)
        factor_context = {
            "datasetVersion": request.get("datasetVersion"),
            "quantConfigVersion": self.config.version,
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
        if job_type == "TRAIN_PREDICT":
            samples = engine.training_samples(records, product_type, horizon_days, benchmark, fundamentals)
            factor_rows = [{
                **factor_context,
                "asOfDate": sample.as_of_date,
                "sampleRole": "TRAINING",
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
            "quantConfigVersion": self.config.version,
            "horizonProfileVersion": request.get("horizonProfileVersion"),
            "horizonCode": request.get("horizonCode"),
            "horizonDays": horizon_days,
            "asOfDate": factor.as_of_date,
            "marketRegime": self._regime(factor.values),
            "benchmarkCode": request.get("benchmarkCode") or "CASH_CNY",
            "roundTripCostBps": self.config.number(f"labels.roundTripCostBps.{product_type}"),
        }
        if job_type == "FACTOR_ANALYSIS":
            top_count = self.config.integer("prediction.topFactorCount")
            ranked = sorted(factor.values.items(), key=lambda item: abs(item[1]), reverse=True)[:top_count]
            return common | {
                "modelVersion": None,
                "strategyVersion": None,
                "probabilityPositiveExcess": None,
                "expectedExcessReturn": None,
                "predictionInterval": None,
                "confidence": "LOW",
                "action": "NO_TRADE",
                "targetWeight": 0.0,
                "topFactors": [{"name": name, "value": value} for name, value in ranked],
                "riskFlags": ["MODEL_UNAVAILABLE"],
                "backtestSummary": None,
            }
        assert samples is not None
        from .models import predict_ensemble, train_ensemble
        artifact = train_ensemble(samples, self.config)
        prediction = predict_ensemble(artifact, factor.values)
        model_file_hash = self._save_model(artifact)
        risk_flags = [] if artifact.status == "VALIDATED" else ["MODEL_NOT_VALIDATED"]
        if not benchmark:
            risk_flags.append("CASH_BENCHMARK")
        if product_type == "STOCK" and factor.values.get("fundamental_availability", 0.0) < 1.0:
            risk_flags.append("FUNDAMENTALS_UNAVAILABLE")
        target_weight = size_target_weight(
            product_type=product_type,
            action=prediction.action,
            confidence=prediction.confidence,
            market_regime=common["marketRegime"],
            annualized_volatility=factor.values.get("realized_volatility", 0.0),
            current_weight=float(request.get("currentWeight") or 0.0),
            config=self.config,
        )
        return common | {
            "modelVersion": artifact.model_version,
            "modelFileHash": model_file_hash,
            "modelStatus": artifact.status,
            "strategyVersion": f"strategy-{artifact.model_version[:16]}",
            "probabilityPositiveExcess": prediction.probability_positive_excess,
            "expectedExcessReturn": prediction.expected_excess_return,
            "predictionInterval": list(prediction.prediction_interval),
            "confidence": prediction.confidence,
            "action": prediction.action,
            "targetWeight": target_weight,
            "topFactors": list(prediction.top_factors),
            "riskFlags": risk_flags,
            "backtestSummary": artifact.metrics,
        }

    def _regime(self, factors: Mapping[str, float]) -> str:
        if factors.get("realized_volatility", 0.0) >= self.config.number("regime.highVolatility"):
            return "HIGH_VOLATILITY"
        slope = factors.get("trend_slope", 0.0)
        if slope >= self.config.number("regime.positiveTrend"):
            return "UPTREND"
        if slope <= self.config.number("regime.negativeTrend"):
            return "DOWNTREND"
        return "RANGE"

    def _save_model(self, artifact: Any) -> str:
        target = self.models_root / f"{artifact.model_version}.pkl"
        payload = pickle.dumps(artifact, protocol=pickle.HIGHEST_PROTOCOL)
        digest = hashlib.sha256(payload).hexdigest()
        if target.exists():
            existing = target.read_bytes()
            if hashlib.sha256(existing).hexdigest() != digest:
                raise ValueError("immutable model artifact hash conflict")
            return digest
        temporary = target.with_suffix(".tmp")
        temporary.write_bytes(payload)
        os.replace(temporary, target)
        return digest

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
            os.replace(temporary, path)


def default_quant_storage_root(config: QuantConfig) -> Path:
    env_name = config.text("jobs.storageRootEnv")
    configured = os.getenv(env_name)
    return Path(configured) if configured else Path(__file__).resolve().parents[2] / ".data"


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()
