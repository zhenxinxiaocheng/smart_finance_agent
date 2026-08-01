from __future__ import annotations

import hashlib
import json
import itertools
from dataclasses import dataclass
from typing import Any, Sequence

import numpy as np
from scipy.stats import norm, spearmanr
from sklearn.isotonic import IsotonicRegression
from sklearn.ensemble import ExtraTreesClassifier, ExtraTreesRegressor
from sklearn.linear_model import ElasticNet, LogisticRegression
from sklearn.metrics import brier_score_loss, log_loss
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler
from xgboost import XGBClassifier, XGBRegressor

from .config import QuantConfig
from .engine import InsufficientQuantData, TrainingSample
from .validation import (
    choose_calibration_method,
    evaluate_validation,
    validation_policy,
)


class SigmoidCalibrator:
    def __init__(self) -> None:
        self.model = LogisticRegression(C=1_000_000.0, solver="lbfgs")

    def fit(self, values: np.ndarray, labels: np.ndarray) -> "SigmoidCalibrator":
        self.model.fit(np.asarray(values, dtype=float).reshape(-1, 1), labels)
        return self

    def predict(self, values: np.ndarray) -> np.ndarray:
        rows = np.asarray(values, dtype=float).reshape(-1, 1)
        return self.model.predict_proba(rows)[:, 1]


class RuleStrategyClassifier:
    def __init__(
        self,
        algorithm: str,
        feature_names: Sequence[str],
        config: QuantConfig,
    ) -> None:
        self.algorithm = algorithm
        self.feature_names = tuple(feature_names)
        self.parameters = _rule_parameters(algorithm, config)

    def fit(self, values: np.ndarray, labels: np.ndarray) -> "RuleStrategyClassifier":
        return self

    def predict_proba(self, values: np.ndarray) -> np.ndarray:
        signal = _rule_signal(
            np.asarray(values, dtype=float),
            self.feature_names,
            self.algorithm,
            self.parameters,
        )
        positive = np.clip(1.0 / (1.0 + np.exp(-signal)), 1e-6, 1 - 1e-6)
        return np.column_stack((1.0 - positive, positive))


class RuleStrategyRegressor:
    def __init__(
        self,
        algorithm: str,
        feature_names: Sequence[str],
        config: QuantConfig,
    ) -> None:
        self.algorithm = algorithm
        self.feature_names = tuple(feature_names)
        self.parameters = _rule_parameters(algorithm, config)

    def fit(self, values: np.ndarray, labels: np.ndarray) -> "RuleStrategyRegressor":
        return self

    def predict(self, values: np.ndarray) -> np.ndarray:
        signal = _rule_signal(
            np.asarray(values, dtype=float),
            self.feature_names,
            self.algorithm,
            self.parameters,
        )
        return np.clip(signal / 12.0, -0.25, 0.25)


@dataclass
class ModelArtifact:
    model_version: str
    config_version: str
    feature_names: tuple[str, ...]
    status: str
    metrics: dict[str, Any]
    linear_classifier: Any
    linear_regressor: Any
    tree_classifier: Any
    tree_regressor: Any
    quantile_lower_regressor: Any
    quantile_upper_regressor: Any
    calibrator: Any
    residual_interval: tuple[float, float]
    action_thresholds: dict[str, float]
    ensemble_linear_weight: float
    confidence_thresholds: dict[str, float]
    top_factor_count: int
    validation_as_of_indices: tuple[int, ...]
    validation_target_weights: tuple[float, ...]
    selection_fold_returns: tuple[float, ...] = ()
    materialized: bool = True


@dataclass(frozen=True)
class QuantPrediction:
    probability_positive_excess: float
    expected_excess_return: float
    prediction_interval: tuple[float, float]
    action: str
    confidence: str
    top_factors: tuple[dict[str, float], ...]


def train_ensemble(
    samples: Sequence[TrainingSample],
    config: QuantConfig,
    *,
    benchmark_available: bool = True,
    data_fresh: bool = True,
    algorithm: str = "REGIME_ENSEMBLE",
    validation_series_id: str = "TARGET",
    search_only: bool = False,
    selection_pbo: float | None = None,
) -> ModelArtifact:
    minimum = config.integer("training.minimumSamples")
    if len(samples) < minimum:
        raise InsufficientQuantData(
            f"training partition requires at least {minimum} samples"
        )
    feature_names = tuple(sorted(samples[0].features))
    if any(tuple(sorted(sample.features)) != feature_names for sample in samples):
        raise ValueError("all training samples must share one feature schema")
    validation_sample_count = sum(
        sample.series_id == validation_series_id
        for sample in samples
    )
    if validation_sample_count < config.integer(
        "training.minimumEvaluationSamples"
    ):
        raise InsufficientQuantData(
            "target series has insufficient samples for economic validation"
        )
    x = np.asarray([[sample.features[name] for name in feature_names] for sample in samples], dtype=float)
    y_class = np.asarray([
        int(
            sample.positive_return
            if sample.positive_return is not None
            else sample.positive_excess
        )
        for sample in samples
    ], dtype=int)
    y_return = np.asarray([
        sample.net_return
        if sample.net_return is not None
        else sample.net_excess_return
        for sample in samples
    ], dtype=float)
    _require_binary_labels(y_class, "training")

    folds = min(config.integer("training.walkForwardFolds"), max(2, len(samples) // minimum))
    gap = max(0, int(round(samples[0].label_end_index - samples[0].as_of_index)
                     * config.number("training.embargoHorizonMultiplier")))
    splits = _date_walk_forward_splits(
        samples,
        folds=folds,
        embargo_dates=gap,
        validation_series_id=validation_series_id,
    )
    probabilities = np.full(len(samples), np.nan)
    expected = np.full(len(samples), np.nan)
    buy_threshold = config.number("prediction.buyWatchProbability")
    minimum_expected = config.number("prediction.minimumExpectedExcessReturn")
    algorithm = _canonical_algorithm(algorithm)
    if algorithm not in {
        "ELASTIC_NET",
        "XGBOOST",
        "EXTRA_TREES",
        "TREND_VOLATILITY",
        "RISK_FILTERED_MEAN_REVERSION",
        "REGIME_ENSEMBLE",
    }:
        raise ValueError(f"unsupported quant algorithm: {algorithm}")
    linear_weight = {
        "ELASTIC_NET": 1.0,
        "XGBOOST": 0.0,
        "EXTRA_TREES": 0.0,
        "TREND_VOLATILITY": 0.0,
        "RISK_FILTERED_MEAN_REVERSION": 0.0,
        "REGIME_ENSEMBLE": -1.0,
    }[algorithm]
    fold_passes = 0
    drawdown_guard_fold_passes = 0
    fold_count = 0
    fold_rank_ics: list[float] = []
    strategy_fold_returns: list[float] = []
    for train_indices, test_indices in splits:
        if len(np.unique(y_class[train_indices])) < 2:
            continue
        target_test_indices = _validation_series_indices(
            samples,
            test_indices,
            validation_series_id,
        )
        if not len(target_test_indices):
            continue
        models = _fit_models(
            x[train_indices], y_class[train_indices], y_return[train_indices], config,
            include_quantiles=False,
            algorithm=algorithm,
            feature_names=feature_names,
        )
        raw_probability, raw_expected = _raw_predict(
            models,
            x[target_test_indices],
            linear_weight,
            algorithm,
            feature_names,
        )
        probabilities[target_test_indices] = raw_probability
        expected[target_test_indices] = raw_expected
        fold_positions = _non_overlapping_positions(
            samples,
            target_test_indices,
        )
        fold_signals = (
            (raw_probability[fold_positions] >= buy_threshold)
            & (raw_expected[fold_positions] >= minimum_expected)
        )
        fold_returns = np.where(
            fold_signals,
            y_return[target_test_indices[fold_positions]],
            0.0,
        )
        fold_return = _compounded_return(fold_returns)
        fold_buy_and_hold_return = _compounded_return(
            y_return[target_test_indices[fold_positions]]
        )
        strategy_fold_returns.append(fold_return)
        fold_passes += int(fold_return > 0)
        drawdown_guard_fold_passes += int(_drawdown_guard_fold_pass(
            fold_return,
            fold_buy_and_hold_return,
            config.number("promotion.drawdownGuard.maximumDownsideCapture"),
        ))
        fold_rank_ics.append(
            _rank_ic(
                raw_expected[fold_positions],
                y_return[target_test_indices[fold_positions]],
            )
        )
        fold_count += 1

    validation_mask = ~np.isnan(probabilities)
    out_of_sample_indices = _validation_series_indices(
        samples,
        np.flatnonzero(validation_mask),
        validation_series_id,
    )
    calibration_indices, evaluation_indices = _chronological_calibration_split(
        samples,
        out_of_sample_indices,
        config.number("training.calibrationFraction"),
        config.integer("training.minimumCalibrationSamples"),
        config.integer("training.minimumEvaluationSamples"),
        config.number("training.embargoHorizonMultiplier"),
    )
    if not len(calibration_indices) or not len(evaluation_indices):
        raise InsufficientQuantData(
            "walk-forward validation produced insufficient out-of-sample predictions"
        )
    _require_binary_labels(y_class[calibration_indices], "calibration")
    _require_binary_labels(y_class[evaluation_indices], "evaluation")

    calibration_method = choose_calibration_method(len(calibration_indices))
    evaluation_calibrator = _new_calibrator(calibration_method)
    evaluation_calibrator.fit(probabilities[calibration_indices], y_class[calibration_indices])
    calibrated = evaluation_calibrator.predict(probabilities[evaluation_indices])
    base_probability = float(np.mean(y_class[calibration_indices]))
    model_brier = brier_score_loss(y_class[evaluation_indices], calibrated)
    baseline_brier = brier_score_loss(
        y_class[evaluation_indices],
        np.full(len(evaluation_indices), base_probability),
    )
    brier_skill = 0.0 if baseline_brier == 0 else 1 - model_brier / baseline_brier
    model_log_loss = log_loss(y_class[evaluation_indices], calibrated, labels=[0, 1])
    baseline_log_loss = log_loss(
        y_class[evaluation_indices],
        np.full(len(evaluation_indices), base_probability),
        labels=[0, 1],
    )
    log_loss_skill = (
        0.0 if baseline_log_loss == 0 else 1 - model_log_loss / baseline_log_loss
    )
    calibration_intercept, calibration_slope = _calibration_parameters(
        y_class[evaluation_indices],
        calibrated,
    )
    evaluation_positions = _non_overlapping_positions(samples, evaluation_indices)
    strategy_signals = (
        (calibrated[evaluation_positions] >= buy_threshold)
        & (expected[evaluation_indices[evaluation_positions]] >= minimum_expected)
    )
    event_target_weights = (
        (calibrated >= buy_threshold)
        & (expected[evaluation_indices] >= minimum_expected)
    ).astype(float)
    strategy_returns = np.where(
        strategy_signals,
        y_return[evaluation_indices[evaluation_positions]],
        0.0,
    )
    annualization = config.integer("annualizationDays")
    elapsed_days = _elapsed_days(samples, evaluation_indices[evaluation_positions])
    mean_return = float(np.mean(strategy_returns)) if len(strategy_returns) else 0.0
    volatility = float(np.std(strategy_returns, ddof=1)) if len(strategy_returns) > 1 else 0.0
    periods_per_year = 0.0 if elapsed_days == 0 else annualization * len(strategy_returns) / elapsed_days
    sharpe = 0.0 if volatility == 0 else mean_return / volatility * np.sqrt(periods_per_year)
    annualized_excess = _annualized_return(strategy_returns, elapsed_days, annualization)
    maximum_drawdown = _maximum_drawdown(strategy_returns)
    evaluation_sample_indices = evaluation_indices[evaluation_positions]
    buy_and_hold_returns = y_return[evaluation_sample_indices]
    official_benchmark_returns = np.asarray([
        (
            (samples[int(index)].net_return
             if samples[int(index)].net_return is not None
             else samples[int(index)].net_excess_return)
            - samples[int(index)].net_excess_return
        )
        for index in evaluation_sample_indices
    ], dtype=float)
    buy_and_hold_annualized = _annualized_return(
        buy_and_hold_returns,
        elapsed_days,
        annualization,
    )
    official_benchmark_annualized = _annualized_return(
        official_benchmark_returns,
        elapsed_days,
        annualization,
    ) if benchmark_available else 0.0
    cash_annualized = config.number("baselines.cashAnnualizedReturn")
    baselines = {
        "BUY_AND_HOLD": buy_and_hold_annualized,
        "CASH": cash_annualized,
    }
    if benchmark_available:
        baselines["OFFICIAL_BENCHMARK"] = official_benchmark_annualized
    strongest_baseline_name, strongest_baseline_return = max(
        baselines.items(),
        key=lambda item: item[1],
    )
    net_excess_vs_strongest = annualized_excess - strongest_baseline_return
    buy_and_hold_drawdown = _maximum_drawdown(buy_and_hold_returns)
    drawdown_reduction = (
        0.0
        if abs(buy_and_hold_drawdown) <= 1e-12
        else 1.0 - abs(maximum_drawdown) / abs(buy_and_hold_drawdown)
    )
    negative_market = buy_and_hold_returns < 0
    market_downside = abs(float(np.sum(buy_and_hold_returns[negative_market])))
    strategy_downside = abs(float(np.sum(
        np.minimum(strategy_returns[negative_market], 0.0)
    )))
    downside_capture = (
        0.0 if market_downside <= 1e-12 else strategy_downside / market_downside
    )
    turnover = float(np.mean(np.abs(np.diff(
        np.concatenate(([0.0], event_target_weights))
    )))) if len(event_target_weights) else 0.0
    cross_window_volatility = (
        float(np.std(strategy_fold_returns, ddof=1))
        if len(strategy_fold_returns) > 1
        else 0.0
    )
    fold_pass_ratio = fold_passes / fold_count if fold_count else 0.0
    drawdown_guard_fold_pass_ratio = (
        drawdown_guard_fold_passes / fold_count if fold_count else 0.0
    )
    evaluation_expected = expected[evaluation_indices]
    evaluation_actual = y_return[evaluation_indices]
    oos_r2 = _oos_r2(evaluation_actual, evaluation_expected)
    mae = float(np.mean(np.abs(evaluation_actual - evaluation_expected)))
    rmse = float(np.sqrt(np.mean(np.square(evaluation_actual - evaluation_expected))))
    dm_p_value = _diebold_mariano_p_value(evaluation_actual, evaluation_expected)
    evaluation_rank_ic = _rank_ic(evaluation_expected, evaluation_actual)
    positive_ic_ratio = (
        sum(value > 0 for value in fold_rank_ics) / len(fold_rank_ics)
        if fold_rank_ics
        else 0.0
    )
    calibration_residuals = (
        y_return[calibration_indices] - expected[calibration_indices]
    )
    lower_quantile = config.number("prediction.residualLowerQuantile")
    upper_quantile = config.number("prediction.residualUpperQuantile")
    residual_lower = float(np.quantile(calibration_residuals, lower_quantile))
    residual_upper = float(np.quantile(calibration_residuals, upper_quantile))
    interval_lower = evaluation_expected + residual_lower
    interval_upper = evaluation_expected + residual_upper
    interval_coverage = float(
        np.mean(
            (evaluation_actual >= interval_lower)
            & (evaluation_actual <= interval_upper)
        )
    )
    pinball_skill = _pinball_skill(
        y_return[calibration_indices],
        evaluation_actual,
        interval_lower,
        interval_upper,
        lower_quantile,
        upper_quantile,
    )
    deflated_sharpe_probability = _deflated_sharpe_probability(
        strategy_returns,
        sharpe,
        benchmark_sharpe=config.number("promotion.minimumSharpe"),
    )
    cost_stress_return = annualized_excess - config.number(
        "promotion.costStressAnnualizedPenalty"
    )
    cost_stress_excess_vs_strongest = (
        cost_stress_return - strongest_baseline_return
    )
    independent_event_count = float(len(evaluation_positions))
    feature_distribution = {
        name: {
            "mean": round(float(np.mean(x[:, position])), 10),
            "std": round(float(np.std(x[:, position])), 10),
        }
        for position, name in enumerate(feature_names)
    }
    label_distribution = {
        "mean": round(float(np.mean(y_return)), 10),
        "std": round(float(np.std(y_return)), 10),
    }
    metrics = {
        "algorithm": algorithm,
        "annualizedNetReturn": round(annualized_excess, 10),
        "annualizedExcessReturn": round(
            annualized_excess - official_benchmark_annualized,
            10,
        ),
        "buyAndHoldAnnualizedReturn": round(buy_and_hold_annualized, 10),
        "officialBenchmarkAnnualizedReturn": (
            round(official_benchmark_annualized, 10)
            if benchmark_available else None
        ),
        "cashAnnualizedReturn": round(cash_annualized, 10),
        "strongestBaseline": strongest_baseline_name,
        "strongestBaselineAnnualizedReturn": round(
            strongest_baseline_return,
            10,
        ),
        "netExcessVsStrongestBaseline": round(
            net_excess_vs_strongest,
            10,
        ),
        "buyAndHoldMaximumDrawdown": round(buy_and_hold_drawdown, 10),
        "drawdownReduction": round(drawdown_reduction, 10),
        "downsideCapture": round(downside_capture, 10),
        "downsideProtection": round(1.0 - downside_capture, 10),
        "turnover": round(turnover, 10),
        "crossWindowVolatility": round(cross_window_volatility, 10),
        "sharpe": round(float(sharpe), 10),
        "maximumDrawdown": round(maximum_drawdown, 10),
        "brierSkill": round(float(brier_skill), 10),
        "logLossSkill": round(float(log_loss_skill), 10),
        "positiveLabelRate": round(float(np.mean(y_class)), 10),
        "calibrationMethod": calibration_method,
        "calibrationSlope": round(calibration_slope, 10),
        "calibrationIntercept": round(calibration_intercept, 10),
        "oosR2": round(oos_r2, 10),
        "mae": round(mae, 10),
        "rmse": round(rmse, 10),
        "dmPValue": round(dm_p_value, 10),
        "medianRankIc": round(
            float(np.median(fold_rank_ics)) if fold_rank_ics else evaluation_rank_ic,
            10,
        ),
        "positiveIcFoldRatio": round(positive_ic_ratio, 10),
        "intervalCoverage": round(interval_coverage, 10),
        "pinballSkill": round(pinball_skill, 10),
        "deflatedSharpeProbability": round(deflated_sharpe_probability, 10),
        "pbo": (
            None
            if selection_pbo is None
            else round(float(selection_pbo), 10)
        ),
        "costStressAnnualizedExcessReturn": round(cost_stress_return, 10),
        "costStressNetExcessVsStrongestBaseline": round(
            cost_stress_excess_vs_strongest,
            10,
        ),
        "independentEventCount": independent_event_count,
        "evaluationSampleCount": float(len(evaluation_indices)),
        "horizonDays": float(
            samples[0].label_end_index - samples[0].as_of_index
        ),
        "featureDistribution": feature_distribution,
        "labelDistribution": label_distribution,
        "foldPassRatio": round(fold_pass_ratio, 10),
        "drawdownGuardFoldPassRatio": round(
            drawdown_guard_fold_pass_ratio,
            10,
        ),
        "walkForwardFolds": float(fold_count),
        "sampleCount": float(validation_sample_count),
        "trainingSampleCount": float(len(samples)),
        "validationSeriesSampleCount": float(validation_sample_count),
        "policyMinimumPaperTradingDays": float(config.integer("promotion.minimumPaperTradingDays")),
        "policyMaximumBrierScore": config.number("monitoring.maximumBrierScore"),
        "policyMinimumRealizedExcessReturn": config.number("monitoring.minimumRealizedExcessReturn"),
        "policyMaximumFeatureZScore": config.number("monitoring.maximumFeatureZScore"),
        "policyMaximumLabelZScore": config.number("monitoring.maximumLabelZScore"),
        "policyConsecutiveFailuresBeforeRetirement": float(
            config.integer("monitoring.consecutiveFailuresBeforeRetirement")),
    }
    validation_report = evaluate_validation(
        metrics,
        policy=validation_policy(config),
        benchmark_available=benchmark_available,
        data_fresh=data_fresh,
    )
    metrics["validationReport"] = validation_report.as_dict()
    metrics["economicRole"] = validation_report.economic_role.value
    metrics["diagnosticsPassed"] = validation_report.diagnostics_passed
    status = validation_report.lifecycle.value
    interval = (
        residual_lower,
        residual_upper,
    )
    version_material = {
        "config": config.data,
        "algorithm": algorithm,
        "features": feature_names,
        "dates": [sample.as_of_date for sample in samples],
        "labels": [
            round(
                sample.net_return
                if sample.net_return is not None
                else sample.net_excess_return,
                12,
            )
            for sample in samples
        ],
        "metrics": metrics,
    }
    model_version = hashlib.sha256(
        json.dumps(version_material, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()
    artifact_fields = {
        "model_version": model_version,
        "config_version": config.version,
        "feature_names": feature_names,
        "status": status,
        "metrics": metrics,
        "residual_interval": interval,
        "action_thresholds": {
            "buy": config.number("prediction.buyWatchProbability"),
            "add": config.number("prediction.addProbability"),
            "reduce": config.number("prediction.reduceProbability"),
            "exit": config.number("prediction.exitProbability"),
            "minimumExpected": config.number("prediction.minimumExpectedExcessReturn"),
        },
        "ensemble_linear_weight": linear_weight,
        "confidence_thresholds": {
            "highRatio": config.number("prediction.confidence.highWidthToExpectedRatio"),
            "mediumRatio": config.number("prediction.confidence.mediumWidthToExpectedRatio"),
            "minimumMagnitude": config.number("prediction.confidence.minimumExpectedMagnitude"),
        },
        "top_factor_count": config.integer("prediction.topFactorCount"),
        "validation_as_of_indices": tuple(
            samples[int(index)].as_of_index for index in evaluation_indices
        ),
        "validation_target_weights": tuple(
            float(value) for value in event_target_weights
        ),
        "selection_fold_returns": tuple(
            float(value) for value in strategy_fold_returns
        ),
    }
    if search_only:
        return ModelArtifact(
            **artifact_fields,
            linear_classifier=None,
            linear_regressor=None,
            tree_classifier=None,
            tree_regressor=None,
            quantile_lower_regressor=None,
            quantile_upper_regressor=None,
            calibrator=None,
            materialized=False,
        )

    final_models = _fit_models(
        x,
        y_class,
        y_return,
        config,
        algorithm=algorithm,
        feature_names=feature_names,
    )
    production_method = choose_calibration_method(len(out_of_sample_indices))
    production_calibrator = _new_calibrator(production_method)
    production_calibrator.fit(
        probabilities[out_of_sample_indices],
        y_class[out_of_sample_indices],
    )
    return ModelArtifact(
        **artifact_fields,
        linear_classifier=final_models[0],
        linear_regressor=final_models[1],
        tree_classifier=final_models[2],
        tree_regressor=final_models[3],
        quantile_lower_regressor=final_models[4],
        quantile_upper_regressor=final_models[5],
        calibrator=production_calibrator,
        materialized=True,
    )


def evaluate_final_holdout(
    artifact: ModelArtifact,
    samples: Sequence[TrainingSample],
    config: QuantConfig,
    *,
    benchmark_available: bool,
    data_fresh: bool,
    algorithm: str,
) -> ModelArtifact:
    minimum_samples = config.integer(
        "autoSearch.finalHoldoutValidation.minimumSamples"
    )
    labels = np.asarray([
        int(
            sample.positive_return
            if sample.positive_return is not None
            else sample.positive_excess
        )
        for sample in samples
    ], dtype=int)
    actual = np.asarray([
        sample.net_return
        if sample.net_return is not None
        else sample.net_excess_return
        for sample in samples
    ], dtype=float)
    failure_codes: list[str] = []
    if len(samples) < minimum_samples or len(np.unique(labels)) < 2:
        failure_codes.append("INSUFFICIENT_DATA")
        holdout_metrics = {
            "passed": False,
            "sampleCount": len(samples),
            "failureCodes": failure_codes,
            "checks": [],
        }
    else:
        predictions = [predict_ensemble(artifact, sample.features) for sample in samples]
        probabilities = np.clip(np.asarray([
            prediction.probability_positive_excess
            for prediction in predictions
        ], dtype=float), 1e-6, 1 - 1e-6)
        expected = np.asarray([
            prediction.expected_excess_return
            for prediction in predictions
        ], dtype=float)
        baseline_probability = float(artifact.metrics["positiveLabelRate"])
        baseline_probabilities = np.full(len(samples), baseline_probability)
        baseline_brier = brier_score_loss(labels, baseline_probabilities)
        brier_skill = (
            0.0
            if baseline_brier <= 1e-18
            else 1 - brier_score_loss(labels, probabilities) / baseline_brier
        )
        baseline_log_loss = log_loss(
            labels,
            baseline_probabilities,
            labels=[0, 1],
        )
        log_loss_skill = (
            0.0
            if baseline_log_loss <= 1e-18
            else 1 - log_loss(labels, probabilities, labels=[0, 1])
            / baseline_log_loss
        )
        positions = _non_overlapping_positions(
            samples,
            np.arange(len(samples), dtype=int),
        )
        tradable_actions = {"BUY", "BUY_WATCH", "ADD"}
        strategy_returns = np.asarray([
            actual[position]
            if predictions[position].action in tradable_actions
            else 0.0
            for position in positions
        ], dtype=float)
        interval_coverage = float(np.mean([
            prediction.prediction_interval[0]
            <= actual[index]
            <= prediction.prediction_interval[1]
            for index, prediction in enumerate(predictions)
        ]))
        actual_metrics = {
            "oosR2": _oos_r2(actual, expected),
            "brierSkill": float(brier_skill),
            "logLossSkill": float(log_loss_skill),
            "netReturn": _compounded_return(strategy_returns),
            "intervalCoverage": interval_coverage,
        }
        diagnostic_specifications = (
            (
                "oosR2",
                ">",
                config.number(
                    "autoSearch.finalHoldoutValidation.minimumOosR2"
                ),
            ),
            (
                "brierSkill",
                ">",
                config.number(
                    "autoSearch.finalHoldoutValidation.minimumBrierSkill"
                ),
            ),
            (
                "logLossSkill",
                ">",
                config.number(
                    "autoSearch.finalHoldoutValidation.minimumLogLossSkill"
                ),
            ),
            (
                "netReturn",
                ">",
                config.number(
                    "autoSearch.finalHoldoutValidation.minimumNetReturn"
                ),
            ),
            (
                "intervalCoverage",
                ">=",
                config.number(
                    "autoSearch.finalHoldoutValidation.minimumIntervalCoverage"
                ),
            ),
            (
                "intervalCoverage",
                "<=",
                config.number(
                    "autoSearch.finalHoldoutValidation.maximumIntervalCoverage"
                ),
            ),
        )
        checks = [
            {
                "key": key,
                "actual": round(actual_metrics[key], 10),
                "operator": operator,
                "threshold": threshold,
                "passed": _compare_metric(
                    actual_metrics[key],
                    operator,
                    threshold,
                ),
                "required": False,
            }
            for key, operator, threshold in diagnostic_specifications
        ]
        diagnostics_passed = all(check["passed"] for check in checks)
        elapsed_days = _elapsed_days(samples, positions)
        annualization = config.integer("annualizationDays")
        annualized_net_return = _annualized_return(
            strategy_returns,
            elapsed_days,
            annualization,
        )
        buy_and_hold_returns = actual[positions]
        buy_and_hold_annualized = _annualized_return(
            buy_and_hold_returns,
            elapsed_days,
            annualization,
        )
        official_returns = np.asarray([
            (
                (samples[int(position)].net_return
                 if samples[int(position)].net_return is not None
                 else samples[int(position)].net_excess_return)
                - samples[int(position)].net_excess_return
            )
            for position in positions
        ], dtype=float)
        official_annualized = (
            _annualized_return(official_returns, elapsed_days, annualization)
            if benchmark_available else 0.0
        )
        cash_annualized = config.number("baselines.cashAnnualizedReturn")
        baseline_values = {
            "BUY_AND_HOLD": buy_and_hold_annualized,
            "CASH": cash_annualized,
        }
        if benchmark_available:
            baseline_values["OFFICIAL_BENCHMARK"] = official_annualized
        strongest_name, strongest_return = max(
            baseline_values.items(),
            key=lambda item: item[1],
        )
        strategy_drawdown = _maximum_drawdown(strategy_returns)
        buy_and_hold_drawdown = _maximum_drawdown(buy_and_hold_returns)
        drawdown_reduction = (
            0.0
            if abs(buy_and_hold_drawdown) <= 1e-12
            else 1.0 - abs(strategy_drawdown) / abs(buy_and_hold_drawdown)
        )
        negative_market = buy_and_hold_returns < 0
        market_downside = abs(float(np.sum(
            buy_and_hold_returns[negative_market]
        )))
        strategy_downside = abs(float(np.sum(np.minimum(
            strategy_returns[negative_market],
            0.0,
        ))))
        downside_capture = (
            0.0
            if market_downside <= 1e-12
            else strategy_downside / market_downside
        )
        role = str(
            artifact.metrics.get("economicRole")
            or artifact.metrics.get("validationReport", {}).get("economicRole")
            or "RISK_REFERENCE"
        )
        net_excess = annualized_net_return - strongest_return
        stressed_net_return = annualized_net_return - config.number(
            "promotion.costStressAnnualizedPenalty"
        )
        if role == "RETURN_ENHANCER":
            economic_specifications = (
                ("netExcessVsStrongestBaseline", net_excess, ">", 0.0),
                (
                    "costStressNetExcessVsStrongestBaseline",
                    stressed_net_return - strongest_return,
                    ">=",
                    0.0,
                ),
            )
        elif role == "DRAWDOWN_GUARD":
            economic_specifications = (
                ("drawdownReduction", drawdown_reduction, ">=", 0.2),
                ("downsideCapture", downside_capture, "<=", 0.8),
                (
                    "annualizedNetReturn",
                    annualized_net_return,
                    ">=",
                    cash_annualized,
                ),
            )
        else:
            economic_specifications = (
                ("economicRole", 0.0, ">", 0.0),
            )
        economic_checks = [
            {
                "key": key,
                "actual": round(value, 10),
                "operator": operator,
                "threshold": threshold,
                "passed": _compare_metric(value, operator, threshold),
                "required": True,
            }
            for key, value, operator, threshold in economic_specifications
        ]
        checks.extend(economic_checks)
        economic_passed = all(check["passed"] for check in economic_checks)
        if not data_fresh:
            failure_codes.append("DATA_STALE")
        if not economic_passed:
            failure_codes.append("MODEL_REJECTED")
        holdout_metrics = {
            "passed": not failure_codes,
            "diagnosticsPassed": diagnostics_passed,
            "sampleCount": len(samples),
            "independentEventCount": len(positions),
            "dataStart": samples[0].as_of_date,
            "dataEnd": samples[-1].as_of_date,
            "algorithm": algorithm,
            "failureCodes": failure_codes,
            "checks": checks,
            "economicRole": role,
            "annualizedNetReturn": round(annualized_net_return, 10),
            "buyAndHoldAnnualizedReturn": round(
                buy_and_hold_annualized,
                10,
            ),
            "officialBenchmarkAnnualizedReturn": (
                round(official_annualized, 10)
                if benchmark_available else None
            ),
            "cashAnnualizedReturn": round(cash_annualized, 10),
            "strongestBaseline": strongest_name,
            "strongestBaselineAnnualizedReturn": round(
                strongest_return,
                10,
            ),
            "netExcessVsStrongestBaseline": round(net_excess, 10),
            "drawdownReduction": round(drawdown_reduction, 10),
            "downsideCapture": round(downside_capture, 10),
            **{
                key: round(value, 10)
                for key, value in actual_metrics.items()
            },
        }
    artifact.metrics = artifact.metrics | {"finalHoldout": holdout_metrics}
    validation_report = dict(artifact.metrics.get("validationReport") or {})
    combined_failures = list(dict.fromkeys([
        *validation_report.get("failureCodes", []),
        *failure_codes,
    ]))
    passed = bool(validation_report.get("passed")) and not combined_failures
    validation_report.update({
        "passed": passed,
        "lifecycle": "VALIDATED" if passed else "DRAFT",
        "failureCodes": combined_failures,
    })
    artifact.metrics["validationReport"] = validation_report
    artifact.status = "VALIDATED" if passed else "DRAFT"
    artifact.model_version = hashlib.sha256((
        artifact.model_version
        + json.dumps(
            holdout_metrics,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
    ).encode("utf-8")).hexdigest()
    return artifact


def attach_event_backtest(artifact: ModelArtifact,
                          result: Any,
                          config: QuantConfig) -> ModelArtifact:
    event_metrics = {
        "executionModel": "A_SHARE_EVENT_V1",
        "eventTotalReturn": round(float(result.total_return), 10),
        "eventAnnualizedReturn": round(float(result.annualized_return), 10),
        "eventAnnualizedVolatility": round(float(result.annualized_volatility), 10),
        "eventSharpe": round(float(result.sharpe), 10),
        "eventSortino": round(float(result.sortino), 10),
        "eventCalmar": round(float(result.calmar), 10),
        "eventMaximumDrawdown": round(float(result.maximum_drawdown), 10),
        "eventTurnover": round(float(result.turnover), 10),
        "eventTrades": float(result.trades),
        "eventRejectedOrders": float(result.rejected_orders),
        "eventPartialFills": float(result.partial_fills),
    }
    artifact.metrics = artifact.metrics | event_metrics
    artifact.status = "VALIDATED" if _passes_promotion(artifact.metrics, config) else "DRAFT"
    artifact.model_version = hashlib.sha256((
        artifact.model_version
        + json.dumps(event_metrics, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    ).encode("utf-8")).hexdigest()
    return artifact


def predict_ensemble(artifact: ModelArtifact, features: dict[str, float]) -> QuantPrediction:
    if tuple(sorted(features)) != artifact.feature_names:
        raise ValueError("prediction features do not match the trained feature schema")
    row = np.asarray([[features[name] for name in artifact.feature_names]], dtype=float)
    raw_probability, raw_expected = _raw_predict((
        artifact.linear_classifier, artifact.linear_regressor,
        artifact.tree_classifier, artifact.tree_regressor,
    ), row, artifact.ensemble_linear_weight,
        str(artifact.metrics.get("algorithm") or ""),
        artifact.feature_names)
    probability = float(np.clip(artifact.calibrator.predict(raw_probability)[0], 0, 1))
    expected = float(raw_expected[0])
    quantile_lower = float(artifact.quantile_lower_regressor.predict(row)[0])
    quantile_upper = float(artifact.quantile_upper_regressor.predict(row)[0])
    lower = min(quantile_lower, expected)
    upper = max(quantile_upper, expected)
    action = _action(artifact, probability, expected)
    width = max(0.0, upper - lower)
    confidence = _prediction_confidence(
        width,
        expected,
        artifact.confidence_thresholds["highRatio"],
        artifact.confidence_thresholds["mediumRatio"],
        artifact.confidence_thresholds["minimumMagnitude"],
    )
    coefficients = artifact.linear_classifier.named_steps["model"].coef_[0]
    contributions = coefficients * artifact.linear_classifier.named_steps["scale"].transform(row)[0]
    ranked = sorted(zip(artifact.feature_names, contributions), key=lambda item: abs(item[1]), reverse=True)
    return QuantPrediction(
        probability_positive_excess=probability,
        expected_excess_return=expected,
        prediction_interval=(lower, upper),
        action=action,
        confidence=confidence,
        top_factors=tuple({"name": name, "contribution": float(value)} for name, value in ranked[:artifact.top_factor_count]),
    )


def _fit_models(
    x: np.ndarray,
    y_class: np.ndarray,
    y_return: np.ndarray,
    config: QuantConfig,
    *,
    include_quantiles: bool = True,
    algorithm: str = "REGIME_ENSEMBLE",
    feature_names: Sequence[str] = (),
):
    algorithm = _canonical_algorithm(algorithm)
    seed = config.integer("randomSeed")
    max_iter = config.integer("training.elasticNet.maximumIterations")
    needs_linear = include_quantiles or algorithm in {
        "ELASTIC_NET",
        "REGIME_ENSEMBLE",
    }
    linear_classifier = (
        Pipeline([
            ("scale", StandardScaler()),
            ("model", LogisticRegression(
                penalty="elasticnet", solver="saga",
                C=config.number("training.elasticNet.classificationC"),
                l1_ratio=config.number("training.elasticNet.l1Ratio"),
                max_iter=max_iter, random_state=seed,
            )),
        ])
        if needs_linear else None
    )
    linear_regressor = (
        Pipeline([
            ("scale", StandardScaler()),
            ("model", ElasticNet(
                alpha=config.number("training.elasticNet.regressionAlpha"),
                l1_ratio=config.number("training.elasticNet.l1Ratio"),
                max_iter=max_iter, random_state=seed,
            )),
        ])
        if needs_linear else None
    )
    xgboost_common = {
        "n_estimators": config.integer("training.xgboost.estimators"),
        "max_depth": config.integer("training.xgboost.maximumDepth"),
        "learning_rate": config.number("training.xgboost.learningRate"),
        "subsample": config.number("training.xgboost.subsample"),
        "colsample_bytree": config.number("training.xgboost.columnSample"),
        "reg_alpha": config.number("training.xgboost.l1Regularization"),
        "reg_lambda": config.number("training.xgboost.l2Regularization"),
        "random_state": seed,
        "n_jobs": 1,
        "tree_method": "hist",
    }
    if algorithm == "ELASTIC_NET":
        tree_classifier = None
        tree_regressor = None
    elif algorithm == "EXTRA_TREES":
        tree_classifier = ExtraTreesClassifier(
            n_estimators=config.integer("training.extraTrees.estimators"),
            max_depth=config.integer("training.extraTrees.maximumDepth"),
            min_samples_leaf=config.integer("training.extraTrees.minimumLeaf"),
            max_features=config.number("training.extraTrees.maximumFeatures"),
            class_weight="balanced",
            random_state=seed,
            n_jobs=1,
        )
        tree_regressor = ExtraTreesRegressor(
            n_estimators=config.integer("training.extraTrees.estimators"),
            max_depth=config.integer("training.extraTrees.maximumDepth"),
            min_samples_leaf=config.integer("training.extraTrees.minimumLeaf"),
            max_features=config.number("training.extraTrees.maximumFeatures"),
            random_state=seed,
            n_jobs=1,
        )
    elif algorithm in {"TREND_VOLATILITY", "RISK_FILTERED_MEAN_REVERSION"}:
        if not feature_names:
            raise ValueError("rule strategy requires feature names")
        tree_classifier = RuleStrategyClassifier(
            algorithm,
            feature_names,
            config,
        )
        tree_regressor = RuleStrategyRegressor(
            algorithm,
            feature_names,
            config,
        )
    else:
        tree_classifier = XGBClassifier(
            objective="binary:logistic",
            eval_metric="logloss",
            **xgboost_common,
        )
        tree_regressor = XGBRegressor(
            objective="reg:squarederror",
            eval_metric="rmse",
            **xgboost_common,
        )
        if algorithm == "REGIME_ENSEMBLE":
            regime_settings = {
                "baseLinearWeight": config.number(
                    "prediction.ensemble.linearWeight"
                ),
                "highVolatility": config.number(
                    "strategy.regimeEnsemble.highVolatility"
                ),
                "window": config.integer("strategy.regimeEnsemble.window"),
            }
            tree_classifier.quant_regime_settings = regime_settings
            tree_regressor.quant_regime_settings = regime_settings
    core_models = tuple(
        (model, target)
        for model, target in (
            (linear_classifier, y_class),
            (linear_regressor, y_return),
            (tree_classifier, y_class),
            (tree_regressor, y_return),
        )
        if model is not None
    )
    for model, target in core_models:
        model.fit(x, target)
    if not include_quantiles:
        return linear_classifier, linear_regressor, tree_classifier, tree_regressor

    quantile_lower = XGBRegressor(
        objective="reg:quantileerror", quantile_alpha=config.number("prediction.residualLowerQuantile"),
        eval_metric="quantile", **xgboost_common)
    quantile_upper = XGBRegressor(
        objective="reg:quantileerror", quantile_alpha=config.number("prediction.residualUpperQuantile"),
        eval_metric="quantile", **xgboost_common)
    quantile_lower.fit(x, y_return)
    quantile_upper.fit(x, y_return)
    return (linear_classifier, linear_regressor, tree_classifier, tree_regressor,
            quantile_lower, quantile_upper)


def _raw_predict(
    models,
    x: np.ndarray,
    linear_weight: float,
    algorithm: str = "",
    feature_names: Sequence[str] = (),
) -> tuple[np.ndarray, np.ndarray]:
    if linear_weight != -1.0 and not 0 <= linear_weight <= 1:
        raise ValueError("prediction.ensemble.linearWeight must be between zero and one")
    linear_classifier, linear_regressor, tree_classifier, tree_regressor = models[:4]
    if linear_weight == 1.0:
        if linear_classifier is None or linear_regressor is None:
            raise ValueError("linear quant models are not fitted")
        return (
            linear_classifier.predict_proba(x)[:, 1],
            linear_regressor.predict(x),
        )
    if linear_weight == 0.0:
        if tree_classifier is None or tree_regressor is None:
            raise ValueError("tree or rule quant models are not fitted")
        return (
            tree_classifier.predict_proba(x)[:, 1],
            tree_regressor.predict(x),
        )
    if (
        linear_classifier is None
        or linear_regressor is None
        or tree_classifier is None
        or tree_regressor is None
    ):
        raise ValueError("ensemble quant models are not fitted")
    if linear_weight == -1.0:
        settings = getattr(
            tree_classifier,
            "quant_regime_settings",
            {},
        )
        linear_weights = _regime_linear_weights(
            np.asarray(x, dtype=float),
            feature_names,
            base_weight=float(settings.get("baseLinearWeight", 0.5)),
            high_volatility=float(settings.get("highVolatility", 0.35)),
            regime_window=int(settings.get("window", 120)),
        )
    else:
        linear_weights = np.full(len(x), linear_weight, dtype=float)
    tree_weights = 1.0 - linear_weights
    probability = (
        linear_classifier.predict_proba(x)[:, 1] * linear_weights
        + tree_classifier.predict_proba(x)[:, 1] * tree_weights
    )
    expected = (
        linear_regressor.predict(x) * linear_weights
        + tree_regressor.predict(x) * tree_weights
    )
    return probability, expected


def _canonical_algorithm(algorithm: str) -> str:
    normalized = str(algorithm).strip().upper()
    return {
        "GRADIENT_BOOSTING": "XGBOOST",
        "VALIDATED_ENSEMBLE": "REGIME_ENSEMBLE",
    }.get(normalized, normalized)


def _rule_parameters(
    algorithm: str,
    config: QuantConfig,
) -> dict[str, float]:
    if algorithm == "TREND_VOLATILITY":
        return {
            "fastWindow": float(config.integer(
                "strategy.trendVolatility.fastWindow"
            )),
            "slowWindow": float(config.integer(
                "strategy.trendVolatility.slowWindow"
            )),
            "volatilityWindow": float(config.integer(
                "strategy.trendVolatility.volatilityWindow"
            )),
            "targetVolatility": config.number(
                "risk.targetAnnualizedVolatility"
            ),
        }
    if algorithm == "RISK_FILTERED_MEAN_REVERSION":
        return {
            "window": float(config.integer("strategy.meanReversion.window")),
            "entryZ": config.number("strategy.meanReversion.entryZ"),
            "exitZ": config.number("strategy.meanReversion.exitZ"),
            "targetVolatility": config.number(
                "risk.targetAnnualizedVolatility"
            ),
        }
    raise ValueError(f"unsupported rule strategy: {algorithm}")


def _rule_signal(
    values: np.ndarray,
    feature_names: Sequence[str],
    algorithm: str,
    parameters: Mapping[str, float],
) -> np.ndarray:
    short = _feature(values, feature_names, "momentum_short")
    primary = _feature(values, feature_names, "momentum_primary")
    long = _feature(values, feature_names, "momentum_long")
    volatility = np.maximum(
        _feature(values, feature_names, "realized_volatility"),
        0.01,
    )
    if algorithm == "TREND_VOLATILITY":
        fast = parameters["fastWindow"]
        slow = max(parameters["slowWindow"], fast + 1.0)
        volatility_window = parameters["volatilityWindow"]
        fast_weight = np.clip(1.0 - fast / slow, 0.2, 0.8)
        momentum = (
            fast_weight * short
            + (1.0 - fast_weight) * long
            + 0.5 * primary
        )
        trend = _feature(values, feature_names, "trend_slope") * slow
        volatility_scale = (
            parameters["targetVolatility"] / volatility
            * np.sqrt(np.clip(volatility_window, 10.0, 252.0) / 252.0)
        )
        return np.clip(
            (8.0 * momentum + 12.0 * trend)
            * np.clip(volatility_scale, 0.2, 1.5),
            -12.0,
            12.0,
        )
    if algorithm == "RISK_FILTERED_MEAN_REVERSION":
        window = parameters["window"]
        horizon_blend = np.clip((window - 10.0) / 242.0, 0.0, 1.0)
        momentum = (
            (1.0 - horizon_blend) * short
            + horizon_blend * long
            + 0.5 * primary
        )
        scale = volatility * np.sqrt(max(window, 10.0) / 252.0)
        z_score = -momentum / np.maximum(scale, 1e-6)
        entry = max(parameters["entryZ"], parameters["exitZ"] + 0.05)
        active = np.sign(z_score) * np.maximum(
            np.abs(z_score) - parameters["exitZ"],
            0.0,
        ) / (entry - parameters["exitZ"])
        risk_filter = np.clip(
            parameters["targetVolatility"] / volatility,
            0.0,
            1.0,
        )
        return np.clip(active * risk_filter * 4.0, -12.0, 12.0)
    raise ValueError(f"unsupported rule strategy: {algorithm}")


def _feature(
    values: np.ndarray,
    feature_names: Sequence[str],
    name: str,
) -> np.ndarray:
    try:
        position = tuple(feature_names).index(name)
    except ValueError:
        return np.zeros(len(values), dtype=float)
    return np.asarray(values[:, position], dtype=float)


def _regime_linear_weights(
    values: np.ndarray,
    feature_names: Sequence[str],
    *,
    base_weight: float,
    high_volatility: float,
    regime_window: int,
) -> np.ndarray:
    base = np.clip(base_weight, 0.1, 0.9)
    volatility = _feature(
        values,
        feature_names,
        "realized_volatility",
    )
    trend = _feature(values, feature_names, "trend_slope")
    threshold = 0.001 * np.sqrt(20.0 / max(10, regime_window))
    weights = np.full(len(values), base, dtype=float)
    weights = np.where(trend > threshold, np.maximum(0.1, base - 0.2), weights)
    weights = np.where(trend < -threshold, np.minimum(0.9, base + 0.2), weights)
    weights = np.where(
        volatility >= high_volatility,
        np.minimum(0.9, base + 0.3),
        weights,
    )
    return np.clip(weights, 0.1, 0.9)


def probability_of_backtest_overfitting(
    candidate_fold_returns: Sequence[Sequence[float]],
) -> float:
    matrix = np.asarray(candidate_fold_returns, dtype=float)
    if matrix.ndim != 2 or matrix.shape[0] < 2 or matrix.shape[1] < 4:
        return 1.0
    fold_count = matrix.shape[1]
    train_size = fold_count // 2
    overfit = 0
    evaluated = 0
    all_folds = set(range(fold_count))
    for train_tuple in itertools.combinations(range(fold_count), train_size):
        train_indices = np.asarray(train_tuple, dtype=int)
        test_indices = np.asarray(sorted(all_folds - set(train_tuple)), dtype=int)
        train_performance = np.mean(matrix[:, train_indices], axis=1)
        selected = int(np.argmax(train_performance))
        test_performance = np.mean(matrix[:, test_indices], axis=1)
        selected_rank = int(np.sum(test_performance < test_performance[selected]))
        percentile = (selected_rank + 0.5) / matrix.shape[0]
        overfit += int(percentile <= 0.5)
        evaluated += 1
    return 1.0 if evaluated == 0 else overfit / evaluated


def _drawdown_guard_fold_pass(
    strategy_return: float,
    buy_and_hold_return: float,
    maximum_downside_capture: float,
) -> bool:
    if not 0.0 <= maximum_downside_capture <= 1.0:
        raise ValueError("maximum downside capture must be between zero and one")
    if buy_and_hold_return < 0.0:
        return strategy_return >= buy_and_hold_return * maximum_downside_capture
    return strategy_return >= 0.0


def _prediction_confidence(width: float, expected: float, high_ratio: float,
                           medium_ratio: float, minimum_magnitude: float) -> str:
    magnitude = max(abs(expected), minimum_magnitude)
    if width <= magnitude * high_ratio:
        return "HIGH"
    if width <= magnitude * medium_ratio:
        return "MEDIUM"
    return "LOW"


def _passes_promotion(metrics: dict[str, Any], config: QuantConfig) -> bool:
    report = metrics.get("validationReport")
    return isinstance(report, dict) and report.get("passed") is True


def _action(artifact: ModelArtifact, probability: float, expected: float) -> str:
    if artifact.status != "VALIDATED":
        return "NO_TRADE"
    thresholds = artifact.action_thresholds
    if probability <= thresholds["exit"]:
        return "EXIT"
    if probability <= thresholds["reduce"]:
        return "REDUCE"
    if expected < thresholds["minimumExpected"]:
        return "HOLD"
    if probability >= thresholds["add"]:
        return "ADD"
    if probability >= thresholds["buy"]:
        return "BUY_WATCH"
    return "HOLD"


def _compare_metric(actual: float, operator: str, threshold: float) -> bool:
    if operator == ">":
        return actual > threshold
    if operator == ">=":
        return actual >= threshold
    if operator == "<=":
        return actual <= threshold
    raise ValueError(f"unsupported final holdout operator: {operator}")


def _maximum_drawdown(returns: np.ndarray) -> float:
    equity = np.cumprod(1 + returns)
    peaks = np.maximum.accumulate(equity)
    return float(np.min(equity / peaks - 1)) if len(equity) else 0.0


def _chronological_calibration_split(samples: Sequence[TrainingSample],
                                     indices: np.ndarray, fraction: float,
                                     minimum_calibration: int,
                                     minimum_evaluation: int,
                                     embargo_multiplier: float) -> tuple[np.ndarray, np.ndarray]:
    if not 0 < fraction < 1:
        raise ValueError("training.calibrationFraction must be between zero and one")
    if embargo_multiplier < 1:
        raise ValueError("training.embargoHorizonMultiplier must be at least one")
    calibration_size = max(minimum_calibration, int(round(len(indices) * fraction)))
    if calibration_size >= len(indices):
        return np.asarray([], dtype=int), np.asarray([], dtype=int)
    calibration = indices[:calibration_size]
    last_sample = samples[int(calibration[-1])]
    horizon = last_sample.label_end_index - last_sample.as_of_index
    unique_dates = _series_dates_for_indices(samples, indices)
    date_positions = {value: index for index, value in enumerate(unique_dates)}
    evaluation_cutoff = date_positions[last_sample.as_of_date] + int(
        np.ceil(horizon * embargo_multiplier)
    )
    evaluation_start = calibration_size
    while (evaluation_start < len(indices)
           and date_positions[samples[int(indices[evaluation_start])].as_of_date]
           < evaluation_cutoff):
        evaluation_start += 1
    evaluation = indices[evaluation_start:]
    if len(evaluation) < minimum_evaluation:
        return np.asarray([], dtype=int), np.asarray([], dtype=int)
    return calibration, evaluation


def _non_overlapping_positions(samples: Sequence[TrainingSample], indices: np.ndarray) -> np.ndarray:
    selected: list[int] = []
    next_available_by_series: dict[str, int] = {}
    for position, sample_index in enumerate(indices):
        sample = samples[int(sample_index)]
        next_available = next_available_by_series.get(sample.series_id, -1)
        if sample.as_of_index < next_available:
            continue
        selected.append(position)
        next_available_by_series[sample.series_id] = sample.label_end_index
    return np.asarray(selected, dtype=int)


def _validation_series_indices(
    samples: Sequence[TrainingSample],
    indices: np.ndarray,
    series_id: str,
) -> np.ndarray:
    return np.asarray([
        int(index)
        for index in indices
        if samples[int(index)].series_id == series_id
    ], dtype=int)


def _elapsed_days(samples: Sequence[TrainingSample], indices: np.ndarray) -> int:
    if not len(indices):
        return 0
    first = samples[int(indices[0])]
    last = samples[int(indices[-1])]
    unique_dates = _series_dates_for_indices(samples, indices)
    date_positions = {value: index for index, value in enumerate(unique_dates)}
    horizon = last.label_end_index - last.as_of_index
    return max(
        1,
        date_positions[last.as_of_date] - date_positions[first.as_of_date] + horizon,
    )


def _series_dates_for_indices(
    samples: Sequence[TrainingSample],
    indices: np.ndarray,
) -> list[str]:
    series_ids = {
        samples[int(index)].series_id
        for index in indices
    }
    if len(series_ids) == 1:
        series_id = next(iter(series_ids))
        return sorted({
            sample.as_of_date
            for sample in samples
            if sample.series_id == series_id
        })
    return sorted({sample.as_of_date for sample in samples})


def _date_walk_forward_splits(
    samples: Sequence[TrainingSample],
    *,
    folds: int,
    embargo_dates: int,
    validation_series_id: str | None = None,
) -> list[tuple[np.ndarray, np.ndarray]]:
    if folds < 2:
        raise ValueError("walk-forward validation requires at least two folds")
    validation_dates = sorted({
        sample.as_of_date
        for sample in samples
        if (
            validation_series_id is None
            or sample.series_id == validation_series_id
        )
    })
    if len(validation_dates) <= folds:
        return []
    test_size = max(1, len(validation_dates) // (folds + 1))
    first_test = len(validation_dates) - folds * test_size
    result: list[tuple[np.ndarray, np.ndarray]] = []
    for fold in range(folds):
        test_start = first_test + fold * test_size
        test_end = (
            len(validation_dates)
            if fold == folds - 1
            else test_start + test_size
        )
        train_end = max(0, test_start - max(0, embargo_dates))
        if train_end >= len(validation_dates):
            continue
        train_cutoff = validation_dates[train_end]
        test_dates = set(validation_dates[test_start:test_end])
        train_indices = np.asarray([
            index
            for index, sample in enumerate(samples)
            if sample.as_of_date < train_cutoff
        ], dtype=int)
        test_indices = np.asarray([
            index
            for index, sample in enumerate(samples)
            if sample.as_of_date in test_dates
            and (
                validation_series_id is None
                or sample.series_id == validation_series_id
            )
        ], dtype=int)
        if len(train_indices) and len(test_indices):
            result.append((train_indices, test_indices))
    return result


def _compounded_return(returns: np.ndarray) -> float:
    return float(np.prod(1 + returns) - 1) if len(returns) else 0.0


def _annualized_return(returns: np.ndarray, elapsed_days: int, annualization: int) -> float:
    if not len(returns) or elapsed_days <= 0:
        return 0.0
    equity = max(float(np.prod(1 + returns)), 1e-12)
    return equity ** (annualization / elapsed_days) - 1


def _new_calibrator(method: str) -> Any:
    if method == "sigmoid":
        return SigmoidCalibrator()
    if method == "isotonic":
        return IsotonicRegression(out_of_bounds="clip", y_min=0.0, y_max=1.0)
    raise ValueError(f"unsupported probability calibration method: {method}")


def _require_binary_labels(labels: np.ndarray, partition: str) -> None:
    if len(np.unique(labels)) < 2:
        raise InsufficientQuantData(
            f"{partition} partition requires positive and negative labels"
        )


def _oos_r2(actual: np.ndarray, predicted: np.ndarray) -> float:
    denominator = float(np.sum(np.square(actual)))
    if denominator <= 1e-18:
        return 0.0
    return 1 - float(np.sum(np.square(actual - predicted))) / denominator


def _diebold_mariano_p_value(actual: np.ndarray, predicted: np.ndarray) -> float:
    if len(actual) < 3:
        return 1.0
    differential = np.square(actual) - np.square(actual - predicted)
    mean = float(np.mean(differential))
    variance = float(np.var(differential, ddof=1))
    if variance <= 1e-18:
        return 0.0 if mean > 0 else 1.0
    statistic = mean / np.sqrt(variance / len(differential))
    return float(1 - norm.cdf(statistic))


def _rank_ic(predicted: np.ndarray, actual: np.ndarray) -> float:
    if len(predicted) < 3 or np.allclose(predicted, predicted[0]):
        return 0.0
    correlation = spearmanr(predicted, actual).statistic
    return 0.0 if not np.isfinite(correlation) else float(correlation)


def _calibration_parameters(
    labels: np.ndarray,
    probabilities: np.ndarray,
) -> tuple[float, float]:
    if len(labels) < 3 or len(np.unique(labels)) < 2:
        return 0.0, 0.0
    clipped = np.clip(probabilities, 1e-6, 1 - 1e-6)
    logits = np.log(clipped / (1 - clipped)).reshape(-1, 1)
    model = LogisticRegression(C=1_000_000.0, solver="lbfgs")
    model.fit(logits, labels)
    return float(model.intercept_[0]), float(model.coef_[0][0])


def _pinball_skill(
    calibration_actual: np.ndarray,
    evaluation_actual: np.ndarray,
    lower: np.ndarray,
    upper: np.ndarray,
    lower_quantile: float,
    upper_quantile: float,
) -> float:
    model_loss = (
        _pinball_loss(evaluation_actual, lower, lower_quantile)
        + _pinball_loss(evaluation_actual, upper, upper_quantile)
    )
    baseline_lower = np.full(
        len(evaluation_actual),
        float(np.quantile(calibration_actual, lower_quantile)),
    )
    baseline_upper = np.full(
        len(evaluation_actual),
        float(np.quantile(calibration_actual, upper_quantile)),
    )
    baseline_loss = (
        _pinball_loss(evaluation_actual, baseline_lower, lower_quantile)
        + _pinball_loss(evaluation_actual, baseline_upper, upper_quantile)
    )
    return 0.0 if baseline_loss <= 1e-18 else 1 - model_loss / baseline_loss


def _pinball_loss(actual: np.ndarray, predicted: np.ndarray, quantile: float) -> float:
    error = actual - predicted
    return float(np.mean(np.maximum(quantile * error, (quantile - 1) * error)))


def _deflated_sharpe_probability(
    returns: np.ndarray,
    sharpe: float,
    *,
    benchmark_sharpe: float,
) -> float:
    if len(returns) < 3:
        return 0.0
    centered = returns - np.mean(returns)
    standard_deviation = float(np.std(returns, ddof=1))
    if standard_deviation <= 1e-18:
        return 1.0 if sharpe > benchmark_sharpe else 0.0
    skewness = float(np.mean(np.power(centered / standard_deviation, 3)))
    kurtosis = float(np.mean(np.power(centered / standard_deviation, 4)))
    denominator = max(
        1e-12,
        1 - skewness * sharpe + ((kurtosis - 1) / 4) * sharpe * sharpe,
    )
    statistic = (
        (sharpe - benchmark_sharpe)
        * np.sqrt(len(returns) - 1)
        / np.sqrt(denominator)
    )
    return float(norm.cdf(statistic))
