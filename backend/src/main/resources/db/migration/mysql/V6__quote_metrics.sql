ALTER TABLE product_daily_quote
    ADD COLUMN previous_close DECIMAL(28,10) NULL AFTER close_price,
    ADD COLUMN change_amount DECIMAL(28,10) NULL AFTER previous_close,
    ADD COLUMN change_percent DECIMAL(18,8) NULL AFTER change_amount,
    ADD COLUMN amount DECIMAL(28,8) NULL AFTER volume,
    ADD COLUMN turnover_rate DECIMAL(18,8) NULL AFTER amount,
    ADD COLUMN volume_ratio DECIMAL(18,8) NULL AFTER turnover_rate,
    ADD COLUMN amplitude DECIMAL(18,8) NULL AFTER volume_ratio;
