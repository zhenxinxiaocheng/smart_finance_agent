from __future__ import annotations

import json
import os
from collections.abc import Mapping
from decimal import Decimal
from pathlib import Path
from typing import Any

from .models import DataQualityConfig


def load_data_quality_config(version: str, config_dir: str | Path | None = None) -> DataQualityConfig:
    suffix = _version_suffix(version)
    directory = Path(config_dir) if config_dir is not None else Path(__file__).resolve().parents[2] / "config"
    matches = sorted(directory.glob(f"data-quality-{suffix}.json"))
    if len(matches) != 1:
        raise ValueError(f"expected exactly one data-quality config for version {version!r}, found {len(matches)}")

    with matches[0].open("r", encoding="utf-8") as handle:
        payload = json.load(handle, parse_float=Decimal)
    if not isinstance(payload, Mapping):
        raise ValueError("data-quality config root must be an object")
    expected_version = f"data-quality-{suffix}"
    if payload.get("version") != expected_version:
        raise ValueError(f"data-quality config version must be {expected_version}")

    env_name = payload.get("storageRootEnv")
    if not isinstance(env_name, str) or not env_name.strip():
        raise ValueError("data-quality config storageRootEnv must be a non-blank string")
    raw_root = os.getenv(env_name)
    configured_root = payload.get("storageRoot")
    if raw_root and raw_root.strip():
        storage_root = Path(raw_root).expanduser()
    elif isinstance(configured_root, str) and configured_root.strip():
        candidate = Path(configured_root).expanduser()
        storage_root = candidate if candidate.is_absolute() else directory / candidate
    else:
        raise ValueError(f"data-quality storage environment variable {env_name} is missing or blank")
    storage_root.mkdir(parents=True, exist_ok=True)
    if not storage_root.is_dir():
        raise ValueError(f"data-quality storage root from {env_name} must be a directory")

    data: dict[str, Any] = dict(payload)
    data["storageRoot"] = storage_root.resolve()
    return DataQualityConfig.model_validate(data)


def _version_suffix(version: str) -> str:
    normalized = version.strip()
    if not normalized:
        raise ValueError("data-quality config version cannot be blank")
    return normalized.removeprefix("data-quality-")
