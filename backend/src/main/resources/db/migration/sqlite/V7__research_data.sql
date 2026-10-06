CREATE TABLE investment_research_record (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    product_id INTEGER NOT NULL,
    dataset TEXT NOT NULL,
    record_key TEXT NOT NULL,
    content_hash TEXT NOT NULL,
    provider TEXT NOT NULL,
    adapter_version TEXT NOT NULL,
    as_of_date TEXT NULL,
    published_date TEXT NULL,
    observed_at TEXT NOT NULL,
    available_at TEXT NOT NULL,
    availability_basis TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    UNIQUE (product_id,dataset,provider,record_key,observed_at)
);
CREATE INDEX idx_research_asof ON investment_research_record(product_id,dataset,available_at,id);
CREATE INDEX idx_research_versions ON investment_research_record(product_id,dataset,provider,record_key,id);

UPDATE market_data_job SET status='QUEUED',attempt_count=0,next_retry_at=NULL,
lease_until=NULL,lease_token=NULL,updated_at=datetime('now')
WHERE status='FAILED' AND product_id IN (SELECT id FROM investment_product WHERE product_type IN ('ETF','INDEX'))
AND last_error LIKE '%product_type must be STOCK or MUTUAL_FUND%';
