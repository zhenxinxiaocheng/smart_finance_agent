from __future__ import annotations

import json
import os
import re
import threading
from dataclasses import asdict, dataclass
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal
from functools import lru_cache
from time import monotonic
from typing import Any, Iterable, Sequence
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from weakref import WeakKeyDictionary
from zoneinfo import ZoneInfo

from .data_quality.models import AdjustType, DataSnapshotContext, ProductType
from .fund_returns import attach_fund_returns
from .benchmark_registry import (
    benchmark_provider_symbol,
    fx_provider_config,
    normalize_benchmark_name,
    resolve_fund_benchmark,
)
from .fund_classification import FundClassification, classify_fund_type
from .index_registry import INDEX_WATCHLIST_REGISTRY
from .strategy_config import load_strategy_config


STRATEGY = load_strategy_config()


@dataclass
class _FundSnapshotCacheEntry:
    expires_at: float = 0
    records: tuple[dict[str, Any], ...] | None = None
    error_type: str | None = None
    error_message: str | None = None
    loading: bool = False


_FUND_SNAPSHOT_CACHE: WeakKeyDictionary[Any, _FundSnapshotCacheEntry] = WeakKeyDictionary()
_FUND_CATALOG_CACHE: WeakKeyDictionary[Any, _FundSnapshotCacheEntry] = WeakKeyDictionary()
_FUND_SNAPSHOT_CACHE_CONDITION = threading.Condition(threading.RLock())


def _provider_integer(name: str) -> int:
    return STRATEGY.integer(f"providers.{name}")


def _market_zone() -> ZoneInfo:
    return ZoneInfo(str(STRATEGY.value("providers.market_timezone")))


def _clear_fund_snapshot_cache() -> None:
    """Clear the process cache so provider tests do not share global state."""
    with _FUND_SNAPSHOT_CACHE_CONDITION:
        _FUND_SNAPSHOT_CACHE.clear()
        _FUND_CATALOG_CACHE.clear()
        _FUND_SNAPSHOT_CACHE_CONDITION.notify_all()


def _cached_fund_records(
    cache: WeakKeyDictionary[Any, _FundSnapshotCacheEntry],
    ak_module: Any,
    loader,
    ttl_ms: int,
    clock=None,
) -> tuple[dict[str, Any], ...]:
    cache_clock = monotonic if clock is None else clock
    with _FUND_SNAPSHOT_CACHE_CONDITION:
        entry = cache.get(ak_module)
        if entry is None:
            entry = _FundSnapshotCacheEntry()
            cache[ak_module] = entry
        while True:
            if entry.expires_at > cache_clock():
                if entry.error_type is not None:
                    raise ProviderUnavailable(
                        f"{entry.error_type}: {entry.error_message or ''}"
                    ) from None
                return entry.records or ()
            if not entry.loading:
                entry.loading = True
                break
            _FUND_SNAPSHOT_CACHE_CONDITION.wait()

    records: tuple[dict[str, Any], ...] | None = None
    error_type: str | None = None
    error_message: str | None = None
    try:
        records = tuple(loader().to_dict("records"))
    except Exception as exc:
        error_type = type(exc).__name__
        error_message = str(exc)

    with _FUND_SNAPSHOT_CACHE_CONDITION:
        entry.records = records
        entry.error_type = error_type
        entry.error_message = error_message
        entry.expires_at = cache_clock() + ttl_ms / 1000
        entry.loading = False
        _FUND_SNAPSHOT_CACHE_CONDITION.notify_all()

    if error_type is not None:
        raise ProviderUnavailable(f"{error_type}: {error_message or ''}") from None
    return records or ()


def _fund_snapshot_records(ak_module: Any, clock=None) -> tuple[dict[str, Any], ...]:
    return _cached_fund_records(
        _FUND_SNAPSHOT_CACHE,
        ak_module,
        ak_module.fund_open_fund_daily_em,
        _provider_integer("fund_snapshot_cache_ttl_ms"),
        clock,
    )


def _fund_catalog_records(ak_module: Any, clock=None) -> tuple[dict[str, Any], ...]:
    return _cached_fund_records(
        _FUND_CATALOG_CACHE,
        ak_module,
        ak_module.fund_name_em,
        _provider_integer("fund_snapshot_cache_ttl_ms"),
        clock,
    )


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
    total_return_index: Decimal | None = None

    def json_dict(self) -> dict[str, Any]:
        value = asdict(self)
        value["data_date"] = self.data_date.isoformat()
        value["fetched_at"] = self.fetched_at.isoformat()
        for key in ("open", "high", "low", "close", "volume", "total_return_index"):
            value[key] = None if value[key] is None else str(value[key])
        return value


@dataclass(frozen=True)
class ProviderBatch:
    product_type: ProductType
    code: str
    market: str
    frequency: str
    adjust_type: AdjustType
    provider: str
    adapter_version: str
    fetched_at: datetime
    records: tuple[NormalizedQuote, ...]
    warnings: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        try:
            product_type = self.product_type if isinstance(self.product_type, ProductType) else ProductType(self.product_type)
        except (TypeError, ValueError) as error:
            raise ValueError("product_type must be STOCK or MUTUAL_FUND") from error
        try:
            adjust_type = self.adjust_type if isinstance(self.adjust_type, AdjustType) else AdjustType(self.adjust_type)
        except (TypeError, ValueError) as error:
            raise ValueError("adjust_type must be QFQ, HFQ, or NONE") from error
        object.__setattr__(self, "product_type", product_type)
        object.__setattr__(self, "adjust_type", adjust_type)
        for field_name in ("code", "market", "provider", "adapter_version"):
            value = getattr(self, field_name)
            if not isinstance(value, str) or not value.strip():
                raise ValueError(f"{field_name} must be non-blank")
        if self.frequency != "DAY":
            raise ValueError("frequency must be DAY")
        if self.fetched_at.tzinfo is None or self.fetched_at.utcoffset() is None:
            raise ValueError("fetched_at must be timezone-aware")
        records = tuple(self.records)
        warnings = tuple(self.warnings)
        object.__setattr__(self, "records", records)
        object.__setattr__(self, "warnings", warnings)
        if not records:
            raise ValueError("records must be non-empty")
        if product_type is ProductType.MUTUAL_FUND and adjust_type is not AdjustType.NONE:
            raise ValueError("MUTUAL_FUND batches require adjustType=NONE")
        for warning in warnings:
            if not isinstance(warning, str) or not warning.strip():
                raise ValueError("warnings must contain non-blank strings")
        for record in records:
            if not isinstance(record, NormalizedQuote):
                raise ValueError("records must contain NormalizedQuote values")
            expected = {
                "product_code": self.code,
                "market": self.market,
                "provider": self.provider,
                "adapter_version": self.adapter_version,
            }
            for field_name, expected_value in expected.items():
                if getattr(record, field_name) != expected_value:
                    raise ValueError(f"record {field_name.removeprefix('product_')} conflicts with batch")
            for field_name in ("open", "high", "low", "close", "volume"):
                value = getattr(record, field_name)
                if value is not None and (isinstance(value, bool) or not isinstance(value, Decimal) or not value.is_finite()):
                    raise ValueError(f"record {field_name} must be a finite decimal")

    def to_snapshot_rows(self) -> list[dict[str, Any]]:
        rows: list[dict[str, Any]] = []
        for record in self.records:
            is_fund = self.product_type is ProductType.MUTUAL_FUND
            rows.append({
                "product_code": self.code,
                "product_type": self.product_type.value,
                "market": self.market,
                "frequency": self.frequency,
                "adjust_type": self.adjust_type.value,
                "provider": self.provider,
                "adapter_version": self.adapter_version,
                "data_date": record.data_date,
                "observed_at": self.fetched_at,
                "open": None if is_fund else record.open,
                "high": None if is_fund else record.high,
                "low": None if is_fund else record.low,
                "close": None if is_fund else record.close,
                "volume": None if is_fund else record.volume,
                "nav": record.close if is_fund else None,
                "trading_status": None,
                # Keep UNIT_NAV unchanged; the factor carries reinvested returns.
                "adjustment_factor": ((record.total_return_index / record.close).quantize(Decimal("0.0000000001"))
                                      if is_fund and record.total_return_index is not None else None),
                "corporate_action_reference": None,
                "nav_type": "UNIT_NAV" if is_fund else None,
                "estimated": False if is_fund else None,
            })
        return rows

    def snapshot_context(self, start_date: date, end_date: date) -> DataSnapshotContext:
        return DataSnapshotContext(
            product_type=self.product_type,
            market=self.market,
            code=self.code,
            frequency=self.frequency,
            adjust_type=self.adjust_type,
            provider=self.provider,
            adapter_version=self.adapter_version,
            requested_start_date=start_date,
            requested_end_date=end_date,
            fetched_at=self.fetched_at,
        )


def normalize_quote(
    *,
    product_code: str,
    market: str,
    trade_date: str | date,
    raw: dict[str, Any],
    provider: str,
    adapter_version: str = "1",
    fetched_at: datetime | None = None,
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
        fetched_at=fetched_at if fetched_at is not None else datetime.now(timezone.utc),
        total_return_index=_decimal(raw.get("total_return_index")),
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
        with opener(f"http://qt.gtimg.cn/q={prefix}{normalized_code}",
                    timeout=_provider_integer("realtime_http_timeout_seconds")) as response:
            payload = response.read().decode("gbk")
        fields = payload.split('"')[1].split("~")
        latest_price = _decimal(fields[3])
        if latest_price is None or latest_price <= 0 or len(fields) <= 49:
            raise ValueError("实时行情缺少有效价格或时间")
        quote_at = datetime.strptime(fields[30], "%Y%m%d%H%M%S").replace(
            tzinfo=_market_zone()
        )
        metrics = {
            "previousClose": _decimal(fields[4]),
            "openPrice": _decimal(fields[5]),
            "changeAmount": _decimal(fields[31]),
            "changePercent": _decimal(fields[32]),
            "highPrice": _decimal(fields[33]),
            "lowPrice": _decimal(fields[34]),
            # 腾讯 A 股成交量单位为手、成交额单位为万元，统一换算为股和元。
            "volume": _multiply(_decimal(fields[36]), Decimal(
                _provider_integer("tencent_volume_lot_size"))),
            "amount": _multiply(_decimal(fields[37]), Decimal(
                _provider_integer("tencent_amount_unit"))),
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
        with opener(request, timeout=_provider_integer("realtime_http_timeout_seconds")) as response:
            payload = response.read().decode("gbk")
        fields = payload.split('"')[1].split(",")
        latest_price = _decimal(fields[3])
        if latest_price is None or latest_price <= 0 or len(fields) <= 31:
            raise ValueError("实时行情缺少有效价格或时间")
        quote_at = datetime.strptime(
            f"{fields[30]} {fields[31]}", "%Y-%m-%d %H:%M:%S"
        ).replace(tzinfo=_market_zone())
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


def _index_quote(raw: dict[str, Any], scope: str) -> dict[str, Any] | None:
    raw_code = _first_text(raw, "代码", "code", "symbol")
    name = _first_text(raw, "名称", "name")
    latest = _decimal(_first_text(raw, "最新价", "close", "latest"))
    if not raw_code or not name or latest is None:
        return None
    if scope == "CN":
        digits = "".join(character for character in raw_code if character.isdigit())
        if len(digits) < 6:
            return None
        index_code = f"CN_INDEX:{digits[-6:]}"
        market = "CN"
        previous_close = _decimal(_first_text(raw, "昨收", "昨收价", "previousClose"))
        open_price = _decimal(_first_text(raw, "今开", "开盘价", "open"))
        high_price = _decimal(_first_text(raw, "最高", "最高价", "high"))
        low_price = _decimal(_first_text(raw, "最低", "最低价", "low"))
        provider = "AKSHARE_SINA_CN_INDEX"
        data_time = _first_text(raw, "时间", "最新行情时间", "dataTime") or None
    else:
        normalized_code = raw_code.strip().upper()
        if not re.fullmatch(r"[A-Z0-9._-]{1,20}", normalized_code):
            return None
        index_code = f"GLOBAL_INDEX:{normalized_code}"
        market = "GLOBAL"
        previous_close = _decimal(_first_text(raw, "昨收价", "昨收", "previousClose"))
        open_price = _decimal(_first_text(raw, "开盘价", "今开", "open"))
        high_price = _decimal(_first_text(raw, "最高价", "最高", "high"))
        low_price = _decimal(_first_text(raw, "最低价", "最低", "low"))
        provider = "AKSHARE_EASTMONEY_GLOBAL_INDEX"
        data_time = _first_text(raw, "最新行情时间", "时间", "dataTime") or None
    change_amount = _decimal(_first_text(raw, "涨跌额", "changeAmount"))
    change_percent = _decimal(_first_text(raw, "涨跌幅", "changePercent"))
    if change_amount is None and previous_close is not None:
        change_amount = latest - previous_close
    if change_percent is None and change_amount is not None and previous_close:
        change_percent = change_amount / previous_close * Decimal("100")
    return {
        "indexCode": index_code,
        "name": name,
        "market": market,
        "latestPrice": _decimal_string(latest),
        "changePercent": _decimal_string(change_percent),
        "changeAmount": _decimal_string(change_amount),
        "previousClose": _decimal_string(previous_close),
        "openPrice": _decimal_string(open_price),
        "highPrice": _decimal_string(high_price),
        "lowPrice": _decimal_string(low_price),
        "dataTime": data_time,
        "fetchedAt": datetime.now(timezone.utc).isoformat(),
        "provider": provider,
    }


def _historical_index_quote(
    records: Sequence[dict[str, Any]],
    configured: dict[str, str],
) -> dict[str, Any] | None:
    usable = []
    for raw in records:
        close = _decimal(_first_text(raw, "close", "收盘"))
        if close is not None:
            usable.append((raw, close))
    if not usable:
        return None
    latest_raw, latest = usable[-1]
    previous_close = usable[-2][1] if len(usable) > 1 else None
    change_amount = latest - previous_close if previous_close is not None else None
    return {
        "indexCode": configured["indexCode"],
        "name": configured["name"],
        "market": configured["market"],
        "latestPrice": _decimal_string(latest),
        "changePercent": _decimal_string(_percentage(change_amount, previous_close))
        if change_amount is not None else None,
        "changeAmount": _decimal_string(change_amount),
        "previousClose": _decimal_string(previous_close),
        "openPrice": _decimal_string(_decimal(_first_text(latest_raw, "open", "开盘"))),
        "highPrice": _decimal_string(_decimal(_first_text(latest_raw, "high", "最高"))),
        "lowPrice": _decimal_string(_decimal(_first_text(latest_raw, "low", "最低"))),
        "dataTime": _first_text(latest_raw, "date", "日期") or None,
        "fetchedAt": datetime.now(timezone.utc).isoformat(),
        "provider": f"AKSHARE_{configured['provider']}",
    }


def _index_market_catalog(ak_module: Any = None) -> list[dict[str, Any]]:
    if ak_module is None:
        try:
            import akshare as ak_module
        except ImportError as exc:
            raise ProviderUnavailable("AKShare is not installed") from exc
    items: dict[str, dict[str, Any]] = {}
    errors: list[str] = []
    for scope, method_name in (
        ("CN", "stock_zh_index_spot_sina"),
        ("GLOBAL", "index_global_spot_em"),
    ):
        loader = getattr(ak_module, method_name, None)
        if loader is None:
            errors.append(f"{method_name}: 接口不可用")
            continue
        try:
            records = loader().to_dict("records")
        except Exception as exc:
            errors.append(f"{method_name}: {exc}")
            continue
        for raw in records:
            quote = _index_quote(raw, scope)
            if quote is not None:
                quote = INDEX_WATCHLIST_REGISTRY.enrich(quote)
                items.setdefault(quote["indexCode"], quote)
    for configured in INDEX_WATCHLIST_REGISTRY.fallback_quotes():
        if configured["indexCode"] in items:
            continue
        if configured["provider"] != "SINA_US_INDEX_HISTORY":
            errors.append(f"{configured['indexCode']}: 不支持的回退数据源")
            continue
        loader = getattr(ak_module, "index_us_stock_sina", None)
        if loader is None:
            errors.append("index_us_stock_sina: 接口不可用")
            continue
        try:
            records = loader(symbol=configured["symbol"]).to_dict("records")
            quote = _historical_index_quote(records, configured)
        except Exception as exc:
            errors.append(f"index_us_stock_sina({configured['symbol']}): {exc}")
            continue
        if quote is not None:
            items[quote["indexCode"]] = quote
    if not items:
        raise ProviderUnavailable("；".join(errors) or "AKSHARE 未返回指数行情")
    return list(items.values())


def fetch_index_quotes(index_codes: Sequence[str], ak_module: Any = None) -> list[dict[str, Any]]:
    requested = [str(code).strip().upper() for code in index_codes if str(code).strip()]
    if not requested:
        return []
    catalog = {item["indexCode"].upper(): item for item in _index_market_catalog(ak_module)}
    return [catalog[code] for code in requested if code in catalog]


def search_index_quotes(keyword: str, limit: int = 20, ak_module: Any = None) -> list[dict[str, Any]]:
    query = str(keyword or "").strip().casefold()
    if not query:
        return []
    matches = [
        item for item in _index_market_catalog(ak_module)
        if INDEX_WATCHLIST_REGISTRY.matches(item, query)
    ]
    matches.sort(key=lambda item: (
        not item["name"].casefold().startswith(query), item["market"], item["name"]
    ))
    return matches[:max(1, min(int(limit), 50))]


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
    fetched_at = clock() if clock is not None else datetime.now(_market_zone())
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
    inception_date: str | date | None = None,
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
    fund_type_raw: str | None = None,
    fund_category: str | None = None,
    classification_source: str | None = None,
    classification_version: str | None = None,
    benchmark_name: str | None = None,
    tracking_target: str | None = None,
    benchmark_code: str | None = None,
    benchmark_components: dict[str, float] | None = None,
    benchmark_resolution_status: str | None = None,
    benchmark_resolution_reason: str | None = None,
    benchmark_source_uri: str | None = None,
    benchmark_source_version: str | None = None,
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
        "inceptionDate": None if inception_date is None else str(inception_date)[:10],
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
        "fundTypeRaw": fund_type_raw,
        "fundCategory": fund_category,
        "classificationSource": classification_source,
        "classificationVersion": classification_version,
        "benchmarkName": benchmark_name,
        "trackingTarget": tracking_target,
        "benchmarkCode": benchmark_code,
        "benchmarkComponents": dict(benchmark_components or {}),
        "benchmarkResolutionStatus": benchmark_resolution_status,
        "benchmarkResolutionReason": benchmark_resolution_reason,
        "benchmarkSourceUri": benchmark_source_uri,
        "benchmarkSourceVersion": benchmark_source_version,
    }


def _provider_date(value: Any) -> date | None:
    if value is None:
        return None
    text = str(value).strip()
    if not text:
        return None
    digits = "".join(character for character in text if character.isdigit())
    if len(digits) >= 8:
        try:
            return date.fromisoformat(f"{digits[:4]}-{digits[4:6]}-{digits[6:8]}")
        except ValueError:
            return None
    try:
        return date.fromisoformat(text[:10])
    except ValueError:
        return None


def _stock_inception_date(ak_module: Any, code: str) -> date | None:
    provider = getattr(ak_module, "stock_individual_info_em", None)
    if provider is not None:
        try:
            records = provider(symbol=code).to_dict("records")
            for record in records:
                key = str(record.get("item", record.get("项目", ""))).strip()
                if key in {"上市时间", "上市日期", "list_date", "listing_date"}:
                    listing_date = _provider_date(record.get("value", record.get("值")))
                    if listing_date is not None:
                        return listing_date
        except Exception:
            pass

    exchange_sources = {
        "SZSE": (("stock_info_sz_name_code", "A股列表", "A股代码", "A股上市日期"),),
        "SSE": (
            ("stock_info_sh_name_code", "主板A股", "证券代码", "上市日期"),
            ("stock_info_sh_name_code", "科创板", "证券代码", "上市日期"),
        ),
        "BSE": (("stock_info_bj_name_code", None, "证券代码", "上市日期"),),
    }
    for provider_name, symbol, code_key, date_key in exchange_sources.get(infer_a_share_market(code), ()):
        provider = getattr(ak_module, provider_name, None)
        if provider is None:
            continue
        try:
            frame = provider() if symbol is None else provider(symbol=symbol)
            records = frame.to_dict("records")
        except Exception:
            continue
        for record in records:
            if str(record.get(code_key, "")).strip().zfill(6) == code:
                listing_date = _provider_date(record.get(date_key))
                if listing_date is not None:
                    return listing_date
    return None


def _normalized_index_name(value: Any) -> str:
    return normalize_benchmark_name(value)


def _benchmark_target_resolver(ak_module: Any):
    aliases: dict[str, str] = {}
    index_candidates: dict[str, set[str]] = {}
    loaded = False

    def load() -> None:
        nonlocal loaded
        if loaded:
            return
        loaded = True
        for provider_name in ("index_stock_info", "index_stock_info_sina"):
            provider = getattr(ak_module, provider_name, None)
            if provider is None:
                continue
            try:
                records = provider().to_dict("records")
            except Exception:
                continue
            for item in records:
                name = _first_text(
                    item, "display_name", "index_name", "指数名称", "指数简称", "name"
                )
                raw_code = _first_text(
                    item, "index_code", "symbol", "指数代码", "代码", "code"
                )
                digits = "".join(character for character in raw_code if character.isdigit())
                normalized_name = _normalized_index_name(name)
                if normalized_name and len(digits) >= 6:
                    index_candidates.setdefault(normalized_name, set()).add(digits[-6:])
        for name, codes in index_candidates.items():
            aliases[name] = f"CN_INDEX:{sorted(codes)[0]}"
        sge_provider = getattr(ak_module, "spot_symbol_table_sge", None)
        if sge_provider is not None:
            try:
                records = sge_provider().to_dict("records")
            except Exception:
                records = []
            for item in records:
                symbol = _first_text(item, "品种", "symbol", "代码", "code")
                normalized_name = _normalized_index_name(symbol)
                if normalized_name and symbol:
                    aliases.setdefault(normalized_name, f"SGE_SPOT:{symbol.upper()}")

    def resolve(value: str) -> str | None:
        load()
        return aliases.get(_normalized_index_name(value))

    return resolve


def _fund_benchmark_metadata(ak_module: Any, code: str) -> dict[str, Any]:
    overview_provider = getattr(ak_module, "fund_overview_em", None)
    if overview_provider is None:
        return {}
    records = overview_provider(symbol=code).to_dict("records")
    if not records:
        return {}
    overview = records[0]
    inception_date = _provider_date(
        _first_text(overview, "成立日期/规模", "成立日期", "inception_date")
    )
    benchmark_name = _first_text(overview, "业绩比较基准", "performance_benchmark")
    tracking_target = _first_text(overview, "跟踪标的", "tracking_target")
    if tracking_target.startswith("该基金无"):
        tracking_target = ""

    resolution = resolve_fund_benchmark(
        tracking_target,
        benchmark_name,
        _benchmark_target_resolver(ak_module),
    )

    result = {
        "inceptionDate": None if inception_date is None else inception_date.isoformat(),
        "benchmarkName": benchmark_name or None,
        "trackingTarget": tracking_target or None,
        "benchmarkSourceUri": f"https://fundf10.eastmoney.com/jbgk_{code}.html",
        "benchmarkSourceVersion": (
            f"AKSHARE-FUND-OVERVIEW-V2|{resolution.registry_version}"
        ),
        "benchmarkComponents": resolution.components,
        "benchmarkResolutionStatus": resolution.status,
        "benchmarkResolutionReason": resolution.reason,
    }
    if resolution.benchmark_code:
        result["benchmarkCode"] = resolution.benchmark_code
    return result


def resolve_product_metadata(
    product_type: str,
    code: str,
    ak_module: Any = None,
    cache_clock=None,
) -> dict[str, Any]:
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
    fund_catalog_records: tuple[dict[str, Any], ...] = ()
    fund_catalog_match: dict[str, Any] | None = None
    fund_classification = classify_fund_type(None)
    fund_benchmark: dict[str, Any] = {}
    inception_date: date | None = None
    if normalized_type == "STOCK":
        records = ak_module.stock_info_a_code_name().to_dict("records")
        match = next((item for item in records if str(item.get("code", "")).zfill(6) == normalized_code), None)
        if match is None:
            raise ProviderUnavailable(f"AKSHARE: 未找到股票代码 {normalized_code}")
        name = str(match.get("name", "")).strip()
        market = infer_a_share_market(normalized_code)
        inception_date = _stock_inception_date(ak_module, normalized_code)
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
        market = "FUND_CN"
        try:
            fund_benchmark = _fund_benchmark_metadata(ak_module, normalized_code)
            inception_date = _provider_date(fund_benchmark.get("inceptionDate"))
        except Exception as exc:
            warnings.append(f"AKSHARE 基金基准获取失败: {exc}")
        try:
            fund_catalog_records = _fund_catalog_records(ak_module, clock=cache_clock)
        except Exception as exc:
            warnings.append(f"AKSHARE 基金类型获取失败: {exc}")
        else:
            fund_catalog_match = next(
                (item for item in fund_catalog_records
                 if str(item.get("基金代码", "")).strip().zfill(6) == normalized_code),
                None,
            )
            if fund_catalog_match is None:
                warnings.append(f"AKSHARE 基金类型未找到基金代码 {normalized_code}")
            else:
                fund_classification = classify_fund_type(fund_catalog_match.get("基金类型"))
        try:
            snapshot_records = _fund_snapshot_records(ak_module, clock=cache_clock)
        except Exception as exc:
            warnings.append(f"AKSHARE 基金快照获取失败: {exc}")
        else:
            snapshot_match = next(
                (item for item in snapshot_records
                 if str(item.get("基金代码", "")).strip().zfill(6) == normalized_code),
                None,
            )
            if snapshot_match is None:
                warnings.append(f"AKSHARE 基金快照未找到基金代码 {normalized_code}")
            else:
                snapshot_result = _resolved_fund_snapshot(
                    snapshot_match, normalized_code, fund_classification)
                if snapshot_result is not None:
                    snapshot_result["warnings"] = warnings
                    snapshot_result.update(fund_benchmark)
                    return snapshot_result
                warnings.append(f"AKSHARE 基金快照未返回有效净值 {normalized_code}")

        match = fund_catalog_match
        if match is None:
            raise ProviderUnavailable(f"AKSHARE: 未找到基金代码 {normalized_code}")
        name = str(match.get("基金简称", "")).strip()
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
        inception_date=inception_date,
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
        fund_type_raw=fund_classification.raw_type,
        fund_category=fund_classification.category,
        classification_source=(
            "AKSHARE_FUND_NAME_EM" if fund_classification.raw_type is not None else None
        ),
        classification_version=fund_classification.version,
        benchmark_name=fund_benchmark.get("benchmarkName"),
        tracking_target=fund_benchmark.get("trackingTarget"),
        benchmark_code=fund_benchmark.get("benchmarkCode"),
        benchmark_components=fund_benchmark.get("benchmarkComponents"),
        benchmark_resolution_status=fund_benchmark.get("benchmarkResolutionStatus"),
        benchmark_resolution_reason=fund_benchmark.get("benchmarkResolutionReason"),
        benchmark_source_uri=fund_benchmark.get("benchmarkSourceUri"),
        benchmark_source_version=fund_benchmark.get("benchmarkSourceVersion"),
    )


def _first_text(row: dict[str, Any], *keys: str) -> str:
    for key in keys:
        value = row.get(key)
        if value is not None and str(value).strip():
            return str(value).strip()
    return ""


def _resolved_fund_snapshot(
    row: dict[str, Any],
    code: str,
    classification: FundClassification,
) -> dict[str, Any] | None:
    dated_navs: list[tuple[date, Decimal]] = []
    for column, value in row.items():
        column_text = str(column)
        match = re.search(r"(\d{4}[-/.]\d{1,2}[-/.]\d{1,2}).*单位净值", column_text)
        nav = _snapshot_decimal(value)
        if match is None or nav is None or nav <= 0:
            continue
        year, month, day = (int(part) for part in re.split(r"[-/.]", match.group(1)))
        try:
            data_date = date(year, month, day)
        except ValueError:
            continue
        dated_navs.append((data_date, nav))
    if not dated_navs:
        return None

    dated_navs.sort(key=lambda item: item[0], reverse=True)
    latest_date, latest_nav = dated_navs[0]
    previous_nav = dated_navs[1][1] if len(dated_navs) > 1 else None
    change_amount = _snapshot_decimal(row.get("日增长值"))
    if change_amount is None and previous_nav is not None:
        change_amount = latest_nav - previous_nav
    change_percent = _snapshot_decimal(row.get("日增长率"))
    if change_percent is None and change_amount is not None:
        change_percent = _percentage(change_amount, previous_nav)
    return normalize_resolved_product(
        product_type="MUTUAL_FUND",
        code=code,
        name=str(row.get("基金简称", "")).strip(),
        market="FUND_CN",
        currency="CNY",
        provider="AKSHARE",
        latest_price=latest_nav,
        data_date=latest_date,
        previous_close=previous_nav,
        change_amount=change_amount,
        change_percent=change_percent,
        fund_type_raw=classification.raw_type,
        fund_category=classification.category,
        classification_source=(
            "AKSHARE_FUND_NAME_EM" if classification.raw_type is not None else None
        ),
        classification_version=classification.version,
    )


def _snapshot_decimal(value: Any) -> Decimal | None:
    if value is None:
        return None
    text = str(value).strip().removesuffix("%").strip()
    if not text or text.lower() in {"nan", "none", "nat"}:
        return None
    try:
        result = Decimal(text)
    except Exception:
        return None
    return result if result.is_finite() else None


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
            symbol=code, start_year=str(max(
                STRATEGY.integer("fundamental.provider_minimum_start_year"),
                date.today().year - STRATEGY.integer("fundamental.provider_lookback_years"),
            ))
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
    selected = (annual if len(annual) >= STRATEGY.integer("fundamental.minimum_periods") else rows)[
        :STRATEGY.integer("fundamental.provider_max_periods")]
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

    def daily_quality_batch(
        self, code: str, market: str, product_type: ProductType | str,
        start_date: date, end_date: date, adjust_type: AdjustType | str, fetched_at: datetime,
    ) -> ProviderBatch | None:
        raise ProviderUnavailable(f"{self.name}: strict daily batch is not supported")


class TencentHistoryProvider(MarketDataProvider):
    name = "TENCENT"

    def __init__(self, opener=urlopen):
        self.opener = opener

    def supports(self, market: str, product_type: str) -> bool:
        return market.upper() in {"SSE", "SZSE", "BSE"} and product_type.upper() in {"STOCK", "ETF"}

    def _history_rows(
        self, symbol: str, start_date: date, end_date: date, token: str, payload_key: str,
    ) -> list[list[Any]]:
        rows_by_date: dict[str, list[Any]] = {}
        chunk_days = _provider_integer("tencent_history_chunk_calendar_days")
        cursor = start_date
        while cursor <= end_date:
            chunk_end = min(end_date, cursor + timedelta(days=chunk_days - 1))
            url = (
                "http://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param="
                f"{symbol},day,{cursor.isoformat()},{chunk_end.isoformat()},"
                f"{_provider_integer('tencent_history_max_records')},{token}"
            )
            with self.opener(url, timeout=_provider_integer("history_http_timeout_seconds")) as response:
                payload = json.loads(response.read().decode("utf-8"))
            for row in ((payload.get("data") or {}).get(symbol) or {}).get(payload_key) or []:
                if len(row) >= 6:
                    rows_by_date[str(row[0])] = row
            cursor = chunk_end + timedelta(days=1)
        return [rows_by_date[key] for key in sorted(rows_by_date)]

    def daily_quotes(self, code: str, market: str, product_type: str,
                     start_date: date, end_date: date) -> list[NormalizedQuote]:
        market_prefix = {"SSE": "sh", "SZSE": "sz", "BSE": "bj"}.get(market.upper())
        if market_prefix is None:
            raise ProviderUnavailable(f"unsupported market: {market}")
        symbol = f"{market_prefix}{code.strip()}"
        rows = self._history_rows(symbol, start_date, end_date, "qfq", "qfqday")
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
                    "volume": str(Decimal(str(volume)) * Decimal(
                        _provider_integer("tencent_volume_lot_size"))),
                },
                provider=self.name,
            ))
        return [item for item in records if start_date <= item.data_date <= end_date]

    def daily_quality_batch(self, code: str, market: str, product_type: ProductType | str,
                            start_date: date, end_date: date, adjust_type: AdjustType | str,
                            fetched_at: datetime) -> ProviderBatch | None:
        product = _product_type(product_type)
        adjustment = _adjust_type(adjust_type)
        _validate_quality_request(code, market, product, start_date, end_date, adjustment, fetched_at)
        if product is not ProductType.STOCK:
            raise ProviderUnavailable("TENCENT: MUTUAL_FUND is not supported")
        market_prefix = {"SSE": "sh", "SZSE": "sz", "BSE": "bj"}.get(market.upper())
        if market_prefix is None:
            raise ProviderUnavailable(f"TENCENT: unsupported market: {market}")
        token, payload_key = {
            AdjustType.QFQ: ("qfq", "qfqday"),
            AdjustType.HFQ: ("hfq", "hfqday"),
            AdjustType.NONE: ("none", "day"),
        }[adjustment]
        symbol = f"{market_prefix}{code.strip()}"
        rows = self._history_rows(symbol, start_date, end_date, token, payload_key)
        records = []
        for row in rows:
            if len(row) < 6:
                continue
            trade_date, open_price, close_price, high_price, low_price, volume = row[:6]
            records.append(_quality_quote(
                code=code, market=market, trade_date=trade_date,
                raw={"open": open_price, "high": high_price, "low": low_price,
                     "close": close_price,
                     "volume": str(Decimal(str(volume)) * Decimal(_provider_integer("tencent_volume_lot_size")))},
                provider=self.name, fetched_at=fetched_at,
            ))
        return _provider_batch(product, code, market, adjustment, self.name, fetched_at,
                               [item for item in records if start_date <= item.data_date <= end_date])


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
            frame = ak.stock_zh_a_hist(
                symbol=code, period="daily", start_date=start, end_date=end, adjust="qfq",
                timeout=_provider_integer("history_http_timeout_seconds"),
            )
        elif market == "HKEX":
            frame = ak.stock_hk_hist(symbol=code, period="daily", start_date=start, end_date=end, adjust="")
        elif market in {"NYSE", "NASDAQ", "AMEX"}:
            frame = ak.stock_us_hist(symbol=akshare_us_symbol(code, market), period="daily", start_date=start, end_date=end, adjust="")
        else:
            frame = ak.fund_open_fund_info_em(symbol=code, indicator="单位净值走势")
        return [item for item in _frame_to_quotes(frame, code, market, self.name)
                if start_date <= item.data_date <= end_date]

    def daily_quality_batch(self, code: str, market: str, product_type: ProductType | str,
                            start_date: date, end_date: date, adjust_type: AdjustType | str,
                            fetched_at: datetime) -> ProviderBatch | None:
        product = _product_type(product_type)
        adjustment = _adjust_type(adjust_type)
        _validate_quality_request(code, market, product, start_date, end_date, adjustment, fetched_at)
        try:
            import akshare as ak
        except ImportError as exc:
            raise ProviderUnavailable("AKShare is not installed") from exc
        market = market.upper()
        start = start_date.strftime("%Y%m%d")
        end = end_date.strftime("%Y%m%d")
        if product is ProductType.MUTUAL_FUND:
            frame = ak.fund_open_fund_info_em(symbol=code, indicator="单位净值走势")
        else:
            argument = {AdjustType.QFQ: "qfq", AdjustType.HFQ: "hfq", AdjustType.NONE: ""}[adjustment]
            if market in {"SSE", "SZSE", "BSE"}:
                frame = ak.stock_zh_a_hist(symbol=code, period="daily", start_date=start,
                                           end_date=end, adjust=argument,
                                           timeout=_provider_integer("history_http_timeout_seconds"))
            elif market == "HKEX":
                frame = ak.stock_hk_hist(symbol=code, period="daily", start_date=start,
                                         end_date=end, adjust=argument)
            elif market in {"NYSE", "NASDAQ", "AMEX"}:
                frame = ak.stock_us_hist(symbol=akshare_us_symbol(code, market), period="daily",
                                         start_date=start, end_date=end, adjust=argument)
            else:
                raise ProviderUnavailable(f"AKSHARE: unsupported market: {market}")
        records = [item for item in _frame_to_quotes(frame, code, market, self.name, fetched_at=fetched_at)
                   if start_date <= item.data_date <= end_date]
        return _provider_batch(product, code, market, adjustment, self.name, fetched_at, records)


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

    def daily_quality_batch(self, code: str, market: str, product_type: ProductType | str,
                            start_date: date, end_date: date, adjust_type: AdjustType | str,
                            fetched_at: datetime) -> ProviderBatch | None:
        product = _product_type(product_type)
        adjustment = _adjust_type(adjust_type)
        _validate_quality_request(code, market, product, start_date, end_date, adjustment, fetched_at)
        if product is not ProductType.STOCK:
            raise ProviderUnavailable("BAOSTOCK: MUTUAL_FUND is not supported")
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
                start_date=start_date.isoformat(), end_date=end_date.isoformat(), frequency="d",
                adjustflag={AdjustType.HFQ: "1", AdjustType.QFQ: "2", AdjustType.NONE: "3"}[adjustment],
            )
            rows = []
            while result.error_code == "0" and result.next():
                rows.append(dict(zip(result.fields, result.get_row_data())))
            records = [_quality_quote(code=code, market=market, trade_date=row["date"], raw=row,
                                      provider=self.name, fetched_at=fetched_at) for row in rows]
            return _provider_batch(product, code, market, adjustment, self.name, fetched_at, records)
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

    def daily_quality_batch(self, code: str, market: str, product_type: ProductType | str,
                            start_date: date, end_date: date, adjust_type: AdjustType | str,
                            fetched_at: datetime) -> ProviderBatch | None:
        product = _product_type(product_type)
        adjustment = _adjust_type(adjust_type)
        _validate_quality_request(code, market, product, start_date, end_date, adjustment, fetched_at)
        if product is not ProductType.STOCK:
            raise ProviderUnavailable("TUSHARE: MUTUAL_FUND is not supported")
        if adjustment is not AdjustType.NONE:
            raise ProviderUnavailable(f"TUSHARE: raw daily cannot deliver requested {adjustment.value} adjustment")
        try:
            import tushare as ts
        except ImportError as exc:
            raise ProviderUnavailable("Tushare is not installed") from exc
        token = os.getenv("TUSHARE_TOKEN")
        if not token:
            raise ProviderUnavailable("TUSHARE_TOKEN is not configured")
        pro = ts.pro_api(token)
        normalized_market = market.upper()
        suffix = {"SSE": ".SH", "SZSE": ".SZ", "BSE": ".BJ", "HKEX": ".HK"}.get(normalized_market, "")
        kwargs = {"ts_code": code + suffix, "start_date": start_date.strftime("%Y%m%d"),
                  "end_date": end_date.strftime("%Y%m%d")}
        if normalized_market == "HKEX":
            frame = pro.hk_daily(**kwargs)
        elif normalized_market in {"NYSE", "NASDAQ", "AMEX"}:
            frame = pro.us_daily(ts_code=code, start_date=kwargs["start_date"], end_date=kwargs["end_date"])
        else:
            frame = pro.daily(**kwargs)
        records = _frame_to_quotes(frame, code, normalized_market, self.name, fetched_at=fetched_at)
        return _provider_batch(product, code, normalized_market, AdjustType.NONE, self.name, fetched_at, records)


class ProviderRegistry:
    def __init__(self, providers: Iterable[MarketDataProvider] | None = None):
        self.providers = list((
            TencentHistoryProvider(), AkshareProvider(), BaostockProvider(), TushareProvider()
        ) if providers is None else providers)

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

    def daily_quality_batches(
        self, *, code: str, market: str, product_type: ProductType | str,
        start_date: date, end_date: date, adjust_type: AdjustType | str, clock,
        provider_names: Sequence[str] | None = None,
        maximum_batches: int = 2,
    ) -> tuple[tuple[ProviderBatch, ...], tuple[str, ...]]:
        product = _product_type(product_type)
        adjustment = _adjust_type(adjust_type)
        if not callable(clock):
            raise ValueError("clock must be callable")
        if isinstance(maximum_batches, bool) or not isinstance(maximum_batches, int) or maximum_batches < 1:
            raise ValueError("maximum_batches must be a positive integer")
        fetched_at = clock()
        normalized_code = code.strip().upper() if isinstance(code, str) else code
        normalized_market = market.strip().upper() if isinstance(market, str) else market
        _validate_quality_request(
            normalized_code, normalized_market, product, start_date, end_date, adjustment, fetched_at
        )
        warnings: list[str] = []
        batches: list[ProviderBatch] = []
        selected_providers: set[str] = set()
        providers = self.providers
        if provider_names is not None:
            providers_by_name = {provider.name.strip().upper(): provider for provider in self.providers}
            normalized_names = tuple(name.strip().upper() for name in provider_names)
            providers = []
            for name in normalized_names:
                provider = providers_by_name.get(name)
                if provider is None:
                    warnings.append(f"{name}: configured provider is not registered")
                else:
                    providers.append(provider)
        for provider in providers:
            try:
                if not provider.supports(normalized_market, product.value):
                    continue
                value = provider.daily_quality_batch(
                    normalized_code, normalized_market, product, start_date, end_date, adjustment, fetched_at
                )
            except Exception as exc:
                warnings.append(f"{provider.name}: {exc}")
                continue
            if value is None:
                warnings.append(f"{provider.name}: no records")
                continue
            if not isinstance(value, ProviderBatch):
                warnings.append(f"{provider.name}: invalid ProviderBatch")
                continue
            mismatch = _batch_mismatch(value, product, normalized_code, normalized_market, adjustment)
            if mismatch is not None:
                warnings.append(f"{provider.name}: {mismatch}")
                continue
            if value.provider in selected_providers:
                warnings.append(f"{provider.name}: duplicate provider batch skipped")
                continue
            batches.append(value)
            warnings.extend(value.warnings)
            selected_providers.add(value.provider)
            if len(batches) >= maximum_batches:
                break
        if not batches:
            raise ProviderUnavailable("; ".join(warnings) or "no provider supports this product")
        return tuple(batches), tuple(warnings)


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


def fetch_benchmark_history(
    benchmark_code: str,
    start_date: date,
    end_date: date,
    *,
    components: dict[str, float] | None = None,
    ak_module: Any = None,
    baostock_module: Any = None,
) -> list[dict[str, Any]]:
    normalized = benchmark_code.strip().upper()
    if end_date < start_date:
        raise ValueError("benchmark end_date must not be before start_date")
    if ak_module is None:
        try:
            import akshare as ak_module
        except ImportError as exc:
            raise ProviderUnavailable("AKShare is not installed") from exc

    normalized_components = {
        str(code).strip(): float(weight)
        for code, weight in (components or {}).items()
        if str(code).strip()
    }
    if len(normalized_components) > 1:
        return _composite_benchmark_history(
            normalized_components,
            start_date,
            end_date,
            ak_module=ak_module,
            baostock_module=baostock_module,
        )

    if normalized.startswith("GLOBAL_INDEX_FX:"):
        contract = benchmark_code.strip().split(":", 1)[1]
        if "@" not in contract:
            raise ValueError(f"invalid FX-adjusted global benchmark code: {benchmark_code}")
        symbol, fx_symbol = (item.strip() for item in contract.rsplit("@", 1))
        if not symbol or not fx_symbol:
            raise ValueError(f"invalid FX-adjusted global benchmark code: {benchmark_code}")
        rows, index_provider = _global_index_rows(
            ak_module, benchmark_code, symbol, start_date, end_date
        )
        fx_rows, fx_provider = _benchmark_fx_rows(
            ak_module, start_date, end_date, fx_symbol
        )
        fx_by_date = {item[0]: item[1] for item in fx_rows}
        adjusted = [
            (data_date, value * fx_by_date[data_date])
            for data_date, value in rows
            if data_date in fx_by_date
        ]
        if len(adjusted) < 2:
            raise ProviderUnavailable("AKSHARE 未返回足够的全球指数汇率调整数据")
        return _normalized_benchmark_level(
            adjusted, provider=f"{index_provider}+{fx_provider}"
        )
    if normalized in {"NASDAQ100_TR_CNY", "NASDAQ100_FX_ADJUSTED"}:
        return fetch_benchmark_history(
            "GLOBAL_INDEX_FX:纳斯达克100@USDCNY",
            start_date,
            end_date,
            ak_module=ak_module,
            baostock_module=baostock_module,
        )
    if normalized.startswith("CN_INDEX:"):
        index_code = normalized.split(":", 1)[1]
        if len(index_code) != 6 or not index_code.isdigit():
            raise ValueError(f"invalid China index benchmark code: {benchmark_code}")
        provider_symbol = _china_index_provider_symbol(index_code)
        providers = (
            (
                "AKSHARE_EASTMONEY",
                lambda: ak_module.index_zh_a_hist(
                    symbol=index_code,
                    period="daily",
                    start_date=start_date.strftime("%Y%m%d"),
                    end_date=end_date.strftime("%Y%m%d"),
                ),
            ),
            (
                "AKSHARE_SINA",
                lambda: ak_module.stock_zh_index_daily(symbol=provider_symbol),
            ),
            (
                "AKSHARE_TENCENT",
                lambda: ak_module.stock_zh_index_daily_tx(symbol=provider_symbol),
            ),
        )
        errors: list[str] = []
        for provider, loader in providers:
            try:
                rows = _benchmark_rows(loader(), start_date, end_date)
                return _normalized_benchmark_level(rows, provider=provider)
            except Exception as exc:
                errors.append(f"{provider}: {exc}")
        raise ProviderUnavailable("；".join(errors))
    if normalized.startswith("SGE_SPOT:"):
        requested_symbol = benchmark_code.strip().split(":", 1)[1]
        configured_symbol = benchmark_provider_symbol(benchmark_code, requested_symbol)
        symbol = _sge_provider_symbol(ak_module, configured_symbol)
        try:
            frame = ak_module.spot_hist_sge(symbol=symbol)
            rows = _benchmark_rows(frame, start_date, end_date)
            return _normalized_benchmark_level(rows, provider="AKSHARE_SGE")
        except Exception as exc:
            raise ProviderUnavailable(f"AKSHARE_SGE: {exc}") from exc
    if normalized.startswith("GLOBAL_INDEX:"):
        symbol = benchmark_code.strip().split(":", 1)[1]
        try:
            rows, provider = _global_index_rows(
                ak_module, benchmark_code, symbol, start_date, end_date
            )
            return _normalized_benchmark_level(rows, provider=provider)
        except Exception as exc:
            raise ProviderUnavailable(f"AKSHARE_GLOBAL_INDEX: {exc}") from exc
    if normalized == "CSI300":
        try:
            frame = ak_module.stock_zh_index_daily_em(symbol="sh000300")
            rows = _benchmark_rows(frame, start_date, end_date)
            return _normalized_benchmark_level(rows)
        except Exception:
            rows = _baostock_benchmark_rows(
                start_date,
                end_date,
                baostock_module=baostock_module,
            )
            return _normalized_benchmark_level(rows, provider="BAOSTOCK")
    raise ValueError(f"unsupported benchmark code: {benchmark_code}")


def _sge_provider_symbol(ak_module: Any, requested_symbol: str) -> str:
    provider = getattr(ak_module, "spot_symbol_table_sge", None)
    if provider is None:
        return requested_symbol
    try:
        records = provider().to_dict("records")
    except Exception:
        return requested_symbol
    expected = requested_symbol.casefold()
    for item in records:
        symbol = _first_text(item, "品种", "symbol", "代码", "code")
        if symbol.casefold() == expected:
            return symbol
    return requested_symbol


def _global_index_rows(
    ak_module: Any,
    benchmark_code: str,
    fallback_symbol: str,
    start_date: date,
    end_date: date,
) -> tuple[list[tuple[date, Decimal]], str]:
    providers = (
        ("index_global_hist_em", "AK_EM_GLOBAL", fallback_symbol),
        ("index_us_stock_sina", "AK_SINA_US_INDEX", None),
    )
    errors: list[str] = []
    for method_name, source, fallback in providers:
        method = getattr(ak_module, method_name, None)
        symbol = benchmark_provider_symbol(benchmark_code, fallback, method_name)
        if method is None or symbol is None:
            continue
        try:
            return _benchmark_rows(method(symbol=symbol), start_date, end_date), source
        except Exception as exc:
            errors.append(f"{source}: {exc}")
    raise ProviderUnavailable("；".join(errors) or "未配置可用的全球指数行情源")


def _composite_benchmark_history(
    components: dict[str, float],
    start_date: date,
    end_date: date,
    *,
    ak_module: Any,
    baostock_module: Any = None,
) -> list[dict[str, Any]]:
    if any(weight <= 0 for weight in components.values()):
        raise ValueError("benchmark component weights must be positive")
    if abs(sum(components.values()) - 1.0) > 0.000001:
        raise ValueError("benchmark component weights must total one")
    component_levels: dict[str, dict[date, Decimal]] = {}
    for code in components:
        records = fetch_benchmark_history(
            code,
            start_date,
            end_date,
            ak_module=ak_module,
            baostock_module=baostock_module,
        )
        component_levels[code] = {
            date.fromisoformat(str(record["data_date"])[:10]): Decimal(str(record["close"]))
            for record in records
        }
    dates = sorted({data_date for levels in component_levels.values() for data_date in levels})
    current: dict[str, Decimal] = {}
    previous: dict[str, Decimal] | None = None
    level = Decimal("1")
    rows: list[tuple[date, Decimal]] = []
    for data_date in dates:
        for code, levels in component_levels.items():
            if data_date in levels:
                current[code] = levels[data_date]
        if len(current) != len(components):
            continue
        if previous is None:
            rows.append((data_date, level))
            previous = dict(current)
            continue
        daily_return = sum(
            Decimal(str(components[code])) * (current[code] / previous[code] - Decimal("1"))
            for code in components
        )
        level *= Decimal("1") + daily_return
        rows.append((data_date, level))
        previous = dict(current)
    if len(rows) < 2:
        raise ProviderUnavailable("复合基准没有足够的共同历史区间")
    return _normalized_benchmark_level(rows, provider="COMPOSITE")


def _benchmark_rows(
    frame: Any,
    start_date: date,
    end_date: date,
    *,
    close_aliases: tuple[str, ...] = ("close", "收盘", "收盘价", "最新价", "单位净值"),
    scale: Decimal = Decimal("1"),
) -> list[tuple[date, Decimal]]:
    date_aliases = ("date", "日期", "净值日期")
    rows: list[tuple[date, Decimal]] = []
    for raw in frame.to_dict("records"):
        raw_date = next((raw.get(key) for key in date_aliases if raw.get(key) is not None), None)
        raw_close = next((raw.get(key) for key in close_aliases if raw.get(key) is not None), None)
        if raw_date is None or raw_close is None:
            continue
        data_date = date.fromisoformat(str(raw_date)[:10])
        close = _decimal(raw_close)
        if close is not None:
            close *= scale
        if start_date <= data_date <= end_date and close is not None and close > 0:
            rows.append((data_date, close))
    result = sorted(dict(rows).items())
    if len(result) < 2:
        raise ProviderUnavailable("AKSHARE 未返回足够的基准历史数据")
    return result


def _china_index_provider_symbol(index_code: str) -> str:
    if index_code.startswith("399"):
        return f"sz{index_code}"
    if index_code.startswith("899"):
        return f"bj{index_code}"
    return f"sh{index_code}"


def _benchmark_fx_rows(
    ak_module: Any,
    start_date: date,
    end_date: date,
    symbol: str = "USDCNY",
) -> tuple[list[tuple[date, Decimal]], str]:
    providers = (
        ("forex_hist_em", "AK_EM_FX"),
        ("currency_boc_sina", "AK_SINA_BOC_FX"),
    )
    errors: list[str] = []
    for method_name, source in providers:
        config = fx_provider_config(symbol, method_name)
        method = getattr(ak_module, method_name, None)
        if config is None or method is None:
            continue
        try:
            if method_name == "currency_boc_sina":
                frame = method(
                    symbol=config["symbol"],
                    start_date=start_date.strftime("%Y%m%d"),
                    end_date=end_date.strftime("%Y%m%d"),
                )
            else:
                frame = method(symbol=config["symbol"])
            aliases = (config["valueField"],) if config.get("valueField") else (
                "close", "收盘", "收盘价", "最新价"
            )
            rows = _benchmark_rows(
                frame,
                start_date,
                end_date,
                close_aliases=aliases,
                scale=Decimal(str(config.get("scale", 1))),
            )
            return rows, source
        except Exception as exc:
            errors.append(f"{source}: {exc}")
    raise ProviderUnavailable("；".join(errors) or f"未配置汇率行情源: {symbol}")


def _baostock_benchmark_rows(
    start_date: date,
    end_date: date,
    *,
    baostock_module: Any = None,
) -> list[tuple[date, Decimal]]:
    if baostock_module is None:
        try:
            import baostock as baostock_module
        except ImportError as exc:
            raise ProviderUnavailable("BaoStock is not installed") from exc
    login = baostock_module.login()
    if login.error_code != "0":
        raise ProviderUnavailable(login.error_msg)
    try:
        result = baostock_module.query_history_k_data_plus(
            "sh.000300",
            "date,close",
            start_date=start_date.isoformat(),
            end_date=end_date.isoformat(),
            frequency="d",
            adjustflag="3",
        )
        if result.error_code != "0":
            raise ProviderUnavailable(result.error_msg)
        rows: list[tuple[date, Decimal]] = []
        while result.next():
            row = dict(zip(result.fields, result.get_row_data()))
            close = _decimal(row.get("close"))
            if row.get("date") and close is not None and close > 0:
                rows.append((date.fromisoformat(str(row["date"])[:10]), close))
        normalized = sorted(dict(rows).items())
        if len(normalized) < 2:
            raise ProviderUnavailable("BaoStock 未返回足够的沪深300基准历史数据")
        return normalized
    finally:
        baostock_module.logout()


def _tencent_csi300_rows(
    start_date: date,
    end_date: date,
    *,
    opener=urlopen,
) -> list[tuple[date, Decimal]]:
    provider = TencentHistoryProvider(opener=opener)
    raw_rows = provider._history_rows(
        "sh000300",
        start_date,
        end_date,
        "none",
        "day",
    )
    rows: list[tuple[date, Decimal]] = []
    for row in raw_rows:
        if len(row) < 3:
            continue
        data_date = date.fromisoformat(str(row[0])[:10])
        close = _decimal(row[2])
        if start_date <= data_date <= end_date and close is not None and close > 0:
            rows.append((data_date, close))
    normalized = sorted(dict(rows).items())
    if len(normalized) < 2:
        raise ProviderUnavailable("Tencent 未返回足够的沪深300基准历史数据")
    return normalized


def _eastmoney_csi300_rows(
    start_date: date,
    end_date: date,
    *,
    opener=urlopen,
) -> list[tuple[date, Decimal]]:
    query = urlencode({
        "secid": "1.000300",
        "klt": "101",
        "fqt": "1",
        "lmt": "1000000",
        "beg": start_date.strftime("%Y%m%d"),
        "end": end_date.strftime("%Y%m%d"),
        "fields1": "f1,f2,f3,f4,f5,f6",
        "fields2": "f51,f52,f53,f54,f55,f56",
    })
    request = Request(
        f"https://push2his.eastmoney.com/api/qt/stock/kline/get?{query}",
        headers={
            "Accept": "application/json",
            "Referer": "https://quote.eastmoney.com/",
            "User-Agent": "Mozilla/5.0",
        },
    )
    with opener(request, timeout=30) as response:
        payload = json.loads(response.read().decode("utf-8"))
    klines = ((payload.get("data") or {}).get("klines") or [])
    rows: list[tuple[date, Decimal]] = []
    for raw in klines:
        parts = str(raw).split(",")
        if len(parts) < 3:
            continue
        data_date = date.fromisoformat(parts[0])
        close = _decimal(parts[2])
        if start_date <= data_date <= end_date and close is not None and close > 0:
            rows.append((data_date, close))
    normalized = sorted(dict(rows).items())
    if len(normalized) < 2:
        raise ProviderUnavailable("东方财富未返回足够的沪深300基准历史数据")
    return normalized


def _normalized_benchmark_level(
    rows: list[tuple[date, Decimal]],
    *,
    provider: str = "AKSHARE",
) -> list[dict[str, Any]]:
    level = Decimal("100")
    previous = rows[0][1]
    fetched_at = datetime.now(timezone.utc).isoformat()
    result: list[dict[str, Any]] = []
    for index, (data_date, value) in enumerate(rows):
        if index:
            period_return = value / previous - Decimal("1")
            level *= Decimal("1") + period_return
            level = level.quantize(Decimal("0.0001"))
        result.append({
            "data_date": data_date.isoformat(),
            "close": format(level, "f"),
            "provider": provider,
            "adapter_version": "benchmark-adapter-v1",
            "fetched_at": fetched_at,
        })
        previous = value
    return result


@lru_cache(maxsize=1)
def _cached_a_share_trade_dates() -> tuple[date, ...]:
    try:
        import akshare as ak_module
    except ImportError as exc:
        raise ProviderUnavailable("AKShare is not installed") from exc
    return _load_a_share_trade_dates(ak_module)


def _load_a_share_trade_dates(ak_module: Any) -> tuple[date, ...]:
    try:
        frame = ak_module.tool_trade_date_hist_sina()
    except Exception as exc:
        raise ProviderUnavailable(f"AKSHARE 交易日历获取失败: {exc}") from exc
    dates: set[date] = set()
    for row in frame.to_dict("records"):
        value = row.get("trade_date", row.get("交易日", row.get("日期")))
        if value is not None:
            dates.add(date.fromisoformat(str(value)[:10]))
    return tuple(sorted(dates))


def fetch_a_share_trade_calendar(year: int, ak_module: Any = None) -> list[str]:
    if ak_module is None:
        dates = _cached_a_share_trade_dates()
    else:
        dates = _load_a_share_trade_dates(ak_module)
    result = [trading_date.isoformat() for trading_date in dates if trading_date.year == year]
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


def _frame_to_quotes(frame: Any, code: str, market: str, provider: str,
                     fetched_at: datetime | None = None) -> list[NormalizedQuote]:
    aliases = {
        "日期": "date", "净值日期": "date", "trade_date": "date",
        "开盘": "open", "最高": "high", "最低": "low", "收盘": "close",
        "单位净值": "close", "nav": "close", "成交量": "volume", "vol": "volume",
    }
    records: list[NormalizedQuote] = []
    originals = frame.to_dict("records")
    if originals and "单位净值" in originals[0]:
        originals = attach_fund_returns(originals)
    for original in originals:
        row = {aliases.get(str(key), str(key).lower()): value for key, value in original.items()}
        records.append(normalize_quote(product_code=code, market=market, trade_date=str(row["date"])[:10],
                                       raw=row, provider=provider, fetched_at=fetched_at))
    return records


def _quality_quote(*, code: str, market: str, trade_date: str | date, raw: dict[str, Any],
                   provider: str, fetched_at: datetime) -> NormalizedQuote:
    return normalize_quote(
        product_code=code, market=market, trade_date=trade_date, raw=raw,
        provider=provider, fetched_at=fetched_at,
    )


def _provider_batch(product_type: ProductType, code: str, market: str, adjust_type: AdjustType,
                    provider: str, fetched_at: datetime,
                    records: Iterable[NormalizedQuote]) -> ProviderBatch | None:
    values = tuple(records)
    if not values:
        return None
    return ProviderBatch(
        product_type=product_type,
        code=code.strip().upper(),
        market=market.strip().upper(),
        frequency="DAY",
        adjust_type=adjust_type,
        provider=provider,
        adapter_version="1",
        fetched_at=fetched_at,
        records=values,
        warnings=(),
    )


def _product_type(value: ProductType | str) -> ProductType:
    try:
        return value if isinstance(value, ProductType) else ProductType(value)
    except (TypeError, ValueError) as error:
        raise ValueError("product_type must be STOCK or MUTUAL_FUND") from error


def _adjust_type(value: AdjustType | str) -> AdjustType:
    try:
        return value if isinstance(value, AdjustType) else AdjustType(value)
    except (TypeError, ValueError) as error:
        raise ValueError("adjust_type must be QFQ, HFQ, or NONE") from error


def _validate_quality_request(code: str, market: str, product_type: ProductType,
                              start_date: date, end_date: date, adjust_type: AdjustType,
                              fetched_at: datetime) -> None:
    if not isinstance(code, str) or not code.strip():
        raise ValueError("code must be non-blank")
    if not isinstance(market, str) or not market.strip():
        raise ValueError("market must be non-blank")
    if (not isinstance(start_date, date) or isinstance(start_date, datetime)
            or not isinstance(end_date, date) or isinstance(end_date, datetime)):
        raise ValueError("start_date and end_date must be dates")
    if start_date > end_date:
        raise ValueError("start_date cannot be after end_date")
    if not isinstance(fetched_at, datetime) or fetched_at.tzinfo is None or fetched_at.utcoffset() is None:
        raise ValueError("clock must return a timezone-aware datetime")
    if product_type is ProductType.MUTUAL_FUND and adjust_type is not AdjustType.NONE:
        raise ValueError("MUTUAL_FUND requests require adjustType=NONE")


def _batch_mismatch(batch: ProviderBatch, product_type: ProductType, code: str, market: str,
                    adjust_type: AdjustType) -> str | None:
    expected = (
        (batch.product_type, product_type, "product type"),
        (batch.code, code, "code"),
        (batch.market, market, "market"),
        (batch.frequency, "DAY", "frequency"),
        (batch.adjust_type, adjust_type, "actual adjust"),
    )
    for actual, requested, label in expected:
        if actual != requested:
            return f"{label} mismatch (requested {requested}, actual {actual})"
    return None
