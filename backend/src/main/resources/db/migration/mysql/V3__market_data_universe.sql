ALTER TABLE investment_product
    ADD COLUMN exchange_code VARCHAR(30) NULL,
    ADD COLUMN listing_date DATE NULL,
    ADD COLUMN delisting_date DATE NULL,
    ADD COLUMN source_metadata VARCHAR(500) NULL;

CREATE INDEX idx_market_product_filter ON investment_product (market, product_type, status, id);
CREATE INDEX idx_daily_quote_date ON product_daily_quote (trade_date, product_id, adjust_type);
CREATE INDEX idx_daily_quote_synced ON product_daily_quote (synced_at, product_id);

CREATE TABLE market_data_job (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT NOT NULL,
    job_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    start_date DATE NOT NULL,
    target_date DATE NOT NULL,
    checkpoint_date DATE NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME NULL,
    lease_until DATETIME NULL,
    last_error VARCHAR(500) NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_market_data_job (product_id, job_type),
    KEY idx_market_data_job_queue (status, next_retry_at, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE market_catalog_state (
    market VARCHAR(20) NOT NULL PRIMARY KEY,
    status VARCHAR(20) NOT NULL,
    product_count INT NOT NULL DEFAULT 0,
    last_synced_at DATETIME NULL,
    last_error VARCHAR(500) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE quant_universe_snapshot (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    universe_id VARCHAR(36) NOT NULL,
    revision INT NOT NULL,
    as_of_date DATE NOT NULL,
    capability VARCHAR(24) NOT NULL,
    source VARCHAR(80) NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_quant_universe_revision (universe_id, revision),
    KEY idx_quant_universe_asof (user_id, universe_id, as_of_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE quant_universe_member (
    snapshot_id VARCHAR(36) NOT NULL,
    product_id BIGINT NOT NULL,
    PRIMARY KEY (snapshot_id, product_id),
    KEY idx_quant_universe_member_product (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
