from __future__ import annotations

import json
import os
from collections.abc import Mapping
from pathlib import Path
from typing import Any


class StrategyConfig:
    def __init__(self, data: Mapping[str, Any]):
        self.data = dict(data)
        self.version = self.text("version")
        if "outlook" in self.data.get("technical", {}):
            self._validate_outlook()

    def value(self, path: str) -> Any:
        current: Any = self.data
        for part in path.split("."):
            if not isinstance(current, Mapping) or part not in current:
                raise ValueError(f"strategy config is missing {path}")
            current = current[part]
        return current

    def text(self, path: str) -> str:
        value = str(self.value(path)).strip()
        if not value:
            raise ValueError(f"strategy config value {path} cannot be blank")
        return value

    def integer(self, path: str) -> int:
        value = int(self.value(path))
        if value < 1:
            raise ValueError(f"strategy config value {path} must be positive")
        return value

    def number(self, path: str) -> float:
        return float(self.value(path))

    def integer_list(self, path: str) -> list[int]:
        values = [int(item) for item in self.value(path)]
        if not values or any(item < 1 for item in values) or len(values) != len(set(values)):
            raise ValueError(f"strategy config list {path} must contain unique positive integers")
        return values

    def object_list(self, path: str) -> list[dict[str, Any]]:
        values = self.value(path)
        if not isinstance(values, list) or not all(isinstance(item, Mapping) for item in values):
            raise ValueError(f"strategy config value {path} must be an object list")
        return [dict(item) for item in values]

    def _validate_outlook(self) -> None:
        weights = self.value("technical.outlook.weights")
        expected = {"trend", "momentum", "volumePrice", "volatility", "structure"}
        if not isinstance(weights, Mapping) or set(weights) != expected:
            raise ValueError("technical outlook weights must define all five dimensions")
        numeric_weights = [float(value) for value in weights.values()]
        if any(value <= 0 for value in numeric_weights) or not abs(sum(numeric_weights) - 1.0) < 1e-9:
            raise ValueError("technical outlook weights must be positive and total one")
        thresholds = self.value("technical.outlook.direction_thresholds")
        if not isinstance(thresholds, Mapping) or not (
            float(thresholds["bullish"]) > float(thresholds["lean_bullish"]) > 0
            > float(thresholds["lean_bearish"]) > float(thresholds["bearish"])
        ):
            raise ValueError("technical outlook direction thresholds must be strictly ordered")
        confidence = self.value("technical.outlook.confidence_thresholds")
        numeric_confidence = [
            confidence.get("high_agreement"),
            confidence.get("medium_agreement"),
            confidence.get("minimum_full_availability"),
        ] if isinstance(confidence, Mapping) else []
        if len(numeric_confidence) != 3 or any(
            value is None or not 0 <= float(value) <= 1
            for value in numeric_confidence
        ):
            raise ValueError("technical outlook confidence thresholds must be between zero and one")
        if str(confidence.get("maximum_without_market_snapshot")) not in {
            "LOW", "MEDIUM", "HIGH"
        }:
            raise ValueError("technical outlook missing-snapshot confidence cap is invalid")


def load_strategy_config(path: str | Path | None = None) -> StrategyConfig:
    configured_path = path or os.getenv("ANALYSIS_STRATEGY_CONFIG")
    source = Path(configured_path) if configured_path else (
        Path(__file__).resolve().parent.parent / "config" / "technical-strategy-v5.json"
    )
    with source.open("r", encoding="utf-8") as handle:
        data = json.load(handle)
    if not isinstance(data, Mapping):
        raise ValueError("strategy config root must be an object")
    return StrategyConfig(data)
