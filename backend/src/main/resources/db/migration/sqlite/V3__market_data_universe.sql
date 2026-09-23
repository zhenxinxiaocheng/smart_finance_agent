ALTER TABLE investment_product ADD COLUMN exchange_code TEXT NULL;
ALTER TABLE investment_product ADD COLUMN listing_date TEXT NULL;
ALTER TABLE investment_product ADD COLUMN delisting_date TEXT NULL;
ALTER TABLE investment_product ADD COLUMN source_metadata TEXT NULL;

CREATE INDEX idx_market_product_filter ON investment_product (market, product_type, status, id);
CREATE INDEX idx_daily_quote_date ON product_daily_quote (trade_date, product_id, adjust_type);
CREATE INDEX idx_daily_quote_synced ON product_daily_quote (synced_at, product_id);

CREATE TABLE market_data_job (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    product_id INTEGER NOT NULL,
    job_type TEXT NOT NULL,
    status TEXT NOT NULL,
    start_date TEXT NOT NULL,
    target_date TEXT NOT NULL,
    checkpoint_date TEXT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_retry_at TEXT NULL,
    lease_until TEXT NULL,
    last_error TEXT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE (product_id, job_type)
);
CREATE INDEX idx_market_data_job_queue ON market_data_job (status, next_retry_at, updated_at);

CREATE TABLE market_catalog_state (
    market TEXT NOT NULL PRIMARY KEY,
    status TEXT NOT NULL,
    product_count INTEGER NOT NULL DEFAULT 0,
    last_synced_at TEXT NULL,
    last_error TEXT NULL
);

CREATE TABLE quant_universe_snapshot (
    id TEXT NOT NULL PRIMARY KEY,
    user_id INTEGER NOT NULL,
    universe_id TEXT NOT NULL,
    revision INTEGER NOT NULL,
    as_of_date TEXT NOT NULL,
    capability TEXT NOT NULL,
    source TEXT NULL,
    created_at TEXT NOT NULL,
    UNIQUE (universe_id, revision)
);
CREATE INDEX idx_quant_universe_asof ON quant_universe_snapshot (user_id, universe_id, as_of_date);

CREATE TABLE quant_universe_member (
    snapshot_id TEXT NOT NULL,
    product_id INTEGER NOT NULL,
    PRIMARY KEY (snapshot_id, product_id)
);
CREATE INDEX idx_quant_universe_member_product ON quant_universe_member (product_id);
