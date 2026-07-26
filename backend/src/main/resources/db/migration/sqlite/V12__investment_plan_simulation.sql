ALTER TABLE investment_plan ADD COLUMN last_execution_date TEXT;
ALTER TABLE investment_plan ADD COLUMN last_execution_amount NUMERIC;
ALTER TABLE investment_plan ADD COLUMN last_execution_price NUMERIC;
ALTER TABLE investment_plan ADD COLUMN execution_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE investment_plan ADD COLUMN last_execution_status TEXT NOT NULL DEFAULT 'WAITING';
ALTER TABLE investment_plan ADD COLUMN last_execution_message TEXT;

CREATE INDEX idx_investment_plan_due
    ON investment_plan(enabled, next_execution_date);
