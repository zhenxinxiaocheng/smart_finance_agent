from __future__ import annotations

import math
import copy
import unittest
from datetime import date, timedelta
from pathlib import Path
from unittest.mock import patch

import numpy as np
import app.quant.models as quant_models

from app.quant.backtest import simulate_a_share_long_only, simulate_long_only
from app.quant.config import load_quant_config
from app.quant.config import QuantConfig
from app.quant.engine import QuantEngine, TrainingSample
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
    def test_a_share_backtest_never_spends_more_cash_than_available(self):
        records = [
            {
                "data_date": (date(2026, 7, 1) + timedelta(days=index)).isoformat(),
                "open": 9.99,
                "high": 10.1,
                "low": 9.8,
                "close": 9.99,
                "previous_close": 9.99,
                "volume": 1_000_000,
            }
            for index in range(3)
        ]

        result = simulate_a_share_long_only(records, [1.0, 1.0, 1.0], load_quant_config())

        self.assertGreaterEqual(min(result.cash_curve), 0.0)
        self.assertEqual(9900.0, result.final_quantity)
        self.assertEqual(1, result.partial_fills)
        self.assertEqual((9900.0,), result.fill_quantities)

    def test_a_share_backtest_executes_previous_signal_in_board_lots(self):
        records = [
            {
                "data_date": (date(2026, 7, 1) + timedelta(days=index)).isoformat(),
                "open": 10.0,
                "high": 10.2,
                "low": 9.8,
                "close": 10.0,
                "previous_close": 10.0,
                "volume": 1_000_000,
            }
            for index in range(4)
        ]

        result = simulate_a_share_long_only(records, [0.10, 0.0, 0.0, 0.0], load_quant_config())

        self.assertEqual(2, result.trades)
        self.assertEqual(("2026-07-02", "2026-07-03"), result.fill_dates)
        self.assertEqual(("BUY", "SELL"), result.fill_sides)
        self.assertEqual((1000.0, 1000.0), result.fill_quantities)
        self.assertEqual(0.0, result.final_quantity)
        self.assertLess(result.total_return, 0.0)

    def test_a_share_backtest_retries_after_suspension_and_limit_up_lock(self):
        records = [
            {
                "data_date": "2026-07-01",
                "open": 10.0,
                "high": 10.2,
                "low": 9.8,
                "close": 10.0,
                "previous_close": 10.0,
                "volume": 1_000_000,
            },
            {
                "data_date": "2026-07-02",
                "open": 10.0,
                "high": 10.0,
                "low": 10.0,
                "close": 10.0,
                "previous_close": 10.0,
                "volume": 0,
            },
            {
                "data_date": "2026-07-03",
                "open": 11.0,
                "high": 11.0,
                "low": 11.0,
                "close": 11.0,
                "previous_close": 10.0,
                "volume": 1_000_000,
            },
            {
                "data_date": "2026-07-06",
                "open": 10.8,
                "high": 11.1,
                "low": 10.7,
                "close": 10.9,
                "previous_close": 11.0,
                "volume": 1_000_000,
            },
        ]

        result = simulate_a_share_long_only(records, [0.10, 0.10, 0.10, 0.10], load_quant_config())

        self.assertEqual(2, result.rejected_orders)
        self.assertEqual(("2026-07-06",), result.fill_dates)
        self.assertEqual(("BUY",), result.fill_sides)
        self.assertGreater(result.final_quantity, 0.0)

    def test_a_share_backtest_uses_record_specific_price_limit(self):
        records = [
            {
                "data_date": "2026-07-01",
                "open": 10.0,
                "high": 10.2,
                "low": 9.8,
                "close": 10.0,
                "previous_close": 10.0,
                "volume": 1_000_000,
                "price_limit_ratio": 0.05,
            },
            {
                "data_date": "2026-07-02",
                "open": 10.5,
                "high": 10.5,
                "low": 10.5,
                "close": 10.5,
                "previous_close": 10.0,
                "volume": 1_000_000,
                "price_limit_ratio": 0.05,
            },
        ]

        result = simulate_a_share_long_only(records, [0.10, 0.10], load_quant_config())

        self.assertEqual(1, result.rejected_orders)
        self.assertEqual((), result.fill_dates)

    def test_default_quant_config_uses_independent_validation_rule_version(self):
        config = load_quant_config()

        self.assertEqual("quant-research-v2", config.version)
        self.assertGreater(config.integer("training.minimumCalibrationSamples"), 0)
        self.assertGreater(config.integer("training.minimumEvaluationSamples"), 0)

    def test_previous_quant_config_remains_loadable_for_replay(self):
        path = Path(__file__).resolve().parents[1] / "config" / "quant-research-v1.json"

        config = load_quant_config(path)

        self.assertEqual("quant-research-v1", config.version)
        self.assertGreater(config.integer("training.minimumCalibrationSamples"), 0)
        self.assertGreater(config.integer("training.minimumEvaluationSamples"), 0)

    @staticmethod
    def synthetic_training_samples(count: int = 240, horizon_days: int = 20) -> list[TrainingSample]:
        return [
            TrainingSample(
                as_of_index=index,
                label_end_index=index + horizon_days,
                as_of_date=(date(2024, 1, 1) + timedelta(days=index)).isoformat(),
                features={"sequence": float(index)},
                net_excess_return=0.004 if index % 2 == 0 else -0.001,
                positive_excess=index % 2 == 0,
            )
            for index in range(count)
        ]

    @staticmethod
    def fast_model_config(*, buy_probability: float = 0.58) -> QuantConfig:
        data = copy.deepcopy(load_quant_config().data)
        data["training"]["minimumSamples"] = 40
        data["prediction"]["buyWatchProbability"] = buy_probability
        return QuantConfig(data)

    @staticmethod
    def fake_fitted_models(*_args, **_kwargs):
        return tuple(object() for _ in range(6))

    @staticmethod
    def fake_raw_predictions(_models, values: np.ndarray, *_args) -> tuple[np.ndarray, np.ndarray]:
        probability = np.full(len(values), 0.55, dtype=float)
        expected = np.full(len(values), 0.002, dtype=float)
        return probability, expected

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

    def test_probability_calibration_is_fitted_before_the_evaluation_period(self):
        instances = []

        class RecordingCalibrator:
            def __init__(self):
                self.fit_size = 0
                self.predict_sizes: list[int] = []
                instances.append(self)

            def fit(self, values, labels):
                self.fit_size = len(values)
                return self

            def predict(self, values):
                self.predict_sizes.append(len(values))
                return np.clip(np.asarray(values, dtype=float), 0.0, 1.0)

        with patch("app.quant.models._fit_models", side_effect=self.fake_fitted_models), \
                patch("app.quant.models._raw_predict", side_effect=self.fake_raw_predictions), \
                patch(
                    "app.quant.models._new_calibrator",
                    side_effect=lambda _method: RecordingCalibrator(),
                ):
            train_ensemble(self.synthetic_training_samples(), self.fast_model_config())

        evaluation_calibrator = instances[0]
        self.assertLess(evaluation_calibrator.fit_size, evaluation_calibrator.predict_sizes[0])

    def test_training_uses_sigmoid_for_small_independent_calibration_set(self):
        with patch("app.quant.models._fit_models", side_effect=self.fake_fitted_models), \
                patch("app.quant.models._raw_predict", side_effect=self.fake_raw_predictions):
            artifact = train_ensemble(
                self.synthetic_training_samples(),
                self.fast_model_config(),
            )

        self.assertEqual("sigmoid", artifact.metrics["calibrationMethod"])

    def test_training_exposes_strict_validation_metrics(self):
        with patch("app.quant.models._fit_models", side_effect=self.fake_fitted_models), \
                patch("app.quant.models._raw_predict", side_effect=self.fake_raw_predictions):
            artifact = train_ensemble(
                self.synthetic_training_samples(),
                self.fast_model_config(buy_probability=0.10),
            )

        required = {
            "oosR2",
            "mae",
            "rmse",
            "dmPValue",
            "medianRankIc",
            "positiveIcFoldRatio",
            "logLossSkill",
            "calibrationSlope",
            "calibrationIntercept",
            "intervalCoverage",
            "pinballSkill",
            "deflatedSharpeProbability",
            "pbo",
            "costStressAnnualizedExcessReturn",
            "independentEventCount",
            "featureDistribution",
            "labelDistribution",
            "validationReport",
        }
        self.assertTrue(required.issubset(artifact.metrics))

    def test_calibration_labels_end_before_embargoed_evaluation_period(self):
        samples = self.synthetic_training_samples()
        indices = np.arange(40, len(samples), dtype=int)

        calibration, evaluation = quant_models._chronological_calibration_split(
            samples,
            indices,
            fraction=0.20,
            minimum_calibration=20,
            minimum_evaluation=20,
            embargo_multiplier=1.0,
        )

        last_calibration = samples[int(calibration[-1])]
        first_evaluation = samples[int(evaluation[0])]
        self.assertGreaterEqual(first_evaluation.as_of_index, last_calibration.label_end_index)

    def test_strategy_validation_uses_configured_threshold_and_non_overlapping_horizon_returns(self):
        samples = self.synthetic_training_samples()
        with patch("app.quant.models._fit_models", side_effect=self.fake_fitted_models), \
                patch("app.quant.models._raw_predict", side_effect=self.fake_raw_predictions):
            permissive = train_ensemble(samples, self.fast_model_config(buy_probability=0.10))
            restrictive = train_ensemble(samples, self.fast_model_config(buy_probability=0.90))

        self.assertNotEqual(
            permissive.metrics["annualizedExcessReturn"],
            restrictive.metrics["annualizedExcessReturn"],
        )
        self.assertEqual(0.0, restrictive.metrics["annualizedExcessReturn"])
        self.assertLess(abs(permissive.metrics["annualizedExcessReturn"]), 0.10)

    def test_ensemble_blending_and_confidence_boundaries_are_configurable(self):
        class Classifier:
            def __init__(self, probability: float):
                self.probability = probability

            def predict_proba(self, values):
                positive = np.full(len(values), self.probability)
                return np.column_stack((1 - positive, positive))

        class Regressor:
            def __init__(self, expected: float):
                self.expected = expected

            def predict(self, values):
                return np.full(len(values), self.expected)

        models = (Classifier(0.20), Regressor(0.10), Classifier(0.80), Regressor(0.50))
        probability, expected = quant_models._raw_predict(models, np.ones((1, 1)), linear_weight=0.25)

        self.assertAlmostEqual(0.65, probability[0])
        self.assertAlmostEqual(0.40, expected[0])
        self.assertEqual("HIGH", quant_models._prediction_confidence(0.01, 0.02, 1.0, 2.0, 1e-12))
        self.assertEqual("MEDIUM", quant_models._prediction_confidence(0.03, 0.02, 1.0, 2.0, 1e-12))
        self.assertEqual("LOW", quant_models._prediction_confidence(0.05, 0.02, 1.0, 2.0, 1e-12))

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
