from __future__ import annotations

import copy
import tempfile
import unittest
from pathlib import Path

import optuna

from app.quant.config import QuantConfig, load_quant_config
from app.quant.optimization import (
    create_or_load_study,
    optimization_budget,
    suggest_candidate,
)


class QuantOptimizationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.config = QuantConfig(copy.deepcopy(load_quant_config().data))

    def test_budget_scales_with_effective_dimension_and_is_bounded(self):
        budget = optimization_budget(8)

        self.assertEqual(32, budget.startup_trials)
        self.assertEqual(96, budget.maximum_trials)
        self.assertEqual(24, budget.no_improvement_limit)
        self.assertEqual(1_800, budget.timeout_seconds)

        small = optimization_budget(3)
        self.assertEqual((20, 80, 20), (
            small.startup_trials,
            small.maximum_trials,
            small.no_improvement_limit,
        ))
        self.assertEqual(240, optimization_budget(30).maximum_trials)

    def test_all_configured_strategies_generate_parameters_in_scientific_ranges(self):
        expected = {
            "ELASTIC_NET",
            "XGBOOST",
            "EXTRA_TREES",
            "TREND_VOLATILITY",
            "RISK_FILTERED_MEAN_REVERSION",
            "REGIME_ENSEMBLE",
        }
        observed: set[str] = set()
        for algorithm in expected:
            trial = FixedAlgorithmTrial(algorithm)
            candidate = suggest_candidate(trial, self.config, horizon_days=60)
            observed.add(candidate.algorithm)
            self.assertTrue(candidate.parameters)
            if "maximumDepth" in candidate.parameters:
                self.assertGreaterEqual(candidate.parameters["maximumDepth"], 1)
                self.assertLessEqual(candidate.parameters["maximumDepth"], 6)
            if "estimators" in candidate.parameters:
                self.assertGreaterEqual(candidate.parameters["estimators"], 32)
                self.assertLessEqual(candidate.parameters["estimators"], 512)
            if "learningRate" in candidate.parameters:
                self.assertGreaterEqual(candidate.parameters["learningRate"], 0.005)
                self.assertLessEqual(candidate.parameters["learningRate"], 0.2)
            for key, value in candidate.parameters.items():
                if key.endswith("Window"):
                    self.assertGreaterEqual(value, 10)
                    self.assertLessEqual(value, 240)
                if key == "targetAnnualizedVolatility":
                    self.assertGreaterEqual(value, 0.06)
                    self.assertLessEqual(value, 0.18)
        self.assertEqual(expected, observed)

    def test_tpe_study_reuses_prior_trials_for_same_research_identity(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).parent) as directory:
            storage = f"sqlite:///{Path(directory, 'study.db').as_posix()}"
            first = create_or_load_study(
                study_name="asset-010736-v1",
                storage=storage,
                seed=20260718,
                startup_trials=2,
            )
            first.optimize(lambda trial: (
                trial.suggest_float("gain", 0.0, 1.0),
                1.0,
                0.1,
                0.2,
                0.3,
            ), n_trials=3)

            resumed = create_or_load_study(
                study_name="asset-010736-v1",
                storage=storage,
                seed=20260718,
                startup_trials=2,
            )

            self.assertEqual(3, len(resumed.trials))
            self.assertIsInstance(resumed.sampler, optuna.samplers.TPESampler)
            self.assertEqual(5, len(resumed.directions))


class FixedAlgorithmTrial:
    def __init__(self, algorithm: str):
        self.algorithm = algorithm

    def suggest_categorical(self, name, choices):
        if name == "algorithm":
            return self.algorithm
        return choices[0]

    def suggest_int(self, name, low, high, *, step=1, log=False):
        if log:
            return low
        return low + ((high - low) // (2 * step)) * step

    def suggest_float(self, name, low, high, *, step=None, log=False):
        if log:
            return low
        if step:
            return low + round((high - low) / (2 * step)) * step
        return (low + high) / 2


if __name__ == "__main__":
    unittest.main()
