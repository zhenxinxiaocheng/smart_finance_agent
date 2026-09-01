from __future__ import annotations

import hashlib
import json
import os
import re
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import Any


def normalize_benchmark_name(value: Any) -> str:
    text = re.sub(r"[\s（）()·,，]", "", str(value or "")).strip()
    for suffix in ("全收益指数", "净收益指数", "价格指数", "指数收益率", "收益率", "指数"):
        if text.endswith(suffix):
            text = text[:-len(suffix)]
            break
    return text.casefold()


@dataclass(frozen=True)
class BenchmarkResolution:
    status: str
    benchmark_code: str | None
    components: dict[str, float]
    reason: str | None
    registry_version: str


class BenchmarkInstrumentRegistry:
    def __init__(self, data: Mapping[str, Any]):
        self.version = str(data.get("version", "")).strip()
        if not self.version:
            raise ValueError("benchmark instrument registry version cannot be blank")
        instruments = data.get("instruments")
        if not isinstance(instruments, list):
            raise ValueError("benchmark instrument registry instruments must be a list")
        aliases: dict[str, str] = {}
        provider_symbols: dict[str, dict[str, str]] = {}
        for item in instruments:
            if not isinstance(item, Mapping):
                raise ValueError("benchmark instrument registry item must be an object")
            code = str(item.get("benchmarkCode", "")).strip()
            provider_symbol = str(item.get("providerSymbol", "")).strip()
            names = item.get("aliases")
            if not code or not provider_symbol or not isinstance(names, list) or not names:
                raise ValueError("benchmark instrument registry item is incomplete")
            symbols = {"default": provider_symbol}
            configured_symbols = item.get("providerSymbols")
            if isinstance(configured_symbols, Mapping):
                symbols.update({
                    str(provider).strip(): str(symbol).strip()
                    for provider, symbol in configured_symbols.items()
                    if str(provider).strip() and str(symbol).strip()
                })
            provider_symbols[code.casefold()] = symbols
            for name in names:
                normalized = normalize_benchmark_name(name)
                if normalized:
                    aliases[normalized] = code
        self._aliases = aliases
        self._provider_symbols = provider_symbols
        self._fx_instruments: dict[str, dict[str, dict[str, Any]]] = {}
        for item in data.get("fxInstruments", []):
            if not isinstance(item, Mapping):
                continue
            symbol = str(item.get("symbol", "")).strip().casefold()
            if not symbol:
                continue
            self._fx_instruments[symbol] = {
                "symbols": dict(item.get("providerSymbols") or {}),
                "fields": dict(item.get("providerValueFields") or {}),
                "scales": dict(item.get("providerScales") or {}),
            }

    def resolve(self, value: Any) -> str | None:
        return self._aliases.get(normalize_benchmark_name(value))

    def provider_symbol(self, benchmark_code: str, provider: str | None = None) -> str | None:
        symbols = self._provider_symbols.get(str(benchmark_code or "").strip().casefold(), {})
        return symbols.get(provider or "default")

    def fx_provider_config(self, symbol: str, provider: str) -> dict[str, Any] | None:
        instrument = self._fx_instruments.get(str(symbol or "").strip().casefold())
        if instrument is None:
            return None
        provider_symbol = instrument["symbols"].get(provider)
        if not provider_symbol:
            return None
        return {
            "symbol": provider_symbol,
            "valueField": instrument["fields"].get(provider),
            "scale": instrument["scales"].get(provider, 1),
        }


def load_benchmark_registry(path: str | Path | None = None) -> BenchmarkInstrumentRegistry:
    configured_path = path or os.getenv("ANALYSIS_BENCHMARK_REGISTRY")
    source = Path(configured_path) if configured_path else (
        Path(__file__).resolve().parent.parent / "config" / "benchmark-instruments-v1.json"
    )
    with source.open("r", encoding="utf-8") as handle:
        data = json.load(handle)
    if not isinstance(data, Mapping):
        raise ValueError("benchmark instrument registry root must be an object")
    return BenchmarkInstrumentRegistry(data)


REGISTRY = load_benchmark_registry()


def benchmark_provider_symbol(
    benchmark_code: str,
    fallback: str | None,
    provider: str | None = None,
) -> str | None:
    return REGISTRY.provider_symbol(benchmark_code, provider) or fallback


def fx_provider_config(symbol: str, provider: str) -> dict[str, Any] | None:
    return REGISTRY.fx_provider_config(symbol, provider)


def resolve_fund_benchmark(
    tracking_target: str | None,
    benchmark_name: str | None,
    dynamic_resolver: Callable[[str], str | None],
) -> BenchmarkResolution:
    target = str(tracking_target or "").strip()
    benchmark = str(benchmark_name or "").strip()
    if target:
        code = REGISTRY.resolve(target) or dynamic_resolver(target)
        if code:
            return _ready({code: 1.0})

    components, unresolved = _formula_components(benchmark, dynamic_resolver)
    if components and not unresolved:
        return _ready(components)

    if target:
        reason = f"跟踪标的“{target}”尚无可执行的行情适配器"
        if unresolved:
            reason += f"；业绩基准还缺少：{'、'.join(unresolved)}"
        return BenchmarkResolution(
            "UNSUPPORTED_BENCHMARK", None, {}, reason, REGISTRY.version
        )
    if benchmark:
        reason = "业绩比较基准无法完整转换为日频行情"
        if unresolved:
            reason += f"，缺少：{'、'.join(unresolved)}"
        return BenchmarkResolution(
            "INCOMPLETE_BENCHMARK", None, {}, reason, REGISTRY.version
        )
    return BenchmarkResolution(
        "MISSING_BENCHMARK",
        None,
        {},
        "基金资料未提供跟踪标的或业绩比较基准",
        REGISTRY.version,
    )


def _formula_components(
    formula: str,
    dynamic_resolver: Callable[[str], str | None],
) -> tuple[dict[str, float], list[str]]:
    if not formula:
        return {}, []
    terms = [item.strip() for item in re.split(r"(?<=%)[+＋]", formula) if item.strip()]
    components: dict[str, float] = {}
    unresolved: list[str] = []
    weighted = False
    for term in terms:
        weight_match = re.search(r"(?:\*|×)\s*(\d+(?:\.\d+)?)\s*%", term)
        weight = float(weight_match.group(1)) / 100.0 if weight_match else None
        if weight is not None:
            weighted = True
            label = term[:weight_match.start()].strip()
        else:
            label = term
        code = REGISTRY.resolve(label) or dynamic_resolver(label)
        if code is None:
            unresolved.append(label)
            continue
        if weight is None:
            if len(terms) > 1:
                unresolved.append(label)
                continue
            weight = 1.0
        components[code] = components.get(code, 0.0) + weight
    if weighted and abs(sum(components.values()) - 1.0) > 0.000001:
        unresolved.append("未解析成分权重")
    return components, list(dict.fromkeys(unresolved))


def _ready(components: dict[str, float]) -> BenchmarkResolution:
    ordered = dict(sorted(components.items()))
    if len(ordered) == 1:
        benchmark_code = next(iter(ordered))
    else:
        material = json.dumps(ordered, sort_keys=True, separators=(",", ":"))
        fingerprint = hashlib.sha256(material.encode("utf-8")).hexdigest()[:24]
        benchmark_code = f"COMPOSITE:{fingerprint}"
    return BenchmarkResolution(
        "READY", benchmark_code, ordered, None, REGISTRY.version
    )
