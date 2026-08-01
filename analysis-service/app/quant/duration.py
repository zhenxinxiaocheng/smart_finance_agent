from __future__ import annotations

import math
from typing import Any, Mapping

from .config import QuantConfig


def estimate_job_duration_seconds(
    request: Mapping[str, Any],
    config: QuantConfig,
) -> int | None:
    if str(request.get("type") or "").strip().upper() != "AUTO_SEARCH":
        return None

    row_count = len(request.get("records") or [])
    row_count += len(request.get("benchmarkRecords") or [])
    row_count += sum(
        len(member.get("records") or [])
        for member in request.get("universeRecords") or []
    )
    preprocessing_seconds = math.ceil(
        row_count
        * config.number(
            "autoSearch.durationEstimate.preprocessingSecondsPerRecord"
        )
    )
    return int(
        config.number("autoSearch.timeBudgetSeconds")
        + config.number("autoSearch.durationEstimate.finalizationSeconds")
        + preprocessing_seconds
    )
