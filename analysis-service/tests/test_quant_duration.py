from __future__ import annotations

import unittest

from app.quant.config import load_quant_config
from app.quant.duration import estimate_job_duration_seconds


class QuantJobDurationEstimateTest(unittest.TestCase):
    def setUp(self) -> None:
        self.config = load_quant_config()

    def test_auto_search_estimate_uses_search_budget_and_all_input_rows(self) -> None:
        request = {
            "type": "AUTO_SEARCH",
            "records": [{}] * 100,
            "benchmarkRecords": [{}] * 50,
            "universeRecords": [
                {"records": [{}] * 200},
                {"records": [{}] * 50},
            ],
        }

        estimate = estimate_job_duration_seconds(request, self.config)

        self.assertEqual(1864, estimate)

    def test_non_auto_search_job_has_no_duration_estimate(self) -> None:
        estimate = estimate_job_duration_seconds(
            {"type": "PREDICT", "records": [{}] * 400},
            self.config,
        )

        self.assertIsNone(estimate)


if __name__ == "__main__":
    unittest.main()
