import gc
import json
import threading
import unittest
import weakref
from concurrent.futures import ThreadPoolExecutor
from datetime import date
from datetime import datetime
from decimal import Decimal
from zoneinfo import ZoneInfo
from unittest.mock import patch

import app.providers as providers

from app.providers import (
    TencentHistoryProvider,
    _tencent_csi300_rows,
    akshare_us_symbol,
    discover_fund_research_universe,
    fetch_benchmark_history,
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

    def stock_individual_info_em(self, symbol):
        assert symbol == "600519"
        return FakeFrame([
            {"item": "股票代码", "value": "600519"},
            {"item": "上市时间", "value": "20010827"},
        ])

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

    def stock_zh_index_daily_em(self, **kwargs):
        return FakeFrame([
            {"date": "2026-07-09", "close": "100.0"},
            {"date": "2026-07-10", "close": "110.0"},
        ])

    def spot_hist_sge(self, **kwargs):
        return FakeFrame([
            {"日期": "2026-07-09", "收盘价": "500.0"},
            {"日期": "2026-07-10", "收盘价": "510.0"},
        ])

    def index_global_hist_em(self, **kwargs):
        return FakeFrame([
            {"日期": "2026-07-09", "收盘": "20000.0"},
            {"日期": "2026-07-10", "收盘": "20200.0"},
        ])


class FakeFundSnapshotAkshare:
    def __init__(self, records):
        self.records = records
        self.snapshot_calls = 0
        self.name_calls = 0
        self.history_calls = 0

    def fund_open_fund_daily_em(self):
        self.snapshot_calls += 1
        return FakeFrame(self.records)

    def fund_name_em(self):
        self.name_calls += 1
        return FakeFrame([
            {"基金代码": "000001", "基金简称": "历史名称 A"},
            {"基金代码": "000002", "基金简称": "历史名称 B"},
        ])

    def fund_open_fund_info_em(self, **kwargs):
        self.history_calls += 1
        return FakeFrame([
            {"净值日期": "2026-07-17", "单位净值": "1.570", "日增长率": "1.00"},
            {"净值日期": "2026-07-18", "单位净值": "1.600", "日增长率": "1.91"},
        ])


class FakeMonotonicClock:
    def __init__(self, value=100.0):
        self.value = value

    def __call__(self):
        return self.value

    def advance(self, seconds):
        self.value += seconds


class ProviderNormalizationTest(unittest.TestCase):
    def tearDown(self):
        clear_cache = getattr(providers, "_clear_fund_snapshot_cache", None)
        if clear_cache is not None:
            clear_cache()

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

    def test_official_composite_benchmark_builds_versioned_synthetic_level(self):
        result = fetch_benchmark_history(
            "CSI300_95_CASH_5",
            date(2026, 7, 9),
            date(2026, 7, 10),
            ak_module=FakeAkshare(),
        )

        self.assertEqual("2026-07-09", result[0]["data_date"])
        self.assertEqual("100", result[0]["close"])
        self.assertEqual("109.5000", result[1]["close"])
        self.assertEqual("AKSHARE", result[0]["provider"])
        self.assertEqual("benchmark-adapter-v1", result[0]["adapter_version"])

    def test_csi300_benchmark_falls_back_to_baostock_when_akshare_is_unavailable(self):
        class FailingAkshare:
            def stock_zh_index_daily_em(self, **_kwargs):
                raise ConnectionError("eastmoney unavailable")

        class BaoResult:
            error_code = "0"
            error_msg = ""
            fields = ["date", "close"]

            def __init__(self):
                self.rows = iter([
                    ["2026-07-09", "100"],
                    ["2026-07-10", "110"],
                ])
                self.current = None

            def next(self):
                self.current = next(self.rows, None)
                return self.current is not None

            def get_row_data(self):
                return self.current

        class FakeBaoStock:
            def login(self):
                return type("Login", (), {"error_code": "0", "error_msg": ""})()

            def logout(self):
                pass

            def query_history_k_data_plus(self, code, fields, **kwargs):
                self.call = (code, fields, kwargs)
                return BaoResult()

        baostock = FakeBaoStock()
        result = fetch_benchmark_history(
            "CSI300_95_CASH_5",
            date(2026, 7, 9),
            date(2026, 7, 10),
            ak_module=FailingAkshare(),
            baostock_module=baostock,
        )

        self.assertEqual("sh.000300", baostock.call[0])
        self.assertEqual("109.5000", result[1]["close"])
        self.assertEqual("BAOSTOCK", result[0]["provider"])

    def test_tencent_can_supply_long_csi300_benchmark_history(self):
        payload = json.dumps({
            "data": {
                "sh000300": {
                    "day": [
                        ["2005-04-08", "1000", "1010", "1020", "990", "1"],
                        ["2005-04-11", "1010", "1020", "1030", "1000", "1"],
                    ]
                }
            }
        })

        rows = _tencent_csi300_rows(
            date(2005, 4, 8),
            date(2005, 4, 11),
            opener=lambda *_args, **_kwargs: FakeJsonResponse(payload),
        )

        self.assertEqual(
            [(date(2005, 4, 8), Decimal("1010")),
             (date(2005, 4, 11), Decimal("1020"))],
            rows,
        )

    def test_index_fund_research_universe_is_discovered_without_hardcoded_codes(self):
        class FundCatalogAkshare:
            @staticmethod
            def fund_open_fund_rank_em(symbol):
                self.assertEqual("指数型", symbol)
                return FakeFrame([
                    {"fund_code": "000001", "fund_name": "Alpha 沪深300增强A"},
                    {"fund_code": "000002", "fund_name": "Alpha 沪深300增强C"},
                    {"fund_code": "000003", "fund_name": "Beta 中证500增强A"},
                    {"fund_code": "000004", "fund_name": "Gamma 沪深300联接A"},
                ])

            @staticmethod
            def fund_open_fund_info_em(symbol, indicator):
                self.assertEqual("单位净值走势", indicator)
                base = 1.0 if symbol == "000001" else 2.0
                return FakeFrame([
                    {"date": "2026-07-08", "nav": base},
                    {"date": "2026-07-09", "nav": base + 0.01},
                    {"date": "2026-07-10", "nav": base + 0.02},
                ])

        result = discover_fund_research_universe(
            model_family="INDEX_FUND",
            benchmark_code="CSI300_95_CASH_5",
            target_code="010736",
            start_date=date(2026, 7, 8),
            end_date=date(2026, 7, 10),
            limit=5,
            minimum_records=2,
            selection_rule={
                "catalogSymbol": "指数型",
                "nameAliases": ["沪深300"],
                "excludedNamePatterns": ["C"],
            },
            ak_module=FundCatalogAkshare(),
        )

        self.assertEqual(["000001", "000004"], [item["code"] for item in result["members"]])
        self.assertEqual(3, len(result["members"][0]["records"]))
        self.assertEqual(64, len(result["datasetVersion"]))

    def test_benchmark_provider_rejects_unknown_profile_code(self):
        with self.assertRaisesRegex(ValueError, "unsupported benchmark code"):
            fetch_benchmark_history(
                "CASH_CNY",
                date(2026, 7, 9),
                date(2026, 7, 10),
                ak_module=FakeAkshare(),
            )

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
            inception_date="2001-08-27",
        )

        self.assertEqual("STOCK", result["productType"])
        self.assertEqual("600519", result["code"])
        self.assertEqual("贵州茅台", result["name"])
        self.assertEqual("1204.98", result["latestPrice"])
        self.assertEqual("2001-08-27", result["inceptionDate"])

    def test_resolve_a_share_uses_code_list_and_latest_daily_price(self):
        provider = FakeAkshare()
        result = resolve_product_metadata("STOCK", "600519", ak_module=provider)

        self.assertEqual("贵州茅台", result["name"])
        self.assertEqual("SSE", result["market"])
        self.assertEqual("1204.98", result["latestPrice"])
        self.assertEqual("14.98", result["changeAmount"])
        self.assertEqual("1.2588", result["changePercent"])
        self.assertEqual("2001-08-27", result["inceptionDate"])
        self.assertEqual("qfq", provider.last_hist_kwargs["adjust"])

    def test_resolve_domestic_fund_uses_fund_name_and_nav(self):
        result = resolve_product_metadata("MUTUAL_FUND", "000001", ak_module=FakeAkshare())

        self.assertEqual("华夏成长混合", result["name"])
        self.assertEqual("FUND_CN", result["market"])
        self.assertEqual("1.527", result["latestPrice"])
        self.assertEqual("0.027", result["changeAmount"])
        self.assertEqual("1.80", result["changePercent"])
        self.assertEqual("2026-07-09", result["inceptionDate"])

    def test_resolve_domestic_fund_prefers_latest_valued_dynamic_snapshot_column(self):
        provider = FakeFundSnapshotAkshare([{
            "基金代码": "000001",
            "基金简称": "快照名称 A",
            "2026-07-21-单位净值": "",
            "2026-07-18-单位净值": "1.600",
            "2026-07-17-单位净值": "1.570",
            "日增长值": "0.030",
            "日增长率": "1.91",
        }])

        result = resolve_product_metadata("MUTUAL_FUND", "000001", ak_module=provider)

        self.assertEqual(1, provider.snapshot_calls)
        self.assertEqual(0, provider.name_calls)
        self.assertEqual(0, provider.history_calls)
        self.assertEqual("快照名称 A", result["name"])
        self.assertEqual("2026-07-18", result["dataDate"])
        self.assertEqual("1.600", result["latestPrice"])
        self.assertEqual("1.570", result["previousClose"])
        self.assertEqual("0.030", result["changeAmount"])
        self.assertEqual("1.91", result["changePercent"])
        self.assertEqual([], result["warnings"])

    def test_fund_snapshot_is_reused_for_multiple_codes_within_configured_ttl(self):
        provider = FakeFundSnapshotAkshare([
            {"基金代码": "000001", "基金简称": "快照 A",
             "2026/07/18-单位净值": "1.600", "2026/07/17-单位净值": "1.570",
             "日增长值": "0.030", "日增长率": "1.91"},
            {"基金代码": "000002", "基金简称": "快照 B",
             "2026.07.18-单位净值": "2.100", "2026.07.17-单位净值": "2.000",
             "日增长值": "0.100", "日增长率": "5.00"},
        ])

        first = resolve_product_metadata("MUTUAL_FUND", "000001", ak_module=provider)
        second = resolve_product_metadata("MUTUAL_FUND", "000002", ak_module=provider)

        self.assertEqual(30000, providers._provider_integer("fund_snapshot_cache_ttl_ms"))
        self.assertEqual(1, provider.snapshot_calls)
        self.assertEqual("1.600", first["latestPrice"])
        self.assertEqual("2.100", second["latestPrice"])

    def test_concurrent_fund_resolves_coalesce_the_same_provider_snapshot_request(self):
        provider = FakeFundSnapshotAkshare([
            {"基金代码": "000001", "基金简称": "快照 A",
             "2026-07-18-单位净值": "1.600", "2026-07-17-单位净值": "1.570"},
            {"基金代码": "000002", "基金简称": "快照 B",
             "2026-07-18-单位净值": "2.100", "2026-07-17-单位净值": "2.000"},
        ])
        original_snapshot = provider.fund_open_fund_daily_em
        first_started = threading.Event()
        release_snapshot = threading.Event()
        waiter_entered = threading.Event()
        original_wait = providers._FUND_SNAPSHOT_CACHE_CONDITION.wait

        def blocked_snapshot():
            frame = original_snapshot()
            first_started.set()
            release_snapshot.wait(timeout=2)
            return frame

        def observed_wait(*args, **kwargs):
            waiter_entered.set()
            return original_wait(*args, **kwargs)

        provider.fund_open_fund_daily_em = blocked_snapshot
        with patch.object(providers._FUND_SNAPSHOT_CACHE_CONDITION, "wait", side_effect=observed_wait):
            with ThreadPoolExecutor(max_workers=2) as executor:
                first = executor.submit(resolve_product_metadata, "MUTUAL_FUND", "000001", provider)
                self.assertTrue(first_started.wait(timeout=1))
                second = executor.submit(resolve_product_metadata, "MUTUAL_FUND", "000002", provider)
                try:
                    self.assertTrue(waiter_entered.wait(timeout=1))
                finally:
                    release_snapshot.set()
                results = [first.result(timeout=2), second.result(timeout=2)]

        self.assertEqual(1, provider.snapshot_calls)
        self.assertEqual(["1.600", "2.100"], [item["latestPrice"] for item in results])

    def test_fund_snapshot_failure_falls_back_to_history_and_keeps_warning(self):
        provider = FakeFundSnapshotAkshare([])

        def fail_snapshot():
            provider.snapshot_calls += 1
            raise RuntimeError("snapshot offline")

        provider.fund_open_fund_daily_em = fail_snapshot

        result = resolve_product_metadata("MUTUAL_FUND", "000001", ak_module=provider)

        self.assertEqual("1.600", result["latestPrice"])
        self.assertEqual(1, provider.name_calls)
        self.assertEqual(1, provider.history_calls)
        self.assertTrue(any("快照获取失败" in item and "snapshot offline" in item
                            for item in result["warnings"]))

    def test_fund_code_missing_from_snapshot_falls_back_to_history_and_keeps_warning(self):
        provider = FakeFundSnapshotAkshare([{
            "基金代码": "000002", "基金简称": "快照 B",
            "2026-07-18-单位净值": "2.100", "2026-07-17-单位净值": "2.000",
        }])

        result = resolve_product_metadata("MUTUAL_FUND", "000001", ak_module=provider)

        self.assertEqual("历史名称 A", result["name"])
        self.assertEqual("1.600", result["latestPrice"])
        self.assertEqual(1, provider.name_calls)
        self.assertEqual(1, provider.history_calls)
        self.assertTrue(any("快照未找到基金代码 000001" in item for item in result["warnings"]))

    def test_fund_snapshot_cache_releases_failed_provider_for_garbage_collection(self):
        provider = FakeFundSnapshotAkshare([])

        def fail_snapshot():
            provider.snapshot_calls += 1
            raise RuntimeError("snapshot offline")

        provider.fund_open_fund_daily_em = fail_snapshot
        resolve_product_metadata("MUTUAL_FUND", "000001", ak_module=provider)
        provider_reference = weakref.ref(provider)

        del fail_snapshot
        del provider
        gc.collect()

        self.assertIsNone(provider_reference())

    def test_fund_snapshot_cache_refetches_after_ttl_with_controlled_clock(self):
        clock = FakeMonotonicClock()
        provider = FakeFundSnapshotAkshare([
            {"基金代码": "000001", "基金简称": "快照 A",
             "2026-07-18-单位净值": "1.600", "2026-07-17-单位净值": "1.570"},
        ])

        resolve_product_metadata("MUTUAL_FUND", "000001", ak_module=provider, cache_clock=clock)
        resolve_product_metadata("MUTUAL_FUND", "000001", ak_module=provider, cache_clock=clock)
        self.assertEqual(1, provider.snapshot_calls)

        clock.advance(30.001)
        resolve_product_metadata("MUTUAL_FUND", "000001", ak_module=provider, cache_clock=clock)

        self.assertEqual(2, provider.snapshot_calls)

    def test_failed_fund_snapshot_is_negatively_cached_until_ttl_expires(self):
        clock = FakeMonotonicClock()
        provider = FakeFundSnapshotAkshare([])

        def fail_snapshot():
            provider.snapshot_calls += 1
            raise RuntimeError("snapshot offline")

        provider.fund_open_fund_daily_em = fail_snapshot

        first = resolve_product_metadata("MUTUAL_FUND", "000001", ak_module=provider, cache_clock=clock)
        second = resolve_product_metadata("MUTUAL_FUND", "000001", ak_module=provider, cache_clock=clock)
        self.assertEqual(1, provider.snapshot_calls)
        self.assertEqual(first["warnings"], second["warnings"])
        self.assertTrue(any("RuntimeError: snapshot offline" in item for item in second["warnings"]))

        clock.advance(30.001)
        resolve_product_metadata("MUTUAL_FUND", "000001", ak_module=provider, cache_clock=clock)

        self.assertEqual(2, provider.snapshot_calls)

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
