ALTER TABLE investment_plan
    ADD COLUMN last_execution_date DATE NULL AFTER next_execution_date,
    ADD COLUMN last_execution_amount DECIMAL(28,8) NULL AFTER last_execution_date,
    ADD COLUMN last_execution_price DECIMAL(28,10) NULL AFTER last_execution_amount,
    ADD COLUMN execution_count INT NOT NULL DEFAULT 0 AFTER last_execution_price,
    ADD COLUMN last_execution_status VARCHAR(30) NOT NULL DEFAULT 'WAITING' AFTER execution_count,
    ADD COLUMN last_execution_message VARCHAR(500) NULL AFTER last_execution_status;

CREATE INDEX idx_investment_plan_due
    ON investment_plan(enabled, next_execution_date);
