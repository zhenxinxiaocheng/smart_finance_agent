import unittest
from unittest.mock import patch

from app.main import ProductResolveRequest, RealtimeQuoteRequest, realtime_quote, resolve_product


class ProductEndpointTest(unittest.TestCase):
    @patch("app.main.resolve_product_metadata")
    def test_resolve_product_returns_normalized_provider_result(self, resolver):
        resolver.return_value = {
            "productType": "STOCK",
            "code": "600519",
            "name": "贵州茅台",
            "market": "SSE",
            "currency": "CNY",
            "provider": "AKSHARE",
            "dataDate": "2026-07-10",
            "latestPrice": "1204.98",
            "warnings": [],
        }

        result = resolve_product(ProductResolveRequest(product_type="STOCK", code="600519"))

        self.assertEqual("贵州茅台", result["name"])
        resolver.assert_called_once_with("STOCK", "600519")

    @patch("app.main.fetch_realtime_stock_quote")
    def test_realtime_quote_returns_normalized_provider_result(self, quote_fetcher):
        quote_fetcher.return_value = {
            "code": "002632", "market": "SZSE", "latestPrice": "8.63",
            "dataDate": "2026-07-13", "fetchedAt": "2026-07-13T11:23:30+08:00",
            "provider": "TENCENT", "warnings": [],
        }

        result = realtime_quote(RealtimeQuoteRequest(code="002632", market="SZSE"))

        self.assertEqual("8.63", result["latestPrice"])
        quote_fetcher.assert_called_once_with("002632", "SZSE")


if __name__ == "__main__":
    unittest.main()
