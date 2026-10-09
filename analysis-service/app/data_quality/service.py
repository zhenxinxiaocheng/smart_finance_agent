from __future__ import annotations

from collections.abc import Callable
from datetime import date, datetime, timedelta
from typing import Any

from .config import load_data_quality_config
from .engine import DataQualityEngine
from .models import AdjustType, DataQualityConfig, DataQualityManifest, DataSnapshotContext, ProductType
from .snapshot_store import SnapshotStore
from ..quote_availability import quote_availability


CalendarLoader = Callable[[int], list[str]]
ConfigLoader = Callable[[str], DataQualityConfig]
Clock = Callable[[], datetime]


class DataQualityService:
    def __init__(
        self,
        registry: Any,
        calendar_loader: CalendarLoader,
        clock: Clock,
        config_loader: ConfigLoader = load_data_quality_config,
    ):
        self._registry = registry
        self._calendar_loader = calendar_loader
        self._clock = clock
        self._config_loader = config_loader

    def validate(
        self,
        *,
        product_type: ProductType,
        code: str,
        market: str,
        frequency: str,
        adjust_type: AdjustType,
        start_date: date,
        end_date: date,
        quality_config_version: str,
        fund_category: str | None = None,
    ) -> dict[str, Any]:
        if frequency != "DAY":
            raise ValueError("frequency must be DAY")
        if start_date > end_date:
            raise ValueError("startDate must not be after endDate")
        config = self._config_loader(quality_config_version)
        batches, provider_warnings = self._registry.daily_quality_batches(
            code=code,
            market=market,
            product_type=product_type,
            start_date=start_date,
            end_date=end_date,
            adjust_type=adjust_type,
            clock=self._clock,
            provider_names=config.providers.enabled if config.providers is not None else None,
            maximum_batches=config.providers.maximum_batches if config.providers is not None else 2,
        )
        store = SnapshotStore(config.storage_root, config.schema_version)
        manifests: list[DataQualityManifest] = []
        rows_by_version: dict[str, list[dict[str, Any]]] = {}
        for batch in batches:
            manifest = store.write(batch.to_snapshot_rows(config.schema_version), batch.snapshot_context(start_date, end_date))
            manifest = store.read_manifest(manifest.dataset_version)
            manifests.append(manifest)
            rows_by_version[manifest.dataset_version] = store.read(manifest.dataset_version)

        primary = manifests[0]
        secondary_rows = rows_by_version[manifests[1].dataset_version] if len(manifests) > 1 else None
        expected_dates = self._expected_dates(config, primary, fund_category)
        report = DataQualityEngine(config, lambda: primary.fetched_at).evaluate(
            primary,
            rows_by_version[primary.dataset_version],
            expected_dates,
            integrity_verified=True,
            secondary_records=secondary_rows,
        )
        return _response(primary, manifests[1:], report, rows_by_version[primary.dataset_version], provider_warnings)

    def replay(
        self,
        *,
        dataset_version: str,
        quality_config_version: str,
        secondary_dataset_version: str | None = None,
        fund_category: str | None = None,
        continuation_dataset_versions: list[str] | None = None,
        continuation_secondary_versions: list[str] | None = None,
        start_date: date | None = None,
        end_date: date | None = None,
    ) -> dict[str, Any]:
        config = self._config_loader(quality_config_version)
        store = SnapshotStore(config.storage_root, config.schema_version)
        prepared_versions = [dataset_version, *(continuation_dataset_versions or [])]
        if continuation_dataset_versions or start_date is not None or end_date is not None:
            primary = self._prepared_window(store, prepared_versions, start_date, end_date)
            dataset_version = primary.dataset_version
        else:
            primary = store.read_manifest(dataset_version)
        primary_rows = store.read(dataset_version)
        secondary_manifests: list[DataQualityManifest] = []
        secondary_rows = None
        if secondary_dataset_version is not None:
            secondary = self._prepared_window(store,
                [secondary_dataset_version, *(continuation_secondary_versions or [])], start_date, end_date) \
                if continuation_dataset_versions or start_date is not None else store.read_manifest(secondary_dataset_version)
            _require_replay_compatible(primary, secondary)
            secondary_manifests.append(secondary)
            secondary_rows = store.read(secondary.dataset_version)
        expected_dates = self._expected_dates(config, primary, fund_category)
        report = DataQualityEngine(config, lambda: primary.fetched_at).evaluate(
            primary,
            primary_rows,
            expected_dates,
            integrity_verified=True,
            secondary_records=secondary_rows,
        )
        response = _response(primary, secondary_manifests, report, primary_rows, ())
        if continuation_dataset_versions or start_date is not None:
            response["preparedDatasetVersions"] = prepared_versions
        return response

    def _prepared_window(self, store, versions, start, end):
        """Rebuild a real quality input from intact prepared snapshots, without provider access."""
        if start is None or end is None or start > end:
            raise ValueError("prepared replay requires an ordered start/end window")
        manifests = [store.read_manifest(version) for version in versions]
        primary = manifests[0]
        fields = ("product_type", "code", "market", "frequency", "adjust_type", "provider", "adapter_version", "schema_version")
        if any(any(getattr(item, field) != getattr(primary, field) for field in fields) for item in manifests):
            raise ValueError("prepared datasets have incompatible identity or price basis")
        cursor = start
        for item in sorted(manifests, key=lambda item: item.requested_start_date):
            if item.requested_end_date < cursor:
                continue
            if item.requested_start_date > cursor:
                raise ValueError("prepared replay contains an unprepared window")
            cursor = max(cursor, item.requested_end_date + timedelta(days=1))
        if cursor <= end:
            raise ValueError("prepared replay contains an unprepared tail")
        rows = {}
        for item in sorted(manifests, key=lambda item: item.fetched_at):
            for row in store.read(item.dataset_version):
                day = date.fromisoformat(row["data_date"])
                if not start <= day <= end:
                    continue
                previous = rows.get(day)
                if previous is not None and primary.adjust_type is not AdjustType.NONE \
                        and previous.get("close") != row.get("close"):
                    raise ValueError("prepared adjusted datasets disagree on their overlapping price basis")
                rows[day] = row
        context = DataSnapshotContext(product_type=primary.product_type, code=primary.code, market=primary.market,
            frequency=primary.frequency, adjust_type=primary.adjust_type, provider=primary.provider,
            adapter_version=primary.adapter_version, requested_start_date=start, requested_end_date=end,
            fetched_at=max(item.fetched_at for item in manifests))
        return store.write([rows[day] for day in sorted(rows)], context)

    def claim(self, *, dataset_version: str, quality_config_version: str) -> dict[str, str]:
        config = self._config_loader(quality_config_version)
        marker = SnapshotStore(config.storage_root, config.schema_version).claim(dataset_version)
        return {"datasetVersion": dataset_version, "claimMarker": marker.relative_to(config.storage_root).as_posix()}

    def _calendar_dates(self, start_date: date, end_date: date) -> tuple[date, ...]:
        values: set[date] = set()
        for year in range(start_date.year, end_date.year + 1):
            values.update(date.fromisoformat(value) for value in self._calendar_loader(year))
        return tuple(sorted(value for value in values if start_date <= value <= end_date))

    def _expected_dates(self, config, manifest, fund_category):
        if not config.publication_aware_rules:
            return self._calendar_dates(manifest.requested_start_date, manifest.requested_end_date)
        result = quote_availability.resolve(product_type=manifest.product_type.value, market=manifest.market,
                                            fund_category=fund_category, at=manifest.fetched_at,
                                            start=manifest.requested_start_date, end=manifest.requested_end_date)
        return tuple(date.fromisoformat(day) for day in result['expectedDates'])


def _response(primary, secondary, report, records, warnings) -> dict[str, Any]:
    return {
        "datasetVersion": primary.dataset_version,
        "manifest": primary.model_dump(mode="json", by_alias=True),
        "secondaryDatasetVersions": [item.dataset_version for item in secondary],
        "secondaryManifests": [item.model_dump(mode="json", by_alias=True) for item in secondary],
        "qualityReport": report.model_dump(mode="json", by_alias=True),
        "records": records,
        "warnings": list(warnings),
    }


def _require_replay_compatible(primary: DataQualityManifest, secondary: DataQualityManifest) -> None:
    fields = ("product_type", "code", "market", "frequency", "adjust_type",
              "requested_start_date", "requested_end_date")
    mismatched = [field for field in fields if getattr(primary, field) != getattr(secondary, field)]
    if mismatched:
        raise ValueError(f"secondary dataset is incompatible: {','.join(mismatched)}")
    if primary.provider == secondary.provider:
        raise ValueError("secondary dataset provider must differ from primary provider")
