from __future__ import annotations

import json
import os
import hashlib
from copy import deepcopy
from dataclasses import dataclass
from pathlib import Path
from typing import Any


@dataclass(frozen=True)
class QuantConfig:
    data: dict[str, Any]

    @property
    def version(self) -> str:
        return self.text("version")

    def value(self, path: str) -> Any:
        current: Any = self.data
        for part in path.split("."):
            if not isinstance(current, dict) or part not in current:
                raise ValueError(f"missing quant configuration: {path}")
            current = current[part]
        return current

    def number(self, path: str) -> float:
        value = self.value(path)
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise ValueError(f"quant configuration {path} must be numeric")
        return float(value)

    def integer(self, path: str) -> int:
        value = self.value(path)
        if isinstance(value, bool) or not isinstance(value, int):
            raise ValueError(f"quant configuration {path} must be an integer")
        return value

    def text(self, path: str) -> str:
        value = self.value(path)
        if not isinstance(value, str) or not value.strip():
            raise ValueError(f"quant configuration {path} must be non-blank text")
        return value.strip()


_EXPERIMENT_PARAMETER_PATHS = {
    "linearWeight": "prediction.ensemble.linearWeight",
    "classificationC": "training.elasticNet.classificationC",
    "regressionAlpha": "training.elasticNet.regressionAlpha",
    "estimators": "training.xgboost.estimators",
    "maximumDepth": "training.xgboost.maximumDepth",
    "learningRate": "training.xgboost.learningRate",
}


def with_experiment_parameters(
    base: QuantConfig,
    parameters: dict[str, Any] | None,
) -> QuantConfig:
    if not parameters:
        return base
    unsupported = sorted(set(parameters) - set(_EXPERIMENT_PARAMETER_PATHS))
    if unsupported:
        raise ValueError(f"unsupported experiment parameter: {unsupported[0]}")
    data = deepcopy(base.data)
    for key, value in parameters.items():
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise ValueError(f"experiment parameter must be numeric: {key}")
        parts = _EXPERIMENT_PARAMETER_PATHS[key].split(".")
        current = data
        for part in parts[:-1]:
            current = current[part]
        current[parts[-1]] = value
    material = json.dumps(
        {"baseVersion": base.version, "parameters": parameters},
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    data["version"] = f"{base.version}-exp-{hashlib.sha256(material).hexdigest()[:12]}"
    return QuantConfig(data)


def load_quant_config(path: str | Path | None = None) -> QuantConfig:
    configured = path or os.getenv("QUANT_RESEARCH_CONFIG_PATH")
    config_path = Path(configured) if configured else (
        Path(__file__).resolve().parents[2] / "config" / "quant-research-v2.json"
    )
    data = json.loads(config_path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError("quant configuration root must be an object")
    config = QuantConfig(data)
    if not config.version.startswith("quant-research-"):
        raise ValueError("quant configuration must use a versioned quant-research-* name")
    config.integer("randomSeed")
    config.integer("annualizationDays")
    config.integer("training.minimumSamples")
    config.integer("training.walkForwardFolds")
    config.integer("training.minimumCalibrationSamples")
    config.integer("training.minimumEvaluationSamples")
    return config
