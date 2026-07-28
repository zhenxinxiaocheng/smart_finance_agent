from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock

from app.quant.inference import SavedModelInferenceService
from app.quant.model_store import ImmutableModelStore


class SavedModelInferenceServiceTest(unittest.TestCase):
    def test_predict_loads_saved_model_and_rejects_feature_schema_drift(self):
        version = "c" * 64
        artifact = SimpleNamespace(
            model_version=version,
            config_version="quant-research-v2",
            feature_names=("momentum", "volatility"),
            status="VALIDATED",
        )
        test_root = Path(__file__).resolve().parents[1] / ".test-tmp"
        test_root.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=test_root) as root:
            store = ImmutableModelStore(Path(root))
            digest = store.save(artifact)
            service = SavedModelInferenceService(
                store,
                predictor=lambda model, features: {
                    "modelVersion": model.model_version,
                    "score": features["momentum"],
                },
            )

            result = service.predict(
                version,
                model_hash=digest,
                config_version="quant-research-v2",
                features={"momentum": 0.2, "volatility": 0.1},
            )

            self.assertEqual(version, result["modelVersion"])
            self.assertEqual(0.2, result["score"])
            with self.assertRaisesRegex(ValueError, "feature schema"):
                service.predict(
                    version,
                    model_hash=digest,
                    config_version="quant-research-v2",
                    features={"momentum": 0.2},
                )

    def test_predict_rejects_draft_model_even_when_integrity_checks_pass(self):
        version = "d" * 64
        artifact = SimpleNamespace(
            model_version=version,
            config_version="quant-research-v2",
            feature_names=("momentum",),
            status="DRAFT",
        )
        store = Mock()
        store.load.return_value = artifact
        service = SavedModelInferenceService(
            store,
            predictor=lambda model, features: features["momentum"],
        )

        with self.assertRaisesRegex(ValueError, "not deployable"):
            service.predict(
                version,
                model_hash="e" * 64,
                config_version="quant-research-v2",
                features={"momentum": 0.2},
            )


if __name__ == "__main__":
    unittest.main()
