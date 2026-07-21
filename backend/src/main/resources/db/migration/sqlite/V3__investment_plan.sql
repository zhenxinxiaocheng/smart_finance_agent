CREATE TABLE investment_plan (
    id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL, account_id INTEGER NOT NULL,
    product_id INTEGER NOT NULL, amount NUMERIC NOT NULL, currency TEXT NOT NULL, frequency TEXT NOT NULL,
    execution_day INTEGER NOT NULL, next_execution_date TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_investment_plan_user ON investment_plan(user_id, enabled, next_execution_date);
