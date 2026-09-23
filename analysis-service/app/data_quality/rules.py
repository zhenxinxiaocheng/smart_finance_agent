from __future__ import annotations

from dataclasses import dataclass

from .models import IssueSeverity, ProductType


@dataclass(frozen=True, slots=True)
class DataQualityRule:
    code: str
    severity: IssueSeverity
    product_type: ProductType | None = None
    blocking_group: str | None = None


RULES: tuple[DataQualityRule, ...] = (
    DataQualityRule("COMMON_REQUIRED_FIELDS", IssueSeverity.CRITICAL),
    DataQualityRule("COMMON_UNIQUE_ORDERED_DATES", IssueSeverity.CRITICAL),
    DataQualityRule("COMMON_POSITIVE_VALUES", IssueSeverity.CRITICAL),
    DataQualityRule("MANIFEST_CONTENT_INTEGRITY", IssueSeverity.CRITICAL),
    DataQualityRule("STOCK_OHLC_RELATION", IssueSeverity.CRITICAL, ProductType.STOCK),
    DataQualityRule("STOCK_ADJUSTMENT_CONSISTENCY", IssueSeverity.CRITICAL, ProductType.STOCK),
    DataQualityRule("STOCK_STALENESS", IssueSeverity.WARNING, ProductType.STOCK),
    DataQualityRule("STOCK_UNEXPLAINED_TRADING_GAPS", IssueSeverity.WARNING, ProductType.STOCK),
    DataQualityRule("STOCK_EXTREME_RETURN", IssueSeverity.WARNING, ProductType.STOCK, "STOCK_PRICE_EVENT"),
    DataQualityRule("STOCK_EXTREME_VOLUME", IssueSeverity.WARNING, ProductType.STOCK),
    DataQualityRule("STOCK_CORPORATE_ACTION_EVIDENCE", IssueSeverity.WARNING, ProductType.STOCK,
                    "STOCK_PRICE_EVENT"),
    DataQualityRule("STOCK_SECONDARY_SOURCE_AVAILABILITY", IssueSeverity.INFO, ProductType.STOCK),
    DataQualityRule("STOCK_CROSS_SOURCE_RECONCILIATION", IssueSeverity.CRITICAL, ProductType.STOCK),
    DataQualityRule("FUND_NAV_TYPE_CONSISTENCY", IssueSeverity.CRITICAL, ProductType.MUTUAL_FUND),
    DataQualityRule("FUND_ESTIMATED_NAV_FORBIDDEN", IssueSeverity.CRITICAL, ProductType.MUTUAL_FUND),
    DataQualityRule("FUND_STALENESS", IssueSeverity.WARNING, ProductType.MUTUAL_FUND),
    DataQualityRule("FUND_UNEXPLAINED_NAV_GAPS", IssueSeverity.WARNING, ProductType.MUTUAL_FUND),
)

RULE_CODES: tuple[str, ...] = tuple(rule.code for rule in RULES)
RULE_BY_CODE = {rule.code: rule for rule in RULES}
