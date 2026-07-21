import json
import math
import sys
import unittest
from dataclasses import FrozenInstanceError
from datetime import date, datetime, timezone
from decimal import Decimal
from unittest.mock import patch

from app.data_quality import AdjustType, ProductType
from app.providers import (
    AkshareProvider,
    BaostockProvider,
    NormalizedQuote,
    ProviderBatch,
    ProviderRegistry,
    ProviderUnavailable,
    TencentHistoryProvider,
    TushareProvider,
)


FETCHED_AT = datetime(2026, 7, 18, 8, 30, tzinfo=timezone.utc)


class FakeFrame:
    def __init__(self, records):
        self.records = records

    def to_dict(self, orient):
        assert orient == "records"
        return list(self.records)


class FakeJsonResponse:
    def __init__(self, payload):
        self.payload = payload

    def read(self):
        return self.payload.encode("utf-8")

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False


def quote(*, provider="TENCENT", code="000001", market="SZSE", close="10.20", **values):
    return NormalizedQuote(
        product_code=code,
        market=market,
        data_date=date(2026, 7, 17),
        open=values.get("open", Decimal("10.00")),
        high=values.get("high", Decimal("10.40")),
        low=values.get("low", Decimal("9.90")),
        close=values.get("close_value", Decimal(close)),
        volume=values.get("volume", Decimal("1200")),
        provider=provider,
        adapter_version=values.get("adapter_version", "1"),
        fetched_at=values.get("quote_fetched_at", datetime(2026, 7, 17, tzinfo=timezone.utc)),
    )


def batch(*, provider="TENCENT", adjust_type=AdjustType.QFQ, records=None,
          product_type=ProductType.STOCK, code="000001", market="SZSE"):
    return ProviderBatch(
        product_type=product_type,
        code=code,
        market=market,
        frequency="DAY",
        adjust_type=adjust_type,
        provider=provider,
        adapter_version="1",
        fetched_at=FETCHED_AT,
        records=tuple(records if records is not None else [quote(provider=provider, code=code, market=market)]),
        warnings=(),
    )


class ProviderBatchContractTest(unittest.TestCase):
    def test_stock_batch_maps_exact_snapshot_rows_without_mutating_quotes_or_fabricating_factor(self):
        source = quote()
        original = source
        value = batch(records=[source])

        rows = value.to_snapshot_rows()

        self.assertEqual(original, source)
        self.assertEqual(1, len(rows))
        self.assertEqual({
            "product_code": "000001", "product_type": "STOCK", "market": "SZSE",
            "frequency": "DAY", "adjust_type": "QFQ", "provider": "TENCENT",
            "adapter_version": "1", "data_date": date(2026, 7, 17),
            "observed_at": FETCHED_AT, "open": Decimal("10.00"),
            "high": Decimal("10.40"), "low": Decimal("9.90"),
            "close": Decimal("10.20"), "volume": Decimal("1200"), "nav": None,
            "trading_status": None, "adjustment_factor": None,
            "corporate_action_reference": None, "nav_type": None, "estimated": None,
        }, rows[0])
        with self.assertRaises(FrozenInstanceError):
            value.provider = "OTHER"

    def test_fund_batch_maps_unit_nav_and_rejects_adjustment(self):
        fund_quote = quote(provider="AKSHARE", code="000001", market="FUND_CN", close="1.527")
        value = batch(provider="AKSHARE", product_type=ProductType.MUTUAL_FUND,
                      market="FUND_CN", adjust_type=AdjustType.NONE, records=[fund_quote])

        row = value.to_snapshot_rows()[0]

        self.assertEqual(Decimal("1.527"), row["nav"])
        self.assertEqual("UNIT_NAV", row["nav_type"])
        self.assertFalse(row["estimated"])
        for field in ("open", "high", "low", "close", "volume"):
            self.assertIsNone(row[field])
        with self.assertRaisesRegex(ValueError, "NONE"):
            batch(provider="AKSHARE", product_type=ProductType.MUTUAL_FUND,
                  market="FUND_CN", adjust_type=AdjustType.QFQ, records=[fund_quote])

    def test_batch_rejects_invalid_identity_time_records_and_numeric_values(self):
        invalid_cases = [
            ({"provider": " "}, "provider"),
            ({"fetched_at": datetime(2026, 7, 18, 8, 30)}, "timezone"),
            ({"records": ()}, "non-empty"),
            ({"product_type": "ETF"}, "product_type"),
            ({"records": (quote(provider="OTHER"),)}, "provider"),
            ({"records": (quote(code="600000"),)}, "code"),
            ({"records": (quote(market="SSE"),)}, "market"),
            ({"records": (quote(close_value=True),)}, "close"),
            ({"records": (quote(close_value=Decimal("NaN")),)}, "close"),
        ]
        defaults = {
            "product_type": ProductType.STOCK, "code": "000001", "market": "SZSE",
            "frequency": "DAY", "adjust_type": AdjustType.QFQ, "provider": "TENCENT",
            "adapter_version": "1", "fetched_at": FETCHED_AT,
            "records": (quote(),), "warnings": (),
        }
        for changes, message in invalid_cases:
            with self.subTest(changes=changes), self.assertRaisesRegex(ValueError, message):
                ProviderBatch(**(defaults | changes))


class ProviderAdjustmentContractTest(unittest.TestCase):
    def test_tencent_query_and_payload_key_match_each_declared_adjustment(self):
        for adjust_type, token, payload_key in (
            (AdjustType.QFQ, "qfq", "qfqday"),
            (AdjustType.HFQ, "hfq", "hfqday"),
            (AdjustType.NONE, "none", "day"),
        ):
            calls = []
            payload = json.dumps({"data": {"sz000001": {payload_key: [
                ["2026-07-17", "10", "10.2", "10.4", "9.9", "12"]
            ]}}})

            def opener(url, **kwargs):
                calls.append((url, kwargs))
                return FakeJsonResponse(payload)

            value = TencentHistoryProvider(opener=opener).daily_quality_batch(
                "000001", "SZSE", "STOCK", date(2026, 7, 1), date(2026, 7, 18),
                adjust_type, FETCHED_AT,
            )

            self.assertEqual(adjust_type, value.adjust_type)
            self.assertIn(f",{token}", calls[0][0])

    def test_tencent_history_is_segmented_and_merged_for_long_ranges(self):
        calls = []

        def opener(url, **kwargs):
            calls.append((url, kwargs))
            day = "2024-01-02" if len(calls) == 1 else "2026-07-17"
            payload = json.dumps({"data": {"sz000001": {"qfqday": [
                [day, "10", "10.2", "10.4", "9.9", "12"]
            ]}}})
            return FakeJsonResponse(payload)

        settings = {
            "tencent_history_chunk_calendar_days": 365,
            "tencent_history_max_records": 1000,
            "history_http_timeout_seconds": 8,
            "tencent_volume_lot_size": 100,
        }
        with patch("app.providers._provider_integer", side_effect=settings.__getitem__):
            value = TencentHistoryProvider(opener=opener).daily_quality_batch(
                "000001", "SZSE", "STOCK", date(2024, 1, 1), date(2026, 7, 18),
                AdjustType.QFQ, FETCHED_AT,
            )

        self.assertGreater(len(calls), 1)
        self.assertEqual(
            [date(2024, 1, 2), date(2026, 7, 17)],
            [record.data_date for record in value.records],
        )

    def test_akshare_stock_and_fund_arguments_match_actual_adjustment(self):
        class FakeAkshare:
            def __init__(self):
                self.calls = []

            def stock_zh_a_hist(self, **kwargs):
                self.calls.append(("stock", kwargs))
                return FakeFrame([{"日期": "2026-07-17", "收盘": "10.2"}])

            def fund_open_fund_info_em(self, **kwargs):
                self.calls.append(("fund", kwargs))
                return FakeFrame([{"净值日期": "2026-07-17", "单位净值": "1.527"}])

        fake = FakeAkshare()
        with patch.dict(sys.modules, {"akshare": fake}):
            for adjust_type, argument in ((AdjustType.QFQ, "qfq"), (AdjustType.HFQ, "hfq"), (AdjustType.NONE, "")):
                value = AkshareProvider().daily_quality_batch(
                    "000001", "SZSE", "STOCK", date(2026, 7, 1), date(2026, 7, 18),
                    adjust_type, FETCHED_AT,
                )
                self.assertEqual(adjust_type, value.adjust_type)
                self.assertEqual(argument, fake.calls[-1][1]["adjust"])
            fund = AkshareProvider().daily_quality_batch(
                "000001", "FUND_CN", "MUTUAL_FUND", date(2026, 7, 1), date(2026, 7, 18),
                AdjustType.NONE, FETCHED_AT,
            )
        self.assertEqual(AdjustType.NONE, fund.adjust_type)
        self.assertNotIn("adjust", fake.calls[-1][1])

    def test_baostock_adjustflag_matches_declared_actual_adjustment(self):
        class Result:
            error_code = "0"
            fields = ["date", "open", "high", "low", "close", "volume"]

            def __init__(self):
                self.pending = True

            def next(self):
                pending, self.pending = self.pending, False
                return pending

            def get_row_data(self):
                return ["2026-07-17", "10", "10.4", "9.9", "10.2", "1200"]

        class FakeBaoStock:
            def __init__(self):
                self.flags = []

            def login(self):
                return type("Login", (), {"error_code": "0", "error_msg": ""})()

            def logout(self):
                pass

            def query_history_k_data_plus(self, *_args, **kwargs):
                self.flags.append(kwargs["adjustflag"])
                return Result()

        fake = FakeBaoStock()
        with patch.dict(sys.modules, {"baostock": fake}):
            for adjust_type, flag in ((AdjustType.HFQ, "1"), (AdjustType.QFQ, "2"), (AdjustType.NONE, "3")):
                value = BaostockProvider().daily_quality_batch(
                    "000001", "SZSE", "STOCK", date(2026, 7, 1), date(2026, 7, 18),
                    adjust_type, FETCHED_AT,
                )
                self.assertEqual(adjust_type, value.adjust_type)
                self.assertEqual(flag, fake.flags[-1])

    def test_tushare_daily_is_raw_none_and_never_fabricates_adjustment_factor(self):
        class Pro:
            def __init__(self):
                self.kwargs = None

            def daily(self, **kwargs):
                self.kwargs = kwargs
                return FakeFrame([{"trade_date": "20260717", "open": "10", "high": "10.4",
                                   "low": "9.9", "close": "10.2", "vol": "1200"}])

        pro = Pro()
        fake = type("FakeTushare", (), {"pro_api": staticmethod(lambda _token: pro)})()
        with patch.dict(sys.modules, {"tushare": fake}), patch.dict("os.environ", {"TUSHARE_TOKEN": "token"}):
            value = TushareProvider().daily_quality_batch(
                "000001", "SZSE", "STOCK", date(2026, 7, 1), date(2026, 7, 18),
                AdjustType.NONE, FETCHED_AT,
            )
            with self.assertRaisesRegex(ProviderUnavailable, "QFQ"):
                TushareProvider().daily_quality_batch(
                    "000001", "SZSE", "STOCK", date(2026, 7, 1), date(2026, 7, 18),
                    AdjustType.QFQ, FETCHED_AT,
                )

        self.assertEqual(AdjustType.NONE, value.adjust_type)
        self.assertNotIn("adjust", pro.kwargs)
        self.assertIsNone(value.to_snapshot_rows()[0]["adjustment_factor"])


class FakeProvider:
    def __init__(self, name, outcome):
        self.name = name
        self.outcome = outcome
        self.calls = []

    def supports(self, _market, _product_type):
        return True

    def daily_quality_batch(self, code, market, product_type, start_date, end_date,
                            adjust_type, fetched_at):
        self.calls.append((code, market, product_type, start_date, end_date, adjust_type, fetched_at))
        if isinstance(self.outcome, Exception):
            raise self.outcome
        if callable(self.outcome):
            return self.outcome()
        return self.outcome


class ProviderRegistryBatchTest(unittest.TestCase):
    def test_registry_returns_deterministic_distinct_primary_secondary_and_accumulated_warnings(self):
        empty = FakeProvider("EMPTY", None)
        second = FakeProvider("SECOND", lambda: batch(provider="SECOND", records=[quote(provider="SECOND")]))
        primary = FakeProvider("PRIMARY", lambda: batch(provider="PRIMARY", records=[quote(provider="PRIMARY")]))
        duplicate = FakeProvider("PRIMARY", lambda: batch(provider="PRIMARY", records=[quote(provider="PRIMARY")]))
        registry = ProviderRegistry([empty, primary, duplicate, second])

        batches, warnings = registry.daily_quality_batches(
            code="000001", market="SZSE", product_type=ProductType.STOCK,
            start_date=date(2026, 7, 1), end_date=date(2026, 7, 18),
            adjust_type=AdjustType.QFQ, clock=lambda: FETCHED_AT,
        )

        self.assertEqual(["PRIMARY", "SECOND"], [item.provider for item in batches])
        self.assertTrue(any("EMPTY" in warning and "no records" in warning for warning in warnings))
        self.assertTrue(any("duplicate" in warning.lower() for warning in warnings))
        self.assertEqual({FETCHED_AT}, {item.fetched_at for item in batches})

    def test_registry_warns_and_falls_back_on_outage_or_actual_adjustment_mismatch(self):
        outage = FakeProvider("OUTAGE", RuntimeError("offline"))
        mismatch = FakeProvider("RAW", lambda: batch(provider="RAW", adjust_type=AdjustType.NONE,
                                                       records=[quote(provider="RAW")]))
        valid = FakeProvider("VALID", lambda: batch(provider="VALID", records=[quote(provider="VALID")]))

        batches, warnings = ProviderRegistry([outage, mismatch, valid]).daily_quality_batches(
            code="000001", market="SZSE", product_type=ProductType.STOCK,
            start_date=date(2026, 7, 1), end_date=date(2026, 7, 18),
            adjust_type=AdjustType.QFQ, clock=lambda: FETCHED_AT,
        )

        self.assertEqual(["VALID"], [item.provider for item in batches])
        self.assertTrue(any("offline" in warning for warning in warnings))
        self.assertTrue(any("adjust" in warning.lower() for warning in warnings))

    def test_registry_rejects_reversed_range_bad_clock_and_requires_a_provider(self):
        registry = ProviderRegistry([])
        call = dict(code="000001", market="SZSE", product_type=ProductType.STOCK,
                    start_date=date(2026, 7, 18), end_date=date(2026, 7, 1),
                    adjust_type=AdjustType.QFQ, clock=lambda: FETCHED_AT)
        with self.assertRaisesRegex(ValueError, "start"):
            registry.daily_quality_batches(**call)
        with self.assertRaisesRegex(ValueError, "timezone"):
            ProviderRegistry([FakeProvider("EMPTY", None)]).daily_quality_batches(
                **(call | {"start_date": date(2026, 7, 1), "clock": lambda: datetime(2026, 7, 18)})
            )
        with self.assertRaises(ProviderUnavailable):
            ProviderRegistry([FakeProvider("EMPTY", None)]).daily_quality_batches(
                **(call | {"start_date": date(2026, 7, 1)})
            )


if __name__ == "__main__":
    unittest.main()
