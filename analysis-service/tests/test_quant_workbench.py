"""User-run regressions for deterministic research and the paper ledger."""
from copy import deepcopy
from datetime import date, timedelta
from decimal import Decimal, ROUND_HALF_UP
import json

import pytest

from app.quant_workbench.engine import EngineError, execute
from app.quant_workbench.engine import prepare
from app.quant_workbench.parameters import parameter_catalog


def test_editor_defaults_match_execution_and_preserve_explicit_values():
    payload = request()
    payload["config"] = {"strategyType": "TREND"}
    catalog = parameter_catalog()
    config, _ = prepare(payload)
    for parameter in catalog["parameters"]:
        assert config[parameter["key"]] == parameter["default"]
        assert parameter["basis"]
    payload["config"].update(lookback=37, targetVol=.08, feeRate=0)
    overridden, _ = prepare(payload)
    assert overridden["lookback"] == 37
    assert overridden["targetVol"] == .08
    assert overridden["feeRate"] == 0
    catalog["parameters"][0]["default"] = -1
    assert parameter_catalog()["parameters"][0]["default"] != -1


def request(asset_class="STOCK"):
    days = []
    day = date(2023, 1, 2)
    while len(days) < 180:
        if day.weekday() < 5:
            days.append(day.isoformat())
        day += timedelta(days=1)
    bars = [{"date": day, "open": 10 + i * .025, "close": 10.01 + i * .025,
             "researchClose": 10.01 + i * .025,
             "volume": 1000000, "adjustType": "NONE"} for i, day in enumerate(days)]
    return {"kind": "BACKTEST", "startDate": days[65], "endDate": days[-1],
            "config": {"strategyType": "TREND", "assetClass": asset_class,
                       "lookback": 10, "slowWindow": 30, "rebalanceDays": 5,
                       "initialCash": 100000, "maxWeight": .5, "targetVol": .15,
                       "publicationLagDays": 1, "settlementDays": 2,
                       "feeRate": .001, "sellFeeRate": .002, "seed": 42},
            "assets": [{"id": "asset-1", "code": "fixture", "name": "Synthetic regression fixture",
                        "assetClass": asset_class, "bars": bars}]}


def test_backtest_repeats_exactly():
    payload = request()
    assert execute(payload) == execute(deepcopy(payload))


def test_future_prices_cannot_change_previous_signals_or_fills():
    payload = request()
    cutoff = payload["assets"][0]["bars"][125]["date"]
    changed = deepcopy(payload)
    for bar in changed["assets"][0]["bars"]:
        if bar["date"] > cutoff:
            bar["open"] *= 1.03
            bar["close"] *= 1.03
    before, after = execute(payload)["result"], execute(changed)["result"]
    for key in ("signals", "fills", "equityCurve"):
        assert [r for r in before[key] if r["date"] <= cutoff] == [
            r for r in after[key] if r["date"] <= cutoff]


@pytest.mark.parametrize("asset_class", ["STOCK", "ETF", "FUND"])
def test_fills_use_later_prices_and_reconcile_cash(asset_class):
    payload = request(asset_class)
    result = execute(payload)["result"]
    orders = {o["id"]: o for o in result["orders"]}
    assert result["fills"], "Fixture must exercise actual fills"
    cash = payload["config"]["initialCash"]
    for entry in result["cashLedger"]:
        if entry["type"] != "RECEIVABLE":
            cash += entry["amount"]
        assert cash == pytest.approx(entry["balance"], abs=1e-7)
        assert cash >= -1e-7
    for fill in result["fills"]:
        assert fill["date"] > orders[fill["orderId"]]["signalDate"]
        rate = payload["config"]["feeRate" if fill["side"] == "BUY" else "sellFeeRate"]
        expected_fee = Decimal(str(fill["notional"] * rate)).quantize(Decimal(".01"), rounding=ROUND_HALF_UP)
        assert fill["fee"] == pytest.approx(float(expected_fee))
    final = result["equityCurve"][-1]
    assert final["equity"] == pytest.approx(
        final["cash"] + final["receivables"] + sum(p["marketValue"] for p in result["positions"]))


def test_paper_first_run_does_not_replay_and_repeated_poll_is_idempotent():
    payload = request()
    payload["kind"] = "PAPER"
    first = execute(payload)
    assert first["result"]["fills"] == []
    assert all(o["signalDate"] >= payload["endDate"] for o in first["result"]["orders"])
    payload["state"] = first["state"]
    repeated = execute(payload)
    assert repeated["state"] == first["state"]
    assert repeated["result"]["fills"] == []
    assert repeated["result"]["orders"] == []


def test_pause_cancels_pending_without_generating_replacements():
    payload = request()
    payload["kind"] = "PAPER"
    payload["state"] = execute(payload)["state"]
    payload["action"] = "PAUSE"
    paused = execute(payload)
    assert paused["state"]["pendingOrders"] == []
    assert paused["result"]["fills"] == []
    assert all(o["status"] == "CANCELLED" for o in paused["result"]["orders"])
    payload["state"] = paused["state"]
    assert execute(payload)["state"] == paused["state"]


def test_paused_holdings_are_valued_without_new_orders_or_fills():
    payload = request()
    payload["kind"] = "PAPER"
    bars = payload["assets"][0]["bars"]
    payload["endDate"] = bars[100]["date"]
    first = execute(payload)
    payload["state"] = first["state"]
    payload["endDate"] = bars[101]["date"]
    invested = execute(payload)
    assert invested["result"]["fills"]
    original_state = deepcopy(invested["state"])
    payload["state"] = invested["state"]
    payload["action"] = "PAUSE"
    payload["endDate"] = bars[110]["date"]
    paused = execute(payload)
    assert invested["state"] == original_state, "Caller-owned portfolio state must not be mutated"
    assert paused["result"]["fills"] == []
    assert all(o["status"] == "CANCELLED" for o in paused["result"]["orders"])
    assert paused["state"]["cash"] == original_state["cash"]
    assert paused["state"]["equityCurve"][-1]["date"] == payload["endDate"]
    assert paused["state"]["equityCurve"][-1]["equity"] > original_state["equityCurve"][-1]["equity"]


def test_fund_redemption_remains_receivable_until_settlement():
    payload = request("FUND")
    payload["config"]["maxDrawdown"] = .95
    for i, bar in enumerate(payload["assets"][0]["bars"]):
        if i > 110:
            bar["close"] = bar["open"] = 12.76 - (i - 110) * .025
    result = execute(payload)["result"]
    sales = {f["orderId"]: f for f in result["fills"] if f["side"] == "SELL"}
    assert sales, "Fixture must exercise redemption"
    receivables = [r for r in result["cashLedger"] if r["type"] == "RECEIVABLE"]
    settlements = {r["orderId"]: r for r in result["cashLedger"] if r["type"] == "SETTLEMENT"}
    assert receivables
    for row in receivables:
        settled = settlements[row["orderId"]]
        assert settled["amount"] == row["amount"]
        assert date.fromisoformat(settled["date"]) >= date.fromisoformat(row["date"]) + timedelta(days=2)


def test_resume_does_not_generate_trades_in_unprocessed_paused_dates():
    payload = request()
    payload["kind"] = "PAPER"
    bars = payload["assets"][0]["bars"]
    payload["endDate"] = bars[100]["date"]
    payload["state"] = execute(payload)["state"]
    payload["action"] = "PAUSE"
    paused = execute(payload)
    payload["state"] = paused["state"]
    payload["action"] = "RUN"
    payload["endDate"] = bars[110]["date"]
    payload["signalStartDate"] = bars[110]["date"]
    resumed = execute(payload)
    assert resumed["result"]["fills"] == []
    assert all(s["date"] >= payload["signalStartDate"] for s in resumed["result"]["signals"])
    assert all(o["signalDate"] >= payload["signalStartDate"] for o in resumed["result"]["orders"])


def test_split_arrival_does_not_consume_an_incomplete_portfolio_date():
    payload = request()
    payload["kind"] = "PAPER"
    second_asset = deepcopy(payload["assets"][0])
    second_asset["id"] = "asset-2"
    second_asset["code"] = "fixture-2"
    complete_bars = deepcopy(second_asset["bars"])
    second_asset["bars"] = complete_bars[:100]
    payload["assets"].append(second_asset)
    payload["endDate"] = complete_bars[100]["date"]
    incomplete = execute(payload)
    assert incomplete["state"]["lastDate"] < payload["endDate"]
    assert incomplete["result"]["fills"] == []
    payload["state"] = incomplete["state"]
    payload["assets"][1]["bars"] = complete_bars
    complete = execute(payload)
    assert complete["state"]["lastDate"] == payload["endDate"]
    assert complete["result"]["fills"] == [], "Delayed data must not turn startup into historical trading"
    assert all(o["signalDate"] >= payload["endDate"] for o in complete["result"]["orders"])
    payload["state"] = complete["state"]
    repeated = execute(payload)
    assert repeated["state"] == complete["state"]
    assert repeated["result"]["orders"] == []


def test_stale_data_watermark_does_not_make_valid_ml_deployment_look_ahead(monkeypatch):
    payload = request()
    bars = payload["assets"][0]["bars"]
    payload["kind"] = "PAPER"
    payload["config"]["strategyType"] = "ML_ELASTIC_NET"
    payload["endDate"] = bars[110]["date"]
    model_end = bars[105]["date"]
    payload["assets"][0]["bars"] = bars[:101]
    monkeypatch.setattr("app.quant_workbench.engine.load_model", lambda ref, config: {
        "evaluatedThrough": model_end, "qualification": {"status": "QUALIFIED"}})
    initial = execute(payload)
    assert initial["state"]["lastDate"] < model_end
    assert initial["state"]["deploymentStart"] > model_end
    payload["state"] = initial["state"]
    repeated = execute(payload)
    assert repeated["status"] == "SUCCEEDED"
    assert repeated["result"]["fills"] == []


def test_adjusted_execution_prices_are_rejected():
    payload = request()
    for bar in payload["assets"][0]["bars"]:
        bar["adjustType"] = "QFQ"
    with pytest.raises(EngineError) as error:
        execute(payload)
    assert error.value.code == "RAW_PRICE_REQUIRED"


def test_missing_research_series_is_rejected_without_raw_price_fallback():
    payload = request()
    del payload["assets"][0]["bars"][0]["researchClose"]
    with pytest.raises(EngineError) as error:
        execute(payload)
    assert error.value.code == "RESEARCH_PRICE_REQUIRED"


def test_unverified_corporate_actions_do_not_get_qualified():
    response = execute(request())
    assert response["status"] == "SUCCEEDED"
    assert response["qualification"]["status"] == "UNQUALIFIED"
    assert "CORPORATE_ACTIONS_NOT_VERIFIED" in response["qualification"]["reasons"]


def test_research_report_preserves_effective_inputs_and_eligibility_scope():
    payload = request("FUND")
    payload.update(universeId="pool-1", universeVersionId="pool-version-3",
                   strategyVersionId="strategy-version-2", factorVersionId="factor-version-4")
    payload["config"]["feeRate"] = 0
    response = execute(payload)
    provenance = response["result"]["provenance"]
    assert response["qualification"]["scope"] == "PAPER_ELIGIBILITY_ONLY_NOT_PROFITABILITY_CERTIFICATION"
    assert provenance["universeId"] == "pool-1"
    assert provenance["universeVersion"] == "pool-version-3"
    assert provenance["strategyVersionId"] == "strategy-version-2"
    assert provenance["factorSetVersionId"] == "factor-version-4"
    assert provenance["startDate"] == payload["startDate"]
    assert provenance["endDate"] == payload["endDate"]
    assert provenance["config"]["feeRate"] == 0
    assert provenance["config"]["sellFeeRate"] == .002
    assert provenance["config"]["fundShareDecimals"] == 4
    assert all(provenance[key] for key in ("engineVersion", "codeHash", "dataHash", "configHash"))
    assert response["result"]["assumptions"]
    assert response["result"]["benchmark"]["type"] == "UNIVERSE_EQUAL_WEIGHT_MATCHED_EXPOSURE"
    changed = deepcopy(payload)
    changed["config"]["feeRate"] = .003
    other = execute(changed)["result"]["provenance"]
    assert other["configHash"] != provenance["configHash"]
    assert other["dataHash"] == provenance["dataHash"]


def test_corporate_action_warning_keeps_affected_asset_dates():
    payload = request("FUND")
    bars = payload["assets"][0]["bars"]
    affected_date = bars[100]["date"]
    for bar in bars[100:]:
        bar["close"] *= .5
        bar["open"] *= .5
    response = execute(payload)
    assert response["status"] == "SUCCEEDED"
    assert response["qualification"]["status"] == "UNQUALIFIED"
    assert "CORPORATE_ACTION_UNSUPPORTED" in response["qualification"]["reasons"]
    assert affected_date in response["result"]["corporateActionWarnings"]["asset-1"]


def test_holdout_changes_do_not_refit_elastic_net(monkeypatch, tmp_path):
    monkeypatch.setenv("QUANT_V2_MODEL_DIR", str(tmp_path))
    payload = request()
    payload["kind"] = "TRAINING"
    payload["startDate"] = payload["assets"][0]["bars"][0]["date"]
    payload["config"]["strategyType"] = "ML_ELASTIC_NET"
    original = execute(payload)
    model = json.loads((tmp_path / original["modelRef"] / "model.json").read_text())
    changed = deepcopy(payload)
    for bar in changed["assets"][0]["bars"]:
        if bar["date"] >= model["holdoutStart"]:
            bar["open"] *= 1.02
            bar["close"] *= 1.02
    second = execute(changed)
    other = json.loads((tmp_path / second["modelRef"] / "model.json").read_text())
    for key in ("mean", "scale", "coef", "intercept", "trainedThrough"):
        assert model[key] == other[key]
    assert model["trainedThrough"] < model["holdoutStart"]


@pytest.mark.parametrize("algorithm", ["ML_ELASTIC_NET", "ML_XGBOOST"])
def test_trained_model_cannot_backtest_its_evaluation_period(monkeypatch, tmp_path, algorithm):
    monkeypatch.setenv("QUANT_V2_MODEL_DIR", str(tmp_path))
    payload = request()
    payload["kind"] = "TRAINING"
    payload["startDate"] = payload["assets"][0]["bars"][0]["date"]
    payload["config"]["strategyType"] = algorithm
    payload["modelRef"] = execute(payload)["modelRef"]
    payload["kind"] = "BACKTEST"
    with pytest.raises(EngineError) as error:
        execute(payload)
    assert error.value.code == "MODEL_LOOKAHEAD"
