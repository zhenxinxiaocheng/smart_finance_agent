ALTER TABLE quant_prediction
    ADD COLUMN profit_probability DECIMAL(18, 10) NULL AFTER as_of_date,
    ADD COLUMN loss_probability DECIMAL(18, 10) NULL AFTER profit_probability,
    ADD COLUMN expected_net_return DECIMAL(18, 10) NULL AFTER loss_probability;
