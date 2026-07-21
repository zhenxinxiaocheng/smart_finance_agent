from __future__ import annotations

import math
import unittest
from datetime import date, timedelta

from app.quant.backtest import simulate_long_only
from app.quant.config import load_quant_config
from app.quant.engine import QuantEngine
from app.quant.models import predict_ensemble, train_ensemble
from app.quant.risk import size_target_weight


def market_records(count: int = 420, *, drift: float = 0.08) -> list[dict[str, object]]:
    start = date(2024, 1, 1)
    records: list[dict[str, object]] = []
    for index in range(count):
        close = 20 + index * drift + math.sin(index / 8) * 1.2
        records.append({
            "data_date": (start + timedelta(days=index)).isoformat(),
            "open": close - 0.15,
            "high": close + 0.45,
            "low": close - 0.55,
            "close": close,
            "volume": 100_000 + index * 180,
        })
    return records


class QuantCoreTest(unittest.TestCase):
    def test_stock_factor_windows_follow_requested_horizon_without_future_leakage(self):
        engine = QuantEngine(load_quant_config())
        records = market_records()

        short = engine.factor_row(records, "STOCK", horizon_days=20, index=260)
        long = engine.factor_row(records, "STOCK", horizon_days=120, index=260)
        mutated = market_records()
        for item in mutated[261:]:
            item["close"] = float(item["close"]) * 8
        unchanged = engine.factor_row(mutated, "STOCK", horizon_days=20, index=260)

        self.assertNotEqual(short.values["momentum_primary"], long.values["momentum_primary"])
        self.assertEqual(short.values, unchanged.values)
        self.assertEqual(20, short.horizon_days)

    def test_fund_factor_set_is_separate_from_stock_factor_set(self):
        engine = QuantEngine(load_quant_config())
        records = market_records()

        stock = engine.factor_row(records, "STOCK", horizon_days=30)
        fund = engine.factor_row(records, "MUTUAL_FUND", horizon_days=30)

        self.assertIn("volume_surprise", stock.values)
        self.assertNotIn("volume_surprise", fund.values)
        self.assertIn("valuation_pe", stock.values)
        self.assertEqual(0.0, stock.values["fundamental_availability"])
        self.assertIn("return_consistency", fund.values)

    def test_training_samples_use_forward_net_excess_return_as_label(self):
        engine = QuantEngine(load_quant_config())
        records = market_records()
        benchmark = market_records(drift=0.03)

        samples = engine.training_samples(records, "STOCK", 20, benchmark)

        self.assertGreater(len(samples), 100)
        first = samples[0]
        self.assertEqual(first.as_of_index + 20, first.label_end_index)
        self.assertEqual(first.net_excess_return > 0, first.positive_excess)

    def test_model_training_is_deterministic_and_returns_calibrated_probability(self):
        engine = QuantEngine(load_quant_config())
        records = market_records(700)
        benchmark = market_records(700, drift=0.025)
        samples = engine.training_samples(records, "STOCK", 20, benchmark)

        first = train_ensemble(samples, load_quant_config())
        second = train_ensemble(samples, load_quant_config())
        prediction = predict_ensemble(first, samples[-1].features)

        self.assertEqual(first.model_version, second.model_version)
        self.assertEqual(first.metrics, second.metrics)
        self.assertGreaterEqual(prediction.probability_positive_excess, 0)
        self.assertLessEqual(prediction.probability_positive_excess, 1)
        self.assertLessEqual(prediction.prediction_interval[0], prediction.expected_excess_return)
        self.assertGreaterEqual(prediction.prediction_interval[1], prediction.expected_excess_return)
        self.assertIn(prediction.action, {"BUY_WATCH", "ADD", "HOLD", "REDUCE", "EXIT", "NO_TRADE"})

    def test_backtest_charges_costs_and_forces_no_trade_without_signal(self):
        config = load_quant_config()
        prices = [10.0] * 30
        active = [1 if index % 2 == 0 else 0 for index in range(30)]

        result = simulate_long_only(prices, active, config)
        inactive = simulate_long_only(prices, [0] * 30, config)

        self.assertLess(result.total_return, 0)
        self.assertEqual(0, inactive.total_return)
        self.assertEqual(0, inactive.trades)

    def test_position_size_uses_versioned_volatility_and_risk_limits(self):
        config = load_quant_config()

        normal = size_target_weight(
            product_type="STOCK", action="ADD", confidence="HIGH",
            market_regime="UPTREND", annualized_volatility=0.24,
            current_weight=0.03, config=config,
        )
        stressed = size_target_weight(
            product_type="STOCK", action="ADD", confidence="LOW",
            market_regime="HIGH_VOLATILITY", annualized_volatility=0.60,
            current_weight=0.03, config=config,
        )
        hold = size_target_weight(
            product_type="STOCK", action="NO_TRADE", confidence="LOW",
            market_regime="HIGH_VOLATILITY", annualized_volatility=0.60,
            current_weight=0.03, config=config,
        )

        self.assertGreater(normal, stressed)
        self.assertLessEqual(normal, config.number("risk.maximumAssetWeight.STOCK"))
        self.assertEqual(0.03, hold)


if __name__ == "__main__":
    unittest.main()
