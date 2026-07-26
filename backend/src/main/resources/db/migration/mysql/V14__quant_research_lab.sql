ALTER TABLE quant_job
    ADD COLUMN experiment_fingerprint VARCHAR(64),
    ADD COLUMN error_code VARCHAR(40),
    ADD COLUMN error_summary VARCHAR(500),
    ADD UNIQUE KEY uk_quant_job_fingerprint (user_id, experiment_fingerprint);

ALTER TABLE quant_prediction
    ADD COLUMN model_family VARCHAR(40),
    ADD COLUMN feature_vector_json LONGTEXT;

UPDATE quant_model_version SET status = 'DRAFT' WHERE status <> 'RETIRED';
UPDATE quant_strategy_version
SET status = 'DRAFT', activated_at = NULL
WHERE status <> 'RETIRED';

CREATE TABLE IF NOT EXISTS benchmark_profile (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    product_type VARCHAR(30) NOT NULL,
    product_code VARCHAR(40),
    model_family VARCHAR(40) NOT NULL,
    benchmark_code VARCHAR(80) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    composition_json LONGTEXT NOT NULL,
    currency VARCHAR(10) NOT NULL,
    fx_rule VARCHAR(40),
    source_uri VARCHAR(1000) NOT NULL,
    source_version VARCHAR(80) NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE,
    active TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_benchmark_profile_version (product_type, product_code, effective_from),
    KEY idx_benchmark_profile_lookup (product_type, product_code, active, effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO benchmark_profile
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
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    universe_code VARCHAR(80) NOT NULL,
    name VARCHAR(120) NOT NULL,
    model_family VARCHAR(40) NOT NULL,
    market VARCHAR(20),
    selection_rule_json LONGTEXT NOT NULL,
    dataset_version VARCHAR(64),
    active TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_quant_research_universe_code (universe_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS quant_universe_membership (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    universe_id BIGINT NOT NULL,
    product_id BIGINT,
    product_type VARCHAR(30) NOT NULL,
    market VARCHAR(20),
    code VARCHAR(40) NOT NULL,
    valid_from DATE NOT NULL,
    valid_to DATE,
    source_snapshot VARCHAR(255) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_quant_universe_member_version (universe_id, code, valid_from),
    KEY idx_quant_universe_membership_date (universe_id, valid_from, valid_to),
    CONSTRAINT fk_quant_universe_membership_universe FOREIGN KEY (universe_id)
        REFERENCES quant_research_universe(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO quant_research_universe
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

INSERT IGNORE INTO quant_universe_membership
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
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    asset_id BIGINT,
    universe_id BIGINT,
    model_family VARCHAR(40) NOT NULL,
    horizon_code VARCHAR(20) NOT NULL,
    horizon_days INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    experiment_fingerprint VARCHAR(64) NOT NULL,
    config_json LONGTEXT NOT NULL,
    dataset_version VARCHAR(64),
    feature_set_version VARCHAR(64),
    quant_config_version VARCHAR(80) NOT NULL,
    code_version VARCHAR(80) NOT NULL,
    candidate_model_version VARCHAR(64),
    logs_json LONGTEXT,
    error_code VARCHAR(40),
    error_summary VARCHAR(500),
    started_at DATETIME,
    finished_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_quant_experiment_fingerprint (user_id, experiment_fingerprint),
    KEY idx_quant_experiment_user_status (user_id, status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS quant_validation_report (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    experiment_id BIGINT,
    model_version VARCHAR(64) NOT NULL,
    lifecycle VARCHAR(20) NOT NULL,
    passed TINYINT(1) NOT NULL,
    failure_codes_json LONGTEXT NOT NULL,
    checks_json LONGTEXT NOT NULL,
    metrics_json LONGTEXT NOT NULL,
    dataset_version VARCHAR(64),
    feature_set_version VARCHAR(64),
    quant_config_version VARCHAR(80) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_quant_validation_model (model_version),
    CONSTRAINT fk_quant_validation_experiment FOREIGN KEY (experiment_id)
        REFERENCES quant_experiment(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
