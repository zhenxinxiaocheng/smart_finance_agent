ALTER TABLE quant_prediction ADD COLUMN profit_probability DECIMAL(18, 10);
ALTER TABLE quant_prediction ADD COLUMN loss_probability DECIMAL(18, 10);
ALTER TABLE quant_prediction ADD COLUMN expected_net_return DECIMAL(18, 10);
