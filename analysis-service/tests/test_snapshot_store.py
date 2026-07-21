import json
import multiprocessing
import os
import errno
import tempfile
import threading
import unittest
from contextlib import contextmanager
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal
from pathlib import Path
from unittest.mock import patch

from pydantic import ValidationError

from app.data_quality import AdjustType, DataSnapshotContext, ProductType
from app.data_quality.snapshot_store import (
    _is_lock_contention_error,
    SnapshotIntegrityError,
    SnapshotNotFoundError,
    SnapshotStore,
)


def _hold_dataset_lock_in_child(root: str, dataset_version: str, acquired, release) -> None:
    store = SnapshotStore(Path(root), "market-data-schema-v1")
    with store._dataset_lock(dataset_version):
        acquired.set()
        release.wait(10)


class SnapshotStoreTest(unittest.TestCase):
    def setUp(self):
        self.tempdir = tempfile.TemporaryDirectory()
        self.addCleanup(self.tempdir.cleanup)
        self.root = Path(self.tempdir.name)
        self.store = SnapshotStore(self.root, "market-data-schema-v1")
        self.context = DataSnapshotContext(
            product_type=ProductType.STOCK, market="CN", code="000001", frequency="DAY",
            adjust_type=AdjustType.QFQ, provider="tencent", adapter_version="v2",
            requested_start_date=date(2026, 1, 1), requested_end_date=date(2026, 1, 31),
            fetched_at=datetime(2026, 2, 1, 12, 30, tzinfo=timezone.utc),
        )

    def _records(self):
        return [
            {
                "product_code": "000001", "product_type": "STOCK", "market": "CN", "frequency": "DAY",
                "adjust_type": "QFQ", "provider": "tencent", "adapter_version": "v2",
                "data_date": date(2026, 1, 3), "observed_at": datetime(2026, 1, 3, 7, tzinfo=timezone.utc),
                "open": Decimal("10.10"), "high": Decimal("10.50"), "low": Decimal("10.00"),
                "close": Decimal("10.30"), "volume": Decimal("1200"), "nav": None,
                "trading_status": "NORMAL", "adjustment_factor": Decimal("1.0"),
                "corporate_action_reference": None, "nav_type": None, "estimated": False,
            },
            {
                "product_code": "000001", "product_type": "STOCK", "market": "CN", "frequency": "DAY",
                "adjust_type": "QFQ", "provider": "tencent", "adapter_version": "v2",
                "data_date": date(2026, 1, 2), "observed_at": datetime(2026, 1, 2, 7, tzinfo=timezone.utc),
                "open": Decimal("10.00"), "high": Decimal("10.30"), "low": Decimal("9.90"),
                "close": Decimal("10.10"), "volume": Decimal("1000"), "nav": None,
                "trading_status": "NORMAL", "adjustment_factor": Decimal("1.0"),
                "corporate_action_reference": None, "nav_type": None, "estimated": False,
            },
        ]

    def test_write_is_deterministic_and_read_round_trips_sorted_api_safe_rows(self):
        first = self.store.write(self._records(), self.context)
        second = self.store.write(list(reversed(self._records())), self.context)

        self.assertEqual(first.dataset_version, second.dataset_version)
        self.assertRegex(first.dataset_version, r"^[0-9a-f]{64}$")
        self.assertEqual("snapshots/" + first.dataset_version[:2] + "/" + first.dataset_version + ".parquet", first.storage_uri)
        rows = self.store.read(first.dataset_version)
        self.assertEqual(["2026-01-02", "2026-01-03"], [row["data_date"] for row in rows])
        self.assertEqual("2026-01-02T07:00:00Z", rows[0]["observed_at"])
        self.assertEqual("10.1", rows[0]["close"])
        self.assertEqual("1000", rows[0]["volume"])

    def test_any_record_context_identity_or_schema_change_changes_version(self):
        baseline = self.store.write(self._records(), self.context)
        changed_rows = self._records()
        changed_rows[0]["close"] = Decimal("10.31")
        changed_content = self.store.write(changed_rows, self.context)
        changed_context = self.context.model_copy(update={"fetched_at": datetime(2026, 2, 2, tzinfo=timezone.utc)})
        changed_identity = self.store.write(self._records(), changed_context)
        changed_schema = SnapshotStore(self.root, "market-data-schema-v2").write(self._records(), self.context)

        self.assertEqual(4, len({baseline.dataset_version, changed_content.dataset_version,
                                 changed_identity.dataset_version, changed_schema.dataset_version}))

    def test_fetched_at_requires_timezone_and_normalizes_equivalent_utc_instants(self):
        values = self.context.model_dump() | {"fetched_at": datetime(2026, 2, 1, 12, 30)}
        with self.assertRaises(ValidationError):
            DataSnapshotContext(**values)

        utc = self.store.write(self._records(), self.context)
        utc_plus_eight = DataSnapshotContext(**(self.context.model_dump() | {
            "fetched_at": datetime(2026, 2, 1, 20, 30, tzinfo=timezone(timedelta(hours=8))),
        }))
        self.assertEqual(utc.dataset_version, self.store.write(self._records(), utc_plus_eight).dataset_version)

    def test_rejects_empty_duplicate_conflicting_and_unparseable_records(self):
        with self.assertRaisesRegex(ValueError, "empty"):
            self.store.write([], self.context)
        duplicate = self._records()
        duplicate[1]["data_date"] = duplicate[0]["data_date"]
        with self.assertRaisesRegex(ValueError, "duplicate"):
            self.store.write(duplicate, self.context)
        conflict = self._records()
        conflict[0]["market"] = "US"
        with self.assertRaisesRegex(ValueError, "market"):
            self.store.write(conflict, self.context)
        invalid = self._records()
        invalid[0]["close"] = "not-a-decimal"
        with self.assertRaisesRegex(ValueError, "close"):
            self.store.write(invalid, self.context)

    def test_read_fails_closed_for_parquet_or_manifest_tampering_and_missing_pair(self):
        manifest = self.store.write(self._records(), self.context)
        parquet_path = self.root / manifest.storage_uri
        parquet_path.write_bytes(parquet_path.read_bytes() + b"tampered")
        with self.assertRaises(SnapshotIntegrityError):
            self.store.read(manifest.dataset_version)

        manifest = self.store.write(self._records(), self.context.model_copy(update={"fetched_at": datetime(2026, 2, 3, tzinfo=timezone.utc)}))
        manifest_path = self.root / manifest.storage_uri.replace(".parquet", ".manifest.json")
        payload = json.loads(manifest_path.read_text(encoding="utf-8"))
        payload["recordCount"] = 99
        manifest_path.write_text(json.dumps(payload), encoding="utf-8")
        with self.assertRaises(SnapshotIntegrityError):
            self.store.read(manifest.dataset_version)

        missing = self.store.write(self._records(), self.context.model_copy(update={"fetched_at": datetime(2026, 2, 4, tzinfo=timezone.utc)}))
        (self.root / missing.storage_uri).unlink()
        with self.assertRaises(SnapshotNotFoundError):
            self.store.read(missing.dataset_version)

    def test_rejects_dataset_path_traversal(self):
        with self.assertRaises(ValueError):
            self.store.read("../" + "0" * 64)
        with self.assertRaises(ValueError):
            self.store.read("A" * 64)

    def test_atomic_write_failure_leaves_no_final_or_temp_artifacts(self):
        with patch("app.data_quality.snapshot_store.pq.write_table", side_effect=OSError("disk full")):
            with self.assertRaises(OSError):
                self.store.write(self._records(), self.context)

        self.assertEqual([], list((self.root / "snapshots").rglob("*")) if (self.root / "snapshots").exists() else [])
        self.assertEqual([], list(self.root.rglob("*.tmp")))

    def test_failure_between_parquet_and_manifest_publication_leaves_a_recoverable_store(self):
        real_replace = os.replace

        def fail_manifest_replace(source, destination):
            if str(destination).endswith(".manifest.json"):
                raise OSError("manifest disk full")
            return real_replace(source, destination)

        with patch("app.data_quality.snapshot_store.os.replace", side_effect=fail_manifest_replace):
            with self.assertRaisesRegex(OSError, "manifest disk full"):
                self.store.write(self._records(), self.context)

        self.assertEqual([], list((self.root / "snapshots").rglob("*.parquet")))
        self.assertEqual([], list((self.root / "snapshots").rglob("*.manifest.json")))
        self.assertEqual([], list(self.root.rglob("*.tmp")))
        manifest = self.store.write(self._records(), self.context)
        self.assertEqual(manifest, self.store.verify(manifest))

    def test_failed_writer_cleanup_cannot_delete_a_later_locked_writer_snapshot(self):
        real_replace = os.replace
        real_unlink = Path.unlink
        failed_manifest_replace = False
        failing_cleanup_ready = threading.Event()
        allow_failed_cleanup = threading.Event()
        second_result = []
        second_done = threading.Event()
        first_errors = []

        def fail_only_first_manifest(source, destination):
            nonlocal failed_manifest_replace
            if str(destination).endswith(".manifest.json") and not failed_manifest_replace:
                failed_manifest_replace = True
                raise OSError("first manifest publication failed")
            return real_replace(source, destination)

        def pause_failing_cleanup(path, *args, **kwargs):
            if threading.current_thread().name == "failing-writer" and str(path).endswith(".parquet"):
                failing_cleanup_ready.set()
                self.assertTrue(allow_failed_cleanup.wait(5))
            return real_unlink(path, *args, **kwargs)

        def failing_write():
            try:
                self.store.write(self._records(), self.context)
            except Exception as error:
                first_errors.append(error)

        def succeeding_write():
            try:
                second_result.append(self.store.write(self._records(), self.context))
            finally:
                second_done.set()

        with patch("app.data_quality.snapshot_store.os.replace", side_effect=fail_only_first_manifest), \
             patch("pathlib.Path.unlink", new=pause_failing_cleanup):
            first = threading.Thread(target=failing_write, name="failing-writer")
            first.start()
            self.assertTrue(failing_cleanup_ready.wait(5))
            second = threading.Thread(target=succeeding_write)
            second.start()
            try:
                self.assertFalse(second_done.wait(1))
            finally:
                allow_failed_cleanup.set()
                first.join(5)
                second.join(5)

        self.assertEqual(1, len(first_errors))
        self.assertFalse(first.is_alive())
        self.assertFalse(second.is_alive())
        self.assertEqual(1, len(second_result))
        self.assertEqual(second_result[0], self.store.verify(second_result[0]))

    def test_concurrent_writers_wait_for_publication_instead_of_observing_or_overwriting_partial_pair(self):
        real_replace = os.replace
        parquet_published = threading.Event()
        allow_manifest = threading.Event()
        results = []
        errors = []
        first_publish = True
        existing_calls = 0
        second_started = threading.Event()
        second_checked_existing_snapshot = threading.Event()
        guard = threading.Lock()
        real_existing_snapshot = SnapshotStore._existing_snapshot

        def pause_after_first_parquet(source, destination):
            nonlocal first_publish
            if str(destination).endswith(".parquet"):
                with guard:
                    should_pause = first_publish
                    first_publish = False
                if should_pause:
                    result = real_replace(source, destination)
                    parquet_published.set()
                    self.assertTrue(allow_manifest.wait(5))
                    return result
            return real_replace(source, destination)

        def write_snapshot():
            try:
                results.append(self.store.write(self._records(), self.context))
            except Exception as error:
                errors.append(error)

        def observe_existing_snapshot(store, dataset_version):
            nonlocal existing_calls
            with guard:
                existing_calls += 1
                if existing_calls == 2:
                    second_checked_existing_snapshot.set()
            return real_existing_snapshot(store, dataset_version)

        with patch("app.data_quality.snapshot_store.os.replace", side_effect=pause_after_first_parquet), \
             patch.object(SnapshotStore, "_existing_snapshot", new=observe_existing_snapshot):
            first = threading.Thread(target=write_snapshot)
            first.start()
            self.assertTrue(parquet_published.wait(5))
            second = threading.Thread(target=lambda: (second_started.set(), write_snapshot()))
            second.start()
            self.assertTrue(second_started.wait(5))
            try:
                self.assertFalse(second_checked_existing_snapshot.wait(1))
            finally:
                allow_manifest.set()
                first.join(5)
                second.join(5)

        self.assertFalse(first.is_alive())
        self.assertFalse(second.is_alive())
        self.assertEqual([], errors)
        self.assertEqual(2, len(results))
        self.assertEqual(results[0].dataset_version, results[1].dataset_version)
        self.assertEqual(results[0], self.store.verify(results[0]))

    def test_dataset_lock_blocks_an_independent_process(self):
        process_context = multiprocessing.get_context("spawn")
        dataset_version = "a" * 64
        child_acquired = process_context.Event()
        release_child = process_context.Event()
        parent_acquired = threading.Event()
        child = process_context.Process(
            target=_hold_dataset_lock_in_child,
            args=(str(self.root), dataset_version, child_acquired, release_child),
        )

        def acquire_in_parent():
            with self.store._dataset_lock(dataset_version):
                parent_acquired.set()

        child.start()
        self.assertTrue(child_acquired.wait(10))
        parent = threading.Thread(target=acquire_in_parent)
        parent.start()
        try:
            self.assertFalse(parent_acquired.wait(1))
        finally:
            release_child.set()
            child.join(10)
            parent.join(10)

        self.assertEqual(0, child.exitcode)
        self.assertFalse(parent.is_alive())
        self.assertTrue(parent_acquired.is_set())

    def test_windows_lock_contention_classifier_never_retries_access_denied_as_a_lock_conflict(self):
        access_denied = OSError(errno.EACCES, "access denied")
        access_denied.winerror = 5
        self.assertFalse(_is_lock_contention_error(access_denied, "nt"))

        for winerror in (32, 33):
            contention = OSError(errno.EACCES, "lock conflict")
            contention.winerror = winerror
            self.assertTrue(_is_lock_contention_error(contention, "nt"))

        errno_only = OSError(errno.EAGAIN, "try again")
        self.assertTrue(_is_lock_contention_error(errno_only, "nt"))

    def test_claim_preserves_snapshot_hashes_and_prevents_cleanup(self):
        manifest = self.store.write(self._records(), self.context)
        claim_path = self.store.claim(manifest.dataset_version)
        verified = self.store.verify(manifest)
        self.assertEqual(manifest.content_hash, verified.content_hash)
        self.assertEqual(manifest.parquet_file_hash, verified.parquet_file_hash)
        self.assertTrue(claim_path.is_file())

        old = (datetime.now(timezone.utc) - timedelta(days=3)).timestamp()
        os.utime(self.root / manifest.storage_uri, (old, old))
        os.utime((self.root / manifest.storage_uri.replace(".parquet", ".manifest.json")), (old, old))
        self.assertEqual(0, self.store.cleanup_unclaimed(datetime.now(timezone.utc), timedelta(days=1)))

    def test_cleanup_holding_delete_decision_cannot_delete_a_claim_that_races_after_it(self):
        manifest = self.store.write(self._records(), self.context)
        parquet_path = self.root / manifest.storage_uri
        manifest_path = self.root / manifest.storage_uri.replace(".parquet", ".manifest.json")
        old = (datetime.now(timezone.utc) - timedelta(days=3)).timestamp()
        os.utime(parquet_path, (old, old))
        os.utime(manifest_path, (old, old))
        real_unlink = Path.unlink
        cleanup_ready_to_delete = threading.Event()
        allow_cleanup_delete = threading.Event()
        claim_done = threading.Event()
        claim_errors = []

        def pause_parquet_delete(path, *args, **kwargs):
            if path == parquet_path and not cleanup_ready_to_delete.is_set():
                cleanup_ready_to_delete.set()
                self.assertTrue(allow_cleanup_delete.wait(5))
            return real_unlink(path, *args, **kwargs)

        def claim_snapshot():
            try:
                self.store.claim(manifest.dataset_version)
            except Exception as error:
                claim_errors.append(error)
            finally:
                claim_done.set()

        with patch("pathlib.Path.unlink", new=pause_parquet_delete):
            cleanup = threading.Thread(target=lambda: self.store.cleanup_unclaimed(datetime.now(timezone.utc), timedelta(days=1)))
            cleanup.start()
            self.assertTrue(cleanup_ready_to_delete.wait(5))
            claim = threading.Thread(target=claim_snapshot)
            claim.start()
            try:
                self.assertFalse(claim_done.wait(1))
            finally:
                allow_cleanup_delete.set()
                cleanup.join(5)
                claim.join(5)

        self.assertFalse((self.root / self._claim_path(manifest)).exists())
        self.assertEqual(1, len(claim_errors))
        self.assertIsInstance(claim_errors[0], SnapshotNotFoundError)

    def test_claim_holding_marker_publication_prevents_cleanup_deleting_its_snapshot(self):
        manifest = self.store.write(self._records(), self.context)
        parquet_path = self.root / manifest.storage_uri
        manifest_path = self.root / manifest.storage_uri.replace(".parquet", ".manifest.json")
        old = (datetime.now(timezone.utc) - timedelta(days=3)).timestamp()
        os.utime(parquet_path, (old, old))
        os.utime(manifest_path, (old, old))
        real_replace = os.replace
        claim_ready_to_publish = threading.Event()
        allow_claim_publish = threading.Event()
        cleanup_done = threading.Event()
        cleanup_result = []

        def pause_claim_marker(source, destination):
            if str(destination).endswith(".claim"):
                claim_ready_to_publish.set()
                self.assertTrue(allow_claim_publish.wait(5))
            return real_replace(source, destination)

        with patch("app.data_quality.snapshot_store.os.replace", side_effect=pause_claim_marker):
            claim = threading.Thread(target=lambda: self.store.claim(manifest.dataset_version))
            claim.start()
            self.assertTrue(claim_ready_to_publish.wait(5))
            cleanup = threading.Thread(target=lambda: (cleanup_result.append(
                self.store.cleanup_unclaimed(datetime.now(timezone.utc), timedelta(days=1))), cleanup_done.set()))
            cleanup.start()
            try:
                self.assertFalse(cleanup_done.wait(1))
            finally:
                allow_claim_publish.set()
                claim.join(5)
                cleanup.join(5)

        self.assertTrue((self.root / self._claim_path(manifest)).is_file())
        self.assertTrue(parquet_path.is_file())
        self.assertTrue(manifest_path.is_file())
        self.assertEqual([0], cleanup_result)

    def test_cleanup_removes_only_stale_unclaimed_complete_pairs_and_temp_files(self):
        stale = self.store.write(self._records(), self.context)
        fresh = self.store.write(self._records(), self.context.model_copy(update={"fetched_at": datetime(2026, 2, 5, tzinfo=timezone.utc)}))
        stale_time = (datetime.now(timezone.utc) - timedelta(days=3)).timestamp()
        for suffix in (".parquet", ".manifest.json"):
            path = self.root / stale.storage_uri.replace(".parquet", suffix)
            os.utime(path, (stale_time, stale_time))
        temp = self.root / "snapshots" / "ab" / "interrupted.tmp"
        temp.parent.mkdir(parents=True, exist_ok=True)
        temp.write_text("partial", encoding="utf-8")
        os.utime(temp, (stale_time, stale_time))

        self.assertEqual(3, self.store.cleanup_unclaimed(datetime.now(timezone.utc), timedelta(days=1)))
        self.assertFalse((self.root / stale.storage_uri).exists())
        self.assertFalse((self.root / stale.storage_uri.replace(".parquet", ".manifest.json")).exists())
        self.assertFalse(temp.exists())
        self.assertTrue((self.root / fresh.storage_uri).is_file())
        with self.assertRaises(ValueError):
            self.store.cleanup_unclaimed(datetime.now(timezone.utc), timedelta(0))

    @staticmethod
    def _claim_path(manifest):
        return Path("claims") / manifest.dataset_version[:2] / f"{manifest.dataset_version}.claim"


if __name__ == "__main__":
    unittest.main()
