"""Daily publication bounds, separate from proof of historical date coverage.

Fund category supplies a conservative disclosure window, never an overseas
exchange calendar. Unknown/money/QDII/FOF valuation dates remain unverified.
"""
from datetime import date, datetime, time, timedelta
from functools import lru_cache
import json
from pathlib import Path
from zoneinfo import ZoneInfo


def exchange_dates(name: str, year: int) -> tuple[date, ...]:
    if name == 'A_SHARE':
        from .providers import fetch_a_share_trade_calendar
        return tuple(date.fromisoformat(day) for day in fetch_a_share_trade_calendar(year))
    return _exchange_dates(name, year)


@lru_cache(maxsize=256)
def _exchange_dates(name: str, year: int) -> tuple[date, ...]:
    import exchange_calendars as calendars
    calendar = calendars.get_calendar(name, start=f'{year}-01-01', end=f'{year}-12-31')
    return tuple(day.date() for day in calendar.sessions if day.year == year)


class QuoteAvailability:
    def __init__(self, calendar_loader=exchange_dates):
        self._calendar_loader = calendar_loader
        self._rules = json.loads((Path(__file__).parents[1] / 'config/quote-availability-v1.json').read_text(encoding='utf-8'))

    def resolve(self, *, product_type: str, market: str, at: datetime,
                start: date, end: date, fund_category: str | None = None,
                delisting_date: date | None = None) -> dict:
        if at.tzinfo is None or start > end:
            raise ValueError('availability requires an aware timestamp and ordered dates')
        fund = product_type in ('FUND', 'MUTUAL_FUND')
        profile_name = (self._rules['fundProfiles'].get(fund_category) if fund and market == 'FUND_CN'
                        else None if fund
                        else self._rules['marketProfiles'].get(market))
        profile = self._rules['profiles'].get(profile_name)
        local = at.astimezone(ZoneInfo(profile['zone'] if profile else 'Asia/Shanghai'))
        target = min(end, local.date() - timedelta(days=1))
        known = False
        expected = []
        expected_through = None
        if profile:
            loaded = {}

            def load_year(year):
                if year not in loaded:
                    days = tuple(self._calendar_loader(profile['calendar'], year))
                    if not days or any(not isinstance(day, date) or isinstance(day, datetime) or day.year != year for day in days):
                        raise ValueError(f'calendar for {year} is unavailable or has invalid dates')
                    loaded[year] = tuple(sorted(set(days)))
                return loaded[year]

            # A missing historical year must not discard a valid current publication bound.
            try:
                through = local.date() if local.time() >= time.fromisoformat(profile['cutoff']) else local.date() - timedelta(days=1)
                sessions = [day for day in load_year(local.year) if day <= through]
                lag = profile['lagSessions']
                if len(sessions) <= lag:
                    sessions = list(load_year(local.year - 1)) + sessions
                if len(sessions) > lag:
                    target = min(end, sessions[-1 - lag])
            except (RuntimeError, ValueError, ImportError):
                pass
            if profile['expectedDatesKnown']:
                try:
                    # Every requested year must be available; do not bridge an absent year.
                    dates = []
                    for year in range(start.year, end.year + 1):
                        annual = load_year(year)
                        window_start = max(start, date(year, 1, 1))
                        window_end = min(end, date(year, 12, 31))
                        first, last = annual[0], annual[-1]
                        # The loader supplies the full annual schedule. Adjacent sessions
                        # bound year-edge holidays, never a missing month or partial year.
                        if window_start < first and window_start.month == first.month == 1:
                            first = load_year(year - 1)[-1]
                        if window_end > last and window_end.month == last.month == 12:
                            last = load_year(year + 1)[0]
                        if window_start < first or min(window_end, target) > last:
                            raise ValueError(f'calendar for {year} does not bound the requested observations')
                        dates.extend(annual)
                    proof_end = target
                    if end <= last and not any(max(start, target + timedelta(days=1)) <= day <= end for day in dates):
                        proof_end = end
                    if proof_end < start:
                        raise ValueError('requested observations are not yet published')
                    known = True
                    expected = [day for day in dates if start <= day <= target]
                    expected_through = proof_end
                except (RuntimeError, ValueError, ImportError):
                    # Unavailable or truncated calendars are never proof of a holiday or gap.
                    pass
        if delisting_date is not None:
            target = min(target, delisting_date)
            expected = [day for day in expected if day <= target]
            if expected_through is not None:
                expected_through = min(expected_through, delisting_date)
        return {'targetDate': target.isoformat(), 'expectedDates': [day.isoformat() for day in expected],
                'calendarKnown': known, 'expectedThroughDate': expected_through.isoformat() if expected_through else None,
                'ruleProfile': profile_name or 'UNKNOWN', 'ruleVersion': self._rules['version']}


quote_availability = QuoteAvailability()
