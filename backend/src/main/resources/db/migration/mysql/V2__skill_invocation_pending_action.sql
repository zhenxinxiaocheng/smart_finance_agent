ALTER TABLE skill_invocation_record ADD COLUMN pending_action_id BIGINT NULL;
ALTER TABLE skill_invocation_record ADD COLUMN execution_state VARCHAR(32) NULL;
