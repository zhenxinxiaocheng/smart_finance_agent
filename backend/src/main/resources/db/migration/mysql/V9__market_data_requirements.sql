CREATE TABLE market_data_requirement (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    source_type VARCHAR(24) NOT NULL,
    source_id VARCHAR(80) NOT NULL,
    product_id BIGINT NOT NULL,
    dataset VARCHAR(20) NOT NULL,
    adjust_type VARCHAR(16) NOT NULL,
    frequency VARCHAR(16) NOT NULL DEFAULT 'DAILY',
    start_date DATE NOT NULL,
    target_date DATE NOT NULL,
    follow_latest BOOLEAN NOT NULL DEFAULT FALSE,
    expires_at DATETIME NULL,
    released_at DATETIME NULL,
    prepared_start_date DATE NULL,
    prepared_end_date DATE NULL,
    revision BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_market_requirement(user_id,source_type,source_id,product_id,dataset,adjust_type,frequency),
    KEY idx_market_requirement_product(product_id,dataset,released_at,expires_at),
    KEY idx_market_requirement_owner(user_id,source_type,source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
ALTER TABLE quant_v2_task ADD COLUMN preparation_next_check_at BIGINT NULL;
CREATE INDEX idx_quant_task_preparation ON quant_v2_task(status,preparation_next_check_at,created_at);
