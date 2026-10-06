from datetime import date, datetime, timezone
from types import SimpleNamespace

import pandas as pd
import pytest

from app import research_data as research
from app.providers import ProviderUnavailable


CLOCK = lambda: datetime(2026, 10, 4, 5, 0, tzinfo=timezone.utc)


def test_financial_reports_keep_all_periods_and_unknown_publication_time():
    frame = pd.DataFrame({"报告日": ["2023-12-31", "2024-12-31"], "营业收入": [100, 120]})
    provider = SimpleNamespace(stock_financial_report_sina=lambda **kw: frame)
    result = research.collect("600000", "SSE", "STOCK", "FINANCIALS", date(2020, 1, 1),
                              date(2026, 10, 4), ak_module=provider, clock=CLOCK)
    assert result["complete"]
    assert len(result["records"]) == 6
    assert {r["asOfDate"] for r in result["records"]} == {"2023-12-31", "2024-12-31"}
    assert all(r["availabilityBasis"] == "FIRST_OBSERVED" for r in result["records"])
    assert all("peTtm" not in r["payload"]["values"] for r in result["records"])


def test_partial_financial_collection_preserves_success_without_claiming_complete():
    def reports(**kw):
        if kw["symbol"] == "现金流量表":
            raise ConnectionError("source unavailable")
        return pd.DataFrame({"报告日": ["2024-12-31"], "营业收入": [100]})
    result = research.collect("600000", "SSE", "STOCK", "FINANCIALS", date(2020, 1, 1),
                              date(2026, 10, 4), ak_module=SimpleNamespace(stock_financial_report_sina=reports), clock=CLOCK)
    assert not result["complete"]
    assert len(result["records"]) == 2
    assert result["failedSections"] == ["cash_flow"]


def test_holdings_preserve_disclosure_scope_and_source_units():
    frame = pd.DataFrame({"股票代码": ["600000"], "季度": ["2024年1季度股票投资明细"],
                          "占净值比例": [3.5], "持股数": [125]})
    provider = SimpleNamespace(fund_portfolio_hold_em=lambda **kw: frame,
                               fund_portfolio_bond_hold_em=lambda **kw: pd.DataFrame(),
                               fund_portfolio_industry_allocation_em=lambda **kw: pd.DataFrame())
    result = research.collect("010736", "FUND_CN", "MUTUAL_FUND", "FUND_HOLDINGS",
                              date(2024, 1, 1), date(2024, 12, 31), ak_module=provider, clock=CLOCK)
    row = result["records"][0]
    assert row["asOfDate"] == "2024-03-31"
    assert row["payload"]["coverageScope"] == "DISCLOSED_ROWS"
    assert row["payload"]["raw"]["持股数"] == 125
    assert row["payload"]["values"]["quantity"]["unit"] == "TEN_THOUSAND_SHARES"
    assert row["payload"]["values"]["weight"]["unit"] == "PERCENT"


def test_sec_facts_keep_accession_unit_and_filed_date(monkeypatch):
    monkeypatch.setattr(research, "_sec_cik", lambda code: "0000000001")
    facts = {"facts": {"us-gaap": {"Revenue": {"units": {"USD": [
        {"end": "2024-12-31", "filed": "2025-02-10", "accn": "original", "val": 100, "form": "10-K"},
        {"end": "2024-12-31", "filed": "2025-05-01", "accn": "revised", "val": 110, "form": "10-K/A"}
    ]}}}}}
    monkeypatch.setattr(research, "_sec_json", lambda url: facts)
    result = research.collect("AAPL", "NASDAQ", "STOCK", "FINANCIALS", date(2020, 1, 1), date(2026, 10, 4), clock=CLOCK)
    assert len(result["records"]) == 2
    assert result["records"][0]["recordKey"] != result["records"][1]["recordKey"]
    assert all(r["availabilityBasis"] == "FILED_DATE" for r in result["records"])
    assert result["records"][0]["payload"]["values"]["fact"]["unit"] == "USD"


def test_empty_response_and_failed_source_are_different():
    empty = research.collect("600000", "SSE", "STOCK", "CORPORATE_ACTIONS", date(2020, 1, 1),
        date(2026, 10, 4), ak_module=SimpleNamespace(stock_dividend_cninfo=lambda **kw: pd.DataFrame()), clock=CLOCK)
    assert empty["complete"] and empty["records"] == []
    def broken(**kw):
        raise ConnectionError("disconnected")
    with pytest.raises(ProviderUnavailable):
        research.collect("600000", "SSE", "STOCK", "CORPORATE_ACTIONS", date(2020, 1, 1),
            date(2026, 10, 4), ak_module=SimpleNamespace(stock_dividend_cninfo=broken), clock=CLOCK)


def test_fund_profile_fallback_flattens_fields_and_parses_inception_date():
    provider = SimpleNamespace(fund_individual_basic_info_xq=lambda **kw: pd.DataFrame(
        {"item": ["成立时间", "业绩比较基准"], "value": ["20201230", "基金真实基准"]}))
    result = research.collect("010736", "FUND_CN", "MUTUAL_FUND", "PROFILE", date(2020, 1, 1),
                              date(2026, 10, 4), ak_module=provider, clock=CLOCK)
    assert len(result["records"]) == 1
    values = result["records"][0]["payload"]["values"]
    assert values["inceptionDate"]["value"] == "2020-12-30"
    assert values["benchmark"]["value"] == "基金真实基准"


def test_us_fallback_preserves_report_basis_and_unknown_currency(monkeypatch):
    monkeypatch.setattr(research, "_sec_cik", lambda code: (_ for _ in ()).throw(ProviderUnavailable("SEC unavailable")))
    captured = []
    def reports(**kw):
        captured.append(kw)
        return pd.DataFrame({"REPORT_DATE": ["2024-12-31"], "STD_ITEM_CODE": ["revenue"],
                             "REPORT_TYPE": ["年度"], "AMOUNT": [123], "ITEM_NAME": ["营业收入"]})
    result = research.collect("BRK.A", "NYSE", "STOCK", "FINANCIALS", date(2020, 1, 1),
        date(2026, 10, 4), ak_module=SimpleNamespace(stock_financial_us_report_em=reports), clock=CLOCK)
    assert result["complete"] and len(result["records"]) == 3
    assert all(call["stock"] == "BRK_A" and call["indicator"] == "累计季报" for call in captured)
    assert all(row["payload"]["values"]["fact"]["unit"] == "UNKNOWN" for row in result["records"])
    assert all(row["availabilityBasis"] == "FIRST_OBSERVED" for row in result["records"])
    assert {row["payload"]["periodBasis"] for row in result["records"]} == {"INSTANT", "YEAR_TO_DATE"}


def test_industry_holdings_date_field_separates_quarters():
    industries = pd.DataFrame({"行业类别": ["金融", "金融"], "截止时间": ["2025-03-31", "2025-06-30"],
                               "占净值比例": [10, 11], "市值": [100, 110]})
    provider = SimpleNamespace(fund_portfolio_hold_em=lambda **kw: pd.DataFrame(),
        fund_portfolio_bond_hold_em=lambda **kw: pd.DataFrame(),
        fund_portfolio_industry_allocation_em=lambda **kw: industries)
    result = research.collect("010736", "FUND_CN", "MUTUAL_FUND", "FUND_HOLDINGS", date(2025,1,1),
                              date(2025,12,31), ak_module=provider, clock=CLOCK)
    assert result["complete"] and len(result["records"]) == 2
    assert len({row["recordKey"] for row in result["records"]}) == 2
    assert result["records"][0]["payload"]["values"]["marketValue"]["unit"] == "CNY"


def test_empty_notices_are_success_only_with_explicit_source_success(monkeypatch):
    response = SimpleNamespace(raise_for_status=lambda: None,
        json=lambda: {"ErrCode": 0, "TotalCount": 0, "Data": []})
    monkeypatch.setattr(research.requests, "get", lambda *args, **kwargs: response)
    result = research.collect("010736", "FUND_CN", "MUTUAL_FUND", "FUND_NOTICES", date(2020,1,1), date(2026,10,4), clock=CLOCK)
    assert result["complete"] and result["records"] == []
    response.json = lambda: {"ErrCode": 400, "TotalCount": 0, "Data": []}
    with pytest.raises(ProviderUnavailable):
        research.collect("010736", "FUND_CN", "MUTUAL_FUND", "FUND_NOTICES", date(2020,1,1), date(2026,10,4), clock=CLOCK)


def test_archive_fallback_preserves_code_period_and_units(monkeypatch):
    html = "<h4>2025年1季度股票投资明细</h4><table><tr><th>股票代码</th><th>占净值 比例</th><th>持股数 （万股）</th></tr>" \
           "<tr><td>002463</td><td>3.5%</td><td>125</td></tr></table>"
    payload = {"content": html, "arryear": [2025], "curyear": 2025}
    response = SimpleNamespace(raise_for_status=lambda: None, text="var apidata=" + __import__("json").dumps(payload) + ";\n")
    monkeypatch.setattr(research.requests, "get", lambda *args, **kwargs: response)
    provider = SimpleNamespace(fund_portfolio_bond_hold_em=lambda **kw: pd.DataFrame(),
        fund_portfolio_industry_allocation_em=lambda **kw: pd.DataFrame())
    result = research.collect("010736", "FUND_CN", "MUTUAL_FUND", "FUND_HOLDINGS", date(2025,1,1),
                              date(2025,12,31), ak_module=provider, clock=CLOCK)
    row = result["records"][0]
    assert row["asOfDate"] == "2025-03-31" and row["payload"]["raw"]["股票代码"] == "002463"
    assert row["payload"]["values"]["quantity"] == {"value": 125, "unit": "TEN_THOUSAND_SHARES", "sourceField": "持股数"}


def test_new_filing_for_an_old_report_is_selected_by_publication_window(monkeypatch):
    monkeypatch.setattr(research, "_sec_cik", lambda code: "0000000001")
    monkeypatch.setattr(research, "_sec_json", lambda url: {"filings": {"recent": {
        "accessionNumber": ["amendment"], "filingDate": ["2026-05-01"], "reportDate": ["2024-12-31"], "form": ["10-K/A"]}}})
    result = research.collect("AAPL", "NASDAQ", "STOCK", "FILINGS", date(2026,1,1), date(2026,10,4), clock=CLOCK)
    assert len(result["records"]) == 1
    assert result["records"][0]["asOfDate"] == "2024-12-31"
