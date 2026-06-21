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

CREATE TABLE IF NOT EXISTS `skill_invocation_record`
(
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `user_id`    BIGINT       NOT NULL COMMENT 'User ID',
    `trace_id`   VARCHAR(64)  NULL     COMMENT 'ReAct trace ID',
    `skill_name` VARCHAR(100) NOT NULL COMMENT 'Skill/tool name',
    `category`   VARCHAR(50)  NULL     COMMENT 'Skill category',
    `input`      TEXT         NULL     COMMENT 'Input JSON',
    `success`    TINYINT      NOT NULL DEFAULT 0 COMMENT 'Success flag',
    `summary`    VARCHAR(500) NULL     COMMENT 'Observation summary',
    `raw_result` TEXT         NULL     COMMENT 'Raw observation',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
    PRIMARY KEY (`id`),
    INDEX `idx_skill_trace` (`trace_id`),
    INDEX `idx_skill_user` (`user_id`, `skill_name`),
    CONSTRAINT `fk_skill_invocation_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='Agent skill invocation records';

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

-- 聊天消息表
CREATE TABLE IF NOT EXISTS `chat_message`
(
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id`    BIGINT       NOT NULL COMMENT '用户ID',
    `role`       VARCHAR(20)  NOT NULL COMMENT '角色(USER-用户,ASSISTANT-AI助手)',
    `content`    TEXT         NOT NULL COMMENT '消息内容',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `deleted`    TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除(0-正常,1-已删)',
    PRIMARY KEY (`id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_created_at` (`created_at`),
    CONSTRAINT `fk_chat_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='聊天消息表';

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
    CONSTRAINT `fk_analysis_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='Agent分析记录表';
