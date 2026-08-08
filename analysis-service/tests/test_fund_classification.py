import unittest

import app.providers as providers
from app.fund_classification import is_known_fund_category


class FundClassificationTest(unittest.TestCase):
    def test_provider_types_are_normalized_by_versioned_rules(self):
        cases = {
            "指数型-股票": "INDEX_FUND",
            "指数型-海外股票": "QDII_INDEX_FUND",
            "指数型-其他": "OTHER_INDEX_FUND",
            "混合型-偏股": "HYBRID_FUND",
            "债券型-长债": "BOND_FUND",
            "货币型": "MONEY_MARKET_FUND",
        }

        for raw_type, expected_category in cases.items():
            with self.subTest(raw_type=raw_type):
                result = providers.classify_fund_type(raw_type)
                self.assertEqual(expected_category, result.category)
                self.assertEqual(raw_type, result.raw_type)
                self.assertEqual("fund-classification-v1", result.version)

    def test_missing_and_unmapped_provider_types_fail_closed(self):
        for raw_type in (None, "", "尚未支持的新类型"):
            with self.subTest(raw_type=raw_type):
                result = providers.classify_fund_type(raw_type)
                self.assertEqual("UNKNOWN", result.category)

    def test_curated_categories_are_accepted_without_name_or_code_rules(self):
        self.assertTrue(is_known_fund_category("COMMODITY_FUND"))
        self.assertTrue(is_known_fund_category("ACTIVE_FUND"))


if __name__ == "__main__":
    unittest.main()
