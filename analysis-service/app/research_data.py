"""Free-source research facts. Collection is stateless; the existing Java jobs own storage."""
from __future__ import annotations

import calendar
import hashlib
import json
import os
import re
import threading
import time
from datetime import date, datetime, timezone
from decimal import Decimal
from functools import lru_cache
from io import StringIO
from pathlib import Path
from typing import Any

import requests

from .provider_calls import call_akshare
from .providers import ProviderUnavailable


@lru_cache(maxsize=1)
def config():
    path = Path(os.getenv("ANALYSIS_RESEARCH_DATA_CONFIG") or
                Path(__file__).resolve().parent.parent / "config/research-data-v1.json")
    value = json.loads(path.read_text(encoding="utf-8-sig"))
    for key in ("sourceTimeoutSeconds", "requestTimeoutSeconds", "collectionBudgetSeconds",
                "sourceFailureThreshold", "sourceCooldownSeconds"):
        if not isinstance(value.get(key), (int, float)) or value[key] <= 0:
            raise ValueError(f"invalid research configuration: {key}")
    for name, dataset in value["datasets"].items():
        if not re.fullmatch(r"[A-Z_]{1,20}", name) or dataset["refreshDays"] < 1:
            raise ValueError("invalid research dataset")
    return value


def family(market, product_type):
    if product_type == "STOCK" and market in {"SSE", "SZSE", "BSE"}:
        return "CN_STOCK"
    if market == "FUND_CN" and product_type in {"MUTUAL_FUND", "FUND"}:
        return "CN_FUND"
    if product_type == "ETF" and market in {"SSE", "SZSE", "BSE"}:
        return "CN_FUND"
    if market in {"NASDAQ", "NYSE", "AMEX"} and product_type in {"STOCK", "ETF"}:
        return "US_" + product_type
    return None


def capabilities():
    return [{"dataset": name, "families": list(value["families"]),
             "refreshDays": value["refreshDays"], "yearly": bool(value.get("yearly"))}
            for name, value in config()["datasets"].items()]


def _json_value(value):
    if isinstance(value, dict):
        return {str(k): _json_value(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [_json_value(v) for v in value]
    if value is None:
        return None
    if isinstance(value, Decimal):
        return str(value)
    try:
        import pandas as pd
        if pd.isna(value):
            return None
    except (TypeError, ValueError):
        pass
    if isinstance(value, (datetime, date)):
        return value.isoformat()
    if hasattr(value, "item"):
        return _json_value(value.item())
    return value if isinstance(value, (str, bool, int, float)) else str(value)


def _date(value):
    if value is None:
        return None
    text = str(value).strip()
    quarter = re.search(r"(\d{4})\s*年\s*([1-4])\s*季度", text)
    if quarter:
        year, month = int(quarter[1]), int(quarter[2]) * 3
        return date(year, month, calendar.monthrange(year, month)[1]).isoformat()
    match = re.search(r"(\d{4})[-/年](\d{1,2})[-/月](\d{1,2})", text)
    if not match:
        match = re.fullmatch(r"(\d{4})(\d{2})(\d{2})", text)
    if not match:
        return None
    try:
        return date(*(int(v) for v in match.groups())).isoformat()
    except ValueError:
        return None


def _first_date(row, columns):
    return next((parsed for key in columns if (parsed := _date(row.get(key)))), None)


def _hash(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False,
                                    separators=(",", ":"), allow_nan=False).encode()).hexdigest()


def _record(dataset, section, source, row, observed, *, identity=None,
            as_of=None, published=None, versioned=False):
    raw = _json_value(row)
    as_of = as_of or _first_date(raw, config()["dateColumns"])
    published = published or _first_date(raw, config()["publicationColumns"])
    if dataset == "FUND_NOTICES":
        as_of = as_of or published
    identities = identity if identity is not None else {
        key: raw[key] for key in config()["identityColumns"] if raw.get(key) is not None}
    if not as_of and not identities and dataset not in {"PROFILE", "FUND_OPERATIONS"}:
        raise ProviderUnavailable(f"{source}: row has no research date or stable identity")
    values = {}
    units = config().get("fieldUnits", {}).get(source, {}).get(dataset, {})
    for name, aliases in config()["fieldAliases"].items():
        for alias in aliases:
            if raw.get(alias) is not None:
                value = raw[alias]
                if name in {"inceptionDate", "listingDate"}:
                    value = _date(value) or value
                values[name] = {"value": value, "sourceField": alias,
                                "unit": units.get(alias, units.get("default", "UNKNOWN"))}
                break
    payload = {"section": section, "values": values, "raw": raw,
               "fieldUnits": units, "currency": raw.get("币种"),
               "coverageScope": "DISCLOSED_ROWS" if dataset in {"FUND_HOLDINGS", "SHAREHOLDERS"} else "SOURCE_RESPONSE"}
    if dataset == "FINANCIALS":
        payload["periodBasis"] = ("INSTANT" if section == "balance_sheet" else
                                  "REPORTED_INTERVAL" if source == "SEC" and raw.get("start") else
                                  "INSTANT" if source == "SEC" else "YEAR_TO_DATE")
    return {"recordKey": _hash({"section": section, "date": as_of, "identity": identities}),
            "contentHash": _hash(payload), "provider": source, "asOfDate": as_of,
            "publishedDate": published, "observedAt": observed.isoformat(),
            "availabilityBasis": "FILED_DATE" if versioned and published else "FIRST_OBSERVED",
            "publicationTimezone": "America/New_York" if versioned and source == "SEC" else None,
            "payload": payload}


def _http_rows(source, variables, deadline):
    import pandas as pd
    policy = config()["httpSources"][source["endpoint"]]
    params = {key: value.format_map(variables) for key, value in source.get("params", {}).items()}
    params["pageSize"] = policy["pageSize"]
    rows = []
    for page in range(1, policy["maxPages"] + 1):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise ProviderUnavailable("collection budget exhausted before completing response pages")
        response = requests.get(policy["url"], params=dict(params, pageIndex=page), headers=policy["headers"],
                                timeout=min(config()["requestTimeoutSeconds"], remaining))
        response.raise_for_status()
        data = response.json()
        if data.get("ErrCode") != 0 or not isinstance(data.get("Data"), list):
            raise ProviderUnavailable("invalid source response or upstream business error")
        count = data.get("TotalCount")
        if not isinstance(count, int) or count < 0 or any(not isinstance(row, dict) for row in data["Data"]):
            raise ProviderUnavailable("invalid source page metadata")
        rows.extend(data["Data"])
        if len(rows) >= count:
            return pd.DataFrame([{**row, **{target: row.get(key) for key, target in policy["fieldMap"].items()}}
                                 for row in rows])
        if not data["Data"]:
            raise ProviderUnavailable("incomplete source pages")
    raise ProviderUnavailable("source page limit exceeded")


def _fund_archive(source, variables, deadline):
    import pandas as pd
    from bs4 import BeautifulSoup
    from akshare.utils import demjson
    policy = config()["httpSources"][source["endpoint"]]
    params = {key: value.format_map(variables) for key, value in source["params"].items()}
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise ProviderUnavailable("collection budget exhausted")
    response = requests.get(policy["url"], params=params, headers=policy["headers"],
                            timeout=min(config()["requestTimeoutSeconds"], remaining))
    response.raise_for_status()
    text = response.text
    begin, end = text.find("{"), text.rfind("}")
    if begin < 0 or end < begin:
        raise ProviderUnavailable("unexpected fund archive response")
    data = demjson.decode(text[begin:end + 1])
    if (not isinstance(data, dict) or not isinstance(data.get("content"), str)
            or not isinstance(data.get("arryear"), list) or str(data.get("curyear")) != variables["year"]):
        raise ProviderUnavailable("invalid fund archive metadata")
    soup = BeautifulSoup(data["content"], "lxml")
    rows = []
    for table in soup.find_all("table"):
        heading = table.find_previous("h4")
        report_date = _date(heading.get_text(" ", strip=True)) if heading else None
        if not report_date:
            raise ProviderUnavailable("fund archive table has no report period")
        frame = pd.read_html(StringIO(str(table)), converters={"股票代码": str, "债券代码": str})[0]
        for raw in frame.to_dict("records"):
            normalized = {str(key): value for key, value in raw.items()}
            for name, aliases in policy["columnAliases"].items():
                field = next((key for key in aliases if key in normalized), None)
                if field:
                    text_value = str(normalized[field]).replace(",", "").strip().rstrip("%")
                    normalized[name] = _json_value(pd.to_numeric(text_value, errors="coerce"))
            normalized["报告日期"] = report_date
            rows.append(normalized)
    return pd.DataFrame(rows)


def _source_frame(source, variables, ak_module=None, deadline=None):
    if source["function"] == "http_json_rows":
        return _http_rows(source, variables, deadline or time.monotonic() + config()["collectionBudgetSeconds"])
    if source["function"] == "http_fund_archive":
        return _fund_archive(source, variables, deadline or time.monotonic() + config()["collectionBudgetSeconds"])
    frame = call_akshare(source["function"], {k: v.format_map(variables) for k, v in source.get("kwargs", {}).items()},
        timeout_seconds=min(config()["sourceTimeoutSeconds"], max(0.001, deadline - time.monotonic())) if deadline else config()["sourceTimeoutSeconds"],
        request_timeout_seconds=config()["requestTimeoutSeconds"],
        failure_threshold=config()["sourceFailureThreshold"], cooldown_seconds=config()["sourceCooldownSeconds"],
        ak_module=ak_module)
    if frame is None:
        raise ProviderUnavailable(f"{source['provider']}: no response")
    return frame


_sec_lock = threading.Lock()
_sec_last_request = 0.0
_ticker_cache = None


def _sec_json(url):
    global _sec_last_request
    policy = config()["sec"]
    user_agent = os.getenv(policy["userAgentEnvironment"], "").strip()
    if not user_agent:
        raise ProviderUnavailable(f"SEC: configure {policy['userAgentEnvironment']} with your actual access identification")
    with _sec_lock:
        pause = policy["minimumRequestIntervalSeconds"] - (time.monotonic() - _sec_last_request)
        if pause > 0:
            time.sleep(pause)
        _sec_last_request = time.monotonic()
    response = requests.get(url, headers={"User-Agent": user_agent, "Accept": "application/json"},
                            timeout=policy["requestTimeoutSeconds"])
    response.raise_for_status()
    return response.json()


def _sec_cik(code):
    global _ticker_cache
    with _sec_lock:
        cached = _ticker_cache
    if cached is None or cached[0] <= time.monotonic():
        data = _sec_json("https://www.sec.gov/files/company_tickers_exchange.json")
        columns = data["fields"]
        values = {str(row[columns.index("ticker")]).upper(): int(row[columns.index("cik")]) for row in data["data"]}
        with _sec_lock:
            _ticker_cache = (time.monotonic() + config()["sec"]["tickerCacheSeconds"], values)
        cached = _ticker_cache
    cik = cached[1].get(code.upper())
    if cik is None:
        raise ProviderUnavailable(f"SEC: no issuer mapping for {code}")
    return f"{cik:010d}"


def _sec_records(function, code, dataset, section, observed, start_date, end_date, deadline=None):
    cik = _sec_cik(code)
    if function == "sec_facts":
        response = _sec_json(f"https://data.sec.gov/api/xbrl/companyfacts/CIK{cik}.json")
        records = []
        for namespace, concepts in response.get("facts", {}).items():
            for concept, definition in concepts.items():
                for unit, facts in definition.get("units", {}).items():
                    for fact in facts:
                        if not fact.get("accn") or not _date(fact.get("filed")):
                            continue
                        if not _date(fact.get("end")) or fact["end"] > end_date.isoformat() or fact["filed"] > end_date.isoformat():
                            continue
                        raw = dict(fact, namespace=namespace, concept=concept, unit=unit, cik=cik,
                                   label=definition.get("label"), description=definition.get("description"))
                        record = _record(dataset, section, "SEC", raw, observed,
                            identity={"namespace": namespace, "concept": concept, "unit": unit,
                                      "start": fact.get("start"), "accn": fact["accn"], "fy": fact.get("fy"),
                                      "fp": fact.get("fp"), "frame": fact.get("frame")},
                            as_of=_date(fact.get("end")), published=_date(fact["filed"]), versioned=True)
                        record["payload"]["values"] = {"fact": {"value": _json_value(fact.get("val")), "unit": unit,
                                                                       "sourceField": f"{namespace}:{concept}"}}
                        record["contentHash"] = _hash(record["payload"])
                        records.append(record)
        return records
    response = _sec_json(f"https://data.sec.gov/submissions/CIK{cik}.json")
    if function == "sec_profile":
        raw = {key: value for key, value in response.items() if key != "filings"}
        return [_record(dataset, section, "SEC", raw, observed)]
    parts = [response.get("filings", {}).get("recent", {})]
    for item in response.get("filings", {}).get("files", []):
        if (_date(item.get("filingTo")) and item["filingTo"] >= start_date.isoformat()
                and (not _date(item.get("filingFrom")) or item["filingFrom"] <= end_date.isoformat())):
            if deadline is not None and time.monotonic() >= deadline:
                raise ProviderUnavailable("SEC filing collection budget exhausted")
            parts.append(_sec_json("https://data.sec.gov/submissions/" + item["name"]))
    records = []
    for part in parts:
        for index, accession in enumerate(part.get("accessionNumber", [])):
            raw = {key: values[index] for key, values in part.items() if isinstance(values, list) and index < len(values)}
            published = _date(raw.get("filingDate"))
            if not published or not start_date.isoformat() <= published <= end_date.isoformat():
                continue
            records.append(_record(dataset, section, "SEC", raw, observed,
                identity={"accn": accession}, as_of=_date(raw.get("reportDate")) or published,
                published=published, versioned=True))
    return records


def _us_financials(code, dataset, observed, deadline, ak_module):
    rows, failures = [], []
    for report in config()["usFinancialFallback"]:
        try:
            if time.monotonic() >= deadline:
                raise ProviderUnavailable("collection budget exhausted")
            frame = _source_frame(report, {"code": code.replace(".", "_")}, ak_module, deadline)
            for row in frame.to_dict("records"):
                record = _record(dataset, report["section"], report["provider"], row, observed)
                record["payload"]["values"]["fact"] = {"value": _json_value(row.get("AMOUNT")),
                    "sourceField": str(row.get("STD_ITEM_CODE")), "label": str(row.get("ITEM_NAME")),
                    "unit": "UNKNOWN", "periodBasis": record["payload"]["periodBasis"]}
                record["contentHash"] = _hash(record["payload"])
                rows.append(record)
        except Exception as error:
            failures.append({"section": report["section"], "message": str(error)})
    if len(failures) == len(config()["usFinancialFallback"]):
        raise ProviderUnavailable("; ".join(item["message"] for item in failures))
    return rows, failures


def collect(code, market, product_type, dataset, start_date, end_date, *, ak_module=None, clock=None):
    if start_date > end_date:
        raise ValueError("start date cannot exceed end date")
    kind = family(market, product_type)
    definition = config()["datasets"].get(dataset)
    if definition is None or kind not in definition["families"]:
        raise ValueError("unsupported product/dataset combination")
    if definition.get("yearly") and start_date.year != end_date.year:
        raise ValueError("yearly research collection must stay within one calendar year")
    observed = clock() if clock else datetime.now(timezone.utc)
    if observed.tzinfo is None:
        raise ValueError("research clock must include timezone")
    variables = {"code": code, "year": str(start_date.year),
                 "stockSymbol": {"SSE": "sh", "SZSE": "sz", "BSE": "bj"}.get(market, "") + code}
    result, warnings, successful, failed = [], [], [], []
    deadline = time.monotonic() + config()["collectionBudgetSeconds"]
    for partition in definition["families"][kind]:
        section, errors, success = partition["section"], [], False
        for source in partition["sources"]:
            if time.monotonic() >= deadline:
                errors.append("collection budget exhausted")
                break
            try:
                function = source["function"]
                if function.startswith("sec_"):
                    rows = _sec_records(function, code, dataset, section, observed, start_date, end_date, deadline)
                elif function == "em_us_financials":
                    rows, incomplete = _us_financials(code, dataset, observed, deadline, ak_module)
                    failed.extend(item["section"] for item in incomplete)
                    warnings.extend(incomplete)
                else:
                    frame = _source_frame(source, variables, ak_module, deadline)
                    if dataset == "PROFILE" and frame.empty:
                        raise ProviderUnavailable("empty product profile")
                    raw_rows = frame.to_dict("records")
                    if source.get("layout") == "fields":
                        if not frame.empty and len(frame.columns) < 2:
                            raise ProviderUnavailable("invalid field/value response")
                        raw_rows = [{str(row.iloc[0]): row.iloc[1] for _, row in frame.iterrows()}] if not frame.empty else []
                    rows = [_record(dataset, section, source["provider"], row, observed) for row in raw_rows]
                window_field = definition.get("windowField", "asOfDate")
                rows = [row for row in rows if row[window_field] is None or row[window_field] <= end_date.isoformat()]
                if definition.get("yearly"):
                    rows = [row for row in rows if row[window_field] is None or row[window_field] >= start_date.isoformat()]
                result.extend(rows)
                successful.append(section)
                success = True
                break
            except Exception as error:
                errors.append(f"{source['provider']}: {type(error).__name__}: {error}")
        if not success:
            failed.append(section)
        warnings.extend({"section": section, "message": message} for message in errors)
    if not successful:
        raise ProviderUnavailable("research sources unavailable: " + "; ".join(item["message"] for item in warnings))
    # Some APIs return their entire history. Keep it: checkpoint records the checked
    # acquisition window, never the maximum row date masquerading as publication.
    unique = {}
    for row in result:
        key = (row["provider"], row["recordKey"])
        if key in unique and unique[key]["contentHash"] != row["contentHash"]:
            raise ProviderUnavailable("conflicting research rows with the same business identity")
        unique[key] = row
    return {"dataset": dataset, "adapterVersion": config()["version"], "records": list(unique.values()),
            "complete": not failed, "checkedThrough": end_date.isoformat(),
            "successfulSections": successful, "failedSections": failed, "warnings": warnings}
