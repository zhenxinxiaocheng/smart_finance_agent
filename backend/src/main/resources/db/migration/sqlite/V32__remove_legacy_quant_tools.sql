DELETE FROM agent_skill
WHERE source_type = 'BUILT_IN'
  AND skill_key IN (
    'get_investment_quant_signal',
    'get_investment_quant_strategy_status',
    'get_investment_paper_account'
  );
