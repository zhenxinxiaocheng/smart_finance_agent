ALTER TABLE quant_job
    ADD COLUMN estimated_duration_seconds BIGINT NULL AFTER horizon_days;
