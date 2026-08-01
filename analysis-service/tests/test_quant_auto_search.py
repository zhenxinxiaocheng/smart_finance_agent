from __future__ import annotations

import copy
import unittest
from dataclasses import replace
from types import SimpleNamespace

import optuna

from app.quant.auto_search import AutoSearchEngine, _AdaptiveStopper
from app.quant.config import QuantConfig, load_quant_config
from app.quant.engine import TrainingSample


class QuantAutoSearchTest(unittest.TestCase):
    def test_no_improvement_stopper_does_not_treat_pareto_ties_as_progress(self):
        study = optuna.create_study(
            directions=["maximize", "maximize", "minimize", "minimize", "minimize"],
        )
        stopper = _AdaptiveStopper(no_improvement_limit=2)

        def objective(trial):
            trial.set_user_attr("selectionRank", [0.0, 0.0, 0.0, -0.01])
            return 0.01, 0.5, 0.1, 0.1, 0.1

        study.optimize(objective, n_trials=10, callbacks=[stopper])

        self.assertEqual(3, len(study.trials))
        self.assertEqual("NO_IMPROVEMENT", stopper.reason)

    def test_resumed_stopper_keeps_the_best_rank_from_prior_trials(self):
        study = optuna.create_study(
            directions=["maximize", "maximize", "minimize", "minimize", "minimize"],
        )
        study.add_trial(optuna.trial.create_trial(
            values=[0.5, 0.5, 0.1, 0.1, 0.1],
            user_attrs={"selectionRank": [1.0, 0.5, 0.5]},
        ))
        stopper = _AdaptiveStopper(no_improvement_limit=2)
        stopper.restore(study)

        def objective(trial):
            trial.set_user_attr("selectionRank", [0.0, 0.0, 0.0])
            return 0.1, 0.5, 0.1, 0.1, 0.1

        study.optimize(objective, n_trials=10, callbacks=[stopper])

        self.assertEqual(3, len(study.trials))
        self.assertEqual("NO_IMPROVEMENT", stopper.reason)

    def test_failure_routing_enqueues_each_diagnosis_algorithm_pair_once(self):
        study = optuna.create_study(
            directions=["maximize", "maximize", "minimize", "minimize", "minimize"],
        )
        stopper = _AdaptiveStopper(no_improvement_limit=100)

        def objective(trial):
            trial.set_user_attr("algorithm", "XGBOOST")
            trial.set_user_attr("recommendedAlgorithm", "TREND_VOLATILITY")
            trial.set_user_attr("failureDiagnosis", {"code": "COST_TOO_HIGH"})
            trial.set_user_attr("selectionRank", [0.0, 0.0, float(trial.number)])
            return float(trial.number), 0.5, 0.1, 0.1, 0.1

        study.optimize(objective, n_trials=4, callbacks=[stopper])

        routed = [
            trial for trial in study.trials
            if trial.user_attrs.get("routedFromTrial") is not None
        ]
        self.assertEqual(1, len(routed))

    def test_search_only_materializes_the_selected_candidate_once(self):
        data = copy.deepcopy(load_quant_config().data)
        data["training"]["minimumSamples"] = 4
        data["autoSearch"]["timeBudgetSeconds"] = 60
        data["autoSearch"]["finalHoldoutFraction"] = 0.2
        data["autoSearch"]["minimumFinalHoldoutSamples"] = 2
        calls: list[bool] = []

        def trainer(samples, config, *, search_only=False, **kwargs):
            calls.append(search_only)
            return SimpleNamespace(
                model_version=f"candidate-{len(calls)}",
                status="DRAFT",
                materialized=not search_only,
                metrics={
                    "annualizedExcessReturn": -0.01,
                    "netExcessVsStrongestBaseline": -0.01,
                    "downsideProtection": 0.5,
                    "maximumDrawdown": -0.1,
                    "turnover": 0.1,
                    "crossWindowVolatility": 0.1,
                    "validationReport": {
                        "passed": False,
                        "failureCodes": ["MODEL_REJECTED"],
                    },
                },
            )

        result = AutoSearchEngine(
            QuantConfig(data),
            trainer=trainer,
            final_evaluator=lambda artifact, samples, config, **kwargs: artifact,
            maximum_trials_override=2,
        ).search(samples=self.samples(10), benchmark_available=True)

        self.assertEqual([True, True, False], calls)
        self.assertTrue(result.artifact.materialized)

    def test_selected_candidate_receives_pbo_from_all_search_trials(self):
        data = copy.deepcopy(load_quant_config().data)
        data["training"]["minimumSamples"] = 4
        data["autoSearch"]["timeBudgetSeconds"] = 60
        data["autoSearch"]["finalHoldoutFraction"] = 0.2
        data["autoSearch"]["minimumFinalHoldoutSamples"] = 2
        fold_returns = iter((
            (0.4, 0.4, 0.4, 0.4),
            (0.2, 0.2, 0.2, 0.2),
            (-0.1, -0.1, -0.1, -0.1),
        ))
        received_pbo: list[float | None] = []

        def trainer(
            samples,
            config,
            *,
            search_only=False,
            selection_pbo=None,
            **kwargs,
        ):
            if search_only:
                returns = next(fold_returns)
            else:
                returns = (0.4, 0.4, 0.4, 0.4)
                received_pbo.append(selection_pbo)
            return SimpleNamespace(
                model_version=f"candidate-{len(received_pbo)}-{returns[0]}",
                status="DRAFT",
                materialized=not search_only,
                selection_fold_returns=returns,
                metrics={
                    "annualizedExcessReturn": returns[0],
                    "netExcessVsStrongestBaseline": returns[0],
                    "downsideProtection": 0.5,
                    "maximumDrawdown": -0.1,
                    "turnover": 0.1,
                    "crossWindowVolatility": 0.1,
                    "validationReport": {
                        "passed": False,
                        "failureCodes": ["MODEL_REJECTED"],
                    },
                },
            )

        AutoSearchEngine(
            QuantConfig(data),
            trainer=trainer,
            final_evaluator=lambda artifact, samples, config, **kwargs: artifact,
            maximum_trials_override=3,
        ).search(samples=self.samples(10), benchmark_available=True)

        self.assertEqual([0.0], received_pbo)

    def test_panel_training_uses_only_target_series_for_holdout(self):
        data = copy.deepcopy(load_quant_config().data)
        data["training"]["minimumSamples"] = 4
        data["autoSearch"]["timeBudgetSeconds"] = 60
        data["autoSearch"]["finalHoldoutFraction"] = 0.2
        data["autoSearch"]["minimumFinalHoldoutSamples"] = 2
        target = self.samples(10)
        member = [
            replace(sample, series_id="PEER")
            for sample in self.samples(10)
        ]
        trained_series: list[list[str]] = []
        holdout_series: list[list[str]] = []

        def trainer(samples, config, **kwargs):
            trained_series.append([sample.series_id for sample in samples])
            return self.artifact("selected", "VALIDATED", 0.04, 0.8)

        def final_evaluator(artifact, samples, config, **kwargs):
            holdout_series.append([sample.series_id for sample in samples])
            return artifact

        result = AutoSearchEngine(
            QuantConfig(data),
            trainer=trainer,
            final_evaluator=final_evaluator,
            maximum_trials_override=1,
        ).search(
            samples=[*target, *member],
            evaluation_samples=target,
            benchmark_available=True,
        )

        self.assertEqual(8, trained_series[0].count("TARGET"))
        self.assertEqual(8, trained_series[0].count("PEER"))
        self.assertEqual([["TARGET", "TARGET"]], holdout_series)
        self.assertEqual(2, result.summary["finalHoldoutSamples"])
        self.assertEqual(10, result.summary["validationSeriesSamples"])

    def test_candidates_never_receive_final_holdout_and_selected_model_is_evaluated_once(self):
        data = copy.deepcopy(load_quant_config().data)
        data["training"]["minimumSamples"] = 4
        data["autoSearch"]["timeBudgetSeconds"] = 60
        data["autoSearch"]["finalHoldoutFraction"] = 0.2
        data["autoSearch"]["minimumFinalHoldoutSamples"] = 2
        artifacts = iter([
            self.artifact("other", "DRAFT", 0.02, 0.7),
            self.artifact("selected", "VALIDATED", 0.04, 0.8),
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
            maximum_trials_override=2,
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
        data["autoSearch"]["timeBudgetSeconds"] = 60
        data["autoSearch"]["finalHoldoutFraction"] = 0.2
        data["autoSearch"]["minimumFinalHoldoutSamples"] = 2
        artifacts = iter([
            self.artifact("draft-linear", "DRAFT", -0.05, 0.1),
            self.artifact("draft-tree", "DRAFT", 0.02, 0.6),
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
            maximum_trials_override=3,
        ).search(
            samples=self.samples(10),
            benchmark_available=True,
        )

        self.assertEqual("valid-ensemble", result.artifact.model_version)
        self.assertTrue(result.summary["qualified"])
        self.assertEqual(3, result.summary["evaluatedCandidates"])
        self.assertEqual(3, len(calls))
        self.assertTrue(all(algorithm in {
            "ELASTIC_NET",
            "XGBOOST",
            "EXTRA_TREES",
            "TREND_VOLATILITY",
            "RISK_FILTERED_MEAN_REVERSION",
            "REGIME_ENSEMBLE",
        } for algorithm, _ in calls))
        self.assertNotEqual(calls[2][1], data["version"])

    def test_returns_best_draft_and_explicitly_reports_no_qualified_model(self):
        data = copy.deepcopy(load_quant_config().data)
        data["training"]["minimumSamples"] = 4
        data["autoSearch"]["timeBudgetSeconds"] = 60
        data["autoSearch"]["finalHoldoutFraction"] = 0.2
        data["autoSearch"]["minimumFinalHoldoutSamples"] = 2
        artifacts = iter([
            self.artifact("draft-a", "DRAFT", -0.10, 0.1),
            self.artifact("draft-b", "DRAFT", -0.02, 0.2),
        ])

        result = AutoSearchEngine(
            QuantConfig(data),
            trainer=lambda samples, config, **kwargs: next(artifacts),
            final_evaluator=lambda artifact, samples, config, **kwargs: artifact,
            maximum_trials_override=2,
        ).search(samples=self.samples(10), benchmark_available=True)

        self.assertEqual("draft-b", result.artifact.model_version)
        self.assertFalse(result.summary["qualified"])
        self.assertEqual(
            "本轮优化已完成，保留风险参考并等待新数据继续优化",
            result.summary["userMessage"],
        )

    def test_failure_diagnosis_queues_a_scientific_next_algorithm(self):
        data = copy.deepcopy(load_quant_config().data)
        data["training"]["minimumSamples"] = 4
        data["autoSearch"]["timeBudgetSeconds"] = 60
        data["autoSearch"]["finalHoldoutFraction"] = 0.2
        data["autoSearch"]["minimumFinalHoldoutSamples"] = 2
        artifacts = iter([
            SimpleNamespace(
                model_version="high-cost",
                status="DRAFT",
                metrics={
                    "turnover": 5.0,
                    "annualizedNetReturn": 0.08,
                    "costStressAnnualizedExcessReturn": -0.04,
                    "validationReport": {
                        "passed": False,
                        "failureCodes": ["MODEL_REJECTED"],
                    },
                },
            ),
            self.artifact("turnover-controlled", "DRAFT", -0.01, 0.2),
        ])
        calls: list[str] = []

        def trainer(samples, config, **kwargs):
            calls.append(kwargs["algorithm"])
            return next(artifacts)

        result = AutoSearchEngine(
            QuantConfig(data),
            trainer=trainer,
            final_evaluator=lambda artifact, samples, config, **kwargs: artifact,
            maximum_trials_override=2,
        ).search(samples=self.samples(10), benchmark_available=True)

        self.assertEqual("TREND_VOLATILITY", calls[1])
        self.assertEqual(
            "COST_TOO_HIGH",
            result.summary["candidates"][0]["failureDiagnosis"]["code"],
        )

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
