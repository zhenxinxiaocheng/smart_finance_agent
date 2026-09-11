import unittest
from unittest.mock import patch

from app.main import IndexQuotesRequest, index_quotes, search_indexes


class IndexEndpointTest(unittest.TestCase):
    @patch("app.main.fetch_index_quotes")
    def test_batch_quotes_use_canonical_index_codes(self, fetcher):
        fetcher.return_value = [{
            "indexCode": "GLOBAL_INDEX:NDX",
            "name": "纳斯达克100",
            "market": "US",
            "latestPrice": "29143.33",
        }]

        result = index_quotes(IndexQuotesRequest(indexCodes=["GLOBAL_INDEX:NDX"]))

        self.assertEqual("GLOBAL_INDEX:NDX", result["items"][0]["indexCode"])
        fetcher.assert_called_once_with(["GLOBAL_INDEX:NDX"])

    @patch("app.main.search_index_quotes")
    def test_search_forwards_keyword_and_limit(self, searcher):
        searcher.return_value = []

        result = search_indexes("沪深300", 12)

        self.assertEqual([], result["items"])
        searcher.assert_called_once_with("沪深300", limit=12)


if __name__ == "__main__":
    unittest.main()
