DELETE FROM investment_data_job
WHERE job_type = 'RESEARCH_UNIVERSE_HISTORY';

DELETE FROM investment_cash_ledger
WHERE account_id IN (
    SELECT id FROM investment_account WHERE account_type = 'PAPER'
);

DELETE FROM investment_transaction
WHERE account_id IN (
    SELECT id FROM investment_account WHERE account_type = 'PAPER'
);

DELETE FROM investment_position
WHERE account_id IN (
    SELECT id FROM investment_account WHERE account_type = 'PAPER'
);

DELETE FROM investment_cash_balance
WHERE account_id IN (
    SELECT id FROM investment_account WHERE account_type = 'PAPER'
);

DELETE FROM investment_account
WHERE account_type = 'PAPER';

DROP TABLE IF EXISTS quant_paper_fill;
DROP TABLE IF EXISTS quant_paper_order;
DROP TABLE IF EXISTS quant_validation_report;
DROP TABLE IF EXISTS quant_experiment;
DROP TABLE IF EXISTS quant_universe_membership;
DROP TABLE IF EXISTS quant_research_universe;
DROP TABLE IF EXISTS quant_backtest_run;
DROP TABLE IF EXISTS quant_prediction;
DROP TABLE IF EXISTS quant_strategy_version;
DROP TABLE IF EXISTS quant_model_monitor;
DROP TABLE IF EXISTS quant_model_version;
DROP TABLE IF EXISTS quant_training_run;
DROP TABLE IF EXISTS quant_job;
DROP TABLE IF EXISTS quant_feature_set;

ALTER TABLE investment_account
    DROP INDEX uk_investment_active_paper_user,
    DROP COLUMN active_paper_user_id;
