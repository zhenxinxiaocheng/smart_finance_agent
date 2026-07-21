CREATE TABLE IF NOT EXISTS wealth_baseline (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    entered_total_assets DECIMAL(28,8) NOT NULL,
    baseline_non_investment_balance DECIMAL(28,8) NOT NULL,
    baseline_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_wealth_baseline_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS investment_analysis_preference (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    asset_id BIGINT NOT NULL,
    short_min_days INT NOT NULL DEFAULT 5,
    short_max_days INT NOT NULL DEFAULT 20,
    medium_min_days INT NOT NULL DEFAULT 20,
    medium_max_days INT NOT NULL DEFAULT 120,
    long_min_days INT NOT NULL DEFAULT 120,
    long_max_days INT NOT NULL DEFAULT 500,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_analysis_preference_user_asset (user_id, asset_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS investment_analysis_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    asset_id BIGINT NOT NULL,
    rule_version VARCHAR(40) NOT NULL,
    preference_hash VARCHAR(64) NOT NULL,
    signal_hash VARCHAR(64),
    quote_date DATE,
    technical_json LONGTEXT,
    fundamental_json LONGTEXT,
    fund_json LONGTEXT,
    backtest_json LONGTEXT,
    source_status_json TEXT,
    ai_explanation TEXT,
    analysis_status VARCHAR(30) NOT NULL DEFAULT 'READY',
    analyzed_at DATETIME NOT NULL,
    ai_updated_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_analysis_snapshot_user_asset_rule (user_id, asset_id, rule_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
