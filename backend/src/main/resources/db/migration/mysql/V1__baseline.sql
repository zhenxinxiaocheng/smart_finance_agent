-- Current application schema. New changes belong in V2 and later migrations.

CREATE TABLE `user` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `username` VARCHAR(50) NOT NULL,
    `password` VARCHAR(255) NOT NULL,
    `nickname` VARCHAR(50) NULL,
    `email` VARCHAR(100) NULL,
    `avatar` VARCHAR(255) NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `financial_profile` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `life_stage` VARCHAR(50) NULL,
    `monthly_income` DECIMAL(12,2) NOT NULL DEFAULT 0,
    `fixed_expense` DECIMAL(12,2) NOT NULL DEFAULT 0,
    `risk_preference` VARCHAR(30) NULL,
    `savings_goal_amount` DECIMAL(12,2) NOT NULL DEFAULT 0,
    `savings_goal_deadline` VARCHAR(7) NULL,
    `monthly_budget_goal` DECIMAL(12,2) NOT NULL DEFAULT 0,
    `notes` VARCHAR(500) NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_financial_profile_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE,
    UNIQUE KEY uk_profile_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `agent_memory` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `memory_type` VARCHAR(50) NOT NULL,
    `memory_key` VARCHAR(100) NOT NULL,
    `memory_value` TEXT NOT NULL,
    `confidence` DOUBLE NOT NULL DEFAULT 1,
    `source_query` VARCHAR(500) NULL,
    `disabled` TINYINT NOT NULL DEFAULT 0,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT fk_agent_memory_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE,
    UNIQUE KEY uk_agent_memory_key (user_id, memory_type, memory_key),
    KEY idx_agent_memory_user (user_id, disabled, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `agent_skill` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `skill_key` VARCHAR(120) NOT NULL,
    `name` VARCHAR(120) NOT NULL,
    `description` VARCHAR(500) NULL,
    `version` VARCHAR(50) NULL,
    `author` VARCHAR(120) NULL,
    `category` VARCHAR(80) NULL,
    `risk_level` VARCHAR(50) NULL,
    `input_schema` VARCHAR(500) NULL,
    `trigger_text` VARCHAR(500) NULL,
    `instruction_text` TEXT NULL,
    `bound_tools` VARCHAR(500) NULL,
    `source_type` VARCHAR(50) NOT NULL,
    `source_uri` VARCHAR(500) NOT NULL,
    `source_version` VARCHAR(100) NULL,
    `enabled` TINYINT NOT NULL DEFAULT 1,
    `built_in` TINYINT NOT NULL DEFAULT 0,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_agent_skill_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE,
    UNIQUE KEY uk_agent_skill_source (user_id, source_type, source_uri, skill_key),
    KEY idx_agent_skill_user (user_id, enabled, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `skill_invocation_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `trace_id` VARCHAR(64) NULL,
    `skill_name` VARCHAR(100) NOT NULL,
    `category` VARCHAR(50) NULL,
    `source_type` VARCHAR(50) NULL,
    `risk_level` VARCHAR(50) NULL,
    `input` TEXT NULL,
    `success` TINYINT NOT NULL DEFAULT 0,
    `blocked` TINYINT NOT NULL DEFAULT 0,
    `duration_ms` BIGINT NULL,
    `summary` VARCHAR(500) NULL,
    `raw_result` TEXT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_skill_invocation_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE,
    KEY idx_skill_trace (trace_id),
    KEY idx_skill_user (user_id, skill_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `agent_run` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `trace_id` VARCHAR(64) NOT NULL,
    `query` TEXT NOT NULL,
    `final_answer` TEXT NULL,
    `status` VARCHAR(30) NOT NULL DEFAULT 'RUNNING',
    `started_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `finished_at` DATETIME NULL,
    `duration_ms` BIGINT NULL,
    `error_message` VARCHAR(500) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_agent_run_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE,
    UNIQUE KEY uk_agent_run_trace (trace_id),
    KEY idx_agent_run_user (user_id, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `agent_run_step` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `trace_id` VARCHAR(64) NOT NULL,
    `step_number` INT NOT NULL,
    `summary` VARCHAR(500) NULL,
    `tool_name` VARCHAR(100) NULL,
    `input` TEXT NULL,
    `success` TINYINT NULL,
    `observation_summary` VARCHAR(500) NULL,
    `error_message` VARCHAR(500) NULL,
    `status` VARCHAR(30) NOT NULL DEFAULT 'RUNNING',
    `started_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `finished_at` DATETIME NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_agent_run_step_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE,
    UNIQUE KEY uk_agent_run_step (trace_id, step_number),
    KEY idx_agent_run_step_user (user_id, trace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `agent_reflection` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `trace_id` VARCHAR(64) NOT NULL,
    `suggestion_type` VARCHAR(50) NOT NULL,
    `title` VARCHAR(120) NOT NULL,
    `summary` VARCHAR(500) NOT NULL,
    `payload` TEXT NULL,
    `status` VARCHAR(30) NOT NULL DEFAULT 'OPEN',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT fk_agent_reflection_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE,
    KEY idx_agent_reflection_user (user_id, status, deleted),
    KEY idx_agent_reflection_trace (trace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `agent_context_summary` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `conversation_id` BIGINT NULL,
    `trace_id` VARCHAR(64) NULL,
    `scope` VARCHAR(50) NOT NULL DEFAULT 'CONVERSATION',
    `summary` TEXT NOT NULL,
    `source_refs` TEXT NULL,
    `source_hash` VARCHAR(64) NULL,
    `covered_from_message_id` BIGINT NULL,
    `covered_until_message_id` BIGINT NULL,
    `original_tokens` INT NOT NULL DEFAULT 0,
    `compressed_tokens` INT NOT NULL DEFAULT 0,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT fk_context_summary_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE,
    KEY idx_context_summary_conversation (user_id, conversation_id, scope, deleted, updated_at),
    KEY idx_context_summary_source (user_id, scope, source_hash, deleted),
    KEY idx_context_summary_covered (user_id, covered_until_message_id, deleted),
    KEY idx_context_summary_user (user_id, deleted, updated_at),
    KEY idx_context_summary_trace (trace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `transaction` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `amount` DECIMAL(12,2) NOT NULL,
    `type` VARCHAR(10) NOT NULL DEFAULT 'EXPENSE',
    `category` VARCHAR(50) NOT NULL,
    `description` VARCHAR(500) NULL,
    `transaction_date` DATE NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT fk_transaction_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE,
    KEY idx_user_id (user_id),
    KEY idx_user_date (user_id, transaction_date),
    KEY idx_category (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `expense_category` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL DEFAULT 0,
    `name` VARCHAR(50) NOT NULL,
    `icon` VARCHAR(50) NULL,
    `benchmark_min` INT NULL,
    `benchmark_max` INT NULL,
    `benchmark_label` VARCHAR(100) NULL,
    `sort_order` INT NOT NULL DEFAULT 0,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_category (user_id, name),
    KEY idx_user_sort (user_id, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `chat_conversation` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `title` VARCHAR(80) NOT NULL DEFAULT '新对话',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT fk_chat_conversation_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE,
    KEY idx_chat_conversation_user (user_id, deleted, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `chat_message` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `role` VARCHAR(20) NOT NULL,
    `content` TEXT NOT NULL,
    `trace_id` VARCHAR(64) NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    `conversation_id` BIGINT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_chat_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE,
    KEY idx_chat_conversation (conversation_id),
    KEY idx_chat_trace (trace_id),
    KEY idx_user_id (user_id),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `pending_action` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `action_type` VARCHAR(50) NOT NULL,
    `title` VARCHAR(100) NOT NULL,
    `summary` VARCHAR(500) NOT NULL,
    `payload` TEXT NOT NULL,
    `status` VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_pending_action_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE,
    KEY idx_pending_user_status (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `budget` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `category` VARCHAR(50) NOT NULL,
    `month` VARCHAR(7) NOT NULL,
    `budget_amount` DECIMAL(12,2) NOT NULL,
    `alert_threshold` INT NOT NULL DEFAULT 80,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT fk_budget_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE,
    UNIQUE KEY uk_user_category_month (user_id, category, month),
    KEY idx_user_month (user_id, month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `budget_alert` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `category` VARCHAR(50) NOT NULL,
    `month` VARCHAR(7) NOT NULL,
    `alert_type` VARCHAR(20) NOT NULL,
    `severity` VARCHAR(10) NOT NULL DEFAULT 'INFO',
    `spent_amount` DECIMAL(12,2) NOT NULL,
    `budget_amount` DECIMAL(12,2) NOT NULL,
    `usage_percent` DECIMAL(5,1) NOT NULL,
    `message` VARCHAR(500) NOT NULL,
    `is_read` TINYINT NOT NULL DEFAULT 0,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT fk_alert_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE,
    KEY idx_user_read (user_id, is_read),
    KEY idx_user_month (user_id, month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `bill_import_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `original_filename` VARCHAR(255) NULL,
    `file_path` VARCHAR(500) NOT NULL,
    `bill_type` VARCHAR(50) NOT NULL DEFAULT 'UNKNOWN',
    `confidence` DECIMAL(5,4) NOT NULL DEFAULT 0,
    `ocr_text` TEXT NULL,
    `warnings` TEXT NULL,
    `status` VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT fk_bill_import_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE,
    KEY idx_bill_user (user_id),
    KEY idx_bill_status (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `bill_candidate_transaction` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `bill_import_id` BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL,
    `amount` DECIMAL(12,2) NOT NULL,
    `type` VARCHAR(10) NOT NULL DEFAULT 'EXPENSE',
    `category` VARCHAR(50) NOT NULL DEFAULT '其他',
    `description` VARCHAR(500) NULL,
    `transaction_date` DATE NOT NULL,
    `confidence` DECIMAL(5,4) NOT NULL DEFAULT 0,
    `status` VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    `transaction_id` BIGINT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT fk_candidate_bill FOREIGN KEY (bill_import_id) REFERENCES `bill_import_record` (id) ON DELETE CASCADE,
    CONSTRAINT fk_candidate_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE,
    KEY idx_candidate_bill (bill_import_id),
    KEY idx_candidate_user (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `analysis_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `trace_id` VARCHAR(64) NULL,
    `query` TEXT NOT NULL,
    `plan` TEXT NULL,
    `steps_result` TEXT NULL,
    `final_answer` TEXT NULL,
    `score` INT NULL,
    `feedback` VARCHAR(500) NULL,
    `tokens_used` INT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT fk_analysis_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE,
    KEY idx_user_id (user_id),
    KEY idx_created_at (created_at),
    KEY idx_analysis_trace (trace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `agent_schedule` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `trace_id` VARCHAR(64) NULL,
    `name` VARCHAR(200) NOT NULL,
    `description` VARCHAR(500) NULL,
    `cron_expression` VARCHAR(100) NOT NULL,
    `timezone` VARCHAR(50) NOT NULL DEFAULT 'Asia/Shanghai',
    `task_query` TEXT NOT NULL,
    `enabled` TINYINT NOT NULL DEFAULT 1,
    `last_run_at` DATETIME NULL,
    `next_run_at` DATETIME NULL,
    `lock_until` DATETIME NULL,
    `run_count` INT NOT NULL DEFAULT 0,
    `consecutive_failures` INT NOT NULL DEFAULT 0,
    `last_status` VARCHAR(20) NULL,
    `last_answer` TEXT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT fk_agent_schedule_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE,
    KEY idx_agent_schedule_user (user_id, enabled, deleted),
    KEY idx_agent_schedule_next_run (enabled, next_run_at),
    KEY idx_agent_schedule_lock (enabled, lock_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `agent_schedule_run` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `schedule_id` BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL,
    `trace_id` VARCHAR(64) NULL,
    `status` VARCHAR(20) NOT NULL,
    `answer` TEXT NULL,
    `error_message` TEXT NULL,
    `started_at` DATETIME NOT NULL,
    `finished_at` DATETIME NOT NULL,
    `duration_ms` BIGINT NOT NULL DEFAULT 0,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_agent_schedule_run_schedule FOREIGN KEY (schedule_id) REFERENCES `agent_schedule` (id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_schedule_run_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE,
    KEY idx_agent_schedule_run_schedule (schedule_id, created_at),
    KEY idx_agent_schedule_run_user (user_id, created_at),
    KEY idx_agent_schedule_run_trace (trace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `investment_product` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `product_type` VARCHAR(30) NOT NULL,
    `market` VARCHAR(30) NOT NULL,
    `code` VARCHAR(40) NOT NULL,
    `name` VARCHAR(160) NOT NULL,
    `currency` VARCHAR(3) NOT NULL DEFAULT 'CNY',
    `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `inception_date` DATE NULL,
    `history_start_date` DATE NULL,
    `history_end_date` DATE NULL,
    `history_coverage_complete` BOOLEAN NOT NULL DEFAULT FALSE,
    `fund_type_raw` VARCHAR(80) NULL,
    `fund_category` VARCHAR(40) NULL,
    `classification_source` VARCHAR(60) NULL,
    `classification_version` VARCHAR(80) NULL,
    `classified_at` DATETIME NULL,
    PRIMARY KEY (id),
    KEY idx_investment_product_fund_category (product_type, fund_category),
    UNIQUE KEY uk_investment_product (product_type, market, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `investment_account` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `account_name` VARCHAR(120) NOT NULL,
    `account_type` VARCHAR(30) NOT NULL,
    `base_currency` VARCHAR(3) NOT NULL DEFAULT 'CNY',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_investment_account_user (user_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `investment_transaction` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `account_id` BIGINT NOT NULL,
    `product_id` BIGINT NULL,
    `event_type` VARCHAR(30) NOT NULL,
    `trade_date` DATE NOT NULL,
    `settlement_date` DATE NULL,
    `currency` VARCHAR(3) NOT NULL,
    `quantity` DECIMAL(28,10) NULL,
    `price` DECIMAL(28,10) NULL,
    `amount` DECIMAL(28,8) NULL,
    `fee` DECIMAL(28,8) NOT NULL DEFAULT 0,
    `factor` DECIMAL(28,10) NULL,
    `source` VARCHAR(30) NOT NULL DEFAULT 'MANUAL',
    `external_ref` VARCHAR(120) NULL,
    `reversal_transaction_id` BIGINT NULL,
    `note` VARCHAR(500) NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_investment_transaction_ref (user_id, source, external_ref),
    KEY idx_investment_transaction_account (user_id, account_id, trade_date),
    KEY idx_investment_transaction_product (account_id, product_id, trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `investment_cash_balance` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `account_id` BIGINT NOT NULL,
    `currency` VARCHAR(3) NOT NULL,
    `balance` DECIMAL(28,8) NOT NULL DEFAULT 0,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_investment_cash_balance (account_id, currency),
    KEY idx_investment_cash_user (user_id, account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `investment_cash_ledger` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `account_id` BIGINT NOT NULL,
    `transaction_id` BIGINT NOT NULL,
    `currency` VARCHAR(3) NOT NULL,
    `event_type` VARCHAR(30) NOT NULL,
    `amount` DECIMAL(28,8) NOT NULL,
    `external_flow` TINYINT NOT NULL DEFAULT 0,
    `occurred_on` DATE NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_investment_cash_transaction (transaction_id),
    KEY idx_investment_cash_ledger_account (user_id, account_id, occurred_on)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `investment_position` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `account_id` BIGINT NOT NULL,
    `product_id` BIGINT NOT NULL,
    `quantity` DECIMAL(28,10) NOT NULL DEFAULT 0,
    `cost_amount` DECIMAL(28,8) NOT NULL DEFAULT 0,
    `average_cost` DECIMAL(28,10) NOT NULL DEFAULT 0,
    `realized_pnl` DECIMAL(28,8) NOT NULL DEFAULT 0,
    `latest_price` DECIMAL(28,10) NULL,
    `market_value_cny` DECIMAL(28,8) NULL,
    `unrealized_pnl_cny` DECIMAL(28,8) NULL,
    `data_date` DATE NULL,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_investment_position (account_id, product_id),
    KEY idx_investment_position_user (user_id, account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `product_daily_quote` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `product_id` BIGINT NOT NULL,
    `trade_date` DATE NOT NULL,
    `open_price` DECIMAL(28,10) NULL,
    `high_price` DECIMAL(28,10) NULL,
    `low_price` DECIMAL(28,10) NULL,
    `close_price` DECIMAL(28,10) NOT NULL,
    `volume` DECIMAL(28,8) NULL,
    `adjust_type` VARCHAR(20) NOT NULL DEFAULT 'NONE',
    `source` VARCHAR(40) NOT NULL,
    `adapter_version` VARCHAR(40) NULL,
    `synced_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `previous_close` DECIMAL(28,10) NULL,
    `change_amount` DECIMAL(28,10) NULL,
    `change_percent` DECIMAL(18,8) NULL,
    `amount` DECIMAL(28,8) NULL,
    `turnover_rate` DECIMAL(18,8) NULL,
    `volume_ratio` DECIMAL(18,8) NULL,
    `amplitude` DECIMAL(18,8) NULL,
    `total_return_index` DECIMAL(28,10) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_daily_quote (product_id, trade_date, adjust_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `investment_import_batch` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `original_filename` VARCHAR(255) NOT NULL,
    `status` VARCHAR(30) NOT NULL,
    `payload` LONGTEXT NOT NULL,
    `row_count` INT NOT NULL DEFAULT 0,
    `error_count` INT NOT NULL DEFAULT 0,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_investment_import_user (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `investment_sync_batch` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `job_type` VARCHAR(40) NOT NULL,
    `provider` VARCHAR(40) NULL,
    `status` VARCHAR(30) NOT NULL,
    `rows_success` INT NOT NULL DEFAULT 0,
    `rows_failed` INT NOT NULL DEFAULT 0,
    `error_message` VARCHAR(500) NULL,
    `started_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `finished_at` DATETIME NULL,
    PRIMARY KEY (id),
    KEY idx_investment_sync_user (user_id, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `investment_plan` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `account_id` BIGINT NOT NULL,
    `product_id` BIGINT NOT NULL,
    `amount` DECIMAL(28,8) NOT NULL,
    `currency` VARCHAR(3) NOT NULL,
    `frequency` VARCHAR(20) NOT NULL,
    `execution_day` INT NOT NULL,
    `next_execution_date` DATE NOT NULL,
    `last_execution_date` DATE NULL,
    `last_execution_amount` DECIMAL(28,8) NULL,
    `last_execution_price` DECIMAL(28,10) NULL,
    `execution_count` INT NOT NULL DEFAULT 0,
    `last_execution_status` VARCHAR(30) NOT NULL DEFAULT 'WAITING',
    `last_execution_message` VARCHAR(500) NULL,
    `enabled` TINYINT NOT NULL DEFAULT 1,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_investment_plan_due (enabled, next_execution_date),
    KEY idx_investment_plan_user (user_id, enabled, next_execution_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `daily_exchange_rate` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `base_currency` VARCHAR(3) NOT NULL,
    `quote_currency` VARCHAR(3) NOT NULL,
    `rate_date` DATE NOT NULL,
    `rate` DECIMAL(28,10) NOT NULL,
    `source` VARCHAR(40) NOT NULL,
    `adapter_version` VARCHAR(40),
    `synced_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_daily_exchange_rate (base_currency, quote_currency, rate_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `investment_asset` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `account_id` BIGINT NOT NULL,
    `product_id` BIGINT NOT NULL,
    `quantity` DECIMAL(28,10),
    `average_cost` DECIMAL(28,10),
    `note` VARCHAR(500),
    `current_transaction_id` BIGINT,
    `sync_status` VARCHAR(30) NOT NULL DEFAULT 'NOT_SYNCED',
    `sync_error` VARCHAR(500),
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_investment_asset_user (user_id, deleted, updated_at),
    KEY idx_investment_asset_product (user_id, product_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `wealth_baseline` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `user_id` BIGINT NOT NULL,
    `entered_total_assets` DECIMAL(28,8) NOT NULL,
    `baseline_non_investment_balance` DECIMAL(28,8) NOT NULL,
    `baseline_at` DATETIME NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_wealth_baseline_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `investment_horizon_profile` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `user_id` BIGINT NOT NULL,
    `scope_type` VARCHAR(16) NOT NULL,
    `asset_id` BIGINT,
    `version` INT NOT NULL,
    `template_version` VARCHAR(64) NOT NULL,
    `source` VARCHAR(24) NOT NULL,
    `active` TINYINT(1) NOT NULL DEFAULT 1,
    `effective_from` DATETIME NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_horizon_profile_resolution (user_id, scope_type, asset_id, active, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `investment_horizon_setting` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `profile_id` BIGINT NOT NULL,
    `horizon_code` VARCHAR(32) NOT NULL,
    `display_name` VARCHAR(50) NOT NULL,
    `sort_order` INT NOT NULL,
    `min_holding_days` INT NOT NULL,
    `max_holding_days` INT NOT NULL,
    `target_holding_days` INT NOT NULL,
    `is_primary` TINYINT(1) NOT NULL DEFAULT 0,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_horizon_setting_profile FOREIGN KEY (profile_id) REFERENCES `investment_horizon_profile` (id),
    UNIQUE KEY uk_horizon_setting_profile_code (profile_id, horizon_code),
    KEY idx_horizon_setting_profile (profile_id, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `investment_analysis_snapshot` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `user_id` BIGINT NOT NULL,
    `asset_id` BIGINT NOT NULL,
    `rule_version` VARCHAR(40) NOT NULL,
    `preference_hash` VARCHAR(64) NOT NULL,
    `horizon_profile_version` VARCHAR(255),
    `horizon_config_json` LONGTEXT,
    `dataset_version` VARCHAR(64),
    `quality_rule_set_version` VARCHAR(80),
    `strategy_version` VARCHAR(80),
    `analysis_cache_key` VARCHAR(64),
    `quality_status` VARCHAR(16),
    `historical_cache` TINYINT(1) NOT NULL DEFAULT 0,
    `signal_hash` VARCHAR(64),
    `quote_date` DATE,
    `technical_json` LONGTEXT,
    `fundamental_json` LONGTEXT,
    `fund_json` LONGTEXT,
    `backtest_json` LONGTEXT,
    `source_status_json` TEXT,
    `ai_explanation` TEXT,
    `analysis_status` VARCHAR(30) NOT NULL DEFAULT 'READY',
    `analyzed_at` DATETIME NOT NULL,
    `ai_updated_at` DATETIME,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_analysis_snapshot_user_asset_rule (user_id, asset_id, rule_version),
    KEY idx_analysis_cache_key (user_id, asset_id, analysis_cache_key),
    KEY idx_analysis_dataset_version (dataset_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `investment_data_quality_snapshot` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `dataset_version` VARCHAR(64) NOT NULL,
    `product_type` VARCHAR(30) NOT NULL,
    `code` VARCHAR(40) NOT NULL,
    `market` VARCHAR(20) NOT NULL,
    `frequency` VARCHAR(16) NOT NULL,
    `adjust_type` VARCHAR(16) NOT NULL,
    `provider` VARCHAR(80) NOT NULL,
    `adapter_version` VARCHAR(80) NOT NULL,
    `quality_config_version` VARCHAR(80) NOT NULL,
    `quality_rule_set_version` VARCHAR(80) NOT NULL,
    `quality_status` VARCHAR(16) NOT NULL,
    `decision` VARCHAR(16) NOT NULL,
    `enforcement_mode` VARCHAR(16) NOT NULL,
    `requested_start_date` DATE NOT NULL,
    `requested_end_date` DATE NOT NULL,
    `sample_start_date` DATE NOT NULL,
    `sample_end_date` DATE NOT NULL,
    `fetched_at` DATETIME NOT NULL,
    `evaluated_at` DATETIME NOT NULL,
    `manifest_json` LONGTEXT NOT NULL,
    `report_json` LONGTEXT NOT NULL,
    `secondary_dataset_versions_json` LONGTEXT,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_data_quality_dataset_rule (dataset_version, quality_config_version),
    KEY idx_data_quality_dataset_version (dataset_version),
    KEY idx_data_quality_product_range (product_type, code, market, requested_start_date, requested_end_date),
    KEY idx_data_quality_status (quality_status, evaluated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `investment_data_quality_issue` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `quality_snapshot_id` BIGINT NOT NULL,
    `sequence_no` INT NOT NULL,
    `rule_code` VARCHAR(80) NOT NULL,
    `severity` VARCHAR(16) NOT NULL,
    `outcome` VARCHAR(24) NOT NULL,
    `message` TEXT NOT NULL,
    `observed_json` LONGTEXT,
    `expected_json` LONGTEXT,
    `affected_dates_json` LONGTEXT,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_data_quality_issue_snapshot FOREIGN KEY (quality_snapshot_id) REFERENCES `investment_data_quality_snapshot` (id) ON DELETE CASCADE,
    UNIQUE KEY uk_data_quality_issue_order (quality_snapshot_id, sequence_no),
    KEY idx_data_quality_issue_rule (rule_code, severity)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `investment_data_job` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `user_id` BIGINT NOT NULL,
    `asset_id` BIGINT NOT NULL,
    `product_id` BIGINT NOT NULL,
    `job_type` VARCHAR(30) NOT NULL,
    `status` VARCHAR(20) NOT NULL,
    `force_refresh` TINYINT NOT NULL DEFAULT 0,
    `record_count` INT NOT NULL DEFAULT 0,
    `attempt_count` INT NOT NULL DEFAULT 0,
    `next_retry_at` DATETIME,
    `lease_until` DATETIME,
    `lease_token` VARCHAR(36),
    `error_message` VARCHAR(1000),
    `started_at` DATETIME,
    `finished_at` DATETIME,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `requested_start_date` DATE NULL,
    `sample_start_date` DATE NULL,
    `sample_end_date` DATE NULL,
    `coverage_complete` TINYINT NULL,
    `dataset_version` VARCHAR(128) NULL,
    UNIQUE KEY uk_investment_data_job_asset_type (asset_id, job_type),
    KEY idx_investment_data_job_pending (status, next_retry_at, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `benchmark_profile` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `product_type` VARCHAR(30) NOT NULL,
    `product_code` VARCHAR(40),
    `model_family` VARCHAR(40) NOT NULL,
    `benchmark_code` VARCHAR(80) NOT NULL,
    `display_name` VARCHAR(255) NOT NULL,
    `composition_json` LONGTEXT NOT NULL,
    `currency` VARCHAR(10) NOT NULL,
    `fx_rule` VARCHAR(40),
    `source_uri` VARCHAR(1000) NOT NULL,
    `source_version` VARCHAR(80) NOT NULL,
    `effective_from` DATE NOT NULL,
    `effective_to` DATE,
    `active` TINYINT(1) NOT NULL DEFAULT 1,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_benchmark_profile_version (product_type, product_code, effective_from),
    KEY idx_benchmark_profile_lookup (product_type, product_code, active, effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `quant_benchmark_snapshot` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `snapshot_version` CHAR(64) NOT NULL,
    `benchmark_profile_id` BIGINT NOT NULL,
    `benchmark_code` VARCHAR(80) NOT NULL,
    `source_uri` VARCHAR(1000) NOT NULL,
    `source_version` VARCHAR(80) NOT NULL,
    `provider` VARCHAR(40),
    `adapter_version` VARCHAR(40),
    `currency` VARCHAR(10) NOT NULL,
    `fx_rule` VARCHAR(40),
    `effective_from` DATE NOT NULL,
    `effective_to` DATE,
    `sample_start_date` DATE NOT NULL,
    `sample_end_date` DATE NOT NULL,
    `fetched_at` DATETIME NOT NULL,
    `records_json` LONGTEXT NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_quant_benchmark_snapshot_version (snapshot_version),
    KEY idx_quant_benchmark_snapshot_lookup (benchmark_profile_id, sample_start_date, sample_end_date, fetched_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `investment_index_watchlist` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `index_code` VARCHAR(64) NOT NULL,
    `display_name` VARCHAR(120) NOT NULL,
    `market` VARCHAR(20) NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    KEY idx_investment_index_watchlist_user (user_id, deleted, created_at),
    KEY idx_investment_index_watchlist_code (user_id, index_code, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `investment_index_display_order` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `index_code` VARCHAR(64) NOT NULL,
    `sort_order` INT NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_investment_index_display_order (user_id, index_code),
    KEY idx_investment_index_display_order_user (user_id, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `quant_v2_object` (
    `id` VARCHAR(36) PRIMARY KEY,
    `user_id` BIGINT NOT NULL,
    `kind` VARCHAR(20) NOT NULL,
    `name` VARCHAR(160) NOT NULL,
    `status` VARCHAR(24) NOT NULL,
    `revision` INT NOT NULL,
    `payload` LONGTEXT NOT NULL,
    `created_at` VARCHAR(40) NOT NULL,
    `updated_at` VARCHAR(40) NOT NULL,
    KEY idx_qv2_object_owner (user_id,kind,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `quant_v2_version` (
    `id` VARCHAR(36) PRIMARY KEY,
    `user_id` BIGINT NOT NULL,
    `object_id` VARCHAR(36) NOT NULL,
    `version` INT NOT NULL,
    `payload` LONGTEXT NOT NULL,
    `created_at` VARCHAR(40) NOT NULL,
    UNIQUE(object_id,version),
    KEY idx_qv2_version_owner (user_id,object_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `quant_v2_task` (
    `id` VARCHAR(36) PRIMARY KEY,
    `user_id` BIGINT NOT NULL,
    `kind` VARCHAR(20) NOT NULL,
    `name` VARCHAR(160) NOT NULL,
    `status` VARCHAR(24) NOT NULL,
    `stage` VARCHAR(40) NOT NULL,
    `strategy_id` VARCHAR(36),
    `strategy_version_id` VARCHAR(36),
    `universe_id` VARCHAR(36),
    `request_json` LONGTEXT NOT NULL,
    `result_json` LONGTEXT,
    `error_code` VARCHAR(80),
    `error_message` LONGTEXT,
    `claim_token` VARCHAR(36),
    `lease_until` BIGINT,
    `created_at` VARCHAR(40) NOT NULL,
    `updated_at` VARCHAR(40) NOT NULL,
    KEY idx_qv2_task_owner (user_id,kind,created_at),
    KEY idx_qv2_task_claim (status,lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `quant_v2_deployment` (
    `id` VARCHAR(36) PRIMARY KEY,
    `user_id` BIGINT NOT NULL,
    `name` VARCHAR(160) NOT NULL,
    `backtest_id` VARCHAR(36) NOT NULL,
    `strategy_id` VARCHAR(36) NOT NULL,
    `universe_id` VARCHAR(36) NOT NULL,
    `status` VARCHAR(24) NOT NULL,
    `revision` INT NOT NULL,
    `request_json` LONGTEXT NOT NULL,
    `result_json` LONGTEXT,
    `error_code` VARCHAR(80),
    `error_message` LONGTEXT,
    `claim_token` VARCHAR(36),
    `lease_until` BIGINT,
    `next_run_at` BIGINT NOT NULL,
    `created_at` VARCHAR(40) NOT NULL,
    `updated_at` VARCHAR(40) NOT NULL,
    KEY idx_qv2_deployment_owner (user_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `quant_v2_paper_event` (
    `id` VARCHAR(36) PRIMARY KEY,
    `user_id` BIGINT NOT NULL,
    `deployment_id` VARCHAR(36) NOT NULL,
    `event_key` VARCHAR(160) NOT NULL,
    `kind` VARCHAR(24) NOT NULL,
    `payload` LONGTEXT NOT NULL,
    `created_at` VARCHAR(40) NOT NULL,
    UNIQUE(deployment_id,event_key),
    KEY idx_qv2_event_owner (user_id,deployment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `quant_v2_research_snapshot` (
    `id` VARCHAR(36) PRIMARY KEY,
    `user_id` BIGINT NOT NULL,
    `format_version` VARCHAR(40) NOT NULL,
    `content_hash` CHAR(64) NOT NULL,
    `compression` VARCHAR(16) NOT NULL,
    `payload_blob` LONGBLOB NOT NULL,
    `metadata_json` LONGTEXT NOT NULL,
    `uncompressed_bytes` BIGINT NOT NULL,
    `compressed_bytes` BIGINT NOT NULL,
    `created_at` VARCHAR(40) NOT NULL,
    UNIQUE KEY uk_qv2_research_snapshot_content (user_id,format_version,content_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `quant_v2_experiment` (
    `id` VARCHAR(36) PRIMARY KEY,
    `user_id` BIGINT NOT NULL,
    `name` VARCHAR(160) NOT NULL,
    `status` VARCHAR(24) NOT NULL,
    `source_backtest_id` VARCHAR(36) NOT NULL,
    `strategy_id` VARCHAR(36) NOT NULL,
    `strategy_version_id` VARCHAR(36) NOT NULL,
    `universe_id` VARCHAR(36) NOT NULL,
    `universe_version_id` VARCHAR(36) NOT NULL,
    `factor_id` VARCHAR(36),
    `factor_version_id` VARCHAR(36),
    `snapshot_id` VARCHAR(36) NOT NULL,
    `model_ref` VARCHAR(160),
    `model_task_id` VARCHAR(36),
    `variable_definition_json` LONGTEXT NOT NULL,
    `baseline_values_json` LONGTEXT NOT NULL,
    `candidate_values_json` LONGTEXT NOT NULL,
    `base_request_json` LONGTEXT NOT NULL,
    `source_context_json` LONGTEXT NOT NULL,
    `environment_json` LONGTEXT NOT NULL,
    `candidate_rule_version` VARCHAR(64) NOT NULL,
    `stability_algorithm_version` VARCHAR(64) NOT NULL,
    `invariant_hash` CHAR(64) NOT NULL,
    `summary_json` LONGTEXT,
    `request_key` VARCHAR(80),
    `request_hash` CHAR(64),
    `revision` INT NOT NULL,
    `created_at` VARCHAR(40) NOT NULL,
    `updated_at` VARCHAR(40) NOT NULL,
    `completed_at` VARCHAR(40),
    `cancelled_at` VARCHAR(40),
    KEY idx_qv2_experiment_owner (user_id,status,created_at),
    KEY idx_qv2_experiment_strategy (user_id,strategy_id,strategy_version_id),
    UNIQUE KEY uk_qv2_experiment_request (user_id,request_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `quant_v2_experiment_run` (
    `id` VARCHAR(36) PRIMARY KEY,
    `user_id` BIGINT NOT NULL,
    `experiment_id` VARCHAR(36) NOT NULL,
    `ordinal` INT NOT NULL,
    `variable_values_json` LONGTEXT NOT NULL,
    `value_hash` CHAR(64) NOT NULL,
    `baseline` BOOLEAN NOT NULL,
    `active_attempt_id` VARCHAR(36),
    `created_at` VARCHAR(40) NOT NULL,
    KEY idx_qv2_experiment_run_owner (user_id,experiment_id),
    UNIQUE KEY uk_qv2_experiment_run_value (experiment_id,value_hash),
    UNIQUE KEY uk_qv2_experiment_run_ordinal (experiment_id,ordinal)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `quant_v2_experiment_run_attempt` (
    `id` VARCHAR(36) PRIMARY KEY,
    `user_id` BIGINT NOT NULL,
    `run_id` VARCHAR(36) NOT NULL,
    `attempt_no` INT NOT NULL,
    `reason` VARCHAR(32) NOT NULL,
    `task_id` VARCHAR(36) NOT NULL,
    `created_at` VARCHAR(40) NOT NULL,
    KEY idx_qv2_experiment_attempt_owner (user_id,run_id),
    UNIQUE KEY uk_qv2_experiment_attempt_number (run_id,attempt_no),
    UNIQUE KEY uk_qv2_experiment_attempt_task (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
