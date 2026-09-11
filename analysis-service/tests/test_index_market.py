import unittest

from app.providers import fetch_index_quotes, search_index_quotes


class FakeFrame:
    def __init__(self, records):
        self.records = records

    def to_dict(self, orient):
        assert orient == "records"
        return self.records


class FakeAkshare:
    def stock_zh_index_spot_sina(self):
        return FakeFrame([
            {
                "代码": "sh000300",
                "名称": "沪深300",
                "最新价": "4552.58",
                "涨跌额": "4.62",
                "涨跌幅": "0.10",
                "昨收": "4547.96",
                "今开": "4548.10",
                "最高": "4560.00",
                "最低": "4530.00",
            }
        ])

    def index_global_spot_em(self):
        return FakeFrame([
            {
                "代码": "NDX",
                "名称": "纳斯达克",
                "最新价": "29143.33",
                "涨跌额": "66.11",
                "涨跌幅": "0.23",
                "昨收价": "29077.22",
                "开盘价": "29090.00",
                "最高价": "29200.00",
                "最低价": "28950.00",
                "最新行情时间": "2026-09-03 05:00:00",
            }
        ])


class FakeAkshareGlobalUnavailable(FakeAkshare):
    def index_global_spot_em(self):
        raise ConnectionError("global index source unavailable")

    def index_us_stock_sina(self, symbol):
        assert symbol == ".NDX"
        return FakeFrame([
            {
                "date": "2026-09-01",
                "open": "29056.5781",
                "high": "29267.4219",
                "low": "28953.2559",
                "close": "29077.2207",
            },
            {
                "date": "2026-09-02",
                "open": "29015.9961",
                "high": "29165.6230",
                "low": "28971.8984",
                "close": "29143.3301",
            },
        ])


class IndexMarketTest(unittest.TestCase):
    def test_quotes_keep_real_index_levels_and_use_canonical_codes(self):
        result = fetch_index_quotes(
            ["CN_INDEX:000300", "GLOBAL_INDEX:NDX"],
            ak_module=FakeAkshare(),
        )

        self.assertEqual(["CN_INDEX:000300", "GLOBAL_INDEX:NDX"], [item["indexCode"] for item in result])
        self.assertEqual("4552.58", result[0]["latestPrice"])
        self.assertEqual("29143.33", result[1]["latestPrice"])
        self.assertEqual("纳斯达克100", result[1]["name"])
        self.assertEqual("0.23", result[1]["changePercent"])

    def test_configured_history_fallback_keeps_nasdaq_quote_available(self):
        result = fetch_index_quotes(
            ["GLOBAL_INDEX:NDX"],
            ak_module=FakeAkshareGlobalUnavailable(),
        )

        self.assertEqual(1, len(result))
        self.assertEqual("GLOBAL_INDEX:NDX", result[0]["indexCode"])
        self.assertEqual("29143.3301", result[0]["latestPrice"])
        self.assertEqual("66.1094", result[0]["changeAmount"])
        self.assertEqual("AKSHARE_SINA_US_INDEX_HISTORY", result[0]["provider"])

    def test_search_matches_name_or_code_without_mixing_assets(self):
        result = search_index_quotes("沪深", limit=10, ak_module=FakeAkshare())

        self.assertEqual(1, len(result))
        self.assertEqual("CN_INDEX:000300", result[0]["indexCode"])
        self.assertNotIn("productType", result[0])

        nasdaq = search_index_quotes("纳斯达克100", limit=10, ak_module=FakeAkshare())
        self.assertEqual("GLOBAL_INDEX:NDX", nasdaq[0]["indexCode"])


if __name__ == "__main__":
    unittest.main()
