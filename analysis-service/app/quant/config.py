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


def with_experiment_parameters(
    base: QuantConfig,
    parameters: dict[str, Any] | None,
) -> QuantConfig:
    if not parameters:
        return base
    parameter_paths = _experiment_parameter_paths(base.data)
    unsupported = sorted(set(parameters) - set(parameter_paths))
    if unsupported:
        raise ValueError(f"unsupported experiment parameter: {unsupported[0]}")
    data = deepcopy(base.data)
    for key, value in parameters.items():
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise ValueError(f"experiment parameter must be numeric: {key}")
        parts = parameter_paths[key].split(".")
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


def _experiment_parameter_paths(data: dict[str, Any]) -> dict[str, str]:
    paths: dict[str, str] = {}
    strategies = data.get("autoSearch", {}).get("strategies", [])
    if not isinstance(strategies, list):
        return paths
    for strategy in strategies:
        if not isinstance(strategy, dict):
            continue
        specs = strategy.get("parameters", [])
        if not isinstance(specs, list):
            continue
        for spec in specs:
            if not isinstance(spec, dict):
                continue
            name = spec.get("name")
            path = spec.get("configPath")
            if not isinstance(name, str) or not isinstance(path, str):
                continue
            existing = paths.get(name)
            if existing is not None and existing != path:
                raise ValueError(
                    f"search parameter {name} maps to multiple config paths"
                )
            paths[name] = path
    return paths


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
    if "autoSearch" in data:
        config.number("autoSearch.finalHoldoutFraction")
        config.integer("autoSearch.minimumFinalHoldoutSamples")
        config.integer("autoSearch.finalHoldoutValidation.minimumSamples")
        config.number("autoSearch.finalHoldoutValidation.minimumOosR2")
        config.number("autoSearch.finalHoldoutValidation.minimumBrierSkill")
        config.number("autoSearch.finalHoldoutValidation.minimumLogLossSkill")
        config.number("autoSearch.finalHoldoutValidation.minimumNetReturn")
        config.number("autoSearch.finalHoldoutValidation.minimumIntervalCoverage")
        config.number("autoSearch.finalHoldoutValidation.maximumIntervalCoverage")
    return config
