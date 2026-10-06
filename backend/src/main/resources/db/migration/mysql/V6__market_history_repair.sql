-- 目录归属与提供方名称分开；只回填已知旧目录来源。
ALTER TABLE investment_product ADD COLUMN catalog_market VARCHAR(20) NULL;
UPDATE investment_product
SET catalog_market = CASE
    WHEN source_metadata = 'AKSHARE_A_LIST' THEN 'CN_A'
    WHEN source_metadata = 'AKSHARE_ETF_SPOT' THEN 'CN_ETF'
    WHEN source_metadata IN ('AKSHARE_US_SPOT', 'AKSHARE_US_SPOT_CURATED_ETF') THEN 'US'
    WHEN source_metadata = 'INDEX_WATCHLIST_REGISTRY' THEN 'INDEX'
    ELSE NULL END;
CREATE INDEX idx_product_catalog ON investment_product(catalog_market, status, id);

-- V5 的「任意行情存在」不能证明全历史；保留行情与日期，等待历史链路重新验证。
UPDATE investment_product SET history_coverage_complete = 0 WHERE history_coverage_complete = 1;

-- 每次领取换 token，过期 worker 不得更新断点、重试或完成状态。
ALTER TABLE market_data_job ADD COLUMN lease_token VARCHAR(36) NULL;
