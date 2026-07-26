from __future__ import annotations

import unittest
import threading
from pathlib import Path

from app.quant.config import load_quant_config
from app.quant.jobs import (
    QuantJobService,
    _benchmark_contract,
    _failure_payload,
    _merge_panel_samples,
    _visible_target_weight,
)
from app.quant.engine import TrainingSample


class QuantJobContractTest(unittest.TestCase):
    def test_cancelled_running_job_cannot_overwrite_terminal_state(self):
        started = threading.Event()
        release = threading.Event()
        root = Path(__file__).resolve().parents[1] / ".data"
        service = QuantJobService(root, load_quant_config(), max_workers=1)

        def slow_execute(_job_type, _request):
            started.set()
            release.wait(timeout=2)
            return {"unexpected": True}

        service._execute = slow_execute
        created = service.submit({"type": "BACKTEST"})
        self.assertTrue(started.wait(timeout=2))
        try:
            cancelled = service.cancel(created["jobId"])
            self.assertEqual("CANCELLED", cancelled["status"])
        finally:
            release.set()
            service.executor.shutdown(wait=True)
        self.assertEqual("CANCELLED", service.get(created["jobId"])["status"])

    def test_missing_benchmark_is_not_relabelled_as_cash(self):
        code, flags = _benchmark_contract(None, [])

        self.assertIsNone(code)
        self.assertEqual(["BENCHMARK_UNAVAILABLE"], flags)

    def test_failed_job_has_structured_error_and_no_cached_result_claim(self):
        payload = _failure_payload(ValueError("records cannot be empty"))

        self.assertEqual("JOB_FAILED", payload["errorCode"])
        self.assertEqual("ValueError: records cannot be empty", payload["errorSummary"])
        self.assertEqual(["JOB_FAILED"], payload["result"]["riskFlags"])
        self.assertNotIn("上一份有效结果", payload["result"]["userMessage"])

    def test_only_tradable_lifecycle_exposes_target_weight(self):
        self.assertIsNone(_visible_target_weight("DRAFT", 0.18))
        self.assertEqual(0.18, _visible_target_weight("VALIDATED", 0.18))
        self.assertEqual(0.18, _visible_target_weight("PAPER_VERIFIED", 0.18))
        self.assertIsNone(_visible_target_weight("RETIRED", 0.18))

    def test_panel_samples_are_tagged_and_sorted_by_date(self):
        def sample(sample_date, index):
            return TrainingSample(
                as_of_index=index,
                label_end_index=index + 2,
                as_of_date=sample_date,
                features={"momentum": float(index)},
                net_excess_return=0.01,
                positive_excess=True,
            )

        merged = _merge_panel_samples(
            [sample("2026-01-02", 1)],
            [
                ("member-b", [sample("2026-01-01", 0)]),
                ("member-a", [sample("2026-01-02", 1)]),
            ],
        )

        self.assertEqual(
            [
                ("2026-01-01", "member-b"),
                ("2026-01-02", "TARGET"),
                ("2026-01-02", "member-a"),
            ],
            [(item.as_of_date, item.series_id) for item in merged],
        )


if __name__ == "__main__":
    unittest.main()
