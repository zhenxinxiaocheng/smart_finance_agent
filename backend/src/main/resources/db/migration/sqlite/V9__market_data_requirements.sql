CREATE TABLE market_data_requirement (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    source_type TEXT NOT NULL,
    source_id TEXT NOT NULL,
    product_id INTEGER NOT NULL,
    dataset TEXT NOT NULL,
    adjust_type TEXT NOT NULL,
    frequency TEXT NOT NULL DEFAULT 'DAILY',
    start_date TEXT NOT NULL,
    target_date TEXT NOT NULL,
    follow_latest INTEGER NOT NULL DEFAULT 0,
    expires_at TEXT NULL,
    released_at TEXT NULL,
    prepared_start_date TEXT NULL,
    prepared_end_date TEXT NULL,
    revision INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE(user_id,source_type,source_id,product_id,dataset,adjust_type,frequency)
);
CREATE INDEX idx_market_requirement_product ON market_data_requirement(product_id,dataset,released_at,expires_at);
CREATE INDEX idx_market_requirement_owner ON market_data_requirement(user_id,source_type,source_id);
ALTER TABLE quant_v2_task ADD COLUMN preparation_next_check_at BIGINT NULL;
CREATE INDEX idx_quant_task_preparation ON quant_v2_task(status,preparation_next_check_at,created_at);
