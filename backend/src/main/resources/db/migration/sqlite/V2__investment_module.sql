CREATE TABLE investment_product (
    id INTEGER PRIMARY KEY AUTOINCREMENT, product_type TEXT NOT NULL, market TEXT NOT NULL, code TEXT NOT NULL,
    name TEXT NOT NULL, currency TEXT NOT NULL DEFAULT 'CNY', status TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(product_type, market, code)
);
CREATE TABLE investment_account (
    id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL, account_name TEXT NOT NULL,
    account_type TEXT NOT NULL, base_currency TEXT NOT NULL DEFAULT 'CNY',
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_investment_account_user ON investment_account(user_id, deleted);

CREATE TABLE investment_transaction (
    id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL, account_id INTEGER NOT NULL, product_id INTEGER,
    event_type TEXT NOT NULL, trade_date TEXT NOT NULL, settlement_date TEXT, currency TEXT NOT NULL,
    quantity NUMERIC, price NUMERIC, amount NUMERIC, fee NUMERIC NOT NULL DEFAULT 0, factor NUMERIC,
    source TEXT NOT NULL DEFAULT 'MANUAL', external_ref TEXT, reversal_transaction_id INTEGER, note TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, UNIQUE(user_id, source, external_ref)
);
CREATE INDEX idx_investment_transaction_account ON investment_transaction(user_id, account_id, trade_date);
CREATE INDEX idx_investment_transaction_product ON investment_transaction(account_id, product_id, trade_date);

CREATE TABLE investment_cash_balance (
    id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL, account_id INTEGER NOT NULL,
    currency TEXT NOT NULL, balance NUMERIC NOT NULL DEFAULT 0, updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(account_id, currency)
);
CREATE TABLE investment_cash_ledger (
    id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL, account_id INTEGER NOT NULL,
    transaction_id INTEGER NOT NULL UNIQUE, currency TEXT NOT NULL, event_type TEXT NOT NULL, amount NUMERIC NOT NULL,
    external_flow INTEGER NOT NULL DEFAULT 0, occurred_on TEXT NOT NULL, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE investment_position (
    id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL, account_id INTEGER NOT NULL, product_id INTEGER NOT NULL,
    quantity NUMERIC NOT NULL DEFAULT 0, cost_amount NUMERIC NOT NULL DEFAULT 0, average_cost NUMERIC NOT NULL DEFAULT 0,
    realized_pnl NUMERIC NOT NULL DEFAULT 0, latest_price NUMERIC, market_value_cny NUMERIC,
    unrealized_pnl_cny NUMERIC, data_date TEXT, updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(account_id, product_id)
);
CREATE TABLE product_daily_quote (
    id INTEGER PRIMARY KEY AUTOINCREMENT, product_id INTEGER NOT NULL, trade_date TEXT NOT NULL,
    open_price NUMERIC, high_price NUMERIC, low_price NUMERIC, close_price NUMERIC NOT NULL, volume NUMERIC,
    adjust_type TEXT NOT NULL DEFAULT 'NONE', source TEXT NOT NULL, adapter_version TEXT,
    synced_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, UNIQUE(product_id, trade_date, adjust_type)
);
CREATE TABLE investment_import_batch (
    id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL, original_filename TEXT NOT NULL,
    status TEXT NOT NULL, payload TEXT NOT NULL, row_count INTEGER NOT NULL DEFAULT 0,
    error_count INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE investment_sync_batch (
    id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL, job_type TEXT NOT NULL, provider TEXT,
    status TEXT NOT NULL, rows_success INTEGER NOT NULL DEFAULT 0, rows_failed INTEGER NOT NULL DEFAULT 0,
    error_message TEXT, started_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, finished_at TEXT
);
