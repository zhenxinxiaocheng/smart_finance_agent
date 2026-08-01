# Independent Technical Outlook Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an always-available, deterministic technical outlook that explains trend direction from price, volume, momentum, volatility, and price structure without depending on a validated quant model.

**Architecture:** Keep the existing data-quality and snapshot pipeline, but add a pure Python outlook evaluator that consumes already-calculated indicator observations. Spring passes optional market snapshot fields and removes technical-score-based quantity generation. The asset-detail page makes technical outlook the primary card while leaving validated quant strategy output in a separate secondary section.

**Tech Stack:** Python 3.11, FastAPI/Pydantic, Spring Boot 3.2/Java 17, Vue 3/Vite, unittest/pytest, JUnit/Mockito, Node test runner.

## Global Constraints

- Technical outlook must work independently of quant training, model status, prediction, and deployment state.
- Horizon codes and day ranges come from the resolved user profile; do not branch on `SHORT`, `MEDIUM`, `LONG`, or fixed day counts.
- Indicator periods, score weights, thresholds, and data sufficiency rules live in the versioned strategy JSON, not source-code constants.
- Technical output may provide `WATCH`, `WAIT`, `AVOID`, or `REDUCE_WATCH`; it must not generate amounts, share quantities, or orders.
- AI may explain deterministic results but cannot create indicators, prices, probabilities, or action conclusions.
- Missing turnover rate or volume ratio must be represented as unavailable, never inferred from volume.
- Existing `score`, `verdict`, and `signals` fields remain for one compatibility cycle and are derived from `outlook`.
- Preserve all unrelated uncommitted workspace changes and stage only files named by each task.

---

### Task 1: Versioned outlook rules and pure evaluator

**Files:**
- Create: `analysis-service/config/technical-strategy-v3.json`
- Delete: `analysis-service/config/technical-strategy-v2.json`
- Create: `analysis-service/app/technical_outlook.py`
- Modify: `analysis-service/app/strategy_config.py`
- Modify: `analysis-service/tests/test_strategy_config.py`
- Create: `analysis-service/tests/test_technical_outlook.py`

**Interfaces:**
- Consumes: `StrategyConfig` and one `TechnicalObservation` containing values available at a single as-of date.
- Produces: `evaluate_outlook(observation: TechnicalObservation, levels: Mapping[str, Any], config: StrategyConfig) -> dict[str, Any]`.

- [ ] **Step 1: Write failing configuration and evaluator tests**

```python
def test_strategy_v3_defines_all_outlook_dimensions_and_normalized_weights():
    strategy = load_strategy_config()
    assert strategy.version == "technical-strategy-v3"
    weights = strategy.value("technical.outlook.weights")
    assert set(weights) == {"trend", "momentum", "volumePrice", "volatility", "structure"}
    assert sum(float(value) for value in weights.values()) == pytest.approx(1.0)


def test_bullish_volume_confirmed_observation_has_explainable_outlook():
    result = evaluate_outlook(bullish_observation(), price_levels(), load_strategy_config())
    assert result["direction"] in {"BULLISH", "LEAN_BULLISH"}
    assert 0 <= result["strength"] <= 100
    assert result["action"] == "WATCH"
    assert len(result["reasons"]) <= 3
    assert set(result["components"]) == {
        "trend", "momentum", "volumePrice", "volatility", "structure"
    }
    assert result["invalidation"]["price"] == price_levels()["support"]["low"]


def test_missing_turnover_and_volume_ratio_are_not_fabricated():
    result = evaluate_outlook(
        bullish_observation(turnover_rate=None, volume_ratio=None),
        price_levels(),
        load_strategy_config(),
    )
    assert "TURNOVER_RATE_UNAVAILABLE" in result["missingInputs"]
    assert "VOLUME_RATIO_UNAVAILABLE" in result["missingInputs"]
    assert all("换手率" not in text for text in result["reasons"])
```

- [ ] **Step 2: Run the tests and verify RED**

Run: `cd analysis-service; .venv\Scripts\python.exe -m pytest tests/test_strategy_config.py tests/test_technical_outlook.py -q`

Expected: FAIL because strategy v3 and `technical_outlook` do not exist.

- [ ] **Step 3: Create the versioned configuration**

Rename the v2 JSON to v3, preserve provider/fund/fundamental settings, and add this validated structure under `technical.outlook`:

```json
{
  "minimum_core_history_days": 20,
  "weights": {
    "trend": 0.30,
    "momentum": 0.20,
    "volumePrice": 0.20,
    "volatility": 0.15,
    "structure": 0.15
  },
  "direction_thresholds": {
    "bullish": 35,
    "lean_bullish": 12,
    "lean_bearish": -12,
    "bearish": -35
  },
  "confidence_thresholds": {
    "high_agreement": 0.75,
    "medium_agreement": 0.50,
    "minimum_full_availability": 0.80
  },
  "component_clip": 100,
  "trend": {
    "return_clip_percent": 20,
    "return_weight": 2.0,
    "price_average_adjustment": 20,
    "average_alignment_adjustment": 25,
    "slope_adjustment": 15
  },
  "momentum": {
    "macd_adjustment": 35,
    "macd_turn_adjustment": 15,
    "rsi_center": 50,
    "rsi_weight": 1.2,
    "overbought_penalty": 15,
    "oversold_rebound_bonus": 15,
    "kdj_cross_adjustment": 15
  },
  "volume_price": {
    "expansion_ratio": 1.20,
    "contraction_ratio": 0.80,
    "confirmation_adjustment": 35,
    "divergence_adjustment": 30,
    "snapshot_confirmation_adjustment": 10
  },
  "volatility": {
    "atr_close_warning_ratio": 0.04,
    "bollinger_edge_adjustment": 20,
    "high_volatility_penalty": 30
  },
  "structure": {
    "near_level_atr_multiplier": 1.0,
    "breakout_adjustment": 40,
    "level_proximity_adjustment": 20
  }
}
```

Validate that weights total 1, direction thresholds are strictly ordered, and confidence values lie in `[0, 1]` when loading the configuration.

- [ ] **Step 4: Implement the pure evaluator**

```python
@dataclass(frozen=True)
class TechnicalObservation:
    close: float
    previous_close: float | None
    horizon_return_percent: float | None
    fast_average: float | None
    slow_average: float | None
    previous_fast_average: float | None
    previous_slow_average: float | None
    macd_histogram: float | None
    previous_macd_histogram: float | None
    rsi: float | None
    kdj_k: float | None
    kdj_d: float | None
    bollinger_upper: float | None
    bollinger_lower: float | None
    atr: float | None
    volume: float | None
    volume_average: float | None
    turnover_rate: float | None = None
    volume_ratio: float | None = None
    amplitude: float | None = None


def evaluate_outlook(
    observation: TechnicalObservation,
    levels: Mapping[str, Any],
    config: StrategyConfig,
) -> dict[str, Any]:
    components = {
        "trend": _trend_component(observation, config),
        "momentum": _momentum_component(observation, config),
        "volumePrice": _volume_price_component(observation, config),
        "volatility": _volatility_component(observation, config),
        "structure": _structure_component(observation, levels, config),
    }
    signed_score = _weighted_available_score(components, config)
    return _outlook_result(signed_score, components, levels, observation, config)
```

Each component returns a signed score in `[-100, 100]`, availability, evidence codes, and risk codes. Select reasons and risks by absolute weighted contribution, cap each list at three, derive confidence from component availability plus directional agreement, and never label confidence as probability.

- [ ] **Step 5: Run the focused tests and verify GREEN**

Run: `cd analysis-service; .venv\Scripts\python.exe -m pytest tests/test_strategy_config.py tests/test_technical_outlook.py -q`

Expected: all focused tests PASS.

- [ ] **Step 6: Commit Task 1**

```powershell
git add -- analysis-service/config/technical-strategy-v3.json analysis-service/config/technical-strategy-v2.json analysis-service/app/technical_outlook.py analysis-service/app/strategy_config.py analysis-service/tests/test_strategy_config.py analysis-service/tests/test_technical_outlook.py
git commit -m "feat: add deterministic technical outlook evaluator"
```

---

### Task 2: Integrate arbitrary horizons and graceful degradation

**Files:**
- Modify: `analysis-service/app/analysis.py`
- Modify: `analysis-service/app/main.py`
- Modify: `analysis-service/tests/test_analysis_engine.py`
- Modify: `analysis-service/tests/test_analysis_endpoints.py`

**Interfaces:**
- Consumes: `evaluate_outlook(...)` from Task 1 and optional `market_snapshot` from the HTTP request.
- Produces: `analyze_technical(records, horizons, primary_horizon, market_snapshot=None) -> dict[str, Any]`, with an `outlook` at the root and inside every usable horizon.

- [ ] **Step 1: Write failing arbitrary-horizon and degradation tests**

```python
def test_arbitrary_horizon_returns_outlook_without_quant_model():
    result = analyze_technical(
        price_records(320),
        {"WAVE": [7, 45], "POSITION": [80, 260]},
        "WAVE",
        {"turnoverRate": 3.2, "volumeRatio": 1.4, "amplitude": 4.1},
    )
    assert result["status"] == "READY"
    assert result["outlook"] == result["horizons"]["WAVE"]["outlook"]
    assert result["outlook"]["direction"] in {
        "BULLISH", "LEAN_BULLISH", "SIDEWAYS", "LEAN_BEARISH", "BEARISH"
    }
    assert "quant" not in result["outlook"]


def test_long_horizon_with_core_history_returns_limited_not_empty_score():
    result = analyze_technical(
        price_records(200),
        {"PERSONAL_LONG": [300, 900]},
        "PERSONAL_LONG",
    )
    assert result["status"] == "LIMITED"
    assert result["outlook"]["confidence"] == "LOW"
    assert "HORIZON_HISTORY_INCOMPLETE" in result["outlook"]["missingInputs"]
    assert result["horizons"]["PERSONAL_LONG"]["effectiveLookbackDays"] == 199
```

Add an endpoint test proving `marketSnapshot` accepts nullable numeric fields and rejects unknown fields at the request root.

- [ ] **Step 2: Run the focused tests and verify RED**

Run: `cd analysis-service; .venv\Scripts\python.exe -m pytest tests/test_analysis_engine.py tests/test_analysis_endpoints.py -q`

Expected: FAIL because the API does not accept `marketSnapshot` and no `outlook` exists.

- [ ] **Step 3: Build one observation per configured horizon**

After indicators are calculated once, construct `TechnicalObservation` from the selected index. Use `effectiveLookbackDays = min(configuredMaximumDays, availableHistoryDays - 1)` for an incomplete long horizon and mark it `LIMITED`. Only return `INSUFFICIENT` when valid OHLC history is below `technical.outlook.minimum_core_history_days` or the data-quality layer blocks analysis.

Derive compatibility fields from outlook:

```python
legacy_score = round(50.0 + signed_score / 2.0, 1)
legacy_verdict = (
    "FAVORABLE" if direction in {"BULLISH", "LEAN_BULLISH"}
    else "WEAK" if direction in {"BEARISH", "LEAN_BEARISH"}
    else "WAIT"
)
```

Do not use the compatibility score to create the outlook.

- [ ] **Step 4: Extend the FastAPI request without adding financial context**

```python
class MarketSnapshot(BaseModel):
    model_config = ConfigDict(extra="forbid")
    turnover_rate: float | None = Field(
        default=None,
        validation_alias=AliasChoices("turnover_rate", "turnoverRate"),
        serialization_alias="turnoverRate",
    )
    volume_ratio: float | None = Field(
        default=None,
        validation_alias=AliasChoices("volume_ratio", "volumeRatio"),
        serialization_alias="volumeRatio",
    )
    amplitude: float | None = None


class TechnicalAnalysisRequest(AnalysisRequest):
    records: list[dict[str, Any]]
    horizons: dict[str, list[int]]
    primary_horizon: str = Field(...)
    market_snapshot: MarketSnapshot | None = Field(
        default=None,
        validation_alias=AliasChoices("market_snapshot", "marketSnapshot"),
        serialization_alias="marketSnapshot",
    )
```

Pass only `model_dump(by_alias=True)` values into `analyze_technical`; do not add account balance, holdings, financial profile, or quant output.

- [ ] **Step 5: Run the focused tests and verify GREEN**

Run: `cd analysis-service; .venv\Scripts\python.exe -m pytest tests/test_analysis_engine.py tests/test_analysis_endpoints.py -q`

Expected: all focused tests PASS.

- [ ] **Step 6: Commit Task 2**

```powershell
git add -- analysis-service/app/analysis.py analysis-service/app/main.py analysis-service/tests/test_analysis_engine.py analysis-service/tests/test_analysis_endpoints.py
git commit -m "feat: expose multi-horizon technical outlook"
```

---

### Task 3: Replay the exact outlook rules without future leakage

**Files:**
- Modify: `analysis-service/app/analysis.py`
- Modify: `analysis-service/tests/test_analysis_engine.py`

**Interfaces:**
- Consumes: the same indicator arrays and `evaluate_outlook(...)` used by the live technical result.
- Produces: `historical_outlook_at(records, as_of_index, configured_bounds, market_snapshot=None) -> dict[str, Any]` plus horizon backtest entries with `matchedDirection`, `occurrences`, `positiveRate`, `negativeRate`, `medianForwardReturn`, `maximumAdverseExcursion`, and `invalidationRate`; keeps `winRate` as a compatibility alias of `positiveRate`.

- [ ] **Step 1: Write failing exact-rule and leakage tests**

```python
def test_backtest_replays_the_same_direction_rule_as_live_outlook():
    records = price_records(620)
    technical = analyze_technical(records, {"WAVE": [7, 45]}, "WAVE")
    replay = backtest_horizons(records, {"WAVE": [7, 45]})["horizons"]["WAVE"]
    assert replay["matchedDirection"] == technical["outlook"]["direction"]
    assert replay["positiveRate"] == replay["winRate"]
    assert replay["occurrences"] > 0
    assert replay["maximumAdverseExcursion"] <= 0


def test_historical_signal_does_not_change_when_later_prices_change():
    original = historical_outlook_at(price_records(320), 220, [7, 45])
    changed = price_records(320)
    for row in changed[221:]:
        row["close"] = "9999"
        row["high"] = "9999"
        row["low"] = "9999"
    assert historical_outlook_at(changed, 220, [7, 45]) == original
```

- [ ] **Step 2: Run the focused tests and verify RED**

Run: `cd analysis-service; .venv\Scripts\python.exe -m pytest tests/test_analysis_engine.py -q`

Expected: FAIL because replay still uses only a fixed moving-average condition.

- [ ] **Step 3: Replace the fixed bullish signal replay**

Calculate all indicator arrays once. For each historical as-of index, build an observation using only indices `<= as_of_index`, calculate support/resistance from the prefix, and call the same evaluator. Select historical observations whose direction matches the current direction, enforcing spacing from configuration rather than a hard-coded interval.

For every selected observation:

```python
forward = closes[as_of_index + 1:as_of_index + evaluation_days + 1]
forward_return = (forward[-1] / closes[as_of_index] - 1) * 100
adverse = min(price / closes[as_of_index] - 1 for price in forward) * 100
invalidated = any(price < invalidation_price for price in forward) if bullish else any(
    price > invalidation_price for price in forward
)
```

For `SIDEWAYS`, report both support and resistance breaches and count either breach as invalidation.

- [ ] **Step 4: Run the focused tests and verify GREEN**

Run: `cd analysis-service; .venv\Scripts\python.exe -m pytest tests/test_analysis_engine.py -q`

Expected: all analysis-engine tests PASS.

- [ ] **Step 5: Commit Task 3**

```powershell
git add -- analysis-service/app/analysis.py analysis-service/tests/test_analysis_engine.py
git commit -m "fix: replay exact technical outlook rules"
```

---

### Task 4: Spring integration and removal of technical quantity generation

**Files:**
- Modify: `backend/src/main/java/com/smartfinance/agent/investment/service/AnalysisServiceClient.java`
- Modify: `backend/src/main/java/com/smartfinance/agent/investment/service/InvestmentAnalysisServiceImpl.java`
- Modify: `backend/src/main/java/com/smartfinance/agent/investment/dto/InvestmentAssetDetailResponse.java`
- Modify: `backend/src/main/java/com/smartfinance/agent/investment/config/InvestmentRuntimeProperties.java`
- Modify: `backend/src/main/resources/investment-runtime.properties`
- Delete: `backend/src/main/java/com/smartfinance/agent/investment/service/PersonalizedActionCalculator.java`
- Delete: `backend/src/test/java/com/smartfinance/agent/investment/service/PersonalizedActionCalculatorTest.java`
- Modify: `backend/src/test/java/com/smartfinance/agent/investment/service/AnalysisServiceClientTest.java`
- Modify: `backend/src/test/java/com/smartfinance/agent/investment/service/InvestmentAnalysisSignalTest.java`
- Modify: `backend/src/test/java/com/smartfinance/agent/investment/service/InvestmentDataJobWorkerTest.java`
- Modify: `backend/src/test/java/com/smartfinance/agent/investment/config/InvestmentRuntimePropertiesTest.java`

**Interfaces:**
- Consumes: FastAPI `marketSnapshot` and `outlook` contract from Task 2.
- Produces: asset detail with `technicalAnalysis.outlook`; no `personalizedAction` generated from technical score. Quant action-plan endpoints remain unchanged and continue enforcing validated model gates.

- [ ] **Step 1: Write failing client and service boundary tests**

```java
@Test
void technicalAnalysisSendsOnlyMarketSnapshotAndConfiguredHorizons() {
    client.technicalAnalysis(records, horizons, "WAVE", Map.of(
            "turnoverRate", new BigDecimal("3.2"),
            "volumeRatio", new BigDecimal("1.4"),
            "amplitude", new BigDecimal("4.1")
    ));
    assertThat(capturedBody.get("marketSnapshot")).isEqualTo(expectedSnapshot);
    assertThat(capturedBody).doesNotContainKeys("wealth", "holding", "quantModel");
}


@Test
void technicalOutlookDoesNotCreateAmountOrQuantityAdvice() {
    InvestmentAssetDetailResponse response = service.detail(USER_ID, ASSET_ID);
    assertThat(response.getTechnicalAnalysis()).containsKey("outlook");
    assertThat(response.getPersonalizedAction()).isEmpty();
}
```

Keep the DTO getter returning an empty map for one response-compatibility cycle, but remove every producer of budget, batch, sell quantity, and technical confidence.

- [ ] **Step 2: Run the focused backend tests and verify RED**

Run: `cd backend; mvn "-Dtest=AnalysisServiceClientTest,InvestmentAnalysisSignalTest,InvestmentDataJobWorkerTest,InvestmentRuntimePropertiesTest" test -q`

Expected: FAIL because the client has no market snapshot argument and technical score still creates quantities.

- [ ] **Step 3: Pass the optional market snapshot**

```java
private static Map<String, Object> marketSnapshot(InvestmentAssetView asset) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    putIfNotNull(snapshot, "turnoverRate", asset.getTurnoverRate());
    putIfNotNull(snapshot, "volumeRatio", asset.getVolumeRatio());
    putIfNotNull(snapshot, "amplitude", asset.getAmplitude());
    return snapshot;
}
```

Extend `AnalysisServiceClient.technicalAnalysis(...)` with this map and send it under `marketSnapshot`. Empty maps are valid and must not create zero values.

- [ ] **Step 4: Remove the technical-score quantity path**

Remove `PersonalizedActionCalculator` from `InvestmentAnalysisServiceImpl`, its constructor, tests, action configuration, and properties. Set the compatibility `personalizedAction` response to `Map.of()` and remove it from the AI prompt input. Do not alter `QuantInferenceOrchestrator`, `QuantPredictionQueryService`, `PaperTradingService`, or the validated quant action-plan endpoint.

Update the configured strategy version:

```properties
investment.runtime.analysis.strategy-version=${INVESTMENT_ANALYSIS_STRATEGY_VERSION:technical-strategy-v3}
```

- [ ] **Step 5: Run the focused backend tests and verify GREEN**

Run: `cd backend; mvn "-Dtest=AnalysisServiceClientTest,InvestmentAnalysisSignalTest,InvestmentDataJobWorkerTest,InvestmentRuntimePropertiesTest" test -q`

Expected: all focused tests PASS.

- [ ] **Step 6: Commit Task 4**

```powershell
git add -- backend/src/main/java/com/smartfinance/agent/investment/service/AnalysisServiceClient.java backend/src/main/java/com/smartfinance/agent/investment/service/InvestmentAnalysisServiceImpl.java backend/src/main/java/com/smartfinance/agent/investment/dto/InvestmentAssetDetailResponse.java backend/src/main/java/com/smartfinance/agent/investment/config/InvestmentRuntimeProperties.java backend/src/main/resources/investment-runtime.properties backend/src/main/java/com/smartfinance/agent/investment/service/PersonalizedActionCalculator.java backend/src/test/java/com/smartfinance/agent/investment/service/PersonalizedActionCalculatorTest.java backend/src/test/java/com/smartfinance/agent/investment/service/AnalysisServiceClientTest.java backend/src/test/java/com/smartfinance/agent/investment/service/InvestmentAnalysisSignalTest.java backend/src/test/java/com/smartfinance/agent/investment/service/InvestmentDataJobWorkerTest.java backend/src/test/java/com/smartfinance/agent/investment/config/InvestmentRuntimePropertiesTest.java
git commit -m "refactor: separate technical outlook from position sizing"
```

---

### Task 5: Make technical outlook the primary asset-detail experience

**Files:**
- Create: `frontend/src/lib/technicalOutlook.js`
- Create: `frontend/src/lib/technicalOutlook.test.mjs`
- Modify: `frontend/src/views/InvestmentAssetDetail.vue`
- Modify: `frontend/src/lib/investmentHelpText.js`
- Delete: `frontend/src/lib/investmentActionState.js`
- Delete: `frontend/src/lib/investmentActionState.test.mjs`
- Modify: `frontend/test/investmentDetailPage.test.mjs`

**Interfaces:**
- Consumes: `technicalAnalysis.outlook`, per-horizon `outlook`, legacy `score/verdict`, and the existing quant analysis/action-plan responses.
- Produces: `normalizeTechnicalOutlook(technical, activeHorizon)`, direction/action labels, tones, and a primary outlook card that does not depend on quant state.

- [ ] **Step 1: Write failing helper and page contract tests**

```javascript
test('技术走势在没有量化模型时仍完整显示', () => {
  const result = normalizeTechnicalOutlook({
    outlook: {
      direction: 'LEAN_BULLISH', strength: 68, confidence: 'MEDIUM', action: 'WATCH',
      reasons: ['价格保持在主要均线上方'], risks: ['接近压力区'],
      invalidation: { price: 10.25 }, components: {},
    },
  }, null)
  assert.equal(result.directionLabel, '震荡偏多')
  assert.equal(result.actionLabel, '关注')
  assert.equal(result.reasons.length, 1)
})

test('详情页以走势研判为主且技术分析不显示数量建议', () => {
  assert.match(pageSource, /走势研判/)
  assert.match(pageSource, /outlook\.direction/)
  assert.match(pageSource, /主要依据/)
  assert.match(pageSource, /判断失效/)
  assert.doesNotMatch(pageSource, /数量参考|suggestedBudget|sellQuantity|personalized\.batches/)
  assert.match(pageSource, /量化策略/)
})
```

Also replace the old assertion “量化模型结论替代技术评分成为主要交易判断” with an assertion that a missing quant model does not hide the technical outlook.

- [ ] **Step 2: Run the frontend tests and verify RED**

Run: `cd frontend; node src/lib/technicalOutlook.test.mjs; node test/investmentDetailPage.test.mjs`

Expected: FAIL because the helper and primary outlook card do not exist.

- [ ] **Step 3: Implement normalization and legacy fallback**

```javascript
export function normalizeTechnicalOutlook(technical = {}, horizon = null) {
  const source = horizon?.outlook || technical.outlook
  if (source) return normalizeStructured(source)
  const legacyScore = Number(horizon?.score ?? technical.score ?? 50)
  return {
    status: 'LIMITED',
    direction: legacyDirection(horizon?.verdict || technical.verdict),
    strength: Math.min(100, Math.abs(legacyScore - 50) * 2),
    confidence: 'LOW',
    reasons: ['当前展示的是历史兼容评分，等待新规则重新计算'],
    risks: [],
    invalidation: null,
    components: {},
  }
}
```

The fallback is visibly marked `LIMITED`; it must not synthesize indicator evidence or prices.

- [ ] **Step 4: Rebuild the page hierarchy**

Use this order:

1. asset header and data-quality status;
2. primary metrics and K-line;
3. K-line right-side “走势研判” card with direction, strength, confidence, at most three reasons, at most three risks, support/resistance, and invalidation;
4. arbitrary-horizon comparison cards;
5. fundamental/fund analysis;
6. collapsed historical technical replay and AI explanation;
7. independent “量化策略” card showing validated model output or a short research-status message.

Remove the entire technical quantity-reference section and its helper. Retain quant amount/quantity only inside the quant card and only when `hasUsableQuantModel` is true.

- [ ] **Step 5: Update help text for novice-readable semantics**

Explain that strength is direction intensity, confidence is indicator agreement/data completeness, and historical positive rate is not a future probability. Remove text describing technical score as a sizing input.

- [ ] **Step 6: Run the frontend tests and verify GREEN**

Run: `cd frontend; node src/lib/technicalOutlook.test.mjs; node test/investmentDetailPage.test.mjs; node src/lib/quantModelManagement.test.mjs`

Expected: all focused tests PASS.

- [ ] **Step 7: Commit Task 5**

```powershell
git add -- frontend/src/lib/technicalOutlook.js frontend/src/lib/technicalOutlook.test.mjs frontend/src/views/InvestmentAssetDetail.vue frontend/src/lib/investmentHelpText.js frontend/src/lib/investmentActionState.js frontend/src/lib/investmentActionState.test.mjs frontend/test/investmentDetailPage.test.mjs
git commit -m "feat: prioritize independent technical outlook"
```

---

### Task 6: Full verification and boundary audit

**Files:**
- Modify only files already named above if verification reveals a defect caused by this plan.

**Interfaces:**
- Consumes: completed Tasks 1–5.
- Produces: verified three-service implementation with no technical-to-quant or technical-to-sizing dependency.

- [ ] **Step 1: Run the complete Python suite**

Run: `cd analysis-service; .venv\Scripts\python.exe -m pytest tests -q`

Expected: all tests PASS, with only previously known third-party warnings.

- [ ] **Step 2: Run the complete Spring suite**

Run: `cd backend; mvn -q test`

Expected: exit code 0 and no test failures or errors.

- [ ] **Step 3: Run frontend tests and production build**

Run the Node tests sequentially on Windows:

```powershell
cd frontend
Get-ChildItem -LiteralPath src/lib -Filter *.test.mjs | ForEach-Object { node $_.FullName; if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE } }
Get-ChildItem -LiteralPath test -Filter *.test.mjs | ForEach-Object { node $_.FullName; if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE } }
npm run build
```

Expected: every test exits 0 and Vite build succeeds; existing dependency/chunk warnings are acceptable.

- [ ] **Step 4: Audit forbidden coupling and hard-coded horizons**

Run:

```powershell
rg -n "PersonalizedActionCalculator|suggestedBudget|technicalConfidence|SHORT|MEDIUM|LONG|5日|20日|60日" analysis-service/app/technical_outlook.py analysis-service/app/analysis.py backend/src/main/java/com/smartfinance/agent/investment/service/InvestmentAnalysisServiceImpl.java frontend/src/views/InvestmentAssetDetail.vue frontend/src/lib/technicalOutlook.js
git diff --check
```

Expected: no technical sizing references; no branching or labels tied to fixed horizon codes/days; `git diff --check` exits 0. A confidence label such as `MEDIUM` is allowed only when it denotes confidence, not a horizon.

- [ ] **Step 5: Review the working tree and commit only plan files**

Run `git status --short`, compare it to the file lists in Tasks 1–5, and leave pre-existing Agent schedule, launcher, unrelated mapper test, product-site, and unrelated plan changes unstaged.

```powershell
git add -- analysis-service/app/analysis.py analysis-service/app/main.py analysis-service/app/strategy_config.py analysis-service/app/technical_outlook.py analysis-service/config/technical-strategy-v2.json analysis-service/config/technical-strategy-v3.json analysis-service/tests/test_analysis_engine.py analysis-service/tests/test_analysis_endpoints.py analysis-service/tests/test_strategy_config.py analysis-service/tests/test_technical_outlook.py backend/src/main/java/com/smartfinance/agent/investment/config/InvestmentRuntimeProperties.java backend/src/main/java/com/smartfinance/agent/investment/dto/InvestmentAssetDetailResponse.java backend/src/main/java/com/smartfinance/agent/investment/service/AnalysisServiceClient.java backend/src/main/java/com/smartfinance/agent/investment/service/InvestmentAnalysisServiceImpl.java backend/src/main/java/com/smartfinance/agent/investment/service/PersonalizedActionCalculator.java backend/src/main/resources/investment-runtime.properties backend/src/test/java/com/smartfinance/agent/investment/config/InvestmentRuntimePropertiesTest.java backend/src/test/java/com/smartfinance/agent/investment/service/AnalysisServiceClientTest.java backend/src/test/java/com/smartfinance/agent/investment/service/InvestmentAnalysisSignalTest.java backend/src/test/java/com/smartfinance/agent/investment/service/InvestmentDataJobWorkerTest.java backend/src/test/java/com/smartfinance/agent/investment/service/PersonalizedActionCalculatorTest.java frontend/src/lib/investmentActionState.js frontend/src/lib/investmentActionState.test.mjs frontend/src/lib/investmentHelpText.js frontend/src/lib/technicalOutlook.js frontend/src/lib/technicalOutlook.test.mjs frontend/src/views/InvestmentAssetDetail.vue frontend/test/investmentDetailPage.test.mjs
git diff --cached --check
git commit -m "feat: deliver independent technical outlook"
```

If Tasks 1–5 were committed separately and there is no remaining implementation diff, do not create an empty final commit.
