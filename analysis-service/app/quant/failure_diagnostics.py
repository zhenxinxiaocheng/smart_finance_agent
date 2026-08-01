from __future__ import annotations

import math
from dataclasses import asdict, dataclass
from typing import Any, Mapping, Sequence


@dataclass(frozen=True)
class FailureDiagnosis:
    code: str
    message: str
    recommended_algorithm: str | None
    adjustment: str
    data_blocked: bool = False
    evidence: tuple[str, ...] = ()

    def as_dict(self) -> dict[str, Any]:
        result = asdict(self)
        result["recommendedAlgorithm"] = result.pop("recommended_algorithm")
        result["dataBlocked"] = result.pop("data_blocked")
        result["evidence"] = list(self.evidence)
        return result


def diagnose_failure(
    metrics: Mapping[str, Any],
    *,
    current_algorithm: str,
    policy: Mapping[str, Any],
) -> FailureDiagnosis:
    thresholds = {
        str(key): float(value)
        for key, value in policy.items()
        if _finite(value) is not None
    }
    algorithm = str(current_algorithm).strip().upper()
    failure_codes = _failure_codes(metrics)
    if {"INSUFFICIENT_DATA", "DATA_STALE"} & failure_codes:
        return FailureDiagnosis(
            code="DATA_INSUFFICIENT",
            message="历史覆盖或数据新鲜度不足",
            recommended_algorithm=None,
            adjustment="补齐历史或扩展同类资产池后再训练",
            data_blocked=True,
            evidence=tuple(sorted(failure_codes)),
        )

    turnover = _metric(metrics, "turnover")
    stressed_return = _metric(
        metrics,
        "costStressNetExcessVsStrongestBaseline",
        "costStressAnnualizedExcessReturn",
    )
    if (
        turnover is not None
        and turnover > thresholds["maximumTurnover"]
    ) or (
        stressed_return is not None
        and stressed_return < thresholds["minimumCostStressReturn"]
    ):
        return FailureDiagnosis(
            code="COST_TOO_HIGH",
            message="换手或交易成本吞噬了策略优势",
            recommended_algorithm="TREND_VOLATILITY",
            adjustment="延长信号窗口并使用波动率目标抑制换手",
            evidence=_evidence(
                ("turnover", turnover),
                ("costStressReturn", stressed_return),
            ),
        )

    pbo = _metric(metrics, "pbo")
    dsr = _metric(metrics, "deflatedSharpeProbability")
    if (
        pbo is not None and pbo > thresholds["maximumPbo"]
    ) or (
        dsr is not None
        and dsr < thresholds["minimumDeflatedSharpeProbability"]
    ):
        return FailureDiagnosis(
            code="OVERFITTING",
            message="窗口外表现不足，存在过拟合迹象",
            recommended_algorithm="ELASTIC_NET",
            adjustment="降低模型复杂度并增强正则约束",
            evidence=_evidence(("pbo", pbo), ("dsr", dsr)),
        )

    slope = _metric(metrics, "calibrationSlope")
    intercept = _metric(metrics, "calibrationIntercept")
    if (
        slope is not None
        and (
            slope < thresholds["minimumCalibrationSlope"]
            or slope > thresholds["maximumCalibrationSlope"]
        )
    ) or (
        intercept is not None
        and abs(intercept)
        > thresholds["maximumAbsoluteCalibrationIntercept"]
    ):
        return FailureDiagnosis(
            code="CALIBRATION_FAILED",
            message="概率数值与真实发生频率不一致",
            recommended_algorithm="ELASTIC_NET",
            adjustment="切换为更稳定的线性概率模型并重新校准",
            evidence=_evidence(
                ("calibrationSlope", slope),
                ("calibrationIntercept", intercept),
            ),
        )

    fold_ratio = _metric(metrics, "foldPassRatio")
    cross_window = _metric(metrics, "crossWindowVolatility")
    if (
        fold_ratio is not None
        and fold_ratio < thresholds["minimumStableFoldRatio"]
    ) or (
        cross_window is not None
        and cross_window > thresholds["maximumCrossWindowVolatility"]
    ):
        return FailureDiagnosis(
            code="REGIME_INSTABILITY",
            message="模型在不同市场阶段表现不稳定",
            recommended_algorithm="REGIME_ENSEMBLE",
            adjustment="按趋势与波动状态动态切换子模型权重",
            evidence=_evidence(
                ("foldPassRatio", fold_ratio),
                ("crossWindowVolatility", cross_window),
            ),
        )

    net_excess = _metric(
        metrics,
        "netExcessVsStrongestBaseline",
        "annualizedExcessReturn",
    )
    oos_r2 = _metric(metrics, "oosR2")
    rank_ic = _metric(metrics, "medianRankIc")
    if (
        net_excess is None
        or net_excess <= thresholds["minimumNetExcess"]
        or (
            oos_r2 is not None
            and oos_r2 <= thresholds["minimumOosR2"]
        )
        or (
            rank_ic is not None
            and rank_ic <= thresholds["minimumMedianRankIc"]
        )
    ):
        next_algorithm = (
            "EXTRA_TREES" if algorithm == "XGBOOST" else "XGBOOST"
        )
        return FailureDiagnosis(
            code="PREDICTIVE_WEAKNESS",
            message="当前特征与标签的可预测性不足",
            recommended_algorithm=next_algorithm,
            adjustment="切换非线性结构；若仍无改善则更新标签或特征版本",
            evidence=_evidence(
                ("netExcess", net_excess),
                ("oosR2", oos_r2),
                ("medianRankIc", rank_ic),
            ),
        )

    return FailureDiagnosis(
        code="ECONOMIC_GATE_FAILED",
        message="尚未形成稳定的收益增强或回撤保护",
        recommended_algorithm="REGIME_ENSEMBLE",
        adjustment="继续在新数据上优化市场状态集成",
        evidence=tuple(sorted(failure_codes)),
    )


def _failure_codes(metrics: Mapping[str, Any]) -> set[str]:
    report = metrics.get("validationReport")
    if not isinstance(report, Mapping):
        return set()
    raw = report.get("failureCodes")
    if not isinstance(raw, Sequence) or isinstance(raw, (str, bytes)):
        return set()
    return {str(item).strip().upper() for item in raw}


def _metric(metrics: Mapping[str, Any], *keys: str) -> float | None:
    for key in keys:
        if key in metrics:
            return _finite(metrics.get(key))
    return None


def _finite(value: Any) -> float | None:
    try:
        result = float(value)
    except (TypeError, ValueError):
        return None
    return result if math.isfinite(result) else None


def _evidence(*items: tuple[str, float | None]) -> tuple[str, ...]:
    return tuple(
        f"{key}={value:.6g}"
        for key, value in items
        if value is not None
    )
