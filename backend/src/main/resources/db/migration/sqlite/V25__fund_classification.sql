ALTER TABLE investment_product ADD COLUMN fund_type_raw TEXT;
ALTER TABLE investment_product ADD COLUMN fund_category TEXT;
ALTER TABLE investment_product ADD COLUMN classification_source TEXT;
ALTER TABLE investment_product ADD COLUMN classification_version TEXT;
ALTER TABLE investment_product ADD COLUMN classified_at TEXT;

CREATE INDEX IF NOT EXISTS idx_investment_product_fund_category
    ON investment_product(product_type, fund_category);

UPDATE investment_product
SET fund_category = (
        SELECT b.model_family
        FROM benchmark_profile b
        WHERE b.product_type = investment_product.product_type
          AND b.product_code = investment_product.code
          AND b.active = 1
          AND b.effective_from <= DATE('now')
          AND (b.effective_to IS NULL OR b.effective_to >= DATE('now'))
        ORDER BY b.effective_from DESC, b.id DESC
        LIMIT 1
    ),
    classification_source = 'CURATED_BENCHMARK_PROFILE',
    classification_version = (
        SELECT b.source_version
        FROM benchmark_profile b
        WHERE b.product_type = investment_product.product_type
          AND b.product_code = investment_product.code
          AND b.active = 1
          AND b.effective_from <= DATE('now')
          AND (b.effective_to IS NULL OR b.effective_to >= DATE('now'))
        ORDER BY b.effective_from DESC, b.id DESC
        LIMIT 1
    ),
    classified_at = CURRENT_TIMESTAMP
WHERE product_type = 'MUTUAL_FUND'
  AND fund_category IS NULL
  AND EXISTS (
      SELECT 1
      FROM benchmark_profile b
      WHERE b.product_type = investment_product.product_type
        AND b.product_code = investment_product.code
        AND b.active = 1
        AND b.effective_from <= DATE('now')
        AND (b.effective_to IS NULL OR b.effective_to >= DATE('now'))
  );
