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

    def test_research_schema_is_derived_from_the_training_configuration(self):
        config = QuantConfig({
            "version": "quant-research-test",
            "randomSeed": 17,
            "training": {
                "walkForwardFolds": 7,
                "embargoHorizonMultiplier": 1.5,
                "xgboost": {"maximumDepth": 4},
            },
            "researchInterface": {
                "schemaVersion": "research-ui-v2",
                "modelFamilies": [{
                    "code": "A_SHARE_STOCK",
                    "name": "A股股票",
                    "predictionHeads": ["RELATIVE_ALPHA"],
                }],
            },
            "validation": {"diagnostics": {"maximumDmPValue": 0.08}},
            "promotion": {"returnEnhancer": {
                "minimumDeflatedSharpeProbability": 0.91,
                "maximumPbo": 0.24,
            }},
            "autoSearch": {"strategies": [{
                "algorithm": "XGBOOST",
                "parameters": [{
                    "name": "maximumDepth",
                    "label": "树最大深度",
                    "configPath": "training.xgboost.maximumDepth",
                    "type": "int",
                    "low": 1,
                    "high": 6,
                    "step": 1,
                }],
            }]},
        })

        schema = build_runtime_manifest(config)["researchSchema"]

        self.assertEqual("research-ui-v2", schema["schemaVersion"])
        self.assertEqual(["XGBOOST"], schema["algorithms"])
        self.assertEqual("A_SHARE_STOCK", schema["modelFamilies"][0]["code"])
        self.assertEqual({
            "key": "maximumDepth",
            "label": "树最大深度",
            "type": "INTEGER",
            "defaultValue": 4,
            "minimum": 1,
            "maximum": 6,
            "step": 1,
            "sampling": "LINEAR",
            "algorithms": ["XGBOOST"],
        }, schema["fields"][0])
        self.assertEqual({
            "minimumWalkForwardFolds": 7,
            "embargoHorizonMultiplier": 1.5,
            "maximumDmPValue": 0.08,
            "minimumDeflatedSharpeProbability": 0.91,
            "maximumPbo": 0.24,
        }, schema["immutableValidation"])


if __name__ == "__main__":
    unittest.main()
