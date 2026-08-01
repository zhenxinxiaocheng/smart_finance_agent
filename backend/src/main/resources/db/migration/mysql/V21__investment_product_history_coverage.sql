ALTER TABLE investment_product
    ADD COLUMN inception_date DATE NULL,
    ADD COLUMN history_start_date DATE NULL,
    ADD COLUMN history_end_date DATE NULL,
    ADD COLUMN history_coverage_complete BOOLEAN NOT NULL DEFAULT FALSE;
