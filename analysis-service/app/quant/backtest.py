from __future__ import annotations

import math
import statistics
from dataclasses import dataclass
from typing import Sequence

from .config import QuantConfig


@dataclass(frozen=True)
class BacktestResult:
    total_return: float
    annualized_return: float
    annualized_volatility: float
    sharpe: float
    sortino: float
    calmar: float
    maximum_drawdown: float
    turnover: float
    trades: int
    win_rate: float
    profit_factor: float
    equity_curve: tuple[float, ...]


def simulate_long_only(prices: Sequence[float], signals: Sequence[int | float], config: QuantConfig) -> BacktestResult:
    if len(prices) != len(signals) or len(prices) < 2:
        raise ValueError("prices and signals must have the same length of at least two")
    minimum_price = config.number("backtest.minimumPrice")
    if any(price < minimum_price for price in prices):
        raise ValueError("prices contain an invalid value")
    annualization = config.integer("annualizationDays")
    one_way_cost = (
        config.number("backtest.oneWayCostBps") + config.number("backtest.slippageBps")
    ) / 10_000
    equity = 1.0
    position = 0.0
    turnover = 0.0
    trades = 0
    daily_returns: list[float] = []
    curve = [equity]
    trade_returns: list[float] = []
    entry_equity: float | None = None
    for index in range(1, len(prices)):
        target = min(1.0, max(0.0, float(signals[index - 1])))
        change = abs(target - position)
        cost = change * one_way_cost
        market_return = prices[index] / prices[index - 1] - 1
        strategy_return = position * market_return - cost
        equity *= 1 + strategy_return
        daily_returns.append(strategy_return)
        curve.append(equity)
        if change > 0:
            turnover += change
            trades += 1
            if target > position:
                entry_equity = equity
            elif target < position and entry_equity is not None:
                trade_returns.append(equity / entry_equity - 1)
                entry_equity = None
        position = target
    if position > 0:
        exit_cost = position * one_way_cost
        equity *= 1 - exit_cost
        turnover += position
        trades += 1
        curve[-1] = equity
        if entry_equity is not None:
            trade_returns.append(equity / entry_equity - 1)
    total_return = equity - 1
    periods = max(1, len(daily_returns))
    annualized_return = (max(equity, 1e-12) ** (annualization / periods)) - 1
    volatility = statistics.stdev(daily_returns) * math.sqrt(annualization) if len(daily_returns) > 1 else 0.0
    downside = [value for value in daily_returns if value < 0]
    downside_volatility = statistics.stdev(downside) * math.sqrt(annualization) if len(downside) > 1 else 0.0
    maximum_drawdown = _maximum_drawdown(curve)
    wins = [value for value in trade_returns if value > 0]
    losses = [value for value in trade_returns if value < 0]
    return BacktestResult(
        total_return=total_return,
        annualized_return=annualized_return,
        annualized_volatility=volatility,
        sharpe=_ratio(annualized_return, volatility),
        sortino=_ratio(annualized_return, downside_volatility),
        calmar=_ratio(annualized_return, abs(maximum_drawdown)),
        maximum_drawdown=maximum_drawdown,
        turnover=turnover,
        trades=trades,
        win_rate=_ratio(len(wins), len(trade_returns)),
        profit_factor=_ratio(sum(wins), abs(sum(losses))),
        equity_curve=tuple(curve),
    )


def _maximum_drawdown(values: Sequence[float]) -> float:
    peak = values[0]
    drawdown = 0.0
    for value in values:
        peak = max(peak, value)
        drawdown = min(drawdown, value / peak - 1)
    return drawdown


def _ratio(numerator: float, denominator: float) -> float:
    return 0.0 if abs(denominator) < 1e-12 else numerator / denominator
