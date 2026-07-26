CREATE UNIQUE INDEX uk_investment_active_paper_user
    ON investment_account(user_id)
    WHERE account_type = 'PAPER' AND deleted = 0;

CREATE UNIQUE INDEX uk_quant_paper_order_prediction
    ON quant_paper_order(prediction_id);

CREATE UNIQUE INDEX uk_quant_paper_fill_order
    ON quant_paper_fill(order_id);
