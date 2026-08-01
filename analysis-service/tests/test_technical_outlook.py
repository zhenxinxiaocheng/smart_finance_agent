from __future__ import annotations

import unittest
from dataclasses import replace

from app.strategy_config import load_strategy_config

try:
    from app.technical_outlook import TechnicalObservation, evaluate_outlook
except ImportError:
    TechnicalObservation = None
    evaluate_outlook = None


class TechnicalOutlookTest(unittest.TestCase):
    def test_bullish_volume_confirmation_produces_explainable_watch_outlook(self):
        self.assertIsNotNone(TechnicalObservation, "technical outlook module is required")
        self.assertIsNotNone(evaluate_outlook, "technical outlook evaluator is required")
        observation = self.bullish_observation()
        levels = self.price_levels()

        result = evaluate_outlook(observation, levels, load_strategy_config())

        self.assertIn(result["direction"], {"BULLISH", "LEAN_BULLISH"})
        self.assertEqual("WATCH", result["action"])
        self.assertGreater(result["strength"], 0)
        self.assertLessEqual(result["strength"], 100)
        self.assertEqual(
            {"trend", "momentum", "volumePrice", "volatility", "structure"},
            set(result["components"]),
        )
        self.assertLessEqual(len(result["reasons"]), 3)
        self.assertEqual(10.7, result["invalidation"]["price"])

    def test_missing_optional_market_snapshot_is_explicit_and_reduces_confidence(self):
        complete = evaluate_outlook(
            self.bullish_observation(),
            self.price_levels(),
            load_strategy_config(),
        )
        missing = evaluate_outlook(
            replace(
                self.bullish_observation(),
                turnover_rate=None,
                volume_ratio=None,
                amplitude=None,
            ),
            self.price_levels(),
            load_strategy_config(),
        )

        self.assertEqual(
            [
                "TURNOVER_RATE_UNAVAILABLE",
                "VOLUME_RATIO_UNAVAILABLE",
                "AMPLITUDE_UNAVAILABLE",
            ],
            missing["missingInputs"],
        )
        confidence_rank = {"LOW": 0, "MEDIUM": 1, "HIGH": 2}
        self.assertLess(
            confidence_rank[missing["confidence"]],
            confidence_rank[complete["confidence"]],
        )
        self.assertTrue(all("换手率" not in reason for reason in missing["reasons"]))

    @staticmethod
    def bullish_observation():
        return TechnicalObservation(
            close=12.0,
            previous_close=11.5,
            horizon_return_percent=16.0,
            fast_average=11.4,
            slow_average=10.8,
            previous_fast_average=11.2,
            previous_slow_average=10.75,
            macd_histogram=0.45,
            previous_macd_histogram=0.30,
            rsi=61.0,
            kdj_k=68.0,
            kdj_d=57.0,
            bollinger_upper=12.6,
            bollinger_lower=9.8,
            atr=0.35,
            volume=180_000.0,
            volume_average=120_000.0,
            turnover_rate=3.2,
            volume_ratio=1.5,
            amplitude=4.1,
        )

    @staticmethod
    def price_levels():
        return {
            "support": {"price": 10.9, "low": 10.7, "high": 11.1},
            "resistance": {"price": 12.8, "low": 12.6, "high": 13.0},
        }


if __name__ == "__main__":
    unittest.main()
