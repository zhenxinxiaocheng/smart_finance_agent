from copy import deepcopy

import pytest

from app.quant_workbench.engine import execute, prepare, simulate
from test_quant_workbench import request


@pytest.mark.parametrize('asset_class', ['STOCK', 'ETF', 'FUND'])
def test_single_asset_uses_execution_rules_and_holds_to_end(asset_class):
    payload = request(asset_class)
    result = execute(payload)['result']
    baseline = result['buyAndHold']
    assert baseline['status'] == 'READY'
    assert baseline['type'] == 'BUY_AND_HOLD'
    assert result['benchmark']['type'] == 'UNIVERSE_EQUAL_WEIGHT_MATCHED_EXPOSURE'
    assert len(baseline['fills']) == 1
    fill = baseline['fills'][0]
    assert fill['side'] == 'BUY'
    assert fill['date'] == payload['startDate']
    assert fill['fee'] == pytest.approx(round(fill['notional'] * .001, 2))
    c, _ = prepare(payload)
    bar = payload['assets'][0]['bars'][65]
    assert fill['price'] == pytest.approx(bar['close'] if asset_class == 'FUND' else bar['open'] * (1 + c['slippageBps'] / 10000))
    assert fill['quantity'] % 100 == 0 if asset_class != 'FUND' else round(fill['quantity'], c['fundShareDecimals']) == fill['quantity']
    assert baseline['equityCurve'][-1]['equity'] == pytest.approx(baseline['cash'] + baseline['positions'][0]['marketValue'])
    assert [p['date'] for p in baseline['equityCurve']] == [p['date'] for p in result['equityCurve']]
    assert baseline['signals'] == baseline['targetHistory'] == []
    if asset_class == 'FUND':
        assert fill['confirmedDate'] > fill['date']


def test_equal_cash_budgets_do_not_rebalance_or_follow_strategy():
    payload = request()
    second = deepcopy(payload['assets'][0])
    second['id'] = 'asset-2'
    for i, bar in enumerate(second['bars']):
        bar['open'] = bar['close'] = 20 - i * .04
    payload['assets'].append(second)
    first = execute(payload)['result']
    baseline = first['buyAndHold']
    assert len(baseline['fills']) == 2
    for fill in baseline['fills']:
        spent = fill['notional'] + fill['fee']
        assert spent <= 50000
        assert 50000 - spent < 100 * fill['price'] * 1.001 + .02
    for fill in baseline['fills']:
        assert all(p['positions'][fill['assetId']]['quantity'] == fill['quantity'] for p in baseline['positionHistory'])
    values = [p['marketValue'] for p in baseline['positions']]
    assert abs(values[0] / sum(values) - .5) > .05
    payload['config'].update(maxWeight=.1, rebalanceDays=1, maxDrawdown=.01)
    changed = execute(payload)['result']
    assert first['targetHistory'] != changed['targetHistory']
    for key in ['metrics', 'equityCurve', 'positions', 'cash']:
        assert baseline[key] == changed['buyAndHold'][key]
    c, assets = prepare(payload)
    reference = {r['date']: {aid: sum(r['weights'].values()) / len(assets) for aid in assets} for r in changed['targetHistory']}
    original, _ = simulate(payload, c, assets, benchmark_targets=reference)
    assert changed['benchmark']['metrics'] == original['metrics']
    assert changed['benchmark']['equityCurve'] == original['equityCurve']


def test_waits_for_first_executable_day_and_reports_unavailable_assets():
    payload = request()
    payload['assets'][0]['bars'][65]['suspended'] = True
    result = execute(payload)['result']['buyAndHold']
    assert result['fills'][0]['date'] == payload['assets'][0]['bars'][66]['date']
    for bar in payload['assets'][0]['bars'][65:]:
        bar['suspended'] = True
    response = execute(payload)
    assert response['status'] == 'SUCCEEDED'
    assert response['result']['buyAndHold']['status'] == 'UNAVAILABLE'
    assert response['result']['buyAndHold']['reason']
    assert response['result']['buyAndHold']['unfilledAssetIds'] == ['asset-1']


def test_drawdown_does_not_liquidate_buy_and_hold():
    payload = request()
    payload['config']['maxDrawdown'] = .01
    for bar in payload['assets'][0]['bars'][80:]:
        bar['open'] *= .5
        bar['close'] *= .5
    result = execute(payload)['result']
    assert any(fill['side'] == 'SELL' for fill in result['fills'])
    baseline = result['buyAndHold']
    assert baseline['metrics']['maxDrawdown'] > .4
    assert len(baseline['fills']) == 1
    assert baseline['fills'][0]['quantity'] == baseline['positions'][0]['quantity']
    assert baseline['liquidated'] is False


def test_partial_universe_is_unavailable_and_keeps_unspent_budget():
    payload = request()
    second = deepcopy(payload['assets'][0])
    second['id'] = 'asset-2'
    for bar in second['bars']:
        bar['limitUp'] = bar['open']
    payload['assets'].append(second)
    baseline = execute(payload)['result']['buyAndHold']
    assert baseline['status'] == 'UNAVAILABLE'
    assert baseline['unfilledAssetIds'] == ['asset-2']
    assert len(baseline['fills']) == 1
    assert baseline['cash'] >= 50000


def test_fund_budgets_precision_and_settlement_share_execution_ledger():
    payload = request('FUND')
    payload['config'].update(fundShareDecimals=4, initialCash=100000.01, feeRate=.0015)
    second = deepcopy(payload['assets'][0])
    second['id'] = 'asset-2'
    payload['assets'].append(second)
    c, assets = prepare(payload)
    baseline, state = simulate(payload, c, assets, buy_and_hold=True)
    assert len(baseline['fills']) == 2
    assert baseline['fills'][0]['quantity'] == baseline['fills'][1]['quantity']
    for fill in baseline['fills']:
        assert fill['notional'] + fill['fee'] <= 50000
        assert round(fill['quantity'], 4) == fill['quantity']
    assert baseline['cash'] >= .01
    assert baseline['cash'] == pytest.approx(100000.01 + sum(r['amount'] for r in baseline['cashLedger']))
    payload['endDate'] = baseline['fills'][0]['confirmedDate']
    _, state = simulate(payload, c, assets, buy_and_hold=True)
    assert len(state['unsettledBuys']) == 2
    assert all(r['availableDate'] > payload['endDate'] for r in state['unsettledBuys'])


def test_strategy_signal_start_does_not_delay_initial_buy():
    payload = request()
    payload['signalStartDate'] = payload['assets'][0]['bars'][120]['date']
    result = execute(payload)['result']
    assert result['buyAndHold']['fills'][0]['date'] == payload['startDate']
    assert all(fill['date'] > payload['signalStartDate'] for fill in result['fills'])
