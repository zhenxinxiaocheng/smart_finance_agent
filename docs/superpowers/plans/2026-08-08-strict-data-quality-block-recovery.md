# Strict Data Quality Block Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ensure real data-quality blocks never expose stale analysis, operational failures are not mislabeled as data-quality blocks, and a deduplicated recovery job automatically replaces stale snapshots after quality returns to `ALLOW`.

**Architecture:** Keep strict Python integrity rules unchanged. Spring separates explicit rule decisions from runtime availability, emits empty analysis payloads whenever the gate is closed, and queues one recovery job without resetting active work. The existing frontend job poll reloads the detail after recovery, causing the normal cache-key path to generate and persist a new snapshot.

**Tech Stack:** Java 17, Spring Boot 3.2, MyBatis-Plus, JUnit 5, Mockito, Python/FastAPI quality service, Vue 3.

## Global Constraints

- Do not weaken critical fund or stock data-integrity rules.
- `BLOCKED + BLOCK` and `UNAVAILABLE/WAITING` must never return an old technical conclusion, fund action, backtest, or AI explanation.
- Historical snapshots remain stored for audit only.
- Existing unrelated working-tree changes must remain untouched.
- Recovery retries remain 60 seconds, 300 seconds, then `FAILED` on the third failure.

---

### Task 1: Close the analysis gate without stale fallback

**Files:**
- Modify: `backend/src/main/java/com/smartfinance/agent/investment/service/InvestmentAnalysisServiceImpl.java`
- Test: `backend/src/test/java/com/smartfinance/agent/investment/service/InvestmentDataJobWorkerTest.java`

**Interfaces:**
- Consumes: `InvestmentDataQualityService.Evaluation.blocked()` and the existing analysis snapshot.
- Produces: detail responses whose `technicalAnalysis.status` is `BLOCKED` or `UNAVAILABLE`, with empty backtest and AI result when the gate is closed.

- [ ] **Step 1: Write failing tests**

Add fixture tests that provide an existing snapshot and either an explicit `decision=BLOCK` or a thrown quality-service exception. Assert that the response does not contain the snapshot score, verdict, action, backtest occurrences, or AI text; assert `historicalCache=false` and `dataState=BLOCKED` or `WAITING`.

- [ ] **Step 2: Run the tests and confirm failure**

Run:

```powershell
mvn -q "-Dtest=InvestmentDataJobWorkerTest" test
```

Expected: the new assertions fail because the current implementation reads `snapshot.getTechnicalJson()` and marks it as historical cache.

- [ ] **Step 3: Implement the minimal gate response**

Change the quality exception state to `qualityStatus=UNAVAILABLE`, `qualityDecision=WAITING`, and `analysisGate=WAITING`. In the closed-gate branch, create fresh status-only maps instead of reading any snapshot JSON. Skip AI explanation generation and indicator-series merging while the gate is closed. Preserve raw persisted quotes for chart display.

- [ ] **Step 4: Run the focused test**

Run the Step 2 command and require zero failures.

### Task 2: Queue recovery without resetting active jobs

**Files:**
- Modify: `backend/src/main/java/com/smartfinance/agent/investment/service/InvestmentDataJobService.java`
- Modify: `backend/src/main/java/com/smartfinance/agent/investment/service/InvestmentAnalysisServiceImpl.java`
- Test: `backend/src/test/java/com/smartfinance/agent/investment/service/InvestmentDataJobServiceTest.java`
- Test: `backend/src/test/java/com/smartfinance/agent/investment/service/InvestmentDataJobWorkerTest.java`

**Interfaces:**
- Produces: `InvestmentDataJob ensureRecoveryQueued(Long userId, Long assetId, Long productId, String productType)`.
- Active statuses: `QUEUED`, `RUNNING`, `RETRY_WAIT`; these must be returned unchanged.
- Terminal `FAILED` remains failed until explicit user retry; empty, successful, partial, or cancelled work can be queued for a new recovery cycle.

- [ ] **Step 1: Write failing service tests**

Cover: active job is not reset; a completed job is reset to `QUEUED` with `forceRefresh=true`; a failed job is not automatically looped; explicit `ensureQueued(..., true)` still permits manual retry.

- [ ] **Step 2: Run and confirm failure**

```powershell
mvn -q "-Dtest=InvestmentDataJobServiceTest" test
```

- [ ] **Step 3: Implement recovery queuing**

Add `ensureRecoveryQueued` and call it from `autoAnalyze` when latest quality is explicitly blocked or not yet evaluated. Never return the old snapshot from that path. Keep the existing manual refresh endpoint as the explicit retry for `FAILED` jobs.

- [ ] **Step 4: Verify recovery transition**

Add a service-level test where blocked quality queues recovery, the later quality state is `ALLOW`, and a subsequent detail request calls the analysis client and saves a new snapshot rather than returning the old payload.

### Task 3: Expose clear UI states without cached conclusions

**Files:**
- Modify: `backend/src/main/java/com/smartfinance/agent/investment/service/InvestmentAnalysisServiceImpl.java`
- Modify: `frontend/src/views/InvestmentAssetDetail.vue`
- Test: `frontend/test/investmentDetailPage.test.mjs`

**Interfaces:**
- `sourceStatus.dataState`: `READY | BLOCKED | WAITING`.
- `sourceStatus.qualityStatus`, `qualityDecision`, `analysisGate`, `qualityIssues`, and `dataQualityError` are user-safe response fields.

- [ ] **Step 1: Add failing response/UI assertions**

Assert the backend exposes the listed safe fields. Assert the Vue page has distinct copy for `BLOCKED` and `WAITING`, never renders the old “使用可靠缓存” message, and does not describe a blocked result as current analysis.

- [ ] **Step 2: Run and confirm failure**

```powershell
node --test test/investmentDetailPage.test.mjs
```

- [ ] **Step 3: Implement state-specific presentation**

Extend `userSafeSourceStatus`; derive blocked and waiting UI flags from `sourceStatus.dataState`; display the failed quality-rule messages or transient service error and retain only the retry-data action.

- [ ] **Step 4: Verify frontend behavior**

Run the Step 2 command, then `npm run build`.

### Task 4: Full regression verification

**Files:**
- Verify only; do not modify unrelated files.

- [ ] **Step 1: Run Python quality-rule tests**

```powershell
analysis-service\.venv\Scripts\python.exe -m pytest analysis-service/tests/test_data_quality_engine.py analysis-service/tests/test_data_quality_service.py -q
```

- [ ] **Step 2: Run focused Spring tests**

```powershell
cd backend
mvn -q "-Dtest=InvestmentAnalysisSignalTest,InvestmentDataJobServiceTest,InvestmentDataJobWorkerTest,InvestmentAssetServiceIntegrationTest" test
```

- [ ] **Step 3: Run frontend tests and production build**

```powershell
cd frontend
node --test test/investmentDetailPage.test.mjs
npm run build
```

- [ ] **Step 4: Review final diff**

Run `git diff --check` and confirm only the approved recovery implementation plus pre-existing agent changes are present.
