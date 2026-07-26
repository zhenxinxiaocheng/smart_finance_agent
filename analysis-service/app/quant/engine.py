from __future__ import annotations

import math
import statistics
from dataclasses import dataclass
from datetime import date
from typing import Any, Iterable, Mapping, Sequence

from .config import QuantConfig


class QuantDomainError(ValueError):
    error_code = "JOB_FAILED"
    user_message = "量化任务执行失败，请检查错误原因后重试"


class InsufficientQuantData(QuantDomainError):
    error_code = "INSUFFICIENT_DATA"
    user_message = "有效训练样本不足，当前无法训练可靠模型"


class BenchmarkUnavailable(QuantDomainError):
    error_code = "BENCHMARK_UNAVAILABLE"
    user_message = "官方基准数据尚未准备完成，当前暂停模型训练"


@dataclass(frozen=True)
class FactorRow:
    as_of_date: str
    horizon_days: int
    product_type: str
    values: dict[str, float]


@dataclass(frozen=True)
class TrainingSample:
    as_of_index: int
    label_end_index: int
    as_of_date: str
    features: dict[str, float]
    net_excess_return: float
    positive_excess: bool
    series_id: str = "TARGET"


class QuantEngine:
    def __init__(self, config: QuantConfig):
        self.config = config

    def factor_row(
        self,
        records: Sequence[Mapping[str, Any]],
        product_type: str,
        horizon_days: int,
        benchmark_records: Sequence[Mapping[str, Any]] | None = None,
        fundamentals: Iterable[Mapping[str, Any]] | None = None,
        index: int | None = None,
    ) -> FactorRow:
        normalized_type = product_type.strip().upper()
        if normalized_type not in {"STOCK", "MUTUAL_FUND"}:
            raise ValueError("product_type must be STOCK or MUTUAL_FUND")
        if horizon_days < 1:
            raise ValueError("horizon_days must be positive")
        closes = [_price(item, normalized_type) for item in records]
        end = len(closes) - 1 if index is None else index
        if end < 0 or end >= len(closes):
            raise ValueError("factor index is outside the record range")
        minimum = self.config.integer("factors.minimumHistoryDays")
        long_window = _window(horizon_days, self.config.number("factors.horizonWindowRatios.long"))
        required = max(minimum, long_window + 1)
        if end + 1 < required:
            raise InsufficientQuantData(f"factor calculation requires {required} records")

        short_window = _window(horizon_days, self.config.number("factors.horizonWindowRatios.short"))
        primary_window = _window(horizon_days, self.config.number("factors.horizonWindowRatios.primary"))
        risk_window = max(
            self.config.integer("factors.minimumRiskWindowDays"),
            _window(horizon_days, self.config.number("factors.riskWindowRatio")),
        )
        rsi_period = max(
            self.config.integer("factors.minimumRsiPeriodDays"),
            _window(horizon_days, self.config.number("factors.rsiPeriodRatio")),
        )
        atr_period = max(
            self.config.integer("factors.minimumAtrPeriodDays"),
            _window(horizon_days, self.config.number("factors.atrPeriodRatio")),
        )
        price_slice = closes[:end + 1]
        recent_returns = _returns(price_slice[-(risk_window + 1):])
        annualization = self.config.integer("annualizationDays")
        values: dict[str, float] = {
            "horizon_days": float(horizon_days),
            "momentum_short": _period_return(price_slice, short_window),
            "momentum_primary": _period_return(price_slice, primary_window),
            "momentum_long": _period_return(price_slice, long_window),
            "reversal_short": -_period_return(price_slice, short_window),
            "trend_slope": _normalized_slope(price_slice[-primary_window:]),
            "price_to_average": _safe_ratio(price_slice[-1], statistics.fmean(price_slice[-primary_window:])) - 1,
            "realized_volatility": _annualized_volatility(recent_returns, annualization),
            "downside_volatility": _annualized_volatility([value for value in recent_returns if value < 0], annualization),
            "maximum_drawdown": _maximum_drawdown(price_slice[-(risk_window + 1):]),
            "rsi": _rsi(price_slice, rsi_period),
            "atr_ratio": _atr_ratio(records, end, atr_period, normalized_type),
        }
        benchmark = _aligned_benchmark(benchmark_records, records, end, normalized_type)
        if benchmark:
            benchmark_returns = _returns(benchmark[-(risk_window + 1):])
            paired = min(len(recent_returns), len(benchmark_returns))
            asset_values = recent_returns[-paired:]
            benchmark_values = benchmark_returns[-paired:]
            beta = _beta(asset_values, benchmark_values)
            values.update({
                "market_beta": beta,
                "benchmark_alpha": (statistics.fmean(asset_values) - beta * statistics.fmean(benchmark_values)) * annualization,
                "relative_strength": _period_return(price_slice, primary_window) - _period_return(benchmark, primary_window),
                "tracking_error": _annualized_volatility(
                    [asset_values[i] - benchmark_values[i] for i in range(paired)], annualization),
            })
        else:
            values.update({"market_beta": 0.0, "benchmark_alpha": 0.0, "relative_strength": 0.0, "tracking_error": 0.0})

        if normalized_type == "STOCK":
            volumes = [_number(item.get("volume")) for item in records[:end + 1]]
            recent_volumes = volumes[-primary_window:]
            baseline_volumes = volumes[-long_window:]
            values.update({
                "volume_surprise": _safe_ratio(statistics.fmean(recent_volumes), statistics.fmean(baseline_volumes)) - 1,
                "liquidity_log": math.log1p(max(0.0, price_slice[-1] * statistics.fmean(recent_volumes))),
            })
            values.update(_point_in_time_fundamentals(fundamentals, _record_date(records[end])))
        else:
            positive = sum(1 for value in recent_returns if value > self.config.number("factors.consistencyPositiveThreshold"))
            mean_return = statistics.fmean(recent_returns) if recent_returns else 0.0
            downside = values["downside_volatility"]
            active = []
            if benchmark:
                benchmark_returns = _returns(benchmark[-(risk_window + 1):])
                paired = min(len(recent_returns), len(benchmark_returns))
                active = [recent_returns[-paired + i] - benchmark_returns[-paired + i] for i in range(paired)]
            values.update({
                "return_consistency": _safe_ratio(positive, len(recent_returns)),
                "sharpe": _safe_ratio(mean_return * annualization, values["realized_volatility"]),
                "sortino": _safe_ratio(mean_return * annualization, downside),
                "information_ratio": _safe_ratio(
                    (statistics.fmean(active) * annualization) if active else 0.0,
                    _annualized_volatility(active, annualization),
                ),
                "recovery_strength": _safe_ratio(_period_return(price_slice, primary_window), abs(values["maximum_drawdown"])),
            })
        return FactorRow(
            as_of_date=_record_date(records[end]).isoformat(),
            horizon_days=horizon_days,
            product_type=normalized_type,
            values={key: _finite(value) for key, value in sorted(values.items())},
        )

    def training_samples(
        self,
        records: Sequence[Mapping[str, Any]],
        product_type: str,
        horizon_days: int,
        benchmark_records: Sequence[Mapping[str, Any]] | None = None,
        fundamentals: Iterable[Mapping[str, Any]] | None = None,
    ) -> list[TrainingSample]:
        normalized_type = product_type.strip().upper()
        closes = [_price(item, normalized_type) for item in records]
        benchmark_by_date = {
            _record_date(item): _price(item, normalized_type)
            for item in (benchmark_records or [])
        }
        cost = self.config.number(f"labels.roundTripCostBps.{normalized_type}") / 10_000
        minimum = max(
            self.config.integer("factors.minimumHistoryDays"),
            _window(horizon_days, self.config.number("factors.horizonWindowRatios.long")) + 1,
        )
        result: list[TrainingSample] = []
        for index in range(minimum - 1, len(records) - horizon_days):
            row = self.factor_row(records, normalized_type, horizon_days, benchmark_records, fundamentals, index)
            end = index + horizon_days
            asset_return = _safe_ratio(closes[end], closes[index]) - 1
            start_date = _record_date(records[index])
            end_date = _record_date(records[end])
            benchmark_return = 0.0
            if start_date in benchmark_by_date and end_date in benchmark_by_date:
                benchmark_return = _safe_ratio(benchmark_by_date[end_date], benchmark_by_date[start_date]) - 1
            net_excess = asset_return - benchmark_return - cost
            result.append(TrainingSample(
                as_of_index=index,
                label_end_index=end,
                as_of_date=row.as_of_date,
                features=row.values,
                net_excess_return=_finite(net_excess),
                positive_excess=net_excess > 0,
            ))
        return result


def _number(value: Any) -> float:
    if value is None or value == "":
        return 0.0
    return float(value)


def _price(record: Mapping[str, Any], product_type: str) -> float:
    value = record.get("nav") if product_type == "MUTUAL_FUND" else record.get("close")
    if value is None:
        value = record.get("close")
    price = _number(value)
    if price <= 0:
        raise ValueError("price/nav must be positive")
    return price


def _record_date(record: Mapping[str, Any]) -> date:
    raw = record.get("data_date", record.get("trade_date", record.get("date")))
    if isinstance(raw, date):
        return raw
    return date.fromisoformat(str(raw))


def _window(horizon_days: int, ratio: float) -> int:
    return max(2, int(round(horizon_days * ratio)))


def _returns(values: Sequence[float]) -> list[float]:
    return [_safe_ratio(values[index], values[index - 1]) - 1 for index in range(1, len(values))]


def _period_return(values: Sequence[float], days: int) -> float:
    if len(values) <= days:
        raise InsufficientQuantData(f"return calculation requires {days + 1} records")
    return _safe_ratio(values[-1], values[-days - 1]) - 1


def _safe_ratio(numerator: float, denominator: float) -> float:
    return 0.0 if abs(denominator) < 1e-12 else numerator / denominator


def _normalized_slope(values: Sequence[float]) -> float:
    count = len(values)
    x_mean = (count - 1) / 2
    y_mean = statistics.fmean(values)
    denominator = sum((index - x_mean) ** 2 for index in range(count))
    slope = _safe_ratio(sum((index - x_mean) * (value - y_mean) for index, value in enumerate(values)), denominator)
    return _safe_ratio(slope, y_mean)


def _annualized_volatility(returns: Sequence[float], annualization: int) -> float:
    return statistics.stdev(returns) * math.sqrt(annualization) if len(returns) > 1 else 0.0


def _maximum_drawdown(values: Sequence[float]) -> float:
    peak = values[0]
    drawdown = 0.0
    for value in values:
        peak = max(peak, value)
        drawdown = min(drawdown, _safe_ratio(value, peak) - 1)
    return drawdown


def _rsi(values: Sequence[float], period: int) -> float:
    changes = [values[index] - values[index - 1] for index in range(len(values) - period, len(values))]
    gains = statistics.fmean([max(0.0, value) for value in changes])
    losses = statistics.fmean([max(0.0, -value) for value in changes])
    if losses == 0:
        return 100.0 if gains > 0 else 50.0
    return 100 - (100 / (1 + gains / losses))


def _atr_ratio(records: Sequence[Mapping[str, Any]], end: int, period: int, product_type: str) -> float:
    start = end - period + 1
    if product_type == "MUTUAL_FUND" or any(records[index].get("high") is None for index in range(start, end + 1)):
        prices = [_price(item, product_type) for item in records[start - 1:end + 1]]
        return _safe_ratio(statistics.fmean(abs(value) for value in _returns(prices)), prices[-1])
    ranges: list[float] = []
    for index in range(start, end + 1):
        high = _number(records[index].get("high"))
        low = _number(records[index].get("low"))
        previous = _price(records[index - 1], product_type)
        ranges.append(max(high - low, abs(high - previous), abs(low - previous)))
    return _safe_ratio(statistics.fmean(ranges), _price(records[end], product_type))


def _beta(asset: Sequence[float], benchmark: Sequence[float]) -> float:
    if len(asset) < 2 or len(benchmark) < 2:
        return 0.0
    benchmark_mean = statistics.fmean(benchmark)
    asset_mean = statistics.fmean(asset)
    covariance = sum((asset[i] - asset_mean) * (benchmark[i] - benchmark_mean) for i in range(len(asset)))
    variance = sum((value - benchmark_mean) ** 2 for value in benchmark)
    return _safe_ratio(covariance, variance)


def _aligned_benchmark(
    benchmark_records: Sequence[Mapping[str, Any]] | None,
    records: Sequence[Mapping[str, Any]],
    end: int,
    product_type: str,
) -> list[float]:
    if not benchmark_records:
        return []
    by_date = {_record_date(item): _price(item, product_type) for item in benchmark_records}
    return [by_date[_record_date(item)] for item in records[:end + 1] if _record_date(item) in by_date]


def _point_in_time_fundamentals(
    fundamentals: Iterable[Mapping[str, Any]] | None,
    as_of_date: date,
) -> dict[str, float]:
    mapping = {
        "pe": "valuation_pe", "pb": "valuation_pb", "roe": "quality_roe",
        "revenueGrowth": "growth_revenue", "netProfitGrowth": "growth_profit",
        "debtRatio": "resilience_debt_ratio", "operatingCashFlowRatio": "quality_cash_flow",
    }
    eligible = []
    for item in fundamentals or []:
        published = item.get("publishedAt") or item.get("published_at")
        if not published:
            continue
        published_date = published if isinstance(published, date) else date.fromisoformat(str(published)[:10])
        if published_date <= as_of_date:
            eligible.append((published_date, item))
    if not eligible:
        return {**{output: 0.0 for output in mapping.values()}, "fundamental_availability": 0.0}
    latest = max(eligible, key=lambda value: value[0])[1]
    return {
        **{output: _number(latest.get(source)) for source, output in mapping.items()},
        "fundamental_availability": 1.0,
    }


def _finite(value: float) -> float:
    return float(value) if math.isfinite(value) else 0.0
