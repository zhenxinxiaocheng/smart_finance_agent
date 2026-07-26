from __future__ import annotations

import hashlib
import os
import pickle
import re
from pathlib import Path
from typing import Any, Sequence


_MODEL_VERSION = re.compile(r"^[0-9a-f]{64}$")


class ImmutableModelStore:
    def __init__(self, root: Path):
        self.root = root.resolve()
        self.root.mkdir(parents=True, exist_ok=True)

    def save(self, artifact: Any) -> str:
        version = self._version(artifact.model_version)
        payload = pickle.dumps(artifact, protocol=pickle.HIGHEST_PROTOCOL)
        digest = hashlib.sha256(payload).hexdigest()
        target = self.root / f"{version}.pkl"
        if target.exists():
            existing_digest = hashlib.sha256(target.read_bytes()).hexdigest()
            if existing_digest != digest:
                raise ValueError("immutable model artifact hash conflict")
            return digest
        temporary = target.with_suffix(".tmp")
        temporary.write_bytes(payload)
        os.replace(temporary, target)
        return digest

    def load(
        self,
        model_version: str,
        *,
        expected_hash: str | None = None,
        expected_config_version: str | None = None,
        expected_feature_names: Sequence[str] | None = None,
    ) -> Any:
        version = self._version(model_version)
        target = self.root / f"{version}.pkl"
        if not target.is_file():
            raise FileNotFoundError(f"quant model {version} was not found")
        payload = target.read_bytes()
        digest = hashlib.sha256(payload).hexdigest()
        if expected_hash is not None and digest != expected_hash:
            raise ValueError("quant model artifact hash mismatch")
        artifact = pickle.loads(payload)
        if getattr(artifact, "model_version", None) != version:
            raise ValueError("quant model version mismatch")
        if (
            expected_config_version is not None
            and getattr(artifact, "config_version", None) != expected_config_version
        ):
            raise ValueError("quant model configuration version mismatch")
        if (
            expected_feature_names is not None
            and tuple(getattr(artifact, "feature_names", ()))
            != tuple(expected_feature_names)
        ):
            raise ValueError("quant model feature schema mismatch")
        return artifact

    @staticmethod
    def _version(value: Any) -> str:
        version = str(value or "").strip()
        if not _MODEL_VERSION.fullmatch(version):
            raise ValueError("invalid quant model version")
        return version
