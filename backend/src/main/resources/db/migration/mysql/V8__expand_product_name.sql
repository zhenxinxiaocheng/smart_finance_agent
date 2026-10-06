-- Preserve complete exchange directory names rather than truncating source data.
ALTER TABLE investment_product MODIFY COLUMN name VARCHAR(512) NOT NULL,
    ALGORITHM=INPLACE, LOCK=NONE;
