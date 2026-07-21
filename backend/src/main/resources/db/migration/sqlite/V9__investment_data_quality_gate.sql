CREATE TABLE investment_data_quality_snapshot (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    dataset_version TEXT NOT NULL,
    product_type TEXT NOT NULL,
    code TEXT NOT NULL,
    market TEXT NOT NULL,
    frequency TEXT NOT NULL,
    adjust_type TEXT NOT NULL,
    provider TEXT NOT NULL,
    adapter_version TEXT NOT NULL,
    quality_config_version TEXT NOT NULL,
    quality_rule_set_version TEXT NOT NULL,
    quality_status TEXT NOT NULL,
    decision TEXT NOT NULL,
    enforcement_mode TEXT NOT NULL,
    requested_start_date TEXT NOT NULL,
    requested_end_date TEXT NOT NULL,
    sample_start_date TEXT NOT NULL,
    sample_end_date TEXT NOT NULL,
    fetched_at TEXT NOT NULL,
    evaluated_at TEXT NOT NULL,
    manifest_json TEXT NOT NULL,
    report_json TEXT NOT NULL,
    secondary_dataset_versions_json TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(dataset_version, quality_config_version)
);

CREATE INDEX idx_data_quality_dataset_version
    ON investment_data_quality_snapshot(dataset_version);
CREATE INDEX idx_data_quality_product_range
    ON investment_data_quality_snapshot(product_type, code, market, requested_start_date, requested_end_date);
CREATE INDEX idx_data_quality_status
    ON investment_data_quality_snapshot(quality_status, evaluated_at);

CREATE TABLE investment_data_quality_issue (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    quality_snapshot_id INTEGER NOT NULL,
    sequence_no INTEGER NOT NULL,
    rule_code TEXT NOT NULL,
    severity TEXT NOT NULL,
    outcome TEXT NOT NULL,
    message TEXT NOT NULL,
    observed_json TEXT,
    expected_json TEXT,
    affected_dates_json TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(quality_snapshot_id, sequence_no),
    FOREIGN KEY(quality_snapshot_id) REFERENCES investment_data_quality_snapshot(id) ON DELETE CASCADE
);

CREATE INDEX idx_data_quality_issue_rule
    ON investment_data_quality_issue(rule_code, severity);

ALTER TABLE investment_analysis_snapshot ADD COLUMN dataset_version TEXT;
ALTER TABLE investment_analysis_snapshot ADD COLUMN quality_rule_set_version TEXT;
ALTER TABLE investment_analysis_snapshot ADD COLUMN strategy_version TEXT;
ALTER TABLE investment_analysis_snapshot ADD COLUMN analysis_cache_key TEXT;
ALTER TABLE investment_analysis_snapshot ADD COLUMN quality_status TEXT;
ALTER TABLE investment_analysis_snapshot ADD COLUMN historical_cache INTEGER NOT NULL DEFAULT 0;

CREATE INDEX idx_analysis_cache_key
    ON investment_analysis_snapshot(user_id, asset_id, analysis_cache_key);
CREATE INDEX idx_analysis_dataset_version
    ON investment_analysis_snapshot(dataset_version);
