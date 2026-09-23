from types import SimpleNamespace

import pandas as pd
import pytest

from app.market_catalog import catalog, current_members
from app.market_catalog import daily_history
from app.providers import ProviderUnavailable


def test_catalog_keeps_market_identity_and_skips_amex():
    provider = SimpleNamespace(stock_us_spot_em=lambda: pd.DataFrame({
        "代码": ["105.AAPL", "106.IBM", "107.SOME", "105.AAPL", "105.nan", "107.SPY"],
        "名称": ["Apple", "IBM", "Some", "Apple", "Invalid", "SPDR S&P 500 ETF"],
    }))
    assert [(item["productType"], item["market"], item["code"]) for item in catalog("US", provider)] == [
        ("STOCK", "NASDAQ", "AAPL"), ("STOCK", "NYSE", "IBM"), ("ETF", "AMEX", "SPY")]
    assert catalog("US", provider)[-1]["currency"] == "USD"


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


def test_fund_daily_update_uses_batch_nav_instead_of_full_history():
    from datetime import date
    day = date.today()
    provider = SimpleNamespace(fund_open_fund_daily_em=lambda: pd.DataFrame({
        "基金代码": ["000001", "000002"],
        f"{day.isoformat()}-单位净值": [1.25, 2.0],
    }))
    records = daily_history("000001", "FUND_CN", "MUTUAL_FUND", day, day, "NONE", provider)
    assert len(records) == 1
    assert records[0].close == 1.25


def test_index_catalog_uses_registry_without_inventing_exchange():
    rows = catalog("INDEX", SimpleNamespace())
    assert any(row["code"] == "CN_INDEX:000852" for row in rows)
    assert any(row["code"] == "GLOBAL_INDEX:SPX" and row["exchange"] is None for row in rows)
