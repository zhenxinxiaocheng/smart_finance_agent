-- Keep the original source observation time separate from a later re-normalization.
ALTER TABLE investment_research_record ADD COLUMN first_observed_at DATETIME(6) NULL;
