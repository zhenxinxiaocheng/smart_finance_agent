from __future__ import annotations

import unittest

from pydantic import ValidationError

from app.main import (
    BacktestRequest,
    FundAnalysisRequest,
    FundamentalAnalysisRequest,
    TechnicalAnalysisRequest,
    backtest_analysis,
    fund_analysis,
    fundamental_analysis,
    technical_analysis,
)
from tests.test_analysis_engine import price_records


class AnalysisEndpointsTest(unittest.TestCase):
    def test_technical_endpoint_keeps_financial_context_out_of_request(self):
        request = TechnicalAnalysisRequest(
            records=price_records(260),
            horizons={"WAVE": [7, 45], "POSITION": [80, 200]},
            primaryHorizon="WAVE",
        )

        result = technical_analysis(request)

        self.assertEqual("READY", result["status"])
        self.assertNotIn("riskPreference", request.model_dump())
        self.assertEqual({"WAVE", "POSITION"}, set(result["horizons"]))

    def test_technical_request_requires_valid_caller_horizons(self):
        with self.assertRaises(ValidationError):
            TechnicalAnalysisRequest(records=price_records(260))
        with self.assertRaises(ValidationError):
            TechnicalAnalysisRequest(
                records=price_records(260),
                horizons={"WAVE": [45, 7]},
                primaryHorizon="WAVE",
            )
        with self.assertRaises(ValidationError):
            TechnicalAnalysisRequest(
                records=price_records(260),
                horizons={"WAVE": [7, 45]},
                primaryHorizon="MISSING",
            )

    def test_fundamental_fund_and_backtest_endpoints(self):
        fundamental = fundamental_analysis(FundamentalAnalysisRequest(periods=[
            {"period": "2025", "revenue": 120, "netProfit": 15, "roe": 14, "grossMargin": 35,
             "netMargin": 12, "operatingCashFlow": 18, "debtRatio": 35, "currentRatio": 1.9,
             "pe": 16, "pb": 2, "dividendYield": 2.2},
            {"period": "2024", "revenue": 110, "netProfit": 13, "roe": 13, "grossMargin": 34,
             "netMargin": 11, "operatingCashFlow": 15, "debtRatio": 37, "currentRatio": 1.8,
             "pe": 18, "pb": 2.2, "dividendYield": 2},
            {"period": "2023", "revenue": 100, "netProfit": 11, "roe": 12, "grossMargin": 33,
             "netMargin": 10, "operatingCashFlow": 12, "debtRatio": 39, "currentRatio": 1.7,
             "pe": 20, "pb": 2.4, "dividendYield": 1.8},
        ]))
        fund = fund_analysis(FundAnalysisRequest(records=price_records(260, step=0.03)))
        backtest = backtest_analysis(BacktestRequest(
            records=price_records(300),
            horizons={"WAVE": [7, 45], "PERSONAL_LONG": [120, 900]},
        ))

        self.assertEqual("READY", fundamental["status"])
        self.assertEqual("READY", fund["status"])
        self.assertGreater(backtest["horizons"]["WAVE"]["occurrences"], 0)
        self.assertEqual(510, backtest["horizons"]["PERSONAL_LONG"]["evaluationDays"])


if __name__ == "__main__":
    unittest.main()
