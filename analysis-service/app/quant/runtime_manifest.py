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
    }


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
