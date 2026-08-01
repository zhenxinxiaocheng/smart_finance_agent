UPDATE quant_research_universe
SET selection_rule_json = '{
  "productType":"MUTUAL_FUND",
  "classification":"INDEX",
  "membershipMode":"CURRENT_CATALOG_SNAPSHOT",
  "benchmarkRules":{
    "CSI300_95_CASH_5":{
      "catalogSymbol":"指数型",
      "nameAliases":["沪深300"],
      "excludedNamePatterns":["C","E"],
      "memberLimit":12,
      "minimumMembers":5,
      "minimumRecords":500
    }
  }
}'
WHERE universe_code = 'INDEX_FUND_HISTORY';

UPDATE quant_research_universe
SET selection_rule_json = '{
  "productType":"MUTUAL_FUND",
  "classification":"QDII_INDEX",
  "membershipMode":"CURRENT_CATALOG_SNAPSHOT",
  "benchmarkRules":{
    "NASDAQ100_TR_CNY":{
      "catalogSymbol":"QDII",
      "nameAliases":["纳斯达克100","纳指100","NASDAQ100"],
      "excludedNamePatterns":["C","E"],
      "memberLimit":12,
      "minimumMembers":5,
      "minimumRecords":500
    },
    "NASDAQ100_FX_ADJUSTED":{
      "catalogSymbol":"QDII",
      "nameAliases":["纳斯达克100","纳指100","NASDAQ100"],
      "excludedNamePatterns":["C","E"],
      "memberLimit":12,
      "minimumMembers":5,
      "minimumRecords":500
    }
  }
}'
WHERE universe_code = 'QDII_INDEX_HISTORY';

UPDATE quant_research_universe
SET selection_rule_json = '{
  "productType":"MUTUAL_FUND",
  "classification":"COMMODITY",
  "membershipMode":"CURRENT_CATALOG_SNAPSHOT",
  "benchmarkRules":{
    "AU9999_95_CASH_5":{
      "catalogSymbol":"指数型",
      "nameAliases":["黄金","金价","Au99.99"],
      "excludedNamePatterns":["C","E"],
      "memberLimit":12,
      "minimumMembers":5,
      "minimumRecords":500
    }
  }
}'
WHERE universe_code = 'COMMODITY_FUND_HISTORY';
