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

-- 交易记录表
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
