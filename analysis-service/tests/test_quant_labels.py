from __future__ import annotations

import unittest
from datetime import date, timedelta

from app.quant.config import load_quant_config
from app.quant.engine import QuantEngine


class QuantAbsoluteReturnLabelTest(unittest.TestCase):
    def test_profit_label_uses_future_net_absolute_return_not_benchmark_excess(self):
        start = date(2025, 1, 1)
        asset_records = []
        benchmark_records = []
        for index in range(180):
            asset_price = 10.0 * (1.001 ** index)
            benchmark_price = 10.0 * (1.002 ** index)
            day = (start + timedelta(days=index)).isoformat()
            asset_records.append({
                "data_date": day,
                "open": asset_price,
                "high": asset_price,
                "low": asset_price,
                "close": asset_price,
                "volume": 100_000,
            })
            benchmark_records.append({
                "data_date": day,
                "open": benchmark_price,
                "high": benchmark_price,
                "low": benchmark_price,
                "close": benchmark_price,
                "volume": 100_000,
            })

        sample = QuantEngine(load_quant_config()).training_samples(
            asset_records,
            "STOCK",
            20,
            benchmark_records,
        )[0]

        self.assertGreater(sample.net_return, 0)
        self.assertTrue(sample.positive_return)
        self.assertFalse(sample.negative_return)
        self.assertLess(sample.net_excess_return, 0)
        self.assertFalse(sample.positive_excess)


if __name__ == "__main__":
    unittest.main()
