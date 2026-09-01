DELETE FROM quant_prediction
WHERE benchmark_code IN (
    SELECT benchmark_code
    FROM benchmark_profile
    WHERE product_type = 'MUTUAL_FUND'
      AND product_code IS NOT NULL
);

DELETE FROM quant_benchmark_snapshot
WHERE benchmark_profile_id IN (
    SELECT id
    FROM benchmark_profile
    WHERE product_type = 'MUTUAL_FUND'
      AND product_code IS NOT NULL
);

DELETE FROM benchmark_profile
WHERE product_type = 'MUTUAL_FUND'
  AND product_code IS NOT NULL;

UPDATE investment_product
SET fund_category = NULL,
    classification_source = NULL,
    classification_version = NULL,
    classified_at = NULL
WHERE product_type = 'MUTUAL_FUND'
  AND classification_source = 'CURATED_BENCHMARK_PROFILE';
