ALTER TABLE quant_job ADD COLUMN experiment_fingerprint TEXT;
ALTER TABLE quant_job ADD COLUMN error_code TEXT;
ALTER TABLE quant_job ADD COLUMN error_summary TEXT;
ALTER TABLE quant_prediction ADD COLUMN model_family TEXT;
ALTER TABLE quant_prediction ADD COLUMN feature_vector_json TEXT;

UPDATE quant_model_version SET status = 'DRAFT' WHERE status <> 'RETIRED';
UPDATE quant_strategy_version
SET status = 'DRAFT', activated_at = NULL
WHERE status <> 'RETIRED';

CREATE UNIQUE INDEX IF NOT EXISTS uk_quant_job_fingerprint
    ON quant_job(user_id, experiment_fingerprint)
    WHERE experiment_fingerprint IS NOT NULL;

CREATE TABLE IF NOT EXISTS benchmark_profile (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    product_type TEXT NOT NULL,
    product_code TEXT,
    model_family TEXT NOT NULL,
    benchmark_code TEXT NOT NULL,
    display_name TEXT NOT NULL,
    composition_json TEXT NOT NULL,
    currency TEXT NOT NULL,
    fx_rule TEXT,
    source_uri TEXT NOT NULL,
    source_version TEXT NOT NULL,
    effective_from TEXT NOT NULL,
    effective_to TEXT,
    active INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(product_type, product_code, effective_from)
);

CREATE INDEX idx_benchmark_profile_lookup
    ON benchmark_profile(product_type, product_code, active, effective_from);

INSERT OR IGNORE INTO benchmark_profile
    (product_type, product_code, model_family, benchmark_code, display_name,
     composition_json, currency, fx_rule, source_uri, source_version, effective_from)
VALUES
    ('STOCK', NULL, 'A_SHARE_STOCK', 'CSI300',
     'CSI 300 market benchmark',
     '{"CSI300":1.0}', 'CNY', 'NONE',
     'https://www.csindex.com.cn/', 'CSI-OFFICIAL-V1', '2005-04-08'),
    ('MUTUAL_FUND', '010736', 'INDEX_FUND', 'CSI300_95_CASH_5',
     '沪深300收益率×95%＋活期存款税后利率×5%',
     '{"CSI300":0.95,"CASH_CNY":0.05}', 'CNY', 'NONE',
     'https://www.efunds.com.cn/', 'OFFICIAL-2024-ANNUAL', '2021-01-01'),
    ('MUTUAL_FUND', '000218', 'COMMODITY_FUND', 'AU9999_95_CASH_5',
     'Au99.99×95%＋活期存款税后利率×5%',
     '{"AU9999":0.95,"CASH_CNY":0.05}', 'CNY', 'NONE',
     'https://www.gtfund.com/', 'OFFICIAL-PRODUCT', '2013-07-18'),
    ('MUTUAL_FUND', '270042', 'QDII_INDEX_FUND', 'NASDAQ100_TR_CNY',
     '人民币计价纳斯达克100总收益指数',
     '{"NASDAQ100_TOTAL_RETURN":1.0}', 'CNY', 'CNY_CONVERTED',
     'https://www.gffunds.com.cn/', 'OFFICIAL-PRODUCT', '2012-08-15'),
    ('MUTUAL_FUND', '000834', 'QDII_INDEX_FUND', 'NASDAQ100_FX_ADJUSTED',
     '经汇率调整的纳斯达克100指数收益率',
     '{"NASDAQ100":1.0}', 'CNY', 'FX_ADJUSTED',
     'https://www.dcfund.com.cn/', 'OFFICIAL-REPORT', '2014-11-13');

CREATE TABLE IF NOT EXISTS quant_research_universe (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    universe_code TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    model_family TEXT NOT NULL,
    market TEXT,
    selection_rule_json TEXT NOT NULL,
    dataset_version TEXT,
    active INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS quant_universe_membership (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    universe_id INTEGER NOT NULL,
    product_id INTEGER,
    product_type TEXT NOT NULL,
    market TEXT,
    code TEXT NOT NULL,
    valid_from TEXT NOT NULL,
    valid_to TEXT,
    source_snapshot TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(universe_id, code, valid_from),
    FOREIGN KEY(universe_id) REFERENCES quant_research_universe(id)
);

CREATE INDEX idx_quant_universe_membership_date
    ON quant_universe_membership(universe_id, valid_from, valid_to);

INSERT OR IGNORE INTO quant_research_universe
    (universe_code, name, model_family, market, selection_rule_json, active)
VALUES
    ('A_SHARE_HISTORY', 'A-share historical product pool', 'A_SHARE_STOCK', 'CN',
     '{"productType":"STOCK","membershipMode":"POINT_IN_TIME"}', 1),
    ('INDEX_FUND_HISTORY', 'Domestic index fund historical pool', 'INDEX_FUND', 'CN',
     '{"productType":"MUTUAL_FUND","classification":"INDEX","membershipMode":"POINT_IN_TIME"}', 1),
    ('ACTIVE_FUND_HISTORY', 'Active fund historical pool', 'ACTIVE_FUND', 'CN',
     '{"productType":"MUTUAL_FUND","classification":"ACTIVE","membershipMode":"POINT_IN_TIME"}', 1),
    ('QDII_INDEX_HISTORY', 'QDII index fund historical pool', 'QDII_INDEX_FUND', 'GLOBAL',
     '{"productType":"MUTUAL_FUND","classification":"QDII_INDEX","membershipMode":"POINT_IN_TIME"}', 1),
    ('COMMODITY_FUND_HISTORY', 'Commodity fund historical pool', 'COMMODITY_FUND', 'CN',
     '{"productType":"MUTUAL_FUND","classification":"COMMODITY","membershipMode":"POINT_IN_TIME"}', 1);

INSERT OR IGNORE INTO quant_universe_membership
    (universe_id, product_id, product_type, market, code, valid_from, source_snapshot)
SELECT u.id, p.id, p.product_type, p.market, p.code, '2005-01-01', 'V14_CURRENT_PRODUCT_SNAPSHOT'
FROM investment_product p
JOIN quant_research_universe u
  ON u.universe_code = CASE
      WHEN p.product_type = 'STOCK' THEN 'A_SHARE_HISTORY'
      WHEN p.code = '010736' THEN 'INDEX_FUND_HISTORY'
      WHEN p.code IN ('270042', '000834') THEN 'QDII_INDEX_HISTORY'
      WHEN p.code = '000218' THEN 'COMMODITY_FUND_HISTORY'
      ELSE 'ACTIVE_FUND_HISTORY'
  END
WHERE p.status = 'ACTIVE';

CREATE TABLE IF NOT EXISTS quant_experiment (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    asset_id INTEGER,
    universe_id INTEGER,
    model_family TEXT NOT NULL,
    horizon_code TEXT NOT NULL,
    horizon_days INTEGER NOT NULL,
    status TEXT NOT NULL,
    experiment_fingerprint TEXT NOT NULL,
    config_json TEXT NOT NULL,
    dataset_version TEXT,
    feature_set_version TEXT,
    quant_config_version TEXT NOT NULL,
    code_version TEXT NOT NULL,
    candidate_model_version TEXT,
    logs_json TEXT,
    error_code TEXT,
    error_summary TEXT,
    started_at TEXT,
    finished_at TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, experiment_fingerprint)
);

CREATE INDEX idx_quant_experiment_user_status
    ON quant_experiment(user_id, status, created_at);

CREATE TABLE IF NOT EXISTS quant_validation_report (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    experiment_id INTEGER,
    model_version TEXT NOT NULL,
    lifecycle TEXT NOT NULL,
    passed INTEGER NOT NULL,
    failure_codes_json TEXT NOT NULL,
    checks_json TEXT NOT NULL,
    metrics_json TEXT NOT NULL,
    dataset_version TEXT,
    feature_set_version TEXT,
    quant_config_version TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(model_version),
    FOREIGN KEY(experiment_id) REFERENCES quant_experiment(id)
);
