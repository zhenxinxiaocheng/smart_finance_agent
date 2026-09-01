import copy
import unittest

from app import analysis
from app.strategy_config import StrategyConfig, load_strategy_config


class StrategyConfigTest(unittest.TestCase):
    def test_versioned_file_is_the_only_runtime_strategy_parameter_source(self):
        strategy = load_strategy_config()

        self.assertEqual("technical-strategy-v5", strategy.version)
        self.assertEqual(
            5,
            strategy.integer("fund.benchmark_relative_calculation_minimum_observations"),
        )
        self.assertEqual(
            20,
            strategy.integer("fund.benchmark_relative_recommended_observations"),
        )
        self.assertEqual([5, 10, 20, 60, 120, 250],
                         strategy.integer_list("technical.moving_average_periods"))
        self.assertGreater(strategy.integer("backtest.maximum_evaluations_per_horizon"), 0)
        weights = strategy.value("technical.outlook.weights")
        self.assertEqual(
            {"trend", "momentum", "volumePrice", "volatility", "structure"},
            set(weights),
        )
        self.assertAlmostEqual(1.0, sum(float(value) for value in weights.values()))

    def test_engine_uses_supplied_strategy_periods_instead_of_source_defaults(self):
        original = analysis.STRATEGY
        custom_data = copy.deepcopy(original.data)
        custom_data["version"] = "custom-periods-v9"
        custom_data["technical"]["minimum_history_days"] = 11
        custom_data["technical"]["moving_average_periods"] = [3, 7, 11]
        custom_data["technical"]["volume_moving_average_periods"] = [3]
        custom_data["technical"]["trend"]["fast_moving_average"] = 3
        custom_data["technical"]["trend"]["slow_moving_average"] = 7
        custom_data["technical"]["signals"]["fast_moving_average"] = 3
        custom_data["technical"]["signals"]["slow_moving_average"] = 7
        custom_data["technical"]["signals"]["volume_moving_average"] = 3
        custom_data["technical"]["bollinger"]["period"] = 7
        try:
            analysis.STRATEGY = StrategyConfig(custom_data)
            records = [
                {"data_date": f"2026-01-{index + 1:02d}", "open": 10 + index,
                 "high": 10.5 + index, "low": 9.5 + index,
                 "close": 10 + index, "volume": 1000 + index}
                for index in range(15)
            ]

            result = analysis.analyze_technical(records, {"CUSTOM": [4, 10]}, "CUSTOM")

            self.assertEqual("custom-periods-v9", result["strategyVersion"])
            self.assertIn("ma3", result["series"][-1])
            self.assertNotIn("ma5", result["series"][-1])
        finally:
            analysis.STRATEGY = original


if __name__ == "__main__":
    unittest.main()
