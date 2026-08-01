from __future__ import annotations

import copy
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from app.quant.config import QuantConfig, load_quant_config
from app.quant.engine import TrainingSample
from app.quant.models import QuantPrediction, evaluate_final_holdout


class QuantFinalHoldoutTest(unittest.TestCase):
    def test_keeps_validated_model_when_untouched_holdout_passes(self):
        artifact = self.artifact()
        original_version = artifact.model_version

        with patch(
            "app.quant.models.predict_ensemble",
            side_effect=self.predictions(interval_coverage=0.8),
        ):
            result = evaluate_final_holdout(
                artifact,
                self.samples(),
                self.config(),
                benchmark_available=True,
                data_fresh=True,
                algorithm="VALIDATED_ENSEMBLE",
            )

        self.assertEqual("VALIDATED", result.status)
        self.assertTrue(result.metrics["finalHoldout"]["passed"])
        self.assertEqual(10, result.metrics["finalHoldout"]["sampleCount"])
        self.assertNotEqual(original_version, result.model_version)

    def test_holdout_diagnostics_do_not_reject_economic_model(self):
        artifact = self.artifact()

        with patch(
            "app.quant.models.predict_ensemble",
            side_effect=self.predictions(interval_coverage=1.0),
        ):
            result = evaluate_final_holdout(
                artifact,
                self.samples(),
                self.config(),
                benchmark_available=True,
                data_fresh=True,
                algorithm="VALIDATED_ENSEMBLE",
            )

        self.assertEqual("VALIDATED", result.status)
        self.assertTrue(result.metrics["finalHoldout"]["passed"])
        self.assertFalse(result.metrics["finalHoldout"]["diagnosticsPassed"])

    def test_rejects_model_when_holdout_has_no_economic_value(self):
        artifact = self.artifact()
        inactive = [
            QuantPrediction(
                probability_positive_excess=0.5,
                expected_excess_return=0.0,
                prediction_interval=(-0.1, 0.1),
                action="HOLD",
                confidence="LOW",
                top_factors=(),
            )
            for _ in self.samples()
        ]

        with patch("app.quant.models.predict_ensemble", side_effect=inactive):
            result = evaluate_final_holdout(
                artifact,
                self.samples(),
                self.config(),
                benchmark_available=True,
                data_fresh=True,
                algorithm="REGIME_ENSEMBLE",
            )

        self.assertEqual("DRAFT", result.status)
        self.assertIn(
            "MODEL_REJECTED",
            result.metrics["validationReport"]["failureCodes"],
        )

    @staticmethod
    def config() -> QuantConfig:
        data = copy.deepcopy(load_quant_config().data)
        data["autoSearch"]["finalHoldoutValidation"] = {
            "minimumSamples": 10,
            "minimumOosR2": 0.0,
            "minimumBrierSkill": 0.0,
            "minimumLogLossSkill": 0.0,
            "minimumNetReturn": 0.0,
            "minimumIntervalCoverage": 0.75,
            "maximumIntervalCoverage": 0.85,
        }
        return QuantConfig(data)

    @staticmethod
    def artifact():
        return SimpleNamespace(
            model_version="selection-version",
            status="VALIDATED",
            metrics={
                "positiveLabelRate": 0.5,
                "economicRole": "RETURN_ENHANCER",
                "validationReport": {
                    "passed": True,
                    "lifecycle": "VALIDATED",
                    "economicRole": "RETURN_ENHANCER",
                    "failureCodes": [],
                },
            },
        )

    @staticmethod
    def samples() -> list[TrainingSample]:
        actual = [0.04, -0.03, 0.03, -0.02, 0.02, -0.01, 0.05, -0.04, 0.01, -0.02]
        return [
            TrainingSample(
                as_of_index=index,
                label_end_index=index,
                as_of_date=f"2026-02-{index + 1:02d}",
                features={"signal": float(index)},
                net_excess_return=value,
                positive_excess=value > 0,
                net_return=value,
                positive_return=value > 0,
                negative_return=value <= 0,
            )
            for index, value in enumerate(actual)
        ]

    @staticmethod
    def predictions(*, interval_coverage: float) -> list[QuantPrediction]:
        actual = [0.04, -0.03, 0.03, -0.02, 0.02, -0.01, 0.05, -0.04, 0.01, -0.02]
        covered = round(len(actual) * interval_coverage)
        predictions: list[QuantPrediction] = []
        for index, value in enumerate(actual):
            inside = index < covered
            lower, upper = (
                (value - 0.01, value + 0.01)
                if inside
                else (value + 0.01, value + 0.02)
            )
            predictions.append(QuantPrediction(
                probability_positive_excess=0.9 if value > 0 else 0.1,
                expected_excess_return=value,
                prediction_interval=(lower, upper),
                action="BUY_WATCH" if value > 0 else "HOLD",
                confidence="HIGH",
                top_factors=(),
            ))
        return predictions


if __name__ == "__main__":
    unittest.main()
