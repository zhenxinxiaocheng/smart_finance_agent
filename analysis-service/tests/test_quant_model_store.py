from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

from app.quant.model_store import ImmutableModelStore


class ImmutableModelStoreTest(unittest.TestCase):
    def test_saved_model_can_be_reloaded_after_store_restart_with_integrity_checks(self):
        version = "a" * 64
        artifact = SimpleNamespace(
            model_version=version,
            config_version="quant-research-v2",
            feature_names=("momentum", "volatility"),
        )
        with tempfile.TemporaryDirectory(dir=self.temporary_root()) as root:
            first = ImmutableModelStore(Path(root))
            digest = first.save(artifact)

            loaded = ImmutableModelStore(Path(root)).load(
                version,
                expected_hash=digest,
                expected_config_version="quant-research-v2",
                expected_feature_names=("momentum", "volatility"),
            )

            self.assertEqual(version, loaded.model_version)
            self.assertEqual(artifact.feature_names, loaded.feature_names)

    def test_load_rejects_wrong_hash_or_feature_schema(self):
        version = "b" * 64
        artifact = SimpleNamespace(
            model_version=version,
            config_version="quant-research-v2",
            feature_names=("momentum",),
        )
        with tempfile.TemporaryDirectory(dir=self.temporary_root()) as root:
            store = ImmutableModelStore(Path(root))
            digest = store.save(artifact)

            with self.assertRaisesRegex(ValueError, "hash"):
                store.load(version, expected_hash="0" * 64)
            with self.assertRaisesRegex(ValueError, "feature schema"):
                store.load(
                    version,
                    expected_hash=digest,
                    expected_feature_names=("different",),
                )

    @staticmethod
    def temporary_root() -> Path:
        root = Path(__file__).resolve().parents[1] / ".test-tmp"
        root.mkdir(exist_ok=True)
        return root


if __name__ == "__main__":
    unittest.main()
