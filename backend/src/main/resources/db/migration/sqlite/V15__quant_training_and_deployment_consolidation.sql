ALTER TABLE quant_job ADD COLUMN quant_config_version TEXT;
ALTER TABLE quant_job ADD COLUMN product_type TEXT;
ALTER TABLE quant_job ADD COLUMN metrics_json TEXT;

UPDATE quant_job
SET feature_set_version = COALESCE(
        feature_set_version,
        (SELECT run.feature_set_version
         FROM quant_training_run run
         WHERE run.external_job_id = quant_job.external_job_id)
    ),
    quant_config_version = (
        SELECT run.quant_config_version
        FROM quant_training_run run
        WHERE run.external_job_id = quant_job.external_job_id
    ),
    product_type = (
        SELECT run.product_type
        FROM quant_training_run run
        WHERE run.external_job_id = quant_job.external_job_id
    ),
    metrics_json = (
        SELECT run.metrics_json
        FROM quant_training_run run
        WHERE run.external_job_id = quant_job.external_job_id
    ),
    started_at = COALESCE(
        started_at,
        (SELECT run.started_at
         FROM quant_training_run run
         WHERE run.external_job_id = quant_job.external_job_id)
    ),
    finished_at = COALESCE(
        finished_at,
        (SELECT run.finished_at
         FROM quant_training_run run
         WHERE run.external_job_id = quant_job.external_job_id)
    )
WHERE EXISTS (
    SELECT 1
    FROM quant_training_run run
    WHERE run.external_job_id = quant_job.external_job_id
);

DROP TABLE quant_training_run;

ALTER TABLE quant_experiment ADD COLUMN training_mode TEXT NOT NULL DEFAULT 'AUTO';
ALTER TABLE quant_experiment ADD COLUMN trigger_reason TEXT NOT NULL DEFAULT 'MIGRATION';
ALTER TABLE quant_experiment ADD COLUMN parent_model_version TEXT;
ALTER TABLE quant_experiment ADD COLUMN best_model_version TEXT;
ALTER TABLE quant_experiment ADD COLUMN search_summary_json TEXT;

ALTER TABLE quant_strategy_version ADD COLUMN user_id INTEGER;
ALTER TABLE quant_strategy_version ADD COLUMN asset_id INTEGER;
ALTER TABLE quant_strategy_version ADD COLUMN model_family TEXT;
ALTER TABLE quant_strategy_version ADD COLUMN horizon_code TEXT;
ALTER TABLE quant_strategy_version
    ADD COLUMN deployment_role TEXT NOT NULL DEFAULT 'ARCHIVED';

UPDATE quant_strategy_version
SET deployment_role = CASE
    WHEN status = 'CHAMPION' THEN 'CHAMPION'
    WHEN status IN ('PAPER', 'CHALLENGER') THEN 'CHALLENGER'
    ELSE 'ARCHIVED'
END;

CREATE INDEX idx_quant_strategy_binding
    ON quant_strategy_version(user_id, asset_id, model_family, horizon_code, deployment_role);

DELETE FROM quant_prediction
WHERE model_version IS NULL
   OR model_version IN (
       SELECT model_version
       FROM quant_model_version
       WHERE status = 'DRAFT'
   );
