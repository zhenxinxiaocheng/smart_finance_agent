-- 用户表
CREATE TABLE IF NOT EXISTS `user`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `username`    VARCHAR(50)  NOT NULL COMMENT '用户名',
    `password`    VARCHAR(255) NOT NULL COMMENT '加密密码',
    `nickname`    VARCHAR(50)  NULL     COMMENT '昵称',
    `email`       VARCHAR(100) NULL     COMMENT '邮箱',
    `avatar`      VARCHAR(255) NULL     COMMENT '头像URL',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除(0-未删,1-已删)',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_username` (`username`),
    INDEX `idx_created_at` (`created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='用户表';

-- 用户长期财务画像表
CREATE TABLE IF NOT EXISTS `financial_profile`
(
    `id`                    BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id`               BIGINT        NOT NULL COMMENT '用户ID',
    `life_stage`            VARCHAR(50)   NULL     COMMENT '身份阶段',
    `monthly_income`        DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '月收入',
    `fixed_expense`         DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '固定支出',
    `risk_preference`       VARCHAR(30)   NULL     COMMENT '风险偏好',
    `savings_goal_amount`   DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '储蓄目标金额',
    `savings_goal_deadline` VARCHAR(7)    NULL     COMMENT '储蓄目标期限yyyy-MM',
    `monthly_budget_goal`   DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '月度总预算目标',
    `notes`                 VARCHAR(500)  NULL     COMMENT '补充偏好',
    `created_at`            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_profile_user` (`user_id`),
    CONSTRAINT `fk_financial_profile_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='用户长期财务画像表';

-- 交易记录表
CREATE TABLE IF NOT EXISTS `agent_memory`
(
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `user_id`      BIGINT       NOT NULL COMMENT 'User ID',
    `memory_type`  VARCHAR(50)  NOT NULL COMMENT 'CATEGORY_PREFERENCE/RESPONSE_STYLE/ANALYSIS_PREFERENCE/AGENT_PREFERENCE',
    `memory_key`   VARCHAR(100) NOT NULL COMMENT 'Memory key',
    `memory_value` TEXT         NOT NULL COMMENT 'Memory value',
    `confidence`   DOUBLE       NOT NULL DEFAULT 1 COMMENT 'Extraction confidence',
    `source_query` VARCHAR(500) NULL     COMMENT 'Source user query',
    `disabled`     TINYINT      NOT NULL DEFAULT 0 COMMENT 'Disabled flag',
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
    `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated time',
    `deleted`      TINYINT      NOT NULL DEFAULT 0 COMMENT 'Logic delete flag',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_agent_memory_key` (`user_id`, `memory_type`, `memory_key`),
    INDEX `idx_agent_memory_user` (`user_id`, `disabled`, `deleted`),
    CONSTRAINT `fk_agent_memory_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='Agent lightweight long-term memories';

ALTER TABLE `agent_memory` MODIFY COLUMN `memory_value` TEXT NOT NULL COMMENT 'Memory value';

CREATE TABLE IF NOT EXISTS `agent_skill`
(
    `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `user_id`          BIGINT       NOT NULL COMMENT 'User ID',
    `skill_key`        VARCHAR(120) NOT NULL COMMENT 'Stable skill key',
    `name`             VARCHAR(120) NOT NULL COMMENT 'Display name',
    `description`      VARCHAR(500) NULL     COMMENT 'Skill description',
    `version`          VARCHAR(50)  NULL     COMMENT 'Skill version',
    `author`           VARCHAR(120) NULL     COMMENT 'Skill author',
    `category`         VARCHAR(80)  NULL     COMMENT 'Skill category',
    `risk_level`       VARCHAR(50)  NULL     COMMENT 'READ_ONLY/EXTERNAL_INFORMATION/REQUIRES_CONFIRMATION',
    `input_schema`     VARCHAR(500) NULL     COMMENT 'Input schema hint',
    `trigger_text`     VARCHAR(500) NULL     COMMENT 'Trigger hint',
    `instruction_text` TEXT         NULL     COMMENT 'SKILL.md content',
    `bound_tools`      VARCHAR(500) NULL     COMMENT 'Comma-separated safe bound tools',
    `source_type`      VARCHAR(50)  NOT NULL COMMENT 'BUILT_IN/GITHUB/LOCAL_ZIP/MARKETPLACE/URL/PRIVATE_REPO',
    `source_uri`       VARCHAR(500) NOT NULL COMMENT 'Channel-neutral source URI',
    `source_version`   VARCHAR(100) NULL     COMMENT 'Commit/ref/version',
    `enabled`          TINYINT      NOT NULL DEFAULT 1 COMMENT 'Enabled flag',
    `built_in`         TINYINT      NOT NULL DEFAULT 0 COMMENT 'Built-in flag',
    `deleted`          TINYINT      NOT NULL DEFAULT 0 COMMENT 'Logic delete flag',
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
    `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated time',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_agent_skill_source` (`user_id`, `source_type`, `source_uri`(180), `skill_key`),
    INDEX `idx_agent_skill_user` (`user_id`, `enabled`, `deleted`),
    CONSTRAINT `fk_agent_skill_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='Installable Agent skills';

CREATE TABLE IF NOT EXISTS `skill_invocation_record`
(
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `user_id`    BIGINT       NOT NULL COMMENT 'User ID',
    `trace_id`   VARCHAR(64)  NULL     COMMENT 'ReAct trace ID',
    `skill_name` VARCHAR(100) NOT NULL COMMENT 'Skill/tool name',
    `category`   VARCHAR(50)  NULL     COMMENT 'Skill category',
    `source_type` VARCHAR(50) NULL     COMMENT 'Skill source type',
    `risk_level` VARCHAR(50)  NULL     COMMENT 'Skill risk level',
    `input`      TEXT         NULL     COMMENT 'Input JSON',
    `success`    TINYINT      NOT NULL DEFAULT 0 COMMENT 'Success flag',
    `blocked`    TINYINT      NOT NULL DEFAULT 0 COMMENT 'Blocked by policy flag',
    `duration_ms` BIGINT      NULL     COMMENT 'Execution duration in milliseconds',
    `summary`    VARCHAR(500) NULL     COMMENT 'Observation summary',
    `raw_result` TEXT         NULL     COMMENT 'Raw observation',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
    PRIMARY KEY (`id`),
    INDEX `idx_skill_trace` (`trace_id`),
    INDEX `idx_skill_user` (`user_id`, `skill_name`),
    CONSTRAINT `fk_skill_invocation_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='Agent skill invocation records';

ALTER TABLE `skill_invocation_record` ADD COLUMN `source_type` VARCHAR(50) NULL COMMENT 'Skill source type';
ALTER TABLE `skill_invocation_record` ADD COLUMN `risk_level` VARCHAR(50) NULL COMMENT 'Skill risk level';
ALTER TABLE `skill_invocation_record` ADD COLUMN `blocked` TINYINT NOT NULL DEFAULT 0 COMMENT 'Blocked by policy flag';
ALTER TABLE `skill_invocation_record` ADD COLUMN `duration_ms` BIGINT NULL COMMENT 'Execution duration in milliseconds';
CREATE TABLE IF NOT EXISTS `agent_run`
(
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `user_id`       BIGINT       NOT NULL COMMENT 'User ID',
    `trace_id`      VARCHAR(64)  NOT NULL COMMENT 'ReAct trace ID',
    `query`         TEXT         NOT NULL COMMENT 'Original user query',
    `final_answer`  TEXT         NULL     COMMENT 'Final assistant answer',
    `status`        VARCHAR(30)  NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/COMPLETED/FAILED/PARTIAL',
    `started_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Run start time',
    `finished_at`   DATETIME     NULL     COMMENT 'Run finish time',
    `duration_ms`   BIGINT       NULL     COMMENT 'Run duration in milliseconds',
    `error_message` VARCHAR(500) NULL     COMMENT 'Failure summary',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_agent_run_trace` (`trace_id`),
    INDEX `idx_agent_run_user` (`user_id`, `started_at`),
    CONSTRAINT `fk_agent_run_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='Agent run records';

CREATE TABLE IF NOT EXISTS `agent_run_step`
(
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `user_id`             BIGINT       NOT NULL COMMENT 'User ID',
    `trace_id`            VARCHAR(64)  NOT NULL COMMENT 'ReAct trace ID',
    `step_number`         INT          NOT NULL COMMENT 'Step number in run',
    `summary`             VARCHAR(500) NULL     COMMENT 'User-visible step summary',
    `tool_name`           VARCHAR(100) NULL     COMMENT 'Tool/skill name',
    `input`               TEXT         NULL     COMMENT 'Tool input JSON',
    `success`             TINYINT      NULL     COMMENT 'Success flag',
    `observation_summary` VARCHAR(500) NULL     COMMENT 'Observation summary',
    `error_message`       VARCHAR(500) NULL     COMMENT 'Failure summary',
    `status`              VARCHAR(30)  NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/COMPLETED/FAILED',
    `started_at`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Step start time',
    `finished_at`         DATETIME     NULL     COMMENT 'Step finish time',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_agent_run_step` (`trace_id`, `step_number`),
    INDEX `idx_agent_run_step_user` (`user_id`, `trace_id`),
    CONSTRAINT `fk_agent_run_step_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='Agent run visible steps';

CREATE TABLE IF NOT EXISTS `agent_reflection`
(
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `user_id`         BIGINT       NOT NULL COMMENT 'User ID',
    `trace_id`        VARCHAR(64)  NOT NULL COMMENT 'ReAct trace ID',
    `suggestion_type` VARCHAR(50)  NOT NULL COMMENT 'MEMORY_CANDIDATE/SKILL_CANDIDATE/SCHEDULE_CANDIDATE/RISK_WARNING',
    `title`           VARCHAR(120) NOT NULL COMMENT 'Display title',
    `summary`         VARCHAR(500) NOT NULL COMMENT 'Display summary',
    `payload`         TEXT         NULL     COMMENT 'Evidence and action payload JSON',
    `status`          VARCHAR(30)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/ACCEPTED/DISMISSED/EXPIRED',
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
    `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated time',
    `deleted`         TINYINT      NOT NULL DEFAULT 0 COMMENT 'Logic delete flag',
    PRIMARY KEY (`id`),
    INDEX `idx_agent_reflection_user` (`user_id`, `status`, `deleted`),
    INDEX `idx_agent_reflection_trace` (`trace_id`),
    CONSTRAINT `fk_agent_reflection_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='Agent post-run reflections';

CREATE TABLE IF NOT EXISTS `agent_context_summary`
(
    `id`                BIGINT      NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `user_id`           BIGINT      NOT NULL COMMENT 'User ID',
    `conversation_id`   BIGINT      NULL COMMENT 'Chat conversation ID',
    `trace_id`          VARCHAR(64) NULL COMMENT 'Source trace ID',
    `scope`             VARCHAR(50) NOT NULL DEFAULT 'CONVERSATION' COMMENT 'CONVERSATION/TOOL_RESULT/RAG_CONTEXT/MEMORY_CONTEXT/TASK_STATE',
    `summary`           TEXT        NOT NULL COMMENT 'Compressed context summary',
    `source_refs`       TEXT        NULL COMMENT 'Source trace/message/tool refs',
    `source_hash`       VARCHAR(64) NULL COMMENT 'Source content hash',
    `covered_from_message_id`  BIGINT NULL COMMENT 'First covered chat_message ID',
    `covered_until_message_id` BIGINT NULL COMMENT 'Last covered chat_message ID',
    `original_tokens`   INT         NOT NULL DEFAULT 0 COMMENT 'Estimated original tokens',
    `compressed_tokens` INT         NOT NULL DEFAULT 0 COMMENT 'Estimated compressed tokens',
    `created_at`        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
    `updated_at`        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated time',
    `deleted`           TINYINT     NOT NULL DEFAULT 0 COMMENT 'Logic delete flag',
    PRIMARY KEY (`id`),
    INDEX `idx_context_summary_user` (`user_id`, `deleted`, `updated_at`),
    INDEX `idx_context_summary_conversation` (`user_id`, `conversation_id`, `scope`, `deleted`, `updated_at`),
    INDEX `idx_context_summary_trace` (`trace_id`),
    INDEX `idx_context_summary_source` (`user_id`, `scope`, `source_hash`, `deleted`),
    INDEX `idx_context_summary_covered` (`user_id`, `covered_until_message_id`, `deleted`),
    CONSTRAINT `fk_context_summary_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='Agent compressed context summaries';

ALTER TABLE `agent_context_summary` ADD COLUMN `conversation_id` BIGINT NULL COMMENT 'Chat conversation ID';
ALTER TABLE `agent_context_summary` ADD COLUMN `source_hash` VARCHAR(64) NULL COMMENT 'Source content hash';
ALTER TABLE `agent_context_summary` ADD COLUMN `covered_from_message_id` BIGINT NULL COMMENT 'First covered chat_message ID';
ALTER TABLE `agent_context_summary` ADD COLUMN `covered_until_message_id` BIGINT NULL COMMENT 'Last covered chat_message ID';
CREATE INDEX `idx_context_summary_conversation` ON `agent_context_summary` (`user_id`, `conversation_id`, `scope`, `deleted`, `updated_at`);
CREATE INDEX `idx_context_summary_source` ON `agent_context_summary` (`user_id`, `scope`, `source_hash`, `deleted`);
CREATE INDEX `idx_context_summary_covered` ON `agent_context_summary` (`user_id`, `covered_until_message_id`, `deleted`);

CREATE TABLE IF NOT EXISTS `transaction`
(
    `id`               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id`          BIGINT        NOT NULL COMMENT '用户ID',
    `amount`           DECIMAL(12,2) NOT NULL COMMENT '金额',
    `type`             VARCHAR(10)   NOT NULL DEFAULT 'EXPENSE' COMMENT '类型(INCOME-收入,EXPENSE-支出)',
    `category`         VARCHAR(50)   NOT NULL COMMENT '分类名称',
    `description`      VARCHAR(500)  NULL     COMMENT '备注描述',
    `transaction_date` DATE          NOT NULL COMMENT '交易日期',
    `created_at`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`          TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除(0-未删,1-已删)',
    PRIMARY KEY (`id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_user_date` (`user_id`, `transaction_date`),
    INDEX `idx_category` (`category`),
    CONSTRAINT `fk_transaction_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='交易记录表';

-- 消费分类表
CREATE TABLE IF NOT EXISTS `expense_category`
(
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id`         BIGINT       NOT NULL DEFAULT 0 COMMENT '用户ID(0=系统默认)',
    `name`            VARCHAR(50)  NOT NULL COMMENT '分类名称',
    `icon`            VARCHAR(50)  NULL     COMMENT '图标标识',
    `benchmark_min`   INT          NULL     COMMENT '对标分析下限(%)',
    `benchmark_max`   INT          NULL     COMMENT '对标分析上限(%)',
    `benchmark_label` VARCHAR(100) NULL     COMMENT '对标分析标签(如"食品支出")',
    `sort_order`      INT          NOT NULL DEFAULT 0 COMMENT '排序号',
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除(0-未删,1-已删)',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_user_category` (`user_id`, `name`),
    INDEX `idx_user_sort` (`user_id`, `sort_order`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='消费分类表';

CREATE TABLE IF NOT EXISTS `chat_conversation`
(
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `user_id`    BIGINT       NOT NULL COMMENT 'User ID',
    `title`      VARCHAR(80)  NOT NULL DEFAULT '新对话' COMMENT 'Conversation title',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
    `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated time',
    `deleted`    TINYINT      NOT NULL DEFAULT 0 COMMENT 'Logic delete flag',
    PRIMARY KEY (`id`),
    INDEX `idx_chat_conversation_user` (`user_id`, `deleted`, `updated_at`),
    CONSTRAINT `fk_chat_conversation_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='Chat conversations';

-- 聊天消息表
CREATE TABLE IF NOT EXISTS `chat_message`
(
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id`    BIGINT       NOT NULL COMMENT '用户ID',
    `role`       VARCHAR(20)  NOT NULL COMMENT '角色(USER-用户,ASSISTANT-AI助手)',
    `content`    TEXT         NOT NULL COMMENT '消息内容',
    `trace_id`   VARCHAR(64)  NULL     COMMENT 'Assistant trace ID',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `deleted`    TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除(0-正常,1-已删)',
    PRIMARY KEY (`id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_created_at` (`created_at`),
    INDEX `idx_chat_trace` (`trace_id`),
    CONSTRAINT `fk_chat_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='聊天消息表';

ALTER TABLE `chat_message` ADD COLUMN `conversation_id` BIGINT NULL COMMENT 'Conversation ID';
CREATE INDEX `idx_chat_conversation` ON `chat_message` (`conversation_id`);
ALTER TABLE `chat_message` ADD COLUMN `trace_id` VARCHAR(64) NULL COMMENT 'Assistant trace ID';
CREATE INDEX `idx_chat_trace` ON `chat_message` (`trace_id`);

CREATE TABLE IF NOT EXISTS `pending_action`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `user_id`     BIGINT       NOT NULL COMMENT 'User ID',
    `action_type` VARCHAR(50)  NOT NULL COMMENT 'RECORD_TRANSACTION/SET_BUDGET',
    `title`       VARCHAR(100) NOT NULL COMMENT 'Display title',
    `summary`     VARCHAR(500) NOT NULL COMMENT 'Display summary',
    `payload`     TEXT         NOT NULL COMMENT 'Action payload JSON',
    `status`      VARCHAR(30)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/CONFIRMED/CANCELLED',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
    `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated time',
    PRIMARY KEY (`id`),
    INDEX `idx_pending_user_status` (`user_id`, `status`),
    CONSTRAINT `fk_pending_action_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='Pending agent write actions';


-- 预算表
CREATE TABLE IF NOT EXISTS `budget`
(
    `id`              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id`         BIGINT        NOT NULL COMMENT '用户ID',
    `category`        VARCHAR(50)   NOT NULL COMMENT '预算分类(ALL=总预算，其他为具体分类)',
    `month`           VARCHAR(7)    NOT NULL COMMENT '预算月份(yyyy-MM)',
    `budget_amount`   DECIMAL(12,2) NOT NULL COMMENT '预算金额',
    `alert_threshold` INT           NOT NULL DEFAULT 80 COMMENT '预警阈值百分比(如80表示使用达80%时预警)',
    `created_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`         TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除(0-未删,1-已删)',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_user_category_month` (`user_id`, `category`, `month`),
    INDEX `idx_user_month` (`user_id`, `month`),
    CONSTRAINT `fk_budget_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='预算表';

-- 预算预警记录表
CREATE TABLE IF NOT EXISTS `budget_alert`
(
    `id`              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id`         BIGINT        NOT NULL COMMENT '用户ID',
    `category`        VARCHAR(50)   NOT NULL COMMENT '触发预警的分类',
    `month`           VARCHAR(7)    NOT NULL COMMENT '预警月份',
    `alert_type`      VARCHAR(20)   NOT NULL COMMENT '预警类型(THRESHOLD-阈值预警, OVERRUN-超支预警, TREND-趋势预警)',
    `severity`        VARCHAR(10)   NOT NULL DEFAULT 'INFO' COMMENT '严重程度(INFO/WARNING/CRITICAL)',
    `spent_amount`    DECIMAL(12,2) NOT NULL COMMENT '当前已支出金额',
    `budget_amount`   DECIMAL(12,2) NOT NULL COMMENT '预算金额',
    `usage_percent`   DECIMAL(5,1)  NOT NULL COMMENT '使用百分比',
    `message`         VARCHAR(500)  NOT NULL COMMENT '预警消息',
    `is_read`         TINYINT       NOT NULL DEFAULT 0 COMMENT '是否已读(0-未读,1-已读)',
    `created_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `deleted`         TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除(0-未删,1-已删)',
    PRIMARY KEY (`id`),
    INDEX `idx_user_read` (`user_id`, `is_read`),
    INDEX `idx_user_month` (`user_id`, `month`),
    CONSTRAINT `fk_alert_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='预算预警记录表';

-- Agent分析记录表
-- Bill image import records
CREATE TABLE IF NOT EXISTS `bill_import_record`
(
    `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `user_id`           BIGINT       NOT NULL COMMENT 'User ID',
    `original_filename` VARCHAR(255) NULL     COMMENT 'Original uploaded filename',
    `file_path`         VARCHAR(500) NOT NULL COMMENT 'Stored image path',
    `bill_type`         VARCHAR(50)  NOT NULL DEFAULT 'UNKNOWN' COMMENT 'WECHAT/ALIPAY/BANK/NON_BILL/etc',
    `confidence`        DECIMAL(5,4) NOT NULL DEFAULT 0 COMMENT 'Classification confidence',
    `ocr_text`          TEXT         NULL     COMMENT 'Raw visual text summary',
    `warnings`          TEXT         NULL     COMMENT 'Analysis warnings',
    `status`            VARCHAR(30)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/ANALYZED/LOW_CONFIDENCE/REJECTED/FAILED/CONFIRMED',
    `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
    `updated_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated time',
    `deleted`           TINYINT      NOT NULL DEFAULT 0 COMMENT 'Logic delete flag',
    PRIMARY KEY (`id`),
    INDEX `idx_bill_user` (`user_id`),
    INDEX `idx_bill_status` (`user_id`, `status`),
    CONSTRAINT `fk_bill_import_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='Bill image import records';

-- Candidate transactions extracted from bill images
CREATE TABLE IF NOT EXISTS `bill_candidate_transaction`
(
    `id`               BIGINT        NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `bill_import_id`   BIGINT        NOT NULL COMMENT 'Bill import record ID',
    `user_id`          BIGINT        NOT NULL COMMENT 'User ID',
    `amount`           DECIMAL(12,2) NOT NULL COMMENT 'Candidate amount',
    `type`             VARCHAR(10)   NOT NULL DEFAULT 'EXPENSE' COMMENT 'INCOME/EXPENSE',
    `category`         VARCHAR(50)   NOT NULL DEFAULT '其他' COMMENT 'Candidate category',
    `description`      VARCHAR(500)  NULL     COMMENT 'Candidate description',
    `transaction_date` DATE          NOT NULL COMMENT 'Candidate transaction date',
    `confidence`       DECIMAL(5,4)  NOT NULL DEFAULT 0 COMMENT 'Extraction confidence',
    `status`           VARCHAR(30)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/CONFIRMED/IGNORED',
    `transaction_id`   BIGINT        NULL     COMMENT 'Confirmed transaction ID',
    `created_at`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
    `updated_at`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated time',
    `deleted`          TINYINT       NOT NULL DEFAULT 0 COMMENT 'Logic delete flag',
    PRIMARY KEY (`id`),
    INDEX `idx_candidate_bill` (`bill_import_id`),
    INDEX `idx_candidate_user` (`user_id`, `status`),
    CONSTRAINT `fk_candidate_bill` FOREIGN KEY (`bill_import_id`) REFERENCES `bill_import_record` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_candidate_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='Bill candidate transactions';

CREATE TABLE IF NOT EXISTS `analysis_record`
(
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id`      BIGINT       NOT NULL COMMENT '用户ID',
    `trace_id`     VARCHAR(64)  NULL     COMMENT 'ReAct trace ID',
    `query`        TEXT         NOT NULL COMMENT '用户原始问题',
    `plan`         TEXT         NULL     COMMENT 'Agent执行计划(JSON)',
    `steps_result` TEXT         NULL     COMMENT '各步骤执行结果(JSON)',
    `final_answer` TEXT         NULL     COMMENT '最终回答',
    `score`        INT          NULL     COMMENT '用户评分(1-5)',
    `feedback`     VARCHAR(500) NULL     COMMENT '用户反馈',
    `tokens_used`  INT          NULL     COMMENT '消耗Token数',
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `deleted`      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除(0-未删,1-已删)',
    PRIMARY KEY (`id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_created_at` (`created_at`),
    INDEX `idx_analysis_trace` (`trace_id`),
    CONSTRAINT `fk_analysis_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='Agent分析记录表';

CREATE TABLE IF NOT EXISTS `agent_schedule`
(
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `user_id`         BIGINT       NOT NULL COMMENT 'User ID',
    `trace_id`        VARCHAR(64)  NULL     COMMENT 'Last ReAct trace ID',
    `name`            VARCHAR(200) NOT NULL COMMENT 'Schedule name',
    `description`     VARCHAR(500) NULL     COMMENT 'Schedule description',
    `cron_expression` VARCHAR(100) NOT NULL COMMENT 'Spring cron expression',
    `timezone`        VARCHAR(50)  NOT NULL DEFAULT 'Asia/Shanghai' COMMENT 'Timezone',
    `task_query`      TEXT         NOT NULL COMMENT 'Instruction executed by the agent',
    `enabled`         TINYINT      NOT NULL DEFAULT 1 COMMENT 'Enabled flag',
    `last_run_at`     DATETIME     NULL     COMMENT 'Last run time',
    `next_run_at`     DATETIME     NULL     COMMENT 'Next run time',
    `lock_until`      DATETIME     NULL     COMMENT 'Execution lock expiration time',
    `run_count`       INT          NOT NULL DEFAULT 0 COMMENT 'Run count',
    `consecutive_failures` INT     NOT NULL DEFAULT 0 COMMENT 'Consecutive failed run count',
    `last_status`     VARCHAR(20)  NULL     COMMENT 'SUCCESS/FAILED',
    `last_answer`     TEXT         NULL     COMMENT 'Last agent answer',
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
    `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated time',
    `deleted`         TINYINT      NOT NULL DEFAULT 0 COMMENT 'Logic delete flag',
    PRIMARY KEY (`id`),
    INDEX `idx_agent_schedule_user` (`user_id`, `enabled`, `deleted`),
    INDEX `idx_agent_schedule_next_run` (`enabled`, `next_run_at`),
    INDEX `idx_agent_schedule_lock` (`enabled`, `lock_until`),
    CONSTRAINT `fk_agent_schedule_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='Agent scheduled tasks';

CREATE TABLE IF NOT EXISTS `agent_schedule_run`
(
    `id`            BIGINT      NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `schedule_id`   BIGINT      NOT NULL COMMENT 'Schedule ID',
    `user_id`       BIGINT      NOT NULL COMMENT 'User ID',
    `trace_id`      VARCHAR(64) NULL     COMMENT 'ReAct trace ID',
    `status`        VARCHAR(20) NOT NULL COMMENT 'SUCCESS/FAILED',
    `answer`        TEXT        NULL     COMMENT 'Agent answer',
    `error_message` TEXT        NULL     COMMENT 'Failure message',
    `started_at`    DATETIME    NOT NULL COMMENT 'Run started time',
    `finished_at`   DATETIME    NOT NULL COMMENT 'Run finished time',
    `duration_ms`   BIGINT      NOT NULL DEFAULT 0 COMMENT 'Run duration in milliseconds',
    `created_at`    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
    PRIMARY KEY (`id`),
    INDEX `idx_agent_schedule_run_schedule` (`schedule_id`, `created_at`),
    INDEX `idx_agent_schedule_run_user` (`user_id`, `created_at`),
    INDEX `idx_agent_schedule_run_trace` (`trace_id`),
    CONSTRAINT `fk_agent_schedule_run_schedule` FOREIGN KEY (`schedule_id`) REFERENCES `agent_schedule` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_agent_schedule_run_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='Agent scheduled task run history';

-- Investment analysis module
CREATE TABLE IF NOT EXISTS `investment_product` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `product_type` VARCHAR(30) NOT NULL,
    `market` VARCHAR(30) NOT NULL,
    `code` VARCHAR(40) NOT NULL,
    `name` VARCHAR(160) NOT NULL,
    `currency` VARCHAR(3) NOT NULL DEFAULT 'CNY',
    `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_investment_product` (`product_type`, `market`, `code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Investment product master';

CREATE TABLE IF NOT EXISTS `investment_account` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `account_name` VARCHAR(120) NOT NULL,
    `account_type` VARCHAR(30) NOT NULL,
    `base_currency` VARCHAR(3) NOT NULL DEFAULT 'CNY',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_investment_account_user` (`user_id`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Investment account';

CREATE TABLE IF NOT EXISTS `investment_transaction` (
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
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_investment_transaction_ref` (`user_id`, `source`, `external_ref`),
    KEY `idx_investment_transaction_account` (`user_id`, `account_id`, `trade_date`),
    KEY `idx_investment_transaction_product` (`account_id`, `product_id`, `trade_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Immutable investment ledger';

CREATE TABLE IF NOT EXISTS `investment_cash_balance` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `account_id` BIGINT NOT NULL,
    `currency` VARCHAR(3) NOT NULL,
    `balance` DECIMAL(28,8) NOT NULL DEFAULT 0,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_investment_cash_balance` (`account_id`, `currency`),
    KEY `idx_investment_cash_user` (`user_id`, `account_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Investment account cash projection';

CREATE TABLE IF NOT EXISTS `investment_cash_ledger` (
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
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_investment_cash_transaction` (`transaction_id`),
    KEY `idx_investment_cash_ledger_account` (`user_id`, `account_id`, `occurred_on`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Immutable investment cash ledger';

CREATE TABLE IF NOT EXISTS `investment_position` (
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
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_investment_position` (`account_id`, `product_id`),
    KEY `idx_investment_position_user` (`user_id`, `account_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Rebuildable investment position';

CREATE TABLE IF NOT EXISTS `product_daily_quote` (
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
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_product_daily_quote` (`product_id`, `trade_date`, `adjust_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Normalized daily quote or fund NAV';

CREATE TABLE IF NOT EXISTS `investment_import_batch` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `original_filename` VARCHAR(255) NOT NULL,
    `status` VARCHAR(30) NOT NULL,
    `payload` LONGTEXT NOT NULL,
    `row_count` INT NOT NULL DEFAULT 0,
    `error_count` INT NOT NULL DEFAULT 0,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_investment_import_user` (`user_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Investment CSV preview batch';

CREATE TABLE IF NOT EXISTS `investment_sync_batch` (
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
    PRIMARY KEY (`id`),
    KEY `idx_investment_sync_user` (`user_id`, `started_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Investment data synchronization batch';

CREATE TABLE IF NOT EXISTS `investment_plan` (
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
    PRIMARY KEY (`id`),
    KEY `idx_investment_plan_user` (`user_id`, `enabled`, `next_execution_date`),
    KEY `idx_investment_plan_due` (`enabled`, `next_execution_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Recurring investment plan';

CREATE TABLE IF NOT EXISTS `daily_exchange_rate` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `base_currency` VARCHAR(3) NOT NULL,
    `quote_currency` VARCHAR(3) NOT NULL,
    `rate_date` DATE NOT NULL,
    `rate` DECIMAL(28,10) NOT NULL,
    `source` VARCHAR(40) NOT NULL,
    `adapter_version` VARCHAR(40),
    `synced_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_daily_exchange_rate` (`base_currency`, `quote_currency`, `rate_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Normalized daily exchange rate';

CREATE TABLE IF NOT EXISTS `investment_asset` (
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
    PRIMARY KEY (`id`),
    KEY `idx_investment_asset_user` (`user_id`, `deleted`, `updated_at`),
    KEY `idx_investment_asset_product` (`user_id`, `product_id`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='User managed investment asset';

CREATE TABLE IF NOT EXISTS `wealth_baseline` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `user_id` BIGINT NOT NULL,
    `entered_total_assets` DECIMAL(28,8) NOT NULL,
    `baseline_non_investment_balance` DECIMAL(28,8) NOT NULL,
    `baseline_at` DATETIME NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_wealth_baseline_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Unified wealth baseline';

CREATE TABLE IF NOT EXISTS `investment_analysis_preference` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `user_id` BIGINT NOT NULL,
    `asset_id` BIGINT NOT NULL,
    `short_min_days` INT NOT NULL DEFAULT 5,
    `short_max_days` INT NOT NULL DEFAULT 20,
    `medium_min_days` INT NOT NULL DEFAULT 20,
    `medium_max_days` INT NOT NULL DEFAULT 120,
    `long_min_days` INT NOT NULL DEFAULT 120,
    `long_max_days` INT NOT NULL DEFAULT 500,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_analysis_preference_user_asset` (`user_id`, `asset_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Per asset analysis horizon preference';

CREATE TABLE IF NOT EXISTS `investment_horizon_profile` (
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
    KEY `idx_horizon_profile_resolution` (`user_id`, `scope_type`, `asset_id`, `active`, `version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Versioned user and asset horizon profiles';

CREATE TABLE IF NOT EXISTS `investment_horizon_setting` (
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
    UNIQUE KEY `uk_horizon_setting_profile_code` (`profile_id`, `horizon_code`),
    KEY `idx_horizon_setting_profile` (`profile_id`, `sort_order`),
    CONSTRAINT `fk_horizon_setting_profile` FOREIGN KEY (`profile_id`)
        REFERENCES `investment_horizon_profile` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Arbitrary settings inside a horizon profile version';

CREATE TABLE IF NOT EXISTS `investment_analysis_snapshot` (
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
    UNIQUE KEY `uk_analysis_snapshot_user_asset_rule` (`user_id`, `asset_id`, `rule_version`),
    KEY `idx_analysis_cache_key` (`user_id`, `asset_id`, `analysis_cache_key`),
    KEY `idx_analysis_dataset_version` (`dataset_version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Cached deterministic investment analysis';

CREATE TABLE IF NOT EXISTS `investment_data_quality_snapshot` (
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
    UNIQUE KEY `uk_data_quality_dataset_rule` (`dataset_version`, `quality_config_version`),
    KEY `idx_data_quality_dataset_version` (`dataset_version`),
    KEY `idx_data_quality_product_range` (`product_type`, `code`, `market`, `requested_start_date`, `requested_end_date`),
    KEY `idx_data_quality_status` (`quality_status`, `evaluated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Immutable market dataset quality report';

CREATE TABLE IF NOT EXISTS `investment_data_quality_issue` (
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
    UNIQUE KEY `uk_data_quality_issue_order` (`quality_snapshot_id`, `sequence_no`),
    KEY `idx_data_quality_issue_rule` (`rule_code`, `severity`),
    CONSTRAINT `fk_data_quality_issue_snapshot` FOREIGN KEY (`quality_snapshot_id`)
        REFERENCES `investment_data_quality_snapshot` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Data quality rule evidence';
