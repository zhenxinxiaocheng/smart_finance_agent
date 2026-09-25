CREATE TABLE product_quote_coverage (
    product_id INTEGER NOT NULL,
    frequency TEXT NOT NULL,
    adjust_type TEXT NOT NULL,
    dataset_type TEXT NOT NULL,
    requested_start_date TEXT NULL,
    history_start_date TEXT NULL,
    history_end_date TEXT NULL,
    target_date TEXT NULL,
    observations INTEGER NOT NULL DEFAULT 0,
    status TEXT NOT NULL,
    reason TEXT NULL,
    provider TEXT NULL,
    dataset_version TEXT NULL,
    updated_at TEXT NOT NULL,
    PRIMARY KEY (product_id, frequency, adjust_type, dataset_type)
);

CREATE TABLE market_data_scope_product (
    product_id INTEGER NOT NULL PRIMARY KEY,
    scope_group TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

INSERT INTO product_quote_coverage(product_id,frequency,adjust_type,dataset_type,
    requested_start_date,history_start_date,history_end_date,target_date,observations,status,reason,updated_at)
SELECT q.product_id,'DAY',q.adjust_type,
    CASE WHEN p.product_type IN ('MUTUAL_FUND','FUND') THEN 'NAV' ELSE 'PRICE' END,
    COALESCE(p.listing_date,p.inception_date),MIN(q.trade_date),MAX(q.trade_date),MAX(q.trade_date),
    COUNT(*),'INCOMPLETE','LEGACY_UNVERIFIED',CURRENT_TIMESTAMP
FROM product_daily_quote q JOIN investment_product p ON p.id=q.product_id
GROUP BY q.product_id,q.adjust_type,p.product_type,p.listing_date,p.inception_date;

INSERT INTO product_quote_coverage(product_id,frequency,adjust_type,dataset_type,
    requested_start_date,history_start_date,history_end_date,target_date,observations,status,reason,updated_at)
SELECT q.product_id,'DAY',q.adjust_type,'TOTAL_RETURN_INDEX',p.inception_date,
    MIN(CASE WHEN q.total_return_index>0 THEN q.trade_date END),
    MAX(CASE WHEN q.total_return_index>0 THEN q.trade_date END),MAX(q.trade_date),
    SUM(CASE WHEN q.total_return_index>0 THEN 1 ELSE 0 END),
    'INCOMPLETE','LEGACY_UNVERIFIED',CURRENT_TIMESTAMP
FROM product_daily_quote q JOIN investment_product p ON p.id=q.product_id
WHERE p.product_type IN ('MUTUAL_FUND','FUND')
GROUP BY q.product_id,q.adjust_type,p.inception_date;

INSERT INTO market_data_scope_product(product_id,scope_group,updated_at)
SELECT id,
    CASE WHEN source_metadata='AKSHARE_A_LIST' THEN 'CN_A'
         WHEN source_metadata='AKSHARE_ETF_SPOT' THEN 'CN_ETF'
         WHEN source_metadata='AKSHARE_US_SPOT_CURATED_ETF' THEN 'US'
         ELSE 'INDEX' END,
    CURRENT_TIMESTAMP
FROM investment_product
WHERE source_metadata IN ('AKSHARE_A_LIST','AKSHARE_ETF_SPOT','AKSHARE_US_SPOT_CURATED_ETF')
   OR (source_metadata='INDEX_WATCHLIST_REGISTRY' AND market='CN_INDEX');

UPDATE market_data_job SET status='SKIPPED',last_error='OUT_OF_SCOPE',
    lease_until=NULL,next_retry_at=NULL,updated_at=CURRENT_TIMESTAMP
WHERE product_id NOT IN (SELECT product_id FROM market_data_scope_product)
  AND status IN ('QUEUED','RUNNING','RETRY_WAIT');
