from __future__ import annotations

import unittest

from app.quant.engine import TrainingSample
from app.quant.nested_validation import split_search_and_final_holdout


class QuantNestedValidationTest(unittest.TestCase):
    def test_final_holdout_keeps_dates_together_and_purges_overlapping_labels(self):
        samples = [
            TrainingSample(
                as_of_index=index,
                label_end_index=index + 2,
                as_of_date=f"2026-01-{index + 1:02d}",
                features={"signal": float(index)},
                net_excess_return=0.01,
                positive_excess=True,
                series_id=series,
                net_return=0.01,
                positive_return=True,
                negative_return=False,
            )
            for index in range(10)
            for series in ("A", "B")
        ]

        selection, holdout = split_search_and_final_holdout(
            samples,
            holdout_fraction=0.2,
            minimum_holdout_samples=4,
            minimum_selection_samples=8,
        )

        self.assertEqual(
            {"2026-01-09", "2026-01-10"},
            {sample.as_of_date for sample in holdout},
        )
        self.assertEqual(
            "2026-01-06",
            max(sample.as_of_date for sample in selection),
        )
        self.assertTrue(
            {sample.as_of_date for sample in selection}.isdisjoint(
                {sample.as_of_date for sample in holdout}
            )
        )


if __name__ == "__main__":
    unittest.main()
