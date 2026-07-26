CREATE TABLE IF NOT EXISTS quant_benchmark_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    snapshot_version CHAR(64) NOT NULL,
    benchmark_profile_id BIGINT NOT NULL,
    benchmark_code VARCHAR(80) NOT NULL,
    source_uri VARCHAR(1000) NOT NULL,
    source_version VARCHAR(80) NOT NULL,
    provider VARCHAR(40),
    adapter_version VARCHAR(40),
    currency VARCHAR(10) NOT NULL,
    fx_rule VARCHAR(40),
    effective_from DATE NOT NULL,
    effective_to DATE,
    sample_start_date DATE NOT NULL,
    sample_end_date DATE NOT NULL,
    fetched_at DATETIME NOT NULL,
    records_json LONGTEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_quant_benchmark_snapshot_version (snapshot_version),
    KEY idx_quant_benchmark_snapshot_lookup
        (benchmark_profile_id, sample_start_date, sample_end_date, fetched_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
