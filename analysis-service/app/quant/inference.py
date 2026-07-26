from __future__ import annotations

from typing import Any, Callable, Mapping

from .model_store import ImmutableModelStore


class SavedModelInferenceService:
    def __init__(
        self,
        store: ImmutableModelStore,
        *,
        predictor: Callable[[Any, dict[str, float]], Any] | None = None,
    ):
        if predictor is None:
            from .models import predict_ensemble

            predictor = predict_ensemble
        self.store = store
        self.predictor = predictor

    def predict(
        self,
        model_version: str,
        *,
        model_hash: str,
        config_version: str,
        features: Mapping[str, float],
    ) -> Any:
        normalized_features = {
            str(name): float(value)
            for name, value in features.items()
        }
        artifact = self.store.load(
            model_version,
            expected_hash=model_hash,
            expected_config_version=config_version,
            expected_feature_names=tuple(sorted(normalized_features)),
        )
        return self.predictor(artifact, normalized_features)
