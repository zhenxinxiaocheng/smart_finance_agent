CREATE TABLE IF NOT EXISTS investment_asset (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity DECIMAL(28,10) NULL,
    average_cost DECIMAL(28,10) NULL,
    note VARCHAR(500) NULL,
    current_transaction_id BIGINT NULL,
    sync_status VARCHAR(30) NOT NULL DEFAULT 'NOT_SYNCED',
    sync_error VARCHAR(500) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    KEY idx_investment_asset_user (user_id, deleted, updated_at),
    KEY idx_investment_asset_product (user_id, product_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
