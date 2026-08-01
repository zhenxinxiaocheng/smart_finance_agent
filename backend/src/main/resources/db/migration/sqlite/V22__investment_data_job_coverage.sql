ALTER TABLE investment_data_job ADD COLUMN requested_start_date TEXT;
ALTER TABLE investment_data_job ADD COLUMN sample_start_date TEXT;
ALTER TABLE investment_data_job ADD COLUMN sample_end_date TEXT;
ALTER TABLE investment_data_job ADD COLUMN coverage_complete INTEGER;
ALTER TABLE investment_data_job ADD COLUMN dataset_version TEXT;
