CREATE TABLE IF NOT EXISTS daily_exchange_rate (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    base_currency VARCHAR(3) NOT NULL,
    quote_currency VARCHAR(3) NOT NULL,
    rate_date DATE NOT NULL,
    rate DECIMAL(28,10) NOT NULL,
    source VARCHAR(40) NOT NULL,
    adapter_version VARCHAR(40),
    synced_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_daily_exchange_rate (base_currency, quote_currency, rate_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
