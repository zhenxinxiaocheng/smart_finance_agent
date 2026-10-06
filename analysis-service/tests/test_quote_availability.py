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
        self.policy = QuoteAvailability(lambda name, year: self.days[name])
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

    def test_exchange_library_observes_us_independence_day_and_hk_october_holidays(self):
        from app.quote_availability import exchange_dates
        us = exchange_dates('XNYS', 2026)
        hk = exchange_dates('XHKG', 2026)
        self.assertNotIn(date(2026, 7, 3), us)
        self.assertIn(date(2026, 10, 1), us)
        self.assertNotIn(date(2026, 10, 1), hk)
        self.assertIn(date(2026, 10, 5), hk)
