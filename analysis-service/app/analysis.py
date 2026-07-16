from __future__ import annotations

import math
import statistics
from collections.abc import Iterable, Mapping, Sequence
from typing import Any


NUMBER = int | float


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


def _clamp(value: float, low: float = 0.0, high: float = 100.0) -> float:
    return max(low, min(high, value))


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


def _rsi(values: Sequence[float], period: int = 14) -> list[float | None]:
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


def _atr(highs: Sequence[float], lows: Sequence[float], closes: Sequence[float], period: int = 14) -> list[float | None]:
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


def _kdj(highs: Sequence[float], lows: Sequence[float], closes: Sequence[float], period: int = 9) -> tuple[list[float | None], list[float | None], list[float | None]]:
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
        close = _number(record.get("close") or record.get("unit_nav") or record.get("unitNav"))
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


def _trend_score(closes: Sequence[float], ma20: Sequence[float | None], ma60: Sequence[float | None], lookback: int) -> float:
    index = len(closes) - 1
    start = max(0, index - max(lookback, 2) + 1)
    first = closes[start]
    momentum = 0.0 if first == 0 else (closes[index] / first - 1) * 100
    score = 50 + max(-20, min(20, momentum * 1.8))
    if ma20[index] is not None:
        score += 10 if closes[index] >= ma20[index] else -10
    if ma20[index] is not None and ma60[index] is not None:
        score += 10 if ma20[index] >= ma60[index] else -10
    return _clamp(score)


def _verdict(score: float) -> str:
    if score >= 65:
        return "FAVORABLE"
    if score >= 45:
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
    window = 3
    swing_highs: list[float] = []
    swing_lows: list[float] = []
    for index in range(window, len(highs) - window):
        if highs[index] >= max(highs[index - window:index + window + 1]):
            swing_highs.append(highs[index])
        if lows[index] <= min(lows[index - window:index + window + 1]):
            swing_lows.append(lows[index])
    tolerance = max(atr, close * 0.005)
    high_clusters = _cluster_levels(swing_highs[-60:], tolerance)
    low_clusters = _cluster_levels(swing_lows[-60:], tolerance)
    support_candidates = [(level, count) for level, count in low_clusters if level <= close]
    resistance_candidates = [(level, count) for level, count in high_clusters if level >= close]
    support_level, support_count = max(support_candidates, default=(close - atr, 1), key=lambda item: item[0])
    resistance_level, resistance_count = min(resistance_candidates, default=(close + atr, 1), key=lambda item: item[0])
    if resistance_level <= support_level:
        resistance_level = max(close + atr, support_level + atr)
    half_width = max(atr * 0.5, close * 0.002)

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
) -> dict[str, Any]:
    quotes = _normalized_quotes(records)
    if len(quotes) < 20:
        return {"status": "INSUFFICIENT", "series": [], "horizons": {}, "levels": {}}
    closes = [item["close"] for item in quotes]
    highs = [item["high"] for item in quotes]
    lows = [item["low"] for item in quotes]
    volumes = [item["volume"] for item in quotes]
    ma = {period: _moving_average(closes, period) for period in (5, 10, 20, 60, 120, 250)}
    volume_ma = {period: _moving_average(volumes, period) for period in (5, 10)}
    ema12 = _ema(closes, 12)
    ema26 = _ema(closes, 26)
    diff = [fast - slow for fast, slow in zip(ema12, ema26)]
    dea = _ema(diff, 9)
    histogram = [(value - signal) * 2 for value, signal in zip(diff, dea)]
    rsi14 = _rsi(closes)
    atr14 = _atr(highs, lows, closes)
    k_values, d_values, j_values = _kdj(highs, lows, closes)
    boll_mid = ma[20]
    boll_upper: list[float | None] = [None] * len(closes)
    boll_lower: list[float | None] = [None] * len(closes)
    for index in range(19, len(closes)):
        deviation = statistics.pstdev(closes[index - 19:index + 1])
        boll_upper[index] = (boll_mid[index] or closes[index]) + 2 * deviation
        boll_lower[index] = (boll_mid[index] or closes[index]) - 2 * deviation

    series: list[dict[str, Any]] = []
    for index, quote in enumerate(quotes):
        item = dict(quote)
        for period in ma:
            item[f"ma{period}"] = _round(ma[period][index])
        item.update({
            "volumeMa5": _round(volume_ma[5][index]),
            "volumeMa10": _round(volume_ma[10][index]),
            "macd": _round(histogram[index]),
            "macdDiff": _round(diff[index]),
            "macdSignal": _round(dea[index]),
            "rsi14": _round(rsi14[index]),
            "kdjK": _round(k_values[index]),
            "kdjD": _round(d_values[index]),
            "kdjJ": _round(j_values[index]),
            "bollMid": _round(boll_mid[index]),
            "bollUpper": _round(boll_upper[index]),
            "bollLower": _round(boll_lower[index]),
            "atr14": _round(atr14[index]),
        })
        series.append(item)

    if not horizons:
        raise ValueError("at least one horizon is required")
    if primary_horizon not in horizons:
        raise ValueError("primary horizon must exist in horizons")
    latest_atr = atr14[-1] or max(closes[-1] * 0.02, 0.01)
    horizon_results: dict[str, dict[str, Any]] = {}
    for name, configured_bounds in horizons.items():
        bounds = list(configured_bounds)
        minimum, maximum = int(bounds[0]), int(bounds[-1])
        if len(closes) < maximum:
            horizon_results[name] = {
                "status": "INSUFFICIENT",
                "minimumDays": minimum,
                "maximumDays": maximum,
                "availableHistoryDays": len(closes),
                "requiredHistoryDays": maximum,
                "reason": "可用历史数据不足以覆盖用户配置周期",
            }
            continue
        score = _trend_score(closes, ma[20], ma[60], maximum)
        level_lookback = min(len(closes), max(20, maximum * 3))
        level_highs = highs[-level_lookback:]
        level_lows = lows[-level_lookback:]
        level_closes = closes[-level_lookback:]
        horizon_atr_series = _atr(level_highs, level_lows, level_closes)
        horizon_atr = horizon_atr_series[-1] or latest_atr
        horizon_levels = _price_levels(level_highs, level_lows, closes[-1], horizon_atr)
        horizon_support = horizon_levels["support"]
        horizon_resistance = horizon_levels["resistance"]
        horizon_action_zones = {
            "buy": horizon_support,
            "add": {"low": horizon_support["low"], "high": horizon_support["price"]},
            "hold": {"low": horizon_support["price"], "high": horizon_resistance["price"]},
            "reduce": horizon_resistance,
            "risk": {"price": _round(horizon_support["low"] - horizon_atr * 0.5)},
        }
        horizon_results[name] = {
            "status": "READY",
            "minimumDays": minimum,
            "maximumDays": maximum,
            "score": _round(score, 1),
            "verdict": _verdict(score),
            "levelLookbackDays": level_lookback,
            "levels": horizon_levels,
            "actionZones": horizon_action_zones,
        }
    result = {
        "horizons": horizon_results,
        "primaryHorizon": primary_horizon,
        "series": series,
        "signals": {
            "movingAverageAlignment": "BULLISH" if (ma[5][-1] or 0) >= (ma[20][-1] or math.inf) else "BEARISH",
            "momentum": "POSITIVE" if histogram[-1] >= 0 else "NEGATIVE",
            "volume": "EXPANDING" if volumes[-1] >= (volume_ma[5][-1] or volumes[-1]) else "CONTRACTING",
        },
    }
    primary = horizon_results[primary_horizon]
    if primary["status"] != "READY":
        result.update({
            "status": "INSUFFICIENT",
            "reason": primary["reason"],
            "availableHistoryDays": primary["availableHistoryDays"],
            "requiredHistoryDays": primary["requiredHistoryDays"],
        })
        return result
    result.update({
        "status": "READY",
        "score": primary["score"],
        "verdict": primary["verdict"],
        "levels": primary["levels"],
        "actionZones": primary["actionZones"],
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
    if len(quotes) < 20:
        return {"status": "INSUFFICIENT", "series": []}
    values = [item["close"] for item in quotes]
    ma = {period: _moving_average(values, period) for period in (5, 10, 20, 60)}
    daily_returns = [values[index] / values[index - 1] - 1 for index in range(1, len(values)) if values[index - 1]]
    annualized_volatility = statistics.stdev(daily_returns) * math.sqrt(252) * 100 if len(daily_returns) > 1 else 0.0
    max_drawdown, peak_index, trough_index = _maximum_drawdown(values)
    recovered = values[-1] >= values[peak_index] if peak_index < len(values) else False
    latest_ma20 = ma[20][-1] or values[-1]
    latest_ma60 = ma[60][-1]
    one_year_return = _period_return(values, 252)
    one_month_return = _period_return(values, 21)
    three_month_return = _period_return(values, 63)
    score = 50.0
    score += max(-12, min(12, (one_month_return or 0) * 1.5))
    score += max(-12, min(12, (three_month_return or 0) * 0.8))
    score += 8 if values[-1] >= latest_ma20 else -8
    score -= max(0, min(16, abs(max_drawdown) - 10))
    score = _clamp(score)
    if max_drawdown <= -20 and values[-1] < latest_ma20:
        action = "PAUSE"
    elif one_year_return is not None and one_year_return >= 30 and values[-1] > latest_ma20 * 1.08:
        action = "TAKE_PROFIT"
    elif values[-1] >= latest_ma20 and (latest_ma60 is None or latest_ma20 >= latest_ma60):
        action = "ACCUMULATE"
    else:
        action = "HOLD"
    series: list[dict[str, Any]] = []
    for index, quote in enumerate(quotes):
        series.append({
            "date": quote["date"],
            "nav": quote["close"],
            "ma5": _round(ma[5][index]),
            "ma10": _round(ma[10][index]),
            "ma20": _round(ma[20][index]),
            "ma60": _round(ma[60][index]),
        })
    return {
        "status": "READY",
        "score": _round(score, 1),
        "verdict": _verdict(score),
        "oneMonthReturn": _round(one_month_return, 2),
        "threeMonthReturn": _round(three_month_return, 2),
        "oneYearReturn": _round(one_year_return, 2),
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
    if len(rows) < 3:
        return {"status": "INSUFFICIENT", "verdict": "INSUFFICIENT", "coverage": 0, "dimensions": {}}
    dimensions: dict[str, dict[str, Any]] = {}
    newest = rows[0]
    oldest = rows[-1]
    revenue_new, revenue_old = _field(newest, "revenue"), _field(oldest, "revenue")
    profit_new, profit_old = _field(newest, "netProfit"), _field(oldest, "netProfit")
    direct_revenue_growth = _average_available(rows, "revenueGrowth")
    direct_profit_growth = _average_available(rows, "netProfitGrowth")
    if direct_revenue_growth is not None and direct_profit_growth is not None:
        score = _clamp(50 + (direct_revenue_growth + direct_profit_growth) * 1.6)
        dimensions["growth"] = {"score": _round(score, 1), "revenueGrowth": _round(direct_revenue_growth, 2), "profitGrowth": _round(direct_profit_growth, 2)}
    elif revenue_new is not None and revenue_old not in (None, 0) and profit_new is not None and profit_old not in (None, 0):
        years = max(1, len(rows) - 1)
        revenue_growth = (revenue_new / revenue_old) ** (1 / years) - 1 if revenue_new > 0 and revenue_old > 0 else -1
        profit_growth = (profit_new / profit_old) ** (1 / years) - 1 if profit_new > 0 and profit_old > 0 else -1
        score = _clamp(50 + (revenue_growth + profit_growth) * 160)
        dimensions["growth"] = {"score": _round(score, 1), "revenueCagr": _round(revenue_growth * 100, 2), "profitCagr": _round(profit_growth * 100, 2)}
    roe = _average_available(rows, "roe")
    gross_margin = _average_available(rows, "grossMargin")
    net_margin = _average_available(rows, "netMargin")
    if sum(value is not None for value in (roe, gross_margin, net_margin)) >= 2:
        score = 0.0
        weight = 0
        for value, scale in ((roe, 3.5), (gross_margin, 1.7), (net_margin, 4.0)):
            if value is not None:
                score += _clamp(value * scale)
                weight += 1
        dimensions["profitability"] = {"score": _round(score / weight, 1), "roe": _round(roe, 2), "grossMargin": _round(gross_margin, 2), "netMargin": _round(net_margin, 2)}
    direct_cash_ratio = _average_available(rows, "cashToProfit")
    cash_flow = _average_available(rows, "operatingCashFlow")
    net_profit = _average_available(rows, "netProfit")
    if direct_cash_ratio is not None:
        cash_ratio = direct_cash_ratio / 100 if direct_cash_ratio > 10 else direct_cash_ratio
        dimensions["cashQuality"] = {"score": _round(_clamp(cash_ratio * 70), 1), "cashToProfit": _round(cash_ratio, 2)}
    elif cash_flow is not None and net_profit not in (None, 0):
        cash_ratio = cash_flow / net_profit
        dimensions["cashQuality"] = {"score": _round(_clamp(cash_ratio * 70), 1), "cashToProfit": _round(cash_ratio, 2)}
    debt_ratio = _average_available(rows, "debtRatio")
    current_ratio = _average_available(rows, "currentRatio")
    if debt_ratio is not None and current_ratio is not None:
        resilience = _clamp(100 - debt_ratio + min(current_ratio, 3) * 15)
        dimensions["resilience"] = {"score": _round(resilience, 1), "debtRatio": _round(debt_ratio, 2), "currentRatio": _round(current_ratio, 2)}
    pe = _average_available(rows, "pe")
    pb = _average_available(rows, "pb")
    dividend_yield = _average_available(rows, "dividendYield")
    if sum(value is not None for value in (pe, pb, dividend_yield)) >= 2:
        pe_score = 50.0 if pe is None else _clamp(100 - max(0, pe - 8) * 3)
        pb_score = 50.0 if pb is None else _clamp(100 - max(0, pb - 1) * 15)
        dividend_score = 50.0 if dividend_yield is None else _clamp(dividend_yield * 20)
        dimensions["valuation"] = {"score": _round((pe_score + pb_score + dividend_score) / 3, 1), "pe": _round(pe, 2), "pb": _round(pb, 2), "dividendYield": _round(dividend_yield, 2)}
    coverage = len(dimensions)
    if coverage < 4:
        return {"status": "INSUFFICIENT", "verdict": "INSUFFICIENT", "coverage": coverage, "dimensions": dimensions}
    overall = statistics.mean(float(item["score"]) for item in dimensions.values())
    verdict = "ATTRACTIVE" if overall >= 70 else "FAIR" if overall >= 50 else "CAUTIOUS"
    return {"status": "READY", "verdict": verdict, "score": _round(overall, 1), "coverage": coverage, "dimensions": dimensions, "periods": len(rows)}


def backtest_signals(records: Iterable[Mapping[str, Any]], horizon_days: int) -> dict[str, Any]:
    quotes = _normalized_quotes(records)
    closes = [item["close"] for item in quotes]
    if len(closes) < max(40, horizon_days + 20):
        return {"status": "INSUFFICIENT", "horizonDays": horizon_days, "occurrences": 0,
                "winRate": None, "medianForwardReturn": None,
                "maxDrawdown": _round(_maximum_drawdown(closes)[0], 2)}
    ma5 = _moving_average(closes, 5)
    ma20 = _moving_average(closes, 20)
    forward_returns: list[float] = []
    last_signal = -horizon_days
    # A signal is evaluated only with data available on that day. Forward prices
    # are used solely to score the historical outcome, never to create a signal.
    for index in range(20, len(closes) - horizon_days):
        bullish = ma5[index] is not None and ma20[index] is not None and ma5[index] >= ma20[index] and closes[index] >= ma20[index]
        if bullish and index - last_signal >= max(5, horizon_days // 2):
            forward_returns.append((closes[index + horizon_days] / closes[index] - 1) * 100)
            last_signal = index
    occurrences = len(forward_returns)
    win_rate = sum(value > 0 for value in forward_returns) / occurrences * 100 if occurrences else 0.0
    return {
        "status": "READY" if occurrences else "NO_SIGNALS",
        "horizonDays": horizon_days,
        "occurrences": occurrences,
        "winRate": _round(win_rate, 1),
        "medianForwardReturn": _round(statistics.median(forward_returns), 2) if forward_returns else None,
        "maxDrawdown": _round(_maximum_drawdown(closes)[0], 2),
        "sampleStart": quotes[0]["date"],
        "sampleEnd": quotes[-1]["date"],
    }


def backtest_horizons(
    records: Iterable[Mapping[str, Any]],
    horizons: Mapping[str, Sequence[int]],
) -> dict[str, Any]:
    rows = list(records)
    results: dict[str, dict[str, Any]] = {}
    for code, configured_bounds in horizons.items():
        minimum, maximum = int(configured_bounds[0]), int(configured_bounds[1])
        evaluation_days = (minimum + maximum) // 2
        result = backtest_signals(rows, evaluation_days)
        result.update({
            "minimumDays": minimum,
            "maximumDays": maximum,
            "evaluationDays": evaluation_days,
        })
        results[code] = result
    has_usable_result = any(item["status"] != "INSUFFICIENT" for item in results.values())
    return {"status": "READY" if has_usable_result else "INSUFFICIENT", "horizons": results}
