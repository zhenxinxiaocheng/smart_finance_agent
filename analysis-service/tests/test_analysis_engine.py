from __future__ import annotations

import math
import unittest
from datetime import date, timedelta

import app.analysis as analysis_module
from app.analysis import (
    analyze_fund,
    analyze_fundamentals,
    analyze_technical,
    backtest_horizons,
    backtest_signals,
)


def price_records(count: int = 320, *, step: float = 0.18) -> list[dict[str, str]]:
    start = date(2025, 1, 1)
    records: list[dict[str, str]] = []
    for index in range(count):
        close = 20 + index * step + math.sin(index / 6) * 0.8
        records.append({
            "data_date": (start + timedelta(days=index)).isoformat(),
            "open": f"{close - 0.25:.4f}",
            "high": f"{close + 0.65:.4f}",
            "low": f"{close - 0.75:.4f}",
            "close": f"{close:.4f}",
            "volume": f"{100000 + index * 200}",
        })
    return records


class AnalysisEngineTest(unittest.TestCase):
    def test_technical_analysis_returns_chart_indicators_and_price_zones(self):
        records = price_records()

        result = analyze_technical(
            records,
            {"SHORT": [5, 20], "MEDIUM": [20, 120], "LONG": [120, 300]},
            "SHORT",
        )

        self.assertEqual(len(records), len(result["series"]))
        latest = result["series"][-1]
        expected_ma5 = sum(float(item["close"]) for item in records[-5:]) / 5
        self.assertAlmostEqual(expected_ma5, latest["ma5"], places=4)
        self.assertIsNotNone(latest["macd"])
        self.assertIsNotNone(latest["rsi"])
        self.assertIsNotNone(latest["atr"])
        self.assertEqual("technical-strategy-v3", result["strategyVersion"])
        self.assertEqual({"SHORT", "MEDIUM", "LONG"}, set(result["horizons"]))
        self.assertIn(result["horizons"]["SHORT"]["verdict"], {"FAVORABLE", "WAIT", "WEAK"})
        self.assertLess(result["levels"]["support"]["low"], result["levels"]["support"]["high"])
        self.assertLess(result["levels"]["resistance"]["low"], result["levels"]["resistance"]["high"])

    def test_technical_analysis_calculates_price_zones_for_each_horizon(self):
        records = price_records(520, step=0.03)
        for index in range(460, 520):
            close = 24 + (index - 460) * 0.1
            records[index].update({
                "open": f"{close - 0.05:.4f}",
                "high": f"{close + 0.12:.4f}",
                "low": f"{close - 0.12:.4f}",
                "close": f"{close:.4f}",
            })

        result = analyze_technical(records, {
            "SHORT": [5, 20],
            "MEDIUM": [20, 120],
            "LONG": [120, 500],
        }, "SHORT")

        expected_lookbacks = {"SHORT": 60, "MEDIUM": 360, "LONG": 520}
        for name, lookback in expected_lookbacks.items():
            horizon = result["horizons"][name]
            self.assertEqual(lookback, horizon["levelLookbackDays"])
            self.assertIn("support", horizon["levels"])
            self.assertIn("resistance", horizon["levels"])
            self.assertIn("risk", horizon["actionZones"])
        self.assertNotEqual(result["horizons"]["SHORT"]["levels"],
                            result["horizons"]["MEDIUM"]["levels"])
        self.assertEqual(result["horizons"]["SHORT"]["levels"], result["levels"])
        self.assertEqual(result["horizons"]["SHORT"]["actionZones"], result["actionZones"])

    def test_user_codes_and_numbers_are_authoritative(self):
        result = analyze_technical(
            price_records(620),
            {"WAVE": [7, 45], "POSITION": [80, 260]},
            "POSITION",
            {"turnoverRate": 3.2, "volumeRatio": 1.4, "amplitude": 4.1},
        )

        self.assertEqual({"WAVE", "POSITION"}, set(result["horizons"]))
        self.assertEqual(result["horizons"]["POSITION"]["score"], result["score"])
        self.assertEqual(result["horizons"]["POSITION"]["levels"], result["levels"])
        self.assertEqual(result["horizons"]["POSITION"]["outlook"], result["outlook"])
        self.assertIn(result["outlook"]["direction"], {
            "BULLISH", "LEAN_BULLISH", "SIDEWAYS", "LEAN_BEARISH", "BEARISH",
        })
        self.assertNotIn("quant", result["outlook"])
        self.assertEqual(135, result["horizons"]["WAVE"]["levelLookbackDays"])

    def test_incomplete_long_horizon_returns_limited_outlook_from_available_history(self):
        result = analyze_technical(
            price_records(200),
            {"PERSONAL_LONG": [300, 900]},
            "PERSONAL_LONG",
        )

        horizon = result["horizons"]["PERSONAL_LONG"]
        self.assertEqual("LIMITED", result["status"])
        self.assertEqual("LIMITED", horizon["status"])
        self.assertEqual(200, horizon["availableHistoryDays"])
        self.assertEqual(900, horizon["requiredHistoryDays"])
        self.assertEqual(199, horizon["effectiveLookbackDays"])
        self.assertEqual("LOW", horizon["outlook"]["confidence"])
        self.assertIn("HORIZON_HISTORY_INCOMPLETE", horizon["outlook"]["missingInputs"])
        self.assertIn("score", horizon)
        self.assertIn("score", result)

    def test_fund_analysis_reports_returns_volatility_and_drawdown(self):
        records = price_records(260, step=0.04)
        records[180]["close"] = "16.0"

        result = analyze_fund(records)

        self.assertEqual(
            ["ONE_MONTH", "THREE_MONTHS", "ONE_YEAR"],
            [item["code"] for item in result["returnMetrics"]],
        )
        self.assertGreater(result["annualizedVolatility"], 0)
        self.assertLess(result["maxDrawdown"], 0)
        self.assertIn(result["action"], {"ACCUMULATE", "HOLD", "PAUSE", "TAKE_PROFIT"})
        self.assertGreaterEqual(result["score"], 0)
        self.assertLessEqual(result["score"], 100)
        self.assertIn(result["verdict"], {"FAVORABLE", "WAIT", "WEAK"})
        self.assertIsNotNone(result["series"][-1]["ma20"])

    def test_fund_analysis_accepts_canonical_nav_records(self):
        records = [
            {
                "data_date": item["data_date"],
                "nav": item["close"],
            }
            for item in price_records(260, step=0.04)
        ]

        result = analyze_fund(records)

        self.assertEqual("READY", result["status"])
        self.assertEqual(260, len(result["series"]))
        self.assertGreater(result["score"], 0)

    def test_fundamental_analysis_requires_three_periods_and_four_dimensions(self):
        insufficient = analyze_fundamentals([
            {"period": "2025", "revenue": 100, "netProfit": 10, "roe": 8},
            {"period": "2024", "revenue": 90, "netProfit": 9, "roe": 7},
        ])
        self.assertEqual("INSUFFICIENT", insufficient["verdict"])
        self.assertEqual(3, insufficient["requirements"]["minimumPeriods"])
        self.assertIn("当前策略至少需要", insufficient["reason"])

        result = analyze_fundamentals([
            {"period": "2025", "revenue": 135, "netProfit": 18, "roe": 15, "grossMargin": 36,
             "netMargin": 13, "operatingCashFlow": 21, "debtRatio": 38, "currentRatio": 1.8,
             "pe": 18, "pb": 2.1, "dividendYield": 2.0},
            {"period": "2024", "revenue": 118, "netProfit": 15, "roe": 13, "grossMargin": 34,
             "netMargin": 12, "operatingCashFlow": 16, "debtRatio": 40, "currentRatio": 1.6,
             "pe": 20, "pb": 2.3, "dividendYield": 1.8},
            {"period": "2023", "revenue": 100, "netProfit": 12, "roe": 11, "grossMargin": 32,
             "netMargin": 11, "operatingCashFlow": 13, "debtRatio": 42, "currentRatio": 1.5,
             "pe": 22, "pb": 2.5, "dividendYield": 1.5},
        ])

        self.assertNotEqual("INSUFFICIENT", result["verdict"])
        self.assertGreaterEqual(result["coverage"], 4)
        self.assertEqual(4, result["requirements"]["minimumDimensions"])
        self.assertEqual({"profitability", "growth", "cashQuality", "resilience", "valuation"},
                         set(result["dimensions"]))

    def test_backtest_returns_observation_count_win_rate_and_drawdown(self):
        result = backtest_signals(price_records(360), horizon_days=20)

        self.assertGreater(result["occurrences"], 0)
        self.assertGreaterEqual(result["winRate"], 0)
        self.assertLessEqual(result["winRate"], 100)
        self.assertIn("medianForwardReturn", result)
        self.assertLessEqual(result["maxDrawdown"], 0)

    def test_backtest_uses_each_user_range_instead_of_twenty_day_fallback(self):
        result = backtest_horizons(
            price_records(620),
            {"WAVE": [7, 45], "POSITION": [80, 260]},
        )

        self.assertEqual(26, result["horizons"]["WAVE"]["evaluationDays"])
        self.assertEqual(170, result["horizons"]["POSITION"]["evaluationDays"])
        self.assertNotEqual(
            result["horizons"]["WAVE"]["evaluationDays"],
            result["horizons"]["POSITION"]["evaluationDays"],
        )

    def test_backtest_replays_same_direction_rule_as_live_outlook(self):
        records = price_records(620)
        technical = analyze_technical(records, {"WAVE": [7, 45]}, "WAVE")

        replay = backtest_horizons(records, {"WAVE": [7, 45]})["horizons"]["WAVE"]

        self.assertEqual(technical["outlook"]["direction"], replay["matchedDirection"])
        self.assertEqual(replay["positiveRate"], replay["winRate"])
        self.assertGreater(replay["occurrences"], 0)
        self.assertLessEqual(replay["maximumAdverseExcursion"], 0)
        self.assertIn("invalidationRate", replay)

    def test_historical_outlook_does_not_read_later_prices(self):
        records = price_records(320)
        original = analysis_module.historical_outlook_at(records, 220, [7, 45])
        changed = price_records(320)
        for row in changed[221:]:
            row.update({"open": "9999", "high": "9999", "low": "9999", "close": "9999"})

        self.assertEqual(
            original,
            analysis_module.historical_outlook_at(changed, 220, [7, 45]),
        )


if __name__ == "__main__":
    unittest.main()
