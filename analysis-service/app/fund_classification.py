from __future__ import annotations

import json
import os
import re
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
from typing import Any


_CATEGORY_PATTERN = re.compile(r"^[A-Z][A-Z0-9_]*$")


@dataclass(frozen=True)
class FundClassification:
    raw_type: str | None
    category: str
    version: str


@dataclass(frozen=True)
class FundClassificationRules:
    version: str
    unknown_category: str
    exact_mappings: dict[str, str]
    prefix_mappings: tuple[tuple[str, str], ...]
    additional_categories: frozenset[str]


def _default_config_path() -> Path:
    return Path(__file__).resolve().parents[1] / "config" / "fund-classification-v1.json"


def _required_text(value: Any, field: str) -> str:
    text = str(value).strip() if value is not None else ""
    if not text:
        raise ValueError(f"fund classification {field} must be non-blank")
    return text


def _category(value: Any, field: str) -> str:
    category = _required_text(value, field)
    if _CATEGORY_PATTERN.fullmatch(category) is None:
        raise ValueError(f"fund classification {field} must be an uppercase identifier")
    return category


@lru_cache(maxsize=1)
def load_fund_classification_rules() -> FundClassificationRules:
    configured = os.getenv("FUND_CLASSIFICATION_CONFIG")
    path = Path(configured).expanduser().resolve() if configured else _default_config_path()
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError("fund classification config must be an object")

    exact_raw = payload.get("exactMappings", {})
    prefix_raw = payload.get("prefixMappings", [])
    additional_raw = payload.get("additionalCategories", [])
    if (not isinstance(exact_raw, dict)
            or not isinstance(prefix_raw, list)
            or not isinstance(additional_raw, list)):
        raise ValueError("fund classification mappings have invalid types")

    exact = {
        _required_text(raw_type, "exact raw type"): _category(category, "exact category")
        for raw_type, category in exact_raw.items()
    }
    prefixes: list[tuple[str, str]] = []
    for item in prefix_raw:
        if not isinstance(item, dict):
            raise ValueError("fund classification prefix mapping must be an object")
        prefixes.append((
            _required_text(item.get("prefix"), "prefix"),
            _category(item.get("category"), "prefix category"),
        ))

    return FundClassificationRules(
        version=_required_text(payload.get("version"), "version"),
        unknown_category=_category(payload.get("unknownCategory"), "unknown category"),
        exact_mappings=exact,
        prefix_mappings=tuple(prefixes),
        additional_categories=frozenset(
            _category(item, "additional category") for item in additional_raw
        ),
    )


def classify_fund_type(raw_type: str | None) -> FundClassification:
    rules = load_fund_classification_rules()
    normalized = str(raw_type).strip() if raw_type is not None else ""
    category = rules.exact_mappings.get(normalized)
    if category is None:
        category = next(
            (candidate for prefix, candidate in rules.prefix_mappings
             if normalized.startswith(prefix)),
            rules.unknown_category,
        )
    return FundClassification(
        raw_type=normalized or None,
        category=category,
        version=rules.version,
    )


def known_fund_categories() -> frozenset[str]:
    rules = load_fund_classification_rules()
    categories = set(rules.additional_categories)
    categories.update(rules.exact_mappings.values())
    categories.update(category for _, category in rules.prefix_mappings)
    categories.discard(rules.unknown_category)
    return frozenset(categories)


def is_known_fund_category(category: str | None) -> bool:
    normalized = str(category).strip() if category is not None else ""
    return normalized in known_fund_categories()
