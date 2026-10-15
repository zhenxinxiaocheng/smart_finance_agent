ALTER TABLE product_daily_quote ADD COLUMN data_contract_version VARCHAR(64) NULL;
CREATE INDEX idx_quote_contract ON product_daily_quote(product_id,adjust_type,data_contract_version,trade_date);
ALTER TABLE investment_research_record ADD COLUMN data_contract_version VARCHAR(64) NULL;
ALTER TABLE investment_research_record ADD COLUMN source_content_hash CHAR(64) NULL;
CREATE INDEX idx_research_contract ON investment_research_record(product_id,dataset,data_contract_version,available_at,id);
ALTER TABLE market_data_requirement ADD COLUMN prepared_contract_version VARCHAR(64) NULL;
ALTER TABLE market_data_job ADD COLUMN data_contract_version VARCHAR(64) NULL;
ALTER TABLE market_data_job ADD COLUMN error_code VARCHAR(40) NULL;

CREATE TABLE market_data_collection_batch (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    product_id INTEGER NOT NULL,
    dataset VARCHAR(20) NOT NULL,
    adjust_type VARCHAR(20) NOT NULL,
    frequency VARCHAR(16) NOT NULL DEFAULT 'DAILY',
    requested_start_date DATE NOT NULL,
    requested_end_date DATE NOT NULL,
    data_start_date DATE NULL,
    data_end_date DATE NULL,
    record_count INTEGER NOT NULL,
    source_result VARCHAR(16) NOT NULL,
    provider VARCHAR(64) NULL,
    adapter_version VARCHAR(64) NULL,
    data_contract_version VARCHAR(64) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    observed_dates_hash CHAR(64) NULL,
    collected_at TEXT NOT NULL,
    valid_until TEXT NULL,
    CHECK (requested_start_date <= requested_end_date),
    CHECK (record_count >= 0),
    CHECK (source_result IN ('DATA','EMPTY','CLOSED')),
    UNIQUE(product_id,dataset,adjust_type,frequency,data_contract_version,requested_start_date,requested_end_date,content_hash)
);
CREATE INDEX idx_collection_window ON market_data_collection_batch(product_id,dataset,adjust_type,data_contract_version,requested_start_date,requested_end_date);
UPDATE market_data_requirement SET prepared_start_date=NULL,prepared_end_date=NULL,revision=revision+1;
