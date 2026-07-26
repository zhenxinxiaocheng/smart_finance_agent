from __future__ import annotations

import copy
import unittest
from types import SimpleNamespace

from app.quant.auto_search import AutoSearchEngine
from app.quant.config import QuantConfig, load_quant_config


class QuantAutoSearchTest(unittest.TestCase):
    def test_selects_best_validated_candidate_without_user_parameters(self):
        data = copy.deepcopy(load_quant_config().data)
        data["autoSearch"] = {
            "maximumCandidates": 3,
            "timeBudgetSeconds": 60,
            "noImprovementLimit": 3,
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

        result = AutoSearchEngine(QuantConfig(data), trainer=trainer).search(
            samples=[object()],
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
        data["autoSearch"] = {
            "maximumCandidates": 2,
            "timeBudgetSeconds": 60,
            "noImprovementLimit": 2,
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
        ).search(samples=[object()], benchmark_available=True)

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


if __name__ == "__main__":
    unittest.main()
