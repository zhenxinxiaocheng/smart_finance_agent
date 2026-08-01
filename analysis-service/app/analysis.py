from __future__ import annotations

import math
import statistics
from collections.abc import Iterable, Mapping, Sequence
from typing import Any

from .strategy_config import StrategyConfig, load_strategy_config
from .technical_outlook import TechnicalObservation, evaluate_outlook


NUMBER = int | float
STRATEGY: StrategyConfig = load_strategy_config()


def _number(value: Any, default: float | None = None) -> float | None:
    if value is None or value == "":
        return default
    try:
        result = float(value)
    except (TypeError, ValueError):
        return default
    return result if math.isfinite(result) else default


def _round(value: float | None, digits: int = 4) -> float | None:
    return None if value is None or not math.isfinite(value) else round(value, digits)


def _clamp(value: float, low: float, high: float) -> float:
    return max(low, min(high, value))


def _score_clamp(value: float) -> float:
    return _clamp(
        value,
        STRATEGY.number("score.minimum"),
        STRATEGY.number("score.maximum"),
    )


def _moving_average(values: Sequence[float], period: int) -> list[float | None]:
    result: list[float | None] = [None] * len(values)
    if period <= 0:
        return result
    running = 0.0
    for index, value in enumerate(values):
        running += value
        if index >= period:
            running -= values[index - period]
        if index >= period - 1:
            result[index] = running / period
    return result


def _ema(values: Sequence[float], period: int) -> list[float]:
    if not values:
        return []
    alpha = 2.0 / (period + 1)
    result = [values[0]]
    for value in values[1:]:
        result.append(alpha * value + (1 - alpha) * result[-1])
    return result


def _rsi(values: Sequence[float], period: int) -> list[float | None]:
    result: list[float | None] = [None] * len(values)
    if len(values) <= period:
        return result
    gains = [max(values[i] - values[i - 1], 0.0) for i in range(1, len(values))]
    losses = [max(values[i - 1] - values[i], 0.0) for i in range(1, len(values))]
    average_gain = sum(gains[:period]) / period
    average_loss = sum(losses[:period]) / period

    def rsi_value() -> float:
        if average_loss == 0:
            return 100.0 if average_gain > 0 else 50.0
        relative_strength = average_gain / average_loss
        return 100 - 100 / (1 + relative_strength)

    result[period] = rsi_value()
    for value_index in range(period + 1, len(values)):
        delta_index = value_index - 1
        average_gain = (average_gain * (period - 1) + gains[delta_index]) / period
        average_loss = (average_loss * (period - 1) + losses[delta_index]) / period
        result[value_index] = rsi_value()
    return result


def _atr(highs: Sequence[float], lows: Sequence[float], closes: Sequence[float], period: int) -> list[float | None]:
    if not closes:
        return []
    true_ranges = [highs[0] - lows[0]]
    for index in range(1, len(closes)):
        true_ranges.append(max(
            highs[index] - lows[index],
            abs(highs[index] - closes[index - 1]),
            abs(lows[index] - closes[index - 1]),
        ))
    result: list[float | None] = [None] * len(closes)
    if len(closes) < period:
        return result
    current = sum(true_ranges[:period]) / period
    result[period - 1] = current
    for index in range(period, len(closes)):
        current = (current * (period - 1) + true_ranges[index]) / period
        result[index] = current
    return result


def _kdj(highs: Sequence[float], lows: Sequence[float], closes: Sequence[float], period: int) -> tuple[list[float | None], list[float | None], list[float | None]]:
    k_values: list[float | None] = [None] * len(closes)
    d_values: list[float | None] = [None] * len(closes)
    j_values: list[float | None] = [None] * len(closes)
    k = 50.0
    d = 50.0
    for index in range(period - 1, len(closes)):
        highest = max(highs[index - period + 1:index + 1])
        lowest = min(lows[index - period + 1:index + 1])
        rsv = 50.0 if highest == lowest else (closes[index] - lowest) / (highest - lowest) * 100
        k = 2 / 3 * k + 1 / 3 * rsv
        d = 2 / 3 * d + 1 / 3 * k
        k_values[index] = k
        d_values[index] = d
        j_values[index] = 3 * k - 2 * d
    return k_values, d_values, j_values


def _normalized_quotes(records: Iterable[Mapping[str, Any]]) -> list[dict[str, Any]]:
    normalized: list[dict[str, Any]] = []
    for record in records:
        close = _number(
            record.get("close")
            or record.get("nav")
            or record.get("unit_nav")
            or record.get("unitNav")
        )
        if close is None or close <= 0:
            continue
        open_price = _number(record.get("open"), close) or close
        high = _number(record.get("high"), max(open_price, close)) or max(open_price, close)
        low = _number(record.get("low"), min(open_price, close)) or min(open_price, close)
        normalized.append({
            "date": str(record.get("data_date") or record.get("dataDate") or record.get("date") or ""),
            "open": open_price,
            "high": max(high, open_price, close),
            "low": min(low, open_price, close),
            "close": close,
            "volume": _number(record.get("volume"), 0.0) or 0.0,
        })
    normalized.sort(key=lambda item: item["date"])
    return normalized


def _trend_score(closes: Sequence[float], fast_average: Sequence[float | None],
                 slow_average: Sequence[float | None], lookback: int) -> float:
    index = len(closes) - 1
    start = max(0, index - max(lookback, 2) + 1)
    first = closes[start]
    momentum = 0.0 if first == 0 else (closes[index] / first - 1) * 100
    clip = STRATEGY.number("technical.trend.momentum_clip")
    score = STRATEGY.number("technical.trend.base_score") + max(
        -clip, min(clip, momentum * STRATEGY.number("technical.trend.momentum_weight")))
    price_adjustment = STRATEGY.number("technical.trend.price_vs_average_adjustment")
    alignment_adjustment = STRATEGY.number("technical.trend.average_alignment_adjustment")
    if fast_average[index] is not None:
        score += price_adjustment if closes[index] >= fast_average[index] else -price_adjustment
    if fast_average[index] is not None and slow_average[index] is not None:
        score += alignment_adjustment if fast_average[index] >= slow_average[index] else -alignment_adjustment
    return _score_clamp(score)


def _verdict(score: float) -> str:
    if score >= STRATEGY.number("score.favorable_minimum"):
        return "FAVORABLE"
    if score >= STRATEGY.number("score.wait_minimum"):
        return "WAIT"
    return "WEAK"


def _cluster_levels(points: Sequence[float], tolerance: float) -> list[tuple[float, int]]:
    if not points:
        return []
    clusters: list[list[float]] = []
    for point in sorted(points):
        if not clusters or abs(point - statistics.mean(clusters[-1])) > tolerance:
            clusters.append([point])
        else:
            clusters[-1].append(point)
    return [(statistics.mean(cluster), len(cluster)) for cluster in clusters]


def _price_levels(highs: Sequence[float], lows: Sequence[float], close: float, atr: float) -> dict[str, Any]:
    window = STRATEGY.integer("technical.levels.swing_window")
    swing_highs: list[float] = []
    swing_lows: list[float] = []
    for index in range(window, len(highs) - window):
        if highs[index] >= max(highs[index - window:index + window + 1]):
            swing_highs.append(highs[index])
        if lows[index] <= min(lows[index - window:index + window + 1]):
            swing_lows.append(lows[index])
    tolerance = max(atr, close * STRATEGY.number("technical.levels.close_tolerance_ratio"))
    sample_size = STRATEGY.integer("technical.levels.cluster_sample_size")
    high_clusters = _cluster_levels(swing_highs[-sample_size:], tolerance)
    low_clusters = _cluster_levels(swing_lows[-sample_size:], tolerance)
    support_candidates = [(level, count) for level, count in low_clusters if level <= close]
    resistance_candidates = [(level, count) for level, count in high_clusters if level >= close]
    support_level, support_count = max(support_candidates, default=(close - atr, 1), key=lambda item: item[0])
    resistance_level, resistance_count = min(resistance_candidates, default=(close + atr, 1), key=lambda item: item[0])
    if resistance_level <= support_level:
        resistance_level = max(close + atr, support_level + atr)
    half_width = max(
        atr * STRATEGY.number("technical.levels.zone_atr_half_width"),
        close * STRATEGY.number("technical.levels.zone_close_half_width_ratio"),
    )

    def zone(level: float, touches: int) -> dict[str, Any]:
        return {
            "price": _round(level),
            "low": _round(level - half_width),
            "high": _round(level + half_width),
            "touches": touches,
        }

    return {"support": zone(support_level, support_count), "resistance": zone(resistance_level, resistance_count)}


def analyze_technical(
    records: Iterable[Mapping[str, Any]],
    horizons: Mapping[str, Sequence[int]],
    primary_horizon: str,
    market_snapshot: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    quotes = _normalized_quotes(records)
    minimum_history = STRATEGY.integer("technical.minimum_history_days")
    if len(quotes) < minimum_history:
        return {
            "status": "INSUFFICIENT", "series": [], "horizons": {}, "levels": {},
            "strategyVersion": STRATEGY.version,
            "reason": "可用历史数据不足以启动当前策略",
            "availableHistoryDays": len(quotes),
            "requiredHistoryDays": minimum_history,
        }
    closes = [item["close"] for item in quotes]
    highs = [item["high"] for item in quotes]
    lows = [item["low"] for item in quotes]
    volumes = [item["volume"] for item in quotes]
    trend_fast = STRATEGY.integer("technical.trend.fast_moving_average")
    trend_slow = STRATEGY.integer("technical.trend.slow_moving_average")
    signal_fast = STRATEGY.integer("technical.signals.fast_moving_average")
    signal_slow = STRATEGY.integer("technical.signals.slow_moving_average")
    boll_period = STRATEGY.integer("technical.bollinger.period")
    ma_periods = sorted(set(STRATEGY.integer_list("technical.moving_average_periods")
                            + [trend_fast, trend_slow, signal_fast, signal_slow, boll_period]))
    volume_signal_period = STRATEGY.integer("technical.signals.volume_moving_average")
    volume_periods = sorted(set(STRATEGY.integer_list("technical.volume_moving_average_periods")
                                + [volume_signal_period]))
    ma = {period: _moving_average(closes, period) for period in ma_periods}
    volume_ma = {period: _moving_average(volumes, period) for period in volume_periods}
    macd_fast_period = STRATEGY.integer("technical.macd.fast_period")
    macd_slow_period = STRATEGY.integer("technical.macd.slow_period")
    macd_signal_period = STRATEGY.integer("technical.macd.signal_period")
    ema_fast = _ema(closes, macd_fast_period)
    ema_slow = _ema(closes, macd_slow_period)
    diff = [fast - slow for fast, slow in zip(ema_fast, ema_slow)]
    dea = _ema(diff, macd_signal_period)
    histogram_multiplier = STRATEGY.number("technical.macd.histogram_multiplier")
    histogram = [(value - signal) * histogram_multiplier for value, signal in zip(diff, dea)]
    rsi_period = STRATEGY.integer("technical.rsi.period")
    atr_period = STRATEGY.integer("technical.atr.period")
    kdj_period = STRATEGY.integer("technical.kdj.period")
    rsi_values = _rsi(closes, rsi_period)
    atr_values = _atr(highs, lows, closes, atr_period)
    k_values, d_values, j_values = _kdj(highs, lows, closes, kdj_period)
    boll_mid = ma[boll_period]
    boll_upper: list[float | None] = [None] * len(closes)
    boll_lower: list[float | None] = [None] * len(closes)
    boll_deviations = STRATEGY.number("technical.bollinger.standard_deviations")
    for index in range(boll_period - 1, len(closes)):
        deviation = statistics.pstdev(closes[index - boll_period + 1:index + 1])
        boll_upper[index] = (boll_mid[index] or closes[index]) + boll_deviations * deviation
        boll_lower[index] = (boll_mid[index] or closes[index]) - boll_deviations * deviation

    series: list[dict[str, Any]] = []
    for index, quote in enumerate(quotes):
        item = dict(quote)
        for period in ma:
            item[f"ma{period}"] = _round(ma[period][index])
        for period in volume_ma:
            item[f"volumeMa{period}"] = _round(volume_ma[period][index])
        item.update({
            "macd": _round(histogram[index]),
            "macdDiff": _round(diff[index]),
            "macdSignal": _round(dea[index]),
            "rsi": _round(rsi_values[index]),
            "kdjK": _round(k_values[index]),
            "kdjD": _round(d_values[index]),
            "kdjJ": _round(j_values[index]),
            "bollMid": _round(boll_mid[index]),
            "bollUpper": _round(boll_upper[index]),
            "bollLower": _round(boll_lower[index]),
            "atr": _round(atr_values[index]),
        })
        series.append(item)

    if not horizons:
        raise ValueError("at least one horizon is required")
    if primary_horizon not in horizons:
        raise ValueError("primary horizon must exist in horizons")
    latest_atr = atr_values[-1] or max(
        closes[-1] * STRATEGY.number("technical.levels.default_atr_close_ratio"),
        STRATEGY.number("technical.levels.default_atr_floor"),
    )
    horizon_results: dict[str, dict[str, Any]] = {}
    for name, configured_bounds in horizons.items():
        bounds = list(configured_bounds)
        minimum, maximum = int(bounds[0]), int(bounds[-1])
        history_complete = len(closes) >= maximum
        effective_lookback = min(maximum, len(closes) - 1)
        horizon_fast_period = max(2, min(minimum, effective_lookback))
        horizon_slow_period = max(horizon_fast_period, effective_lookback)
        horizon_fast_average = _moving_average(closes, horizon_fast_period)
        horizon_slow_average = _moving_average(closes, horizon_slow_period)
        level_lookback = min(len(closes), max(
            STRATEGY.integer("technical.levels.minimum_lookback_days"),
            effective_lookback * STRATEGY.integer("technical.levels.horizon_multiplier"),
        ))
        level_highs = highs[-level_lookback:]
        level_lows = lows[-level_lookback:]
        level_closes = closes[-level_lookback:]
        horizon_atr_series = _atr(level_highs, level_lows, level_closes, atr_period)
        horizon_atr = horizon_atr_series[-1] or latest_atr
        horizon_levels = _price_levels(level_highs, level_lows, closes[-1], horizon_atr)
        horizon_support = horizon_levels["support"]
        horizon_resistance = horizon_levels["resistance"]
        horizon_action_zones = {
            "buy": horizon_support,
            "add": {"low": horizon_support["low"], "high": horizon_support["price"]},
            "hold": {"low": horizon_support["price"], "high": horizon_resistance["price"]},
            "reduce": horizon_resistance,
            "risk": {"price": _round(
                horizon_support["low"]
                - horizon_atr * STRATEGY.number("technical.levels.risk_atr_multiplier"))},
        }
        snapshot = market_snapshot or {}
        outlook = evaluate_outlook(
            TechnicalObservation(
                close=closes[-1],
                previous_close=closes[-2] if len(closes) > 1 else None,
                horizon_return_percent=(
                    (closes[-1] / closes[-effective_lookback - 1] - 1) * 100
                    if effective_lookback > 0 and closes[-effective_lookback - 1]
                    else None
                ),
                fast_average=horizon_fast_average[-1],
                slow_average=horizon_slow_average[-1],
                previous_fast_average=horizon_fast_average[-2],
                previous_slow_average=horizon_slow_average[-2],
                macd_histogram=histogram[-1],
                previous_macd_histogram=histogram[-2],
                rsi=rsi_values[-1],
                kdj_k=k_values[-1],
                kdj_d=d_values[-1],
                bollinger_upper=boll_upper[-1],
                bollinger_lower=boll_lower[-1],
                atr=horizon_atr,
                volume=volumes[-1],
                volume_average=volume_ma[volume_signal_period][-1],
                turnover_rate=_number(
                    snapshot.get("turnoverRate", snapshot.get("turnover_rate"))
                ),
                volume_ratio=_number(
                    snapshot.get("volumeRatio", snapshot.get("volume_ratio"))
                ),
                amplitude=_number(snapshot.get("amplitude")),
            ),
            horizon_levels,
            STRATEGY,
        )
        if not history_complete:
            outlook = dict(outlook)
            outlook["confidence"] = "LOW"
            outlook["missingInputs"] = list(dict.fromkeys([
                *outlook["missingInputs"],
                "HORIZON_HISTORY_INCOMPLETE",
            ]))
        legacy_score = _score_clamp(50.0 + float(outlook["signedScore"]) / 2.0)
        legacy_verdict = (
            "FAVORABLE"
            if outlook["direction"] in {"BULLISH", "LEAN_BULLISH"}
            else "WEAK"
            if outlook["direction"] in {"BEARISH", "LEAN_BEARISH"}
            else "WAIT"
        )
        horizon_results[name] = {
            "status": "READY" if history_complete else "LIMITED",
            "minimumDays": minimum,
            "maximumDays": maximum,
            "availableHistoryDays": len(closes),
            "requiredHistoryDays": maximum,
            "effectiveLookbackDays": effective_lookback,
            "trendPeriods": {
                "fast": horizon_fast_period,
                "slow": horizon_slow_period,
            },
            "score": _round(legacy_score, 1),
            "verdict": legacy_verdict,
            "outlook": outlook,
            "levelLookbackDays": level_lookback,
            "levels": horizon_levels,
            "actionZones": horizon_action_zones,
        }
    result = {
        "strategyVersion": STRATEGY.version,
        "indicatorPeriods": {
            "movingAverages": ma_periods,
            "volumeMovingAverages": volume_periods,
            "rsi": rsi_period,
            "rsiBounds": [STRATEGY.number("technical.rsi.lower_bound"),
                          STRATEGY.number("technical.rsi.upper_bound")],
            "atr": atr_period,
            "kdj": kdj_period,
            "bollinger": boll_period,
            "macd": {"fast": macd_fast_period, "slow": macd_slow_period,
                     "signal": macd_signal_period},
        },
        "horizons": horizon_results,
        "primaryHorizon": primary_horizon,
        "series": series,
        "signals": {
            "movingAverageAlignment": "BULLISH" if (ma[signal_fast][-1] or 0) >= (ma[signal_slow][-1] or math.inf) else "BEARISH",
            "momentum": "POSITIVE" if histogram[-1] >= 0 else "NEGATIVE",
            "volume": "EXPANDING" if volumes[-1] >= (volume_ma[volume_signal_period][-1] or volumes[-1]) else "CONTRACTING",
        },
    }
    primary = horizon_results[primary_horizon]
    result.update({
        "status": primary["status"],
        "score": primary["score"],
        "verdict": primary["verdict"],
        "outlook": primary["outlook"],
        "levels": primary["levels"],
        "actionZones": primary["actionZones"],
    })
    if primary["status"] == "LIMITED":
        result.update({
            "reason": "可用历史尚未覆盖完整配置周期，当前为低置信度走势研判",
            "availableHistoryDays": primary["availableHistoryDays"],
            "requiredHistoryDays": primary["requiredHistoryDays"],
        })
    return result


def _period_return(values: Sequence[float], days: int) -> float | None:
    if len(values) <= days or values[-days - 1] == 0:
        return None
    return (values[-1] / values[-days - 1] - 1) * 100


def _maximum_drawdown(values: Sequence[float]) -> tuple[float, int, int]:
    if not values:
        return 0.0, 0, 0
    peak = values[0]
    peak_index = 0
    worst = 0.0
    trough_index = 0
    worst_peak_index = 0
    for index, value in enumerate(values):
        if value > peak:
            peak = value
            peak_index = index
        drawdown = value / peak - 1 if peak else 0.0
        if drawdown < worst:
            worst = drawdown
            worst_peak_index = peak_index
            trough_index = index
    return worst * 100, worst_peak_index, trough_index


def analyze_fund(records: Iterable[Mapping[str, Any]]) -> dict[str, Any]:
    quotes = _normalized_quotes(records)
    minimum_history = STRATEGY.integer("fund.minimum_history_days")
    if len(quotes) < minimum_history:
        return {
            "status": "INSUFFICIENT", "series": [],
            "strategyVersion": STRATEGY.version,
            "reason": "可用历史数据不足以启动当前基金策略",
            "availableHistoryDays": len(quotes),
            "requiredHistoryDays": minimum_history,
        }
    values = [item["close"] for item in quotes]
    fast_average_period = STRATEGY.integer("fund.fast_moving_average")
    slow_average_period = STRATEGY.integer("fund.slow_moving_average")
    ma_periods = sorted(set(STRATEGY.integer_list("fund.moving_average_periods")
                            + [fast_average_period, slow_average_period]))
    ma = {period: _moving_average(values, period) for period in ma_periods}
    daily_returns = [values[index] / values[index - 1] - 1 for index in range(1, len(values)) if values[index - 1]]
    annualized_volatility = statistics.stdev(daily_returns) * math.sqrt(
        STRATEGY.integer("fund.annualization_days")) * 100 if len(daily_returns) > 1 else 0.0
    max_drawdown, peak_index, trough_index = _maximum_drawdown(values)
    recovered = values[-1] >= values[peak_index] if peak_index < len(values) else False
    latest_fast_average = ma[fast_average_period][-1] or values[-1]
    latest_slow_average = ma[slow_average_period][-1]
    return_metrics = []
    returns_by_code: dict[str, float | None] = {}
    for configured_period in STRATEGY.object_list("fund.return_periods"):
        code = str(configured_period["code"])
        days = int(configured_period["days"])
        value = _period_return(values, days)
        returns_by_code[code] = value
        return_metrics.append({
            "code": code,
            "displayName": str(configured_period["display_name"]),
            "days": days,
            "value": _round(value, 2),
        })
    short_return = returns_by_code.get(STRATEGY.text("fund.short_return_code"))
    medium_return = returns_by_code.get(STRATEGY.text("fund.medium_return_code"))
    long_return = returns_by_code.get(STRATEGY.text("fund.long_return_code"))
    score = STRATEGY.number("fund.score.base")
    short_clip = STRATEGY.number("fund.score.short_return_clip")
    medium_clip = STRATEGY.number("fund.score.medium_return_clip")
    score += max(-short_clip, min(short_clip, (short_return or 0)
                                  * STRATEGY.number("fund.score.short_return_weight")))
    score += max(-medium_clip, min(medium_clip, (medium_return or 0)
                                   * STRATEGY.number("fund.score.medium_return_weight")))
    average_adjustment = STRATEGY.number("fund.score.above_average_adjustment")
    score += average_adjustment if values[-1] >= latest_fast_average else -average_adjustment
    score -= max(0, min(
        STRATEGY.number("fund.score.drawdown_penalty_cap"),
        abs(max_drawdown) - STRATEGY.number("fund.score.drawdown_penalty_start"),
    ))
    score = _score_clamp(score)
    if (max_drawdown <= STRATEGY.number("fund.actions.pause_drawdown_maximum")
            and values[-1] < latest_fast_average):
        action = "PAUSE"
    elif (long_return is not None
          and long_return >= STRATEGY.number("fund.actions.take_profit_long_return_minimum")
          and values[-1] > latest_fast_average
          * STRATEGY.number("fund.actions.take_profit_price_to_average_ratio")):
        action = "TAKE_PROFIT"
    elif (values[-1] >= latest_fast_average
          and (latest_slow_average is None or latest_fast_average >= latest_slow_average)):
        action = "ACCUMULATE"
    else:
        action = "HOLD"
    series: list[dict[str, Any]] = []
    for index, quote in enumerate(quotes):
        item = {
            "date": quote["date"],
            "nav": quote["close"],
        }
        for period in ma:
            item[f"ma{period}"] = _round(ma[period][index])
        series.append(item)
    return {
        "status": "READY",
        "strategyVersion": STRATEGY.version,
        "indicatorPeriods": {"movingAverages": ma_periods},
        "score": _round(score, 1),
        "verdict": _verdict(score),
        "returnMetrics": return_metrics,
        "annualizedVolatility": _round(annualized_volatility, 2),
        "maxDrawdown": _round(max_drawdown, 2),
        "drawdownRecovered": recovered,
        "drawdownPeakDate": quotes[peak_index]["date"],
        "drawdownTroughDate": quotes[trough_index]["date"],
        "action": action,
        "series": series,
    }


def _field(period: Mapping[str, Any], name: str) -> float | None:
    return _number(period.get(name))


def _average_available(periods: Sequence[Mapping[str, Any]], field: str) -> float | None:
    values = [_field(period, field) for period in periods]
    available = [value for value in values if value is not None]
    return statistics.mean(available) if available else None


def analyze_fundamentals(periods: Iterable[Mapping[str, Any]]) -> dict[str, Any]:
    rows = list(periods)
    minimum_periods = STRATEGY.integer("fundamental.minimum_periods")
    minimum_dimensions = STRATEGY.integer("fundamental.minimum_dimensions")
    requirements = {
        "minimumPeriods": minimum_periods,
        "minimumDimensions": minimum_dimensions,
    }
    if len(rows) < minimum_periods:
        return {"status": "INSUFFICIENT", "verdict": "INSUFFICIENT", "coverage": 0,
                "dimensions": {}, "requirements": requirements,
                "reason": f"当前只有 {len(rows)} 期数据，当前策略至少需要 {minimum_periods} 期",
                "strategyVersion": STRATEGY.version}
    dimensions: dict[str, dict[str, Any]] = {}
    newest = rows[0]
    oldest = rows[-1]
    revenue_new, revenue_old = _field(newest, "revenue"), _field(oldest, "revenue")
    profit_new, profit_old = _field(newest, "netProfit"), _field(oldest, "netProfit")
    direct_revenue_growth = _average_available(rows, "revenueGrowth")
    direct_profit_growth = _average_available(rows, "netProfitGrowth")
    if direct_revenue_growth is not None and direct_profit_growth is not None:
        score = _score_clamp(
            STRATEGY.number("fundamental.base_score")
            + (direct_revenue_growth + direct_profit_growth)
            * STRATEGY.number("fundamental.direct_growth_weight"))
        dimensions["growth"] = {"score": _round(score, 1), "revenueGrowth": _round(direct_revenue_growth, 2), "profitGrowth": _round(direct_profit_growth, 2)}
    elif revenue_new is not None and revenue_old not in (None, 0) and profit_new is not None and profit_old not in (None, 0):
        years = max(1, len(rows) - 1)
        revenue_growth = (revenue_new / revenue_old) ** (1 / years) - 1 if revenue_new > 0 and revenue_old > 0 else -1
        profit_growth = (profit_new / profit_old) ** (1 / years) - 1 if profit_new > 0 and profit_old > 0 else -1
        score = _score_clamp(
            STRATEGY.number("fundamental.base_score")
            + (revenue_growth + profit_growth)
            * STRATEGY.number("fundamental.cagr_growth_weight"))
        dimensions["growth"] = {"score": _round(score, 1), "revenueCagr": _round(revenue_growth * 100, 2), "profitCagr": _round(profit_growth * 100, 2)}
    roe = _average_available(rows, "roe")
    gross_margin = _average_available(rows, "grossMargin")
    net_margin = _average_available(rows, "netMargin")
    if sum(value is not None for value in (roe, gross_margin, net_margin)) >= STRATEGY.integer(
            "fundamental.minimum_profitability_metrics"):
        score = 0.0
        weight = 0
        profitability_scales = STRATEGY.value("fundamental.profitability_scales")
        for value, scale in (
                (roe, float(profitability_scales["roe"])),
                (gross_margin, float(profitability_scales["grossMargin"])),
                (net_margin, float(profitability_scales["netMargin"]))):
            if value is not None:
                score += _score_clamp(value * scale)
                weight += 1
        dimensions["profitability"] = {"score": _round(score / weight, 1), "roe": _round(roe, 2), "grossMargin": _round(gross_margin, 2), "netMargin": _round(net_margin, 2)}
    direct_cash_ratio = _average_available(rows, "cashToProfit")
    cash_flow = _average_available(rows, "operatingCashFlow")
    net_profit = _average_available(rows, "netProfit")
    if direct_cash_ratio is not None:
        cash_ratio = (direct_cash_ratio / 100
                      if direct_cash_ratio > STRATEGY.number("fundamental.cash_ratio_percent_boundary")
                      else direct_cash_ratio)
        dimensions["cashQuality"] = {"score": _round(_score_clamp(
            cash_ratio * STRATEGY.number("fundamental.cash_quality_weight")), 1),
            "cashToProfit": _round(cash_ratio, 2)}
    elif cash_flow is not None and net_profit not in (None, 0):
        cash_ratio = cash_flow / net_profit
        dimensions["cashQuality"] = {"score": _round(_score_clamp(
            cash_ratio * STRATEGY.number("fundamental.cash_quality_weight")), 1),
            "cashToProfit": _round(cash_ratio, 2)}
    debt_ratio = _average_available(rows, "debtRatio")
    current_ratio = _average_available(rows, "currentRatio")
    if debt_ratio is not None and current_ratio is not None:
        resilience = _score_clamp(
            STRATEGY.number("score.maximum") - debt_ratio
            + min(current_ratio, STRATEGY.number("fundamental.current_ratio_cap"))
            * STRATEGY.number("fundamental.current_ratio_weight"))
        dimensions["resilience"] = {"score": _round(resilience, 1), "debtRatio": _round(debt_ratio, 2), "currentRatio": _round(current_ratio, 2)}
    pe = _average_available(rows, "pe")
    pb = _average_available(rows, "pb")
    dividend_yield = _average_available(rows, "dividendYield")
    if sum(value is not None for value in (pe, pb, dividend_yield)) >= STRATEGY.integer(
            "fundamental.minimum_valuation_metrics"):
        neutral = STRATEGY.number("fundamental.valuation_neutral_score")
        pe_score = neutral if pe is None else _score_clamp(
            STRATEGY.number("score.maximum")
            - max(0, pe - STRATEGY.number("fundamental.pe_baseline"))
            * STRATEGY.number("fundamental.pe_penalty_weight"))
        pb_score = neutral if pb is None else _score_clamp(
            STRATEGY.number("score.maximum")
            - max(0, pb - STRATEGY.number("fundamental.pb_baseline"))
            * STRATEGY.number("fundamental.pb_penalty_weight"))
        dividend_score = neutral if dividend_yield is None else _score_clamp(
            dividend_yield * STRATEGY.number("fundamental.dividend_weight"))
        dimensions["valuation"] = {"score": _round((pe_score + pb_score + dividend_score) / 3, 1), "pe": _round(pe, 2), "pb": _round(pb, 2), "dividendYield": _round(dividend_yield, 2)}
    coverage = len(dimensions)
    if coverage < minimum_dimensions:
        return {"status": "INSUFFICIENT", "verdict": "INSUFFICIENT", "coverage": coverage,
                "dimensions": dimensions, "requirements": requirements,
                "reason": f"当前只有 {coverage} 个有效维度，当前策略至少需要 {minimum_dimensions} 个",
                "strategyVersion": STRATEGY.version}
    overall = statistics.mean(float(item["score"]) for item in dimensions.values())
    verdict = ("ATTRACTIVE" if overall >= STRATEGY.number("fundamental.attractive_minimum")
               else "FAIR" if overall >= STRATEGY.number("fundamental.fair_minimum")
               else "CAUTIOUS")
    return {"status": "READY", "verdict": verdict, "score": _round(overall, 1),
            "coverage": coverage, "dimensions": dimensions, "periods": len(rows),
            "requirements": requirements,
            "strategyVersion": STRATEGY.version}


def historical_outlook_at(
    records: Iterable[Mapping[str, Any]],
    as_of_index: int,
    configured_bounds: Sequence[int],
) -> dict[str, Any]:
    """Evaluate one historical point with exactly the same rule as live analysis."""
    rows = list(records)
    if as_of_index < 0 or as_of_index >= len(rows):
        raise IndexError("historical outlook index is outside the available records")
    historical_code = "HISTORICAL"
    result = analyze_technical(
        rows[:as_of_index + 1],
        {historical_code: configured_bounds},
        historical_code,
    )
    if result.get("status") == "INSUFFICIENT":
        raise ValueError("historical point does not contain enough data")
    return dict(result["outlook"])


def _invalidation_triggered(
    invalidation: Mapping[str, Any] | None,
    forward_prices: Sequence[float],
) -> bool:
    if not invalidation or not forward_prices:
        return False
    invalidation_type = invalidation.get("type")
    if invalidation_type == "BELOW":
        price = _number(invalidation.get("price"))
        return price is not None and any(value < price for value in forward_prices)
    if invalidation_type == "ABOVE":
        price = _number(invalidation.get("price"))
        return price is not None and any(value > price for value in forward_prices)
    if invalidation_type == "OUTSIDE_RANGE":
        lower = _number(invalidation.get("lower"))
        upper = _number(invalidation.get("upper"))
        return any(
            (lower is not None and value < lower) or (upper is not None and value > upper)
            for value in forward_prices
        )
    return False


def _maximum_adverse_excursion(
    direction: str,
    entry_price: float,
    forward_prices: Sequence[float],
) -> float:
    path_returns = [(price / entry_price - 1) * 100 for price in forward_prices]
    if not path_returns:
        return 0.0
    if direction in {"BULLISH", "LEAN_BULLISH"}:
        return min(0.0, min(path_returns))
    if direction in {"BEARISH", "LEAN_BEARISH"}:
        return min(0.0, -max(path_returns))
    return min(0.0, -max(abs(value) for value in path_returns))


def _backtest_outlook(
    records: Iterable[Mapping[str, Any]],
    configured_bounds: Sequence[int],
    evaluation_days: int,
) -> dict[str, Any]:
    quotes = _normalized_quotes(records)
    closes = [item["close"] for item in quotes]
    minimum_history = max(
        STRATEGY.integer("technical.minimum_history_days"),
        STRATEGY.integer("backtest.minimum_history_days"),
    )
    if len(closes) < minimum_history + evaluation_days:
        return {
            "status": "INSUFFICIENT", "horizonDays": evaluation_days,
            "occurrences": 0, "matchedDirection": None,
            "positiveRate": None, "negativeRate": None, "winRate": None,
            "medianForwardReturn": None, "maximumAdverseExcursion": None,
            "invalidationRate": None,
            "maxDrawdown": _round(_maximum_drawdown(closes)[0], 2),
            "strategyVersion": STRATEGY.version,
        }

    replay_rows = [{
        "date": item["date"], "open": item["open"], "high": item["high"],
        "low": item["low"], "close": item["close"], "volume": item["volume"],
    } for item in quotes]
    live = analyze_technical(
        replay_rows,
        {"CURRENT": configured_bounds},
        "CURRENT",
    )
    matched_direction = str(live["outlook"]["direction"])
    spacing = max(
        STRATEGY.integer("backtest.minimum_signal_spacing_days"),
        evaluation_days // 2,
    )
    forward_returns: list[float] = []
    adverse_excursions: list[float] = []
    invalidations = 0
    for index in range(minimum_history - 1, len(quotes) - evaluation_days, spacing):
        outlook = historical_outlook_at(replay_rows, index, configured_bounds)
        if outlook["direction"] != matched_direction:
            continue
        entry_price = closes[index]
        forward_prices = closes[index + 1:index + evaluation_days + 1]
        forward_returns.append((forward_prices[-1] / entry_price - 1) * 100)
        adverse_excursions.append(
            _maximum_adverse_excursion(matched_direction, entry_price, forward_prices)
        )
        if _invalidation_triggered(outlook.get("invalidation"), forward_prices):
            invalidations += 1

    occurrences = len(forward_returns)
    positive_rate = (
        sum(value > 0 for value in forward_returns) / occurrences * 100
        if occurrences else 0.0
    )
    negative_rate = (
        sum(value < 0 for value in forward_returns) / occurrences * 100
        if occurrences else 0.0
    )
    return {
        "status": "READY" if occurrences else "NO_MATCHING_HISTORY",
        "strategyVersion": STRATEGY.version,
        "horizonDays": evaluation_days,
        "matchedDirection": matched_direction,
        "occurrences": occurrences,
        "positiveRate": _round(positive_rate, 1),
        "negativeRate": _round(negative_rate, 1),
        "winRate": _round(positive_rate, 1),
        "medianForwardReturn": (
            _round(statistics.median(forward_returns), 2) if forward_returns else None
        ),
        "maximumAdverseExcursion": (
            _round(min(adverse_excursions), 2) if adverse_excursions else None
        ),
        "invalidationRate": (
            _round(invalidations / occurrences * 100, 1) if occurrences else None
        ),
        "maxDrawdown": _round(_maximum_drawdown(closes)[0], 2),
        "sampleStart": quotes[0]["date"],
        "sampleEnd": quotes[-1]["date"],
    }


def backtest_signals(records: Iterable[Mapping[str, Any]], horizon_days: int) -> dict[str, Any]:
    return _backtest_outlook(records, [horizon_days, horizon_days], horizon_days)


def backtest_horizons(
    records: Iterable[Mapping[str, Any]],
    horizons: Mapping[str, Sequence[int]],
) -> dict[str, Any]:
    rows = list(records)
    results: dict[str, dict[str, Any]] = {}
    for code, configured_bounds in horizons.items():
        minimum, maximum = int(configured_bounds[0]), int(configured_bounds[1])
        evaluation_days = (minimum + maximum) // 2
        result = _backtest_outlook(rows, configured_bounds, evaluation_days)
        result.update({
            "minimumDays": minimum,
            "maximumDays": maximum,
            "evaluationDays": evaluation_days,
        })
        results[code] = result
    has_usable_result = any(item["status"] != "INSUFFICIENT" for item in results.values())
    return {"status": "READY" if has_usable_result else "INSUFFICIENT",
            "strategyVersion": STRATEGY.version, "horizons": results}
