from __future__ import annotations

import unittest

from app.quant.validation import (
    ModelLifecycle,
    ValidationFailureCode,
    choose_calibration_method,
    evaluate_validation,
    tradable_target_weight,
)
from app.quant.engine import TrainingSample
from app.quant.models import _date_walk_forward_splits
from app.quant.models import _probability_of_backtest_overfitting


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

        self.assertEqual(0.0, _probability_of_backtest_overfitting(stable))
        self.assertGreater(_probability_of_backtest_overfitting(overfit), 0.2)

    def test_small_calibration_sample_uses_sigmoid(self):
        self.assertEqual("sigmoid", choose_calibration_method(999))
        self.assertEqual("isotonic", choose_calibration_method(1000))

    def test_missing_benchmark_is_a_hard_validation_failure(self):
        report = evaluate_validation(
            passing_metrics(),
            horizon_code="SHORT",
            benchmark_available=False,
            data_fresh=True,
        )

        self.assertEqual(ModelLifecycle.DRAFT, report.lifecycle)
        self.assertIn(
            ValidationFailureCode.BENCHMARK_UNAVAILABLE,
            report.failure_codes,
        )
        self.assertFalse(report.passed)

    def test_strict_metrics_produce_validated_report(self):
        report = evaluate_validation(
            passing_metrics(),
            horizon_code="SHORT",
            benchmark_available=True,
            data_fresh=True,
        )

        self.assertEqual(ModelLifecycle.VALIDATED, report.lifecycle)
        self.assertEqual((), report.failure_codes)
        self.assertTrue(report.passed)
        self.assertTrue(all(item.passed for item in report.checks))

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
