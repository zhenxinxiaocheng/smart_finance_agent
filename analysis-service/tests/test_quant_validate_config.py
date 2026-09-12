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
    assert response.json() == {'compatible': True, 'runtime': engine.runtime_info(), 'effectiveConfig': payload['config']}
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
