CREATE TABLE investment_index_watchlist (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    index_code TEXT NOT NULL,
    display_name TEXT NOT NULL,
    market TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_investment_index_watchlist_user
    ON investment_index_watchlist(user_id, deleted, created_at);
CREATE INDEX idx_investment_index_watchlist_code
    ON investment_index_watchlist(user_id, index_code, deleted);
