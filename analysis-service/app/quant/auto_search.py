from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Any, Callable, Mapping, Sequence

from .config import QuantConfig, with_experiment_parameters
from .engine import TrainingSample
from .nested_validation import split_search_and_final_holdout


@dataclass(frozen=True)
class AutoSearchResult:
    artifact: Any
    summary: dict[str, Any]


class AutoSearchEngine:
    def __init__(
        self,
        config: QuantConfig,
        *,
        trainer: Callable[..., Any] | None = None,
        final_evaluator: Callable[..., Any] | None = None,
        clock: Callable[[], float] = time.monotonic,
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

    def search(
        self,
        samples: Sequence[TrainingSample],
        *,
        benchmark_available: bool,
        data_fresh: bool = True,
    ) -> AutoSearchResult:
        selection_samples, final_holdout = split_search_and_final_holdout(
            samples,
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
        candidates = self._candidates()
        maximum = min(
            self.config.integer("autoSearch.maximumCandidates"),
            len(candidates),
        )
        no_improvement_limit = self.config.integer(
            "autoSearch.noImprovementLimit"
        )
        deadline = self.clock() + self.config.number(
            "autoSearch.timeBudgetSeconds"
        )
        best_artifact: Any | None = None
        best_config: QuantConfig | None = None
        best_algorithm: str | None = None
        best_rank: tuple[float, ...] | None = None
        no_improvement = 0
        evaluated: list[dict[str, Any]] = []
        failures: list[Exception] = []
        for position, candidate in enumerate(candidates[:maximum]):
            if position > 0 and self.clock() >= deadline:
                break
            algorithm = str(candidate["algorithm"]).strip().upper()
            parameters = dict(candidate.get("parameters") or {})
            candidate_config = with_experiment_parameters(
                self.config,
                parameters,
            )
            try:
                artifact = self.trainer(
                    selection_samples,
                    candidate_config,
                    benchmark_available=benchmark_available,
                    data_fresh=data_fresh,
                    algorithm=algorithm,
                )
            except Exception as error:
                failures.append(error)
                evaluated.append({
                    "algorithm": algorithm,
                    "configVersion": candidate_config.version,
                    "status": "FAILED",
                    "errorType": type(error).__name__,
                })
                no_improvement += 1
            else:
                rank = self._rank(artifact)
                evaluated.append({
                    "algorithm": algorithm,
                    "configVersion": candidate_config.version,
                    "modelVersion": artifact.model_version,
                    "status": artifact.status,
                    "qualified": artifact.status == "VALIDATED",
                    "score": list(rank),
                })
                if best_rank is None or rank > best_rank:
                    best_artifact = artifact
                    best_config = candidate_config
                    best_algorithm = algorithm
                    best_rank = rank
                    no_improvement = 0
                else:
                    no_improvement += 1
            if no_improvement >= no_improvement_limit:
                break
        if best_artifact is None:
            if failures:
                raise failures[-1]
            raise ValueError("auto search did not evaluate any candidate")
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
        return AutoSearchResult(
            artifact=best_artifact,
            summary={
                "qualified": qualified,
                "evaluatedCandidates": len(evaluated),
                "maximumCandidates": maximum,
                "selectedModelVersion": best_artifact.model_version,
                "selectedStatus": best_artifact.status,
                "selectionSamples": len(selection_samples),
                "finalHoldoutSamples": len(final_holdout),
                "finalHoldoutEvaluated": final_holdout_evaluated,
                "candidates": evaluated,
                "userMessage": (
                    "已找到通过严格验证的模型"
                    if qualified
                    else "当前没有通过严格验证的模型"
                ),
            },
        )

    def _candidates(self) -> list[Mapping[str, Any]]:
        raw = self.config.value("autoSearch.candidates")
        if not isinstance(raw, list) or not raw:
            raise ValueError("autoSearch.candidates must be a non-empty list")
        candidates: list[Mapping[str, Any]] = []
        for candidate in raw:
            if not isinstance(candidate, Mapping):
                raise ValueError("auto search candidate must be an object")
            algorithm = candidate.get("algorithm")
            if not isinstance(algorithm, str) or not algorithm.strip():
                raise ValueError("auto search candidate algorithm is required")
            parameters = candidate.get("parameters") or {}
            if not isinstance(parameters, Mapping):
                raise ValueError("auto search candidate parameters must be an object")
            candidates.append(candidate)
        return candidates

    @staticmethod
    def _rank(artifact: Any) -> tuple[float, ...]:
        metrics = artifact.metrics
        report = metrics.get("validationReport")
        passed = isinstance(report, Mapping) and bool(report.get("passed"))
        return (
            float(artifact.status == "VALIDATED"),
            float(passed),
            float(metrics.get("oosR2") or 0.0),
            float(metrics.get("annualizedExcessReturn") or 0.0),
            float(metrics.get("sharpe") or 0.0),
            float(metrics.get("brierSkill") or 0.0),
        )
