from __future__ import annotations

import math
import time
from dataclasses import dataclass
from typing import Any, Callable, Mapping, Sequence

import optuna

from .config import QuantConfig, with_experiment_parameters
from .engine import TrainingSample
from .failure_diagnostics import FailureDiagnosis, diagnose_failure
from .nested_validation import split_search_and_final_holdout
from .optimization import (
    create_or_load_study,
    effective_parameter_dimension,
    optimization_budget,
    suggest_candidate,
)


@dataclass(frozen=True)
class AutoSearchResult:
    artifact: Any
    summary: dict[str, Any]


class _AdaptiveStopper:
    def __init__(self, no_improvement_limit: int):
        self.no_improvement_limit = no_improvement_limit
        self.no_improvement_count = 0
        self.reason: str | None = None

    def __call__(
        self,
        study: optuna.study.Study,
        trial: optuna.trial.FrozenTrial,
    ) -> None:
        if trial.user_attrs.get("validated"):
            self.reason = "VALIDATED"
            study.stop()
            return
        recommended = trial.user_attrs.get("recommendedAlgorithm")
        current = trial.user_attrs.get("algorithm")
        if (
            isinstance(recommended, str)
            and recommended
            and recommended != current
            and not trial.user_attrs.get("dataBlocked")
        ):
            study.enqueue_trial(
                {"algorithm": recommended},
                user_attrs={"routedFromTrial": trial.number},
            )
        pareto_numbers = {item.number for item in study.best_trials}
        if trial.state == optuna.trial.TrialState.COMPLETE and trial.number in pareto_numbers:
            self.no_improvement_count = 0
        else:
            self.no_improvement_count += 1
        if self.no_improvement_count >= self.no_improvement_limit:
            self.reason = "NO_IMPROVEMENT"
            study.stop()


class AutoSearchEngine:
    def __init__(
        self,
        config: QuantConfig,
        *,
        trainer: Callable[..., Any] | None = None,
        final_evaluator: Callable[..., Any] | None = None,
        clock: Callable[[], float] = time.monotonic,
        maximum_trials_override: int | None = None,
    ):
        if trainer is None:
            from .models import evaluate_final_holdout, train_ensemble

            trainer = train_ensemble
            final_evaluator = evaluate_final_holdout
        elif final_evaluator is None:
            raise ValueError("a custom trainer requires a final evaluator")
        self.config = config
        self.trainer = trainer
        self.final_evaluator = final_evaluator
        self.clock = clock
        self.maximum_trials_override = maximum_trials_override

    def search(
        self,
        samples: Sequence[TrainingSample],
        *,
        evaluation_samples: Sequence[TrainingSample] | None = None,
        benchmark_available: bool,
        data_fresh: bool = True,
        study_name: str = "quant-auto-search",
        storage: str | None = None,
    ) -> AutoSearchResult:
        validation_samples = (
            samples
            if evaluation_samples is None
            else evaluation_samples
        )
        validation_selection, final_holdout = split_search_and_final_holdout(
            validation_samples,
            holdout_fraction=self.config.number(
                "autoSearch.finalHoldoutFraction"
            ),
            minimum_holdout_samples=self.config.integer(
                "autoSearch.minimumFinalHoldoutSamples"
            ),
            minimum_selection_samples=self.config.integer(
                "training.minimumSamples"
            ),
        )
        selection_samples = (
            validation_selection
            if evaluation_samples is None
            else _panel_selection_before_holdout(
                samples,
                validation_selection,
                final_holdout,
            )
        )
        if len(selection_samples) < self.config.integer(
            "training.minimumSamples"
        ):
            raise ValueError(
                "candidate-selection panel is too small after target holdout purge"
            )
        dimension = effective_parameter_dimension(self.config)
        budget = optimization_budget(
            dimension,
            timeout_seconds=int(self.config.number("autoSearch.timeBudgetSeconds")),
        )
        maximum_trials = (
            min(budget.maximum_trials, self.maximum_trials_override)
            if self.maximum_trials_override is not None
            else budget.maximum_trials
        )
        if maximum_trials <= 0:
            raise ValueError("maximum trials must be positive")
        study = create_or_load_study(
            study_name=study_name,
            storage=storage,
            seed=self.config.integer("randomSeed"),
            startup_trials=min(budget.startup_trials, maximum_trials),
        )
        completed_before = sum(
            trial.state == optuna.trial.TrialState.COMPLETE
            for trial in study.trials
        )
        remaining_trials = max(0, maximum_trials - completed_before)
        artifacts: dict[int, Any] = {}
        configs: dict[int, QuantConfig] = {}
        algorithms: dict[int, str] = {}
        diagnoses: dict[int, FailureDiagnosis] = {}
        evaluated: list[dict[str, Any]] = []
        failure_policy = self.config.value("autoSearch.failureRouting")
        horizon_days = max(
            1,
            int(samples[0].label_end_index - samples[0].as_of_index),
        )

        def objective(trial: optuna.trial.Trial) -> tuple[float, ...]:
            candidate = suggest_candidate(
                trial,
                self.config,
                horizon_days=horizon_days,
            )
            candidate_config = with_experiment_parameters(
                self.config,
                candidate.parameters,
            )
            trial.set_user_attr("algorithm", candidate.algorithm)
            trial.set_user_attr("candidateParameters", candidate.parameters)
            try:
                artifact = self.trainer(
                    selection_samples,
                    candidate_config,
                    benchmark_available=benchmark_available,
                    data_fresh=data_fresh,
                    algorithm=candidate.algorithm,
                )
            except Exception as error:
                trial.set_user_attr("errorType", type(error).__name__)
                trial.set_user_attr("errorMessage", str(error)[:500])
                evaluated.append({
                    "trialNumber": trial.number,
                    "algorithm": candidate.algorithm,
                    "parameters": candidate.parameters,
                    "configVersion": candidate_config.version,
                    "status": "FAILED",
                    "errorType": type(error).__name__,
                    "errorMessage": str(error)[:500],
                })
                return (-1e12, -1e12, 1e12, 1e12, 1e12)

            values = self._objectives(artifact)
            artifacts[trial.number] = artifact
            configs[trial.number] = candidate_config
            algorithms[trial.number] = candidate.algorithm
            validated = artifact.status == "VALIDATED"
            trial.set_user_attr("modelVersion", artifact.model_version)
            trial.set_user_attr("modelStatus", artifact.status)
            trial.set_user_attr("validated", validated)
            trial.set_user_attr("failureCodes", self._failure_codes(artifact))
            diagnosis = diagnose_failure(
                artifact.metrics,
                current_algorithm=candidate.algorithm,
                policy=(
                    failure_policy
                    if isinstance(failure_policy, Mapping)
                    else None
                ),
            )
            diagnoses[trial.number] = diagnosis
            trial.set_user_attr(
                "recommendedAlgorithm",
                diagnosis.recommended_algorithm,
            )
            trial.set_user_attr("dataBlocked", diagnosis.data_blocked)
            trial.set_user_attr("failureDiagnosis", diagnosis.as_dict())
            evaluated.append({
                "trialNumber": trial.number,
                "algorithm": candidate.algorithm,
                "parameters": candidate.parameters,
                "configVersion": candidate_config.version,
                "modelVersion": artifact.model_version,
                "status": artifact.status,
                "qualified": validated,
                "economicRole": artifact.metrics.get("economicRole"),
                "failureCodes": self._failure_codes(artifact),
                "failureDiagnosis": diagnosis.as_dict(),
                "objectives": list(values),
                "score": list(self._rank(artifact)),
            })
            return values

        stopper = _AdaptiveStopper(budget.no_improvement_limit)
        started_at = self.clock()
        if remaining_trials:
            study.optimize(
                objective,
                n_trials=remaining_trials,
                timeout=budget.timeout_seconds,
                callbacks=[stopper],
                catch=(RuntimeError, ValueError, FloatingPointError),
            )
        elapsed_seconds = max(0.0, self.clock() - started_at)
        if not artifacts:
            first_failure = next(
                (
                    item for item in evaluated
                    if item.get("status") == "FAILED"
                ),
                {},
            )
            raise ValueError(
                "optimization study has no new usable artifact; "
                "a new dataset, feature version, or algorithm version is required"
                + (
                    f"; first failure: {first_failure.get('errorType')}: "
                    f"{first_failure.get('errorMessage')}"
                    if first_failure
                    else ""
                )
            )

        pareto_numbers = {trial.number for trial in study.best_trials}
        eligible = [
            (trial_number, artifact)
            for trial_number, artifact in artifacts.items()
            if trial_number in pareto_numbers
        ]
        if not eligible:
            eligible = list(artifacts.items())
        selected_trial, best_artifact = max(
            eligible,
            key=lambda item: self._rank(item[1]),
        )
        best_config = configs[selected_trial]
        best_algorithm = algorithms[selected_trial]
        selected_diagnosis = diagnoses[selected_trial]
        final_holdout_evaluated = best_artifact.status == "VALIDATED"
        if final_holdout_evaluated:
            best_artifact = self.final_evaluator(
                best_artifact,
                final_holdout,
                best_config,
                benchmark_available=benchmark_available,
                data_fresh=data_fresh,
                algorithm=best_algorithm,
            )
        qualified = best_artifact.status == "VALIDATED"
        stopped_reason = stopper.reason
        if stopped_reason is None:
            if elapsed_seconds >= budget.timeout_seconds:
                stopped_reason = "TIME_BUDGET"
            elif completed_before + len(evaluated) >= maximum_trials:
                stopped_reason = "TRIAL_BUDGET"
            else:
                stopped_reason = "COMPLETED"
        return AutoSearchResult(
            artifact=best_artifact,
            summary={
                "qualified": qualified,
                "optimizationStudyId": study.study_name,
                "generation": len(study.trials),
                "effectiveParameterDimension": dimension,
                "startupTrials": min(budget.startup_trials, maximum_trials),
                "maximumTrials": maximum_trials,
                "noImprovementLimit": budget.no_improvement_limit,
                "evaluatedCandidates": len(evaluated),
                "completedTrialsBeforeRun": completed_before,
                "stopReason": stopped_reason,
                "elapsedSeconds": elapsed_seconds,
                "selectedTrialNumber": selected_trial,
                "selectedAlgorithm": best_algorithm,
                "selectedModelVersion": best_artifact.model_version,
                "selectedStatus": best_artifact.status,
                "economicRole": best_artifact.metrics.get("economicRole"),
                "failureDiagnosis": (
                    None if qualified else selected_diagnosis.as_dict()
                ),
                "nextAdjustment": (
                    None if qualified else selected_diagnosis.adjustment
                ),
                "technicalSignalAvailable": True,
                "nextAction": (
                    "PROMOTE"
                    if qualified
                    else "CONTINUE_ON_NEW_DATA_OR_VERSION"
                ),
                "selectionSamples": len(selection_samples),
                "validationSeriesSamples": len(validation_samples),
                "finalHoldoutSamples": len(final_holdout),
                "finalHoldoutEvaluated": final_holdout_evaluated,
                "paretoTrialNumbers": sorted(pareto_numbers),
                "candidates": evaluated,
                "userMessage": (
                    "已找到通过经济验证的量化模型"
                    if qualified
                    else "本轮优化已完成，保留风险参考并等待新数据继续优化"
                ),
            },
        )
    @staticmethod
    def _objectives(artifact: Any) -> tuple[float, ...]:
        metrics = artifact.metrics
        net_excess = _finite(metrics.get(
            "netExcessVsStrongestBaseline",
            metrics.get("annualizedExcessReturn"),
        ))
        downside_protection = _finite(metrics.get(
            "downsideProtection",
            -_finite(metrics.get("downsideCapture"), default=1.0),
        ))
        maximum_drawdown = abs(_finite(metrics.get("maximumDrawdown")))
        turnover = max(0.0, _finite(metrics.get("turnover")))
        cross_window_volatility = max(
            0.0,
            _finite(metrics.get("crossWindowVolatility")),
        )
        return (
            net_excess,
            downside_protection,
            maximum_drawdown,
            turnover,
            cross_window_volatility,
        )

    @staticmethod
    def _rank(artifact: Any) -> tuple[float, ...]:
        metrics = artifact.metrics
        report = metrics.get("validationReport")
        passed = isinstance(report, Mapping) and bool(report.get("passed"))
        economic_role = str(metrics.get("economicRole") or "")
        return (
            float(artifact.status == "VALIDATED"),
            float(economic_role in {"RETURN_ENHANCER", "DRAWDOWN_GUARD"}),
            float(passed),
            _finite(metrics.get(
                "netExcessVsStrongestBaseline",
                metrics.get("annualizedExcessReturn"),
            )),
            _finite(metrics.get("downsideProtection")),
            -abs(_finite(metrics.get("maximumDrawdown"))),
            -max(0.0, _finite(metrics.get("turnover"))),
            _finite(metrics.get("sharpe")),
        )

    @staticmethod
    def _failure_codes(artifact: Any) -> list[str]:
        report = artifact.metrics.get("validationReport")
        if not isinstance(report, Mapping):
            return []
        raw = report.get("failureCodes")
        if not isinstance(raw, Sequence) or isinstance(raw, (str, bytes)):
            return []
        return [str(item) for item in raw]


def _panel_selection_before_holdout(
    samples: Sequence[TrainingSample],
    validation_selection: Sequence[TrainingSample],
    final_holdout: Sequence[TrainingSample],
) -> list[TrainingSample]:
    if not final_holdout:
        raise ValueError("target final holdout cannot be empty")
    validation_series = {
        sample.series_id
        for sample in [*validation_selection, *final_holdout]
    }
    if len(validation_series) != 1:
        raise ValueError("economic validation must use exactly one target series")
    validation_series_id = next(iter(validation_series))
    selected_target = {
        _sample_identity(sample)
        for sample in validation_selection
    }
    holdout_start = min(sample.as_of_date for sample in final_holdout)
    boundary_by_series: dict[str, int] = {}
    for sample in samples:
        if sample.as_of_date < holdout_start:
            continue
        boundary_by_series[sample.series_id] = min(
            boundary_by_series.get(sample.series_id, sample.as_of_index),
            sample.as_of_index,
        )
    selected: list[TrainingSample] = []
    for sample in samples:
        if sample.series_id == validation_series_id:
            if _sample_identity(sample) in selected_target:
                selected.append(sample)
            continue
        if sample.as_of_date >= holdout_start:
            continue
        boundary = boundary_by_series.get(sample.series_id)
        if boundary is not None and sample.label_end_index >= boundary:
            continue
        selected.append(sample)
    return sorted(
        selected,
        key=lambda sample: (
            sample.as_of_date,
            sample.series_id,
            sample.as_of_index,
        ),
    )


def _sample_identity(sample: TrainingSample) -> tuple[Any, ...]:
    return (
        sample.series_id,
        sample.as_of_index,
        sample.label_end_index,
        sample.as_of_date,
    )


def _finite(value: Any, default: float = 0.0) -> float:
    try:
        result = float(value)
    except (TypeError, ValueError):
        return default
    return result if math.isfinite(result) else default
