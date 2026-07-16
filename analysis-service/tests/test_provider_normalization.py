import json
import unittest
from datetime import date
from datetime import datetime
from decimal import Decimal
from zoneinfo import ZoneInfo

import app.providers as providers

from app.providers import (
    TencentHistoryProvider,
    akshare_us_symbol,
    fetch_realtime_stock_quote,
    infer_a_share_market,
    normalize_quote,
    normalize_resolved_product,
    resolve_product_metadata,
)


class FakeHttpResponse:
    def __init__(self, payload):
        self.payload = payload

    def read(self):
        return self.payload.encode("gbk")

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False


class FakeJsonResponse(FakeHttpResponse):
    def read(self):
        return self.payload.encode("utf-8")


class FakeFrame:
    def __init__(self, records):
        self.records = records

    def to_dict(self, orient):
        assert orient == "records"
        return self.records


class FakeAkshare:
    def __init__(self):
        self.last_hist_kwargs = None

    def stock_info_a_code_name(self):
        return FakeFrame([{"code": "600519", "name": "贵州茅台"}])

    def stock_zh_a_hist(self, **kwargs):
        self.last_hist_kwargs = kwargs
        return FakeFrame([
            {"日期": "2026-07-09", "收盘": "1190.00"},
            {"日期": "2026-07-10", "收盘": "1204.98"},
        ])

    def fund_name_em(self):
        return FakeFrame([{"基金代码": "000001", "基金简称": "华夏成长混合"}])

    def fund_open_fund_info_em(self, **kwargs):
        return FakeFrame([
            {"净值日期": "2026-07-09", "单位净值": "1.500", "日增长率": "0.20"},
            {"净值日期": "2026-07-10", "单位净值": "1.527", "日增长率": "1.80"},
        ])


class ProviderNormalizationTest(unittest.TestCase):
    def test_a_share_trade_calendar_uses_provider_trading_dates(self):
        class FakeCalendarAkshare:
            @staticmethod
            def tool_trade_date_hist_sina():
                return FakeFrame([
                    {"trade_date": "2026-09-30"},
                    {"trade_date": "2026-10-08"},
                    {"trade_date": "2027-01-04"},
                ])

        fetcher = getattr(providers, "fetch_a_share_trade_calendar", None)
        self.assertIsNotNone(fetcher)

        result = fetcher(2026, ak_module=FakeCalendarAkshare())

        self.assertEqual(["2026-09-30", "2026-10-08"], result)

    def test_tencent_history_provider_parses_qfq_daily_rows(self):
        payload = json.dumps({
            "data": {
                "sz002632": {
                    "qfqday": [
                        ["2026-07-14", "8.79", "8.62", "9.03", "8.30", "22015"],
                        ["2026-07-15", "8.51", "8.75", "8.98", "8.31", "243779"],
                    ]
                }
            }
        })
        calls = []

        def opener(url, **kwargs):
            calls.append((url, kwargs))
            return FakeJsonResponse(payload)

        rows = TencentHistoryProvider(opener=opener).daily_quotes(
            "002632", "SZSE", "STOCK", date(2026, 7, 1), date(2026, 7, 15)
        )

        self.assertEqual(2, len(rows))
        self.assertEqual("TENCENT", rows[0].provider)
        self.assertEqual(Decimal("8.62"), rows[0].close)
        self.assertEqual(Decimal("2201500"), rows[0].volume)
        self.assertIn("sz002632,day,2026-07-01,2026-07-15,1000,qfq", calls[0][0])
        self.assertEqual(8, calls[0][1]["timeout"])

    def test_normalize_quote_keeps_source_and_uses_decimal_strings(self):
        result = normalize_quote(
            product_code="600519",
            market="SSE",
            trade_date="2026-07-10",
            raw={"open": "1500.1", "high": "1520", "low": "1490", "close": "1510.25", "volume": "1200"},
            provider="AKSHARE",
        )

        self.assertEqual("AKSHARE", result.provider)
        self.assertEqual(Decimal("1510.25"), result.close)
        self.assertEqual("2026-07-10", result.data_date.isoformat())

    def test_akshare_us_symbol_uses_eastmoney_market_prefix(self):
        self.assertEqual("105.AAPL", akshare_us_symbol("AAPL", "NASDAQ"))
        self.assertEqual("106.BRK.B", akshare_us_symbol("brk.b", "NYSE"))
        self.assertEqual("107.SPY", akshare_us_symbol("SPY", "AMEX"))
        self.assertEqual("105.MSFT", akshare_us_symbol("105.MSFT", "NASDAQ"))

    def test_infer_a_share_market_from_code(self):
        self.assertEqual("SSE", infer_a_share_market("600519"))
        self.assertEqual("SZSE", infer_a_share_market("000001"))
        self.assertEqual("BSE", infer_a_share_market("920001"))

    def test_normalize_resolved_product_keeps_standard_fields(self):
        result = normalize_resolved_product(
            product_type="stock",
            code="600519",
            name="贵州茅台",
            market="sse",
            currency="cny",
            provider="akshare",
            latest_price="1204.98",
            data_date="2026-07-10",
        )

        self.assertEqual("STOCK", result["productType"])
        self.assertEqual("600519", result["code"])
        self.assertEqual("贵州茅台", result["name"])
        self.assertEqual("1204.98", result["latestPrice"])

    def test_resolve_a_share_uses_code_list_and_latest_daily_price(self):
        provider = FakeAkshare()
        result = resolve_product_metadata("STOCK", "600519", ak_module=provider)

        self.assertEqual("贵州茅台", result["name"])
        self.assertEqual("SSE", result["market"])
        self.assertEqual("1204.98", result["latestPrice"])
        self.assertEqual("14.98", result["changeAmount"])
        self.assertEqual("1.2588", result["changePercent"])
        self.assertEqual("qfq", provider.last_hist_kwargs["adjust"])

    def test_resolve_domestic_fund_uses_fund_name_and_nav(self):
        result = resolve_product_metadata("MUTUAL_FUND", "000001", ak_module=FakeAkshare())

        self.assertEqual("华夏成长混合", result["name"])
        self.assertEqual("FUND_CN", result["market"])
        self.assertEqual("1.527", result["latestPrice"])
        self.assertEqual("0.027", result["changeAmount"])
        self.assertEqual("1.80", result["changePercent"])

    def test_fetch_tencent_quote_uses_actual_fetch_time(self):
        fields = [""] * 50
        values = {
            0: "51", 1: "道明光学", 2: "002632", 3: "8.62", 4: "8.90", 5: "8.75",
            30: "20260713112330", 31: "-0.28", 32: "-3.15", 33: "9.20", 34: "8.61",
            36: "217551", 37: "19430", 38: "3.79", 43: "6.63", 49: "2.61",
        }
        for index, value in values.items():
            fields[index] = value
        payload = f'v_sz002632="{"~".join(fields)}";'

        result = fetch_realtime_stock_quote(
            "002632", "SZSE",
            opener=lambda *_args, **_kwargs: FakeHttpResponse(payload),
            clock=lambda: datetime(2026, 7, 13, 11, 51, 9, tzinfo=ZoneInfo("Asia/Shanghai")),
        )

        self.assertEqual("8.62", result["latestPrice"])
        self.assertEqual("2026-07-13", result["dataDate"])
        self.assertEqual("2026-07-13T11:51:09+08:00", result["fetchedAt"])
        self.assertEqual("TENCENT", result["provider"])
        self.assertEqual("8.90", result["previousClose"])
        self.assertEqual("-0.28", result["changeAmount"])
        self.assertEqual("-3.15", result["changePercent"])
        self.assertEqual("8.75", result["openPrice"])
        self.assertEqual("9.20", result["highPrice"])
        self.assertEqual("8.61", result["lowPrice"])
        self.assertEqual("21755100", result["volume"])
        self.assertEqual("194300000", result["amount"])
        self.assertEqual("3.79", result["turnoverRate"])
        self.assertEqual("2.61", result["volumeRatio"])
        self.assertEqual("6.63", result["amplitude"])

    def test_fetch_realtime_quote_falls_back_to_sina(self):
        payload = (
            'var hq_str_sz002632="道明光学,8.750,8.900,8.640,9.200,8.610,8.630,8.640,'
            '21552020,192543195.890,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,'
            '2026-07-13,11:23:31,00";'
        )

        def opener(request, **_kwargs):
            url = getattr(request, "full_url", str(request))
            if "gtimg.cn" in url:
                raise OSError("primary unavailable")
            return FakeHttpResponse(payload)

        result = fetch_realtime_stock_quote(
            "002632", "SZSE", opener=opener,
            clock=lambda: datetime(2026, 7, 13, 11, 51, 10, tzinfo=ZoneInfo("Asia/Shanghai")),
        )

        self.assertEqual("8.640", result["latestPrice"])
        self.assertEqual("2026-07-13T11:51:10+08:00", result["fetchedAt"])
        self.assertEqual("SINA", result["provider"])
        self.assertEqual("8.900", result["previousClose"])
        self.assertEqual("-0.260", result["changeAmount"])
        self.assertEqual("-2.9213", result["changePercent"])
        self.assertEqual("8.750", result["openPrice"])
        self.assertEqual("9.200", result["highPrice"])
        self.assertEqual("8.610", result["lowPrice"])
        self.assertEqual("21552020", result["volume"])
        self.assertEqual("192543195.890", result["amount"])
        self.assertIsNone(result["turnoverRate"])
        self.assertIsNone(result["volumeRatio"])
        self.assertEqual("6.6292", result["amplitude"])


if __name__ == "__main__":
    unittest.main()
