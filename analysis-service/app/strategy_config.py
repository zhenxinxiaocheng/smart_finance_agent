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


def load_strategy_config(path: str | Path | None = None) -> StrategyConfig:
    configured_path = path or os.getenv("ANALYSIS_STRATEGY_CONFIG")
    source = Path(configured_path) if configured_path else (
        Path(__file__).resolve().parent.parent / "config" / "technical-strategy-v2.json"
    )
    with source.open("r", encoding="utf-8") as handle:
        data = json.load(handle)
    if not isinstance(data, Mapping):
        raise ValueError("strategy config root must be an object")
    return StrategyConfig(data)
