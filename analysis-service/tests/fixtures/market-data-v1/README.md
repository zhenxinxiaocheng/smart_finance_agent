Synthetic snapshot and data-quality-v5 replay response captured before the v2
snapshot implementation on 2026-10-07. The records and request context come from
SnapshotStoreTest. Replay uses a fixed calendar containing 2026-01-02 and
2026-01-03. No live provider or business data is used.

Keep these files immutable: they prove that old Parquet column order, content
hashes, manifests, returned fields and quality reports survive the upgrade.
