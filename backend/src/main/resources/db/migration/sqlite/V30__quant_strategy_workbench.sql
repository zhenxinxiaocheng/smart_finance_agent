CREATE TABLE quant_v2_object (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, kind VARCHAR(20) NOT NULL,
 name VARCHAR(160) NOT NULL, status VARCHAR(24) NOT NULL, revision INT NOT NULL,
 payload TEXT NOT NULL, created_at VARCHAR(40) NOT NULL, updated_at VARCHAR(40) NOT NULL
);
CREATE INDEX idx_qv2_object_owner ON quant_v2_object(user_id,kind,status);
CREATE TABLE quant_v2_version (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, object_id VARCHAR(36) NOT NULL,
 version INT NOT NULL, payload TEXT NOT NULL, created_at VARCHAR(40) NOT NULL,
 UNIQUE(object_id,version)
);
CREATE INDEX idx_qv2_version_owner ON quant_v2_version(user_id,object_id);
CREATE TABLE quant_v2_task (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, kind VARCHAR(20) NOT NULL,
 name VARCHAR(160) NOT NULL, status VARCHAR(24) NOT NULL, stage VARCHAR(40) NOT NULL,
 strategy_id VARCHAR(36), strategy_version_id VARCHAR(36), universe_id VARCHAR(36),
 request_json TEXT NOT NULL, result_json TEXT, error_code VARCHAR(80), error_message TEXT,
 claim_token VARCHAR(36), lease_until BIGINT, created_at VARCHAR(40) NOT NULL, updated_at VARCHAR(40) NOT NULL
);
CREATE INDEX idx_qv2_task_owner ON quant_v2_task(user_id,kind,created_at);
CREATE INDEX idx_qv2_task_claim ON quant_v2_task(status,lease_until);
CREATE TABLE quant_v2_deployment (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, name VARCHAR(160) NOT NULL,
 backtest_id VARCHAR(36) NOT NULL, strategy_id VARCHAR(36) NOT NULL, universe_id VARCHAR(36) NOT NULL,
 status VARCHAR(24) NOT NULL, revision INT NOT NULL, request_json TEXT NOT NULL,
 result_json TEXT, error_code VARCHAR(80), error_message TEXT, claim_token VARCHAR(36), lease_until BIGINT,
 next_run_at BIGINT NOT NULL, created_at VARCHAR(40) NOT NULL, updated_at VARCHAR(40) NOT NULL
);
CREATE INDEX idx_qv2_deployment_owner ON quant_v2_deployment(user_id,status);
CREATE TABLE quant_v2_paper_event (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, deployment_id VARCHAR(36) NOT NULL,
 event_key VARCHAR(160) NOT NULL, kind VARCHAR(24) NOT NULL, payload TEXT NOT NULL,
 created_at VARCHAR(40) NOT NULL, UNIQUE(deployment_id,event_key)
);
CREATE INDEX idx_qv2_event_owner ON quant_v2_paper_event(user_id,deployment_id);
