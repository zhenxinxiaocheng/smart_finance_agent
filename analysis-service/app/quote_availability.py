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
    return tuple(day.date() for day in calendar.sessions)


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
        if profile:
            # Load complete years, including a predecessor for January publication lag.
            try:
                dates = sorted({day for year in range(min(start.year, local.year) - 1, max(end.year, local.year) + 1)
                                for day in self._calendar_loader(profile['calendar'], year)})
                if not any(day.year == local.year for day in dates):
                    raise ValueError('current-year calendar is unavailable')
                through = local.date() if local.time() >= time.fromisoformat(profile['cutoff']) else local.date() - timedelta(days=1)
                sessions = [day for day in dates if day <= through]
                lag = profile['lagSessions']
                if len(sessions) > lag:
                    target = min(end, sessions[-1 - lag])
                    known = profile['expectedDatesKnown']
                    expected = [day for day in dates if start <= day <= target] if known else []
            except (RuntimeError, ValueError, ImportError):
                # An unavailable or unsupported calendar is not proof of a holiday or gap.
                pass
        if delisting_date is not None:
            target = min(target, delisting_date)
            expected = [day for day in expected if day <= target]
        return {'targetDate': target.isoformat(), 'expectedDates': [day.isoformat() for day in expected],
                'calendarKnown': known, 'ruleProfile': profile_name or 'UNKNOWN', 'ruleVersion': self._rules['version']}


quote_availability = QuoteAvailability()
