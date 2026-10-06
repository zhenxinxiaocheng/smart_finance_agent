"""Provider catalog snapshots. A snapshot is current membership, never history."""

from __future__ import annotations

from collections import Counter
from concurrent.futures import ThreadPoolExecutor
import csv
from datetime import date
from functools import lru_cache
from io import StringIO
import logging
import os
from pathlib import Path
import re
from typing import Any

from .providers import (ProviderUnavailable, _china_index_provider_symbol, _frame_to_quotes,
                        akshare_us_symbol)
from .index_registry import INDEX_WATCHLIST_REGISTRY
from .provider_calls import call_akshare
from .strategy_config import load_strategy_config


_LOG = logging.getLogger(__name__)


class CatalogSnapshot(list):
    """Keep the list contract while exposing the actual supported scope."""

    def __init__(self, items, diagnostics=None):
        super().__init__(items)
        self.diagnostics = diagnostics or {}


@lru_cache(maxsize=1)
def _catalog_config():
    configured = os.getenv("ANALYSIS_MARKET_CATALOG_CONFIG")
    source = Path(configured) if configured else (
        Path(__file__).resolve().parent.parent / "config" / "market-catalog-v1.json")
    result = load_strategy_config(source)
    result.integer("sourceTimeoutSeconds")
    result.integer("requestTimeoutSeconds")
    result.integer("historySourceTimeoutSeconds")
    result.integer("historyRequestTimeoutSeconds")
    cn_sources = result.object_list("cnASources")
    required_cn = {"AKSHARE_SSE_MAIN", "AKSHARE_SSE_STAR", "AKSHARE_SZSE", "AKSHARE_BSE"}
    if len(cn_sources) != len(required_cn) or {item.get("name") for item in cn_sources} != required_cn:
        raise ValueError("CN_A catalog config must retain every required exchange partition")
    us_sources = result.object_list("usDirectorySources")
    if len(us_sources) != 2 or {item.get("name") for item in us_sources} != {"NASDAQ_LISTED", "NASDAQ_OTHER_LISTED"}:
        raise ValueError("US catalog config must retain both official directory partitions")
    return result


def _source_call(source, ak_module=None, *, history=False):
    name = source["name"]
    config = _catalog_config()
    try:
        return call_akshare(source["function"], source.get("kwargs", {}),
                            timeout_seconds=config.integer("historySourceTimeoutSeconds" if history else "sourceTimeoutSeconds"),
                            request_timeout_seconds=config.integer("historyRequestTimeoutSeconds" if history else "requestTimeoutSeconds"),
                            failure_threshold=config.integer("sourceFailureThreshold") if history else 0,
                            cooldown_seconds=config.integer("sourceCooldownSeconds") if history else 0,
                            ak_module=ak_module)
    except Exception as exc:
        raise ProviderUnavailable(f"{name}: {type(exc).__name__}: {exc}") from exc


def _text(value: Any) -> str:
    # NAN、NAT 是真实美股代码；只将非字符串缺失值视为空。
    if isinstance(value, str):
        return value.strip()
    value = str(value).strip()
    return "" if value.lower() in {"nan", "none", "nat", "<na>"} else value


def _cn_code(value: Any) -> str:
    text = _text(value)
    if re.fullmatch(r"\d{1,6}\.0", text):
        text = text[:-2]
    return text.zfill(6) if re.fullmatch(r"\d{1,6}", text) else ""


def _etf_identity(value: Any):
    text = _text(value).lower()
    explicit = re.fullmatch(r"(sh|sz)(\d{6})", text)
    if explicit:
        return ("SSE" if explicit[1] == "sh" else "SZSE"), explicit[2]
    code = _cn_code(value)
    # These are exchange fund-code families, rather than A-share stock heuristics.
    if code.startswith("5"):
        return "SSE", code
    if code.startswith("1"):
        return "SZSE", code
    return None, None


def _catalog_records(frame, source, product_type, identity):
    name = source["name"]
    code_column, name_column = source["codeColumn"], source["nameColumn"]
    if frame is None or code_column not in frame or name_column not in frame:
        raise ProviderUnavailable(f"{name}: unexpected catalog columns")
    if frame.empty:
        raise ProviderUnavailable(f"{name}: empty catalog")
    result = {}
    for row in frame.to_dict("records"):
        market, code = identity(row[code_column])
        product_name = _text(row[name_column])
        if not market or not code or not product_name:
            raise ProviderUnavailable(f"{name}: invalid catalog identity")
        item = {"productType": product_type, "market": market,
                "exchange": None if market == "FUND_CN" else market,
                "code": code, "name": product_name, "currency": "CNY",
                "status": "ACTIVE", "source": name}
        listing_column = source.get("listingDateColumn")
        if listing_column:
            if listing_column not in row:
                raise ProviderUnavailable(f"{name}: listing-date column unavailable")
            value = _text(row[listing_column])
            try:
                item["listingDate"] = date.fromisoformat(value[:10]).isoformat() if value else None
            except ValueError as exc:
                raise ProviderUnavailable(f"{name}: invalid listing date") from exc
        key = (product_type, market, code)
        if key in result and result[key] != item:
            raise ProviderUnavailable(f"{name}: conflicting duplicate catalog identity")
        result.setdefault(key, item)
    return list(result.values())


def _cn_a_catalog(ak_module):
    sources = _catalog_config().object_list("cnASources")

    def partition(source):
        frame = _source_call(source, ak_module)
        return _catalog_records(frame, source, "STOCK",
                                lambda code: (source["market"], _cn_code(code)))

    result, failures = [], []
    with ThreadPoolExecutor(max_workers=len(sources)) as executor:
        futures = [executor.submit(partition, source) for source in sources]
        for source, future in zip(sources, futures):
            try:
                result.extend(future.result())
            except Exception as exc:
                failures.append(f"{source['name']}: {exc}")
    if failures:
        raise ProviderUnavailable("CN_A incomplete catalog; " + "; ".join(failures))
    keys = {(item["productType"], item["market"], item["code"]) for item in result}
    if len(keys) != len(result):
        raise ProviderUnavailable("CN_A incomplete catalog: exchange partitions overlap")
    return CatalogSnapshot(result, {"scope": "SSE_MAIN_SSE_STAR_SZSE_BSE", "complete": True})


def _cn_etf_catalog(ak_module):
    failures = []
    for source in _catalog_config().object_list("cnEtfSources"):
        try:
            result = _catalog_records(_source_call(source, ak_module), source, "ETF", _etf_identity)
            return CatalogSnapshot(result, {"scope": "SSE_SZSE_ETF_CURRENT_SNAPSHOT",
                                            "sourceFailures": failures})
        except Exception as exc:
            failures.append(f"{source['name']}: {exc}")
    raise ProviderUnavailable("CN_ETF catalog unavailable; " + "; ".join(failures))


def _directory_text(source):
    import requests
    try:
        response = requests.get(source["url"], timeout=_catalog_config().integer("requestTimeoutSeconds"))
        response.raise_for_status()
        return response.text
    except Exception as exc:
        raise ProviderUnavailable(f"{source['name']}: {type(exc).__name__}: {exc}") from exc


_US_NON_STOCK_TYPES = (
    ("WARRANT", r"\bwarrants?\b"), ("RIGHT", r"\brights?\b"),
    ("UNIT", r"\bunits?\b"), ("PREFERRED", r"\b(?:preferred (?:stock|shares?|securities)|preference shares?)\b"),
    ("DEBT", r"\b(?:notes?|debentures?|bonds?)\b"),
)


def _us_security_type(row):
    etf = _text(row.get("ETF")).upper()
    if etf == "Y":
        return "ETF", None
    if etf != "N":
        raise ProviderUnavailable("NASDAQ symbol directory: unknown ETF classification")
    if _text(row.get("NextShares")).upper() == "Y":
        return None, "NEXTSHARES"
    # ACT/CTCI uses $ for preferred issues, including depositary shares whose
    # description need not contain the literal words "preferred stock".
    if re.search(r"\$[A-Z]*$", _text(row.get("ACT Symbol")).upper()):
        return None, "PREFERRED"
    description = _text(row["Security Name"]).rsplit(" - ", 1)[-1]
    for category, pattern in _US_NON_STOCK_TYPES:
        if re.search(pattern, description, re.IGNORECASE):
            return None, category
    return "STOCK", None


def _us_directory_rows(text, source, diagnostics):
    lines = [line for line in text.splitlines() if line.strip()]
    if not lines or not lines[-1].startswith("File Creation Time:"):
        raise ProviderUnavailable(f"{source['name']}: file creation time unavailable (truncated directory)")
    reader = csv.DictReader(StringIO("\n".join(lines[:-1])), delimiter="|")
    required = {source["codeColumn"], "Security Name", "ETF", "Test Issue"}
    if source["name"] != "NASDAQ_LISTED":
        required.add("Exchange")
    if not reader.fieldnames or not required.issubset(reader.fieldnames):
        raise ProviderUnavailable(f"{source['name']}: unexpected directory columns")
    venues = _catalog_config().value("usVenues")
    result = []
    for row in reader:
        if None in row or any(row[key] is None for key in required):
            raise ProviderUnavailable(f"{source['name']}: truncated directory row")
        test_issue = _text(row["Test Issue"]).upper()
        if test_issue == "Y":
            diagnostics["testIssues"] += 1
            continue
        if test_issue != "N":
            raise ProviderUnavailable(f"{source['name']}: unknown test-issue classification")
        venue_code = "NASDAQ" if source["name"] == "NASDAQ_LISTED" else _text(row["Exchange"])
        if venue_code not in venues:
            diagnostics["excludedVenues"][f"UNMAPPED:{venue_code}"] += 1
            continue
        venue = venues[venue_code]
        if venue["market"] is None:
            diagnostics["excludedVenues"][venue["exchange"]] += 1
            continue
        product_type, exclusion = _us_security_type(row)
        if exclusion:
            diagnostics["excludedSecurityTypes"][exclusion] += 1
            continue
        code, name = _text(row[source["codeColumn"]]).upper(), _text(row["Security Name"])
        if not re.fullmatch(r"[A-Z][A-Z0-9.-]*", code) or not name:
            raise ProviderUnavailable(f"{source['name']}: unsupported security symbol {code!r}")
        result.append({"productType": product_type, "market": venue["market"],
                       "exchange": venue["exchange"], "code": code, "name": name,
                       "currency": "USD", "status": "ACTIVE", "source": source["name"],
                       "classificationSource": "NASDAQ_SYMBOL_DIRECTORY"})
    if not result:
        raise ProviderUnavailable(f"{source['name']}: empty supported catalog")
    diagnostics["sourceUpdatedAt"][source["name"]] = lines[-1].split("|", 1)[0]
    return result


def _us_catalog(ak_module):
    diagnostics = {"scope": "NASDAQ_NYSE_NYSE_ARCA_NYSE_AMERICAN_CURRENT_SNAPSHOT",
                   "excludedVenues": Counter(), "excludedSecurityTypes": Counter(),
                   "testIssues": 0, "sourceFailures": [], "sourceUpdatedAt": {}}
    names = {}
    name_source = _catalog_config().value("usNameSource")
    try:
        frame = _source_call(name_source, ak_module)
        if frame is None or not {"代码", "名称"}.issubset(frame.columns):
            raise ProviderUnavailable("unexpected catalog columns")
        for row in frame.to_dict("records"):
            prefix, separator, code = _text(row["代码"]).partition(".")
            name = _text(row["名称"])
            if separator and prefix in {"105", "106", "107"} and code and name:
                names[code.upper()] = name
    except Exception as exc:
        diagnostics["sourceFailures"].append(f"{name_source['name']}: {exc}")
    sources = _catalog_config().object_list("usDirectorySources")
    result = {}
    # Both official partitions are mandatory; a successful half is never a US snapshot.
    with ThreadPoolExecutor(max_workers=len(sources)) as executor:
        futures = [executor.submit(_directory_text, source) for source in sources]
        for source, future in zip(sources, futures):
            rows = _us_directory_rows(future.result(), source, diagnostics)
            for item in rows:
                if item["code"] in names:
                    item["name"] = names[item["code"]]
                    item["source"] = f"AKSHARE_US_SPOT+{item['source']}"
                result.setdefault((item["productType"], item["market"], item["code"]), item)
    diagnostics["excludedVenues"] = dict(diagnostics["excludedVenues"])
    diagnostics["excludedSecurityTypes"] = dict(diagnostics["excludedSecurityTypes"])
    if diagnostics["excludedVenues"] or diagnostics["excludedSecurityTypes"]:
        _LOG.warning("US catalog scope exclusions: venues=%s securityTypes=%s",
                     diagnostics["excludedVenues"], diagnostics["excludedSecurityTypes"])
    return CatalogSnapshot(list(result.values()), diagnostics)


def catalog(market: str, ak_module=None) -> list[dict[str, Any]]:
    if market == "CN_A":
        return _cn_a_catalog(ak_module)
    if market == "US":
        return _us_catalog(ak_module)
    if market == "CN_ETF":
        return _cn_etf_catalog(ak_module)
    if market == "FUND_CN":
        source = _catalog_config().value("fundCnSource")
        rows = _catalog_records(_source_call(source, ak_module), source, "MUTUAL_FUND",
                                lambda code: ("FUND_CN", _cn_code(code)))
        return CatalogSnapshot(rows, {"scope": "EASTMONEY_OPEN_FUND_NAV_DIRECTORY",
                                      "membershipCapability": "SOURCE_CURRENT_SNAPSHOT"})
    if market == "INDEX":
        return [{"productType": "INDEX",
                 "market": "CN_INDEX" if item["market"] == "CN" else "US_INDEX",
                 "exchange": None, "code": item["indexCode"], "name": item["name"],
                 "currency": "CNY" if item["market"] == "CN" else "USD",
                 "status": "ACTIVE", "source": "INDEX_WATCHLIST_REGISTRY"}
                for item in INDEX_WATCHLIST_REGISTRY.instruments()]
    raise ValueError("unsupported catalog market")


CN_PRESET_CODES = {"CSI300": "000300", "CSI500": "000905", "CSI1000": "000852"}


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
    if start_date > end_date:
        raise ValueError("start date cannot exceed end date")
    start, end = start_date.strftime("%Y%m%d"), end_date.strftime("%Y%m%d")
    adjust = {"NONE": "", "QFQ": "qfq", "HFQ": "hfq"}[adjust_type]
    variables = {"code": code, "start": start, "end": end, "adjust": adjust,
                 "startIso": start_date.isoformat(), "endIso": end_date.isoformat()}
    if product_type == "ETF" and market in {"SSE", "SZSE", "BSE"}:
        source_group = "cnEtfRaw" if adjust_type == "NONE" and market in {"SSE", "SZSE"} else "cnEtfAdjusted"
        variables["etfSymbol"] = ("sh" if market == "SSE" else "sz") + code
    elif product_type == "STOCK" and market in {"SSE", "SZSE", "BSE"}:
        source_group = "cnStock"
        variables["stockSymbol"] = {"SSE": "sh", "SZSE": "sz", "BSE": "bj"}[market] + code
    elif product_type in {"STOCK", "ETF"} and market in {"NASDAQ", "NYSE", "AMEX"} and adjust_type == "NONE":
        source_group = "usRaw"
        variables["usSymbol"] = akshare_us_symbol(code, market)
    elif product_type in {"MUTUAL_FUND", "FUND"} and market == "FUND_CN" and adjust_type == "NONE":
        source_group = "fundNav"
    elif product_type == "INDEX" and market == "CN_INDEX" and adjust_type == "NONE":
        source_group = "cnIndexRaw"
        variables["indexCode"] = code.removeprefix("CN_INDEX:")
        variables["indexSymbol"] = _china_index_provider_symbol(variables["indexCode"])
    elif product_type == "INDEX" and market == "US_INDEX" and adjust_type == "NONE":
        configured = next((item for item in INDEX_WATCHLIST_REGISTRY.fallback_quotes()
                           if item["indexCode"] == code and item["provider"] == "SINA_US_INDEX_HISTORY"), None)
        if configured is None:
            raise ProviderUnavailable(f"unsupported history: INDEX/US_INDEX/{code}")
        source_group = "usIndexRaw"
        variables["indexSymbol"] = configured["symbol"]
    else:
        raise ProviderUnavailable(f"unsupported history: {product_type}/{market}/{adjust_type}")
    if ak_module is None:
        try:
            import akshare as ak_module
        except ImportError as exc:
            raise ProviderUnavailable("AKShare is not installed") from exc
    failures, empty_response = [], False
    for configured in _catalog_config().object_list(f"historySources.{source_group}"):
        if configured.get("markets") and market not in configured["markets"]:
            continue
        source = dict(configured)
        source["kwargs"] = {key: value.format_map(variables) for key, value in configured["kwargs"].items()}
        try:
            frame = _source_call(source, ak_module, history=True)
            if frame is None:
                raise ProviderUnavailable("no response")
            if frame.empty:
                empty_response = True
                continue
            records = [record for record in _frame_to_quotes(frame, code, market, source["name"])
                       if start_date <= record.data_date <= end_date]
            if records:
                return records
            empty_response = True
        except Exception as exc:
            failures.append(f"{source['name']}: {exc}")
    if empty_response and not failures:
        return []
    raise ProviderUnavailable(f"history unavailable: {product_type}/{market}/{adjust_type}; " + "; ".join(failures))
