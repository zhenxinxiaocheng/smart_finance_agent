from __future__ import annotations

import unittest

from app.quant.validation import (
    EconomicRole,
    ModelLifecycle,
    ValidationFailureCode,
    choose_calibration_method,
    evaluate_validation,
    tradable_target_weight,
)
from app.quant.engine import TrainingSample
from app.quant.models import _date_walk_forward_splits
from app.quant.models import _drawdown_guard_fold_pass
from app.quant.models import probability_of_backtest_overfitting


def passing_metrics() -> dict[str, float]:
    return {
        "oosR2": 0.02,
        "dmPValue": 0.01,
        "medianRankIc": 0.03,
        "positiveIcFoldRatio": 0.8,
        "brierSkill": 0.08,
        "logLossSkill": 0.04,
        "calibrationSlope": 1.0,
        "calibrationIntercept": 0.0,
        "intervalCoverage": 0.8,
        "pinballSkill": 0.03,
        "annualizedExcessReturn": 0.06,
        "annualizedNetReturn": 0.08,
        "netExcessVsStrongestBaseline": 0.03,
        "costStressNetExcessVsStrongestBaseline": 0.02,
        "cashAnnualizedReturn": 0.015,
        "drawdownReduction": 0.1,
        "downsideCapture": 0.9,
        "crossWindowVolatility": 0.04,
        "turnover": 0.2,
        "sharpe": 0.9,
        "deflatedSharpeProbability": 0.97,
        "pbo": 0.1,
        "foldPassRatio": 0.8,
        "maximumDrawdown": -0.12,
        "costStressAnnualizedExcessReturn": 0.02,
        "walkForwardFolds": 5.0,
        "independentEventCount": 70.0,
    }


class QuantValidationTest(unittest.TestCase):
    def test_walk_forward_keeps_same_day_assets_in_one_partition(self):
        samples = [
            TrainingSample(
                as_of_index=day,
                label_end_index=day + 2,
                as_of_date=f"2026-01-{day + 1:02d}",
                features={"x": float(day)},
                net_excess_return=0.01,
                positive_excess=True,
                series_id=series,
            )
            for day in range(12)
            for series in ("asset-a", "asset-b")
        ]

        folds = _date_walk_forward_splits(samples, folds=3, embargo_dates=2)

        self.assertEqual(3, len(folds))
        for train_indices, test_indices in folds:
            train_dates = {samples[int(index)].as_of_date for index in train_indices}
            test_dates = {samples[int(index)].as_of_date for index in test_indices}
            self.assertTrue(train_dates.isdisjoint(test_dates))
            for sample_date in test_dates:
                same_day = {
                    index
                    for index, sample in enumerate(samples)
                    if sample.as_of_date == sample_date
                }
                self.assertTrue(same_day.issubset(set(test_indices.tolist())))

    def test_pbo_detects_candidate_selection_overfit(self):
        stable = [
            [0.04, 0.03, 0.05, 0.04, 0.03, 0.05],
            [0.01, 0.00, 0.01, 0.00, 0.01, 0.00],
            [-0.01, 0.01, -0.01, 0.01, -0.01, 0.01],
        ]
        overfit = [
            [1.0, 0.0, 0.0, 0.0],
            [0.0, 1.0, 0.0, 0.0],
            [0.0, 0.0, 1.0, 0.0],
            [0.0, 0.0, 0.0, 1.0],
        ]

        self.assertEqual(0.0, probability_of_backtest_overfitting(stable))
        self.assertGreater(probability_of_backtest_overfitting(overfit), 0.2)

    def test_drawdown_guard_fold_treats_avoided_loss_as_success(self):
        self.assertTrue(_drawdown_guard_fold_pass(0.0, -0.10, 0.8))
        self.assertTrue(_drawdown_guard_fold_pass(-0.07, -0.10, 0.8))
        self.assertFalse(_drawdown_guard_fold_pass(-0.09, -0.10, 0.8))
        self.assertTrue(_drawdown_guard_fold_pass(0.0, 0.05, 0.8))
        self.assertFalse(_drawdown_guard_fold_pass(-0.01, 0.05, 0.8))

    def test_small_calibration_sample_uses_sigmoid(self):
        self.assertEqual("sigmoid", choose_calibration_method(999))
        self.assertEqual("isotonic", choose_calibration_method(1000))

    def test_missing_benchmark_can_still_validate_drawdown_guard(self):
        metrics = passing_metrics() | {
            "netExcessVsStrongestBaseline": -0.02,
            "deflatedSharpeProbability": 0.4,
            "drawdownReduction": 0.3,
            "downsideCapture": 0.7,
            "annualizedNetReturn": 0.04,
            "costStressAnnualizedExcessReturn": 0.03,
            "foldPassRatio": 0.2,
            "drawdownGuardFoldPassRatio": 0.8,
        }
        report = evaluate_validation(
            metrics,
            horizon_code="SHORT",
            benchmark_available=False,
            data_fresh=True,
        )

        self.assertEqual(ModelLifecycle.VALIDATED, report.lifecycle)
        self.assertEqual(EconomicRole.DRAWDOWN_GUARD, report.economic_role)
        self.assertTrue(report.passed)

    def test_strict_metrics_produce_validated_report(self):
        report = evaluate_validation(
            passing_metrics(),
            horizon_code="SHORT",
            benchmark_available=True,
            data_fresh=True,
        )

        self.assertEqual(ModelLifecycle.VALIDATED, report.lifecycle)
        self.assertEqual(EconomicRole.RETURN_ENHANCER, report.economic_role)
        self.assertEqual((), report.failure_codes)
        self.assertTrue(report.passed)
        self.assertTrue(all(
            item.passed for item in report.checks if item.required
        ))

    def test_prediction_diagnostics_do_not_veto_economic_model(self):
        metrics = passing_metrics() | {
            "oosR2": -0.4,
            "dmPValue": 0.8,
            "medianRankIc": -0.1,
            "brierSkill": -0.2,
            "intervalCoverage": 0.5,
        }

        report = evaluate_validation(
            metrics,
            horizon_code="SHORT",
            benchmark_available=True,
            data_fresh=True,
        )

        self.assertTrue(report.passed)
        self.assertEqual(EconomicRole.RETURN_ENHANCER, report.economic_role)
        self.assertFalse(report.diagnostics_passed)

    def test_failed_economic_roles_fall_back_to_non_tradable_risk_reference(self):
        metrics = passing_metrics() | {
            "netExcessVsStrongestBaseline": -0.02,
            "drawdownReduction": 0.05,
            "downsideCapture": 1.1,
            "annualizedNetReturn": 0.0,
        }

        report = evaluate_validation(
            metrics,
            horizon_code="SHORT",
            benchmark_available=True,
            data_fresh=True,
        )

        self.assertFalse(report.passed)
        self.assertEqual(ModelLifecycle.DRAFT, report.lifecycle)
        self.assertEqual(EconomicRole.RISK_REFERENCE, report.economic_role)
        self.assertIn(ValidationFailureCode.MODEL_REJECTED, report.failure_codes)

    def test_insufficient_independent_events_are_reported_explicitly(self):
        metrics = passing_metrics() | {"independentEventCount": 19.0}

        report = evaluate_validation(
            metrics,
            horizon_code="LONG",
            benchmark_available=True,
            data_fresh=True,
        )

        self.assertIn(
            ValidationFailureCode.INSUFFICIENT_DATA,
            report.failure_codes,
        )

    def test_medium_horizon_uses_available_independent_event_capacity(self):
        metrics = passing_metrics() | {
            "independentEventCount": 11.0,
            "evaluationSampleCount": 660.0,
            "horizonDays": 60.0,
        }

        report = evaluate_validation(
            metrics,
            horizon_code="MEDIUM",
            benchmark_available=True,
            data_fresh=True,
        )

        independent_check = next(
            item
            for item in report.checks
            if item.key == "independentEventCount"
        )
        self.assertEqual(11.0, independent_check.threshold)
        self.assertTrue(independent_check.passed)
        self.assertNotIn(
            ValidationFailureCode.INSUFFICIENT_DATA,
            report.failure_codes,
        )

    def test_draft_model_never_exposes_a_target_weight(self):
        self.assertIsNone(tradable_target_weight(ModelLifecycle.DRAFT, 0.18))
        self.assertEqual(
            0.18,
            tradable_target_weight(ModelLifecycle.VALIDATED, 0.18),
        )
        self.assertEqual(
            0.18,
            tradable_target_weight(ModelLifecycle.PAPER_VERIFIED, 0.18),
        )


if __name__ == "__main__":
    unittest.main()
