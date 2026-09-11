CREATE TABLE investment_index_display_order (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    index_code VARCHAR(64) NOT NULL,
    sort_order INT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_investment_index_display_order (user_id, index_code),
    INDEX idx_investment_index_display_order_user (user_id, sort_order)
);
