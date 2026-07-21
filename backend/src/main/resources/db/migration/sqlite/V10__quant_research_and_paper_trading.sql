ALTER TABLE investment_horizon_setting ADD COLUMN target_holding_days INTEGER;
UPDATE investment_horizon_setting
SET target_holding_days = min_holding_days + CAST((max_holding_days - min_holding_days) / 2 AS INTEGER)
WHERE target_holding_days IS NULL;

CREATE TABLE IF NOT EXISTS quant_feature_set (
    id INTEGER PRIMARY KEY AUTOINCREMENT, feature_set_version TEXT NOT NULL UNIQUE,
    quant_config_version TEXT NOT NULL, product_type TEXT NOT NULL, schema_json TEXT NOT NULL,
    artifact_uri TEXT, artifact_hash TEXT, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS quant_job (
    id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL, asset_id INTEGER,
    external_job_id TEXT NOT NULL UNIQUE, job_type TEXT NOT NULL, status TEXT NOT NULL,
    dataset_version TEXT, feature_set_version TEXT, model_version TEXT, strategy_version TEXT,
    horizon_profile_version TEXT, horizon_code TEXT, horizon_days INTEGER, result_json TEXT,
    user_message TEXT, started_at TEXT, finished_at TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_quant_job_user_status ON quant_job(user_id, status, created_at);
CREATE INDEX idx_quant_job_asset ON quant_job(user_id, asset_id, created_at);
CREATE TABLE IF NOT EXISTS quant_training_run (
    id INTEGER PRIMARY KEY AUTOINCREMENT, external_job_id TEXT NOT NULL UNIQUE, dataset_version TEXT NOT NULL,
    feature_set_version TEXT, quant_config_version TEXT NOT NULL, product_type TEXT NOT NULL,
    horizon_days INTEGER NOT NULL, status TEXT NOT NULL, metrics_json TEXT,
    started_at TEXT NOT NULL, finished_at TEXT, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS quant_model_version (
    id INTEGER PRIMARY KEY AUTOINCREMENT, model_version TEXT NOT NULL UNIQUE, feature_set_version TEXT NOT NULL,
    quant_config_version TEXT NOT NULL, product_type TEXT NOT NULL, horizon_days INTEGER NOT NULL,
    status TEXT NOT NULL, artifact_uri TEXT, artifact_hash TEXT NOT NULL, metrics_json TEXT NOT NULL,
    trained_at TEXT NOT NULL, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS quant_strategy_version (
    id INTEGER PRIMARY KEY AUTOINCREMENT, strategy_version TEXT NOT NULL UNIQUE, model_version TEXT NOT NULL,
    product_type TEXT NOT NULL, status TEXT NOT NULL, validation_metrics_json TEXT NOT NULL,
    activated_at TEXT, retired_at TEXT, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS quant_prediction (
    id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL, asset_id INTEGER NOT NULL,
    dataset_version TEXT NOT NULL, feature_set_version TEXT NOT NULL, model_version TEXT, strategy_version TEXT,
    horizon_profile_version TEXT, horizon_code TEXT NOT NULL, horizon_days INTEGER NOT NULL, as_of_date TEXT NOT NULL,
    probability_positive_excess NUMERIC, expected_excess_return NUMERIC, interval_lower NUMERIC, interval_upper NUMERIC,
    confidence TEXT NOT NULL, action TEXT NOT NULL, target_weight NUMERIC NOT NULL DEFAULT 0,
    market_regime TEXT, benchmark_code TEXT, round_trip_cost_bps NUMERIC,
    top_factors_json TEXT, risk_flags_json TEXT, backtest_summary_json TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_quant_prediction_latest ON quant_prediction(user_id, asset_id, horizon_code, as_of_date, created_at);
CREATE TABLE IF NOT EXISTS quant_backtest_run (
    id INTEGER PRIMARY KEY AUTOINCREMENT, model_version TEXT, strategy_version TEXT, dataset_version TEXT NOT NULL,
    horizon_days INTEGER NOT NULL, status TEXT NOT NULL, metrics_json TEXT NOT NULL,
    equity_curve_uri TEXT, equity_curve_hash TEXT, started_at TEXT NOT NULL, finished_at TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS quant_model_monitor (
    id INTEGER PRIMARY KEY AUTOINCREMENT, model_version TEXT NOT NULL, monitored_on TEXT NOT NULL,
    brier_score NUMERIC, realized_excess_return NUMERIC, drawdown NUMERIC, drift_status TEXT NOT NULL,
    evidence_json TEXT, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(model_version, monitored_on)
);
CREATE TABLE IF NOT EXISTS quant_paper_order (
    id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL, account_id INTEGER NOT NULL,
    product_id INTEGER NOT NULL, strategy_version TEXT NOT NULL, prediction_id INTEGER,
    side TEXT NOT NULL, order_type TEXT NOT NULL, quantity NUMERIC NOT NULL, limit_price NUMERIC,
    status TEXT NOT NULL, submitted_at TEXT NOT NULL, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS quant_paper_fill (
    id INTEGER PRIMARY KEY AUTOINCREMENT, order_id INTEGER NOT NULL, fill_date TEXT NOT NULL,
    quantity NUMERIC NOT NULL, price NUMERIC NOT NULL, fee NUMERIC NOT NULL DEFAULT 0,
    slippage NUMERIC NOT NULL DEFAULT 0, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(order_id) REFERENCES quant_paper_order(id)
);
