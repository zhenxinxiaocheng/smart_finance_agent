from __future__ import annotations

import unittest
import threading
import tempfile
from pathlib import Path

from app.quant.config import load_quant_config
from app.quant.jobs import (
    QuantJobService,
    _benchmark_contract,
    _failure_payload,
    _market_allocation_samples,
    _merge_panel_samples,
    _member_benchmark_records,
    _optimization_study_name,
    _visible_target_weight,
)
from app.quant.engine import InsufficientQuantData, TrainingSample


class QuantJobContractTest(unittest.TestCase):
    def test_optimization_identity_changes_only_with_research_inputs(self):
        base = {
            "datasetVersion": "dataset-v1",
            "featureVersion": "features-v1",
            "algorithmVersion": "algorithms-v3",
            "productType": "MUTUAL_FUND",
            "code": "010736",
            "horizonDays": 60,
        }

        first = _optimization_study_name(base, load_quant_config())
        replay = _optimization_study_name(dict(base), load_quant_config())
        new_data = _optimization_study_name(
            base | {"datasetVersion": "dataset-v2"},
            load_quant_config(),
        )

        self.assertEqual(first, replay)
        self.assertNotEqual(first, new_data)

    def test_runtime_algorithm_change_creates_a_new_validation_study(self):
        base = {
            "datasetVersion": "dataset-v1",
            "runtimeVersion": "a" * 64,
            "productType": "MUTUAL_FUND",
            "code": "010736",
            "horizonDays": 60,
        }

        first = _optimization_study_name(base, load_quant_config())
        changed = _optimization_study_name(
            base | {"runtimeVersion": "b" * 64},
            load_quant_config(),
        )

        self.assertNotEqual(first, changed)

    def test_completed_training_separates_execution_from_validation_outcome(self):
        test_root = Path(__file__).resolve().parents[1] / ".test-tmp"
        test_root.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=test_root) as root:
            service = QuantJobService(
                Path(root),
                load_quant_config(),
                max_workers=1,
            )
            service._execute = lambda _job_type, _request: {
                "modelStatus": "DRAFT",
                "economicRole": "RISK_REFERENCE",
            }

            created = service.submit({
                "type": "AUTO_SEARCH",
                "datasetVersion": "dataset-v1",
            })
            service.executor.shutdown(wait=True)
            completed = service.get(created["jobId"])

        self.assertEqual("SUCCEEDED", completed["status"])
        self.assertEqual("COMPLETED", completed["executionStatus"])
        self.assertEqual("VALIDATION_FAILED", completed["trainingOutcome"])
        self.assertEqual("RESEARCH", completed["deploymentStatus"])
        self.assertEqual("RISK_REFERENCE", completed["economicRole"])

    def test_insufficient_data_completes_as_data_blocked_with_risk_reference(self):
        test_root = Path(__file__).resolve().parents[1] / ".test-tmp"
        test_root.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=test_root) as root:
            service = QuantJobService(
                Path(root),
                load_quant_config(),
                max_workers=1,
            )

            def insufficient(_job_type, _request):
                raise InsufficientQuantData("history is incomplete")

            service._execute = insufficient
            created = service.submit({
                "type": "AUTO_SEARCH",
                "datasetVersion": "dataset-v1",
            })
            service.executor.shutdown(wait=True)
            completed = service.get(created["jobId"])

        self.assertEqual("SUCCEEDED", completed["status"])
        self.assertEqual("COMPLETED", completed["executionStatus"])
        self.assertEqual("DATA_BLOCKED", completed["trainingOutcome"])
        self.assertEqual("RISK_REFERENCE", completed["economicRole"])
        self.assertFalse(completed["result"]["riskReference"]["tradable"])

    def test_cancelled_running_job_cannot_overwrite_terminal_state(self):
        started = threading.Event()
        release = threading.Event()
        test_root = Path(__file__).resolve().parents[1] / ".test-tmp"
        test_root.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=test_root) as root:
            service = QuantJobService(
                Path(root),
                load_quant_config(),
                max_workers=1,
            )

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
            self.assertEqual(
                "CANCELLED",
                service.get(created["jobId"])["status"],
            )

    def test_missing_benchmark_is_not_relabelled_as_cash(self):
        code, flags = _benchmark_contract(None, [])

        self.assertIsNone(code)
        self.assertEqual(["BENCHMARK_UNAVAILABLE"], flags)

    def test_fund_family_member_inherits_shared_official_benchmark(self):
        shared = [{"data_date": "2020-01-02", "close": 4100.0}]

        resolved = _member_benchmark_records(
            {"code": "000051", "records": []},
            shared,
        )

        self.assertIs(shared, resolved)

    def test_member_specific_benchmark_takes_precedence(self):
        shared = [{"data_date": "2020-01-02", "close": 4100.0}]
        member = [{"data_date": "2020-01-02", "close": 3200.0}]

        resolved = _member_benchmark_records(
            {"code": "000051", "benchmarkRecords": member},
            shared,
        )

        self.assertIs(member, resolved)

    def test_index_fund_uses_official_benchmark_as_market_allocation_head(self):
        benchmark = [{"data_date": "2020-01-02", "close": 4100.0}]
        generated = TrainingSample(
            as_of_index=10,
            label_end_index=20,
            as_of_date="2020-01-02",
            features={"momentum": 0.1},
            net_excess_return=0.0,
            positive_excess=False,
            net_return=0.02,
            positive_return=True,
            negative_return=False,
        )

        class RecordingEngine:
            def training_samples(self, records, product_type, horizon_days, benchmark_records):
                self.call = (records, product_type, horizon_days, benchmark_records)
                return [generated]

        engine = RecordingEngine()
        samples = _market_allocation_samples(
            engine,
            model_family="INDEX_FUND",
            benchmark_records=benchmark,
            horizon_days=10,
        )

        self.assertEqual((benchmark, "MUTUAL_FUND", 10, benchmark), engine.call)
        self.assertEqual("MARKET::OFFICIAL_BENCHMARK", samples[0].series_id)
        self.assertEqual("MARKET_ALLOCATION", samples[0].prediction_head)

    def test_active_fund_does_not_add_index_market_head(self):
        class UnexpectedEngine:
            def training_samples(self, *_args, **_kwargs):
                raise AssertionError("active fund must not create index market samples")

        self.assertEqual(
            [],
            _market_allocation_samples(
                UnexpectedEngine(),
                model_family="ACTIVE_FUND",
                benchmark_records=[{"data_date": "2020-01-02", "close": 4100.0}],
                horizon_days=10,
            ),
        )

    def test_failed_job_has_structured_error_and_no_cached_result_claim(self):
        payload = _failure_payload(ValueError("records cannot be empty"))

        self.assertEqual("JOB_FAILED", payload["errorCode"])
        self.assertEqual("ValueError: records cannot be empty", payload["errorSummary"])
        self.assertEqual(["JOB_FAILED"], payload["result"]["riskFlags"])
        self.assertNotIn("上一份有效结果", payload["result"]["userMessage"])

    def test_insufficient_training_data_is_not_reported_as_system_failure(self):
        payload = _failure_payload(
            InsufficientQuantData(
                "calibration partition requires positive and negative labels"
            )
        )

        self.assertEqual("INSUFFICIENT_DATA", payload["errorCode"])
        self.assertEqual(["INSUFFICIENT_DATA"], payload["result"]["riskFlags"])
        self.assertEqual(
            "有效训练样本不足，当前无法训练可靠模型",
            payload["result"]["userMessage"],
        )

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
