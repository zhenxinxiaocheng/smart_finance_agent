CREATE TABLE IF NOT EXISTS investment_data_quality_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    dataset_version VARCHAR(64) NOT NULL,
    product_type VARCHAR(30) NOT NULL,
    code VARCHAR(40) NOT NULL,
    market VARCHAR(20) NOT NULL,
    frequency VARCHAR(16) NOT NULL,
    adjust_type VARCHAR(16) NOT NULL,
    provider VARCHAR(80) NOT NULL,
    adapter_version VARCHAR(80) NOT NULL,
    quality_config_version VARCHAR(80) NOT NULL,
    quality_rule_set_version VARCHAR(80) NOT NULL,
    quality_status VARCHAR(16) NOT NULL,
    decision VARCHAR(16) NOT NULL,
    enforcement_mode VARCHAR(16) NOT NULL,
    requested_start_date DATE NOT NULL,
    requested_end_date DATE NOT NULL,
    sample_start_date DATE NOT NULL,
    sample_end_date DATE NOT NULL,
    fetched_at DATETIME NOT NULL,
    evaluated_at DATETIME NOT NULL,
    manifest_json LONGTEXT NOT NULL,
    report_json LONGTEXT NOT NULL,
    secondary_dataset_versions_json LONGTEXT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_data_quality_dataset_rule (dataset_version, quality_config_version),
    KEY idx_data_quality_dataset_version (dataset_version),
    KEY idx_data_quality_product_range (product_type, code, market, requested_start_date, requested_end_date),
    KEY idx_data_quality_status (quality_status, evaluated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS investment_data_quality_issue (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    quality_snapshot_id BIGINT NOT NULL,
    sequence_no INT NOT NULL,
    rule_code VARCHAR(80) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    outcome VARCHAR(24) NOT NULL,
    message TEXT NOT NULL,
    observed_json LONGTEXT,
    expected_json LONGTEXT,
    affected_dates_json LONGTEXT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_data_quality_issue_order (quality_snapshot_id, sequence_no),
    KEY idx_data_quality_issue_rule (rule_code, severity),
    CONSTRAINT fk_data_quality_issue_snapshot FOREIGN KEY (quality_snapshot_id)
        REFERENCES investment_data_quality_snapshot(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE investment_analysis_snapshot
    ADD COLUMN dataset_version VARCHAR(64),
    ADD COLUMN quality_rule_set_version VARCHAR(80),
    ADD COLUMN strategy_version VARCHAR(80),
    ADD COLUMN analysis_cache_key VARCHAR(64),
    ADD COLUMN quality_status VARCHAR(16),
    ADD COLUMN historical_cache TINYINT(1) NOT NULL DEFAULT 0,
    ADD KEY idx_analysis_cache_key (user_id, asset_id, analysis_cache_key),
    ADD KEY idx_analysis_dataset_version (dataset_version);
