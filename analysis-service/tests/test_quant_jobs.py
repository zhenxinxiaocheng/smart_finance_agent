from __future__ import annotations

import hashlib
import copy
import math
import tempfile
import time
import unittest
from datetime import date, timedelta
from pathlib import Path

from app.quant.config import QuantConfig, load_quant_config
from app.quant.jobs import QuantJobService


def market_records(count: int) -> list[dict[str, object]]:
    start = date(2025, 1, 1)
    return [{
        "data_date": (start + timedelta(days=index)).isoformat(),
        "open": 10 + index * 0.03,
        "high": 10.4 + index * 0.03,
        "low": 9.7 + index * 0.03,
        "close": 10.1 + index * 0.03,
        "volume": 100_000 + index * 100,
    } for index in range(count)]


def training_market_records(count: int) -> list[dict[str, object]]:
    start = date(2024, 1, 1)
    records: list[dict[str, object]] = []
    for index in range(count):
        close = 20 + index * 0.005 + math.sin(index / 8) * 2
        records.append({
            "data_date": (start + timedelta(days=index)).isoformat(),
            "open": close - 0.1,
            "high": close + 0.3,
            "low": close - 0.3,
            "close": close,
            "volume": 200_000 + int(50_000 * (1 + math.sin(index / 5))),
        })
    return records


class QuantJobServiceTest(unittest.TestCase):
    def test_auto_search_job_uses_configured_candidates_and_returns_search_summary(self):
        data = copy.deepcopy(load_quant_config().data)
        data["autoSearch"] = {
            "maximumCandidates": 1,
            "timeBudgetSeconds": 60,
            "noImprovementLimit": 1,
            "finalHoldoutFraction": 0.2,
            "minimumFinalHoldoutSamples": 40,
            "finalHoldoutValidation": copy.deepcopy(
                data["autoSearch"]["finalHoldoutValidation"]
            ),
            "candidates": [
                {"algorithm": "ELASTIC_NET", "parameters": {}},
            ],
        }
        test_root = Path(__file__).resolve().parents[1] / ".test-tmp"
        test_root.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=test_root) as root:
            service = QuantJobService(
                Path(root),
                QuantConfig(data),
                max_workers=1,
            )
            created = service.submit({
                "type": "AUTO_SEARCH",
                "datasetVersion": "e" * 64,
                "productType": "STOCK",
                "horizonCode": "WAVE",
                "horizonDays": 20,
                "records": training_market_records(700),
            })

            completed = self._wait(service, created["jobId"])
            service.executor.shutdown(wait=True)

            self.assertEqual("SUCCEEDED", completed["status"])
            self.assertEqual("AUTO_SEARCH", completed["type"])
            self.assertEqual(
                1,
                completed["result"]["searchSummary"]["evaluatedCandidates"],
            )
            self.assertEqual(
                completed["result"]["modelVersion"],
                completed["result"]["searchSummary"]["selectedModelVersion"],
            )

    def test_train_predict_job_persists_versioned_model_artifact(self):
        test_root = Path(__file__).resolve().parents[1] / ".test-tmp"
        test_root.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=test_root) as root:
            service = QuantJobService(Path(root), load_quant_config(), max_workers=1)
            created = service.submit({
                "type": "TRAIN_PREDICT",
                "datasetVersion": "d" * 64,
                "productType": "STOCK",
                "horizonCode": "WAVE",
                "horizonDays": 20,
                "records": training_market_records(700),
            })

            completed = self._wait(service, created["jobId"])
            service.executor.shutdown(wait=True)

            self.assertEqual("SUCCEEDED", completed["status"])
            result = completed["result"]
            model_path = Path(root) / "quant-models" / f'{result["modelVersion"]}.pkl'
            self.assertTrue(model_path.is_file())
            self.assertEqual(
                result["modelFileHash"],
                hashlib.sha256(model_path.read_bytes()).hexdigest(),
            )
            self.assertEqual("quant-research-v2", result["quantConfigVersion"])
            self.assertIn(result["modelStatus"], {"DRAFT", "VALIDATED"})
            self.assertEqual(
                result["modelStatus"],
                result["validationReport"]["lifecycle"],
            )
            self.assertIsInstance(result["validationReport"]["passed"], bool)
            self.assertEqual(
                "A_SHARE_EVENT_V1",
                result["backtestSummary"]["executionModel"],
            )
            self.assertIn("eventTotalReturn", result["backtestSummary"])
            self.assertIn("eventSharpe", result["backtestSummary"])

    def test_backtest_job_uses_a_share_event_execution_and_persists_fill_ledger(self):
        records = [
            {
                "data_date": (date(2026, 7, 1) + timedelta(days=index)).isoformat(),
                "open": 10.0,
                "high": 10.2,
                "low": 9.8,
                "close": 10.0,
                "previous_close": 10.0,
                "volume": 1_000_000,
            }
            for index in range(4)
        ]
        test_root = Path(__file__).resolve().parents[1] / ".test-tmp"
        test_root.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=test_root) as root:
            service = QuantJobService(Path(root), load_quant_config(), max_workers=1)
            created = service.submit({
                "type": "BACKTEST",
                "records": records,
                "signals": [0.10, 0.0, 0.0, 0.0],
            })

            completed = self._wait(service, created["jobId"])
            service.executor.shutdown(wait=True)

            self.assertEqual("SUCCEEDED", completed["status"])
            self.assertEqual(
                ["2026-07-02", "2026-07-03"],
                completed["result"]["fill_dates"],
            )
            self.assertEqual(["BUY", "SELL"], completed["result"]["fill_sides"])
            self.assertEqual(0.0, completed["result"]["final_quantity"])

    def test_factor_job_is_persisted_and_returns_safe_no_trade_without_model(self):
        test_root = Path(__file__).resolve().parents[1] / ".test-tmp"
        test_root.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=test_root) as root:
            service = QuantJobService(Path(root), load_quant_config(), max_workers=1)
            created = service.submit({
                "type": "FACTOR_ANALYSIS",
                "datasetVersion": "a" * 64,
                "productType": "STOCK",
                "horizonDays": 20,
                "records": market_records(180),
            })

            result = self._wait(service, created["jobId"])
            service.executor.shutdown(wait=True)

            self.assertEqual("SUCCEEDED", result["status"])
            self.assertEqual("NO_TRADE", result["result"]["action"])
            self.assertEqual("MODEL_UNAVAILABLE", result["result"]["riskFlags"][0])
            self.assertIn("BENCHMARK_UNAVAILABLE", result["result"]["riskFlags"])
            self.assertIsNone(result["result"]["benchmarkCode"])
            self.assertIsNone(result["result"]["targetWeight"])
            self.assertEqual(64, len(result["result"]["featureSetVersion"]))
            self.assertEqual(64, len(result["result"]["featureArtifactHash"]))
            self.assertTrue((Path(root) / result["result"]["featureArtifactUri"]).is_file())
            self.assertTrue((Path(root) / "quant-jobs" / f'{created["jobId"]}.json').is_file())

    def test_job_status_survives_service_restart(self):
        test_root = Path(__file__).resolve().parents[1] / ".test-tmp"
        test_root.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=test_root) as root:
            path = Path(root)
            service = QuantJobService(path, load_quant_config(), max_workers=1)
            created = service.submit({
                "type": "FACTOR_ANALYSIS",
                "datasetVersion": "b" * 64,
                "productType": "MUTUAL_FUND",
                "horizonDays": 30,
                "records": market_records(180),
            })
            completed = self._wait(service, created["jobId"])
            service.executor.shutdown(wait=True)

            restarted = QuantJobService(path, load_quant_config(), max_workers=1)

            self.assertEqual(completed, restarted.get(created["jobId"]))
            restarted.executor.shutdown(wait=True)

    def test_failed_job_reports_structured_error_instead_of_cached_result_claim(self):
        test_root = Path(__file__).resolve().parents[1] / ".test-tmp"
        test_root.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=test_root) as root:
            service = QuantJobService(Path(root), load_quant_config(), max_workers=1)
            created = service.submit({
                "type": "FACTOR_ANALYSIS",
                "datasetVersion": "c" * 64,
                "productType": "STOCK",
                "horizonDays": 20,
                "records": [],
            })

            completed = self._wait(service, created["jobId"])
            service.executor.shutdown(wait=True)

            self.assertEqual("FAILED", completed["status"])
            self.assertEqual("JOB_FAILED", completed["errorCode"])
            self.assertTrue(completed["errorSummary"])
            self.assertEqual(
                ["JOB_FAILED"],
                completed["result"]["riskFlags"],
            )
            self.assertNotIn("上一份有效结果", completed["result"]["userMessage"])

    def test_fund_training_without_official_benchmark_is_blocked(self):
        test_root = Path(__file__).resolve().parents[1] / ".test-tmp"
        test_root.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=test_root) as root:
            service = QuantJobService(Path(root), load_quant_config(), max_workers=1)
            created = service.submit({
                "type": "TRAIN_PREDICT",
                "datasetVersion": "d" * 64,
                "productType": "MUTUAL_FUND",
                "modelFamily": "QDII_INDEX_FUND",
                "horizonCode": "SHORT",
                "horizonDays": 20,
                "records": market_records(220),
            })

            completed = self._wait(service, created["jobId"])
            service.executor.shutdown(wait=True)

            self.assertEqual("FAILED", completed["status"])
            self.assertEqual("BENCHMARK_UNAVAILABLE", completed["errorCode"])
            self.assertEqual(
                ["BENCHMARK_UNAVAILABLE"],
                completed["result"]["riskFlags"],
            )
            self.assertEqual(
                "官方基准数据尚未准备完成，当前暂停模型训练",
                completed["result"]["userMessage"],
            )

    @staticmethod
    def _wait(service: QuantJobService, job_id: str) -> dict:
        deadline = time.time() + 30
        while time.time() < deadline:
            job = service.get(job_id)
            if job["status"] in {"SUCCEEDED", "FAILED"}:
                return job
            time.sleep(0.01)
        raise AssertionError("quant job did not finish")


if __name__ == "__main__":
    unittest.main()
