from __future__ import annotations

import unittest

from app.quant.config import load_quant_config, with_experiment_parameters


class QuantConfigOverrideTest(unittest.TestCase):
    def test_mutable_experiment_parameters_create_a_versioned_config(self):
        base = load_quant_config()

        configured = with_experiment_parameters(base, {
            "linearWeight": 0.35,
            "classificationC": 0.75,
            "regressionAlpha": 0.002,
            "estimators": 64,
            "maximumDepth": 4,
            "learningRate": 0.08,
        })

        self.assertEqual(0.35, configured.number("prediction.ensemble.linearWeight"))
        self.assertEqual(0.75, configured.number("training.elasticNet.classificationC"))
        self.assertEqual(0.002, configured.number("training.elasticNet.regressionAlpha"))
        self.assertEqual(64, configured.integer("training.xgboost.estimators"))
        self.assertEqual(4, configured.integer("training.xgboost.maximumDepth"))
        self.assertEqual(0.08, configured.number("training.xgboost.learningRate"))
        self.assertNotEqual(base.version, configured.version)

    def test_validation_thresholds_cannot_be_overridden(self):
        with self.assertRaisesRegex(ValueError, "unsupported experiment parameter"):
            with_experiment_parameters(load_quant_config(), {
                "minimumDeflatedSharpeProbability": 0.1,
            })


if __name__ == "__main__":
    unittest.main()
