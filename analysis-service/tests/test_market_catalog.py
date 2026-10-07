import json
from types import SimpleNamespace
from datetime import date

import pandas as pd
import pytest

from app.market_catalog import catalog, current_members
from app.market_catalog import daily_history
import app.market_catalog as market_catalog_module
from app.providers import ProviderUnavailable


NASDAQ_LISTED = (
    "Symbol|Security Name|Market Category|Test Issue|Financial Status|Round Lot Size|ETF|NextShares\n"
    "AAPL|Apple Inc. - Common Stock|Q|N|N|100|N|N\n"
    "NEWETF|A Newly Listed ETF|G|N|N|100|Y|N\n"
    "SOMEW|Some Acquisition - Warrants|G|N|N|100|N|N\n"
    "UNKNOWN|Security without a type|G|N|N|100|N|N\n"
    "TEST|Test Common Stock|Q|Y|N|100|N|N\n"
    "File Creation Time: 1002202617:00|||||||\n"
)
OTHER_LISTED = (
    "ACT Symbol|Security Name|Exchange|CQS Symbol|ETF|Round Lot Size|Test Issue|NASDAQ Symbol\n"
    "IBM|International Business Machines Common Stock|N|IBM|N|100|N|IBM\n"
    "SPY|SPDR S&P 500 ETF Trust|P|SPY|Y|100|N|SPY\n"
    "BATSF|A Cboe Listed ETF|Z|BATSF|Y|100|N|BATSF\n"
    "File Creation Time: 1002202617:00|||||||\n"
)


def _patch_us_directory(monkeypatch, nasdaq=NASDAQ_LISTED, other=OTHER_LISTED):
    import app.market_catalog as module
    monkeypatch.setattr(module, "_directory_text", lambda source: (
        nasdaq if source["name"] == "NASDAQ_LISTED" else other))


def test_us_catalog_uses_dynamic_etf_classification_and_real_venue(monkeypatch, caplog):
    _patch_us_directory(monkeypatch)
    provider = SimpleNamespace(stock_us_spot_em=lambda: pd.DataFrame({
        "代码": ["105.AAPL", "106.IBM", "107.SPY"],
        "名称": ["苹果", "IBM", "SPDR S&P 500 ETF"],
    }))
    rows = catalog("US", provider)
    actual = {(item["productType"], item["market"], item["exchange"], item["code"]) for item in rows}
    assert ("STOCK", "NASDAQ", "NASDAQ", "AAPL") in actual
    assert ("STOCK", "NYSE", "NYSE", "IBM") in actual
    assert ("ETF", "NASDAQ", "NASDAQ", "NEWETF") in actual
    assert ("ETF", "NYSE", "NYSE_ARCA", "SPY") in actual
    assert not {"SOMEW", "TEST", "BATSF"} & {item["code"] for item in rows}
    assert ("STOCK", "NASDAQ", "NASDAQ", "UNKNOWN") in actual
    assert next(item for item in rows if item["code"] == "AAPL")["name"] == "苹果"
    assert all(item["currency"] == "USD" for item in rows)
    assert rows.diagnostics["excludedVenues"] == {"CBOE_BZX": 1}
    assert rows.diagnostics["excludedSecurityTypes"] == {"WARRANT": 1}


def test_us_catalog_falls_back_without_slow_sina_snapshot(monkeypatch):
    _patch_us_directory(monkeypatch)
    def failed():
        raise ConnectionError("provider disconnected")
    provider = SimpleNamespace(stock_us_spot_em=failed,
                               get_us_stock_name=lambda: pytest.fail("unbounded Sina directory"))
    assert any(item["code"] == "NEWETF" for item in catalog("US", provider))


def test_us_name_search_uses_source_names_and_normalizes_tickers(monkeypatch):
    payload = 'var us_suggest="英伟达,41,nvda,nvda,英伟达,,英伟达,99,1,ESG,,;测试基金,41,abcd,ABCD Trust,测试基金,,测试基金,99,1,,,";'
    requests = []
    def get(url, **kwargs):
        requests.append((url, kwargs))
        return SimpleNamespace(content=payload.encode('gbk'), raise_for_status=lambda: None)
    monkeypatch.setattr('requests.get', get)
    search = getattr(market_catalog_module, 'search_us_names', None)
    assert callable(search), '应提供有界的美股名称查询，不能硬编码公司名单'
    rows = search('英伟达')
    assert rows[0]['code'] == 'NVDA'
    assert '英伟达' in rows[0]['aliases']
    assert rows[1]['code'] == 'ABCD'
    assert requests[0][1]['timeout'] > 0


def test_us_name_search_rejects_malformed_response(monkeypatch):
    monkeypatch.setattr('requests.get', lambda *args, **kwargs: SimpleNamespace(
        content=b'<html>blocked</html>', raise_for_status=lambda: None))
    search = getattr(market_catalog_module, 'search_us_names', None)
    assert callable(search)
    with pytest.raises(ProviderUnavailable):
        search('英伟达')


def test_official_preferred_suffix_does_not_abort_common_stock_directory(monkeypatch):
    other = OTHER_LISTED.replace("File Creation Time:",
        "BAC$E|Bank of America Depositary Shares Series E|N|BACpE|N|100|N|BAC-E\nFile Creation Time:")
    _patch_us_directory(monkeypatch, other=other)
    rows = catalog("US", SimpleNamespace())
    assert {"IBM", "SPY"}.issubset({row["code"] for row in rows})
    assert rows.diagnostics["excludedSecurityTypes"]["PREFERRED"] == 1


def test_a_share_history_uses_tencent_with_explicit_price_basis():
    calls = []
    def history(**kwargs):
        calls.append(kwargs)
        return pd.DataFrame({"date": ["2024-01-02"], "open": [10], "high": [11],
                             "low": [9], "close": [10], "volume": [1000]})
    for adjustment, expected in [("NONE", ""), ("QFQ", "qfq")]:
        rows = daily_history("600000", "SSE", "STOCK", date(2024, 1, 2), date(2024, 1, 3),
                             adjustment, SimpleNamespace(stock_zh_a_hist_tx=history))
        assert rows[0].provider == "AKSHARE_TENCENT"
        assert calls[-1]["symbol"] == "sh600000"
        assert calls[-1]["adjust"] == expected


@pytest.mark.parametrize("product_type,code", [("STOCK", "600000"), ("ETF", "510300")])
@pytest.mark.parametrize("adjustment", ["NONE", "QFQ"])
def test_tencent_adapter_does_not_fetch_years_after_the_requested_window(monkeypatch, product_type, code, adjustment):
    ak = pytest.importorskip("akshare")
    native = ak.stock_zh_a_hist_tx
    requested_years = []
    def response(_url, params, **_options):
        year = int(params["param"].split(",")[2][:4])
        requested_years.append(year)
        payload = {"data": {"sh" + code: {"day": [[f"{year}-01-02", "10", "10", "11", "9", "100"]]}}}
        return SimpleNamespace(text="var =" + json.dumps(payload))
    monkeypatch.setitem(native.__globals__, "get_tx_start_year", lambda **_kwargs: "2020-01-01")
    monkeypatch.setitem(native.__globals__, "get_tqdm", lambda: lambda values, **_kwargs: values)
    monkeypatch.setattr(native.__globals__["requests"], "get", response)
    records = daily_history(code, "SSE", product_type, date(2024, 1, 1), date(2024, 12, 31),
                            adjustment, SimpleNamespace(stock_zh_a_hist_tx=native))
    assert requested_years == [2024]
    assert [record.data_date for record in records] == [date(2024, 1, 2)]


def test_a_share_sina_fallback_preserves_requested_adjustment():
    captured = {}
    def unavailable(**kwargs):
        raise TimeoutError("Tencent unavailable")
    def sina(**kwargs):
        captured.update(kwargs)
        return pd.DataFrame({"date": ["2024-01-02"], "close": [10]})
    records = daily_history("600000", "SSE", "STOCK", date(2024,1,2), date(2024,1,2), "QFQ",
        SimpleNamespace(stock_zh_a_hist_tx=unavailable, stock_zh_a_daily=sina))
    assert captured["symbol"] == "sh600000" and captured["adjust"] == "qfq"
    assert records[0].provider == "AKSHARE_SINA"


def test_us_unknown_venue_is_excluded_without_blocking_supported_markets(monkeypatch):
    other = OTHER_LISTED.replace("|P|SPY|", "|UNKNOWN|SPY|")
    _patch_us_directory(monkeypatch, other=other)
    rows = catalog("US", SimpleNamespace())
    assert "IBM" in {row["code"] for row in rows}
    assert rows.diagnostics["excludedVenues"]["UNMAPPED:UNKNOWN"] == 1


def test_us_new_exchange_does_not_abort_other_listed_directory(monkeypatch):
    other = OTHER_LISTED.replace("|P|SPY|", "|F|SPY|")
    _patch_us_directory(monkeypatch, other=other)
    rows = catalog("US", SimpleNamespace())
    assert "IBM" in {row["code"] for row in rows}
    assert rows.diagnostics["excludedVenues"]["TXSE"] == 1


def test_valid_nan_nat_symbols_are_not_treated_as_missing_values(monkeypatch):
    other = OTHER_LISTED.replace("File Creation Time:",
        "NAN|Nuveen New York Quality Municipal Income Fund|N|NAN|N|100|N|NAN\n"
        "NAT|Nordic American Tankers Common Stock|N|NAT|N|100|N|NAT\nFile Creation Time:")
    _patch_us_directory(monkeypatch, other=other)
    rows = catalog("US", SimpleNamespace())
    assert {"NAN", "NAT"}.issubset({row["code"] for row in rows})


def test_us_truncated_directory_does_not_become_success(monkeypatch):
    _patch_us_directory(monkeypatch, other=OTHER_LISTED.split("File Creation Time:")[0])
    with pytest.raises(ProviderUnavailable, match="creation time"):
        catalog("US", SimpleNamespace())


def test_us_unknown_etf_flag_never_defaults_to_stock(monkeypatch):
    _patch_us_directory(monkeypatch, nasdaq=NASDAQ_LISTED.replace("|100|N|N", "|100||N", 1))
    with pytest.raises(ProviderUnavailable, match="unknown ETF classification"):
        catalog("US", SimpleNamespace())


def _cn_provider():
    return SimpleNamespace(
        stock_info_sh_name_code=lambda symbol: pd.DataFrame({
            "证券代码": ["600000" if symbol == "主板A股" else "688001"],
            "证券简称": ["浦发银行" if symbol == "主板A股" else "华兴源创"],
            "上市日期": ["1999-11-10" if symbol == "主板A股" else "2019-07-22"],
        }),
        stock_info_sz_name_code=lambda symbol: pd.DataFrame({
            "A股代码": [1], "A股简称": ["平安银行"], "A股上市日期": ["1991-04-03"]}),
        stock_info_bj_name_code=lambda: pd.DataFrame({
            "证券代码": ["920001"], "证券简称": ["北交所证券"], "上市日期": ["2024-01-02"]}),
    )


def test_cn_a_requires_every_partition_and_preserves_listing_dates():
    rows = catalog("CN_A", _cn_provider())
    assert {(item["market"], item["code"]) for item in rows} == {
        ("SSE", "600000"), ("SSE", "688001"), ("SZSE", "000001"), ("BSE", "920001")}
    assert next(item for item in rows if item["code"] == "688001")["listingDate"] == "2019-07-22"


def test_cn_a_does_not_return_partial_snapshot():
    provider = _cn_provider()
    provider.stock_info_bj_name_code = lambda: pd.DataFrame()
    with pytest.raises(ProviderUnavailable, match="CN_A.*BSE"):
        catalog("CN_A", provider)


def test_cn_etf_falls_back_and_normalizes_explicit_sina_prefix():
    def failed():
        raise ConnectionError("provider disconnected")
    provider = SimpleNamespace(
        fund_etf_spot_em=failed,
        fund_etf_spot_ths=lambda: pd.DataFrame(),
        fund_etf_category_sina=lambda symbol: pd.DataFrame({
            "代码": ["sh510300", "sz159919", "SH510300"], "名称": ["沪深300", "沪深300", "沪深300"]}),
    )
    rows = catalog("CN_ETF", provider)
    assert {(item["market"], item["code"]) for item in rows} == {
        ("SSE", "510300"), ("SZSE", "159919")}
    assert all(item["source"] == "AKSHARE_ETF_SINA" for item in rows)


def test_cn_etf_uses_supported_ths_source_before_sina():
    provider = SimpleNamespace(
        fund_etf_spot_em=lambda: pd.DataFrame(),
        fund_etf_spot_ths=lambda: pd.DataFrame({"基金代码": ["159919"], "基金名称": ["沪深300ETF"]}),
        fund_etf_category_sina=lambda symbol: pytest.fail("THS already succeeded"),
    )
    assert catalog("CN_ETF", provider)[0]["source"] == "AKSHARE_ETF_THS"


def test_fund_cn_uses_open_fund_directory_and_keeps_etf_feeders_as_funds():
    provider = SimpleNamespace(fund_open_fund_daily_em=lambda: pd.DataFrame({
        "基金代码": ["000001", "010736"],
        "基金简称": ["华夏成长", "指数ETF联接A"],
        "申购状态": ["开放申购", "暂停申购"], "赎回状态": ["开放赎回", "开放赎回"],
    }))
    rows = catalog("FUND_CN", provider)
    assert {(item["productType"], item["market"], item["code"]) for item in rows} == {
        ("MUTUAL_FUND", "FUND_CN", "000001"), ("MUTUAL_FUND", "FUND_CN", "010736")}
    assert all(item["exchange"] is None for item in rows)


def test_all_etf_sources_failing_keeps_source_diagnostics():
    with pytest.raises(ProviderUnavailable, match="CN_ETF.*AKSHARE_ETF_SPOT.*AKSHARE_ETF_THS.*AKSHARE_ETF_SINA"):
        catalog("CN_ETF", SimpleNamespace())


def test_native_akshare_timeout_is_bounded_without_a_worker(monkeypatch):
    import app.provider_calls as calls
    captured = {}
    def adapter(symbol, timeout=None):
        captured.update(symbol=symbol, timeout=timeout)
        return "result"
    monkeypatch.setattr(calls.multiprocessing, "get_context", lambda *args: pytest.fail("native timeout needs no process"))
    result = calls.call_akshare("stock_zh_a_hist", {"symbol": "600000", "timeout": 999},
                               timeout_seconds=30, request_timeout_seconds=10,
                               ak_module=SimpleNamespace(stock_zh_a_hist=adapter))
    assert result == "result"
    assert captured == {"symbol": "600000", "timeout": 10}


def test_current_members_only_returns_provider_snapshot():
    provider = SimpleNamespace(index_stock_cons_csindex=lambda symbol: pd.DataFrame({
        "成分券代码": ["000001", "600000", "000001", None]}))
    assert current_members("CSI300", provider) == ["000001", "600000"]
    with pytest.raises(ProviderUnavailable):
        current_members("SP500", provider)


def test_market_daily_history_requests_raw_prices_without_changing_legacy_provider():
    captured = {}
    def stock_history(**kwargs):
        captured.update(kwargs)
        return pd.DataFrame({"日期": ["2024-01-02"], "开盘": [10], "最高": [12],
                             "最低": [9], "收盘": [11], "成交量": [100]})
    provider = SimpleNamespace(stock_zh_a_hist=stock_history)
    from datetime import date
    result = daily_history("600001", "SSE", "STOCK", date(2024, 1, 2),
                           date(2024, 1, 2), "NONE", provider)
    assert captured["adjust"] == ""
    assert result[0].close == 11


def test_us_etf_uses_us_daily_history_without_adjustment():
    captured = {}
    def history(**kwargs):
        captured.update(kwargs)
        return pd.DataFrame({"日期": ["2024-01-02"], "开盘": [100], "最高": [101],
                             "最低": [99], "收盘": [100], "成交量": [1000]})
    from datetime import date
    result = daily_history("SPY", "AMEX", "ETF", date(2024, 1, 2),
                           date(2024, 1, 2), "NONE", SimpleNamespace(stock_us_hist=history))
    assert captured["symbol"] == "107.SPY"
    assert captured["adjust"] == ""
    assert result[0].close == 100


def test_etf_raw_history_falls_back_to_sina_without_adjustment():
    from datetime import date
    captured = {}
    def failed(**kwargs):
        raise ConnectionError("EM disconnected")
    def sina(**kwargs):
        captured.update(kwargs)
        return pd.DataFrame({"date": ["2024-01-01", "2024-01-02"], "close": [99, 100]})
    rows = daily_history("159919", "SZSE", "ETF", date(2024, 1, 2), date(2024, 1, 2),
                         "NONE", SimpleNamespace(fund_etf_hist_em=failed, fund_etf_hist_sina=sina))
    assert captured == {"symbol": "sz159919"}
    assert len(rows) == 1
    assert rows[0].provider == "AKSHARE_ETF_SINA"


def test_etf_adjusted_history_never_uses_raw_sina_fallback():
    from datetime import date
    def failed(**kwargs):
        raise ConnectionError("EM disconnected")
    provider = SimpleNamespace(fund_etf_hist_em=failed,
                               fund_etf_hist_sina=lambda **kwargs: pytest.fail("raw is not QFQ"))
    with pytest.raises(ProviderUnavailable, match="QFQ"):
        daily_history("510300", "SSE", "ETF", date(2024, 1, 2), date(2024, 1, 2), "QFQ", provider)


def test_us_raw_history_uses_sina_fallback_with_exact_window():
    from datetime import date
    captured = {}
    def failed(**kwargs):
        raise ConnectionError("EM disconnected")
    def sina(**kwargs):
        captured.update(kwargs)
        return pd.DataFrame({"date": ["2024-01-02", "2024-01-03"], "close": [100, 101]})
    rows = daily_history("AAPL", "NASDAQ", "STOCK", date(2024, 1, 2), date(2024, 1, 2),
                         "NONE", SimpleNamespace(stock_us_hist=failed, stock_us_daily=sina))
    assert captured == {"symbol": "AAPL", "adjust": ""}
    assert len(rows) == 1
    assert rows[0].provider == "AKSHARE_US_SINA"


def test_cn_index_raw_history_uses_existing_sina_symbol_resolution():
    from datetime import date
    captured = {}
    def failed(**kwargs):
        raise ConnectionError("EM disconnected")
    def sina(**kwargs):
        captured.update(kwargs)
        return pd.DataFrame({"date": ["2024-01-02"], "close": [3500]})
    rows = daily_history("CN_INDEX:000300", "CN_INDEX", "INDEX", date(2024, 1, 2),
                         date(2024, 1, 2), "NONE",
                         SimpleNamespace(index_zh_a_hist=failed, stock_zh_index_daily=sina))
    assert captured["symbol"] == "sh000300"
    assert rows[0].provider == "AKSHARE_INDEX_SINA"


def test_unknown_us_index_history_is_explicitly_unavailable():
    from datetime import date
    with pytest.raises(ProviderUnavailable, match="unsupported history.*US_INDEX"):
        daily_history("GLOBAL_INDEX:UNKNOWN", "US_INDEX", "INDEX", date(2024, 1, 2),
                      date(2024, 1, 2), "NONE", SimpleNamespace())


@pytest.mark.parametrize("code,symbol", [("GLOBAL_INDEX:SPX", ".INX"), ("GLOBAL_INDEX:NDX", ".NDX")])
def test_registered_us_index_uses_its_existing_provider_symbol_and_exact_raw_window(code, symbol):
    calls = []
    def history(**kwargs):
        calls.append(kwargs)
        return pd.DataFrame({"date": ["2024-01-01", "2024-01-02", "2024-01-03"],
                             "open": [10, 11, 12], "high": [10, 11, 12], "low": [10, 11, 12],
                             "close": [10, 11, 12], "volume": [100, 100, 100]})
    rows = daily_history(code, "US_INDEX", "INDEX", date(2024, 1, 2), date(2024, 1, 2),
                         "NONE", SimpleNamespace(index_us_stock_sina=history))
    assert calls == [{"symbol": symbol}]
    assert [row.data_date for row in rows] == [date(2024, 1, 2)]
    assert rows[0].provider == "SINA_US_INDEX_HISTORY"


def test_fund_incremental_update_compounds_full_history_before_filtering():
    from datetime import date, timedelta
    from decimal import Decimal
    day = date.today()
    provider = SimpleNamespace(
        fund_open_fund_info_em=lambda **kwargs: pd.DataFrame({
            "净值日期": [(day - timedelta(days=1)).isoformat(), day.isoformat()],
            "单位净值": [1.25, 1.2625], "日增长率": [0, 1.0],
        }),
        fund_open_fund_daily_em=lambda: pytest.fail("NAV snapshot cannot establish total return"),
    )
    records = daily_history("000001", "FUND_CN", "MUTUAL_FUND", day, day, "NONE", provider)
    assert len(records) == 1
    assert records[0].close == Decimal("1.2625")
    assert records[0].total_return_index == Decimal("1.01")


def test_history_empty_response_does_not_mask_fallback_timeout():
    from datetime import date
    def failed(**kwargs):
        raise TimeoutError("source timeout")
    provider = SimpleNamespace(fund_etf_hist_em=lambda **kwargs: pd.DataFrame(),
                               fund_etf_hist_sina=failed)
    with pytest.raises(ProviderUnavailable, match="source timeout"):
        daily_history("510300", "SSE", "ETF", date(2024, 1, 2), date(2024, 1, 2), "NONE", provider)


def test_history_successful_empty_sources_preserve_no_published_records():
    from datetime import date
    provider = SimpleNamespace(fund_etf_hist_em=lambda **kwargs: pd.DataFrame(),
                               stock_zh_a_hist_tx=lambda **kwargs: pd.DataFrame(),
                               fund_etf_hist_sina=lambda **kwargs: pd.DataFrame())
    assert daily_history("510300", "SSE", "ETF", date(2024, 1, 2), date(2024, 1, 2), "NONE", provider) == []


def test_etf_tencent_keeps_adjusted_basis_without_eastmoney_dependency():
    from datetime import date
    calls = []
    def source(**kwargs):
        calls.append(kwargs)
        return pd.DataFrame({"date": ["2024-01-02"], "close": [3.5]})
    records = daily_history("510300", "SSE", "ETF", date(2024, 1, 2), date(2024, 1, 2),
                            "QFQ", SimpleNamespace(stock_zh_a_hist_tx=source))
    assert calls == [{"symbol": "sh510300", "start_date": "2024-01-02", "end_date": "2024-01-02", "adjust": "qfq"}]
    assert records[0].provider == "AKSHARE_TENCENT"


def test_index_catalog_uses_registry_without_inventing_exchange():
    rows = catalog("INDEX", SimpleNamespace())
    assert any(row["code"] == "CN_INDEX:000852" for row in rows)
    assert any(row["code"] == "GLOBAL_INDEX:SPX" and row["exchange"] is None for row in rows)
