from __future__ import annotations

import json
import os
from collections.abc import Mapping
from pathlib import Path
from typing import Any


class IndexWatchlistRegistry:
    def __init__(self, data: Mapping[str, Any]):
        self.version = str(data.get("version", "")).strip()
        if not self.version:
            raise ValueError("index watchlist registry version cannot be blank")
        self._items: dict[str, dict[str, Any]] = {}
        for item in data.get("instruments", []):
            if not isinstance(item, Mapping):
                continue
            code = str(item.get("indexCode", "")).strip().upper()
            display_name = str(item.get("displayName", "")).strip()
            market = str(item.get("market", "")).strip().upper()
            aliases = [str(value).strip() for value in item.get("aliases", []) if str(value).strip()]
            raw_fallback = item.get("fallback")
            fallback = None
            if isinstance(raw_fallback, Mapping):
                provider = str(raw_fallback.get("provider", "")).strip().upper()
                symbol = str(raw_fallback.get("symbol", "")).strip()
                if provider and symbol:
                    fallback = {"provider": provider, "symbol": symbol}
            if not code or not display_name:
                raise ValueError("index watchlist registry item is incomplete")
            self._items[code] = {
                "displayName": display_name,
                "market": market or None,
                "aliases": tuple(value.casefold() for value in aliases),
                "fallback": fallback,
            }

    def enrich(self, quote: dict[str, Any]) -> dict[str, Any]:
        configured = self._items.get(str(quote.get("indexCode", "")).upper())
        if configured is None:
            return quote
        return {
            **quote,
            "name": configured["displayName"],
            "market": configured["market"] or quote.get("market"),
        }

    def matches(self, quote: Mapping[str, Any], keyword: str) -> bool:
        query = str(keyword or "").strip().casefold()
        if not query:
            return False
        code = str(quote.get("indexCode", ""))
        name = str(quote.get("name", ""))
        if query in code.casefold() or query in name.casefold():
            return True
        configured = self._items.get(code.upper())
        return configured is not None and any(query in alias for alias in configured["aliases"])

    def fallback_quotes(self) -> tuple[dict[str, str], ...]:
        fallbacks = []
        for index_code, item in self._items.items():
            fallback = item["fallback"]
            if fallback is None:
                continue
            fallbacks.append({
                "indexCode": index_code,
                "name": item["displayName"],
                "market": item["market"] or "GLOBAL",
                **fallback,
            })
        return tuple(fallbacks)


def load_index_watchlist_registry(path: str | Path | None = None) -> IndexWatchlistRegistry:
    configured_path = path or os.getenv("ANALYSIS_INDEX_WATCHLIST_REGISTRY")
    source = Path(configured_path) if configured_path else (
        Path(__file__).resolve().parent.parent / "config" / "index-watchlist-v1.json"
    )
    with source.open("r", encoding="utf-8") as handle:
        data = json.load(handle)
    if not isinstance(data, Mapping):
        raise ValueError("index watchlist registry root must be an object")
    return IndexWatchlistRegistry(data)


INDEX_WATCHLIST_REGISTRY = load_index_watchlist_registry()
