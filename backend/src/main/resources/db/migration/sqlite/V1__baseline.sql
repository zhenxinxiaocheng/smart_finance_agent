-- Current application schema. New changes belong in V2 and later migrations.

CREATE TABLE `user` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `username` TEXT NOT NULL,
    `password` TEXT NOT NULL,
    `nickname` TEXT NULL,
    `email` TEXT NULL,
    `avatar` TEXT NULL,
    `created_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `deleted` INTEGER NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX user_uk_username ON `user` (username);

CREATE INDEX user_idx_created_at ON `user` (created_at);

CREATE TABLE `financial_profile` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `user_id` INTEGER NOT NULL,
    `life_stage` TEXT NULL,
    `monthly_income` NUMERIC NOT NULL DEFAULT 0,
    `fixed_expense` NUMERIC NOT NULL DEFAULT 0,
    `risk_preference` TEXT NULL,
    `savings_goal_amount` NUMERIC NOT NULL DEFAULT 0,
    `savings_goal_deadline` TEXT NULL,
    `monthly_budget_goal` NUMERIC NOT NULL DEFAULT 0,
    `notes` TEXT NULL,
    `created_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_financial_profile_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX financial_profile_uk_profile_user ON `financial_profile` (user_id);

CREATE TABLE `agent_memory` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `user_id` INTEGER NOT NULL,
    `memory_type` TEXT NOT NULL,
    `memory_key` TEXT NOT NULL,
    `memory_value` TEXT NOT NULL,
    `confidence` REAL NOT NULL DEFAULT 1,
    `source_query` TEXT NULL,
    `disabled` INTEGER NOT NULL DEFAULT 0,
    `created_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `deleted` INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT fk_agent_memory_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX agent_memory_uk_agent_memory_key ON `agent_memory` (user_id, memory_type, memory_key);

CREATE INDEX agent_memory_idx_agent_memory_user ON `agent_memory` (user_id, disabled, deleted);

CREATE TABLE `agent_skill` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `user_id` INTEGER NOT NULL,
    `skill_key` TEXT NOT NULL,
    `name` TEXT NOT NULL,
    `description` TEXT NULL,
    `version` TEXT NULL,
    `author` TEXT NULL,
    `category` TEXT NULL,
    `risk_level` TEXT NULL,
    `input_schema` TEXT NULL,
    `trigger_text` TEXT NULL,
    `instruction_text` TEXT NULL,
    `bound_tools` TEXT NULL,
    `source_type` TEXT NOT NULL,
    `source_uri` TEXT NOT NULL,
    `source_version` TEXT NULL,
    `enabled` INTEGER NOT NULL DEFAULT 1,
    `built_in` INTEGER NOT NULL DEFAULT 0,
    `deleted` INTEGER NOT NULL DEFAULT 0,
    `created_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_agent_skill_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX agent_skill_uk_agent_skill_source ON `agent_skill` (user_id, source_type, source_uri, skill_key);

CREATE INDEX agent_skill_idx_agent_skill_user ON `agent_skill` (user_id, enabled, deleted);

CREATE TABLE `skill_invocation_record` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `user_id` INTEGER NOT NULL,
    `trace_id` TEXT NULL,
    `skill_name` TEXT NOT NULL,
    `category` TEXT NULL,
    `source_type` TEXT NULL,
    `risk_level` TEXT NULL,
    `input` TEXT NULL,
    `success` INTEGER NOT NULL DEFAULT 0,
    `blocked` INTEGER NOT NULL DEFAULT 0,
    `duration_ms` INTEGER NULL,
    `summary` TEXT NULL,
    `raw_result` TEXT NULL,
    `created_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_skill_invocation_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE
);

CREATE INDEX skill_invocation_record_idx_skill_trace ON `skill_invocation_record` (trace_id);

CREATE INDEX skill_invocation_record_idx_skill_user ON `skill_invocation_record` (user_id, skill_name);

CREATE TABLE `agent_run` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `user_id` INTEGER NOT NULL,
    `trace_id` TEXT NOT NULL,
    `query` TEXT NOT NULL,
    `final_answer` TEXT NULL,
    `status` TEXT NOT NULL DEFAULT 'RUNNING',
    `started_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `finished_at` TEXT NULL,
    `duration_ms` INTEGER NULL,
    `error_message` TEXT NULL,
    CONSTRAINT fk_agent_run_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX agent_run_uk_agent_run_trace ON `agent_run` (trace_id);

CREATE INDEX agent_run_idx_agent_run_user ON `agent_run` (user_id, started_at);

CREATE TABLE `agent_run_step` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `user_id` INTEGER NOT NULL,
    `trace_id` TEXT NOT NULL,
    `step_number` INTEGER NOT NULL,
    `summary` TEXT NULL,
    `tool_name` TEXT NULL,
    `input` TEXT NULL,
    `success` INTEGER NULL,
    `observation_summary` TEXT NULL,
    `error_message` TEXT NULL,
    `status` TEXT NOT NULL DEFAULT 'RUNNING',
    `started_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `finished_at` TEXT NULL,
    CONSTRAINT fk_agent_run_step_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX agent_run_step_uk_agent_run_step ON `agent_run_step` (trace_id, step_number);

CREATE INDEX agent_run_step_idx_agent_run_step_user ON `agent_run_step` (user_id, trace_id);

CREATE TABLE `agent_reflection` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `user_id` INTEGER NOT NULL,
    `trace_id` TEXT NOT NULL,
    `suggestion_type` TEXT NOT NULL,
    `title` TEXT NOT NULL,
    `summary` TEXT NOT NULL,
    `payload` TEXT NULL,
    `status` TEXT NOT NULL DEFAULT 'OPEN',
    `created_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `deleted` INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT fk_agent_reflection_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE
);

CREATE INDEX agent_reflection_idx_agent_reflection_user ON `agent_reflection` (user_id, status, deleted);

CREATE INDEX agent_reflection_idx_agent_reflection_trace ON `agent_reflection` (trace_id);

CREATE TABLE `agent_context_summary` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `user_id` INTEGER NOT NULL,
    `conversation_id` INTEGER NULL,
    `trace_id` TEXT NULL,
    `scope` TEXT NOT NULL DEFAULT 'CONVERSATION',
    `summary` TEXT NOT NULL,
    `source_refs` TEXT NULL,
    `source_hash` TEXT NULL,
    `covered_from_message_id` INTEGER NULL,
    `covered_until_message_id` INTEGER NULL,
    `original_tokens` INTEGER NOT NULL DEFAULT 0,
    `compressed_tokens` INTEGER NOT NULL DEFAULT 0,
    `created_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `deleted` INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT fk_context_summary_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE
);

CREATE INDEX agent_context_summary_idx_context_summary_conversation ON `agent_context_summary` (user_id, conversation_id, scope, deleted, updated_at);

CREATE INDEX agent_context_summary_idx_context_summary_source ON `agent_context_summary` (user_id, scope, source_hash, deleted);

CREATE INDEX agent_context_summary_idx_context_summary_covered ON `agent_context_summary` (user_id, covered_until_message_id, deleted);

CREATE INDEX agent_context_summary_idx_context_summary_user ON `agent_context_summary` (user_id, deleted, updated_at);

CREATE INDEX agent_context_summary_idx_context_summary_trace ON `agent_context_summary` (trace_id);

CREATE TABLE `transaction` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `user_id` INTEGER NOT NULL,
    `amount` NUMERIC NOT NULL,
    `type` TEXT NOT NULL DEFAULT 'EXPENSE',
    `category` TEXT NOT NULL,
    `description` TEXT NULL,
    `transaction_date` TEXT NOT NULL,
    `created_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `deleted` INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT fk_transaction_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE
);

CREATE INDEX transaction_idx_user_id ON `transaction` (user_id);

CREATE INDEX transaction_idx_user_date ON `transaction` (user_id, transaction_date);

CREATE INDEX transaction_idx_category ON `transaction` (category);

CREATE TABLE `expense_category` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `user_id` INTEGER NOT NULL DEFAULT 0,
    `name` TEXT NOT NULL,
    `icon` TEXT NULL,
    `benchmark_min` INTEGER NULL,
    `benchmark_max` INTEGER NULL,
    `benchmark_label` TEXT NULL,
    `sort_order` INTEGER NOT NULL DEFAULT 0,
    `created_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `deleted` INTEGER NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX expense_category_uk_user_category ON `expense_category` (user_id, name);

CREATE INDEX expense_category_idx_user_sort ON `expense_category` (user_id, sort_order);

CREATE TABLE `chat_conversation` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `user_id` INTEGER NOT NULL,
    `title` TEXT NOT NULL DEFAULT '新对话',
    `created_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `deleted` INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT fk_chat_conversation_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE
);

CREATE INDEX chat_conversation_idx_chat_conversation_user ON `chat_conversation` (user_id, deleted, updated_at);

CREATE TABLE `chat_message` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `user_id` INTEGER NOT NULL,
    `role` TEXT NOT NULL,
    `content` TEXT NOT NULL,
    `trace_id` TEXT NULL,
    `created_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `deleted` INTEGER NOT NULL DEFAULT 0,
    `conversation_id` INTEGER NULL,
    CONSTRAINT fk_chat_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE
);

CREATE INDEX chat_message_idx_chat_conversation ON `chat_message` (conversation_id);

CREATE INDEX chat_message_idx_chat_trace ON `chat_message` (trace_id);

CREATE INDEX chat_message_idx_user_id ON `chat_message` (user_id);

CREATE INDEX chat_message_idx_created_at ON `chat_message` (created_at);

CREATE TABLE `pending_action` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `user_id` INTEGER NOT NULL,
    `action_type` TEXT NOT NULL,
    `title` TEXT NOT NULL,
    `summary` TEXT NOT NULL,
    `payload` TEXT NOT NULL,
    `status` TEXT NOT NULL DEFAULT 'PENDING',
    `created_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_pending_action_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE
);

CREATE INDEX pending_action_idx_pending_user_status ON `pending_action` (user_id, status);

CREATE TABLE `budget` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `user_id` INTEGER NOT NULL,
    `category` TEXT NOT NULL,
    `month` TEXT NOT NULL,
    `budget_amount` NUMERIC NOT NULL,
    `alert_threshold` INTEGER NOT NULL DEFAULT 80,
    `created_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `deleted` INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT fk_budget_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX budget_uk_user_category_month ON `budget` (user_id, category, month);

CREATE INDEX budget_idx_user_month ON `budget` (user_id, month);

CREATE TABLE `budget_alert` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `user_id` INTEGER NOT NULL,
    `category` TEXT NOT NULL,
    `month` TEXT NOT NULL,
    `alert_type` TEXT NOT NULL,
    `severity` TEXT NOT NULL DEFAULT 'INFO',
    `spent_amount` NUMERIC NOT NULL,
    `budget_amount` NUMERIC NOT NULL,
    `usage_percent` NUMERIC NOT NULL,
    `message` TEXT NOT NULL,
    `is_read` INTEGER NOT NULL DEFAULT 0,
    `created_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `deleted` INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT fk_alert_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE
);

CREATE INDEX budget_alert_idx_user_read ON `budget_alert` (user_id, is_read);

CREATE INDEX budget_alert_idx_user_month ON `budget_alert` (user_id, month);

CREATE TABLE `bill_import_record` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `user_id` INTEGER NOT NULL,
    `original_filename` TEXT NULL,
    `file_path` TEXT NOT NULL,
    `bill_type` TEXT NOT NULL DEFAULT 'UNKNOWN',
    `confidence` NUMERIC NOT NULL DEFAULT 0,
    `ocr_text` TEXT NULL,
    `warnings` TEXT NULL,
    `status` TEXT NOT NULL DEFAULT 'PENDING',
    `created_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `deleted` INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT fk_bill_import_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE
);

CREATE INDEX bill_import_record_idx_bill_user ON `bill_import_record` (user_id);

CREATE INDEX bill_import_record_idx_bill_status ON `bill_import_record` (user_id, status);

CREATE TABLE `bill_candidate_transaction` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `bill_import_id` INTEGER NOT NULL,
    `user_id` INTEGER NOT NULL,
    `amount` NUMERIC NOT NULL,
    `type` TEXT NOT NULL DEFAULT 'EXPENSE',
    `category` TEXT NOT NULL DEFAULT '其他',
    `description` TEXT NULL,
    `transaction_date` TEXT NOT NULL,
    `confidence` NUMERIC NOT NULL DEFAULT 0,
    `status` TEXT NOT NULL DEFAULT 'PENDING',
    `transaction_id` INTEGER NULL,
    `created_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `deleted` INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT fk_candidate_bill FOREIGN KEY (bill_import_id) REFERENCES `bill_import_record` (id) ON DELETE CASCADE,
    CONSTRAINT fk_candidate_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE
);

CREATE INDEX bill_candidate_transaction_idx_candidate_bill ON `bill_candidate_transaction` (bill_import_id);

CREATE INDEX bill_candidate_transaction_idx_candidate_user ON `bill_candidate_transaction` (user_id, status);

CREATE TABLE `analysis_record` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `user_id` INTEGER NOT NULL,
    `trace_id` TEXT NULL,
    `query` TEXT NOT NULL,
    `plan` TEXT NULL,
    `steps_result` TEXT NULL,
    `final_answer` TEXT NULL,
    `score` INTEGER NULL,
    `feedback` TEXT NULL,
    `tokens_used` INTEGER NULL,
    `created_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `deleted` INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT fk_analysis_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE
);

CREATE INDEX analysis_record_idx_user_id ON `analysis_record` (user_id);

CREATE INDEX analysis_record_idx_created_at ON `analysis_record` (created_at);

CREATE INDEX analysis_record_idx_analysis_trace ON `analysis_record` (trace_id);

CREATE TABLE `agent_schedule` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `user_id` INTEGER NOT NULL,
    `trace_id` TEXT NULL,
    `name` TEXT NOT NULL,
    `description` TEXT NULL,
    `cron_expression` TEXT NOT NULL,
    `timezone` TEXT NOT NULL DEFAULT 'Asia/Shanghai',
    `task_query` TEXT NOT NULL,
    `enabled` INTEGER NOT NULL DEFAULT 1,
    `last_run_at` TEXT NULL,
    `next_run_at` TEXT NULL,
    `lock_until` TEXT NULL,
    `run_count` INTEGER NOT NULL DEFAULT 0,
    `consecutive_failures` INTEGER NOT NULL DEFAULT 0,
    `last_status` TEXT NULL,
    `last_answer` TEXT NULL,
    `created_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `deleted` INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT fk_agent_schedule_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE
);

CREATE INDEX agent_schedule_idx_agent_schedule_user ON `agent_schedule` (user_id, enabled, deleted);

CREATE INDEX agent_schedule_idx_agent_schedule_next_run ON `agent_schedule` (enabled, next_run_at);

CREATE INDEX agent_schedule_idx_agent_schedule_lock ON `agent_schedule` (enabled, lock_until);

CREATE TABLE `agent_schedule_run` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `schedule_id` INTEGER NOT NULL,
    `user_id` INTEGER NOT NULL,
    `trace_id` TEXT NULL,
    `status` TEXT NOT NULL,
    `answer` TEXT NULL,
    `error_message` TEXT NULL,
    `started_at` TEXT NOT NULL,
    `finished_at` TEXT NOT NULL,
    `duration_ms` INTEGER NOT NULL DEFAULT 0,
    `created_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_agent_schedule_run_schedule FOREIGN KEY (schedule_id) REFERENCES `agent_schedule` (id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_schedule_run_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE
);

CREATE INDEX agent_schedule_run_idx_agent_schedule_run_schedule ON `agent_schedule_run` (schedule_id, created_at);

CREATE INDEX agent_schedule_run_idx_agent_schedule_run_user ON `agent_schedule_run` (user_id, created_at);

CREATE INDEX agent_schedule_run_idx_agent_schedule_run_trace ON `agent_schedule_run` (trace_id);

CREATE TABLE `investment_product` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `product_type` TEXT NOT NULL,
    `market` TEXT NOT NULL,
    `code` TEXT NOT NULL,
    `name` TEXT NOT NULL,
    `currency` TEXT NOT NULL DEFAULT 'CNY',
    `status` TEXT NOT NULL DEFAULT 'ACTIVE',
    `created_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `inception_date` TEXT NULL,
    `history_start_date` TEXT NULL,
    `history_end_date` TEXT NULL,
    `history_coverage_complete` INTEGER NOT NULL DEFAULT FALSE,
    `fund_type_raw` TEXT NULL,
    `fund_category` TEXT NULL,
    `classification_source` TEXT NULL,
    `classification_version` TEXT NULL,
    `classified_at` TEXT NULL
);

CREATE INDEX investment_product_idx_investment_product_fund_category ON `investment_product` (product_type, fund_category);

CREATE UNIQUE INDEX investment_product_uk_investment_product ON `investment_product` (product_type, market, code);

CREATE TABLE `investment_account` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `user_id` INTEGER NOT NULL,
    `account_name` TEXT NOT NULL,
    `account_type` TEXT NOT NULL,
    `base_currency` TEXT NOT NULL DEFAULT 'CNY',
    `created_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `deleted` INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX investment_account_idx_investment_account_user ON `investment_account` (user_id, deleted);

CREATE TABLE `investment_transaction` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `user_id` INTEGER NOT NULL,
    `account_id` INTEGER NOT NULL,
    `product_id` INTEGER NULL,
    `event_type` TEXT NOT NULL,
    `trade_date` TEXT NOT NULL,
    `settlement_date` TEXT NULL,
    `currency` TEXT NOT NULL,
    `quantity` NUMERIC NULL,
    `price` NUMERIC NULL,
    `amount` NUMERIC NULL,
    `fee` NUMERIC NOT NULL DEFAULT 0,
    `factor` NUMERIC NULL,
    `source` TEXT NOT NULL DEFAULT 'MANUAL',
    `external_ref` TEXT NULL,
    `reversal_transaction_id` INTEGER NULL,
    `note` TEXT NULL,
    `created_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX investment_transaction_uk_investment_transaction_ref ON `investment_transaction` (user_id, source, external_ref);

CREATE INDEX investment_transaction_idx_investment_transaction_account ON `investment_transaction` (user_id, account_id, trade_date);

CREATE INDEX investment_transaction_idx_investment_transaction_product ON `investment_transaction` (account_id, product_id, trade_date);

CREATE TABLE `investment_cash_balance` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `user_id` INTEGER NOT NULL,
    `account_id` INTEGER NOT NULL,
    `currency` TEXT NOT NULL,
    `balance` NUMERIC NOT NULL DEFAULT 0,
    `updated_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX investment_cash_balance_uk_investment_cash_balance ON `investment_cash_balance` (account_id, currency);

CREATE INDEX investment_cash_balance_idx_investment_cash_user ON `investment_cash_balance` (user_id, account_id);

CREATE TABLE `investment_cash_ledger` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `user_id` INTEGER NOT NULL,
    `account_id` INTEGER NOT NULL,
    `transaction_id` INTEGER NOT NULL,
    `currency` TEXT NOT NULL,
    `event_type` TEXT NOT NULL,
    `amount` NUMERIC NOT NULL,
    `external_flow` INTEGER NOT NULL DEFAULT 0,
    `occurred_on` TEXT NOT NULL,
    `created_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX investment_cash_ledger_uk_investment_cash_transaction ON `investment_cash_ledger` (transaction_id);

CREATE INDEX investment_cash_ledger_idx_investment_cash_ledger_account ON `investment_cash_ledger` (user_id, account_id, occurred_on);

CREATE TABLE `investment_position` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `user_id` INTEGER NOT NULL,
    `account_id` INTEGER NOT NULL,
    `product_id` INTEGER NOT NULL,
    `quantity` NUMERIC NOT NULL DEFAULT 0,
    `cost_amount` NUMERIC NOT NULL DEFAULT 0,
    `average_cost` NUMERIC NOT NULL DEFAULT 0,
    `realized_pnl` NUMERIC NOT NULL DEFAULT 0,
    `latest_price` NUMERIC NULL,
    `market_value_cny` NUMERIC NULL,
    `unrealized_pnl_cny` NUMERIC NULL,
    `data_date` TEXT NULL,
    `updated_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX investment_position_uk_investment_position ON `investment_position` (account_id, product_id);

CREATE INDEX investment_position_idx_investment_position_user ON `investment_position` (user_id, account_id);

CREATE TABLE `product_daily_quote` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `product_id` INTEGER NOT NULL,
    `trade_date` TEXT NOT NULL,
    `open_price` NUMERIC NULL,
    `high_price` NUMERIC NULL,
    `low_price` NUMERIC NULL,
    `close_price` NUMERIC NOT NULL,
    `volume` NUMERIC NULL,
    `adjust_type` TEXT NOT NULL DEFAULT 'NONE',
    `source` TEXT NOT NULL,
    `adapter_version` TEXT NULL,
    `synced_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `previous_close` NUMERIC NULL,
    `change_amount` NUMERIC NULL,
    `change_percent` NUMERIC NULL,
    `amount` NUMERIC NULL,
    `turnover_rate` NUMERIC NULL,
    `volume_ratio` NUMERIC NULL,
    `amplitude` NUMERIC NULL,
    `total_return_index` NUMERIC NULL
);

CREATE UNIQUE INDEX product_daily_quote_uk_product_daily_quote ON `product_daily_quote` (product_id, trade_date, adjust_type);

CREATE TABLE `investment_import_batch` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `user_id` INTEGER NOT NULL,
    `original_filename` TEXT NOT NULL,
    `status` TEXT NOT NULL,
    `payload` TEXT NOT NULL,
    `row_count` INTEGER NOT NULL DEFAULT 0,
    `error_count` INTEGER NOT NULL DEFAULT 0,
    `created_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX investment_import_batch_idx_investment_import_user ON `investment_import_batch` (user_id, status);

CREATE TABLE `investment_sync_batch` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `user_id` INTEGER NOT NULL,
    `job_type` TEXT NOT NULL,
    `provider` TEXT NULL,
    `status` TEXT NOT NULL,
    `rows_success` INTEGER NOT NULL DEFAULT 0,
    `rows_failed` INTEGER NOT NULL DEFAULT 0,
    `error_message` TEXT NULL,
    `started_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `finished_at` TEXT NULL
);

CREATE INDEX investment_sync_batch_idx_investment_sync_user ON `investment_sync_batch` (user_id, started_at);

CREATE TABLE `investment_plan` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `user_id` INTEGER NOT NULL,
    `account_id` INTEGER NOT NULL,
    `product_id` INTEGER NOT NULL,
    `amount` NUMERIC NOT NULL,
    `currency` TEXT NOT NULL,
    `frequency` TEXT NOT NULL,
    `execution_day` INTEGER NOT NULL,
    `next_execution_date` TEXT NOT NULL,
    `last_execution_date` TEXT NULL,
    `last_execution_amount` NUMERIC NULL,
    `last_execution_price` NUMERIC NULL,
    `execution_count` INTEGER NOT NULL DEFAULT 0,
    `last_execution_status` TEXT NOT NULL DEFAULT 'WAITING',
    `last_execution_message` TEXT NULL,
    `enabled` INTEGER NOT NULL DEFAULT 1,
    `created_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX investment_plan_idx_investment_plan_due ON `investment_plan` (enabled, next_execution_date);

CREATE INDEX investment_plan_idx_investment_plan_user ON `investment_plan` (user_id, enabled, next_execution_date);

CREATE TABLE `daily_exchange_rate` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `base_currency` TEXT NOT NULL,
    `quote_currency` TEXT NOT NULL,
    `rate_date` TEXT NOT NULL,
    `rate` NUMERIC NOT NULL,
    `source` TEXT NOT NULL,
    `adapter_version` TEXT,
    `synced_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX daily_exchange_rate_uk_daily_exchange_rate ON `daily_exchange_rate` (base_currency, quote_currency, rate_date);

CREATE TABLE `investment_asset` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `user_id` INTEGER NOT NULL,
    `account_id` INTEGER NOT NULL,
    `product_id` INTEGER NOT NULL,
    `quantity` NUMERIC,
    `average_cost` NUMERIC,
    `note` TEXT,
    `current_transaction_id` INTEGER,
    `sync_status` TEXT NOT NULL DEFAULT 'NOT_SYNCED',
    `sync_error` TEXT,
    `created_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `deleted` INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX investment_asset_idx_investment_asset_user ON `investment_asset` (user_id, deleted, updated_at);

CREATE INDEX investment_asset_idx_investment_asset_product ON `investment_asset` (user_id, product_id, deleted);

CREATE TABLE `wealth_baseline` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `user_id` INTEGER NOT NULL,
    `entered_total_assets` NUMERIC NOT NULL,
    `baseline_non_investment_balance` NUMERIC NOT NULL,
    `baseline_at` TEXT NOT NULL,
    `created_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX wealth_baseline_uk_wealth_baseline_user ON `wealth_baseline` (user_id);

CREATE TABLE `investment_horizon_profile` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `user_id` INTEGER NOT NULL,
    `scope_type` TEXT NOT NULL,
    `asset_id` INTEGER,
    `version` INTEGER NOT NULL,
    `template_version` TEXT NOT NULL,
    `source` TEXT NOT NULL,
    `active` INTEGER NOT NULL DEFAULT 1,
    `effective_from` TEXT NOT NULL,
    `created_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX investment_horizon_profile_idx_horizon_profile_resolution ON `investment_horizon_profile` (user_id, scope_type, asset_id, active, version);

CREATE TABLE `investment_horizon_setting` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `profile_id` INTEGER NOT NULL,
    `horizon_code` TEXT NOT NULL,
    `display_name` TEXT NOT NULL,
    `sort_order` INTEGER NOT NULL,
    `min_holding_days` INTEGER NOT NULL,
    `max_holding_days` INTEGER NOT NULL,
    `target_holding_days` INTEGER NOT NULL,
    `is_primary` INTEGER NOT NULL DEFAULT 0,
    `created_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_horizon_setting_profile FOREIGN KEY (profile_id) REFERENCES `investment_horizon_profile` (id)
);

CREATE UNIQUE INDEX investment_horizon_setting_uk_horizon_setting_profile_code ON `investment_horizon_setting` (profile_id, horizon_code);

CREATE INDEX investment_horizon_setting_idx_horizon_setting_profile ON `investment_horizon_setting` (profile_id, sort_order);

CREATE TABLE `investment_analysis_snapshot` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `user_id` INTEGER NOT NULL,
    `asset_id` INTEGER NOT NULL,
    `rule_version` TEXT NOT NULL,
    `preference_hash` TEXT NOT NULL,
    `horizon_profile_version` TEXT,
    `horizon_config_json` TEXT,
    `dataset_version` TEXT,
    `quality_rule_set_version` TEXT,
    `strategy_version` TEXT,
    `analysis_cache_key` TEXT,
    `quality_status` TEXT,
    `historical_cache` INTEGER NOT NULL DEFAULT 0,
    `signal_hash` TEXT,
    `quote_date` TEXT,
    `technical_json` TEXT,
    `fundamental_json` TEXT,
    `fund_json` TEXT,
    `backtest_json` TEXT,
    `source_status_json` TEXT,
    `ai_explanation` TEXT,
    `analysis_status` TEXT NOT NULL DEFAULT 'READY',
    `analyzed_at` TEXT NOT NULL,
    `ai_updated_at` TEXT,
    `created_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX investment_analysis_snapshot_uk_analysis_snapshot_user_asset_rule ON `investment_analysis_snapshot` (user_id, asset_id, rule_version);

CREATE INDEX investment_analysis_snapshot_idx_analysis_cache_key ON `investment_analysis_snapshot` (user_id, asset_id, analysis_cache_key);

CREATE INDEX investment_analysis_snapshot_idx_analysis_dataset_version ON `investment_analysis_snapshot` (dataset_version);

CREATE TABLE `investment_data_quality_snapshot` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `dataset_version` TEXT NOT NULL,
    `product_type` TEXT NOT NULL,
    `code` TEXT NOT NULL,
    `market` TEXT NOT NULL,
    `frequency` TEXT NOT NULL,
    `adjust_type` TEXT NOT NULL,
    `provider` TEXT NOT NULL,
    `adapter_version` TEXT NOT NULL,
    `quality_config_version` TEXT NOT NULL,
    `quality_rule_set_version` TEXT NOT NULL,
    `quality_status` TEXT NOT NULL,
    `decision` TEXT NOT NULL,
    `enforcement_mode` TEXT NOT NULL,
    `requested_start_date` TEXT NOT NULL,
    `requested_end_date` TEXT NOT NULL,
    `sample_start_date` TEXT NOT NULL,
    `sample_end_date` TEXT NOT NULL,
    `fetched_at` TEXT NOT NULL,
    `evaluated_at` TEXT NOT NULL,
    `manifest_json` TEXT NOT NULL,
    `report_json` TEXT NOT NULL,
    `secondary_dataset_versions_json` TEXT,
    `created_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX investment_data_quality_snapshot_uk_data_quality_dataset_rule ON `investment_data_quality_snapshot` (dataset_version, quality_config_version);

CREATE INDEX investment_data_quality_snapshot_idx_data_quality_dataset_version ON `investment_data_quality_snapshot` (dataset_version);

CREATE INDEX investment_data_quality_snapshot_idx_data_quality_product_range ON `investment_data_quality_snapshot` (product_type, code, market, requested_start_date, requested_end_date);

CREATE INDEX investment_data_quality_snapshot_idx_data_quality_status ON `investment_data_quality_snapshot` (quality_status, evaluated_at);

CREATE TABLE `investment_data_quality_issue` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `quality_snapshot_id` INTEGER NOT NULL,
    `sequence_no` INTEGER NOT NULL,
    `rule_code` TEXT NOT NULL,
    `severity` TEXT NOT NULL,
    `outcome` TEXT NOT NULL,
    `message` TEXT NOT NULL,
    `observed_json` TEXT,
    `expected_json` TEXT,
    `affected_dates_json` TEXT,
    `created_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_data_quality_issue_snapshot FOREIGN KEY (quality_snapshot_id) REFERENCES `investment_data_quality_snapshot` (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX investment_data_quality_issue_uk_data_quality_issue_order ON `investment_data_quality_issue` (quality_snapshot_id, sequence_no);

CREATE INDEX investment_data_quality_issue_idx_data_quality_issue_rule ON `investment_data_quality_issue` (rule_code, severity);

CREATE TABLE `investment_data_job` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `user_id` INTEGER NOT NULL,
    `asset_id` INTEGER NOT NULL,
    `product_id` INTEGER NOT NULL,
    `job_type` TEXT NOT NULL,
    `status` TEXT NOT NULL,
    `force_refresh` INTEGER NOT NULL DEFAULT 0,
    `record_count` INTEGER NOT NULL DEFAULT 0,
    `attempt_count` INTEGER NOT NULL DEFAULT 0,
    `next_retry_at` TEXT,
    `lease_until` TEXT,
    `lease_token` TEXT,
    `error_message` TEXT,
    `started_at` TEXT,
    `finished_at` TEXT,
    `created_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `requested_start_date` TEXT NULL,
    `sample_start_date` TEXT NULL,
    `sample_end_date` TEXT NULL,
    `coverage_complete` INTEGER NULL,
    `dataset_version` TEXT NULL
);

CREATE UNIQUE INDEX investment_data_job_uk_investment_data_job_asset_type ON `investment_data_job` (asset_id, job_type);

CREATE INDEX investment_data_job_idx_investment_data_job_pending ON `investment_data_job` (status, next_retry_at, created_at);

CREATE TABLE `benchmark_profile` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `product_type` TEXT NOT NULL,
    `product_code` TEXT,
    `model_family` TEXT NOT NULL,
    `benchmark_code` TEXT NOT NULL,
    `display_name` TEXT NOT NULL,
    `composition_json` TEXT NOT NULL,
    `currency` TEXT NOT NULL,
    `fx_rule` TEXT,
    `source_uri` TEXT NOT NULL,
    `source_version` TEXT NOT NULL,
    `effective_from` TEXT NOT NULL,
    `effective_to` TEXT,
    `active` INTEGER NOT NULL DEFAULT 1,
    `created_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX benchmark_profile_uk_benchmark_profile_version ON `benchmark_profile` (product_type, product_code, effective_from);

CREATE INDEX benchmark_profile_idx_benchmark_profile_lookup ON `benchmark_profile` (product_type, product_code, active, effective_from);

CREATE TABLE `quant_benchmark_snapshot` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `snapshot_version` TEXT NOT NULL,
    `benchmark_profile_id` INTEGER NOT NULL,
    `benchmark_code` TEXT NOT NULL,
    `source_uri` TEXT NOT NULL,
    `source_version` TEXT NOT NULL,
    `provider` TEXT,
    `adapter_version` TEXT,
    `currency` TEXT NOT NULL,
    `fx_rule` TEXT,
    `effective_from` TEXT NOT NULL,
    `effective_to` TEXT,
    `sample_start_date` TEXT NOT NULL,
    `sample_end_date` TEXT NOT NULL,
    `fetched_at` TEXT NOT NULL,
    `records_json` TEXT NOT NULL,
    `created_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX quant_benchmark_snapshot_uk_quant_benchmark_snapshot_version ON `quant_benchmark_snapshot` (snapshot_version);

CREATE INDEX quant_benchmark_snapshot_idx_quant_benchmark_snapshot_lookup ON `quant_benchmark_snapshot` (benchmark_profile_id, sample_start_date, sample_end_date, fetched_at);

CREATE TABLE `investment_index_watchlist` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `user_id` INTEGER NOT NULL,
    `index_code` TEXT NOT NULL,
    `display_name` TEXT NOT NULL,
    `market` TEXT NOT NULL,
    `created_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `deleted` INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX investment_index_watchlist_idx_investment_index_watchlist_user ON `investment_index_watchlist` (user_id, deleted, created_at);

CREATE INDEX investment_index_watchlist_idx_investment_index_watchlist_code ON `investment_index_watchlist` (user_id, index_code, deleted);

CREATE TABLE `investment_index_display_order` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    `user_id` INTEGER NOT NULL,
    `index_code` TEXT NOT NULL,
    `sort_order` INTEGER NOT NULL,
    `created_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX investment_index_display_order_uk_investment_index_display_order ON `investment_index_display_order` (user_id, index_code);

CREATE INDEX investment_index_display_order_idx_investment_index_display_order_user ON `investment_index_display_order` (user_id, sort_order);

CREATE TABLE `quant_v2_object` (
    `id` TEXT NOT NULL PRIMARY KEY,
    `user_id` INTEGER NOT NULL,
    `kind` TEXT NOT NULL,
    `name` TEXT NOT NULL,
    `status` TEXT NOT NULL,
    `revision` INTEGER NOT NULL,
    `payload` TEXT NOT NULL,
    `created_at` TEXT NOT NULL,
    `updated_at` TEXT NOT NULL
);

CREATE INDEX quant_v2_object_idx_qv2_object_owner ON `quant_v2_object` (user_id,kind,status);

CREATE TABLE `quant_v2_version` (
    `id` TEXT NOT NULL PRIMARY KEY,
    `user_id` INTEGER NOT NULL,
    `object_id` TEXT NOT NULL,
    `version` INTEGER NOT NULL,
    `payload` TEXT NOT NULL,
    `created_at` TEXT NOT NULL,
    UNIQUE(object_id,version)
);

CREATE INDEX quant_v2_version_idx_qv2_version_owner ON `quant_v2_version` (user_id,object_id);

CREATE TABLE `quant_v2_task` (
    `id` TEXT NOT NULL PRIMARY KEY,
    `user_id` INTEGER NOT NULL,
    `kind` TEXT NOT NULL,
    `name` TEXT NOT NULL,
    `status` TEXT NOT NULL,
    `stage` TEXT NOT NULL,
    `strategy_id` TEXT,
    `strategy_version_id` TEXT,
    `universe_id` TEXT,
    `request_json` TEXT NOT NULL,
    `result_json` TEXT,
    `error_code` TEXT,
    `error_message` TEXT,
    `claim_token` TEXT,
    `lease_until` INTEGER,
    `created_at` TEXT NOT NULL,
    `updated_at` TEXT NOT NULL
);

CREATE INDEX quant_v2_task_idx_qv2_task_owner ON `quant_v2_task` (user_id,kind,created_at);

CREATE INDEX quant_v2_task_idx_qv2_task_claim ON `quant_v2_task` (status,lease_until);

CREATE TABLE `quant_v2_deployment` (
    `id` TEXT NOT NULL PRIMARY KEY,
    `user_id` INTEGER NOT NULL,
    `name` TEXT NOT NULL,
    `backtest_id` TEXT NOT NULL,
    `strategy_id` TEXT NOT NULL,
    `universe_id` TEXT NOT NULL,
    `status` TEXT NOT NULL,
    `revision` INTEGER NOT NULL,
    `request_json` TEXT NOT NULL,
    `result_json` TEXT,
    `error_code` TEXT,
    `error_message` TEXT,
    `claim_token` TEXT,
    `lease_until` INTEGER,
    `next_run_at` INTEGER NOT NULL,
    `created_at` TEXT NOT NULL,
    `updated_at` TEXT NOT NULL
);

CREATE INDEX quant_v2_deployment_idx_qv2_deployment_owner ON `quant_v2_deployment` (user_id,status);

CREATE TABLE `quant_v2_paper_event` (
    `id` TEXT NOT NULL PRIMARY KEY,
    `user_id` INTEGER NOT NULL,
    `deployment_id` TEXT NOT NULL,
    `event_key` TEXT NOT NULL,
    `kind` TEXT NOT NULL,
    `payload` TEXT NOT NULL,
    `created_at` TEXT NOT NULL,
    UNIQUE(deployment_id,event_key)
);

CREATE INDEX quant_v2_paper_event_idx_qv2_event_owner ON `quant_v2_paper_event` (user_id,deployment_id);

CREATE TABLE `quant_v2_research_snapshot` (
    `id` TEXT NOT NULL PRIMARY KEY,
    `user_id` INTEGER NOT NULL,
    `format_version` TEXT NOT NULL,
    `content_hash` TEXT NOT NULL,
    `compression` TEXT NOT NULL,
    `payload_blob` BLOB NOT NULL,
    `metadata_json` TEXT NOT NULL,
    `uncompressed_bytes` INTEGER NOT NULL,
    `compressed_bytes` INTEGER NOT NULL,
    `created_at` TEXT NOT NULL
);

CREATE UNIQUE INDEX quant_v2_research_snapshot_uk_qv2_research_snapshot_content ON `quant_v2_research_snapshot` (user_id,format_version,content_hash);

CREATE TABLE `quant_v2_experiment` (
    `id` TEXT NOT NULL PRIMARY KEY,
    `user_id` INTEGER NOT NULL,
    `name` TEXT NOT NULL,
    `status` TEXT NOT NULL,
    `source_backtest_id` TEXT NOT NULL,
    `strategy_id` TEXT NOT NULL,
    `strategy_version_id` TEXT NOT NULL,
    `universe_id` TEXT NOT NULL,
    `universe_version_id` TEXT NOT NULL,
    `factor_id` TEXT,
    `factor_version_id` TEXT,
    `snapshot_id` TEXT NOT NULL,
    `model_ref` TEXT,
    `model_task_id` TEXT,
    `variable_definition_json` TEXT NOT NULL,
    `baseline_values_json` TEXT NOT NULL,
    `candidate_values_json` TEXT NOT NULL,
    `base_request_json` TEXT NOT NULL,
    `source_context_json` TEXT NOT NULL,
    `environment_json` TEXT NOT NULL,
    `candidate_rule_version` TEXT NOT NULL,
    `stability_algorithm_version` TEXT NOT NULL,
    `invariant_hash` TEXT NOT NULL,
    `summary_json` TEXT,
    `request_key` TEXT,
    `request_hash` TEXT,
    `revision` INTEGER NOT NULL,
    `created_at` TEXT NOT NULL,
    `updated_at` TEXT NOT NULL,
    `completed_at` TEXT,
    `cancelled_at` TEXT
);

CREATE INDEX quant_v2_experiment_idx_qv2_experiment_owner ON `quant_v2_experiment` (user_id,status,created_at);

CREATE INDEX quant_v2_experiment_idx_qv2_experiment_strategy ON `quant_v2_experiment` (user_id,strategy_id,strategy_version_id);

CREATE UNIQUE INDEX quant_v2_experiment_uk_qv2_experiment_request ON `quant_v2_experiment` (user_id,request_key);

CREATE TABLE `quant_v2_experiment_run` (
    `id` TEXT NOT NULL PRIMARY KEY,
    `user_id` INTEGER NOT NULL,
    `experiment_id` TEXT NOT NULL,
    `ordinal` INTEGER NOT NULL,
    `variable_values_json` TEXT NOT NULL,
    `value_hash` TEXT NOT NULL,
    `baseline` INTEGER NOT NULL,
    `active_attempt_id` TEXT,
    `created_at` TEXT NOT NULL
);

CREATE INDEX quant_v2_experiment_run_idx_qv2_experiment_run_owner ON `quant_v2_experiment_run` (user_id,experiment_id);

CREATE UNIQUE INDEX quant_v2_experiment_run_uk_qv2_experiment_run_value ON `quant_v2_experiment_run` (experiment_id,value_hash);

CREATE UNIQUE INDEX quant_v2_experiment_run_uk_qv2_experiment_run_ordinal ON `quant_v2_experiment_run` (experiment_id,ordinal);

CREATE TABLE `quant_v2_experiment_run_attempt` (
    `id` TEXT NOT NULL PRIMARY KEY,
    `user_id` INTEGER NOT NULL,
    `run_id` TEXT NOT NULL,
    `attempt_no` INTEGER NOT NULL,
    `reason` TEXT NOT NULL,
    `task_id` TEXT NOT NULL,
    `created_at` TEXT NOT NULL
);

CREATE INDEX quant_v2_experiment_run_attempt_idx_qv2_experiment_attempt_owner ON `quant_v2_experiment_run_attempt` (user_id,run_id);

CREATE UNIQUE INDEX quant_v2_experiment_run_attempt_uk_qv2_experiment_attempt_number ON `quant_v2_experiment_run_attempt` (run_id,attempt_no);

CREATE UNIQUE INDEX quant_v2_experiment_run_attempt_uk_qv2_experiment_attempt_task ON `quant_v2_experiment_run_attempt` (task_id);
