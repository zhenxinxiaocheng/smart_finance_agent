CREATE TABLE wealth_baseline (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL UNIQUE,
    entered_total_assets NUMERIC NOT NULL,
    baseline_non_investment_balance NUMERIC NOT NULL,
    baseline_at TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE investment_analysis_preference (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    asset_id INTEGER NOT NULL,
    short_min_days INTEGER NOT NULL DEFAULT 5,
    short_max_days INTEGER NOT NULL DEFAULT 20,
    medium_min_days INTEGER NOT NULL DEFAULT 20,
    medium_max_days INTEGER NOT NULL DEFAULT 120,
    long_min_days INTEGER NOT NULL DEFAULT 120,
    long_max_days INTEGER NOT NULL DEFAULT 500,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, asset_id)
);

CREATE TABLE investment_analysis_snapshot (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    asset_id INTEGER NOT NULL,
    rule_version TEXT NOT NULL,
    preference_hash TEXT NOT NULL,
    signal_hash TEXT,
    quote_date TEXT,
    technical_json TEXT,
    fundamental_json TEXT,
    fund_json TEXT,
    backtest_json TEXT,
    source_status_json TEXT,
    ai_explanation TEXT,
    analysis_status TEXT NOT NULL DEFAULT 'READY',
    analyzed_at TEXT NOT NULL,
    ai_updated_at TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, asset_id, rule_version)
);
