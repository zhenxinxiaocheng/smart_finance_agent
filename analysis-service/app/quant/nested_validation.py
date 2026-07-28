from __future__ import annotations

import math
from collections import defaultdict
from typing import Sequence

from .engine import InsufficientQuantData, TrainingSample


def split_search_and_final_holdout(
    samples: Sequence[TrainingSample],
    *,
    holdout_fraction: float,
    minimum_holdout_samples: int,
    minimum_selection_samples: int,
) -> tuple[list[TrainingSample], list[TrainingSample]]:
    if not 0.0 < holdout_fraction < 0.5:
        raise ValueError("final holdout fraction must be between 0 and 0.5")
    if minimum_holdout_samples < 1 or minimum_selection_samples < 1:
        raise ValueError("partition minimums must be positive")
    ordered = sorted(
        samples,
        key=lambda sample: (
            sample.as_of_date,
            sample.series_id,
            sample.as_of_index,
        ),
    )
    required_holdout = max(
        minimum_holdout_samples,
        int(math.ceil(len(ordered) * holdout_fraction)),
    )
    dates: dict[str, list[TrainingSample]] = defaultdict(list)
    for sample in ordered:
        dates[sample.as_of_date].append(sample)
    holdout_dates: set[str] = set()
    holdout_count = 0
    for as_of_date in sorted(dates, reverse=True):
        holdout_dates.add(as_of_date)
        holdout_count += len(dates[as_of_date])
        if holdout_count >= required_holdout:
            break
    holdout = [
        sample for sample in ordered if sample.as_of_date in holdout_dates
    ]
    raw_selection = [
        sample for sample in ordered if sample.as_of_date not in holdout_dates
    ]
    boundary_by_series: dict[str, int] = {}
    for sample in holdout:
        boundary_by_series[sample.series_id] = min(
            boundary_by_series.get(sample.series_id, sample.as_of_index),
            sample.as_of_index,
        )
    selection = [
        sample
        for sample in raw_selection
        if sample.series_id not in boundary_by_series
        or sample.label_end_index < boundary_by_series[sample.series_id]
    ]
    if len(holdout) < minimum_holdout_samples:
        raise InsufficientQuantData("final holdout partition is too small")
    if len(selection) < minimum_selection_samples:
        raise InsufficientQuantData(
            "candidate-selection partition is too small after purge"
        )
    return selection, holdout
