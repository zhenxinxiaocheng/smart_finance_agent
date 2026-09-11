CREATE TABLE quant_v2_research_snapshot (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, format_version VARCHAR(40) NOT NULL,
 content_hash CHAR(64) NOT NULL, compression VARCHAR(16) NOT NULL, payload_blob BLOB NOT NULL,
 metadata_json TEXT NOT NULL, uncompressed_bytes BIGINT NOT NULL, compressed_bytes BIGINT NOT NULL,
 created_at VARCHAR(40) NOT NULL
);
CREATE UNIQUE INDEX uk_qv2_research_snapshot_content
 ON quant_v2_research_snapshot(user_id,format_version,content_hash);

CREATE TABLE quant_v2_experiment (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, name VARCHAR(160) NOT NULL,
 status VARCHAR(24) NOT NULL, source_backtest_id VARCHAR(36) NOT NULL,
 strategy_id VARCHAR(36) NOT NULL, strategy_version_id VARCHAR(36) NOT NULL,
 universe_id VARCHAR(36) NOT NULL, universe_version_id VARCHAR(36) NOT NULL,
 factor_id VARCHAR(36), factor_version_id VARCHAR(36), snapshot_id VARCHAR(36) NOT NULL,
 model_ref VARCHAR(160), model_task_id VARCHAR(36), variable_definition_json TEXT NOT NULL,
 baseline_values_json TEXT NOT NULL, candidate_values_json TEXT NOT NULL,
 base_request_json TEXT NOT NULL, source_context_json TEXT NOT NULL, environment_json TEXT NOT NULL,
 candidate_rule_version VARCHAR(64) NOT NULL, stability_algorithm_version VARCHAR(64) NOT NULL,
 invariant_hash CHAR(64) NOT NULL, summary_json TEXT, request_key VARCHAR(80), request_hash CHAR(64),
 revision INT NOT NULL, created_at VARCHAR(40) NOT NULL, updated_at VARCHAR(40) NOT NULL,
 completed_at VARCHAR(40), cancelled_at VARCHAR(40)
);
CREATE INDEX idx_qv2_experiment_owner ON quant_v2_experiment(user_id,status,created_at);
CREATE INDEX idx_qv2_experiment_strategy ON quant_v2_experiment(user_id,strategy_id,strategy_version_id);
CREATE UNIQUE INDEX uk_qv2_experiment_request ON quant_v2_experiment(user_id,request_key);

CREATE TABLE quant_v2_experiment_run (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, experiment_id VARCHAR(36) NOT NULL,
 ordinal INT NOT NULL, variable_values_json TEXT NOT NULL, value_hash CHAR(64) NOT NULL,
 baseline BOOLEAN NOT NULL, active_attempt_id VARCHAR(36), created_at VARCHAR(40) NOT NULL
);
CREATE INDEX idx_qv2_experiment_run_owner ON quant_v2_experiment_run(user_id,experiment_id);
CREATE UNIQUE INDEX uk_qv2_experiment_run_value ON quant_v2_experiment_run(experiment_id,value_hash);
CREATE UNIQUE INDEX uk_qv2_experiment_run_ordinal ON quant_v2_experiment_run(experiment_id,ordinal);

CREATE TABLE quant_v2_experiment_run_attempt (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, run_id VARCHAR(36) NOT NULL,
 attempt_no INT NOT NULL, reason VARCHAR(32) NOT NULL, task_id VARCHAR(36) NOT NULL,
 created_at VARCHAR(40) NOT NULL
);
CREATE INDEX idx_qv2_experiment_attempt_owner ON quant_v2_experiment_run_attempt(user_id,run_id);
CREATE UNIQUE INDEX uk_qv2_experiment_attempt_number ON quant_v2_experiment_run_attempt(run_id,attempt_no);
CREATE UNIQUE INDEX uk_qv2_experiment_attempt_task ON quant_v2_experiment_run_attempt(task_id);
