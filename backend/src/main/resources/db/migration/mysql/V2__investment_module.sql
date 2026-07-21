CREATE TABLE IF NOT EXISTS investment_product (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    product_type VARCHAR(30) NOT NULL,
    market VARCHAR(30) NOT NULL,
    code VARCHAR(40) NOT NULL,
    name VARCHAR(160) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'CNY',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_investment_product (product_type, market, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS investment_account (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    account_name VARCHAR(120) NOT NULL,
    account_type VARCHAR(30) NOT NULL,
    base_currency VARCHAR(3) NOT NULL DEFAULT 'CNY',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    KEY idx_investment_account_user (user_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS investment_transaction (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    product_id BIGINT NULL,
    event_type VARCHAR(30) NOT NULL,
    trade_date DATE NOT NULL,
    settlement_date DATE NULL,
    currency VARCHAR(3) NOT NULL,
    quantity DECIMAL(28,10) NULL,
    price DECIMAL(28,10) NULL,
    amount DECIMAL(28,8) NULL,
    fee DECIMAL(28,8) NOT NULL DEFAULT 0,
    factor DECIMAL(28,10) NULL,
    source VARCHAR(30) NOT NULL DEFAULT 'MANUAL',
    external_ref VARCHAR(120) NULL,
    reversal_transaction_id BIGINT NULL,
    note VARCHAR(500) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_investment_transaction_ref (user_id, source, external_ref),
    KEY idx_investment_transaction_account (user_id, account_id, trade_date),
    KEY idx_investment_transaction_product (account_id, product_id, trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS investment_cash_balance (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    balance DECIMAL(28,8) NOT NULL DEFAULT 0,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_investment_cash_balance (account_id, currency),
    KEY idx_investment_cash_user (user_id, account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS investment_cash_ledger (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    transaction_id BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    event_type VARCHAR(30) NOT NULL,
    amount DECIMAL(28,8) NOT NULL,
    external_flow TINYINT NOT NULL DEFAULT 0,
    occurred_on DATE NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_investment_cash_transaction (transaction_id),
    KEY idx_investment_cash_ledger_account (user_id, account_id, occurred_on)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS investment_position (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity DECIMAL(28,10) NOT NULL DEFAULT 0,
    cost_amount DECIMAL(28,8) NOT NULL DEFAULT 0,
    average_cost DECIMAL(28,10) NOT NULL DEFAULT 0,
    realized_pnl DECIMAL(28,8) NOT NULL DEFAULT 0,
    latest_price DECIMAL(28,10) NULL,
    market_value_cny DECIMAL(28,8) NULL,
    unrealized_pnl_cny DECIMAL(28,8) NULL,
    data_date DATE NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_investment_position (account_id, product_id),
    KEY idx_investment_position_user (user_id, account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS product_daily_quote (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT NOT NULL,
    trade_date DATE NOT NULL,
    open_price DECIMAL(28,10) NULL,
    high_price DECIMAL(28,10) NULL,
    low_price DECIMAL(28,10) NULL,
    close_price DECIMAL(28,10) NOT NULL,
    volume DECIMAL(28,8) NULL,
    adjust_type VARCHAR(20) NOT NULL DEFAULT 'NONE',
    source VARCHAR(40) NOT NULL,
    adapter_version VARCHAR(40) NULL,
    synced_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_product_daily_quote (product_id, trade_date, adjust_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS investment_import_batch (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    status VARCHAR(30) NOT NULL,
    payload LONGTEXT NOT NULL,
    row_count INT NOT NULL DEFAULT 0,
    error_count INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_investment_import_user (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS investment_sync_batch (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    job_type VARCHAR(40) NOT NULL,
    provider VARCHAR(40) NULL,
    status VARCHAR(30) NOT NULL,
    rows_success INT NOT NULL DEFAULT 0,
    rows_failed INT NOT NULL DEFAULT 0,
    error_message VARCHAR(500) NULL,
    started_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at DATETIME NULL,
    KEY idx_investment_sync_user (user_id, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
