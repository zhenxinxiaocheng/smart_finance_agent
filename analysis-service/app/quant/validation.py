from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum
from typing import Mapping


class ModelLifecycle(StrEnum):
    DRAFT = "DRAFT"
    VALIDATED = "VALIDATED"
    PAPER_VERIFIED = "PAPER_VERIFIED"
    RETIRED = "RETIRED"


class ValidationFailureCode(StrEnum):
    JOB_FAILED = "JOB_FAILED"
    MODEL_REJECTED = "MODEL_REJECTED"
    INSUFFICIENT_DATA = "INSUFFICIENT_DATA"
    DATA_STALE = "DATA_STALE"
    BENCHMARK_UNAVAILABLE = "BENCHMARK_UNAVAILABLE"


@dataclass(frozen=True)
class ValidationCheck:
    key: str
    category: str
    actual: float
    operator: str
    threshold: float
    passed: bool
    message: str


@dataclass(frozen=True)
class ValidationReport:
    lifecycle: ModelLifecycle
    passed: bool
    failure_codes: tuple[ValidationFailureCode, ...]
    checks: tuple[ValidationCheck, ...]

    def as_dict(self) -> dict[str, object]:
        return {
            "lifecycle": self.lifecycle.value,
            "passed": self.passed,
            "failureCodes": [value.value for value in self.failure_codes],
            "checks": [
                {
                    "key": item.key,
                    "category": item.category,
                    "actual": item.actual,
                    "operator": item.operator,
                    "threshold": item.threshold,
                    "passed": item.passed,
                    "message": item.message,
                }
                for item in self.checks
            ],
        }


_MINIMUM_EVENTS = {
    "SHORT": 60.0,
    "MEDIUM": 40.0,
    "LONG": 20.0,
}


def choose_calibration_method(independent_samples: int) -> str:
    if independent_samples < 0:
        raise ValueError("independent calibration sample count cannot be negative")
    return "isotonic" if independent_samples >= 1000 else "sigmoid"


def evaluate_validation(
    metrics: Mapping[str, float],
    *,
    horizon_code: str,
    benchmark_available: bool,
    data_fresh: bool,
) -> ValidationReport:
    normalized_horizon = str(horizon_code).strip().upper()
    if normalized_horizon not in _MINIMUM_EVENTS:
        raise ValueError(f"unsupported validation horizon: {horizon_code}")

    failures: list[ValidationFailureCode] = []
    if not benchmark_available:
        failures.append(ValidationFailureCode.BENCHMARK_UNAVAILABLE)
    if not data_fresh:
        failures.append(ValidationFailureCode.DATA_STALE)

    specifications = (
        ("oosR2", "PREDICTION", ">", 0.0, "样本外 R² 必须大于零"),
        ("dmPValue", "PREDICTION", "<", 0.05, "预测误差必须显著优于基线"),
        ("medianRankIc", "PREDICTION", ">", 0.0, "Rank IC 中位数必须为正"),
        ("positiveIcFoldRatio", "STABILITY", ">=", 0.6, "至少 60% 窗口的 IC 为正"),
        ("brierSkill", "CALIBRATION", ">", 0.0, "Brier Skill 必须优于历史概率"),
        ("logLossSkill", "CALIBRATION", ">", 0.0, "LogLoss Skill 必须优于历史概率"),
        ("calibrationSlope", "CALIBRATION", ">=", 0.8, "概率校准斜率不得低于 0.8"),
        ("calibrationSlope", "CALIBRATION", "<=", 1.2, "概率校准斜率不得高于 1.2"),
        ("calibrationIntercept", "CALIBRATION", "abs<=", 0.05, "概率校准截距偏差不得超过 0.05"),
        ("intervalCoverage", "INTERVAL", ">=", 0.75, "80% 区间覆盖率不得低于 75%"),
        ("intervalCoverage", "INTERVAL", "<=", 0.85, "80% 区间覆盖率不得高于 85%"),
        ("pinballSkill", "INTERVAL", ">", 0.0, "区间损失必须优于基线"),
        ("annualizedExcessReturn", "ECONOMIC", ">", 0.0, "成本后年化超额收益必须为正"),
        ("sharpe", "ECONOMIC", ">", 0.5, "样本外 Sharpe 必须大于 0.5"),
        ("deflatedSharpeProbability", "ROBUSTNESS", ">=", 0.95, "DSR 置信概率必须不低于 95%"),
        ("pbo", "ROBUSTNESS", "<=", 0.2, "回测过拟合概率不得高于 20%"),
        ("foldPassRatio", "STABILITY", ">=", 0.6, "至少 60% 外层窗口盈利"),
        ("maximumDrawdown", "RISK", "abs<=", 0.2, "最大回撤不得超过 20%"),
        (
            "costStressAnnualizedExcessReturn",
            "ROBUSTNESS",
            ">=",
            0.0,
            "1.5 倍交易成本压力下收益不得为负",
        ),
        ("walkForwardFolds", "TRAINING", ">=", 5.0, "至少需要 5 个走步验证窗口"),
        (
            "independentEventCount",
            "TRAINING",
            ">=",
            _MINIMUM_EVENTS[normalized_horizon],
            "独立样本事件数量不足",
        ),
    )
    checks: list[ValidationCheck] = []
    missing_metric = False
    for key, category, operator, threshold, message in specifications:
        if key not in metrics:
            actual = float("nan")
            passed = False
            missing_metric = True
        else:
            actual = float(metrics[key])
            passed = _compare(actual, operator, threshold)
        checks.append(
            ValidationCheck(
                key=key,
                category=category,
                actual=actual,
                operator=operator,
                threshold=threshold,
                passed=passed,
                message=message,
            )
        )

    independent_events = float(metrics.get("independentEventCount", 0.0))
    folds = float(metrics.get("walkForwardFolds", 0.0))
    if (
        independent_events < _MINIMUM_EVENTS[normalized_horizon]
        or folds < 5
        or missing_metric
    ):
        failures.append(ValidationFailureCode.INSUFFICIENT_DATA)
    if any(not item.passed for item in checks) and not missing_metric:
        failures.append(ValidationFailureCode.MODEL_REJECTED)

    unique_failures = tuple(dict.fromkeys(failures))
    passed = not unique_failures and all(item.passed for item in checks)
    return ValidationReport(
        lifecycle=ModelLifecycle.VALIDATED if passed else ModelLifecycle.DRAFT,
        passed=passed,
        failure_codes=unique_failures,
        checks=tuple(checks),
    )


def tradable_target_weight(
    lifecycle: ModelLifecycle | str,
    target_weight: float | None,
) -> float | None:
    normalized = (
        lifecycle if isinstance(lifecycle, ModelLifecycle) else ModelLifecycle(lifecycle)
    )
    if normalized not in {ModelLifecycle.VALIDATED, ModelLifecycle.PAPER_VERIFIED}:
        return None
    return target_weight


def _compare(actual: float, operator: str, threshold: float) -> bool:
    if operator == ">":
        return actual > threshold
    if operator == ">=":
        return actual >= threshold
    if operator == "<":
        return actual < threshold
    if operator == "<=":
        return actual <= threshold
    if operator == "abs<=":
        return abs(actual) <= threshold
    raise ValueError(f"unsupported validation operator: {operator}")
