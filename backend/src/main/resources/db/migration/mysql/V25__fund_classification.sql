ALTER TABLE investment_product
    ADD COLUMN fund_type_raw VARCHAR(80) NULL AFTER product_type,
    ADD COLUMN fund_category VARCHAR(40) NULL AFTER fund_type_raw,
    ADD COLUMN classification_source VARCHAR(60) NULL AFTER fund_category,
    ADD COLUMN classification_version VARCHAR(80) NULL AFTER classification_source,
    ADD COLUMN classified_at DATETIME NULL AFTER classification_version;

CREATE INDEX idx_investment_product_fund_category
    ON investment_product(product_type, fund_category);

UPDATE investment_product p
JOIN benchmark_profile b
  ON b.product_type = p.product_type
 AND b.product_code = p.code
 AND b.active = 1
 AND b.effective_from <= CURRENT_DATE
 AND (b.effective_to IS NULL OR b.effective_to >= CURRENT_DATE)
LEFT JOIN benchmark_profile newer
  ON newer.product_type = b.product_type
 AND newer.product_code = b.product_code
 AND newer.active = 1
 AND newer.effective_from <= CURRENT_DATE
 AND (newer.effective_to IS NULL OR newer.effective_to >= CURRENT_DATE)
 AND (
      newer.effective_from > b.effective_from
      OR (newer.effective_from = b.effective_from AND newer.id > b.id)
 )
SET p.fund_category = b.model_family,
    p.classification_source = 'CURATED_BENCHMARK_PROFILE',
    p.classification_version = b.source_version,
    p.classified_at = CURRENT_TIMESTAMP
WHERE p.product_type = 'MUTUAL_FUND'
  AND p.fund_category IS NULL
  AND newer.id IS NULL;
