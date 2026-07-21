from __future__ import annotations

import hashlib
import json
import os
import threading
import uuid
from pathlib import Path
from typing import Any, Mapping, Sequence

import pyarrow as pa
import pyarrow.parquet as pq

_ROW_METADATA = frozenset({
    "asOfDate", "sampleRole", "netExcessReturn", "positiveExcess",
})


class FactorSnapshotStore:
    def __init__(self, root: Path):
        self.root = root.resolve()
        self._lock = threading.Lock()

    def write(self, rows: Sequence[Mapping[str, Any]], context: Mapping[str, Any]) -> dict[str, Any]:
        canonical_rows = [dict(sorted(row.items())) for row in rows]
        material = {
            "context": dict(sorted(context.items())),
            "rows": canonical_rows,
        }
        version = hashlib.sha256(_json(material).encode("utf-8")).hexdigest()
        relative = Path("quant-factors") / version[:2] / f"{version}.parquet"
        target = (self.root / relative).resolve()
        manifest_path = target.with_suffix(".manifest.json")
        if self.root not in target.parents:
            raise ValueError("factor snapshot path escapes storage root")
        with self._lock:
            target.parent.mkdir(parents=True, exist_ok=True)
            if target.exists() or manifest_path.exists():
                return self._verify_existing(version, relative, target, manifest_path)
            parquet_temp = target.with_name(f".{uuid.uuid4().hex}.parquet.tmp")
            manifest_temp = manifest_path.with_name(f".{uuid.uuid4().hex}.manifest.tmp")
            try:
                pq.write_table(pa.Table.from_pylist(canonical_rows), parquet_temp)
                artifact_hash = _file_hash(parquet_temp)
                manifest = self._manifest(version, relative, artifact_hash, canonical_rows, context)
                manifest_temp.write_text(_json(manifest), encoding="utf-8")
                os.replace(parquet_temp, target)
                os.replace(manifest_temp, manifest_path)
                return manifest
            finally:
                parquet_temp.unlink(missing_ok=True)
                manifest_temp.unlink(missing_ok=True)

    def _verify_existing(self, version: str, relative: Path, target: Path, manifest_path: Path) -> dict[str, Any]:
        if not target.is_file() or not manifest_path.is_file():
            raise ValueError("factor snapshot is incomplete")
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        if manifest.get("featureSetVersion") != version:
            raise ValueError("factor snapshot manifest identity mismatch")
        if manifest.get("featureArtifactUri") != relative.as_posix():
            raise ValueError("factor snapshot storage URI mismatch")
        if manifest.get("featureArtifactHash") != _file_hash(target):
            raise ValueError("factor snapshot file was modified")
        return manifest

    @staticmethod
    def _manifest(version: str, relative: Path, artifact_hash: str,
                  rows: Sequence[Mapping[str, Any]], context: Mapping[str, Any]) -> dict[str, Any]:
        metadata = set(context) | _ROW_METADATA
        feature_names = sorted({name for row in rows for name in row if name not in metadata})
        return {
            "featureSetVersion": version,
            "featureArtifactUri": relative.as_posix(),
            "featureArtifactHash": artifact_hash,
            "featureSchema": feature_names,
            "rowCount": len(rows),
            "context": dict(sorted(context.items())),
        }


def _file_hash(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)
