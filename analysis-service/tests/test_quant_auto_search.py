from __future__ import annotations

import copy
import unittest
from types import SimpleNamespace

from app.quant.auto_search import AutoSearchEngine
from app.quant.config import QuantConfig, load_quant_config
from app.quant.engine import TrainingSample


class QuantAutoSearchTest(unittest.TestCase):
    def test_candidates_never_receive_final_holdout_and_selected_model_is_evaluated_once(self):
        data = copy.deepcopy(load_quant_config().data)
        data["training"]["minimumSamples"] = 4
        data["autoSearch"] = {
            "maximumCandidates": 2,
            "timeBudgetSeconds": 60,
            "noImprovementLimit": 2,
            "finalHoldoutFraction": 0.2,
            "minimumFinalHoldoutSamples": 2,
            "candidates": [
                {"algorithm": "ELASTIC_NET", "parameters": {}},
                {"algorithm": "GRADIENT_BOOSTING", "parameters": {}},
            ],
        }
        artifacts = iter([
            self.artifact("selected", "VALIDATED", 0.04, 0.8),
            self.artifact("other", "VALIDATED", 0.02, 0.7),
        ])
        trained_dates: list[list[str]] = []
        evaluated: list[tuple[str, list[str]]] = []

        def trainer(samples, config, **kwargs):
            trained_dates.append([sample.as_of_date for sample in samples])
            return next(artifacts)

        def final_evaluator(artifact, holdout, config, **kwargs):
            evaluated.append((
                artifact.model_version,
                [sample.as_of_date for sample in holdout],
            ))
            return artifact

        result = AutoSearchEngine(
            QuantConfig(data),
            trainer=trainer,
            final_evaluator=final_evaluator,
        ).search(
            samples=self.samples(10),
            benchmark_available=True,
        )

        self.assertEqual([self.dates(8), self.dates(8)], trained_dates)
        self.assertEqual([("selected", self.dates(2, start=8))], evaluated)
        self.assertEqual(2, result.summary["finalHoldoutSamples"])

    def test_selects_best_validated_candidate_without_user_parameters(self):
        data = copy.deepcopy(load_quant_config().data)
        data["training"]["minimumSamples"] = 4
        data["autoSearch"] = {
            "maximumCandidates": 3,
            "timeBudgetSeconds": 60,
            "noImprovementLimit": 3,
            "finalHoldoutFraction": 0.2,
            "minimumFinalHoldoutSamples": 2,
            "candidates": [
                {"algorithm": "ELASTIC_NET", "parameters": {}},
                {"algorithm": "GRADIENT_BOOSTING", "parameters": {}},
                {
                    "algorithm": "VALIDATED_ENSEMBLE",
                    "parameters": {"linearWeight": 0.65},
                },
            ],
        }
        artifacts = iter([
            self.artifact("draft-linear", "DRAFT", -0.05, 0.1),
            self.artifact("valid-tree", "VALIDATED", 0.02, 0.6),
            self.artifact("valid-ensemble", "VALIDATED", 0.04, 0.8),
        ])
        calls: list[tuple[str, str]] = []

        def trainer(samples, config, **kwargs):
            calls.append((kwargs["algorithm"], config.version))
            return next(artifacts)

        result = AutoSearchEngine(
            QuantConfig(data),
            trainer=trainer,
            final_evaluator=lambda artifact, samples, config, **kwargs: artifact,
        ).search(
            samples=self.samples(10),
            benchmark_available=True,
        )

        self.assertEqual("valid-ensemble", result.artifact.model_version)
        self.assertTrue(result.summary["qualified"])
        self.assertEqual(3, result.summary["evaluatedCandidates"])
        self.assertEqual(
            ["ELASTIC_NET", "GRADIENT_BOOSTING", "VALIDATED_ENSEMBLE"],
            [algorithm for algorithm, _ in calls],
        )
        self.assertNotEqual(calls[2][1], data["version"])

    def test_returns_best_draft_and_explicitly_reports_no_qualified_model(self):
        data = copy.deepcopy(load_quant_config().data)
        data["training"]["minimumSamples"] = 4
        data["autoSearch"] = {
            "maximumCandidates": 2,
            "timeBudgetSeconds": 60,
            "noImprovementLimit": 2,
            "finalHoldoutFraction": 0.2,
            "minimumFinalHoldoutSamples": 2,
            "candidates": [
                {"algorithm": "ELASTIC_NET", "parameters": {}},
                {"algorithm": "GRADIENT_BOOSTING", "parameters": {}},
            ],
        }
        artifacts = iter([
            self.artifact("draft-a", "DRAFT", -0.10, 0.1),
            self.artifact("draft-b", "DRAFT", -0.02, 0.2),
        ])

        result = AutoSearchEngine(
            QuantConfig(data),
            trainer=lambda samples, config, **kwargs: next(artifacts),
            final_evaluator=lambda artifact, samples, config, **kwargs: artifact,
        ).search(samples=self.samples(10), benchmark_available=True)

        self.assertEqual("draft-b", result.artifact.model_version)
        self.assertFalse(result.summary["qualified"])
        self.assertEqual("当前没有通过严格验证的模型", result.summary["userMessage"])

    @staticmethod
    def artifact(model_version: str, status: str, oos_r2: float, sharpe: float):
        return SimpleNamespace(
            model_version=model_version,
            status=status,
            metrics={
                "oosR2": oos_r2,
                "sharpe": sharpe,
                "annualizedExcessReturn": max(0.0, oos_r2),
                "brierSkill": max(0.0, oos_r2),
                "validationReport": {"passed": status == "VALIDATED"},
            },
        )

    @staticmethod
    def samples(count: int) -> list[TrainingSample]:
        return [
            TrainingSample(
                as_of_index=index,
                label_end_index=index,
                as_of_date=f"2026-01-{index + 1:02d}",
                features={"signal": float(index)},
                net_excess_return=0.01,
                positive_excess=index % 2 == 0,
                net_return=0.01,
                positive_return=index % 2 == 0,
                negative_return=index % 2 != 0,
            )
            for index in range(count)
        ]

    @staticmethod
    def dates(count: int, *, start: int = 0) -> list[str]:
        return [
            f"2026-01-{index + 1:02d}"
            for index in range(start, start + count)
        ]


if __name__ == "__main__":
    unittest.main()
