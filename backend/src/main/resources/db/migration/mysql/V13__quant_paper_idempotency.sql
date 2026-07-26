ALTER TABLE investment_account
    ADD COLUMN active_paper_user_id BIGINT
        GENERATED ALWAYS AS (
            CASE WHEN account_type = 'PAPER' AND deleted = 0 THEN user_id ELSE NULL END
        ) STORED;

CREATE UNIQUE INDEX uk_investment_active_paper_user
    ON investment_account(active_paper_user_id);

CREATE UNIQUE INDEX uk_quant_paper_order_prediction
    ON quant_paper_order(prediction_id);

CREATE UNIQUE INDEX uk_quant_paper_fill_order
    ON quant_paper_fill(order_id);
