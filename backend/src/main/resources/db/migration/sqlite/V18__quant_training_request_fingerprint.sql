ALTER TABLE quant_job ADD COLUMN request_fingerprint TEXT;

CREATE UNIQUE INDEX uk_quant_job_training_request
    ON quant_job(user_id, asset_id, job_type, request_fingerprint)
    WHERE request_fingerprint IS NOT NULL;
