CREATE TABLE IF NOT EXISTS quant_benchmark_snapshot (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    snapshot_version TEXT NOT NULL UNIQUE,
    benchmark_profile_id INTEGER NOT NULL,
    benchmark_code TEXT NOT NULL,
    source_uri TEXT NOT NULL,
    source_version TEXT NOT NULL,
    provider TEXT,
    adapter_version TEXT,
    currency TEXT NOT NULL,
    fx_rule TEXT,
    effective_from TEXT NOT NULL,
    effective_to TEXT,
    sample_start_date TEXT NOT NULL,
    sample_end_date TEXT NOT NULL,
    fetched_at TEXT NOT NULL,
    records_json TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_quant_benchmark_snapshot_lookup
    ON quant_benchmark_snapshot(
        benchmark_profile_id,
        sample_start_date,
        sample_end_date,
        fetched_at
    );
