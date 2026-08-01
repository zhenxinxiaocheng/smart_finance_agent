from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping

from .strategy_config import StrategyConfig


@dataclass(frozen=True)
class TechnicalObservation:
    close: float
    previous_close: float | None
    horizon_return_percent: float | None
    fast_average: float | None
    slow_average: float | None
    previous_fast_average: float | None
    previous_slow_average: float | None
    macd_histogram: float | None
    previous_macd_histogram: float | None
    rsi: float | None
    kdj_k: float | None
    kdj_d: float | None
    bollinger_upper: float | None
    bollinger_lower: float | None
    atr: float | None
    volume: float | None
    volume_average: float | None
    turnover_rate: float | None = None
    volume_ratio: float | None = None
    amplitude: float | None = None


def evaluate_outlook(
    observation: TechnicalObservation,
    levels: Mapping[str, Any],
    config: StrategyConfig,
) -> dict[str, Any]:
    components = {
        "trend": _trend_component(observation, config),
        "momentum": _momentum_component(observation, config),
        "volumePrice": _volume_price_component(observation, config),
        "volatility": _volatility_component(observation, config),
        "structure": _structure_component(observation, levels, config),
    }
    weights = config.value("technical.outlook.weights")
    available = [key for key, value in components.items() if value["available"]]
    available_weight = sum(float(weights[key]) for key in available)
    signed_score = 0.0 if available_weight == 0 else sum(
        float(components[key]["score"]) * float(weights[key])
        for key in available
    ) / available_weight
    signed_score = _clip(signed_score, config.number("technical.outlook.component_clip"))
    direction = _direction(signed_score, config)
    reasons = _ranked_messages(components, weights, "evidence")
    risks = _ranked_messages(components, weights, "risks")
    missing_inputs = _missing_inputs(observation)
    confidence = _confidence(direction, components, weights, observation, config)
    return {
        "direction": direction,
        "strength": round(abs(signed_score), 1),
        "signedScore": round(signed_score, 4),
        "confidence": confidence,
        "action": _action(direction),
        "reasons": reasons[:3],
        "risks": risks[:3],
        "invalidation": _invalidation(direction, levels),
        "components": components,
        "missingInputs": missing_inputs,
    }


def _trend_component(value: TechnicalObservation, config: StrategyConfig) -> dict[str, Any]:
    if value.fast_average is None and value.horizon_return_percent is None:
        return _component(0.0, False, [], [])
    score = 0.0
    if value.horizon_return_percent is not None:
        clip = config.number("technical.outlook.trend.return_clip_percent")
        score += _clip(
            value.horizon_return_percent,
            clip,
        ) * config.number("technical.outlook.trend.return_weight")
    price_adjustment = config.number("technical.outlook.trend.price_average_adjustment")
    if value.fast_average is not None:
        score += price_adjustment if value.close >= value.fast_average else -price_adjustment
    alignment = config.number("technical.outlook.trend.average_alignment_adjustment")
    if value.fast_average is not None and value.slow_average is not None:
        score += alignment if value.fast_average >= value.slow_average else -alignment
    slope = config.number("technical.outlook.trend.slope_adjustment")
    if value.fast_average is not None and value.previous_fast_average is not None:
        score += slope if value.fast_average >= value.previous_fast_average else -slope
    score = _clip(score, config.number("technical.outlook.component_clip"))
    evidence = ["价格与均线趋势偏强" if score > 0 else "价格与均线趋势偏弱"]
    return _component(score, True, evidence, [])


def _momentum_component(value: TechnicalObservation, config: StrategyConfig) -> dict[str, Any]:
    available = any(item is not None for item in (
        value.macd_histogram, value.rsi, value.kdj_k, value.kdj_d,
    ))
    if not available:
        return _component(0.0, False, [], [])
    score = 0.0
    if value.macd_histogram is not None:
        adjustment = config.number("technical.outlook.momentum.macd_adjustment")
        score += adjustment if value.macd_histogram >= 0 else -adjustment
        if value.previous_macd_histogram is not None:
            turn = config.number("technical.outlook.momentum.macd_turn_adjustment")
            score += turn if value.macd_histogram >= value.previous_macd_histogram else -turn
    if value.rsi is not None:
        center = config.number("technical.outlook.momentum.rsi_center")
        score += (value.rsi - center) * config.number("technical.outlook.momentum.rsi_weight")
        if value.rsi >= config.number("technical.rsi.upper_bound"):
            score -= config.number("technical.outlook.momentum.overbought_penalty")
        elif value.rsi <= config.number("technical.rsi.lower_bound"):
            score += config.number("technical.outlook.momentum.oversold_rebound_bonus")
    if value.kdj_k is not None and value.kdj_d is not None:
        cross = config.number("technical.outlook.momentum.kdj_cross_adjustment")
        score += cross if value.kdj_k >= value.kdj_d else -cross
    score = _clip(score, config.number("technical.outlook.component_clip"))
    risks = []
    if value.rsi is not None and value.rsi >= config.number("technical.rsi.upper_bound"):
        risks.append("动量指标进入偏热区域")
    evidence = ["动量指标整体偏强" if score > 0 else "动量指标整体偏弱"]
    return _component(score, True, evidence, risks)


def _volume_price_component(value: TechnicalObservation, config: StrategyConfig) -> dict[str, Any]:
    if value.volume is None or value.volume_average in (None, 0):
        return _component(0.0, False, [], [])
    volume_ratio = value.volume / value.volume_average
    price_change = 0.0 if value.previous_close in (None, 0) else value.close / value.previous_close - 1
    expansion = config.number("technical.outlook.volume_price.expansion_ratio")
    contraction = config.number("technical.outlook.volume_price.contraction_ratio")
    confirmation = config.number("technical.outlook.volume_price.confirmation_adjustment")
    divergence = config.number("technical.outlook.volume_price.divergence_adjustment")
    score = 0.0
    evidence: list[str] = []
    risks: list[str] = []
    if volume_ratio >= expansion and price_change > 0:
        score += confirmation
        evidence.append("价格上涨同时成交量放大，量价配合")
    elif volume_ratio >= expansion and price_change < 0:
        score -= divergence
        risks.append("价格下跌同时成交量放大，抛压偏强")
    elif volume_ratio <= contraction:
        evidence.append("成交量收缩，当前方向仍需放量确认")
    if value.volume_ratio is not None:
        snapshot_adjustment = config.number(
            "technical.outlook.volume_price.snapshot_confirmation_adjustment"
        )
        if value.volume_ratio >= expansion:
            score += snapshot_adjustment if price_change >= 0 else -snapshot_adjustment
    if not evidence:
        evidence.append("成交量与价格变化暂未形成强确认")
    return _component(
        _clip(score, config.number("technical.outlook.component_clip")),
        True,
        evidence,
        risks,
    )


def _volatility_component(value: TechnicalObservation, config: StrategyConfig) -> dict[str, Any]:
    if value.atr is None and value.bollinger_upper is None and value.bollinger_lower is None:
        return _component(0.0, False, [], [])
    score = 0.0
    evidence: list[str] = []
    risks: list[str] = []
    if value.atr is not None and value.close > 0:
        atr_ratio = value.atr / value.close
        if atr_ratio >= config.number("technical.outlook.volatility.atr_close_warning_ratio"):
            score -= config.number("technical.outlook.volatility.high_volatility_penalty")
            risks.append("近期波动明显放大，价格容易快速反复")
        else:
            evidence.append("当前波动仍处于可控区间")
    edge = config.number("technical.outlook.volatility.bollinger_edge_adjustment")
    if value.bollinger_upper is not None and value.close > value.bollinger_upper:
        score += edge
        risks.append("价格位于布林带上沿之外，短线回落风险增加")
    elif value.bollinger_lower is not None and value.close < value.bollinger_lower:
        score -= edge
        risks.append("价格跌出布林带下沿，弱势波动仍在延续")
    return _component(score, True, evidence, risks)


def _structure_component(
    value: TechnicalObservation,
    levels: Mapping[str, Any],
    config: StrategyConfig,
) -> dict[str, Any]:
    support = levels.get("support") if isinstance(levels, Mapping) else None
    resistance = levels.get("resistance") if isinstance(levels, Mapping) else None
    if not isinstance(support, Mapping) or not isinstance(resistance, Mapping):
        return _component(0.0, False, [], [])
    support_high = _number(support.get("high"))
    resistance_low = _number(resistance.get("low"))
    support_price = _number(support.get("price"))
    resistance_price = _number(resistance.get("price"))
    score = 0.0
    evidence: list[str] = []
    risks: list[str] = []
    breakout = config.number("technical.outlook.structure.breakout_adjustment")
    proximity = config.number("technical.outlook.structure.level_proximity_adjustment")
    distance = (value.atr or 0.0) * config.number(
        "technical.outlook.structure.near_level_atr_multiplier"
    )
    if resistance_low is not None and value.close > resistance_low:
        score += breakout
        evidence.append("价格正在测试或突破主要压力区")
    elif support_high is not None and value.close < support_high:
        score -= breakout
        risks.append("价格正在测试或跌破主要支撑区")
    elif resistance_price is not None and distance > 0 and resistance_price - value.close <= distance:
        score += proximity
        risks.append("价格接近压力区，突破前可能反复")
    elif support_price is not None and distance > 0 and value.close - support_price <= distance:
        score -= proximity
        evidence.append("价格接近支撑区，正在观察承接力度")
    else:
        evidence.append("价格仍在主要支撑与压力区间内运行")
    return _component(score, True, evidence, risks)


def _confidence(
    direction: str,
    components: Mapping[str, Mapping[str, Any]],
    weights: Mapping[str, Any],
    observation: TechnicalObservation,
    config: StrategyConfig,
) -> str:
    available = [item for item in components.values() if item["available"]]
    availability = sum(
        float(weights[key]) for key, item in components.items() if item["available"]
    )
    direction_sign = 1 if direction in {"BULLISH", "LEAN_BULLISH"} else (
        -1 if direction in {"BEARISH", "LEAN_BEARISH"} else 0
    )
    directional = [float(item["score"]) for item in available if abs(float(item["score"])) > 1e-9]
    if direction_sign == 0:
        agreement = sum(abs(score) < 35 for score in directional) / max(1, len(directional))
    else:
        agreement = sum(score * direction_sign > 0 for score in directional) / max(1, len(directional))
    high = config.number("technical.outlook.confidence_thresholds.high_agreement")
    medium = config.number("technical.outlook.confidence_thresholds.medium_agreement")
    full = config.number("technical.outlook.confidence_thresholds.minimum_full_availability")
    result = "LOW"
    if availability >= full and agreement >= high:
        result = "HIGH"
    elif availability >= medium and agreement >= medium:
        result = "MEDIUM"
    if all(item is None for item in (
        observation.turnover_rate,
        observation.volume_ratio,
        observation.amplitude,
    )):
        cap = config.text(
            "technical.outlook.confidence_thresholds.maximum_without_market_snapshot"
        )
        ranks = {"LOW": 0, "MEDIUM": 1, "HIGH": 2}
        if ranks[result] > ranks[cap]:
            result = cap
    return result


def _direction(score: float, config: StrategyConfig) -> str:
    thresholds = config.value("technical.outlook.direction_thresholds")
    if score >= float(thresholds["bullish"]):
        return "BULLISH"
    if score >= float(thresholds["lean_bullish"]):
        return "LEAN_BULLISH"
    if score <= float(thresholds["bearish"]):
        return "BEARISH"
    if score <= float(thresholds["lean_bearish"]):
        return "LEAN_BEARISH"
    return "SIDEWAYS"


def _action(direction: str) -> str:
    return {
        "BULLISH": "WATCH",
        "LEAN_BULLISH": "WATCH",
        "SIDEWAYS": "WAIT",
        "LEAN_BEARISH": "REDUCE_WATCH",
        "BEARISH": "AVOID",
    }[direction]


def _invalidation(direction: str, levels: Mapping[str, Any]) -> dict[str, Any] | None:
    support = levels.get("support") if isinstance(levels, Mapping) else None
    resistance = levels.get("resistance") if isinstance(levels, Mapping) else None
    if direction in {"BULLISH", "LEAN_BULLISH"} and isinstance(support, Mapping):
        return {"type": "BELOW", "price": _number(support.get("low"))}
    if direction in {"BEARISH", "LEAN_BEARISH"} and isinstance(resistance, Mapping):
        return {"type": "ABOVE", "price": _number(resistance.get("high"))}
    if isinstance(support, Mapping) and isinstance(resistance, Mapping):
        return {
            "type": "OUTSIDE_RANGE",
            "lower": _number(support.get("low")),
            "upper": _number(resistance.get("high")),
        }
    return None


def _ranked_messages(
    components: Mapping[str, Mapping[str, Any]],
    weights: Mapping[str, Any],
    field: str,
) -> list[str]:
    ranked: list[tuple[float, str]] = []
    for key, component in components.items():
        contribution = abs(float(component["score"]) * float(weights[key]))
        for message in component[field]:
            ranked.append((contribution, str(message)))
    ranked.sort(key=lambda item: item[0], reverse=True)
    return list(dict.fromkeys(message for _, message in ranked))


def _missing_inputs(value: TechnicalObservation) -> list[str]:
    missing = []
    if value.turnover_rate is None:
        missing.append("TURNOVER_RATE_UNAVAILABLE")
    if value.volume_ratio is None:
        missing.append("VOLUME_RATIO_UNAVAILABLE")
    if value.amplitude is None:
        missing.append("AMPLITUDE_UNAVAILABLE")
    return missing


def _component(
    score: float,
    available: bool,
    evidence: list[str],
    risks: list[str],
) -> dict[str, Any]:
    return {
        "score": round(score, 4),
        "available": available,
        "evidence": evidence,
        "risks": risks,
    }


def _clip(value: float, limit: float) -> float:
    return max(-limit, min(limit, value))


def _number(value: Any) -> float | None:
    if value is None:
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None
