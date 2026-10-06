CREATE TABLE investment_research_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT NOT NULL,
    dataset VARCHAR(20) NOT NULL,
    record_key CHAR(64) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    adapter_version VARCHAR(64) NOT NULL,
    as_of_date DATE NULL,
    published_date DATE NULL,
    observed_at DATETIME(6) NOT NULL,
    available_at DATETIME(6) NOT NULL,
    availability_basis VARCHAR(30) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    UNIQUE KEY uk_research_observation (product_id,dataset,provider,record_key,observed_at),
    KEY idx_research_asof (product_id,dataset,available_at,id),
    KEY idx_research_versions (product_id,dataset,provider,record_key,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 原有类型校验错误已修复；恢复断点，不清理已有行情。
UPDATE market_data_job j JOIN investment_product p ON p.id=j.product_id
SET j.status='QUEUED',j.attempt_count=0,j.next_retry_at=NULL,j.lease_until=NULL,j.lease_token=NULL,j.updated_at=NOW()
WHERE j.status='FAILED' AND p.product_type IN ('ETF','INDEX')
AND j.last_error LIKE '%product_type must be STOCK or MUTUAL_FUND%';
