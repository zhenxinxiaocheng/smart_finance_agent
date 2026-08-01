from __future__ import annotations

import unittest

from app.quant.failure_diagnostics import diagnose_failure


class QuantFailureDiagnosticsTest(unittest.TestCase):
    def test_data_failure_blocks_retry_until_history_is_repaired(self):
        diagnosis = diagnose_failure(
            {"validationReport": {"failureCodes": ["INSUFFICIENT_DATA"]}},
            current_algorithm="XGBOOST",
        )

        self.assertEqual("DATA_INSUFFICIENT", diagnosis.code)
        self.assertTrue(diagnosis.data_blocked)
        self.assertIsNone(diagnosis.recommended_algorithm)

    def test_overfitting_simplifies_the_next_model(self):
        diagnosis = diagnose_failure(
            {
                "pbo": 0.51,
                "deflatedSharpeProbability": 0.4,
                "foldPassRatio": 0.3,
            },
            current_algorithm="XGBOOST",
        )

        self.assertEqual("OVERFITTING", diagnosis.code)
        self.assertEqual("ELASTIC_NET", diagnosis.recommended_algorithm)

    def test_high_cost_routes_the_next_trial_to_turnover_control(self):
        diagnosis = diagnose_failure(
            {
                "turnover": 4.2,
                "annualizedNetReturn": 0.08,
                "costStressAnnualizedExcessReturn": -0.03,
            },
            current_algorithm="EXTRA_TREES",
        )

        self.assertEqual("COST_TOO_HIGH", diagnosis.code)
        self.assertEqual(
            "TREND_VOLATILITY",
            diagnosis.recommended_algorithm,
        )

    def test_calibration_and_regime_failures_have_specific_routes(self):
        calibration = diagnose_failure(
            {
                "calibrationSlope": 2.3,
                "calibrationIntercept": 0.3,
                "crossWindowVolatility": 0.1,
            },
            current_algorithm="XGBOOST",
        )
        regime = diagnose_failure(
            {
                "calibrationSlope": 1.0,
                "calibrationIntercept": 0.0,
                "crossWindowVolatility": 0.42,
                "foldPassRatio": 0.4,
            },
            current_algorithm="ELASTIC_NET",
        )

        self.assertEqual("CALIBRATION_FAILED", calibration.code)
        self.assertEqual("ELASTIC_NET", calibration.recommended_algorithm)
        self.assertEqual("REGIME_INSTABILITY", regime.code)
        self.assertEqual("REGIME_ENSEMBLE", regime.recommended_algorithm)


if __name__ == "__main__":
    unittest.main()
