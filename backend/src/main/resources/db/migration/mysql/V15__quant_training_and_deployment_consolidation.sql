ALTER TABLE quant_job
    ADD COLUMN quant_config_version VARCHAR(80),
    ADD COLUMN product_type VARCHAR(30),
    ADD COLUMN metrics_json LONGTEXT;

UPDATE quant_job job
JOIN quant_training_run run ON run.external_job_id = job.external_job_id
SET job.feature_set_version = COALESCE(job.feature_set_version, run.feature_set_version),
    job.quant_config_version = run.quant_config_version,
    job.product_type = run.product_type,
    job.metrics_json = run.metrics_json,
    job.started_at = COALESCE(job.started_at, run.started_at),
    job.finished_at = COALESCE(job.finished_at, run.finished_at);

DROP TABLE quant_training_run;

ALTER TABLE quant_experiment
    ADD COLUMN training_mode VARCHAR(16) NOT NULL DEFAULT 'AUTO',
    ADD COLUMN trigger_reason VARCHAR(40) NOT NULL DEFAULT 'MIGRATION',
    ADD COLUMN parent_model_version VARCHAR(64),
    ADD COLUMN best_model_version VARCHAR(64),
    ADD COLUMN search_summary_json LONGTEXT;

ALTER TABLE quant_strategy_version
    ADD COLUMN user_id BIGINT,
    ADD COLUMN asset_id BIGINT,
    ADD COLUMN model_family VARCHAR(40),
    ADD COLUMN horizon_code VARCHAR(32),
    ADD COLUMN deployment_role VARCHAR(20) NOT NULL DEFAULT 'ARCHIVED';

UPDATE quant_strategy_version
SET deployment_role = CASE
    WHEN status = 'CHAMPION' THEN 'CHAMPION'
    WHEN status IN ('PAPER', 'CHALLENGER') THEN 'CHALLENGER'
    ELSE 'ARCHIVED'
END;

CREATE INDEX idx_quant_strategy_binding
    ON quant_strategy_version(user_id, asset_id, model_family, horizon_code, deployment_role);

DELETE prediction
FROM quant_prediction prediction
LEFT JOIN quant_model_version model ON model.model_version = prediction.model_version
WHERE model.model_version IS NULL OR model.status = 'DRAFT';
