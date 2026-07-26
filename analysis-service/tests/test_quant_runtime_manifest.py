from __future__ import annotations

import unittest
from pathlib import Path

from app.quant.config import QuantConfig
from app.quant.runtime_manifest import build_runtime_manifest


class QuantRuntimeManifestTest(unittest.TestCase):
    def test_runtime_version_changes_with_config_or_quant_code(self):
        config = QuantConfig({
            "version": "quant-research-test",
            "randomSeed": 17,
        })
        changed_config = QuantConfig({
            "version": "quant-research-test",
            "randomSeed": 19,
        })

        source_root = Path(__file__).resolve().parents[1] / ".test-tmp"
        module = source_root / "runtime_manifest_features.py"
        try:
            module.write_text("FEATURE_VERSION = 1\n", encoding="utf-8")

            original = build_runtime_manifest(config, source_root)
            same = build_runtime_manifest(config, source_root)
            after_config_change = build_runtime_manifest(changed_config, source_root)
            module.write_text("FEATURE_VERSION = 2\n", encoding="utf-8")
            after_code_change = build_runtime_manifest(config, source_root)
        finally:
            module.unlink(missing_ok=True)

        self.assertEqual(original["runtimeVersion"], same["runtimeVersion"])
        self.assertNotEqual(
            original["runtimeVersion"],
            after_config_change["runtimeVersion"],
        )
        self.assertNotEqual(
            original["runtimeVersion"],
            after_code_change["runtimeVersion"],
        )
        self.assertEqual(64, len(original["runtimeVersion"]))


if __name__ == "__main__":
    unittest.main()
