# Quant Training Duration Indicators Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在量化模型管理页显示配置驱动的预计时长、实时运行时长和完成后的实际耗时。

**Architecture:** 分析服务根据同一份量化配置与请求数据规模产生估算秒数；Spring 持久化并通过模型管理接口输出；Vue 只在浏览器本地更新时间文案，不增加轮询频率。估算缺失时明确显示“计算中”，不使用页面固定值。

**Tech Stack:** Python 3.11/FastAPI、Spring Boot 3/MyBatis-Plus、MySQL/SQLite/H2、Vue 3、Node test runner。

## Global Constraints

- 不改变训练预算、停止条件、候选选择或模型晋级规则。
- 估算参数只存在于 `analysis-service/config/quant-research-v2.json`。
- 保留工作区已有调度器、启动脚本、产品站和旧计划文件改动。

---

### Task 1: 分析服务生成预计时长

**Files:**
- Create: `analysis-service/app/quant/duration.py`
- Modify: `analysis-service/app/quant/jobs.py:53-92`
- Modify: `analysis-service/config/quant-research-v2.json:59-63`
- Create: `analysis-service/tests/test_quant_duration.py`

**Interfaces:**
- Consumes: `QuantConfig.number(path)` 与量化任务请求中的 `records`、`benchmarkRecords`、`universeRecords[*].records`。
- Produces: `estimate_job_duration_seconds(request: Mapping[str, Any], config: QuantConfig) -> int | None`，以及任务状态字段 `estimatedDurationSeconds`。

- [ ] **Step 1: 写估算器失败测试**

```python
def test_auto_search_estimate_uses_search_budget_and_all_input_rows():
    request = {
        "type": "AUTO_SEARCH",
        "records": [{}] * 100,
        "benchmarkRecords": [{}] * 50,
        "universeRecords": [{"records": [{}] * 250}],
    }
    estimate = estimate_job_duration_seconds(request, configured_quant_config)
    assert estimate == 1864

def test_non_auto_search_job_has_no_duration_estimate():
    assert estimate_job_duration_seconds({"type": "PREDICT"}, configured_quant_config) is None
```

- [ ] **Step 2: 验证测试因缺少模块而失败**

Run: `analysis-service/.venv/Scripts/python.exe -m pytest analysis-service/tests/test_quant_duration.py -q`

Expected: FAIL，提示 `app.quant.duration` 不存在。

- [ ] **Step 3: 实现配置驱动估算器并写入任务状态**

```python
def estimate_job_duration_seconds(request, config):
    if str(request.get("type", "")).upper() != "AUTO_SEARCH":
        return None
    row_count = len(request.get("records") or []) + len(request.get("benchmarkRecords") or [])
    row_count += sum(len(item.get("records") or []) for item in request.get("universeRecords") or [])
    preprocessing = math.ceil(
        row_count * config.number("autoSearch.durationEstimate.preprocessingSecondsPerRecord")
    )
    return int(
        config.number("autoSearch.timeBudgetSeconds")
        + config.number("autoSearch.durationEstimate.finalizationSeconds")
        + preprocessing
    )
```

在 `QuantJobService.submit()` 的初始状态中加入 `estimatedDurationSeconds`。配置增加 `preprocessingSecondsPerRecord: 0.01` 和 `finalizationSeconds: 60`。

- [ ] **Step 4: 运行 Python 聚焦测试**

Run: `analysis-service/.venv/Scripts/python.exe -m pytest analysis-service/tests/test_quant_duration.py analysis-service/tests/test_quant_jobs.py -q`

Expected: PASS。

---

### Task 2: Spring 持久化并输出时长字段

**Files:**
- Modify: `backend/src/main/java/com/smartfinance/agent/investment/quant/QuantJob.java`
- Modify: `backend/src/main/java/com/smartfinance/agent/investment/quant/QuantTrainingOrchestrator.java:312-385,622-654`
- Modify: `backend/src/main/java/com/smartfinance/agent/investment/quant/QuantModelManagementService.java:135-176`
- Create: `backend/src/main/resources/db/migration/mysql/V24__quant_job_duration_estimate.sql`
- Create: `backend/src/main/resources/db/migration/sqlite/V24__quant_job_duration_estimate.sql`
- Modify: `backend/src/test/resources/schema-h2.sql`
- Modify: `backend/src/test/java/com/smartfinance/agent/investment/quant/QuantServiceImplIntegrationTest.java`

**Interfaces:**
- Consumes: 分析服务状态字段 `estimatedDurationSeconds`。
- Produces: `training.startedAt`、`training.estimatedDurationSeconds`、`training.actualDurationSeconds`。

- [ ] **Step 1: 写 Spring 失败测试**

```java
assertThat(created.get("estimatedDurationSeconds")).isEqualTo(2460L);
assertThat(training.get("startedAt")).isNotNull();
assertThat(training.get("estimatedDurationSeconds")).isEqualTo(2460L);
assertThat(training.get("actualDurationSeconds")).isEqualTo(125L);
```

测试创建任务时远端返回估算值，并构造开始、结束时间验证实际耗时。

- [ ] **Step 2: 验证 Spring 测试因字段缺失而失败**

Run: `cd backend; mvn -q "-Dtest=QuantServiceImplIntegrationTest#trainingDurationFieldsArePersistedAndExposed" test`

Expected: FAIL，返回 Map 不含预计或实际时长。

- [ ] **Step 3: 添加实体、迁移和映射**

```java
private Long estimatedDurationSeconds;
```

创建任务和轮询时读取远端数值；`jobView` 输出创建、开始、结束与预计秒数；`trainingView` 用 `Duration.between(startedAtOrCreatedAt, finishedAt)` 计算终态实际秒数。

- [ ] **Step 4: 运行 Spring 聚焦测试**

Run: `cd backend; mvn -q "-Dtest=QuantServiceImplIntegrationTest" test`

Expected: PASS。

---

### Task 3: Vue 显示实时、预计和实际耗时

**Files:**
- Modify: `frontend/src/lib/quantModelManagement.js`
- Modify: `frontend/src/lib/quantModelManagement.test.mjs`
- Modify: `frontend/src/views/QuantModelManagement.vue:53-63,213-283`

**Interfaces:**
- Consumes: `training.createdAt`、`startedAt`、`finishedAt`、`estimatedDurationSeconds`、`actualDurationSeconds` 和执行状态。
- Produces: `formatDuration(seconds)` 与 `trainingDurationView(training, nowMillis)`。

- [ ] **Step 1: 写前端失败测试**

```javascript
assert.equal(formatDuration(3723), '1小时2分')
assert.deepEqual(trainingDurationView(running, now), {
  elapsed: '已运行 12分30秒',
  estimate: '预计时长 约10分钟',
  overdue: '已超过预计时长 2分30秒',
})
```

同时覆盖终态“实际耗时”和估算缺失“计算中”。

- [ ] **Step 2: 验证 Node 测试因导出缺失而失败**

Run: `node --test frontend/src/lib/quantModelManagement.test.mjs`

Expected: FAIL，提示缺少 `formatDuration` 或 `trainingDurationView`。

- [ ] **Step 3: 实现纯函数和本地计时器**

```javascript
const durationNow = ref(Date.now())
let durationTimer = null

onMounted(() => {
  durationTimer = window.setInterval(() => { durationNow.value = Date.now() }, 1000)
})
onBeforeUnmount(() => window.clearInterval(durationTimer))
```

状态卡显示两个紧凑标识；超时时使用警示色；终态显示实际耗时。保留“最近检查”文字。

- [ ] **Step 4: 运行前端聚焦测试和构建**

Run: `node --test frontend/src/lib/quantModelManagement.test.mjs`

Run: `cd frontend; npm run build`

Expected: PASS。

---

### Task 4: 完整回归、真实接口验收与提交

**Files:**
- Verify only; do not include unrelated working-tree files.

**Interfaces:**
- Consumes: Tasks 1-3 的完整数据链。
- Produces: 可提交的训练时长功能和真实接口证据。

- [ ] **Step 1: 运行完整测试**

Run: `analysis-service/.venv/Scripts/python.exe -m pytest analysis-service/tests -q`

Run: `cd backend; mvn -q test`

Run: `node --test frontend/src/lib/*.test.mjs`

Expected: 全部通过。

- [ ] **Step 2: 重启本地服务并验收**

Run: `.\start-dev.ps1`

确认 010736 的模型管理响应包含预计/实际时长，页面运行中标识会本地递增，且轮询仍为 3 秒一次。

- [ ] **Step 3: 检查提交边界并提交**

```powershell
git diff --check
git status --short
git add -- analysis-service/app/quant/duration.py analysis-service/app/quant/jobs.py analysis-service/config/quant-research-v2.json analysis-service/tests/test_quant_duration.py backend/src/main/java/com/smartfinance/agent/investment/quant/QuantJob.java backend/src/main/java/com/smartfinance/agent/investment/quant/QuantTrainingOrchestrator.java backend/src/main/java/com/smartfinance/agent/investment/quant/QuantModelManagementService.java backend/src/main/resources/db/migration/mysql/V24__quant_job_duration_estimate.sql backend/src/main/resources/db/migration/sqlite/V24__quant_job_duration_estimate.sql backend/src/test/resources/schema-h2.sql backend/src/test/java/com/smartfinance/agent/investment/quant/QuantServiceImplIntegrationTest.java frontend/src/lib/quantModelManagement.js frontend/src/lib/quantModelManagement.test.mjs frontend/src/views/QuantModelManagement.vue docs/superpowers/plans/2026-08-01-quant-training-duration-indicators.md
git commit -m "feat: show quant training duration"
```

只提交本功能文件，保留用户原有未提交改动。
