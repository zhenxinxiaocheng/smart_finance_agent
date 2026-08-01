ALTER TABLE quant_job
    ADD COLUMN execution_status VARCHAR(20) NULL AFTER status,
    ADD COLUMN training_outcome VARCHAR(24) NULL AFTER execution_status,
    ADD COLUMN deployment_status VARCHAR(24) NULL AFTER training_outcome,
    ADD COLUMN economic_role VARCHAR(32) NULL AFTER deployment_status,
    ADD COLUMN optimization_study_id VARCHAR(120) NULL AFTER economic_role,
    ADD COLUMN optimization_generation INT NULL AFTER optimization_study_id,
    ADD COLUMN baseline_comparison_json JSON NULL AFTER optimization_generation,
    ADD COLUMN diagnostics_json JSON NULL AFTER baseline_comparison_json;

ALTER TABLE quant_experiment
    ADD COLUMN execution_status VARCHAR(20) NULL AFTER status,
    ADD COLUMN training_outcome VARCHAR(24) NULL AFTER execution_status,
    ADD COLUMN deployment_status VARCHAR(24) NULL AFTER training_outcome,
    ADD COLUMN economic_role VARCHAR(32) NULL AFTER deployment_status,
    ADD COLUMN optimization_study_id VARCHAR(120) NULL AFTER economic_role,
    ADD COLUMN optimization_generation INT NULL AFTER optimization_study_id,
    ADD COLUMN baseline_comparison_json JSON NULL AFTER optimization_generation;

ALTER TABLE quant_model_version
    ADD COLUMN deployment_status VARCHAR(24) NULL AFTER status,
    ADD COLUMN economic_role VARCHAR(32) NULL AFTER deployment_status,
    ADD COLUMN optimization_study_id VARCHAR(120) NULL AFTER economic_role,
    ADD COLUMN optimization_generation INT NULL AFTER optimization_study_id,
    ADD COLUMN baseline_comparison_json JSON NULL AFTER optimization_generation;
