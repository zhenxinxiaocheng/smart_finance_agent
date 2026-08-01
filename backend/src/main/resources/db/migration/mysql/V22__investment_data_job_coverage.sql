ALTER TABLE investment_data_job
    ADD COLUMN requested_start_date DATE NULL AFTER error_message,
    ADD COLUMN sample_start_date DATE NULL AFTER requested_start_date,
    ADD COLUMN sample_end_date DATE NULL AFTER sample_start_date,
    ADD COLUMN coverage_complete TINYINT NULL AFTER sample_end_date,
    ADD COLUMN dataset_version VARCHAR(128) NULL AFTER coverage_complete;
