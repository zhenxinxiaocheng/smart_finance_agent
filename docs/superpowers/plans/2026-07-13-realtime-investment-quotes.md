# 投资行情准实时更新 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A 股资产由后端每 3 秒获取并保存最新报价，前端每 3 秒展示缓存价格和精确更新时间，场外基金维持每日净值。

**Architecture:** Python 提供独立单股实时报价接口，腾讯 HTTP 行情为主源、日线为降级；Java 定时任务在交易时段同步全部股票资产并唯一写库；Vue 只轮询本地资产 API。行情失败保留旧价并标记延迟。

**Tech Stack:** Python 3 / FastAPI / unittest，Java 17 / Spring Boot / MyBatis-Plus / JUnit 5，Vue 3 / Vite / Node test runner。

## Global Constraints

- A 股同步间隔固定为 3000 ms。
- 同步由 Java 后端执行，不依赖浏览器是否前台。
- 场外基金不做实时轮询。
- Java 是唯一数据库写入者。
- 不新增数据库迁移，复用 `product_daily_quote.synced_at` 作为最后成功报价时间。
- 当前工作区包含用户其他未提交修改，不执行 Git 提交、清理或覆盖无关文件。

---

### Task 1: Python 单股实时行情接口

**Files:**
- Modify: `analysis-service/app/providers.py`
- Modify: `analysis-service/app/main.py`
- Modify: `analysis-service/tests/test_provider_normalization.py`
- Modify: `analysis-service/tests/test_product_endpoint.py`

**Interfaces:**
- Produces: `fetch_realtime_stock_quote(code: str, market: str, opener=urlopen) -> dict`
- Produces: `POST /internal/v1/quotes/realtime`，响应包含 `code`、`market`、`latestPrice`、`dataDate`、`fetchedAt`、`provider`、`warnings`

- [ ] **Step 1: 写入失败测试**

```python
def test_fetch_tencent_quote_returns_price_and_exact_time(self):
    payload = 'v_sz002632="51~道明光学~002632~8.63~8.90~8.75~0~0~0~0~0~20260713112330";'
    result = fetch_realtime_stock_quote("002632", "SZSE", opener=fake_opener(payload, "gbk"))
    self.assertEqual("8.63", result["latestPrice"])
    self.assertEqual("2026-07-13T11:23:30+08:00", result["fetchedAt"])
```

- [ ] **Step 2: 运行测试并确认因函数不存在而失败**

Run: `.\.venv\Scripts\python.exe -m unittest tests.test_provider_normalization.ProviderNormalizationTest.test_fetch_tencent_quote_returns_price_and_exact_time -v`

- [ ] **Step 3: 实现腾讯行情解析与内部接口**

```python
def fetch_realtime_stock_quote(code: str, market: str, opener=urlopen) -> dict[str, Any]:
    prefix = {"SSE": "sh", "BSE": "bj"}.get(market.upper(), "sz")
    response = opener(f"http://qt.gtimg.cn/q={prefix}{code}", timeout=5)
    fields = response.read().decode("gbk").split('"')[1].split("~")
    fetched_at = datetime.strptime(fields[30], "%Y%m%d%H%M%S").replace(tzinfo=ZoneInfo("Asia/Shanghai"))
    return {"code": code, "market": market, "latestPrice": str(Decimal(fields[3])),
            "dataDate": fetched_at.date().isoformat(), "fetchedAt": fetched_at.isoformat(),
            "provider": "TENCENT", "warnings": []}
```

- [ ] **Step 4: 增加 endpoint 测试并运行 Python 全量用例**

Run: `.\.venv\Scripts\python.exe -m unittest discover -s tests -v`
Expected: all tests `OK`。

### Task 2: Java 实时报价客户端与资产更新时间

**Files:**
- Modify: `backend/src/main/java/com/smartfinance/agent/investment/service/AnalysisServiceClient.java`
- Modify: `backend/src/main/java/com/smartfinance/agent/investment/service/InvestmentAssetServiceImpl.java`
- Modify: `backend/src/main/java/com/smartfinance/agent/investment/dto/InvestmentAssetView.java`
- Modify: `backend/src/test/java/com/smartfinance/agent/investment/service/AnalysisServiceClientTest.java`
- Modify: `backend/src/test/java/com/smartfinance/agent/investment/service/InvestmentAssetServiceIntegrationTest.java`

**Interfaces:**
- Consumes: `POST /internal/v1/quotes/realtime`
- Produces: `AnalysisServiceClient.RealtimeQuote realtimeQuote(String code, String market)`
- Produces: `InvestmentAssetView.fetchedAt: LocalDateTime`

- [ ] **Step 1: 写入客户端解析和股票同步失败测试**

```java
assertThat(client.realtimeQuote("002632", "SZSE").latestPrice()).isEqualByComparingTo("8.63");
assertThat(assetService.sync(7L, asset.getId()).getFetchedAt())
        .isEqualTo(LocalDateTime.of(2026, 7, 13, 11, 23, 30));
```

- [ ] **Step 2: 运行定向测试并确认缺少接口/字段而失败**

Run: `mvn -q -Dtest=AnalysisServiceClientTest,InvestmentAssetServiceIntegrationTest test`

- [ ] **Step 3: 实现客户端、股票实时同步和失败保留旧价**

```java
public record RealtimeQuote(String code, String market, BigDecimal latestPrice,
                            LocalDate dataDate, LocalDateTime fetchedAt,
                            String provider, List<String> warnings) {}
```

`InvestmentAssetServiceImpl.sync` 对 `STOCK` 调用 `realtimeQuote`，更新当日 quote 的 `closePrice/source/syncedAt`；请求异常时只更新资产状态为 `FAILED`，不删除旧 quote。

- [ ] **Step 4: 运行定向测试确认通过**

Run: `mvn -q -Dtest=AnalysisServiceClientTest,InvestmentAssetServiceIntegrationTest test`

### Task 3: Java 后台 3 秒同步任务

**Files:**
- Create: `backend/src/main/java/com/smartfinance/agent/investment/service/InvestmentRealtimeQuoteWorker.java`
- Create: `backend/src/test/java/com/smartfinance/agent/investment/service/InvestmentRealtimeQuoteWorkerTest.java`
- Modify: `backend/src/main/resources/application.yml`

**Interfaces:**
- Consumes: `InvestmentAssetService.sync(userId, assetId)`
- Produces: `refreshStockAssets()` scheduled with `${investment.realtime.interval-ms:3000}`

- [ ] **Step 1: 写入失败测试**

```java
worker.refreshStockAssets(LocalDateTime.of(2026, 7, 13, 10, 0));
verify(assetService).sync(2L, stockAssetId);
verify(assetService, never()).sync(2L, fundAssetId);
```

- [ ] **Step 2: 运行测试并确认 Worker 不存在而失败**

Run: `mvn -q -Dtest=InvestmentRealtimeQuoteWorkerTest test`

- [ ] **Step 3: 实现固定延迟任务和交易时段判断**

```java
@Scheduled(fixedDelayString = "${investment.realtime.interval-ms:3000}")
public void refreshStockAssets() {
    refreshStockAssets(LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
}
```

仅工作日 `09:15-11:30`、`13:00-15:00` 调用股票资产，单个标的失败不得中断其他标的。

- [ ] **Step 4: 运行 Worker 与投资集成测试**

Run: `mvn -q -Dtest=InvestmentRealtimeQuoteWorkerTest,InvestmentAssetServiceIntegrationTest test`

### Task 4: Vue 3 秒刷新与精确时间展示

**Files:**
- Create: `frontend/src/lib/investmentRealtime.js`
- Create: `frontend/src/lib/investmentRealtime.test.mjs`
- Modify: `frontend/src/views/InvestmentAnalysis.vue`
- Modify: `frontend/src/components/investment/InvestmentAssetTable.vue`

**Interfaces:**
- Produces: `startInvestmentRealtimePolling(callback, intervalMs = 3000, scheduler = globalThis)` 返回停止函数
- Produces: `formatQuoteTime(fetchedAt, dataDate)`

- [ ] **Step 1: 写入失败测试**

```javascript
test('uses a 3000ms polling interval and returns cleanup', () => {
  const scheduler = fakeScheduler()
  const stop = startInvestmentRealtimePolling(load, 3000, scheduler)
  assert.equal(scheduler.interval, 3000)
  stop()
  assert.equal(scheduler.cleared, true)
})
```

- [ ] **Step 2: 运行测试并确认模块不存在而失败**

Run: `node --test src/lib/investmentRealtime.test.mjs`

- [ ] **Step 3: 实现轮询、卸载清理和时间展示**

```javascript
onMounted(() => {
  loadAssets()
  stopRealtimePolling = startInvestmentRealtimePolling(loadAssets)
  window.addEventListener('focus', loadAssets)
})
onUnmounted(() => {
  stopRealtimePolling?.()
  window.removeEventListener('focus', loadAssets)
})
```

资产表股票显示 `HH:mm:ss`，基金继续显示净值日期；轮询刷新不显示整表 loading 状态。

- [ ] **Step 4: 运行 Node 测试与前端构建**

Run: `node --test src/lib/investmentRealtime.test.mjs`
Run: `npm run build`

### Task 5: 全链路验证

**Files:**
- Verify: `.run-logs/*.log`

- [ ] **Step 1: 运行全量测试**

Run: `.\.venv\Scripts\python.exe -m unittest discover -s tests -v`
Run: `mvn -q test`
Run: `npm run build`

- [ ] **Step 2: 重启三进程并验证健康状态**

Run: `.\start-dev.ps1`
Expected: `8090/health`、`8080/api/auth/me`、`3000/stocks` 均可访问。

- [ ] **Step 3: 验证真实股票报价**

对 `002632` 调用 Python 实时报价接口，确认价格来源为 `TENCENT`、`fetchedAt` 精确到秒；观察页面 3 秒轮询且后台 Worker 日志无连续异常。

