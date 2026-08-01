from __future__ import annotations

import math
import statistics
from dataclasses import dataclass
from typing import Any, Mapping, Sequence

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
    rejected_orders: int = 0
    partial_fills: int = 0
    final_cash: float = 0.0
    final_quantity: float = 0.0
    fill_dates: tuple[str, ...] = ()
    fill_sides: tuple[str, ...] = ()
    fill_quantities: tuple[float, ...] = ()
    cash_curve: tuple[float, ...] = ()


def simulate_a_share_long_only(records: Sequence[Mapping[str, Any]],
                               signals: Sequence[int | float],
                               config: QuantConfig) -> BacktestResult:
    if len(records) != len(signals) or len(records) < 2:
        raise ValueError("records and signals must have the same length of at least two")

    minimum_price = config.number("backtest.minimumPrice")
    initial_cash = config.number("backtest.initialCash")
    board_lot = config.integer("backtest.boardLotSize")
    limit_ratio = config.number("backtest.priceLimitRatio")
    cost_rate = config.number("backtest.oneWayCostBps") / 10_000
    slippage_rate = config.number("backtest.slippageBps") / 10_000
    annualization = config.integer("annualizationDays")
    if initial_cash <= 0 or board_lot <= 0:
        raise ValueError("initial cash and board lot size must be positive")

    cash = initial_cash
    quantity = 0
    available_to_sell = 0
    rejected_orders = 0
    turnover = 0.0
    curve = [1.0]
    daily_returns: list[float] = []
    trade_returns: list[float] = []
    fill_dates: list[str] = []
    fill_sides: list[str] = []
    fill_quantities: list[float] = []
    cash_curve = [cash]
    partial_fills = 0
    position_cost_basis = 0.0
    previous_equity = initial_cash

    for index in range(1, len(records)):
        record = records[index]
        open_price = _positive_number(record, "open", minimum_price)
        close_price = _positive_number(record, "close", minimum_price)
        previous_close = _positive_number(record, "previous_close", minimum_price)
        record_limit_ratio = float(record.get("price_limit_ratio", limit_ratio))
        if record_limit_ratio <= 0 or record_limit_ratio >= 1:
            raise ValueError("price_limit_ratio must be between zero and one")
        volume = float(record.get("volume") or 0.0)
        target_weight = min(1.0, max(0.0, float(signals[index - 1])))
        equity_before_trade = cash + quantity * open_price
        target_quantity = _board_lot_quantity(
            equity_before_trade * target_weight / open_price,
            board_lot,
        )
        requested_quantity = target_quantity - quantity

        if requested_quantity != 0:
            side = "BUY" if requested_quantity > 0 else "SELL"
            executable_quantity = (
                requested_quantity
                if side == "BUY"
                else -min(abs(requested_quantity), available_to_sell)
            )
            if executable_quantity != 0:
                if volume <= 0 or _is_locked_at_price_limit(
                        record, side, previous_close, record_limit_ratio):
                    rejected_orders += 1
                else:
                    execution_price = open_price * (
                        1 + slippage_rate if side == "BUY" else 1 - slippage_rate
                    )
                    filled_quantity = abs(executable_quantity)
                    if side == "BUY":
                        affordable_quantity = _board_lot_quantity(
                            cash / (execution_price * (1 + cost_rate)),
                            board_lot,
                        )
                        filled_quantity = min(filled_quantity, affordable_quantity)
                    if filled_quantity < abs(requested_quantity):
                        partial_fills += 1
                    if filled_quantity <= 0:
                        rejected_orders += 1
                    else:
                        notional = filled_quantity * execution_price
                        fee = notional * cost_rate
                        if side == "BUY":
                            cash -= notional + fee
                            quantity += int(filled_quantity)
                            position_cost_basis += notional + fee
                        else:
                            quantity_before_sale = quantity
                            net_proceeds = notional - fee
                            sold_cost_basis = position_cost_basis * (
                                filled_quantity / quantity_before_sale
                            )
                            cash += net_proceeds
                            quantity -= int(filled_quantity)
                            available_to_sell -= int(filled_quantity)
                            if sold_cost_basis > 0:
                                trade_returns.append(net_proceeds / sold_cost_basis - 1)
                            position_cost_basis = max(
                                0.0,
                                position_cost_basis - sold_cost_basis,
                            )
                            if quantity == 0:
                                position_cost_basis = 0.0
                        turnover += notional / max(equity_before_trade, minimum_price)
                        fill_dates.append(str(record["data_date"]))
                        fill_sides.append(side)
                        fill_quantities.append(float(filled_quantity))

        closing_equity = cash + quantity * close_price
        daily_return = closing_equity / previous_equity - 1
        daily_returns.append(daily_return)
        curve.append(closing_equity / initial_cash)
        cash_curve.append(cash)
        previous_equity = closing_equity
        available_to_sell = quantity

    total_return = previous_equity / initial_cash - 1
    annualized_return, volatility, downside_volatility = _return_statistics(
        total_return,
        daily_returns,
        annualization,
    )
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
        trades=len(fill_dates),
        win_rate=_ratio(len(wins), len(trade_returns)),
        profit_factor=_ratio(sum(wins), abs(sum(losses))),
        equity_curve=tuple(curve),
        rejected_orders=rejected_orders,
        final_cash=cash,
        final_quantity=float(quantity),
        fill_dates=tuple(fill_dates),
        fill_sides=tuple(fill_sides),
        fill_quantities=tuple(fill_quantities),
        cash_curve=tuple(cash_curve),
        partial_fills=partial_fills,
    )


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


def _positive_number(record: Mapping[str, Any], field: str, minimum: float) -> float:
    value = float(record[field])
    if value < minimum:
        raise ValueError(f"records contain an invalid {field}")
    return value


def _board_lot_quantity(quantity: float, board_lot: int) -> int:
    return max(0, math.floor(quantity / board_lot) * board_lot)


def _is_locked_at_price_limit(record: Mapping[str, Any],
                              side: str,
                              previous_close: float,
                              limit_ratio: float) -> bool:
    high = float(record["high"])
    low = float(record["low"])
    open_price = float(record["open"])
    tolerance = max(previous_close * 1e-6, 1e-8)
    limit_price = previous_close * (1 + limit_ratio if side == "BUY" else 1 - limit_ratio)
    if side == "BUY":
        return low >= limit_price - tolerance and open_price >= limit_price - tolerance
    return high <= limit_price + tolerance and open_price <= limit_price + tolerance


def _return_statistics(total_return: float,
                       daily_returns: Sequence[float],
                       annualization: int) -> tuple[float, float, float]:
    periods = max(1, len(daily_returns))
    annualized_return = max(1 + total_return, 1e-12) ** (annualization / periods) - 1
    volatility = (
        statistics.stdev(daily_returns) * math.sqrt(annualization)
        if len(daily_returns) > 1
        else 0.0
    )
    downside = [value for value in daily_returns if value < 0]
    downside_volatility = (
        statistics.stdev(downside) * math.sqrt(annualization)
        if len(downside) > 1
        else 0.0
    )
    return annualized_return, volatility, downside_volatility
