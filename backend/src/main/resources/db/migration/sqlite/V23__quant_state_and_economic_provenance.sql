ALTER TABLE quant_job ADD COLUMN execution_status TEXT;
ALTER TABLE quant_job ADD COLUMN training_outcome TEXT;
ALTER TABLE quant_job ADD COLUMN deployment_status TEXT;
ALTER TABLE quant_job ADD COLUMN economic_role TEXT;
ALTER TABLE quant_job ADD COLUMN optimization_study_id TEXT;
ALTER TABLE quant_job ADD COLUMN optimization_generation INTEGER;
ALTER TABLE quant_job ADD COLUMN baseline_comparison_json TEXT;
ALTER TABLE quant_job ADD COLUMN diagnostics_json TEXT;

ALTER TABLE quant_experiment ADD COLUMN execution_status TEXT;
ALTER TABLE quant_experiment ADD COLUMN training_outcome TEXT;
ALTER TABLE quant_experiment ADD COLUMN deployment_status TEXT;
ALTER TABLE quant_experiment ADD COLUMN economic_role TEXT;
ALTER TABLE quant_experiment ADD COLUMN optimization_study_id TEXT;
ALTER TABLE quant_experiment ADD COLUMN optimization_generation INTEGER;
ALTER TABLE quant_experiment ADD COLUMN baseline_comparison_json TEXT;

ALTER TABLE quant_model_version ADD COLUMN deployment_status TEXT;
ALTER TABLE quant_model_version ADD COLUMN economic_role TEXT;
ALTER TABLE quant_model_version ADD COLUMN optimization_study_id TEXT;
ALTER TABLE quant_model_version ADD COLUMN optimization_generation INTEGER;
ALTER TABLE quant_model_version ADD COLUMN baseline_comparison_json TEXT;
