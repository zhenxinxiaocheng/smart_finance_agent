from __future__ import annotations

import copy
import hashlib
import json
import math
import os
import re
import uuid
from datetime import date, timedelta
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path

import numpy as np
from .parameters import CATALOG_VERSION, CANDIDATE_RULE_VERSION, PARAMETERS


VERSION = "quant-v2.1"
FACTOR_KEYS = ("momentum", "trend", "volatility", "drawdown", "reversal", "volume", "liquidity")


class EngineError(ValueError):
    def __init__(self, code, message):
        self.code = code
        super().__init__(message)


def fail(code, message):
    raise EngineError(code, message)


def number(value, name, low=0, high=1e15):
    result = float(value)
    if not math.isfinite(result) or not low <= result <= high:
        fail("INVALID_CONFIG", f"{name} must be finite and between {low} and {high}")
    return result


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False, allow_nan=False).encode()).hexdigest()


# Freeze sources when loaded: on-disk edits are not executing code.
_LOADED_CODE_HASH = hashlib.sha256(b"".join(
    Path(__file__).with_name(name).read_bytes()
    for name in ("engine.py", "parameters.py", "sensitivity.py")
)).hexdigest()


def runtime_info():
    return {
        "engineVersion": VERSION,
        "codeHash": _LOADED_CODE_HASH,
        "parameterCatalogVersion": CATALOG_VERSION,
        "candidateRuleVersion": CANDIDATE_RULE_VERSION,
    }


def require_runtime(expected):
    if expected is None:
        return
    current = runtime_info()
    if not isinstance(expected, dict) or any(expected.get(key) != value for key, value in current.items()):
        fail("EXPERIMENT_ENVIRONMENT_CHANGED", "The frozen experiment runtime is not active")


def money(value):
    return float(Decimal(str(value)).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP))


def prepare(payload):
    c = copy.deepcopy(payload.get("config") or {})
    for key, default, low, high, kind, *_ in PARAMETERS:
        value = number(c.get(key, default), key, low, high)
        if kind == "integer" and value != int(value):
            fail("INVALID_CONFIG", f"{key} must be an integer")
        c[key] = int(value) if kind == "integer" else value
    c.setdefault("strategyType", "TREND")
    if c["strategyType"] not in ("TREND", "MULTI_FACTOR", "ML_ELASTIC_NET", "ML_XGBOOST"):
        fail("INVALID_STRATEGY", "Unsupported strategyType")
    factors = c.get("factors") or [{"key": "momentum", "weight": 1}, {"key": "volatility", "weight": 1}]
    for f in factors:
        if f["key"] not in FACTOR_KEYS:
            fail("UNKNOWN_FACTOR", f"Unsupported factor: {f['key']}")
        f["weight"] = number(f.get("weight", 1), "factor weight", -100, 100)
    if not any(f["weight"] for f in factors):
        fail("INVALID_CONFIG", "At least one nonzero factor weight is required")
    c["factors"] = factors
    assets = {}
    end = str(payload.get("endDate") or date.today().isoformat())
    date.fromisoformat(end)
    for original in payload.get("assets", []):
        a = copy.deepcopy(original)
        aid = str(a["id"])
        if aid in assets:
            fail("DUPLICATE_ASSET", aid)
        cls = a.get("assetClass", c.get("assetClass", "STOCK"))
        cls = "FUND" if cls == "MUTUAL_FUND" else cls
        if cls not in ("STOCK", "ETF", "FUND"):
            fail("INVALID_ASSET_CLASS", cls)
        a["assetClass"] = cls
        bars = sorted(a.get("bars", []), key=lambda b: b["date"])
        seen = set()
        checked = []
        for b in bars:
            day = str(b["date"])
            date.fromisoformat(day)
            if day in seen:
                fail("DUPLICATE_DATE", f"{aid}: {day}")
            seen.add(day)
            if day > end:
                continue
            b["date"] = day
            b["close"] = number(b["close"], "close", 1e-8)
            b["researchClose"] = number(b.get("researchClose") or b["close"], "researchClose", 1e-8)
            research_only = payload.get("kind") in ("TRAINING", "FACTOR_RESEARCH")
            if cls != "FUND":
                if not research_only and str(b.get("adjustType", "")).upper() not in ("RAW", "NONE", "UNADJUSTED", "NOT_ADJUSTED"):
                    fail("RAW_PRICE_REQUIRED", f"{aid}: explicit raw execution prices required")
                b["open"] = number((b.get("open") or b["close"]) if research_only else b.get("open"), "open", 1e-8)
            else:
                b["open"] = b["close"]
            b["volume"] = number(b.get("volume") or 0, "volume")
            if checked:
                raw_ratio = b["close"] / checked[-1]["close"]
                research_ratio = b["researchClose"] / checked[-1]["researchClose"]
                if abs(raw_ratio / research_ratio - 1) > .01 or raw_ratio < .65 or raw_ratio > 1.5:
                    a.setdefault("corporateActionWarnings", []).append(day)
            checked.append(b)
        if not checked:
            fail("INSUFFICIENT_DATA", f"{aid}: no observations")
        a["bars"] = checked
        a["byDate"] = {b["date"]: b for b in checked}
        assets[aid] = a
    if not assets:
        fail("EMPTY_UNIVERSE", "At least one asset is required")
    classes = {a["assetClass"] for a in assets.values()}
    if len(classes) != 1:
        fail("MIXED_ASSET_CLASSES", "Create separate stock, ETF and fund universes")
    c["assetClass"] = next(iter(classes))
    if c["assetClass"] == "FUND" and any(f["key"] in ("volume", "liquidity") for f in factors):
        fail("FACTOR_NOT_APPLICABLE", "Funds do not provide exchange volume or liquidity")
    if any(f["key"] in ("volume", "liquidity") for f in factors) and any(not any(b["volume"] > 0 for b in a["bars"]) for a in assets.values()):
        fail("FACTOR_DATA_UNAVAILABLE", "Selected factor requires observed volume for every asset")
    return c, assets


def features(bars, c):
    if len(bars) < max(c["slowWindow"], c["lookback"] + 1):
        return None
    p = np.array([b["researchClose"] for b in bars], dtype=float)
    returns = np.diff(p[-c["lookback"] - 1:]) / p[-c["lookback"] - 1:-1]
    v = np.array([b["volume"] for b in bars[-c["lookback"]:]])
    return {
        "momentum": float(p[-1] / p[-c["lookback"] - 1] - 1),
        "trend": float(p[-1] / np.mean(p[-c["slowWindow"]:]) - 1),
        "volatility": -float(np.std(returns, ddof=1) * np.sqrt(252)),
        "drawdown": float(p[-1] / np.max(p[-c["lookback"]:]) - 1),
        "reversal": float(1 - p[-1] / p[-min(6, len(p))]),
        "volume": float(v[-1] / np.mean(v) - 1) if np.mean(v) > 0 else 0.,
        "liquidity": float(np.log1p(np.mean(v * p[-len(v):]))),
    }


def rows_for_research(assets, c):
    rows = []
    h = c["predictionHorizon"]
    for aid, a in assets.items():
        bars = a["bars"]
        for i in range(max(c["slowWindow"], c["lookback"] + 1) - 1, len(bars)):
            # Labels begin at next executable session, after publication latency.
            decision_day = published_on(bars[i], a, c)
            entry = next((j for j in range(i + 1, len(bars)) if bars[j]["date"] > decision_day), len(bars))
            finish = entry + h
            if finish >= len(bars):
                break
            f = features(bars[:i + 1], c)
            entry_price = bars[entry]["open"] * bars[entry]["researchClose"] / bars[entry]["close"]
            rows.append({"date": decision_day, "labelEnd": published_on(bars[finish], a, c), "id": aid,
                         "x": [f[k] for k in feature_keys(c)], "y": bars[finish]["researchClose"] / entry_price - 1})
    return sorted(rows, key=lambda r: (r["date"], r["id"]))


def correlation(x, y):
    x = np.asarray(x, dtype=float)
    y = np.asarray(y, dtype=float)
    if (len(x) < 3 or not np.isfinite(x).all() or not np.isfinite(y).all()
            or np.ptp(x) < 1e-12 or np.ptp(y) < 1e-12):
        return None
    value = float(np.corrcoef(x, y)[0, 1])
    return value if math.isfinite(value) else None


def model_root():
    return Path(os.environ.get("QUANT_V2_MODEL_DIR", str(Path(__file__).resolve().parents[2] / ".data" / "quant-v2-models")))


def load_model(ref, c):
    if not isinstance(ref, str) or not re.fullmatch(r"[0-9a-f]{32}", ref):
        fail("INVALID_MODEL_REF", "Expected server model identifier")
    folder = model_root() / ref
    try:
        artifact = json.loads((folder / "model.json").read_text(encoding="utf-8"))
    except FileNotFoundError:
        fail("MODEL_NOT_FOUND", ref)
    if artifact.get("engineVersion") != VERSION or artifact["strategyType"] != c["strategyType"] or artifact["featureConfig"] != feature_config(c):
        fail("MODEL_CONFIG_MISMATCH", "Model feature configuration differs from request")
    if artifact["strategyType"] == "ML_XGBOOST":
        from xgboost import XGBRegressor
        estimator = XGBRegressor()
        estimator.load_model(folder / "booster.ubj")
        artifact["estimator"] = estimator
    return artifact


def feature_config(c):
    result = {k: c[k] for k in ("lookback", "slowWindow", "predictionHorizon", "publicationLagDays", "assetClass", "factors")}
    result["factorSetVersion"] = c.get("factorSetVersion")
    return result


def feature_keys(c):
    return [f["key"] for f in c["factors"]]


def predict(model, vectors):
    x = np.asarray(vectors, dtype=float)
    if model["strategyType"] == "ML_XGBOOST":
        return model["estimator"].predict(x)
    return ((x - np.asarray(model["mean"])) / np.asarray(model["scale"])) @ np.asarray(model["coef"]) + model["intercept"]


def training(rows, c, source_hash):
    if not c["strategyType"].startswith("ML_"):
        fail("INVALID_TRAINING_STRATEGY", "Training requires ML_ELASTIC_NET or ML_XGBOOST")
    dates = sorted({r["date"] for r in rows})
    if len(dates) < 100:
        fail("INSUFFICIENT_DATA", "Training requires at least 100 labeled dates after feature warmup")
    cut1, cut2 = dates[int(len(dates) * .6)], dates[int(len(dates) * .8)]
    train = [r for r in rows if r["labelEnd"] < cut1]
    valid = [r for r in rows if cut1 <= r["date"] < cut2 and r["labelEnd"] < cut2]
    hold = [r for r in rows if r["date"] >= cut2]
    if min(map(len, (train, valid, hold))) < 20:
        fail("INSUFFICIENT_DATA", "Purged chronological partitions each require 20 samples")
    x, y = np.array([r["x"] for r in train]), np.array([r["y"] for r in train])
    vx, vy = np.array([r["x"] for r in valid]), np.array([r["y"] for r in valid])
    from sklearn.linear_model import ElasticNet
    from sklearn.preprocessing import StandardScaler
    best, best_loss = None, float("inf")
    scaler = StandardScaler().fit(x)
    for parameter in (.0001, .001, .01) if c["strategyType"] == "ML_ELASTIC_NET" else (2, 3, 4):
        if c["strategyType"] == "ML_ELASTIC_NET":
            est = ElasticNet(alpha=parameter, l1_ratio=.5, random_state=c["seed"], max_iter=10000)
            est.fit(scaler.transform(x), y)
            prediction = est.predict(scaler.transform(vx))
        else:
            from xgboost import XGBRegressor
            est = XGBRegressor(n_estimators=120, max_depth=parameter, learning_rate=.04,
                               objective="reg:squarederror", random_state=c["seed"], n_jobs=1)
            est.fit(x, y)
            prediction = est.predict(vx)
        loss = float(np.mean((prediction - vy) ** 2))
        if loss < best_loss:
            best, best_loss = est, loss
    artifact = {"strategyType": c["strategyType"], "featureConfig": feature_config(c),
                "features": feature_keys(c), "trainedThrough": max(r["labelEnd"] for r in train),
                "holdoutStart": cut2, "evaluatedThrough": max(r["labelEnd"] for r in hold),
                "sourceHash": source_hash, "engineVersion": VERSION}
    if c["strategyType"] == "ML_ELASTIC_NET":
        artifact.update(mean=scaler.mean_.tolist(), scale=scaler.scale_.tolist(), coef=best.coef_.tolist(), intercept=float(best.intercept_))
    else:
        artifact["estimator"] = best
    hy = np.array([r["y"] for r in hold])
    hp = predict(artifact, [r["x"] for r in hold])
    mse = float(np.mean((hp - hy) ** 2))
    baseline = float(np.mean((hy - np.mean(y)) ** 2))
    ic = correlation(hp, hy)
    reasons = []
    if ic is None or ic <= 0:
        reasons.append("FINAL_HOLDOUT_IC_NOT_POSITIVE")
    if mse >= baseline:
        reasons.append("FINAL_HOLDOUT_NOT_BETTER_THAN_TRAIN_MEAN")
    qualification = {"status": "QUALIFIED" if not reasons else "UNQUALIFIED", "reasons": reasons}
    artifact["qualification"] = qualification
    ref = uuid.uuid4().hex
    folder = model_root() / ref
    folder.mkdir(parents=True, exist_ok=False)
    saved = {k: v for k, v in artifact.items() if k != "estimator"}
    if "estimator" in artifact:
        best.save_model(folder / "booster.ubj")
    (folder / "model.json").write_text(json.dumps(saved, allow_nan=False), encoding="utf-8")
    return ref, qualification, {"validationMse": best_loss, "holdoutMse": mse, "baselineMse": baseline,
                               "holdoutIC": ic, "trainSamples": len(train), "validationSamples": len(valid),
                               "holdoutSamples": len(hold), "trainEnd": artifact["trainedThrough"],
                               "validationStart": cut1, "holdoutStart": cut2, "holdoutEnd": artifact["evaluatedThrough"],
                               "evaluatedThrough": artifact["evaluatedThrough"], "purged": True}


def factor_research(rows, c):
    if len(rows) < 30:
        fail("INSUFFICIENT_DATA", "Factor research requires 30 labeled samples")
    from scipy.stats import rankdata
    output = []
    for f in c["factors"]:
        k = feature_keys(c).index(f["key"])
        grouped = {}
        for r in rows:
            grouped.setdefault(r["date"], []).append(r)
        daily = [correlation(rankdata([r["x"][k] for r in group]), rankdata([r["y"] for r in group])) for group in grouped.values()]
        daily = [v for v in daily if v is not None]
        pooled = correlation(rankdata([r["x"][k] for r in rows]), rankdata([r["y"] for r in rows]))
        ic = float(np.mean(daily)) if daily else pooled
        groups = [[] for _ in range(5)]
        for day_rows in grouped.values():
            if len(day_rows) < 5:
                continue
            ordered = sorted(day_rows, key=lambda r: (r["x"][k], r["id"]))
            for index, part in enumerate(np.array_split(np.arange(len(ordered)), 5)):
                groups[index].append(float(np.mean([ordered[int(i)]["y"] for i in part])))
        output.append({"key": f["key"], "sampleCount": len(rows), "rankIC": ic,
                       "groupReturns": [{"group": i + 1, "meanForwardReturn": float(np.mean(g)) if g else None, "dates": len(g)} for i, g in enumerate(groups)],
                       "icIR": float(np.mean(daily) / np.std(daily, ddof=1)) if len(daily) > 2 and np.std(daily) > 0 else None,
                       "method": "CROSS_SECTIONAL" if daily else "POOLED_TIME_SERIES",
                       "qualification": "QUALIFIED" if daily and len(daily) >= 20 and ic * f["weight"] > 0 else "UNQUALIFIED"})
    return output


def published_on(bar, asset, c):
    explicit = bar.get("publishedDate")
    if explicit:
        return date.fromisoformat(str(explicit)).isoformat()
    lag = c["publicationLagDays"] if asset["assetClass"] == "FUND" else 0
    return (date.fromisoformat(bar["date"]) + timedelta(days=lag)).isoformat()


def available_bars(asset, day, c):
    return [b for b in asset["bars"] if b["date"] <= day and published_on(b, asset, c) <= day]


def targets(assets, day, c, model=None):
    observed = {}
    for aid, asset in assets.items():
        bars = available_bars(asset, day, c)
        if not bars or (date.fromisoformat(day) - date.fromisoformat(bars[-1]["date"])).days > 7:
            continue
        f = features(bars, c)
        if f:
            observed[aid] = f
    if not observed:
        return {}, []
    keys = sorted(observed)
    scores = {}
    if model:
        values = predict(model, [[observed[k][f] for f in feature_keys(c)] for k in keys])
        scores = dict(zip(keys, map(float, values)))
    elif c["strategyType"] == "TREND":
        scores = {k: observed[k]["trend"] for k in keys}
    else:
        scores = dict.fromkeys(keys, 0.)
        for f in c["factors"]:
            values = np.asarray([observed[k][f["key"]] for k in keys])
            std = float(np.std(values))
            normalized = (values - np.mean(values)) / std if std > 1e-12 else np.zeros(len(keys))
            for k, score in zip(keys, normalized):
                scores[k] += float(score * f["weight"])
    selected = sorted(keys, key=lambda k: (-scores[k], k))[:c["topN"]]
    if c["strategyType"] == "TREND":
        selected = [k for k in selected if scores[k] > 0 and observed[k]["momentum"] > 0]
    weights = {}
    for k in selected:
        weight = min(c["maxWeight"], 1 / len(selected))
        if c["strategyType"] == "TREND":
            weight *= min(1., c["targetVol"] / max(-observed[k]["volatility"], 1e-6))
        weights[k] = weight
    return weights, [{"date": day, "assetId": k, "score": scores[k], "targetWeight": weights.get(k, 0.)} for k in keys]


def price_for(asset, day, c):
    bars = available_bars(asset, day, c)
    return bars[-1]["close"] if bars else None


def portfolio_equity(state, assets, day, c):
    value = float(state["cash"]) + sum(float(r["amount"]) for r in state["receivables"])
    for aid, p in state["positions"].items():
        price = price_for(assets[aid], day, c) or p.get("lastPrice", 0.)
        p["lastPrice"] = price
        value += p["quantity"] * price
    return value


def performance(curve, initial, fills):
    values = np.asarray([initial] + [r["equity"] for r in curve], dtype=float)
    returns = np.diff(values) / values[:-1] if len(values) > 1 else np.array([])
    drawdown = 1 - values / np.maximum.accumulate(values)
    days = max(1, (date.fromisoformat(curve[-1]["date"]) - date.fromisoformat(curve[0]["date"])).days) if curve else 1
    total = float(values[-1] / initial - 1)
    annual = float((1 + total) ** (365.25 / days) - 1) if days >= 30 and total > -1 else None
    return {"netReturn": total, "annualizedReturn": annual,
            "maxDrawdown": float(np.max(drawdown)),
            "volatility": float(np.std(returns, ddof=1) * math.sqrt(252)) if len(returns) > 1 else None,
            "turnover": sum(f["notional"] for f in fills) / max(float(np.mean(values)), 1),
            "fees": sum(f["fee"] for f in fills), "tradeCount": len(fills), "observations": len(curve)}


def simulation_context(payload, assets, model=None):
    paper = payload.get("kind") == "PAPER"
    new_paper = paper and not ((payload.get("state") or {}).get("deploymentStart") or (payload.get("state") or {}).get("lastDate"))
    action = payload.get("action", "RUN")
    if action not in ("RUN", "PAUSE", "LIQUIDATE"):
        fail("INVALID_ACTION", "Paper action must be RUN, PAUSE or LIQUIDATE")
    start = str(payload.get("startDate") or min(b["date"] for a in assets.values() for b in a["bars"]))
    end = str(payload.get("endDate") or max(b["date"] for a in assets.values() for b in a["bars"]))
    date.fromisoformat(start)
    date.fromisoformat(end)
    if start > end:
        fail("INVALID_DATE_RANGE", "startDate is after endDate")
    if new_paper:
        start = end
    if model and not paper and start <= model["evaluatedThrough"]:
        fail("MODEL_LOOKAHEAD", f"Backtest must begin after final model evaluation date {model['evaluatedThrough']}")
    return paper, new_paper, action, start, end


def validate_config(payload):
    c, assets = prepare(payload)
    if payload.get("kind", "BACKTEST") != "BACKTEST":
        fail("INVALID_TASK_KIND", "Config compatibility requires a backtest context")
    model = load_model(payload.get("modelRef"), c) if c["strategyType"].startswith("ML_") else None
    simulation_context(payload, assets, model)
    return {"compatible": True, "runtime": runtime_info(), "effectiveConfig": c}


def simulate(payload, c, assets, model=None, benchmark_targets=None):
    paper, new_paper, action, start, end = simulation_context(payload, assets, model)
    state = copy.deepcopy(payload.get("state")) if paper and payload.get("state") else {
        "cash": c["initialCash"], "positions": {}, "pendingOrders": [], "receivables": [],
        "lastDate": "", "sessionCount": 0, "equityCurve": [], "peakEquity": c["initialCash"],
        "totalFees": 0., "totalNotional": 0., "tradeCount": 0,
    }
    for key, default in (("peakEquity", c["initialCash"]), ("totalFees", 0.), ("totalNotional", 0.), ("tradeCount", 0), ("riskStopped", False)):
        state.setdefault(key, default)
    if paper:
        state.setdefault("deploymentStart", state.get("lastDate") or end)
        start = max(start, state["deploymentStart"])
    state.setdefault("unsettledBuys", [])
    if any(aid not in assets for aid in state["positions"]):
        fail("STATE_UNIVERSE_MISMATCH", "Paper holdings do not belong to this universe")
    binding = digest({"config": c, "assets": sorted(assets), "modelRef": payload.get("modelRef")})
    if state.get("binding") and state["binding"] != binding:
        fail("STATE_CONFIG_MISMATCH", "Running paper portfolio configuration is immutable")
    state["binding"] = binding
    signal_start = str(payload.get("signalStartDate") or state.get("signalStartDate") or start)
    date.fromisoformat(signal_start)
    state["signalStartDate"] = signal_start
    events, fills, ledger, signals, snapshots, target_history = [], [], [], [], [], []
    if action == "PAUSE" or (action == "LIQUIDATE" and not state.get("liquidating")):
        for order in state["pendingOrders"]:
            events.append({**order, "status": "CANCELLED", "reason": action})
        state["pendingOrders"] = []
    days = {b["date"] for a in assets.values() for b in a["bars"]}
    for a in assets.values():
        if a["assetClass"] == "FUND":
            days.update(published_on(b, a, c) for b in a["bars"])
    watermarks = {}
    for aid, asset in assets.items():
        available = available_bars(asset, end, c)
        watermarks[aid] = max((published_on(b, asset, c) for b in available), default="")
    complete_through = min(watermarks.values())
    if new_paper:
        # Establish a launch boundary without replaying an old portfolio. Only
        # observed, complete data can advance the shared portfolio watermark.
        previous_day = (date.fromisoformat(start) - timedelta(days=1)).isoformat()
        state["lastDate"] = min(complete_through, previous_day)
    upper = min(end, complete_through) if paper else end
    days = sorted(d for d in days if start <= d <= upper and d > state["lastDate"])

    def add_orders(day, weights, equity):
        existing = {o["assetId"] for o in state["pendingOrders"]}
        for aid in sorted(assets):
            if aid in existing:
                continue
            price = price_for(assets[aid], day, c)
            if not price:
                continue
            held = state["positions"].get(aid, {}).get("quantity", 0.)
            lot = 100 if assets[aid]["assetClass"] != "FUND" else 10 ** -c["fundShareDecimals"]
            desired = math.floor(equity * weights.get(aid, 0.) / price / lot) * lot
            delta = desired - held
            if abs(delta) < lot / 2:
                continue
            order = {"id": digest({"binding": binding, "date": day, "assetId": aid, "side": "BUY" if delta > 0 else "SELL"})[:24],
                     "assetId": aid, "signalDate": day, "side": "BUY" if delta > 0 else "SELL",
                     "quantity": abs(delta), "status": "PENDING"}
            state["pendingOrders"].append(order)
            events.append(dict(order))

    # Stopping with liquidation creates orders even without a new observation. They
    # remain pending until a subsequent executable session, never filled at stale NAV.
    if action == "LIQUIDATE" and not state.get("liquidating"):
        state["liquidating"] = True
        add_orders(max(state["lastDate"], end), {}, portfolio_equity(state, assets, end, c))
    for day in days:
        if action == "RUN" and day < signal_start:
            events.extend({**o, "status": "CANCELLED", "reason": "BEFORE_SIGNAL_START", "date": day} for o in state["pendingOrders"])
            state["pendingOrders"] = []
        state["unsettledBuys"] = [r for r in state["unsettledBuys"] if r["availableDate"] > day]
        remaining = []
        for receivable in state["receivables"]:
            if receivable["dueDate"] <= day:
                state["cash"] = money(state["cash"] + receivable["amount"])
                ledger.append({"date": day, "type": "SETTLEMENT", "amount": receivable["amount"], "orderId": receivable["orderId"], "balance": state["cash"]})
            else:
                remaining.append(receivable)
        state["receivables"] = remaining
        pending = []
        for order in sorted(state["pendingOrders"], key=lambda o: (o["side"] != "SELL", o["id"])):
            asset = assets[order["assetId"]]
            candidates = [b for b in asset["bars"] if order["signalDate"] < b["date"] <= day]
            if asset["assetClass"] == "FUND":
                candidates = candidates[:1]
                bar = candidates[0] if candidates and published_on(candidates[0], asset, c) <= day else None
            else:
                bar = asset["byDate"].get(day) if day > order["signalDate"] else None
            if not bar:
                pending.append(order)
                continue
            if asset["assetClass"] != "FUND" and (bar["volume"] <= 0 or bar.get("suspended") or (order["side"] == "BUY" and bar.get("limitUp") and bar["open"] >= float(bar["limitUp"])) or (order["side"] == "SELL" and bar.get("limitDown") and bar["open"] <= float(bar["limitDown"]))):
                events.append({**order, "status": "REJECTED", "reason": "SUSPENDED_OR_PRICE_LIMIT", "date": day})
                continue
            fund = asset["assetClass"] == "FUND"
            buy = order["side"] == "BUY"
            slip = 0 if fund else c["slippageBps"] / 10000
            price = bar["open"] * (1 + slip if buy else 1 - slip)
            fee_rate = c["feeRate"] if buy else c["sellFeeRate"]
            position = state["positions"].setdefault(order["assetId"], {"quantity": 0., "cost": 0., "lastPrice": price})
            lot = 10 ** -c["fundShareDecimals"] if fund else 100
            quantity = order["quantity"]
            if buy:
                quantity = min(quantity, math.floor(max(state["cash"], 0.) / (price * (1 + fee_rate)) / lot) * lot)
            else:
                locked = sum(r["quantity"] for r in state["unsettledBuys"] if r["assetId"] == order["assetId"])
                quantity = min(quantity, max(0., position["quantity"] - locked))
            if quantity < lot / 2:
                events.append({**order, "status": "REJECTED", "reason": "INSUFFICIENT_CASH_OR_POSITION", "date": day})
                continue
            quantity = round(quantity, c["fundShareDecimals"] if fund else 0)
            notional = money(quantity * price)
            fee = money(notional * fee_rate)
            if buy and money(notional + fee) > state["cash"]:
                quantity = round(math.floor(max(0., state["cash"] - .02) / (price * (1 + fee_rate)) / lot) * lot, c["fundShareDecimals"] if fund else 0)
                notional = money(quantity * price)
                fee = money(notional * fee_rate)
            if quantity <= 0:
                events.append({**order, "status": "REJECTED", "reason": "INSUFFICIENT_CASH_AFTER_ROUNDING", "date": day})
                continue
            if buy:
                state["cash"] = money(state["cash"] - notional - fee)
                position["cost"] += notional + fee
                position["quantity"] += quantity
                if fund and c["settlementDays"]:
                    state["unsettledBuys"].append({"assetId": order["assetId"], "quantity": quantity,
                                                   "availableDate": (date.fromisoformat(day) + timedelta(days=c["settlementDays"])).isoformat()})
                amount = -money(notional + fee)
            else:
                position["cost"] *= max(0., 1 - quantity / position["quantity"])
                position["quantity"] = max(0., position["quantity"] - quantity)
                amount = money(notional - fee)
                if fund and c["settlementDays"]:
                    state["receivables"].append({"amount": amount, "dueDate": (date.fromisoformat(day) + timedelta(days=c["settlementDays"])).isoformat(), "orderId": order["id"]})
                else:
                    state["cash"] = money(state["cash"] + amount)
            status = "FILLED" if abs(quantity - order["quantity"]) < 1e-8 else "PARTIALLY_FILLED_CANCELLED"
            fill = {"id": order["id"], "orderId": order["id"], "assetId": order["assetId"], "date": bar["date"], "confirmedDate": day, "side": order["side"], "quantity": quantity, "price": price, "notional": notional, "fee": fee}
            fills.append(fill)
            events.append({**order, "status": status, "filledQuantity": quantity, "date": day})
            ledger.append({"date": day, "type": "RECEIVABLE" if not buy and fund and c["settlementDays"] else order["side"], "amount": amount, "orderId": order["id"], "balance": state["cash"]})
            state["totalFees"] = money(state["totalFees"] + fee)
            state["totalNotional"] = money(state["totalNotional"] + notional)
            state["tradeCount"] += 1
        state["pendingOrders"] = pending
        equity = portfolio_equity(state, assets, day, c)
        state["peakEquity"] = max(state["peakEquity"], equity)
        drawdown = 1 - equity / state["peakEquity"]
        if drawdown >= c["maxDrawdown"]:
            state["riskStopped"] = True
            for order in state["pendingOrders"]:
                if order["side"] == "BUY":
                    events.append({**order, "status": "CANCELLED", "reason": "DRAWDOWN_LIMIT", "date": day})
            state["pendingOrders"] = [o for o in state["pendingOrders"] if o["side"] != "BUY"]
        point = {"date": day, "equity": equity, "cash": state["cash"], "receivables": sum(r["amount"] for r in state["receivables"]), "drawdown": drawdown}
        state["equityCurve"].append(point)
        snapshots.append({"date": day, "positions": copy.deepcopy(state["positions"])})
        if benchmark_targets is not None:
            if day in benchmark_targets:
                add_orders(day, benchmark_targets[day], equity)
        elif action == "RUN" and day >= signal_start and not state.get("liquidating"):
            if state["riskStopped"]:
                add_orders(day, {}, equity)
                target_history.append({"date": day, "weights": {}})
            elif state["sessionCount"] % c["rebalanceDays"] == 0:
                weights, today_signals = targets(assets, day, c, model)
                signals.extend(today_signals)
                add_orders(day, weights, equity)
                target_history.append({"date": day, "weights": weights})
        elif action == "LIQUIDATE" and state.get("liquidating"):
            add_orders(day, {}, equity)
        state["sessionCount"] += 1
        state["lastDate"] = day
    metrics = performance(state["equityCurve"], c["initialCash"], fills)
    metrics.update(fees=state["totalFees"], tradeCount=state["tradeCount"], turnover=state["totalNotional"] / max(c["initialCash"], 1))
    state["liquidated"] = bool(state.get("liquidating") and not any(p["quantity"] > 1e-8 for p in state["positions"].values()) and not state["pendingOrders"] and not state["receivables"])
    result = {"metrics": metrics, "equityCurve": state["equityCurve"], "orders": events, "fills": fills,
              "positions": [{"assetId": aid, **p, "marketValue": p["quantity"] * p["lastPrice"]} for aid, p in state["positions"].items() if p["quantity"] > 1e-8],
              "positionHistory": snapshots, "cashLedger": ledger, "signals": signals, "targetHistory": target_history,
              "cash": state["cash"], "receivables": state["receivables"], "liquidated": state["liquidated"]}
    if paper:
        latest = max(watermarks.values())
        valuation_state = copy.deepcopy(state)
        result["valuation"] = {"asOfDate": end, "equity": portfolio_equity(valuation_state, assets, end, c),
                               "completeThrough": complete_through, "assetWatermarks": watermarks,
                               "dataState": "INCOMPLETE" if complete_through < latest else "READY",
                               "awaitingAssets": [aid for aid, watermark in watermarks.items() if watermark < latest]}
    return result, state


def execute(payload):
    require_runtime(payload.get("expectedRuntime"))
    c, assets = prepare(payload)
    kind = payload.get("kind", "BACKTEST")
    if kind not in ("BACKTEST", "TRAINING", "FACTOR_RESEARCH", "PAPER"):
        fail("INVALID_TASK_KIND", str(kind))
    snapshot = [{k: v for k, v in a.items() if k != "byDate"} for a in assets.values()]
    runtime = runtime_info()
    provenance = {"dataHash": digest(snapshot), "configHash": digest(c), "config": c,
                  "engineVersion": runtime["engineVersion"], "codeHash": runtime["codeHash"],
                  "seed": c["seed"], "startDate": payload.get("startDate"), "endDate": payload.get("endDate"),
                  "universeId": payload.get("universeId"), "universeVersion": payload.get("universeVersionId", payload.get("universeVersion")),
                  "strategyVersionId": payload.get("strategyVersionId"), "factorSetVersionId": payload.get("factorSetVersionId", payload.get("factorVersionId"))}
    provenance.update(parameterCatalogVersion=runtime["parameterCatalogVersion"], modelRef=payload.get("modelRef"))
    assumptions = ["Fixed snapshot universe; historical constituent membership and survivorship bias are not certified.",
                   "Signal at observed close, execution on a later session; no intraday execution.",
                   f"Trade consideration and fees rounded to CNY cents using HALF_UP; fund share precision {c['fundShareDecimals']} decimal places."]
    if c["assetClass"] == "FUND":
        assumptions.extend([f"Fund fees are configurable assumptions: subscription {c['feeRate']}, redemption {c['sellFeeRate']}.",
                            f"NAV publication delay {c['publicationLagDays']} calendar days; subscription share availability and redemption cash settlement {c['settlementDays']} calendar days after confirmation.",
                            "Fund dividends/reinvestment are unsupported; discontinuities block certification."])
    else:
        assumptions.append("100-share buy lots; configured proportional fees/slippage; price limits enforced only when provider supplies limits.")
    response = {"status": "SUCCEEDED", "stage": "COMPLETED", "progress": 100}
    if kind in ("TRAINING", "FACTOR_RESEARCH"):
        rows = rows_for_research(assets, c)
        rows = [r for r in rows if not payload.get("startDate") or r["date"] >= payload["startDate"]]
        if kind == "TRAINING":
            ref, qualification, metrics = training(rows, c, provenance["dataHash"])
            response.update(modelRef=ref, qualification=qualification, result={"metrics": metrics, "modelRef": ref})
        else:
            research = factor_research(rows, c)
            keys = feature_keys(c)
            matrix = [[correlation([r["x"][i] for r in rows], [r["x"][j] for r in rows]) for j in range(len(keys))] for i in range(len(keys))]
            counts = {aid: sum(r["id"] == aid for r in rows) for aid in assets}
            coverage = [{"assetId": aid, "observations": len(a["bars"]), "usableSamples": counts[aid], "coverage": counts[aid] / len(a["bars"])} for aid, a in assets.items()]
            response.update(qualification={"status": "UNQUALIFIED", "reasons": ["FACTOR_RESEARCH_IS_NOT_A_DEPLOYABLE_STRATEGY"]}, result={"factors": research, "correlation": {"keys": keys, "matrix": matrix}, "coverage": coverage})
    else:
        model = load_model(payload.get("modelRef"), c) if c["strategyType"].startswith("ML_") else None
        if model and kind == "PAPER" and str((payload.get("state") or {}).get("deploymentStart") or payload.get("endDate") or date.today().isoformat()) <= model["evaluatedThrough"]:
            fail("MODEL_LOOKAHEAD", "Paper deployment must start after final model evaluation")
        result, state = simulate(payload, c, assets, model)
        reasons = []
        if kind == "BACKTEST" and len(result["equityCurve"]) < 60:
            reasons.append("INSUFFICIENT_EVALUATION_DATES")
        if kind == "BACKTEST" and not result["metrics"]["tradeCount"]:
            reasons.append("NO_EXECUTED_TRADES")
        if result["metrics"]["maxDrawdown"] >= c["maxDrawdown"]:
            reasons.append("DRAWDOWN_LIMIT_EXCEEDED")
        if c["assetClass"] != "FUND" and not all(a.get("corporateActionsVerified") is True for a in assets.values()):
            reasons.append("CORPORATE_ACTIONS_NOT_VERIFIED")
        warning_start = state.get("deploymentStart") if kind == "PAPER" else payload.get("startDate")
        affected = {aid: [d for d in a.get("corporateActionWarnings", []) if str(warning_start or "") <= d <= str(payload.get("endDate") or date.today().isoformat())] for aid, a in assets.items()}
        affected = {aid: days for aid, days in affected.items() if days}
        if affected:
            reasons.append("CORPORATE_ACTION_UNSUPPORTED")
            result["corporateActionWarnings"] = affected
        if model and model["qualification"]["status"] != "QUALIFIED":
            reasons.append("MODEL_FINAL_HOLDOUT_UNQUALIFIED")
        if kind == "BACKTEST":
            reference = {r["date"]: {aid: sum(r["weights"].values()) / len(assets) for aid in assets} for r in result["targetHistory"]}
            baseline, _ = simulate(payload, c, assets, model, reference)
            result["benchmark"] = {"status": "READY", "type": "UNIVERSE_EQUAL_WEIGHT_MATCHED_EXPOSURE",
                                   "name": "资产池等权基准（匹配策略目标仓位）", "metrics": baseline["metrics"],
                                   "equityCurve": baseline["equityCurve"],
                                   "excessReturn": result["metrics"]["netReturn"] - baseline["metrics"]["netReturn"]}
        else:
            result["benchmark"] = {"status": "NOT_APPLICABLE", "reason": "Compare the immutable deployment source backtest for matched-exposure benchmark"}
        response.update(qualification={"status": "QUALIFIED" if not reasons else "UNQUALIFIED", "reasons": reasons, "scope": "PAPER_ELIGIBILITY_ONLY_NOT_PROFITABILITY_CERTIFICATION"}, result=result)
        if kind == "PAPER":
            response.pop("qualification", None)
            result["runtime"] = {
                "status": "ATTENTION_REQUIRED" if reasons else "WAITING_EXECUTION" if state.get("pendingOrders") else "MONITORING" if state.get("tradeCount") else "WAITING_SIGNAL",
                "reasons": reasons,
                "corporateActionWarnings": affected,
            }
            response["state"] = state
    response["result"].update(provenance=provenance, assumptions=assumptions)
    json.dumps(response, allow_nan=False)
    return response
