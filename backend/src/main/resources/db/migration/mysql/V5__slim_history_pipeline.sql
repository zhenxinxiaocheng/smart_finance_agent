-- V5: 移除为 Market Data 第二套链路引入的冗余状态，回归产品级历史覆盖。
-- 已执行的 V1-V4 不做修改。

-- 1) 用实际行情回填产品级历史覆盖，供原始 Investment 链路使用。
--    不在此处入队任何回补任务：迁移在启动时执行，不得触发全量回补。
UPDATE investment_product p
SET p.history_start_date = (SELECT MIN(q.trade_date) FROM product_daily_quote q
                            WHERE q.product_id = p.id),
    p.history_end_date = (SELECT MAX(q.trade_date) FROM product_daily_quote q
                          WHERE q.product_id = p.id),
    p.history_coverage_complete = 1
WHERE EXISTS (SELECT 1 FROM product_daily_quote q WHERE q.product_id = p.id);

-- 2) 删除 Coverage / Scope 状态表。
DROP TABLE IF EXISTS product_quote_coverage;
DROP TABLE IF EXISTS market_data_scope_product;

-- 3) 删除任务表上不再需要的覆盖状态列。
ALTER TABLE investment_data_job
    DROP COLUMN requested_start_date,
    DROP COLUMN sample_start_date,
    DROP COLUMN sample_end_date,
    DROP COLUMN coverage_complete,
    DROP COLUMN dataset_version;

-- 4) 清理 scope 时代留下的越界标记，让这些产品在下次目录调度时重新评估。
UPDATE market_data_job
SET last_error = NULL,
    attempt_count = 0,
    next_retry_at = NULL,
    lease_until = NULL
WHERE status = 'SKIPPED' AND last_error = 'OUT_OF_SCOPE';
