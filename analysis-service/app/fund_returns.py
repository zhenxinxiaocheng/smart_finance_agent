from __future__ import annotations

import math
from decimal import Decimal, InvalidOperation
from typing import Any, Mapping, Sequence


class FundReturnDataUnavailable(ValueError):
    """Unit NAV alone cannot establish an investor's total return."""


def total_return_index(record: Mapping[str, Any]) -> float:
    value = record.get("total_return_index")
    try:
        if value is None and record.get("adjustment_factor") is not None:
            nav = record.get("nav") or record.get("close")
            if nav is not None:
                value = float(nav) * float(record["adjustment_factor"])
        result = float(value)
    except (TypeError, ValueError):
        raise FundReturnDataUnavailable("基金含分红收益历史待补齐，系统正在自动同步") from None
    if not math.isfinite(result) or result <= 0:
        raise FundReturnDataUnavailable("基金含分红收益数据校验未通过，系统将自动重新获取")
    return result


def attach_fund_returns(rows: Sequence[Mapping[str, Any]]) -> list[dict[str, Any]]:
    """Compound provider daily returns, which already account for distributions.

    Do this on the full history BEFORE date filtering. Never substitute a NAV
    change for a missing official return, or resume an index across a gap.
    """
    ordered = sorted(rows, key=lambda row: str(row.get("净值日期", row.get("date", ""))))
    index: Decimal | None = Decimal(1)
    result = []
    for position, row in enumerate(ordered):
        if position and index is not None:
            try:
                daily = Decimal(str(row.get("日增长率"))) / Decimal(100)
                if not daily.is_finite() or daily <= -1:
                    raise ValueError("invalid daily return")
                index *= 1 + daily
            except (InvalidOperation, TypeError, ValueError):
                index = None
        result.append({**row, "total_return_index": index})
    return result
