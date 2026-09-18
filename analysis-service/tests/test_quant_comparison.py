import pytest
from app.quant_workbench.engine import execute, comparison_series
from test_quant_workbench import request


def test_backtest_adds_initial_point_and_calendar_month_returns_without_changing_hold():
    payload = request()
    result = execute(payload)['result']
    for series in [result, result['buyAndHold']]:
        assert series['chartEquityCurve'][0] == {'date': payload['startDate'] + 'T00:00:00', 'equity': 100000, 'drawdown': 0.0}
        assert len(series['chartEquityCurve']) == len(series['equityCurve']) + 1
        previous = 100000
        for month in series['monthlyReturns']:
            month_end = [p for p in series['equityCurve'] if p['date'].startswith(month['month'])][-1]['equity']
            assert month['return'] == pytest.approx(month_end / previous - 1)
            previous = month_end
    assert result['benchmark']['type'] == 'UNIVERSE_EQUAL_WEIGHT_MATCHED_EXPOSURE'
    assert len(result['buyAndHold']['fills']) == 1


def test_tracking_normalizes_and_calculates_drawdown_and_monthly_returns():
    payload = request()
    payload['trackingIndex'] = {'status': 'READY', 'code': 'GLOBAL_INDEX:NASDAQ100', 'name': '纳斯达克100',
        'records': [{'data_date': payload['startDate'], 'close': '200'},
                    {'data_date': '2023-04-28', 'close': '220'},
                    {'data_date': '2023-05-31', 'close': '176'},
                    {'data_date': payload['endDate'], 'close': '240'}]}
    result = execute(payload)['result']
    index = result['trackingIndex']
    assert index['status'] == 'READY'
    assert index['name'] == '纳斯达克100'
    assert [p['equity'] for p in index['equityCurve']] == pytest.approx([100000, 110000, 88000, 120000])
    assert index['metrics']['netReturn'] == pytest.approx(.2)
    assert index['metrics']['maxDrawdown'] == pytest.approx(.2)
    assert index['monthlyReturns'][1]['return'] == pytest.approx(-.2)
    assert index['monthlyReturns'][-1]['return'] is None  # missing intervening months
    assert index['chartEquityCurve'][0] == result['chartEquityCurve'][0]


@pytest.mark.parametrize('tracking', [None, {'status': 'UNAVAILABLE'},
    {'status': 'READY', 'name': 'X', 'records': [{'date': '2023-06-01', 'close': 100}]},
    {'status': 'READY', 'name': 'X', 'records': [{'date': 'bad', 'close': 0}]}])
def test_unavailable_tracking_never_blocks_strategy(tracking):
    payload = request()
    payload['trackingIndex'] = tracking
    result = execute(payload)
    assert result['status'] == 'SUCCEEDED'
    assert result['result'].get('trackingIndex', {}).get('status') != 'READY'


def test_missing_first_month_is_not_reported_as_next_month_return():
    series = {'equityCurve': [{'date':'2023-02-28','equity':120,'drawdown':0},
                              {'date':'2023-03-31','equity':132,'drawdown':0}]}
    comparison_series(series, 100, '2023-01-15')
    assert series['monthlyReturns'][0] == {'month':'2023-02', 'return':None}
    assert series['monthlyReturns'][1]['return'] == pytest.approx(.1)


def test_index_uses_previous_observed_close_on_start_holiday_without_filling_dates():
    payload = request()
    payload['startDate'] = '2023-04-08'
    payload['trackingIndex'] = {'status':'READY','code':'CSI300','name':'沪深300','records':[
        {'date':'2023-04-07','close':100}, {'date':'2023-04-10','close':110}, {'date':payload['endDate'],'close':99}]}
    index = execute(payload)['result']['trackingIndex']
    assert [p['date'] for p in index['equityCurve']] == ['2023-04-10',payload['endDate']]
    assert index['equityCurve'][0]['equity'] == pytest.approx(110000)
    assert index['equityCurve'][1]['drawdown'] == pytest.approx(.1)
