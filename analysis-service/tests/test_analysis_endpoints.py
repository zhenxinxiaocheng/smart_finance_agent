from __future__ import annotations

import unittest
from datetime import date
import sys
from types import SimpleNamespace
from unittest.mock import patch
from fastapi import HTTPException

from pydantic import ValidationError

from app.main import (
    BacktestRequest,
    BenchmarkHistoryRequest,
    FundAnalysisRequest,
    FundamentalAnalysisRequest,
    TechnicalAnalysisRequest,
    backtest_analysis,
    fund_analysis,
    fundamental_analysis,
    technical_analysis,
    app,
    QuoteRequest,
    DataQualityValidateRequest,
    DataQualityReplayRequest,
    replay_data_quality,
    daily_quotes,
    validate_data_quality,
)
from app.providers import ProviderEmpty, ProviderUnavailable
from tests.test_analysis_engine import price_records


class AnalysisEndpointsTest(unittest.TestCase):
    def test_replay_passes_actual_dates_and_keeps_legacy_arguments_unchanged(self):
        original = dict(datasetVersion="a" * 64, qualityConfigVersion="data-quality-v7")
        with patch("app.main.quality_service.replay", return_value={}) as replay:
            replay_data_quality(DataQualityReplayRequest(**original))
            replay.assert_called_once_with(dataset_version="a" * 64, secondary_dataset_version=None,
                fund_category=None, quality_config_version="data-quality-v7")
            replay.reset_mock()
            replay_data_quality(DataQualityReplayRequest(**original, startDate="2026-01-02", endDate="2026-01-04",
                observedDates=["2026-01-02", "2026-01-04"]))
            replay.assert_called_once_with(dataset_version="a" * 64, secondary_dataset_version=None,
                fund_category=None, quality_config_version="data-quality-v7", continuation_dataset_versions=None,
                continuation_secondary_versions=None, start_date=date(2026, 1, 2), end_date=date(2026, 1, 4),
                observed_dates=[date(2026, 1, 2), date(2026, 1, 4)])
        with self.assertRaises(ValidationError):
            DataQualityReplayRequest(**original, observedDates=[])

    def test_us_fundamentals_are_unavailable_without_querying_the_a_share_source(self):
        def a_share_source(**kwargs):
            self.fail('US symbols must not reach the A-share financial source')
        with patch.dict(sys.modules, {'akshare': SimpleNamespace(stock_financial_analysis_indicator=a_share_source)}):
            for code in ('NVDA', 'T', 'BRK.B'):
                with self.subTest(code=code):
                    result = fundamental_analysis(FundamentalAnalysisRequest(code=code, market='NYSE'))
                    self.assertEqual('INSUFFICIENT', result['status'])
                    self.assertEqual({}, result['dimensions'])
                    self.assertIn('未接入', result['reason'])

    def test_a_share_financial_source_still_rejects_non_six_digit_codes(self):
        with patch.dict(sys.modules, {'akshare': SimpleNamespace()}):
            for code in ('12345', 'ABCDEF'):
                with self.subTest(code=code):
                    with self.assertRaises(HTTPException) as error:
                        fundamental_analysis(FundamentalAnalysisRequest(code=code, market='SSE'))
                    self.assertEqual(400, error.exception.status_code)

    def test_nonpositive_research_prices_return_controlled_input_errors(self):
        records=price_records(320);records[160]['close']='-1'
        requests=(
            (technical_analysis,TechnicalAnalysisRequest(records=records,horizons={'SHORT':[5,20]},primaryHorizon='SHORT')),
            (backtest_analysis,BacktestRequest(records=records,horizons={'SHORT':[5,20]})),
            (fund_analysis,FundAnalysisRequest(records=records,fundCategory='INDEX_FUND')),
        )
        for endpoint,request in requests:
            with self.subTest(endpoint=endpoint):
                with self.assertRaises(HTTPException) as error:
                    endpoint(request)
                self.assertEqual(400,error.exception.status_code)
                self.assertIn('非正价格',str(error.exception.detail))

    def test_market_empty_window_is_successful_query_and_failure_stays_error(self):
        request = QuoteRequest(code="SPY", market="AMEX", product_type="ETF",
            start_date="2026-07-17", end_date="2026-07-18", adjust_type="NONE")
        with patch("app.main.daily_history", return_value=[]):
            self.assertEqual("EMPTY", daily_quotes(request)["result"])
            self.assertEqual([], daily_quotes(request)["records"])
        with patch("app.main.daily_history", side_effect=ProviderUnavailable("no records due to timeout")):
            with self.assertRaises(HTTPException) as error:
                daily_quotes(request)
            self.assertEqual(503, error.exception.status_code)
            self.assertEqual("SOURCE_ERROR", error.exception.detail["code"])

    def test_quality_normal_empty_is_not_an_http_failure(self):
        request = DataQualityValidateRequest(productType="MUTUAL_FUND", code="010736", market="FUND_CN",
            frequency="DAY", adjustType="NONE", startDate="2026-07-17", endDate="2026-07-18",
            qualityConfigVersion="data-quality-v7")
        with patch("app.main.quality_service.validate", side_effect=ProviderEmpty("no records")):
            self.assertEqual("EMPTY", validate_data_quality(request)["result"])

    def test_benchmark_history_contract_is_available_to_backend(self):
        request = BenchmarkHistoryRequest(
            benchmarkCode="CSI300_95_CASH_5",
            startDate="2026-01-01",
            endDate="2026-07-01",
        )
        paths = {(route.path, method) for route in app.routes for method in getattr(route, "methods", set())}

        self.assertEqual("CSI300_95_CASH_5", request.benchmark_code)
        self.assertIn(("/internal/v1/market-data/benchmarks/daily", "POST"), paths)

    def test_technical_endpoint_keeps_financial_context_out_of_request(self):
        request = TechnicalAnalysisRequest(
            records=price_records(260),
            horizons={"WAVE": [7, 45], "POSITION": [80, 200]},
            primaryHorizon="WAVE",
            marketSnapshot={
                "turnoverRate": 3.2,
                "volumeRatio": 1.4,
                "amplitude": 4.1,
            },
        )

        result = technical_analysis(request)

        self.assertEqual("READY", result["status"])
        self.assertNotIn("riskPreference", request.model_dump())
        self.assertEqual({"WAVE", "POSITION"}, set(result["horizons"]))
        self.assertEqual("technical-strategy-v5", result["strategyVersion"])
        self.assertIn("outlook", result)
        self.assertEqual(3.2, request.market_snapshot.turnover_rate)

    def test_technical_market_snapshot_rejects_unknown_context(self):
        with self.assertRaises(ValidationError):
            TechnicalAnalysisRequest(
                records=price_records(260),
                horizons={"WAVE": [7, 45]},
                primaryHorizon="WAVE",
                marketSnapshot={"wealth": 1_000_000},
            )

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
        fund = fund_analysis(FundAnalysisRequest(
            records=price_records(260, step=0.03),
            fundCategory="INDEX_FUND",
        ))
        backtest = backtest_analysis(BacktestRequest(
            records=price_records(300),
            horizons={"WAVE": [7, 45], "PERSONAL_LONG": [120, 900]},
        ))

        self.assertEqual("READY", fundamental["status"])
        self.assertEqual("READY", fund["status"])
        self.assertGreater(backtest["horizons"]["WAVE"]["occurrences"], 0)
        self.assertEqual(510, backtest["horizons"]["PERSONAL_LONG"]["evaluationDays"])

    def test_fund_endpoint_accepts_user_horizons_and_returns_full_history(self):
        fund = fund_analysis(FundAnalysisRequest(
            records=price_records(520, step=0.03),
            horizons={
                "SHORT": {"minDays": 5, "maxDays": 20, "targetDays": 10},
                "MEDIUM": {"minDays": 20, "maxDays": 120, "targetDays": 60},
            },
            primaryHorizon="MEDIUM",
            fundCategory="QDII_INDEX_FUND",
        ))

        self.assertEqual("READY", fund["status"])
        self.assertEqual("MEDIUM", fund["primaryHorizon"])
        self.assertIn("fullHistory", fund)
        self.assertIn("horizons", fund)
        self.assertEqual({"SHORT", "MEDIUM"}, set(fund["horizons"]))
        self.assertEqual("QDII_INDEX_FUND", fund["fundCategory"])
        self.assertEqual("UNAVAILABLE", fund["adviceStatus"])

    def test_fund_endpoint_passes_existing_benchmark_payload_to_analysis(self):
        records = price_records(30, step=0.03)
        benchmark_records = [
            {"data_date": item["data_date"], "close": item["close"]}
            for item in records
        ]

        fund = fund_analysis(FundAnalysisRequest(
            records=records,
            horizons={
                "SHORT": {"minDays": 5, "maxDays": 5, "targetDays": 5},
            },
            primaryHorizon="SHORT",
            fundCategory="INDEX_FUND",
            benchmark={
                "status": "READY",
                "code": "CSI300",
                "sourceVersion": "CSI-OFFICIAL-V1",
                "records": benchmark_records,
            },
        ))

        self.assertEqual("CSI300", fund["benchmark"]["code"])
        self.assertEqual(6, fund["horizons"]["SHORT"]["alignedObservationCount"])

    def test_fund_endpoint_without_category_fails_closed(self):
        fund = fund_analysis(FundAnalysisRequest(
            records=price_records(260, step=0.03),
            horizons={
                "SHORT": {"minDays": 5, "maxDays": 20, "targetDays": 10},
            },
            primaryHorizon="SHORT",
        ))

        self.assertEqual("INSUFFICIENT", fund["status"])
        self.assertEqual("FUND_CATEGORY_UNAVAILABLE", fund["reasonCode"])
        self.assertEqual("WAIT", fund["action"])


if __name__ == "__main__":
    unittest.main()
