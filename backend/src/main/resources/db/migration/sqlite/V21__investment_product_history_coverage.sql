ALTER TABLE investment_product ADD COLUMN inception_date TEXT;
ALTER TABLE investment_product ADD COLUMN history_start_date TEXT;
ALTER TABLE investment_product ADD COLUMN history_end_date TEXT;
ALTER TABLE investment_product ADD COLUMN history_coverage_complete INTEGER NOT NULL DEFAULT 0;
