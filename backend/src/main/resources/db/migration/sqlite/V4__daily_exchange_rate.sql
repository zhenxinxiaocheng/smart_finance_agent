CREATE TABLE daily_exchange_rate (
    id INTEGER PRIMARY KEY AUTOINCREMENT, base_currency TEXT NOT NULL, quote_currency TEXT NOT NULL,
    rate_date TEXT NOT NULL, rate NUMERIC NOT NULL, source TEXT NOT NULL, adapter_version TEXT,
    synced_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, UNIQUE(base_currency, quote_currency, rate_date)
);
