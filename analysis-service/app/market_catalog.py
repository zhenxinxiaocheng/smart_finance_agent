"""Provider catalog snapshots. A snapshot is current membership, never history."""

from __future__ import annotations

from typing import Any

from datetime import date, datetime, timedelta
from decimal import Decimal, InvalidOperation
from functools import lru_cache
import json
import os
from pathlib import Path
import re
from zoneinfo import ZoneInfo

from .providers import ProviderUnavailable, _frame_to_quotes, akshare_us_symbol, normalize_quote
from .index_registry import INDEX_WATCHLIST_REGISTRY


def _us_etf_symbols() -> set[str]:
    configured = os.getenv("ANALYSIS_US_ETF_REGISTRY")
    source = Path(configured) if configured else (
        Path(__file__).resolve().parent.parent / "config" / "market-us-etfs-v1.json")
    with source.open("r", encoding="utf-8") as handle:
        data = json.load(handle)
    if not data.get("version") or not isinstance(data.get("symbols"), list):
        raise ValueError("US ETF registry is invalid")
    symbols = {str(symbol).strip().upper() for symbol in data["symbols"]}
    if not symbols or any(not re.fullmatch(r"[A-Z][A-Z0-9.-]*", symbol) for symbol in symbols):
        raise ValueError("US ETF symbols are invalid")
    return symbols


US_ETF_SYMBOLS = _us_etf_symbols()


def _records(frame: Any, code_column: str, name_column: str, market_for_code,
             product_type: str, source: str) -> list[dict[str, str]]:
    if frame is None or code_column not in frame or name_column not in frame:
        raise ProviderUnavailable(f"{source}: unexpected catalog columns")
    result = []
    seen = set()
    for _, row in frame.iterrows():
        raw_code = str(row[code_column]).strip()
        name = str(row[name_column]).strip()
        market, code = market_for_code(raw_code)
        if not market or not code or not name or name.lower() == "nan":
            continue
        key = (product_type, market, code)
        if key in seen:
            continue
        seen.add(key)
        result.append({"productType": product_type, "market": market, "exchange": market,
                       "code": code, "name": name,
                       "currency": "USD" if market in {"NASDAQ", "NYSE", "AMEX"} else "CNY",
                       "status": "ACTIVE", "source": source})
    return result


def catalog(market: str, ak_module=None) -> list[dict[str, str]]:
    if ak_module is None:
        try:
            import akshare as ak_module
        except ImportError as exc:
            raise ProviderUnavailable("AKShare is not installed") from exc
    if market == "CN_A":
        def cn_exchange(code: str):
            if not re.fullmatch(r"\d{6}", code):
                return None, None
            exchange = "BSE" if code.startswith(("4", "8", "9")) else (
                "SSE" if code.startswith(("5", "6")) else "SZSE")
            return exchange, code
        return _records(ak_module.stock_info_a_code_name(), "code", "name",
                        cn_exchange, "STOCK", "AKSHARE_A_LIST")
    if market == "US":
        exchange = {"105": "NASDAQ", "106": "NYSE"}
        def us_exchange(value: str):
            prefix, separator, code = value.partition(".")
            return (exchange.get(prefix), code.upper()) if separator and code.lower() not in {"nan", "none"} and re.fullmatch(
                r"[A-Za-z][A-Za-z0-9.-]*", code) else (None, None)
        def etf_exchange(value: str):
            prefix, separator, code = value.partition(".")
            if prefix == "107":
                return ("AMEX", code.upper()) if separator and re.fullmatch(
                    r"[A-Za-z][A-Za-z0-9.-]*", code) else (None, None)
            return us_exchange(value)
        frame = ak_module.stock_us_spot_em()
        if "代码" not in frame:
            raise ProviderUnavailable("AKSHARE_US_SPOT: unexpected catalog columns")
        etf_rows = frame[frame["代码"].astype(str).str.partition(".")[2].str.upper().isin(US_ETF_SYMBOLS)]
        stock_rows = frame.drop(etf_rows.index)
        return (_records(stock_rows, "代码", "名称", us_exchange, "STOCK", "AKSHARE_US_SPOT")
                + _records(etf_rows, "代码", "名称", etf_exchange, "ETF", "AKSHARE_US_SPOT_CURATED_ETF"))
    if market == "CN_ETF":
        def etf_exchange(code: str):
            if not re.fullmatch(r"\d{6}", code):
                return None, None
            return ("SSE" if code.startswith(("5", "6")) else "SZSE", code)
        return _records(ak_module.fund_etf_spot_em(), "代码", "名称",
                        etf_exchange, "ETF", "AKSHARE_ETF_SPOT")
    if market == "INDEX":
        return [{"productType": "INDEX",
                 "market": "CN_INDEX" if item["market"] == "CN" else "US_INDEX",
                 "exchange": None, "code": item["indexCode"], "name": item["name"],
                 "currency": "CNY" if item["market"] == "CN" else "USD",
                 "status": "ACTIVE", "source": "INDEX_WATCHLIST_REGISTRY"}
                for item in INDEX_WATCHLIST_REGISTRY.instruments()]
    raise ValueError("unsupported catalog market")


CN_PRESET_CODES = {"CSI300": "000300", "CSI500": "000905", "CSI1000": "000852"}


def _fund_daily_rows(frame: Any) -> dict[str, list[tuple[date, Any]]]:
    if frame is None or "基金代码" not in frame:
        raise ProviderUnavailable("AKSHARE_FUND_DAILY: unexpected columns")
    nav_columns = [(name, date.fromisoformat(name[:10])) for name in frame.columns
                   if re.fullmatch(r"\d{4}-\d{2}-\d{2}-单位净值", str(name))]
    if not nav_columns:
        raise ProviderUnavailable("AKSHARE_FUND_DAILY: NAV columns unavailable")
    result = {}
    for _, row in frame.iterrows():
        code = str(row["基金代码"]).strip()
        if not re.fullmatch(r"\d{6}", code):
            continue
        values = []
        for column, day in nav_columns:
            try:
                nav = Decimal(str(row[column]).strip())
                if nav.is_finite() and nav > 0:
                    values.append((day, nav))
            except InvalidOperation:
                continue
        if values:
            result[code] = values
    return result


@lru_cache(maxsize=2)
def _cached_fund_daily(hour: str) -> dict[str, list[tuple[date, Any]]]:
    import akshare
    return _fund_daily_rows(akshare.fund_open_fund_daily_em())


def current_members(preset: str, ak_module=None) -> list[str]:
    if preset not in CN_PRESET_CODES:
        raise ProviderUnavailable(f"{preset}: current constituents unavailable")
    if ak_module is None:
        try:
            import akshare as ak_module
        except ImportError as exc:
            raise ProviderUnavailable("AKShare is not installed") from exc
    frame = ak_module.index_stock_cons_csindex(symbol=CN_PRESET_CODES[preset])
    column = next((name for name in ("成分券代码", "证券代码", "品种代码") if name in frame), None)
    if column is None:
        raise ProviderUnavailable("AKShare CSI constituents: unexpected columns")
    return sorted({str(code).strip().zfill(6) for code in frame[column]
                   if re.fullmatch(r"\d{1,6}", str(code).strip())})


def daily_history(code: str, market: str, product_type: str,
                  start_date: date, end_date: date, adjust_type: str,
                  ak_module=None):
    if adjust_type not in {"NONE", "QFQ", "HFQ"}:
        raise ValueError("unsupported adjust type")
    if ak_module is None:
        try:
            import akshare as ak_module
        except ImportError as exc:
            raise ProviderUnavailable("AKShare is not installed") from exc
    start, end = start_date.strftime("%Y%m%d"), end_date.strftime("%Y%m%d")
    adjust = {"NONE": "", "QFQ": "qfq", "HFQ": "hfq"}[adjust_type]
    if product_type == "ETF" and market in {"SSE", "SZSE", "BSE"}:
        frame = ak_module.fund_etf_hist_em(symbol=code, period="daily",
                                           start_date=start, end_date=end, adjust=adjust)
    elif product_type == "STOCK" and market in {"SSE", "SZSE", "BSE"}:
        frame = ak_module.stock_zh_a_hist(symbol=code, period="daily",
                                          start_date=start, end_date=end, adjust=adjust)
    elif ((product_type == "STOCK" and market in {"NASDAQ", "NYSE", "AMEX"})
          or (product_type == "ETF" and market in {"NASDAQ", "NYSE", "AMEX"})) and adjust_type == "NONE":
        frame = ak_module.stock_us_hist(symbol=akshare_us_symbol(code, market),
                                        period="daily", start_date=start, end_date=end, adjust="")
    elif (product_type in {"MUTUAL_FUND", "FUND"} and market == "FUND_CN"
          and adjust_type == "NONE" and end_date >= date.today() - timedelta(days=1)
          and (end_date - start_date).days <= 7):
        hour = datetime.now(ZoneInfo("Asia/Shanghai")).strftime("%Y%m%d%H")
        latest = (_cached_fund_daily(hour) if ak_module is None else
                  _fund_daily_rows(ak_module.fund_open_fund_daily_em()))
        return [normalize_quote(product_code=code, market=market, trade_date=day,
                                raw={"close": nav}, provider="AKSHARE_FUND_DAILY")
                for day, nav in latest.get(code, []) if start_date <= day <= end_date]
    elif product_type in {"MUTUAL_FUND", "FUND"} and market == "FUND_CN" and adjust_type == "NONE":
        frame = ak_module.fund_open_fund_info_em(symbol=code, indicator="单位净值走势")
    elif product_type == "INDEX" and market == "CN_INDEX" and adjust_type == "NONE":
        symbol = code.removeprefix("CN_INDEX:")
        frame = ak_module.index_zh_a_hist(symbol=symbol, period="daily",
                                          start_date=start, end_date=end)
    else:
        raise ProviderUnavailable(f"unsupported history: {product_type}/{market}/{adjust_type}")
    records = [record for record in _frame_to_quotes(frame, code, market, "AKSHARE")
               if start_date <= record.data_date <= end_date]
    return records
