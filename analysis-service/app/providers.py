from __future__ import annotations

import json
import os
from dataclasses import asdict, dataclass
from datetime import date, datetime, timezone
from decimal import Decimal
from typing import Any, Iterable
from urllib.request import Request, urlopen
from zoneinfo import ZoneInfo


@dataclass(frozen=True)
class NormalizedQuote:
    product_code: str
    market: str
    data_date: date
    open: Decimal | None
    high: Decimal | None
    low: Decimal | None
    close: Decimal
    volume: Decimal | None
    provider: str
    adapter_version: str
    fetched_at: datetime

    def json_dict(self) -> dict[str, Any]:
        value = asdict(self)
        value["data_date"] = self.data_date.isoformat()
        value["fetched_at"] = self.fetched_at.isoformat()
        for key in ("open", "high", "low", "close", "volume"):
            value[key] = None if value[key] is None else str(value[key])
        return value


def normalize_quote(
    *,
    product_code: str,
    market: str,
    trade_date: str | date,
    raw: dict[str, Any],
    provider: str,
    adapter_version: str = "1",
) -> NormalizedQuote:
    data_date = trade_date if isinstance(trade_date, date) else date.fromisoformat(str(trade_date))
    close = _decimal(raw.get("close"))
    if close is None or close <= 0:
        raise ValueError("close must be a positive decimal")
    return NormalizedQuote(
        product_code=product_code.strip().upper(),
        market=market.strip().upper(),
        data_date=data_date,
        open=_decimal(raw.get("open")),
        high=_decimal(raw.get("high")),
        low=_decimal(raw.get("low")),
        close=close,
        volume=_decimal(raw.get("volume")),
        provider=provider.strip().upper(),
        adapter_version=adapter_version,
        fetched_at=datetime.now(timezone.utc),
    )


def infer_a_share_market(code: str) -> str:
    normalized = code.strip()
    if normalized.startswith(("6", "5")):
        return "SSE"
    if normalized.startswith(("8", "4", "9")):
        return "BSE"
    return "SZSE"


def fetch_realtime_stock_quote(code: str, market: str, opener=urlopen, clock=None) -> dict[str, Any]:
    normalized_code = code.strip()
    normalized_market = market.strip().upper()
    if len(normalized_code) != 6 or not normalized_code.isdigit():
        raise ValueError("股票代码必须是 6 位数字")
    prefix = {"SSE": "sh", "BSE": "bj"}.get(normalized_market, "sz")
    errors: list[str] = []
    try:
        with opener(f"http://qt.gtimg.cn/q={prefix}{normalized_code}", timeout=5) as response:
            payload = response.read().decode("gbk")
        fields = payload.split('"')[1].split("~")
        latest_price = _decimal(fields[3])
        if latest_price is None or latest_price <= 0 or len(fields) <= 49:
            raise ValueError("实时行情缺少有效价格或时间")
        quote_at = datetime.strptime(fields[30], "%Y%m%d%H%M%S").replace(
            tzinfo=ZoneInfo("Asia/Shanghai")
        )
        metrics = {
            "previousClose": _decimal(fields[4]),
            "openPrice": _decimal(fields[5]),
            "changeAmount": _decimal(fields[31]),
            "changePercent": _decimal(fields[32]),
            "highPrice": _decimal(fields[33]),
            "lowPrice": _decimal(fields[34]),
            # 腾讯 A 股成交量单位为手、成交额单位为万元，统一换算为股和元。
            "volume": _multiply(_decimal(fields[36]), Decimal("100")),
            "amount": _multiply(_decimal(fields[37]), Decimal("10000")),
            "turnoverRate": _decimal(fields[38]),
            "amplitude": _decimal(fields[43]),
            "volumeRatio": _decimal(fields[49]),
        }
    except Exception as exc:
        errors.append(f"TENCENT: {exc}")
    else:
        return _realtime_quote_result(
            normalized_code, normalized_market, latest_price, quote_at, "TENCENT", [], clock, metrics
        )

    try:
        request = Request(
            f"http://hq.sinajs.cn/list={prefix}{normalized_code}",
            headers={"Referer": "https://finance.sina.com.cn/"},
        )
        with opener(request, timeout=5) as response:
            payload = response.read().decode("gbk")
        fields = payload.split('"')[1].split(",")
        latest_price = _decimal(fields[3])
        if latest_price is None or latest_price <= 0 or len(fields) <= 31:
            raise ValueError("实时行情缺少有效价格或时间")
        quote_at = datetime.strptime(
            f"{fields[30]} {fields[31]}", "%Y-%m-%d %H:%M:%S"
        ).replace(tzinfo=ZoneInfo("Asia/Shanghai"))
        previous_close = _decimal(fields[2])
        high_price = _decimal(fields[4])
        low_price = _decimal(fields[5])
        metrics = {
            "previousClose": previous_close,
            "openPrice": _decimal(fields[1]),
            "changeAmount": latest_price - previous_close if previous_close is not None else None,
            "changePercent": _percentage(latest_price - previous_close, previous_close),
            "highPrice": high_price,
            "lowPrice": low_price,
            "volume": _decimal(fields[8]),
            "amount": _decimal(fields[9]),
            "turnoverRate": None,
            "amplitude": _percentage(high_price - low_price, previous_close)
            if high_price is not None and low_price is not None else None,
            "volumeRatio": None,
        }
    except Exception as exc:
        errors.append(f"SINA: {exc}")
    else:
        return _realtime_quote_result(
            normalized_code, normalized_market, latest_price, quote_at, "SINA", errors, clock, metrics
        )

    raise ProviderUnavailable("；".join(errors))


def _realtime_quote_result(
    code: str,
    market: str,
    latest_price: Decimal,
    quote_at: datetime,
    provider: str,
    warnings: list[str],
    clock=None,
    metrics: dict[str, Decimal | None] | None = None,
) -> dict[str, Any]:
    fetched_at = clock() if clock is not None else datetime.now(ZoneInfo("Asia/Shanghai"))
    result = {
        "code": code,
        "market": market,
        "latestPrice": str(latest_price),
        "dataDate": quote_at.date().isoformat(),
        "fetchedAt": fetched_at.isoformat(),
        "provider": provider,
        "warnings": warnings,
    }
    result.update({key: _decimal_string(value) for key, value in (metrics or {}).items()})
    return result


def normalize_resolved_product(
    *,
    product_type: str,
    code: str,
    name: str,
    market: str,
    currency: str,
    provider: str,
    latest_price: Any = None,
    data_date: str | date | None = None,
    warnings: list[str] | None = None,
    previous_close: Any = None,
    change_amount: Any = None,
    change_percent: Any = None,
    open_price: Any = None,
    high_price: Any = None,
    low_price: Any = None,
    volume: Any = None,
    amount: Any = None,
    turnover_rate: Any = None,
    volume_ratio: Any = None,
    amplitude: Any = None,
) -> dict[str, Any]:
    price = _decimal(latest_price)
    return {
        "productType": product_type.strip().upper(),
        "code": code.strip().upper(),
        "name": name.strip(),
        "market": market.strip().upper(),
        "currency": currency.strip().upper(),
        "provider": provider.strip().upper(),
        "dataDate": None if data_date is None else str(data_date)[:10],
        "latestPrice": None if price is None else str(price),
        "previousClose": _decimal_string(_decimal(previous_close)),
        "changeAmount": _decimal_string(_decimal(change_amount)),
        "changePercent": _decimal_string(_decimal(change_percent)),
        "openPrice": _decimal_string(_decimal(open_price)),
        "highPrice": _decimal_string(_decimal(high_price)),
        "lowPrice": _decimal_string(_decimal(low_price)),
        "volume": _decimal_string(_decimal(volume)),
        "amount": _decimal_string(_decimal(amount)),
        "turnoverRate": _decimal_string(_decimal(turnover_rate)),
        "volumeRatio": _decimal_string(_decimal(volume_ratio)),
        "amplitude": _decimal_string(_decimal(amplitude)),
        "warnings": list(warnings or []),
    }


def resolve_product_metadata(product_type: str, code: str, ak_module: Any = None) -> dict[str, Any]:
    normalized_type = product_type.strip().upper()
    normalized_code = code.strip().upper()
    if normalized_type not in {"STOCK", "MUTUAL_FUND"}:
        raise ValueError("product_type must be STOCK or MUTUAL_FUND")
    if len(normalized_code) != 6 or not normalized_code.isdigit():
        raise ValueError("股票或基金代码必须是 6 位数字")
    if ak_module is None:
        try:
            import akshare as ak_module
        except ImportError as exc:
            raise ProviderUnavailable("AKShare is not installed") from exc

    warnings: list[str] = []
    fund_records: list[dict[str, Any]] = []
    if normalized_type == "STOCK":
        records = ak_module.stock_info_a_code_name().to_dict("records")
        match = next((item for item in records if str(item.get("code", "")).zfill(6) == normalized_code), None)
        if match is None:
            raise ProviderUnavailable(f"AKSHARE: 未找到股票代码 {normalized_code}")
        name = str(match.get("name", "")).strip()
        market = infer_a_share_market(normalized_code)
        try:
            frame = ak_module.stock_zh_a_hist(
                symbol=normalized_code,
                period="daily",
                start_date=(date.today().replace(day=1)).strftime("%Y%m%d"),
                end_date=date.today().strftime("%Y%m%d"),
                adjust="qfq",
            )
            quotes = _frame_to_quotes(frame, normalized_code, market, "AKSHARE")
        except Exception as exc:
            quotes = []
            warnings.append(f"AKSHARE 行情获取失败: {exc}")
    else:
        records = ak_module.fund_name_em().to_dict("records")
        match = next((item for item in records if str(item.get("基金代码", "")).zfill(6) == normalized_code), None)
        if match is None:
            raise ProviderUnavailable(f"AKSHARE: 未找到基金代码 {normalized_code}")
        name = str(match.get("基金简称", "")).strip()
        market = "FUND_CN"
        try:
            frame = ak_module.fund_open_fund_info_em(symbol=normalized_code, indicator="单位净值走势")
            fund_records = frame.to_dict("records")
            quotes = _frame_to_quotes(frame, normalized_code, market, "AKSHARE")
        except Exception as exc:
            quotes = []
            warnings.append(f"AKSHARE 净值获取失败: {exc}")

    latest = max(quotes, key=lambda item: item.data_date) if quotes else None
    previous = max(
        (item for item in quotes if latest is not None and item.data_date < latest.data_date),
        key=lambda item: item.data_date,
        default=None,
    )
    daily_growth = None
    if latest is not None and fund_records:
        latest_record = next(
            (item for item in fund_records
             if str(item.get("净值日期", item.get("日期", "")))[:10] == latest.data_date.isoformat()),
            None,
        )
        if latest_record is not None:
            daily_growth = latest_record.get("日增长率")
    return normalize_resolved_product(
        product_type=normalized_type,
        code=normalized_code,
        name=name,
        market=market,
        currency="CNY",
        provider="AKSHARE",
        latest_price=None if latest is None else latest.close,
        data_date=None if latest is None else latest.data_date,
        previous_close=None if previous is None else previous.close,
        change_amount=None if latest is None or previous is None else latest.close - previous.close,
        change_percent=daily_growth if daily_growth is not None else (
            _percentage(latest.close - previous.close, previous.close)
            if latest is not None and previous is not None else None
        ),
        open_price=None if latest is None else latest.open,
        high_price=None if latest is None else latest.high,
        low_price=None if latest is None else latest.low,
        volume=None if latest is None else latest.volume,
        warnings=warnings,
    )


class ProviderUnavailable(RuntimeError):
    pass


def fetch_stock_fundamentals(code: str, ak_module: Any = None) -> tuple[list[dict[str, Any]], list[str]]:
    """Return a small, provider-neutral set of financial periods for long-horizon analysis."""
    if ak_module is None:
        try:
            import akshare as ak_module
        except ImportError as exc:
            raise ProviderUnavailable("AKShare is not installed") from exc
    warnings: list[str] = []
    try:
        frame = ak_module.stock_financial_analysis_indicator(
            symbol=code, start_year=str(max(2000, date.today().year - 5))
        )
        rows = frame.to_dict("records")
    except Exception as exc:
        raise ProviderUnavailable(f"AKSHARE 财务指标获取失败: {exc}") from exc

    def value(row: dict[str, Any], *names: str) -> str | None:
        for name in names:
            decimal_value = _decimal(row.get(name))
            if decimal_value is not None:
                return str(decimal_value)
        return None

    def period(row: dict[str, Any]) -> str:
        return str(row.get("日期") or row.get("报告期") or row.get("date") or "")[:10]

    rows = [row for row in rows if period(row)]
    rows.sort(key=period, reverse=True)
    annual = [row for row in rows if period(row).endswith("12-31")]
    selected = (annual if len(annual) >= 3 else rows)[:5]
    periods: list[dict[str, Any]] = []
    for row in selected:
        periods.append({
            "period": period(row),
            "roe": value(row, "加权净资产收益率(%)", "净资产收益率(%)"),
            "grossMargin": value(row, "销售毛利率(%)", "主营业务利润率(%)"),
            "netMargin": value(row, "销售净利率(%)", "总资产净利润率(%)"),
            "revenueGrowth": value(row, "主营业务收入增长率(%)", "营业收入增长率(%)"),
            "netProfitGrowth": value(row, "净利润增长率(%)"),
            "cashToProfit": value(row, "经营现金净流量与净利润的比率(%)"),
            "debtRatio": value(row, "资产负债率(%)"),
            "currentRatio": value(row, "流动比率"),
        })

    try:
        valuation_rows = ak_module.stock_a_indicator_lg(symbol=code).to_dict("records")
        if valuation_rows:
            latest = valuation_rows[-1]
            pe_values = [_decimal(item.get("pe_ttm") or item.get("pe")) for item in valuation_rows]
            pe_values = sorted(item for item in pe_values if item is not None and item > 0)
            current_pe = _decimal(latest.get("pe_ttm") or latest.get("pe"))
            percentile = None
            if current_pe is not None and pe_values:
                percentile = Decimal(sum(item <= current_pe for item in pe_values)) / Decimal(len(pe_values)) * Decimal("100")
            for item in periods:
                item["pe"] = _decimal_string(current_pe)
                item["pb"] = _decimal_string(_decimal(latest.get("pb")))
                item["dividendYield"] = _decimal_string(_decimal(latest.get("dv_ttm") or latest.get("dv_ratio")))
                item["pePercentile"] = _decimal_string(percentile)
    except Exception as exc:
        warnings.append(f"AKSHARE 估值数据获取失败: {exc}")
    return periods, warnings


class MarketDataProvider:
    name = "BASE"

    def supports(self, market: str, product_type: str) -> bool:
        raise NotImplementedError

    def daily_quotes(self, code: str, market: str, product_type: str, start_date: date, end_date: date) -> list[NormalizedQuote]:
        raise NotImplementedError


class TencentHistoryProvider(MarketDataProvider):
    name = "TENCENT"

    def __init__(self, opener=urlopen):
        self.opener = opener

    def supports(self, market: str, product_type: str) -> bool:
        return market.upper() in {"SSE", "SZSE", "BSE"} and product_type.upper() in {"STOCK", "ETF"}

    def daily_quotes(self, code: str, market: str, product_type: str,
                     start_date: date, end_date: date) -> list[NormalizedQuote]:
        market_prefix = {"SSE": "sh", "SZSE": "sz", "BSE": "bj"}.get(market.upper())
        if market_prefix is None:
            raise ProviderUnavailable(f"unsupported market: {market}")
        symbol = f"{market_prefix}{code.strip()}"
        url = (
            "http://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param="
            f"{symbol},day,{start_date.isoformat()},{end_date.isoformat()},1000,qfq"
        )
        with self.opener(url, timeout=8) as response:
            payload = json.loads(response.read().decode("utf-8"))
        rows = ((payload.get("data") or {}).get(symbol) or {}).get("qfqday") or []
        records = []
        for row in rows:
            if len(row) < 6:
                continue
            trade_date, open_price, close_price, high_price, low_price, volume = row[:6]
            records.append(normalize_quote(
                product_code=code,
                market=market,
                trade_date=trade_date,
                raw={
                    "open": open_price,
                    "high": high_price,
                    "low": low_price,
                    "close": close_price,
                    # 腾讯日线成交量单位为手，统一转换为股。
                    "volume": str(Decimal(str(volume)) * Decimal("100")),
                },
                provider=self.name,
            ))
        return [item for item in records if start_date <= item.data_date <= end_date]


class AkshareProvider(MarketDataProvider):
    name = "AKSHARE"

    def supports(self, market: str, product_type: str) -> bool:
        return market.upper() in {"SSE", "SZSE", "BSE", "HKEX", "NYSE", "NASDAQ", "AMEX", "FUND_CN"}

    def daily_quotes(self, code: str, market: str, product_type: str, start_date: date, end_date: date) -> list[NormalizedQuote]:
        try:
            import akshare as ak
        except ImportError as exc:
            raise ProviderUnavailable("AKShare is not installed") from exc
        market = market.upper()
        start = start_date.strftime("%Y%m%d")
        end = end_date.strftime("%Y%m%d")
        if product_type.upper() == "ETF" and market in {"SSE", "SZSE", "BSE"}:
            frame = ak.fund_etf_hist_em(symbol=code, period="daily", start_date=start, end_date=end, adjust="qfq")
        elif market in {"SSE", "SZSE", "BSE"}:
            frame = ak.stock_zh_a_hist(symbol=code, period="daily", start_date=start, end_date=end, adjust="qfq")
        elif market == "HKEX":
            frame = ak.stock_hk_hist(symbol=code, period="daily", start_date=start, end_date=end, adjust="")
        elif market in {"NYSE", "NASDAQ", "AMEX"}:
            frame = ak.stock_us_hist(symbol=akshare_us_symbol(code, market), period="daily", start_date=start, end_date=end, adjust="")
        else:
            frame = ak.fund_open_fund_info_em(symbol=code, indicator="单位净值走势")
        return [item for item in _frame_to_quotes(frame, code, market, self.name)
                if start_date <= item.data_date <= end_date]


class BaostockProvider(MarketDataProvider):
    name = "BAOSTOCK"

    def supports(self, market: str, product_type: str) -> bool:
        return market.upper() in {"SSE", "SZSE", "BSE"} and product_type.upper() in {"STOCK", "ETF"}

    def daily_quotes(self, code: str, market: str, product_type: str, start_date: date, end_date: date) -> list[NormalizedQuote]:
        try:
            import baostock as bs
        except ImportError as exc:
            raise ProviderUnavailable("BaoStock is not installed") from exc
        prefix = "sh" if market.upper() == "SSE" else "sz"
        login = bs.login()
        if login.error_code != "0":
            raise ProviderUnavailable(login.error_msg)
        try:
            result = bs.query_history_k_data_plus(
                f"{prefix}.{code}", "date,open,high,low,close,volume",
                start_date=start_date.isoformat(), end_date=end_date.isoformat(), frequency="d", adjustflag="2"
            )
            rows = []
            while result.error_code == "0" and result.next():
                rows.append(dict(zip(result.fields, result.get_row_data())))
            return [normalize_quote(product_code=code, market=market, trade_date=row["date"], raw=row, provider=self.name)
                    for row in rows]
        finally:
            bs.logout()


class TushareProvider(MarketDataProvider):
    name = "TUSHARE"

    def supports(self, market: str, product_type: str) -> bool:
        return bool(os.getenv("TUSHARE_TOKEN"))

    def daily_quotes(self, code: str, market: str, product_type: str, start_date: date, end_date: date) -> list[NormalizedQuote]:
        try:
            import tushare as ts
        except ImportError as exc:
            raise ProviderUnavailable("Tushare is not installed") from exc
        token = os.getenv("TUSHARE_TOKEN")
        if not token:
            raise ProviderUnavailable("TUSHARE_TOKEN is not configured")
        pro = ts.pro_api(token)
        market = market.upper()
        suffix = {"SSE": ".SH", "SZSE": ".SZ", "BSE": ".BJ", "HKEX": ".HK"}.get(market, "")
        kwargs = {"ts_code": code + suffix, "start_date": start_date.strftime("%Y%m%d"), "end_date": end_date.strftime("%Y%m%d")}
        if market == "HKEX":
            frame = pro.hk_daily(**kwargs)
        elif market in {"NYSE", "NASDAQ", "AMEX"}:
            frame = pro.us_daily(ts_code=code, start_date=kwargs["start_date"], end_date=kwargs["end_date"])
        else:
            frame = pro.daily(**kwargs)
        return _frame_to_quotes(frame, code, market, self.name)


class ProviderRegistry:
    def __init__(self, providers: Iterable[MarketDataProvider] | None = None):
        self.providers = list(providers or (
            TencentHistoryProvider(), AkshareProvider(), BaostockProvider(), TushareProvider()
        ))

    def daily_quotes(self, code: str, market: str, product_type: str, start_date: date, end_date: date):
        warnings: list[str] = []
        for provider in self.providers:
            if not provider.supports(market, product_type):
                continue
            try:
                records = provider.daily_quotes(code, market, product_type, start_date, end_date)
                if records:
                    return records, warnings
                warnings.append(f"{provider.name}: no records")
            except Exception as exc:
                warnings.append(f"{provider.name}: {exc}")
        raise ProviderUnavailable("; ".join(warnings) or "no provider supports this product")


def akshare_fx_rates(base_currency: str, quote_currency: str, start_date: date, end_date: date) -> list[dict[str, Any]]:
    try:
        import akshare as ak
    except ImportError as exc:
        raise ProviderUnavailable("AKShare is not installed") from exc
    symbol = f"{base_currency.strip().upper()}{quote_currency.strip().upper()}"
    frame = ak.forex_hist_em(symbol=symbol)
    aliases = {"日期": "date", "收盘": "close"}
    records = []
    fetched_at = datetime.now(timezone.utc).isoformat()
    for original in frame.to_dict("records"):
        row = {aliases.get(str(key), str(key).lower()): value for key, value in original.items()}
        data_date = date.fromisoformat(str(row["date"])[:10])
        rate = _decimal(row.get("close"))
        if start_date <= data_date <= end_date and rate is not None and rate > 0:
            records.append({"base_currency": base_currency.upper(), "quote_currency": quote_currency.upper(),
                            "data_date": data_date.isoformat(), "rate": str(rate), "provider": "AKSHARE",
                            "adapter_version": "1", "fetched_at": fetched_at})
    return records


def fetch_a_share_trade_calendar(year: int, ak_module: Any = None) -> list[str]:
    if ak_module is None:
        try:
            import akshare as ak_module
        except ImportError as exc:
            raise ProviderUnavailable("AKShare is not installed") from exc
    try:
        frame = ak_module.tool_trade_date_hist_sina()
    except Exception as exc:
        raise ProviderUnavailable(f"AKSHARE 交易日历获取失败: {exc}") from exc
    dates = []
    for row in frame.to_dict("records"):
        value = row.get("trade_date", row.get("交易日", row.get("日期")))
        if value is None:
            continue
        trading_date = date.fromisoformat(str(value)[:10])
        if trading_date.year == year:
            dates.append(trading_date.isoformat())
    result = sorted(set(dates))
    if not result:
        raise ProviderUnavailable(f"AKSHARE 未返回 {year} 年 A 股交易日历")
    return result


def _decimal(value: Any) -> Decimal | None:
    if value is None or str(value).strip() in {"", "nan", "None"}:
        return None
    return Decimal(str(value).strip())


def _decimal_string(value: Decimal | None) -> str | None:
    return None if value is None else str(value)


def _multiply(value: Decimal | None, factor: Decimal) -> Decimal | None:
    return None if value is None else value * factor


def _percentage(numerator: Decimal, denominator: Decimal | None) -> Decimal | None:
    if denominator is None or denominator == 0:
        return None
    return (numerator / denominator * Decimal("100")).quantize(Decimal("0.0001"))


def akshare_us_symbol(code: str, market: str) -> str:
    normalized = code.strip().upper()
    if normalized[:3].isdigit() and len(normalized) > 4 and normalized[3] == ".":
        return normalized
    prefix = {"NASDAQ": "105", "NYSE": "106", "AMEX": "107"}.get(market.strip().upper())
    if prefix is None:
        raise ValueError(f"unsupported US market: {market}")
    return f"{prefix}.{normalized}"


def _frame_to_quotes(frame: Any, code: str, market: str, provider: str) -> list[NormalizedQuote]:
    aliases = {
        "日期": "date", "净值日期": "date", "trade_date": "date",
        "开盘": "open", "最高": "high", "最低": "low", "收盘": "close",
        "单位净值": "close", "成交量": "volume", "vol": "volume",
    }
    records: list[NormalizedQuote] = []
    for original in frame.to_dict("records"):
        row = {aliases.get(str(key), str(key).lower()): value for key, value in original.items()}
        records.append(normalize_quote(product_code=code, market=market, trade_date=str(row["date"])[:10], raw=row, provider=provider))
    return records
