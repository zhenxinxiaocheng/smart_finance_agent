from copy import deepcopy

import pytest
import asyncio
import json

from app.main import app
from app.quant_workbench import engine
from tests.test_quant_workbench import request


def validate(payload, authenticated=True):
    # Exercise the real ASGI route and its auth without an optional HTTP client dependency.
    async def call():
        messages = []
        async def receive():
            return {'type': 'http.request', 'body': json.dumps(payload).encode(), 'more_body': False}
        async def send(message):
            messages.append(message)
        headers = [(b'content-type', b'application/json')]
        if authenticated:
            headers.append((b'x-internal-token', b'dev-analysis-token'))
        await app({'type': 'http', 'asgi': {'version': '3.0'}, 'http_version': '1.1',
                   'method': 'POST', 'scheme': 'http', 'path': '/quant/v2/validate-config',
                   'raw_path': b'/quant/v2/validate-config', 'query_string': b'',
                   'headers': headers, 'client': ('test', 1), 'server': ('test', 80)}, receive, send)
        class Response:
            status_code = next(m['status'] for m in messages if m['type'] == 'http.response.start')
            def json(self):
                return json.loads(b''.join(m.get('body', b'') for m in messages if m['type'] == 'http.response.body'))
        return Response()
    return asyncio.run(call())


def test_validation_is_internal():
    assert validate(request(), authenticated=False).status_code == 401


def test_validation_returns_current_config_without_simulation_or_mutation(monkeypatch):
    payload = request()
    payload['config'] = engine.prepare(payload)[0]
    before = deepcopy(payload)
    monkeypatch.setattr(engine, 'simulate', lambda *a, **k: pytest.fail('validation simulated'))
    response = validate(payload)
    assert response.status_code == 200
    assert response.json() == {'compatible': True, 'runtime': engine.runtime_info(),
                               'effectiveConfig': payload['config'],
                               'assumptions': expected_assumptions('STOCK'),
                               'benchmarkContract': EXPECTED_BENCHMARK}
    assert engine.validate_config(payload) == response.json()
    assert payload == before


@pytest.mark.parametrize(('field', 'value', 'reason'), [
    ('slowWindow', 0, 'INVALID_CONFIG'), ('strategyType', 'UNKNOWN', 'INVALID_STRATEGY')])
def test_incompatible_config_retains_engine_reason(field, value, reason):
    payload = request()
    payload['config'][field] = value
    response = validate(payload)
    assert response.status_code == 422
    assert response.json()['detail']['code'] == 'CURRENT_RUNTIME_CONFIG_INCOMPATIBLE'
    assert response.json()['detail']['reasonCode'] == reason


def test_date_range_is_validated_without_execution():
    payload = request()
    payload['startDate'] = '2099-01-01'
    response = validate(payload)
    assert response.status_code == 422
    assert response.json()['detail']['reasonCode'] == 'INVALID_DATE_RANGE'


@pytest.mark.parametrize('mode', ['missing', 'mismatch', 'lookahead', 'compatible'])
def test_ml_contract_and_lookahead_use_real_model_loader(tmp_path, monkeypatch, mode):
    import json
    monkeypatch.setenv('QUANT_V2_MODEL_DIR', str(tmp_path))
    payload = request()
    payload['config']['strategyType'] = 'ML_ELASTIC_NET'
    config, _ = engine.prepare(payload)
    payload['modelRef'] = 'a' * 32
    if mode != 'missing':
        folder = tmp_path / payload['modelRef']
        folder.mkdir()
        model = {'engineVersion': engine.VERSION, 'strategyType': 'ML_ELASTIC_NET',
                 'featureConfig': engine.feature_config(config),
                 'evaluatedThrough': payload['startDate'] if mode == 'lookahead' else '2020-01-01'}
        if mode == 'mismatch':
            model['featureConfig']['lookback'] += 1
        (folder / 'model.json').write_text(json.dumps(model), encoding='utf-8')
    monkeypatch.setattr(engine, 'simulate', lambda *a, **k: pytest.fail('validation simulated'))
    response = validate(payload)
    assert response.status_code == (200 if mode == 'compatible' else 422)
    if mode != 'compatible':
        assert response.json()['detail']['reasonCode'] == {
            'missing': 'MODEL_NOT_FOUND', 'mismatch': 'MODEL_CONFIG_MISMATCH', 'lookahead': 'MODEL_LOOKAHEAD'}[mode]
    else:
        assert response.json()['assumptions'] == expected_assumptions('STOCK')
        assert response.json()['benchmarkContract'] == EXPECTED_BENCHMARK


EXPECTED_BENCHMARK = {'status': 'READY', 'type': 'UNIVERSE_EQUAL_WEIGHT_MATCHED_EXPOSURE',
                      'name': '资产池等权基准（匹配策略目标仓位）'}


def expected_assumptions(asset_class):
    common = [
        'Fixed snapshot universe; historical constituent membership and survivorship bias are not certified.',
        'Signal at observed close, execution on a later session; no intraday execution.',
        'Trade consideration and fees rounded to CNY cents using HALF_UP; fund share precision 4 decimal places.',
    ]
    if asset_class == 'FUND':
        return common + [
            'Fund fees are configurable assumptions: subscription 0.001, redemption 0.002.',
            'NAV publication delay 1 calendar days; subscription share availability and redemption cash settlement 2 calendar days after confirmation.',
            'Fund dividends/reinvestment are unsupported; discontinuities block certification.',
        ]
    return common + ['100-share buy lots; configured proportional fees/slippage; price limits enforced only when provider supplies limits.']


@pytest.mark.parametrize('asset_class', ['STOCK', 'ETF', 'FUND'])
def test_research_contract_preserves_existing_execution_semantics(asset_class):
    payload = request(asset_class)
    before = deepcopy(payload)
    validation = engine.validate_config(payload)
    result = engine.execute(payload)['result']
    assert validation['assumptions'] == result['assumptions'] == expected_assumptions(asset_class)
    assert validation['benchmarkContract'] == EXPECTED_BENCHMARK
    assert {key: result['benchmark'][key] for key in EXPECTED_BENCHMARK} == EXPECTED_BENCHMARK
    assert set(result['benchmark']) == set(EXPECTED_BENCHMARK) | {'metrics', 'equityCurve', 'excessReturn'}
    assert result['benchmark']['metrics']
    assert result['benchmark']['equityCurve']
    assert result['benchmark']['excessReturn'] == pytest.approx(
        result['metrics']['netReturn'] - result['benchmark']['metrics']['netReturn'])
    assert payload == before


def test_validation_and_execution_really_share_contract_helpers(monkeypatch):
    assumptions_calls = []
    benchmark_calls = []
    def assumptions(config):
        assumptions_calls.append(deepcopy(config))
        return ['shared assumption sentinel']
    def benchmark():
        benchmark_calls.append(True)
        return {'status': 'SENTINEL', 'type': 'SHARED', 'name': 'shared benchmark sentinel'}
    monkeypatch.setattr(engine, 'research_assumptions', assumptions)
    monkeypatch.setattr(engine, 'backtest_benchmark_contract', benchmark)
    payload = request()
    validation = engine.validate_config(payload)
    result = engine.execute(payload)['result']
    assert validation['assumptions'] == result['assumptions'] == ['shared assumption sentinel']
    assert validation['benchmarkContract'] == {key: result['benchmark'][key] for key in ('status', 'type', 'name')}
    assert len(assumptions_calls) == len(benchmark_calls) == 2
    assert assumptions_calls[0] == assumptions_calls[1] == engine.prepare(payload)[0]


def test_contract_helpers_return_fresh_values_and_preserve_config():
    config, _ = engine.prepare(request('FUND'))
    before = deepcopy(config)
    engine.research_assumptions(config).clear()
    engine.backtest_benchmark_contract().clear()
    assert engine.research_assumptions(config) == expected_assumptions('FUND')
    assert engine.backtest_benchmark_contract() == EXPECTED_BENCHMARK
    assert config == before
