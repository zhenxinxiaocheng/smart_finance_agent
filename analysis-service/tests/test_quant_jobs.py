from __future__ import annotations

import tempfile
import time
import unittest
from datetime import date, timedelta
from pathlib import Path

from app.quant.config import load_quant_config
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


class QuantJobServiceTest(unittest.TestCase):
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

    @staticmethod
    def _wait(service: QuantJobService, job_id: str) -> dict:
        deadline = time.time() + 5
        while time.time() < deadline:
            job = service.get(job_id)
            if job["status"] in {"SUCCEEDED", "FAILED"}:
                return job
            time.sleep(0.01)
        raise AssertionError("quant job did not finish")


if __name__ == "__main__":
    unittest.main()
