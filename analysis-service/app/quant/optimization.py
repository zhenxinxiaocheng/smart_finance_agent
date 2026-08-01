from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping, Sequence

import optuna
from sqlalchemy.pool import NullPool

from .config import QuantConfig


OBJECTIVE_DIRECTIONS = (
    "maximize",
    "maximize",
    "minimize",
    "minimize",
    "minimize",
)


@dataclass(frozen=True)
class OptimizationBudget:
    startup_trials: int
    maximum_trials: int
    no_improvement_limit: int
    timeout_seconds: int


@dataclass(frozen=True)
class SuggestedCandidate:
    algorithm: str
    parameters: dict[str, int | float]


def optimization_budget(
    effective_dimension: int,
    *,
    timeout_seconds: int = 1_800,
) -> OptimizationBudget:
    if effective_dimension <= 0:
        raise ValueError("effective parameter dimension must be positive")
    return OptimizationBudget(
        startup_trials=max(20, 4 * effective_dimension),
        maximum_trials=min(240, max(80, 12 * effective_dimension)),
        no_improvement_limit=max(20, 3 * effective_dimension),
        timeout_seconds=max(1, int(timeout_seconds)),
    )


def effective_parameter_dimension(config: QuantConfig) -> int:
    strategies = _strategies(config)
    return 1 + max(len(_parameters(strategy)) for strategy in strategies)


def suggest_candidate(
    trial: optuna.trial.Trial,
    config: QuantConfig,
    *,
    horizon_days: int,
) -> SuggestedCandidate:
    if horizon_days <= 0:
        raise ValueError("horizon_days must be positive")
    strategies = _strategies(config)
    names = [str(strategy["algorithm"]).strip().upper() for strategy in strategies]
    algorithm = str(trial.suggest_categorical("algorithm", names)).strip().upper()
    strategy = next(
        item for item in strategies
        if str(item["algorithm"]).strip().upper() == algorithm
    )
    values: dict[str, int | float] = {}
    for spec in _parameters(strategy):
        name = _text(spec, "name")
        value_type = _text(spec, "type").lower()
        if value_type == "int":
            values[name] = trial.suggest_int(
                name,
                int(spec["low"]),
                int(spec["high"]),
                step=int(spec.get("step", 1)),
                log=bool(spec.get("log", False)),
            )
        elif value_type == "float":
            step = spec.get("step")
            values[name] = trial.suggest_float(
                name,
                float(spec["low"]),
                float(spec["high"]),
                step=None if step is None else float(step),
                log=bool(spec.get("log", False)),
            )
        elif value_type == "horizon_window":
            ratio = trial.suggest_float(
                f"{name}Ratio",
                float(spec["multipleLow"]),
                float(spec["multipleHigh"]),
            )
            lower = int(spec.get("minimum", 10))
            upper = int(spec.get("maximum", 252))
            values[name] = max(lower, min(upper, int(round(horizon_days * ratio))))
        else:
            raise ValueError(f"unsupported search parameter type: {value_type}")
    return SuggestedCandidate(algorithm=algorithm, parameters=values)


def create_or_load_study(
    *,
    study_name: str,
    storage: str | None,
    seed: int,
    startup_trials: int,
) -> optuna.study.Study:
    sampler = optuna.samplers.TPESampler(
        seed=seed,
        n_startup_trials=startup_trials,
        multivariate=True,
        group=True,
    )
    resolved_storage: str | optuna.storages.BaseStorage | None = storage
    if storage and storage.startswith("sqlite"):
        resolved_storage = optuna.storages.RDBStorage(
            url=storage,
            engine_kwargs={"poolclass": NullPool},
        )
    return optuna.create_study(
        study_name=study_name,
        storage=resolved_storage,
        load_if_exists=True,
        directions=list(OBJECTIVE_DIRECTIONS),
        sampler=sampler,
    )


def experiment_parameter_paths(config: QuantConfig) -> dict[str, str]:
    result: dict[str, str] = {}
    for strategy in _strategies(config):
        for spec in _parameters(strategy):
            name = _text(spec, "name")
            path = _text(spec, "configPath")
            existing = result.get(name)
            if existing is not None and existing != path:
                raise ValueError(
                    f"search parameter {name} maps to multiple config paths"
                )
            result[name] = path
    return result


def _strategies(config: QuantConfig) -> list[Mapping[str, Any]]:
    raw = config.value("autoSearch.strategies")
    if not isinstance(raw, Sequence) or isinstance(raw, (str, bytes)) or not raw:
        raise ValueError("autoSearch.strategies must be a non-empty list")
    strategies: list[Mapping[str, Any]] = []
    for item in raw:
        if not isinstance(item, Mapping):
            raise ValueError("auto search strategy must be an object")
        _text(item, "algorithm")
        _parameters(item)
        strategies.append(item)
    return strategies


def _parameters(strategy: Mapping[str, Any]) -> list[Mapping[str, Any]]:
    raw = strategy.get("parameters")
    if not isinstance(raw, Sequence) or isinstance(raw, (str, bytes)) or not raw:
        raise ValueError("auto search strategy parameters must be a non-empty list")
    result: list[Mapping[str, Any]] = []
    for item in raw:
        if not isinstance(item, Mapping):
            raise ValueError("auto search parameter must be an object")
        result.append(item)
    return result


def _text(source: Mapping[str, Any], key: str) -> str:
    value = source.get(key)
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"search space field {key} must be non-blank text")
    return value.strip()
