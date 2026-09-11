CREATE TABLE investment_index_watchlist (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    index_code VARCHAR(64) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    market VARCHAR(20) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    INDEX idx_investment_index_watchlist_user (user_id, deleted, created_at),
    INDEX idx_investment_index_watchlist_code (user_id, index_code, deleted)
);
