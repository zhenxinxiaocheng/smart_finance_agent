CREATE TABLE IF NOT EXISTS investment_data_job (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    asset_id INTEGER NOT NULL,
    product_id INTEGER NOT NULL,
    job_type TEXT NOT NULL,
    status TEXT NOT NULL,
    force_refresh INTEGER NOT NULL DEFAULT 0,
    record_count INTEGER NOT NULL DEFAULT 0,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_retry_at TEXT,
    lease_until TEXT,
    lease_token TEXT,
    error_message TEXT,
    started_at TEXT,
    finished_at TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(asset_id, job_type)
);

CREATE INDEX idx_investment_data_job_pending
    ON investment_data_job(status, next_retry_at, created_at);
