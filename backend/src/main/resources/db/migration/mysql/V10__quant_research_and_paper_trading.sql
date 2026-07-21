ALTER TABLE investment_horizon_setting
    ADD COLUMN target_holding_days INT NULL AFTER max_holding_days;

UPDATE investment_horizon_setting
SET target_holding_days = min_holding_days + FLOOR((max_holding_days - min_holding_days) / 2)
WHERE target_holding_days IS NULL;

ALTER TABLE investment_horizon_setting
    MODIFY target_holding_days INT NOT NULL;

CREATE TABLE IF NOT EXISTS quant_feature_set (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    feature_set_version VARCHAR(64) NOT NULL,
    quant_config_version VARCHAR(80) NOT NULL,
    product_type VARCHAR(30) NOT NULL,
    schema_json LONGTEXT NOT NULL,
    artifact_uri VARCHAR(1000),
    artifact_hash VARCHAR(64),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_quant_feature_set_version (feature_set_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS quant_job (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    asset_id BIGINT,
    external_job_id VARCHAR(32) NOT NULL,
    job_type VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL,
    dataset_version VARCHAR(64),
    feature_set_version VARCHAR(64),
    model_version VARCHAR(64),
    strategy_version VARCHAR(80),
    horizon_profile_version VARCHAR(255),
    horizon_code VARCHAR(32),
    horizon_days INT,
    result_json LONGTEXT,
    user_message VARCHAR(500),
    started_at DATETIME,
    finished_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_quant_job_external (external_job_id),
    KEY idx_quant_job_user_status (user_id, status, created_at),
    KEY idx_quant_job_asset (user_id, asset_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS quant_training_run (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    external_job_id VARCHAR(32) NOT NULL,
    dataset_version VARCHAR(64) NOT NULL,
    feature_set_version VARCHAR(64),
    quant_config_version VARCHAR(80) NOT NULL,
    product_type VARCHAR(30) NOT NULL,
    horizon_days INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    metrics_json LONGTEXT,
    started_at DATETIME NOT NULL,
    finished_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_quant_training_external_job (external_job_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS quant_model_version (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    model_version VARCHAR(64) NOT NULL,
    feature_set_version VARCHAR(64) NOT NULL,
    quant_config_version VARCHAR(80) NOT NULL,
    product_type VARCHAR(30) NOT NULL,
    horizon_days INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    artifact_uri VARCHAR(1000),
    artifact_hash VARCHAR(64) NOT NULL,
    metrics_json LONGTEXT NOT NULL,
    trained_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_quant_model_version (model_version),
    KEY idx_quant_model_status (product_type, status, trained_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS quant_strategy_version (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    strategy_version VARCHAR(80) NOT NULL,
    model_version VARCHAR(64) NOT NULL,
    product_type VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL,
    validation_metrics_json LONGTEXT NOT NULL,
    activated_at DATETIME,
    retired_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_quant_strategy_version (strategy_version),
    KEY idx_quant_strategy_status (product_type, status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS quant_prediction (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    asset_id BIGINT NOT NULL,
    dataset_version VARCHAR(64) NOT NULL,
    feature_set_version VARCHAR(64) NOT NULL,
    model_version VARCHAR(64),
    strategy_version VARCHAR(80),
    horizon_profile_version VARCHAR(255),
    horizon_code VARCHAR(32) NOT NULL,
    horizon_days INT NOT NULL,
    as_of_date DATE NOT NULL,
    probability_positive_excess DECIMAL(18,10),
    expected_excess_return DECIMAL(18,10),
    interval_lower DECIMAL(18,10),
    interval_upper DECIMAL(18,10),
    confidence VARCHAR(16) NOT NULL,
    action VARCHAR(24) NOT NULL,
    target_weight DECIMAL(18,10) NOT NULL DEFAULT 0,
    market_regime VARCHAR(30),
    benchmark_code VARCHAR(40),
    round_trip_cost_bps DECIMAL(18,6),
    top_factors_json LONGTEXT,
    risk_flags_json LONGTEXT,
    backtest_summary_json LONGTEXT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_quant_prediction_latest (user_id, asset_id, horizon_code, as_of_date, created_at),
    KEY idx_quant_prediction_model (model_version, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS quant_backtest_run (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    model_version VARCHAR(64),
    strategy_version VARCHAR(80),
    dataset_version VARCHAR(64) NOT NULL,
    horizon_days INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    metrics_json LONGTEXT NOT NULL,
    equity_curve_uri VARCHAR(1000),
    equity_curve_hash VARCHAR(64),
    started_at DATETIME NOT NULL,
    finished_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_quant_backtest_strategy (strategy_version, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS quant_model_monitor (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    model_version VARCHAR(64) NOT NULL,
    monitored_on DATE NOT NULL,
    brier_score DECIMAL(18,10),
    realized_excess_return DECIMAL(18,10),
    drawdown DECIMAL(18,10),
    drift_status VARCHAR(20) NOT NULL,
    evidence_json LONGTEXT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_quant_model_monitor_day (model_version, monitored_on)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS quant_paper_order (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    strategy_version VARCHAR(80) NOT NULL,
    prediction_id BIGINT,
    side VARCHAR(10) NOT NULL,
    order_type VARCHAR(20) NOT NULL,
    quantity DECIMAL(28,10) NOT NULL,
    limit_price DECIMAL(28,10),
    status VARCHAR(20) NOT NULL,
    submitted_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_quant_paper_order_account (user_id, account_id, status, submitted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS quant_paper_fill (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    fill_date DATE NOT NULL,
    quantity DECIMAL(28,10) NOT NULL,
    price DECIMAL(28,10) NOT NULL,
    fee DECIMAL(28,8) NOT NULL DEFAULT 0,
    slippage DECIMAL(28,8) NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_quant_paper_fill_order (order_id, fill_date),
    CONSTRAINT fk_quant_paper_fill_order FOREIGN KEY (order_id)
        REFERENCES quant_paper_order(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
