from datetime import date, datetime, timezone
import unittest
from unittest.mock import patch

from app.quote_availability import QuoteAvailability


class QuoteAvailabilityTest(unittest.TestCase):
    def setUp(self):
        self.days = {
            'A_SHARE': [date(2026, 9, 28), date(2026, 9, 29), date(2026, 9, 30),
                        date(2026, 10, 8), date(2026, 10, 9), date(2026, 10, 12)],
            'XNYS': [date(2026, 9, 28), date(2026, 9, 29), date(2026, 9, 30),
                     date(2026, 10, 1), date(2026, 10, 2), date(2026, 10, 5), date(2026, 10, 6)],
        }
        self.policy = QuoteAvailability(lambda name, year: [day for day in self.days[name] if day.year == year])
        self.now = datetime(2026, 10, 6, 8, tzinfo=timezone.utc)

    def resolve(self, product='STOCK', market='SSE', category=None, **kwargs):
        return self.policy.resolve(product_type=product, market=market, fund_category=category,
                                   at=kwargs.pop('at', self.now), start=date(2026, 9, 28),
                                   end=date(2026, 10, 12), **kwargs)

    def test_mainland_holiday_does_not_close_us_exchange(self):
        self.assertEqual('2026-09-30', self.resolve()['targetDate'])
        self.assertEqual('2026-10-05', self.resolve(market='NASDAQ')['targetDate'])
        self.assertNotIn('2026-10-01', self.resolve()['expectedDates'])
        self.assertIn('2026-10-01', self.resolve(market='NASDAQ')['expectedDates'])

    def test_domestic_nav_and_qdii_publication_have_different_delays(self):
        domestic = self.resolve('MUTUAL_FUND', 'FUND_CN', 'INDEX_FUND')
        qdii = self.resolve('MUTUAL_FUND', 'FUND_CN', 'QDII_INDEX_FUND')
        self.assertEqual('2026-09-30', domestic['targetDate'])
        self.assertEqual('2026-09-29', qdii['targetDate'])
        self.assertFalse(qdii['calendarKnown'])
        self.assertEqual([], qdii['expectedDates'])

    def test_resumption_waits_for_disclosure_cutoff_and_moves_forward_afterwards(self):
        before = datetime(2026, 10, 8, 12, tzinfo=timezone.utc)
        after = datetime(2026, 10, 8, 15, tzinfo=timezone.utc)
        self.assertEqual('2026-09-29', self.resolve('MUTUAL_FUND', 'FUND_CN', 'QDII_INDEX_FUND', at=before)['targetDate'])
        self.assertEqual('2026-09-30', self.resolve('MUTUAL_FUND', 'FUND_CN', 'QDII_INDEX_FUND', at=after)['targetDate'])

    def test_money_unknown_and_fof_do_not_inherit_equity_gap_calendar(self):
        for category in ('MONEY_MARKET_FUND', 'FOF_FUND', None, 'OTHER'):
            with self.subTest(category=category):
                self.assertFalse(self.resolve('MUTUAL_FUND', 'FUND_CN', category)['calendarKnown'])

    def test_delisting_clips_both_target_and_expected_dates(self):
        result = self.resolve(delisting_date=date(2026, 9, 29))
        self.assertEqual('2026-09-29', result['targetDate'])
        self.assertEqual(['2026-09-28', '2026-09-29'], result['expectedDates'])

    def test_domestic_fund_category_does_not_imply_domestic_market(self):
        self.assertFalse(self.resolve('MUTUAL_FUND', 'HKEX', 'INDEX_FUND')['calendarKnown'])

    def test_missing_current_year_does_not_reuse_previous_year_as_calendar_proof(self):
        policy = QuoteAvailability(lambda _, year: [date(2025, 12, 31)] if year == 2025 else [])
        result = policy.resolve(product_type='STOCK', market='SSE', at=self.now,
                                start=date(2026, 1, 1), end=date(2026, 10, 6))
        self.assertFalse(result['calendarKnown'])

    def test_unavailable_calendar_cannot_prove_missing_dates(self):
        result = QuoteAvailability(lambda *_: []).resolve(
            product_type='STOCK', market='NYSE', at=self.now,
            start=date(2026, 9, 28), end=date(2026, 10, 6))
        self.assertFalse(result['calendarKnown'])
        self.assertEqual([], result['expectedDates'])

    def test_missing_requested_year_cannot_prove_history_but_keeps_current_publication_bound(self):
        policy = QuoteAvailability(lambda _, year: [date(2026, 10, 5), date(2026, 10, 6)] if year == 2026 else [])
        result = policy.resolve(product_type='STOCK', market='NASDAQ', at=self.now,
                                start=date(2025, 12, 1), end=date(2026, 10, 6))
        self.assertFalse(result['calendarKnown'])
        self.assertEqual('2026-10-05', result['targetDate'])
        self.assertEqual([], result['expectedDates'])

    def test_truncated_beginning_cannot_turn_missing_history_into_a_holiday(self):
        policy = QuoteAvailability(lambda _, year: [date(2026, 10, 5), date(2026, 10, 6)] if year == 2026 else [])
        result = policy.resolve(product_type='STOCK', market='NASDAQ', at=self.now,
                                start=date(2026, 9, 28), end=date(2026, 10, 6))
        self.assertFalse(result['calendarKnown'])

    def test_missing_middle_year_cannot_be_bridged_by_known_boundary_years(self):
        days = {2024: [date(2024, 6, 3), date(2024, 12, 31)],
                2026: [date(2026, 1, 2), date(2026, 10, 5), date(2026, 10, 6)]}
        result = QuoteAvailability(lambda _, year: days.get(year, [])).resolve(
            product_type='STOCK', market='NASDAQ', at=self.now,
            start=date(2024, 6, 3), end=date(2026, 10, 5))
        self.assertFalse(result['calendarKnown'])
        self.assertIsNone(result['expectedThroughDate'])

    def test_truncated_middle_year_cannot_prove_complete_multiyear_history(self):
        from app.quote_availability import exchange_dates
        for months in ((10,), tuple(range(1, 11))):
            with self.subTest(months=months):
                def loader(name, year):
                    days = exchange_dates(name, year)
                    return [day for day in days if day.month in months] if year == 2025 else days
                result = QuoteAvailability(loader).resolve(
                    product_type='STOCK', market='NASDAQ', at=self.now,
                    start=date(2024, 7, 1), end=date(2026, 10, 5))
                self.assertFalse(result['calendarKnown'])
                self.assertIsNone(result['expectedThroughDate'])

    def test_real_exchange_year_boundaries_preserve_holiday_and_weekend_proof(self):
        policy = QuoteAvailability()
        january = policy.resolve(product_type='STOCK', market='NASDAQ',
                                 at=datetime(2026, 1, 5, 8, tzinfo=timezone.utc),
                                 start=date(2026, 1, 1), end=date(2026, 1, 4))
        self.assertTrue(january['calendarKnown'])
        self.assertEqual(['2026-01-02'], january['expectedDates'])
        self.assertEqual('2026-01-04', january['expectedThroughDate'])
        december = policy.resolve(product_type='STOCK', market='NASDAQ',
                                  at=datetime(2027, 1, 4, 8, tzinfo=timezone.utc),
                                  start=date(2026, 12, 31), end=date(2027, 1, 3))
        self.assertTrue(december['calendarKnown'])
        self.assertEqual(['2026-12-31'], december['expectedDates'])
        self.assertEqual('2027-01-03', december['expectedThroughDate'])

    def test_availability_endpoint_preserves_the_requested_window_and_proof_boundary(self):
        from fastapi.encoders import jsonable_encoder
        import app.main as main
        policy = QuoteAvailability(lambda _, year: [date(2026, 7, 2), date(2026, 7, 6)] if year == 2026 else [])
        request = main.QuoteAvailabilityRequest.model_validate({
            'productType': 'STOCK', 'market': 'NASDAQ', 'fundCategory': None,
            'startDate': '2026-07-02', 'endDate': '2026-07-05',
            'at': '2026-07-05T20:00:00Z', 'delistingDate': None})
        with patch.object(main, 'quote_availability', policy):
            response = jsonable_encoder(main.daily_availability(request))
        self.assertEqual(['2026-07-02'], response['expectedDates'])
        self.assertEqual('2026-07-02', response['targetDate'])
        self.assertEqual('2026-07-05', response['expectedThroughDate'])

    def test_unpublished_session_is_not_a_confirmed_closed_tail(self):
        days = [date(2026, 10, 2), date(2026, 10, 5), date(2026, 10, 6)]
        policy = QuoteAvailability(lambda _, year: days if year == 2026 else [])
        result = policy.resolve(product_type='STOCK', market='NASDAQ', at=self.now,
                                start=date(2026, 10, 2), end=date(2026, 10, 6))
        self.assertEqual(['2026-10-02', '2026-10-05'], result['expectedDates'])
        self.assertEqual('2026-10-05', result['expectedThroughDate'])

    def test_real_weekend_can_be_verified_beyond_the_last_publication(self):
        policy = QuoteAvailability(lambda _, year: [date(2026, 7, 2), date(2026, 7, 6)] if year == 2026 else [])
        result = policy.resolve(product_type='STOCK', market='NASDAQ',
                                at=datetime(2026, 7, 5, 20, tzinfo=timezone.utc),
                                start=date(2026, 7, 2), end=date(2026, 7, 5))
        self.assertEqual('2026-07-02', result['targetDate'])
        self.assertEqual(['2026-07-02'], result['expectedDates'])
        self.assertEqual('2026-07-05', result['expectedThroughDate'])

    def test_exchange_library_observes_us_independence_day_and_hk_october_holidays(self):
        from app.quote_availability import exchange_dates
        us = exchange_dates('XNYS', 2026)
        hk = exchange_dates('XHKG', 2026)
        self.assertNotIn(date(2026, 7, 3), us)
        self.assertIn(date(2026, 10, 1), us)
        self.assertNotIn(date(2026, 10, 1), hk)
        self.assertIn(date(2026, 10, 5), hk)
