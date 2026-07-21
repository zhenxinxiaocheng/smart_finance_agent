ALTER TABLE product_daily_quote ADD COLUMN previous_close NUMERIC;
ALTER TABLE product_daily_quote ADD COLUMN change_amount NUMERIC;
ALTER TABLE product_daily_quote ADD COLUMN change_percent NUMERIC;
ALTER TABLE product_daily_quote ADD COLUMN amount NUMERIC;
ALTER TABLE product_daily_quote ADD COLUMN turnover_rate NUMERIC;
ALTER TABLE product_daily_quote ADD COLUMN volume_ratio NUMERIC;
ALTER TABLE product_daily_quote ADD COLUMN amplitude NUMERIC;
