# Investment History Jobs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增股票或基金后立即返回最新价格/净值，并由可恢复的后台任务自动补齐历史行情和分析数据，详情页自动刷新任务状态。

**Architecture:** 使用数据库表 `investment_data_job` 保存每个资产的历史准备任务；资产新增与任务入队位于同一事务。定时 Worker 领取任务后复用 `InvestmentAnalysisService.refresh/retryData` 的股票 K 线、基金净值、数据质量和分析链路，失败按持久化次数退避重试。详情接口只读任务状态并为旧资产幂等补任务，前端仅在任务处于 `QUEUED/RUNNING/RETRY_WAIT` 时轮询。

**Tech Stack:** Spring Boot 3.2、MyBatis-Plus、Flyway、MySQL/SQLite/H2、Vue 3、Node test runner。

## Global Constraints

- 股票和基金使用同一套任务状态机，股票任务类型为 `STOCK_HISTORY`，基金任务类型为 `FUND_NAV_HISTORY`。
- 新增接口继续同步强制拉取最新价格或最新净值，不同步等待历史数据。
- 每个资产和任务类型只保留一条任务记录；手动刷新重置该记录，不生成重复任务。
- 任务状态固定为 `QUEUED`、`RUNNING`、`RETRY_WAIT`、`SUCCEEDED`、`PARTIAL`、`FAILED`。
- 初始任务复用可靠数据快照，手动刷新才强制重新拉取上游数据。
- 历史任务失败不得删除资产或覆盖已经保存的最新价格/净值。
- 详情接口不得同步调用外部行情源。
- 不修改 `start-dev.ps1`、`product-site/` 和投资模块之外的现有未提交改动。

---

### Task 1: 持久化任务模型与入队服务

**Files:**
- Create: `backend/src/main/resources/db/migration/mysql/V11__investment_data_jobs.sql`
- Create: `backend/src/main/resources/db/migration/sqlite/V11__investment_data_jobs.sql`
- Create: `backend/src/main/java/com/smartfinance/agent/investment/entity/InvestmentDataJob.java`
- Create: `backend/src/main/java/com/smartfinance/agent/investment/mapper/InvestmentDataJobMapper.java`
- Create: `backend/src/main/java/com/smartfinance/agent/investment/service/InvestmentDataJobService.java`
- Test: `backend/src/test/java/com/smartfinance/agent/investment/service/InvestmentDataJobServiceTest.java`
- Modify: `backend/src/test/resources/schema-h2.sql`
- Modify: `backend/src/test/java/com/smartfinance/agent/investment/InvestmentSqliteMigrationTest.java`

**Interfaces:**
- Produces: `InvestmentDataJob ensureQueued(Long userId, Long assetId, Long productId, String productType, boolean forceRefresh)`。
- Produces: `Map<String,Object> statusForAsset(Long userId, Long assetId)`，只返回用户可见状态、记录数、错误和时间。
- Produces: `List<InvestmentDataJob> pendingJobs(int limit)`、`boolean claim(Long id, LocalDateTime now, LocalDateTime leaseUntil)` 及完成/重试状态更新方法供 Worker 使用。

- [ ] **Step 1: 先写失败测试**

  覆盖首次入队、同资产幂等、基金任务类型、手动刷新重置终态、其他用户不能读取任务、SQLite V11 表和索引存在。

- [ ] **Step 2: 运行测试确认因缺少实体、表和服务失败**

  Run: `mvn -q '-Dtest=InvestmentDataJobServiceTest,InvestmentSqliteMigrationTest' test`

- [ ] **Step 3: 实现最小持久化模型**

  表字段为 `id,user_id,asset_id,product_id,job_type,status,force_refresh,record_count,attempt_count,next_retry_at,lease_until,error_message,started_at,finished_at,created_at,updated_at`；唯一键为 `(asset_id,job_type)`，待执行索引为 `(status,next_retry_at,created_at)`。

- [ ] **Step 4: 实现幂等入队和用户安全状态视图**

  新任务为 `QUEUED`；活跃任务直接复用；`forceRefresh=true` 时重置状态、次数、错误和完成时间并设置强制刷新标记。

- [ ] **Step 5: 运行聚焦测试至通过**

  Run: `mvn -q '-Dtest=InvestmentDataJobServiceTest,InvestmentSqliteMigrationTest' test`

### Task 2: 新增资产、旧资产修复与后台 Worker

**Files:**
- Create: `backend/src/main/java/com/smartfinance/agent/investment/service/InvestmentDataJobWorker.java`
- Test: `backend/src/test/java/com/smartfinance/agent/investment/service/InvestmentDataJobWorkerTest.java`
- Modify: `backend/src/main/java/com/smartfinance/agent/investment/service/InvestmentAssetServiceImpl.java`
- Modify: `backend/src/main/java/com/smartfinance/agent/investment/service/InvestmentAnalysisServiceImpl.java`
- Modify: `backend/src/main/java/com/smartfinance/agent/investment/controller/InvestmentAssetController.java`
- Modify: `backend/src/test/java/com/smartfinance/agent/investment/service/InvestmentAssetServiceIntegrationTest.java`
- Modify: `backend/src/test/java/com/smartfinance/agent/investment/controller/InvestmentAssetControllerTest.java`
- Modify: `backend/src/main/resources/application.yml`

**Interfaces:**
- Consumes: Task 1 的 `InvestmentDataJobService`。
- Produces: `GET /api/investment/assets/{id}/history-job`。
- Changes: `POST /api/investment/assets/{id}/data-quality/refresh` 仅强制入队并立即返回只读详情，不再在 HTTP 线程调用上游数据源。
- Changes: `sourceStatus.historyJob` 包含 `status,recordCount,attemptCount,errorMessage,updatedAt`。

- [ ] **Step 1: 先写新增行为失败测试**

  断言股票新增创建 `STOCK_HISTORY`，基金新增创建 `FUND_NAV_HISTORY`，且最新价格/净值行为保持不变。

- [ ] **Step 2: 运行新增测试确认没有任务入队**

  Run: `mvn -q '-Dtest=InvestmentAssetServiceIntegrationTest' test`

- [ ] **Step 3: 在资产事务内入队**

  `InvestmentAssetServiceImpl.create` 在保存最新行情和持仓之后调用 `ensureQueued(..., false)`；任务插入与资产提交原子完成。

- [ ] **Step 4: 先写 Worker 失败测试**

  覆盖原子领取、初始任务调用 `analysisService.refresh`、手动任务调用 `retryData`、至少 20 条历史后成功、异常后指数退避、第三次失败进入 `FAILED`、过期 `RUNNING` 可恢复。

- [ ] **Step 5: 实现 Worker**

  `@Scheduled` 每秒扫描，批量上限 2；领取租约 120 秒；失败重试间隔为 60 秒、300 秒，最多 3 次。任务成功以详情 `quoteSeries.size() >= minimumHistoryTradingDays` 为准，不因最新行情失败删除历史结果。

- [ ] **Step 6: 先写详情和控制器失败测试**

  断言旧资产无任务且历史不足时幂等入队；详情只读取任务；手动“重新拉取”仅强制入队；任务查询校验用户归属。

- [ ] **Step 7: 接入详情和接口**

  `readOnlyDetail` 将任务安全视图放入 `sourceStatus.historyJob`；旧资产仅在无任务且历史少于 20 条时创建初始任务，不直接访问数据源。

- [ ] **Step 8: 运行后端聚焦测试至通过**

  Run: `mvn -q '-Dtest=InvestmentDataJobServiceTest,InvestmentDataJobWorkerTest,InvestmentAssetServiceIntegrationTest,InvestmentAssetControllerTest,InvestmentSqliteMigrationTest' test`

### Task 3: 详情页自动轮询与可理解状态

**Files:**
- Modify: `frontend/src/api/investment.js`
- Modify: `frontend/src/views/InvestmentAssetDetail.vue`
- Modify: `frontend/src/views/InvestmentAssetDetail.test.mjs`
- Create: `frontend/src/lib/investmentHistoryJob.js`
- Create: `frontend/src/lib/investmentHistoryJob.test.mjs`

**Interfaces:**
- Consumes: `sourceStatus.historyJob` 和 `GET /investment/assets/{id}/history-job`。
- Produces: `shouldPollHistoryJob(status)` 与轮询生命周期控制。

- [ ] **Step 1: 先写轮询状态失败测试**

  `QUEUED/RUNNING/RETRY_WAIT` 返回 true；`SUCCEEDED/PARTIAL/FAILED` 返回 false。

- [ ] **Step 2: 运行测试确认模块不存在**

  Run: `node --test src/lib/investmentHistoryJob.test.mjs src/views/InvestmentAssetDetail.test.mjs`

- [ ] **Step 3: 实现API和页面轮询**

  页面可见且任务活跃时每 3 秒查询；成功或部分成功后重载详情和图表；失败停止轮询并显示重试入口；路由切换和组件卸载清理定时器；已有实时价格刷新逻辑不变。

- [ ] **Step 4: 收紧用户文案测试**

  源码检查只针对 `<template>`，允许脚本内部使用 `BLOCKED` 状态常量，但模板不得展示内部诊断字段。

- [ ] **Step 5: 运行前端聚焦测试和构建**

  Run: `node --test src/lib/investmentHistoryJob.test.mjs src/views/InvestmentAssetDetail.test.mjs`

  Run: `npm run build`

### Task 4: 回归验证

**Files:**
- Verify only.

- [ ] **Step 1: 后端投资模块回归**

  Run: `mvn -q '-Dtest=InvestmentDataJobServiceTest,InvestmentDataJobWorkerTest,InvestmentAssetServiceIntegrationTest,InvestmentAssetControllerTest,InvestmentSyncWorkerTest,InvestmentSqliteMigrationTest' test`

- [ ] **Step 2: 前端相关回归与生产构建**

  Run: `node --test src/lib/investmentHistoryJob.test.mjs src/views/InvestmentAssetDetail.test.mjs src/lib/investmentRealtime.test.mjs src/components/investment/InvestmentAssetTable.test.mjs`

  Run: `npm run build`

- [ ] **Step 3: 检查改动边界**

  Run: `git diff --check`

  确认没有修改 `start-dev.ps1`、`product-site/` 或覆盖用户已有改动。
