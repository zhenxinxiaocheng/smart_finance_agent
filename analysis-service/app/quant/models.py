from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from typing import Any, Sequence

import numpy as np
from sklearn.isotonic import IsotonicRegression
from sklearn.linear_model import ElasticNet, LogisticRegression
from sklearn.metrics import brier_score_loss
from sklearn.model_selection import TimeSeriesSplit
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler
from xgboost import XGBClassifier, XGBRegressor

from .config import QuantConfig
from .engine import TrainingSample


@dataclass
class ModelArtifact:
    model_version: str
    config_version: str
    feature_names: tuple[str, ...]
    status: str
    metrics: dict[str, float]
    linear_classifier: Any
    linear_regressor: Any
    tree_classifier: Any
    tree_regressor: Any
    quantile_lower_regressor: Any
    quantile_upper_regressor: Any
    calibrator: Any
    residual_interval: tuple[float, float]
    action_thresholds: dict[str, float]
    top_factor_count: int


@dataclass(frozen=True)
class QuantPrediction:
    probability_positive_excess: float
    expected_excess_return: float
    prediction_interval: tuple[float, float]
    action: str
    confidence: str
    top_factors: tuple[dict[str, float], ...]


def train_ensemble(samples: Sequence[TrainingSample], config: QuantConfig) -> ModelArtifact:
    minimum = config.integer("training.minimumSamples")
    if len(samples) < minimum:
        raise ValueError(f"quant training requires at least {minimum} samples")
    feature_names = tuple(sorted(samples[0].features))
    if any(tuple(sorted(sample.features)) != feature_names for sample in samples):
        raise ValueError("all training samples must share one feature schema")
    x = np.asarray([[sample.features[name] for name in feature_names] for sample in samples], dtype=float)
    y_class = np.asarray([int(sample.positive_excess) for sample in samples], dtype=int)
    y_return = np.asarray([sample.net_excess_return for sample in samples], dtype=float)
    if len(np.unique(y_class)) < 2:
        raise ValueError("quant training requires positive and negative excess-return labels")

    folds = min(config.integer("training.walkForwardFolds"), max(2, len(samples) // minimum))
    gap = max(0, int(round(samples[0].label_end_index - samples[0].as_of_index)
                     * config.number("training.embargoHorizonMultiplier")))
    splitter = TimeSeriesSplit(n_splits=folds, gap=gap)
    probabilities = np.full(len(samples), np.nan)
    expected = np.full(len(samples), np.nan)
    fold_passes = 0
    fold_count = 0
    for train_indices, test_indices in splitter.split(x):
        if len(np.unique(y_class[train_indices])) < 2:
            continue
        models = _fit_models(x[train_indices], y_class[train_indices], y_return[train_indices], config)
        raw_probability, raw_expected = _raw_predict(models, x[test_indices])
        probabilities[test_indices] = raw_probability
        expected[test_indices] = raw_expected
        realized = y_return[test_indices]
        fold_passes += int(float(np.mean(realized[raw_probability >= 0.5])) > 0) if np.any(raw_probability >= 0.5) else 0
        fold_count += 1

    validation_mask = ~np.isnan(probabilities)
    if int(validation_mask.sum()) < max(20, folds * 5):
        raise ValueError("walk-forward validation produced insufficient out-of-sample predictions")
    calibrator = IsotonicRegression(out_of_bounds="clip", y_min=0.0, y_max=1.0)
    calibrator.fit(probabilities[validation_mask], y_class[validation_mask])
    calibrated = calibrator.predict(probabilities[validation_mask])
    base_probability = float(np.mean(y_class[validation_mask]))
    model_brier = brier_score_loss(y_class[validation_mask], calibrated)
    baseline_brier = brier_score_loss(y_class[validation_mask], np.full(validation_mask.sum(), base_probability))
    brier_skill = 0.0 if baseline_brier == 0 else 1 - model_brier / baseline_brier
    strategy_returns = np.where(calibrated >= 0.5, y_return[validation_mask], 0.0)
    annualization = config.integer("annualizationDays")
    mean_return = float(np.mean(strategy_returns))
    volatility = float(np.std(strategy_returns, ddof=1)) if len(strategy_returns) > 1 else 0.0
    sharpe = 0.0 if volatility == 0 else mean_return / volatility * np.sqrt(annualization)
    annualized_excess = mean_return * annualization
    maximum_drawdown = _maximum_drawdown(strategy_returns)
    fold_pass_ratio = fold_passes / fold_count if fold_count else 0.0
    metrics = {
        "annualizedExcessReturn": round(annualized_excess, 10),
        "sharpe": round(float(sharpe), 10),
        "maximumDrawdown": round(maximum_drawdown, 10),
        "brierSkill": round(float(brier_skill), 10),
        "foldPassRatio": round(fold_pass_ratio, 10),
        "walkForwardFolds": float(fold_count),
        "sampleCount": float(len(samples)),
        "policyMinimumPaperTradingDays": float(config.integer("promotion.minimumPaperTradingDays")),
        "policyMaximumBrierScore": config.number("monitoring.maximumBrierScore"),
        "policyMinimumRealizedExcessReturn": config.number("monitoring.minimumRealizedExcessReturn"),
        "policyConsecutiveFailuresBeforeRetirement": float(
            config.integer("monitoring.consecutiveFailuresBeforeRetirement")),
    }
    status = "VALIDATED" if _passes_promotion(metrics, config) else "DRAFT"
    final_models = _fit_models(x, y_class, y_return, config)
    final_raw_probability, final_expected = _raw_predict(final_models, x[validation_mask])
    residuals = y_return[validation_mask] - final_expected
    interval = (
        float(np.quantile(residuals, config.number("prediction.residualLowerQuantile"))),
        float(np.quantile(residuals, config.number("prediction.residualUpperQuantile"))),
    )
    version_material = {
        "configVersion": config.version,
        "features": feature_names,
        "dates": [sample.as_of_date for sample in samples],
        "labels": [round(sample.net_excess_return, 12) for sample in samples],
        "metrics": metrics,
    }
    model_version = hashlib.sha256(
        json.dumps(version_material, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()
    return ModelArtifact(
        model_version=model_version,
        config_version=config.version,
        feature_names=feature_names,
        status=status,
        metrics=metrics,
        linear_classifier=final_models[0],
        linear_regressor=final_models[1],
        tree_classifier=final_models[2],
        tree_regressor=final_models[3],
        quantile_lower_regressor=final_models[4],
        quantile_upper_regressor=final_models[5],
        calibrator=calibrator,
        residual_interval=interval,
        action_thresholds={
            "buy": config.number("prediction.buyWatchProbability"),
            "add": config.number("prediction.addProbability"),
            "reduce": config.number("prediction.reduceProbability"),
            "exit": config.number("prediction.exitProbability"),
            "minimumExpected": config.number("prediction.minimumExpectedExcessReturn"),
        },
        top_factor_count=config.integer("prediction.topFactorCount"),
    )


def predict_ensemble(artifact: ModelArtifact, features: dict[str, float]) -> QuantPrediction:
    if tuple(sorted(features)) != artifact.feature_names:
        raise ValueError("prediction features do not match the trained feature schema")
    row = np.asarray([[features[name] for name in artifact.feature_names]], dtype=float)
    raw_probability, raw_expected = _raw_predict((
        artifact.linear_classifier, artifact.linear_regressor,
        artifact.tree_classifier, artifact.tree_regressor,
    ), row)
    probability = float(np.clip(artifact.calibrator.predict(raw_probability)[0], 0, 1))
    expected = float(raw_expected[0])
    quantile_lower = float(artifact.quantile_lower_regressor.predict(row)[0])
    quantile_upper = float(artifact.quantile_upper_regressor.predict(row)[0])
    lower = min(quantile_lower, expected)
    upper = max(quantile_upper, expected)
    action = _action(artifact, probability, expected)
    width = max(0.0, upper - lower)
    confidence = "HIGH" if width <= abs(expected) else "MEDIUM" if width <= max(abs(expected) * 2, 1e-12) else "LOW"
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


def _fit_models(x: np.ndarray, y_class: np.ndarray, y_return: np.ndarray, config: QuantConfig):
    seed = config.integer("randomSeed")
    max_iter = config.integer("training.elasticNet.maximumIterations")
    linear_classifier = Pipeline([
        ("scale", StandardScaler()),
        ("model", LogisticRegression(
            penalty="elasticnet", solver="saga",
            C=config.number("training.elasticNet.classificationC"),
            l1_ratio=config.number("training.elasticNet.l1Ratio"),
            max_iter=max_iter, random_state=seed,
        )),
    ])
    linear_regressor = Pipeline([
        ("scale", StandardScaler()),
        ("model", ElasticNet(
            alpha=config.number("training.elasticNet.regressionAlpha"),
            l1_ratio=config.number("training.elasticNet.l1Ratio"),
            max_iter=max_iter, random_state=seed,
        )),
    ])
    tree_common = {
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
    tree_classifier = XGBClassifier(objective="binary:logistic", eval_metric="logloss", **tree_common)
    tree_regressor = XGBRegressor(objective="reg:squarederror", eval_metric="rmse", **tree_common)
    quantile_lower = XGBRegressor(
        objective="reg:quantileerror", quantile_alpha=config.number("prediction.residualLowerQuantile"),
        eval_metric="quantile", **tree_common)
    quantile_upper = XGBRegressor(
        objective="reg:quantileerror", quantile_alpha=config.number("prediction.residualUpperQuantile"),
        eval_metric="quantile", **tree_common)
    for model, target in (
        (linear_classifier, y_class), (linear_regressor, y_return),
        (tree_classifier, y_class), (tree_regressor, y_return),
        (quantile_lower, y_return), (quantile_upper, y_return),
    ):
        model.fit(x, target)
    return (linear_classifier, linear_regressor, tree_classifier, tree_regressor,
            quantile_lower, quantile_upper)


def _raw_predict(models, x: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    linear_classifier, linear_regressor, tree_classifier, tree_regressor = models[:4]
    probability = (
        linear_classifier.predict_proba(x)[:, 1] + tree_classifier.predict_proba(x)[:, 1]
    ) / 2
    expected = (linear_regressor.predict(x) + tree_regressor.predict(x)) / 2
    return probability, expected


def _passes_promotion(metrics: dict[str, float], config: QuantConfig) -> bool:
    return (
        metrics["annualizedExcessReturn"] > config.number("promotion.minimumAnnualizedExcessReturn")
        and metrics["sharpe"] > config.number("promotion.minimumSharpe")
        and abs(metrics["maximumDrawdown"]) <= config.number("promotion.maximumDrawdown")
        and metrics["brierSkill"] > config.number("promotion.minimumBrierSkill")
        and metrics["foldPassRatio"] >= config.number("promotion.minimumFoldPassRatio")
    )


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


def _maximum_drawdown(returns: np.ndarray) -> float:
    equity = np.cumprod(1 + returns)
    peaks = np.maximum.accumulate(equity)
    return float(np.min(equity / peaks - 1)) if len(equity) else 0.0
