from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any

from .config import QuantConfig


def build_runtime_manifest(
    config: QuantConfig,
    source_root: Path | None = None,
) -> dict[str, Any]:
    quant_source_root = (source_root or Path(__file__).resolve().parent).resolve()
    config_hash = _hash_bytes(_canonical_json(config.data))
    code_hash = _source_hash(quant_source_root)
    runtime_version = _hash_bytes(_canonical_json({
        "codeHash": code_hash,
        "configHash": config_hash,
        "randomSeed": config.integer("randomSeed"),
    }))
    return {
        "runtimeVersion": runtime_version,
        "quantConfigVersion": config.version,
        "configHash": config_hash,
        "codeHash": code_hash,
        "randomSeed": config.integer("randomSeed"),
        "researchSchema": _research_schema(config),
    }


def _research_schema(config: QuantConfig) -> dict[str, Any]:
    interface = config.data.get("researchInterface", {})
    strategies = config.data.get("autoSearch", {}).get("strategies", [])
    fields: dict[str, dict[str, Any]] = {}
    algorithms: list[str] = []
    for strategy in strategies if isinstance(strategies, list) else []:
        if not isinstance(strategy, dict):
            continue
        algorithm = strategy.get("algorithm")
        if isinstance(algorithm, str) and algorithm not in algorithms:
            algorithms.append(algorithm)
        specs = strategy.get("parameters", [])
        for spec in specs if isinstance(specs, list) else []:
            if not isinstance(spec, dict):
                continue
            field = _research_field(config, spec)
            field["algorithms"] = [algorithm]
            existing = fields.get(field["key"])
            if existing is not None:
                existing_definition = {
                    key: value for key, value in existing.items()
                    if key != "algorithms"
                }
                field_definition = {
                    key: value for key, value in field.items()
                    if key != "algorithms"
                }
                if existing_definition != field_definition:
                    raise ValueError(
                        f"conflicting research parameter schema: {field['key']}"
                    )
                if algorithm not in existing["algorithms"]:
                    existing["algorithms"].append(algorithm)
            else:
                fields[field["key"]] = field

    return {
        "schemaVersion": interface.get("schemaVersion", config.version),
        "modelFamilies": interface.get("modelFamilies", []),
        "algorithms": algorithms,
        "fields": list(fields.values()),
        "immutableValidation": {
            "minimumWalkForwardFolds": config.integer(
                "training.walkForwardFolds"
            ),
            "embargoHorizonMultiplier": config.number(
                "training.embargoHorizonMultiplier"
            ),
            "maximumDmPValue": config.number(
                "validation.diagnostics.maximumDmPValue"
            ),
            "minimumDeflatedSharpeProbability": config.number(
                "promotion.returnEnhancer.minimumDeflatedSharpeProbability"
            ),
            "maximumPbo": config.number("promotion.returnEnhancer.maximumPbo"),
        },
    } if interface else {}


def _research_field(
    config: QuantConfig,
    spec: dict[str, Any],
) -> dict[str, Any]:
    name = spec.get("name")
    label = spec.get("label")
    path = spec.get("configPath")
    parameter_type = spec.get("type")
    if not all(isinstance(value, str) and value.strip()
               for value in (name, label, path, parameter_type)):
        raise ValueError("research parameter requires name, label, configPath and type")
    if parameter_type == "horizon_window":
        minimum = spec.get("minimum")
        maximum = spec.get("maximum")
        ui_type = "INTEGER"
    else:
        minimum = spec.get("low")
        maximum = spec.get("high")
        ui_type = "INTEGER" if parameter_type == "int" else "NUMBER"
    if not isinstance(minimum, (int, float)) or not isinstance(maximum, (int, float)):
        raise ValueError(f"research parameter range is invalid: {name}")
    field = {
        "key": name,
        "label": label,
        "type": ui_type,
        "defaultValue": config.value(path),
        "minimum": minimum,
        "maximum": maximum,
        "step": spec.get(
            "step",
            1 if ui_type == "INTEGER" else "any",
        ),
        "sampling": "LOG" if spec.get("log") is True else "LINEAR",
    }
    return field


def _source_hash(source_root: Path) -> str:
    digest = hashlib.sha256()
    for path in sorted(source_root.rglob("*.py"), key=lambda item: item.as_posix()):
        relative = path.relative_to(source_root).as_posix().encode("utf-8")
        digest.update(len(relative).to_bytes(4, "big"))
        digest.update(relative)
        content = path.read_bytes()
        digest.update(len(content).to_bytes(8, "big"))
        digest.update(content)
    return digest.hexdigest()


def _canonical_json(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")


def _hash_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()
