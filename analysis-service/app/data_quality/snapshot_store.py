from __future__ import annotations

import hashlib
import json
import os
import re
import tempfile
import threading
import time
import errno
from contextlib import contextmanager
from datetime import date, datetime, timezone, timedelta
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any

import pyarrow as pa
import pyarrow.parquet as pq
from pydantic import ValidationError

from .models import DataQualityManifest, DataSnapshotContext


class SnapshotIntegrityError(RuntimeError):
    """Raised when an on-disk snapshot cannot be proven intact."""


class SnapshotNotFoundError(FileNotFoundError):
    """Raised when a complete snapshot pair does not exist."""


_VERSION_RE = re.compile(r"^[0-9a-f]{64}$")
_IDENTITY_FIELDS = (
    "product_code", "product_type", "market", "frequency", "adjust_type", "provider", "adapter_version",
)
_DECIMAL_FIELDS = ("open", "high", "low", "close", "volume", "nav", "adjustment_factor")
_OPTIONAL_STRING_FIELDS = ("trading_status", "corporate_action_reference", "nav_type")
_FIELDS = (*_IDENTITY_FIELDS, "data_date", "observed_at", *_DECIMAL_FIELDS, *_OPTIONAL_STRING_FIELDS, "estimated")
_THREAD_LOCKS: dict[str, threading.Lock] = {}
_THREAD_LOCKS_GUARD = threading.Lock()
_WINDOWS_LOCK_CONTENTION_ERRORS = frozenset({32, 33})
_LOCK_CONTENTION_ERRNOS = frozenset({errno.EACCES, errno.EAGAIN})
_LOCK_RETRY_INTERVAL_SECONDS = 0.01

CANONICAL_SCHEMA = pa.schema([
    ("product_code", pa.string()),
    ("product_type", pa.string()),
    ("market", pa.string()),
    ("frequency", pa.string()),
    ("adjust_type", pa.string()),
    ("provider", pa.string()),
    ("adapter_version", pa.string()),
    ("data_date", pa.date32()),
    ("observed_at", pa.timestamp("us", tz="UTC")),
    ("open", pa.decimal128(38, 10)),
    ("high", pa.decimal128(38, 10)),
    ("low", pa.decimal128(38, 10)),
    ("close", pa.decimal128(38, 10)),
    ("volume", pa.decimal128(38, 10)),
    ("nav", pa.decimal128(38, 10)),
    ("trading_status", pa.string()),
    ("adjustment_factor", pa.decimal128(38, 10)),
    ("corporate_action_reference", pa.string()),
    ("nav_type", pa.string()),
    ("estimated", pa.bool_()),
])


class SnapshotStore:
    def __init__(self, root: Path, schema_version: str):
        if not isinstance(root, Path):
            raise ValueError("root must be a Path")
        if not isinstance(schema_version, str) or not schema_version.strip():
            raise ValueError("schema_version must be non-blank")
        self.root = root.resolve()
        self.schema_version = schema_version

    def write(self, records: list[dict[str, Any]], context: DataSnapshotContext) -> DataQualityManifest:
        rows = self._canonicalize_records(records, context)
        table = pa.Table.from_pylist(rows, schema=CANONICAL_SCHEMA)
        content_hash = _content_hash(rows)
        dataset_dir = self.root / "snapshots"
        dataset_dir.mkdir(parents=True, exist_ok=True)
        parquet_temp = self._temporary_path(dataset_dir, ".parquet.tmp")
        manifest_temp: Path | None = None
        final_parquet: Path | None = None
        final_manifest: Path | None = None
        created_parquet = False
        created_manifest = False
        try:
            pq.write_table(table, parquet_temp)
            parquet_hash = _sha256_file(parquet_temp)
            manifest = self._build_manifest(rows, context, content_hash, parquet_hash)
            with self._dataset_lock(manifest.dataset_version):
                try:
                    final_parquet, final_manifest = self._paths(manifest.dataset_version)
                    final_parquet.parent.mkdir(parents=True, exist_ok=True)
                    self._recover_incomplete_snapshot(manifest.dataset_version)
                    existing = self._existing_snapshot(manifest.dataset_version)
                    if existing is not None:
                        if existing.model_dump(mode="json", by_alias=True) != manifest.model_dump(mode="json", by_alias=True):
                            raise SnapshotIntegrityError("existing snapshot does not match computed manifest")
                        parquet_temp.unlink(missing_ok=True)
                        return existing
                    manifest_temp = self._temporary_path(final_manifest.parent, ".manifest.json.tmp")
                    manifest_temp.write_text(
                        json.dumps(manifest.model_dump(mode="json", by_alias=True), sort_keys=True, separators=(",", ":")),
                        encoding="utf-8",
                    )
                    os.replace(parquet_temp, final_parquet)
                    created_parquet = True
                    os.replace(manifest_temp, final_manifest)
                    created_manifest = True
                    return manifest
                except Exception:
                    if created_manifest and final_manifest is not None:
                        final_manifest.unlink(missing_ok=True)
                    if created_parquet and final_parquet is not None:
                        final_parquet.unlink(missing_ok=True)
                    raise
        finally:
            parquet_temp.unlink(missing_ok=True)
            if manifest_temp is not None:
                manifest_temp.unlink(missing_ok=True)

    def read(self, dataset_version: str) -> list[dict[str, Any]]:
        manifest, rows = self._load_verified(dataset_version)
        del manifest
        return [_api_row(row) for row in rows]

    def read_manifest(self, dataset_version: str) -> DataQualityManifest:
        manifest, _ = self._load_verified(dataset_version)
        return manifest

    def verify(self, manifest: DataQualityManifest) -> DataQualityManifest:
        if not isinstance(manifest, DataQualityManifest):
            raise ValueError("manifest must be a DataQualityManifest")
        stored, _ = self._load_verified(manifest.dataset_version)
        if stored.model_dump(mode="json", by_alias=True) != manifest.model_dump(mode="json", by_alias=True):
            raise SnapshotIntegrityError("manifest does not match stored snapshot")
        return stored

    def claim(self, dataset_version: str) -> Path:
        with self._dataset_lock(dataset_version):
            self._load_verified(dataset_version)
            claim_path = self._claim_path(dataset_version)
            claim_path.parent.mkdir(parents=True, exist_ok=True)
            if claim_path.exists():
                return claim_path
            temp = self._temporary_path(claim_path.parent, ".claim.tmp")
            try:
                temp.write_text(dataset_version + "\n", encoding="ascii")
                os.replace(temp, claim_path)
                return claim_path
            finally:
                temp.unlink(missing_ok=True)

    def cleanup_unclaimed(self, now: datetime, retention: timedelta) -> int:
        if not isinstance(now, datetime) or now.tzinfo is None:
            raise ValueError("now must be a timezone-aware datetime")
        if not isinstance(retention, timedelta) or retention <= timedelta(0):
            raise ValueError("retention must be positive")
        cutoff = (now - retention).timestamp()
        removed = 0
        for base in (self.root / "snapshots", self.root / "claims"):
            if base.exists():
                for temp in base.rglob("*.tmp"):
                    if temp.is_file() and temp.stat().st_mtime < cutoff:
                        temp.unlink()
                        removed += 1
        snapshots = self.root / "snapshots"
        if not snapshots.exists():
            return removed
        for manifest_path in snapshots.rglob("*.manifest.json"):
            version = manifest_path.name.removesuffix(".manifest.json")
            if not _VERSION_RE.fullmatch(version):
                continue
            with self._dataset_lock(version):
                parquet_path, expected_manifest = self._paths(version)
                if manifest_path != expected_manifest or not parquet_path.is_file() or not expected_manifest.is_file():
                    continue
                if self._claim_path(version).exists():
                    continue
                if max(expected_manifest.stat().st_mtime, parquet_path.stat().st_mtime) < cutoff:
                    parquet_path.unlink()
                    expected_manifest.unlink()
                    removed += 2
        return removed

    def _canonicalize_records(self, records: list[dict[str, Any]], context: DataSnapshotContext) -> list[dict[str, Any]]:
        if not isinstance(records, list) or not records:
            raise ValueError("records must be a non-empty list")
        if not isinstance(context, DataSnapshotContext):
            raise ValueError("context must be a DataSnapshotContext")
        rows = [_canonicalize_record(record, context) for record in records]
        rows.sort(key=lambda row: (row["product_code"], row["market"], row["frequency"], row["adjust_type"], row["data_date"]))
        keys = [(row["product_code"], row["market"], row["frequency"], row["adjust_type"], row["data_date"]) for row in rows]
        if len(set(keys)) != len(keys):
            raise ValueError("duplicate canonical record identity")
        return rows

    def _build_manifest(self, rows: list[dict[str, Any]], context: DataSnapshotContext, content_hash: str, parquet_hash: str) -> DataQualityManifest:
        payload = {
            "productType": context.product_type.value,
            "market": context.market,
            "code": context.code,
            "frequency": context.frequency,
            "adjustType": context.adjust_type.value,
            "provider": context.provider,
            "adapterVersion": context.adapter_version,
            "requestedStartDate": context.requested_start_date.isoformat(),
            "requestedEndDate": context.requested_end_date.isoformat(),
            "sampleStartDate": rows[0]["data_date"].isoformat(),
            "sampleEndDate": rows[-1]["data_date"].isoformat(),
            "recordCount": len(rows),
            "fetchedAt": _utc_iso(context.fetched_at),
            "contentHash": content_hash,
            "parquetFileHash": parquet_hash,
            "storageFormat": "PARQUET",
            "schemaVersion": self.schema_version,
        }
        dataset_version = _sha256_json(payload)
        storage_uri = f"snapshots/{dataset_version[:2]}/{dataset_version}.parquet"
        return DataQualityManifest.model_validate(payload | {"datasetVersion": dataset_version, "storageUri": storage_uri})

    def _load_verified(self, dataset_version: str) -> tuple[DataQualityManifest, list[dict[str, Any]]]:
        self._validate_version(dataset_version)
        parquet_path, manifest_path = self._paths(dataset_version)
        if not parquet_path.is_file() or not manifest_path.is_file():
            raise SnapshotNotFoundError(dataset_version)
        try:
            manifest = DataQualityManifest.model_validate_json(manifest_path.read_text(encoding="utf-8"))
        except (OSError, ValueError, ValidationError) as error:
            raise SnapshotIntegrityError("invalid snapshot manifest") from error
        expected_uri = f"snapshots/{dataset_version[:2]}/{dataset_version}.parquet"
        if manifest.dataset_version != dataset_version or manifest.storage_uri != expected_uri or manifest.schema_version != self.schema_version:
            raise SnapshotIntegrityError("manifest identity does not match snapshot location")
        if _sha256_file(parquet_path) != manifest.parquet_file_hash:
            raise SnapshotIntegrityError("Parquet file hash mismatch")
        try:
            table = pq.read_table(parquet_path)
        except Exception as error:
            raise SnapshotIntegrityError("unreadable Parquet snapshot") from error
        if table.schema != CANONICAL_SCHEMA:
            raise SnapshotIntegrityError("unexpected Parquet schema")
        try:
            rows = self._canonicalize_records(table.to_pylist(), _context_from_manifest(manifest))
        except (TypeError, ValueError) as error:
            raise SnapshotIntegrityError("non-canonical Parquet rows") from error
        if _content_hash(rows) != manifest.content_hash:
            raise SnapshotIntegrityError("snapshot content hash mismatch")
        rebuilt = self._build_manifest(rows, _context_from_manifest(manifest), manifest.content_hash, manifest.parquet_file_hash)
        if rebuilt.model_dump(mode="json", by_alias=True) != manifest.model_dump(mode="json", by_alias=True):
            raise SnapshotIntegrityError("manifest identity hash mismatch")
        return manifest, rows

    def _existing_snapshot(self, dataset_version: str) -> DataQualityManifest | None:
        parquet_path, manifest_path = self._paths(dataset_version)
        if not parquet_path.exists() and not manifest_path.exists():
            return None
        if not parquet_path.is_file() or not manifest_path.is_file():
            raise SnapshotIntegrityError("incomplete existing snapshot")
        manifest, _ = self._load_verified(dataset_version)
        return manifest

    def _recover_incomplete_snapshot(self, dataset_version: str) -> None:
        parquet_path, manifest_path = self._paths(dataset_version)
        parquet_exists = parquet_path.exists()
        manifest_exists = manifest_path.exists()
        if parquet_exists == manifest_exists:
            return
        if self._claim_path(dataset_version).exists():
            raise SnapshotIntegrityError("claimed snapshot has an incomplete final pair")
        for path in (parquet_path, manifest_path):
            if path.exists():
                if not path.is_file():
                    raise SnapshotIntegrityError("incomplete snapshot path is not a file")
                path.unlink()

    def _paths(self, dataset_version: str) -> tuple[Path, Path]:
        self._validate_version(dataset_version)
        relative = Path("snapshots") / dataset_version[:2] / f"{dataset_version}.parquet"
        parquet_path = (self.root / relative).resolve()
        if self.root not in parquet_path.parents:
            raise SnapshotIntegrityError("snapshot path escapes configured root")
        return parquet_path, parquet_path.with_suffix(".manifest.json")

    def _claim_path(self, dataset_version: str) -> Path:
        self._validate_version(dataset_version)
        path = (self.root / "claims" / dataset_version[:2] / f"{dataset_version}.claim").resolve()
        if self.root not in path.parents:
            raise SnapshotIntegrityError("claim path escapes configured root")
        return path

    def _lock_path(self, dataset_version: str) -> Path:
        self._validate_version(dataset_version)
        path = (self.root / "locks" / dataset_version[:2] / f"{dataset_version}.lock").resolve()
        if self.root not in path.parents:
            raise SnapshotIntegrityError("lock path escapes configured root")
        return path

    @contextmanager
    def _dataset_lock(self, dataset_version: str):
        lock_path = self._lock_path(dataset_version)
        lock_path.parent.mkdir(parents=True, exist_ok=True)
        with _thread_lock(lock_path):
            descriptor = os.open(lock_path, os.O_RDWR | os.O_CREAT)
            with os.fdopen(descriptor, "r+b") as lock_file:
                if os.fstat(lock_file.fileno()).st_size == 0:
                    lock_file.write(b"\0")
                    lock_file.flush()
                _acquire_file_lock(lock_file)
                try:
                    yield
                finally:
                    _release_file_lock(lock_file)

    @staticmethod
    def _temporary_path(directory: Path, suffix: str) -> Path:
        descriptor, name = tempfile.mkstemp(dir=directory, suffix=suffix)
        os.close(descriptor)
        return Path(name)

    @staticmethod
    def _validate_version(dataset_version: str) -> None:
        if not isinstance(dataset_version, str) or not _VERSION_RE.fullmatch(dataset_version):
            raise ValueError("dataset_version must be a lowercase SHA-256 hex string")


def _canonicalize_record(record: dict[str, Any], context: DataSnapshotContext) -> dict[str, Any]:
    if not isinstance(record, dict):
        raise ValueError("record must be a mapping")
    unknown = set(record) - set(_FIELDS)
    if unknown:
        raise ValueError("record contains unknown fields")
    missing = (set(_IDENTITY_FIELDS) | {"data_date", "observed_at"}) - set(record)
    if missing:
        raise ValueError("record is missing required fields")
    expected = {
        "product_code": context.code,
        "product_type": context.product_type.value,
        "market": context.market,
        "frequency": context.frequency,
        "adjust_type": context.adjust_type.value,
        "provider": context.provider,
        "adapter_version": context.adapter_version,
    }
    row: dict[str, Any] = {}
    for field in _IDENTITY_FIELDS:
        value = record[field]
        if not isinstance(value, str) or not value.strip():
            raise ValueError(f"{field} must be a non-blank string")
        if value != expected[field]:
            raise ValueError(f"record {field} conflicts with context")
        row[field] = value
    row["data_date"] = _parse_date(record["data_date"])
    row["observed_at"] = _parse_timestamp(record["observed_at"])
    for field in _DECIMAL_FIELDS:
        row[field] = _parse_decimal(record.get(field), field)
    for field in _OPTIONAL_STRING_FIELDS:
        value = record.get(field)
        if value is not None and (not isinstance(value, str) or not value.strip()):
            raise ValueError(f"{field} must be a non-blank string when provided")
        row[field] = value
    estimated = record.get("estimated")
    if estimated is not None and type(estimated) is not bool:
        raise ValueError("estimated must be a boolean when provided")
    row["estimated"] = estimated
    return row


def _parse_date(value: Any) -> date:
    if isinstance(value, datetime):
        raise ValueError("data_date must not include a time")
    if isinstance(value, date):
        return value
    if isinstance(value, str):
        try:
            return date.fromisoformat(value)
        except ValueError as error:
            raise ValueError("data_date is unparseable") from error
    raise ValueError("data_date is unparseable")


def _parse_timestamp(value: Any) -> datetime:
    if isinstance(value, str):
        try:
            value = datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError as error:
            raise ValueError("observed_at is unparseable") from error
    if not isinstance(value, datetime) or value.tzinfo is None:
        raise ValueError("observed_at must be a timezone-aware timestamp")
    return value.astimezone(timezone.utc)


def _parse_decimal(value: Any, field: str) -> Decimal | None:
    if value is None:
        return None
    if isinstance(value, bool):
        raise ValueError(f"{field} is unparseable")
    try:
        decimal = Decimal(str(value))
    except (InvalidOperation, ValueError) as error:
        raise ValueError(f"{field} is unparseable") from error
    if not decimal.is_finite() or decimal.as_tuple().exponent < -10 or len(decimal.as_tuple().digits) > 38:
        raise ValueError(f"{field} is outside canonical decimal128 range")
    return decimal


def _context_from_manifest(manifest: DataQualityManifest) -> DataSnapshotContext:
    return DataSnapshotContext(
        product_type=manifest.product_type, market=manifest.market, code=manifest.code, frequency=manifest.frequency,
        adjust_type=manifest.adjust_type, provider=manifest.provider, adapter_version=manifest.adapter_version,
        requested_start_date=manifest.requested_start_date, requested_end_date=manifest.requested_end_date,
        fetched_at=manifest.fetched_at,
    )


def _api_row(row: dict[str, Any]) -> dict[str, Any]:
    result = dict(row)
    result["data_date"] = row["data_date"].isoformat()
    result["observed_at"] = _utc_iso(row["observed_at"])
    for field in _DECIMAL_FIELDS:
        if row[field] is not None:
            result[field] = _decimal_text(row[field])
    return result


def _content_hash(rows: list[dict[str, Any]]) -> str:
    return hashlib.sha256(b"\n".join(_canonical_json(_api_row(row)).encode("utf-8") for row in rows)).hexdigest()


def _sha256_json(payload: dict[str, Any]) -> str:
    return hashlib.sha256(_canonical_json(payload).encode("utf-8")).hexdigest()


def _canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _utc_iso(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def _decimal_text(value: Decimal) -> str:
    text = format(value, "f")
    if "." in text:
        text = text.rstrip("0").rstrip(".")
    return text if text not in {"", "-0"} else "0"


@contextmanager
def _thread_lock(lock_path: Path):
    key = str(lock_path)
    with _THREAD_LOCKS_GUARD:
        lock = _THREAD_LOCKS.setdefault(key, threading.Lock())
    with lock:
        yield


def _acquire_file_lock(lock_file: Any) -> None:
    if os.name == "nt":
        import msvcrt

        while True:
            try:
                lock_file.seek(0)
                msvcrt.locking(lock_file.fileno(), msvcrt.LK_NBLCK, 1)
                return
            except OSError as error:
                if not _is_lock_contention_error(error, "nt"):
                    raise
                time.sleep(_LOCK_RETRY_INTERVAL_SECONDS)
    else:
        import fcntl

        fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX)


def _release_file_lock(lock_file: Any) -> None:
    if os.name == "nt":
        import msvcrt

        lock_file.seek(0)
        msvcrt.locking(lock_file.fileno(), msvcrt.LK_UNLCK, 1)
    else:
        import fcntl

        fcntl.flock(lock_file.fileno(), fcntl.LOCK_UN)


def _is_lock_contention_error(error: OSError, platform_name: str) -> bool:
    winerror = getattr(error, "winerror", None)
    if platform_name == "nt" and winerror is not None:
        return winerror in _WINDOWS_LOCK_CONTENTION_ERRORS
    return error.errno in _LOCK_CONTENTION_ERRNOS
