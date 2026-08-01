from __future__ import annotations

import math
from dataclasses import dataclass
from enum import StrEnum
from typing import Mapping

from .config import QuantConfig


class ModelLifecycle(StrEnum):
    DRAFT = "DRAFT"
    VALIDATED = "VALIDATED"
    PAPER_VERIFIED = "PAPER_VERIFIED"
    RETIRED = "RETIRED"


class EconomicRole(StrEnum):
    RETURN_ENHANCER = "RETURN_ENHANCER"
    DRAWDOWN_GUARD = "DRAWDOWN_GUARD"
    RISK_REFERENCE = "RISK_REFERENCE"


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
    required: bool
    message: str


@dataclass(frozen=True)
class ValidationReport:
    lifecycle: ModelLifecycle
    economic_role: EconomicRole
    passed: bool
    diagnostics_passed: bool
    failure_codes: tuple[ValidationFailureCode, ...]
    checks: tuple[ValidationCheck, ...]

    def as_dict(self) -> dict[str, object]:
        return {
            "lifecycle": self.lifecycle.value,
            "economicRole": self.economic_role.value,
            "passed": self.passed,
            "diagnosticsPassed": self.diagnostics_passed,
            "failureCodes": [value.value for value in self.failure_codes],
            "checks": [
                {
                    "key": item.key,
                    "category": item.category,
                    "actual": item.actual,
                    "operator": item.operator,
                    "threshold": item.threshold,
                    "passed": item.passed,
                    "required": item.required,
                    "message": item.message,
                }
                for item in self.checks
            ],
        }


def choose_calibration_method(independent_samples: int) -> str:
    if independent_samples < 0:
        raise ValueError("independent calibration sample count cannot be negative")
    return "isotonic" if independent_samples >= 1000 else "sigmoid"


def validation_policy(config: QuantConfig) -> dict[str, float]:
    paths = {
        "minimumOosR2": "validation.diagnostics.minimumOosR2",
        "maximumDmPValue": "validation.diagnostics.maximumDmPValue",
        "minimumMedianRankIc": "validation.diagnostics.minimumMedianRankIc",
        "minimumPositiveIcFoldRatio": "validation.diagnostics.minimumPositiveIcFoldRatio",
        "minimumBrierSkill": "validation.diagnostics.minimumBrierSkill",
        "minimumLogLossSkill": "validation.diagnostics.minimumLogLossSkill",
        "minimumCalibrationSlope": "validation.diagnostics.minimumCalibrationSlope",
        "maximumCalibrationSlope": "validation.diagnostics.maximumCalibrationSlope",
        "maximumAbsoluteCalibrationIntercept": "validation.diagnostics.maximumAbsoluteCalibrationIntercept",
        "minimumIntervalCoverage": "validation.diagnostics.minimumIntervalCoverage",
        "maximumIntervalCoverage": "validation.diagnostics.maximumIntervalCoverage",
        "minimumPinballSkill": "validation.diagnostics.minimumPinballSkill",
        "targetIndependentEvents": "validation.data.targetIndependentEvents",
        "minimumStatisticalBlocks": "validation.data.minimumStatisticalBlocks",
        "minimumWalkForwardFolds": "training.walkForwardFolds",
        "minimumNetExcess": "promotion.returnEnhancer.minimumNetExcessVsStrongestBaseline",
        "minimumDeflatedSharpeProbability": "promotion.returnEnhancer.minimumDeflatedSharpeProbability",
        "maximumPbo": "promotion.returnEnhancer.maximumPbo",
        "minimumReturnFoldPassRatio": "promotion.returnEnhancer.minimumFoldPassRatio",
        "maximumReturnCrossWindowVolatility": "promotion.returnEnhancer.maximumCrossWindowVolatility",
        "minimumDrawdownReduction": "promotion.drawdownGuard.minimumDrawdownReduction",
        "maximumDownsideCapture": "promotion.drawdownGuard.maximumDownsideCapture",
        "minimumGuardFoldPassRatio": "promotion.drawdownGuard.minimumFoldPassRatio",
        "maximumGuardCrossWindowVolatility": "promotion.drawdownGuard.maximumCrossWindowVolatility",
    }
    return {key: config.number(path) for key, path in paths.items()}


def evaluate_validation(
    metrics: Mapping[str, object],
    *,
    policy: Mapping[str, float],
    benchmark_available: bool,
    data_fresh: bool,
) -> ValidationReport:
    normalized_metrics = dict(metrics)
    normalized_metrics.setdefault(
        "drawdownGuardFoldPassRatio",
        normalized_metrics.get("foldPassRatio"),
    )
    metrics = normalized_metrics

    diagnostic_specifications = (
        ("oosR2", "PREDICTION", ">", policy["minimumOosR2"], "样本外 R² 必须优于配置基线"),
        ("dmPValue", "PREDICTION", "<", policy["maximumDmPValue"], "预测误差必须显著优于基线"),
        ("medianRankIc", "PREDICTION", ">", policy["minimumMedianRankIc"], "Rank IC 中位数必须为正"),
        ("positiveIcFoldRatio", "STABILITY", ">=", policy["minimumPositiveIcFoldRatio"], "正向 IC 窗口比例不足"),
        ("brierSkill", "CALIBRATION", ">", policy["minimumBrierSkill"], "Brier Skill 必须优于历史概率"),
        ("logLossSkill", "CALIBRATION", ">", policy["minimumLogLossSkill"], "LogLoss Skill 必须优于历史概率"),
        ("calibrationSlope", "CALIBRATION", ">=", policy["minimumCalibrationSlope"], "概率校准斜率过低"),
        ("calibrationSlope", "CALIBRATION", "<=", policy["maximumCalibrationSlope"], "概率校准斜率过高"),
        ("calibrationIntercept", "CALIBRATION", "abs<=", policy["maximumAbsoluteCalibrationIntercept"], "概率校准截距偏差过高"),
        ("intervalCoverage", "INTERVAL", ">=", policy["minimumIntervalCoverage"], "预测区间覆盖率过低"),
        ("intervalCoverage", "INTERVAL", "<=", policy["maximumIntervalCoverage"], "预测区间覆盖率过高"),
        ("pinballSkill", "INTERVAL", ">", policy["minimumPinballSkill"], "区间损失必须优于基线"),
    )
    data_specifications = (
        ("walkForwardFolds", "TRAINING", ">=", policy["minimumWalkForwardFolds"], "走步验证窗口不足"),
        (
            "independentEventCount",
            "TRAINING",
            ">=",
            _minimum_independent_events(metrics, policy),
            "独立样本事件数量不足",
        ),
    )

    return_specifications = (
        (
            "netExcessVsStrongestBaseline",
            "RETURN_ENHANCER",
            ">",
            policy["minimumNetExcess"],
            "扣费后收益必须超过最强基准",
        ),
        (
            "deflatedSharpeProbability",
            "RETURN_ENHANCER",
            ">=",
            policy["minimumDeflatedSharpeProbability"],
            "DSR 置信概率必须不低于 95%",
        ),
        ("pbo", "RETURN_ENHANCER", "<=", policy["maximumPbo"], "回测过拟合概率过高"),
        (
            "foldPassRatio",
            "RETURN_ENHANCER",
            ">=",
            policy["minimumReturnFoldPassRatio"],
            "至少 60% 外层窗口有效",
        ),
        (
            "costStressNetExcessVsStrongestBaseline",
            "RETURN_ENHANCER",
            ">=",
            policy["minimumNetExcess"],
            "成本压力下仍须不弱于最强基准",
        ),
        (
            "crossWindowVolatility",
            "RETURN_ENHANCER",
            "<=",
            policy["maximumReturnCrossWindowVolatility"],
            "跨窗口表现波动不得过高",
        ),
    )
    guard_specifications = (
        (
            "drawdownReduction",
            "DRAWDOWN_GUARD",
            ">=",
            policy["minimumDrawdownReduction"],
            "相对买入持有至少降低 20% 最大回撤",
        ),
        (
            "downsideCapture",
            "DRAWDOWN_GUARD",
            "<=",
            policy["maximumDownsideCapture"],
            "下跌捕获率不得高于 80%",
        ),
        (
            "annualizedNetReturn",
            "DRAWDOWN_GUARD",
            ">=",
            _metric(metrics, "cashAnnualizedReturn"),
            "扣费后收益不得低于现金基准",
        ),
        (
            "drawdownGuardFoldPassRatio",
            "DRAWDOWN_GUARD",
            ">=",
            policy["minimumGuardFoldPassRatio"],
            "至少 60% 外层窗口有效",
        ),
        (
            "costStressAnnualizedExcessReturn",
            "DRAWDOWN_GUARD",
            ">=",
            _metric(metrics, "cashAnnualizedReturn"),
            "成本压力下收益不得低于现金基准",
        ),
        (
            "crossWindowVolatility",
            "DRAWDOWN_GUARD",
            "<=",
            policy["maximumGuardCrossWindowVolatility"],
            "跨窗口表现波动不得过高",
        ),
    )

    return_passed = benchmark_available and all(
        _compare(_metric(metrics, key), operator, threshold)
        for key, _category, operator, threshold, _message
        in return_specifications
    )
    guard_passed = all(
        _compare(_metric(metrics, key), operator, threshold)
        for key, _category, operator, threshold, _message
        in guard_specifications
    )
    economic_role = (
        EconomicRole.RETURN_ENHANCER
        if return_passed
        else EconomicRole.DRAWDOWN_GUARD
        if guard_passed
        else EconomicRole.RISK_REFERENCE
    )

    checks: list[ValidationCheck] = []
    for specifications, required in (
        (diagnostic_specifications, False),
        (data_specifications, True),
        (
            return_specifications,
            economic_role == EconomicRole.RETURN_ENHANCER,
        ),
        (
            guard_specifications,
            economic_role == EconomicRole.DRAWDOWN_GUARD,
        ),
    ):
        for key, category, operator, threshold, message in specifications:
            actual = _metric(metrics, key)
            passed = _compare(actual, operator, threshold)
            checks.append(
                ValidationCheck(
                    key=key,
                    category=category,
                    actual=actual,
                    operator=operator,
                    threshold=threshold,
                    passed=passed,
                    required=required,
                    message=message,
                )
            )

    failures: list[ValidationFailureCode] = []
    if not data_fresh:
        failures.append(ValidationFailureCode.DATA_STALE)
    missing_required_data = any(
        not item.passed and item.required
        for item in checks
        if item.category == "TRAINING"
    )
    if missing_required_data:
        failures.append(ValidationFailureCode.INSUFFICIENT_DATA)
    if economic_role == EconomicRole.RISK_REFERENCE:
        failures.append(ValidationFailureCode.MODEL_REJECTED)
        if not benchmark_available:
            failures.append(ValidationFailureCode.BENCHMARK_UNAVAILABLE)

    unique_failures = tuple(dict.fromkeys(failures))
    passed = (
        not unique_failures
        and economic_role
        in {EconomicRole.RETURN_ENHANCER, EconomicRole.DRAWDOWN_GUARD}
        and all(item.passed for item in checks if item.required)
    )
    diagnostic_checks = [
        item for item in checks
        if item.category in {"PREDICTION", "STABILITY", "CALIBRATION", "INTERVAL"}
        and not item.required
    ]
    return ValidationReport(
        lifecycle=ModelLifecycle.VALIDATED if passed else ModelLifecycle.DRAFT,
        economic_role=economic_role,
        passed=passed,
        diagnostics_passed=all(item.passed for item in diagnostic_checks),
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


def _metric(
    metrics: Mapping[str, object],
    key: str,
    default: float = float("nan"),
) -> float:
    value = metrics.get(key)
    if value is None:
        return default
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def _minimum_independent_events(
    metrics: Mapping[str, object],
    policy: Mapping[str, float],
) -> float:
    policy_minimum = policy["targetIndependentEvents"]
    evaluation_samples = _metric(metrics, "evaluationSampleCount")
    horizon_days = _metric(metrics, "horizonDays")
    if (
        not math.isfinite(evaluation_samples)
        or not math.isfinite(horizon_days)
        or evaluation_samples <= 0
        or horizon_days <= 0
    ):
        return policy_minimum
    available_capacity = math.floor(evaluation_samples / horizon_days)
    minimum_statistical_blocks = policy["minimumStatisticalBlocks"]
    if available_capacity < minimum_statistical_blocks:
        return minimum_statistical_blocks
    return min(
        policy_minimum,
        max(minimum_statistical_blocks, float(available_capacity)),
    )


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
