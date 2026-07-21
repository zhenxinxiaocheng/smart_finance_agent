CREATE TABLE investment_asset (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    account_id INTEGER NOT NULL,
    product_id INTEGER NOT NULL,
    quantity NUMERIC,
    average_cost NUMERIC,
    note TEXT,
    current_transaction_id INTEGER,
    sync_status TEXT NOT NULL DEFAULT 'NOT_SYNCED',
    sync_error TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_investment_asset_user ON investment_asset(user_id, deleted, updated_at);
CREATE INDEX idx_investment_asset_product ON investment_asset(user_id, product_id, deleted);
