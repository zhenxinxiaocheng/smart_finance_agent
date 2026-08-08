# Fund Classification And Analysis Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist provider-backed fund categories, prevent generic fund rules from emitting advice, and ensure each user horizon is calculated only from the validated analysis window.

**Architecture:** Python owns provider metadata normalization through a versioned configuration; Spring persists the normalized classification and enforces the analysis gate. Fund analysis remains descriptive until a category-specific, benchmark-backed strategy is registered. Existing uncommitted horizon work is preserved and corrected rather than replaced.

**Tech Stack:** Python 3/FastAPI/Pydantic, Java 17/Spring Boot/MyBatis-Plus/Flyway, Vue 3, pytest/JUnit/AssertJ.

## Global Constraints

- Do not resume or change quantitative model training or inference.
- Do not infer a fund category from a product code or fund name.
- Category mappings must be versioned configuration, not conditional literals in analysis code.
- Missing or unsupported classification must return `INSUFFICIENT` and `WAIT`, never a buy/sell/accumulate action.
- Each SHORT/MEDIUM/LONG result must use its own configured target period and validated records.
- Preserve all existing user changes in the dirty worktree.

---

### Task 1: Provider-backed fund classification contract

**Files:**
- Create: `analysis-service/config/fund-classification-v1.json`
- Create: `analysis-service/app/fund_classification.py`
- Create: `analysis-service/tests/test_fund_classification.py`
- Modify: `analysis-service/app/providers.py`
- Modify: `analysis-service/tests/test_provider_normalization.py`

**Interfaces:**
- Produces: `classify_fund_type(raw_type: str | None) -> FundClassification`
- Produces resolver fields: `fundTypeRaw`, `fundCategory`, `classificationSource`, `classificationVersion`

- [ ] Write failing tests for exact/prefix mappings, unknown types, and resolver metadata.
- [ ] Run `pytest tests/test_fund_classification.py tests/test_provider_normalization.py -q` and confirm failure.
- [ ] Add the versioned JSON mapping and loader with fail-closed validation.
- [ ] Enrich `resolve_product_metadata` from `fund_name_em` while keeping snapshot/history fallbacks.
- [ ] Run the focused Python tests and confirm pass.

### Task 2: Persist classification independently of quant runtime

**Files:**
- Create: `backend/src/main/resources/db/migration/mysql/V25__fund_classification.sql`
- Create: `backend/src/main/resources/db/migration/sqlite/V25__fund_classification.sql`
- Create: `backend/src/main/java/com/smartfinance/agent/investment/domain/FundClassificationPolicy.java`
- Modify: `backend/src/main/java/com/smartfinance/agent/investment/entity/InvestmentProduct.java`
- Modify: `backend/src/main/java/com/smartfinance/agent/investment/service/AnalysisServiceClient.java`
- Modify: `backend/src/main/java/com/smartfinance/agent/investment/service/InvestmentAssetServiceImpl.java`
- Create: `backend/src/main/java/com/smartfinance/agent/investment/service/FundClassificationService.java`
- Modify: `backend/src/main/java/com/smartfinance/agent/investment/service/InvestmentHistoryPreparationService.java`
- Modify: `backend/src/main/java/com/smartfinance/agent/investment/dto/InvestmentAssetView.java`
- Test: `backend/src/test/java/com/smartfinance/agent/investment/domain/FundClassificationPolicyTest.java`
- Test: `backend/src/test/java/com/smartfinance/agent/investment/service/AnalysisServiceClientTest.java`

**Interfaces:**
- Consumes resolver classification fields from Task 1.
- Produces persisted fields `fundCategory`, `fundTypeRaw`, `classificationSource`, `classificationVersion`, `classifiedAt`.

- [ ] Write failing Java tests for resolver parsing and source-priority replacement.
- [ ] Run the focused Maven tests and confirm failure.
- [ ] Add MySQL/SQLite migrations and entity/DTO fields.
- [ ] Implement source-priority persistence without product-code/name rules.
- [ ] Enrich existing funds only when a history job observes missing classification; do not add provider calls to the read-only detail request.
- [ ] Run the focused Maven tests and confirm pass.

### Task 3: Fail-closed fund advice and validated-window horizons

**Files:**
- Modify: `analysis-service/app/main.py`
- Modify: `analysis-service/app/analysis.py`
- Modify: `analysis-service/tests/test_analysis_engine.py`
- Modify: `analysis-service/tests/test_analysis_endpoints.py`
- Modify: `backend/src/main/java/com/smartfinance/agent/investment/service/AnalysisServiceClient.java`
- Modify: `backend/src/main/java/com/smartfinance/agent/investment/service/InvestmentAnalysisServiceImpl.java`
- Modify: `backend/src/test/java/com/smartfinance/agent/investment/service/InvestmentAnalysisSignalTest.java`

**Interfaces:**
- Fund request consumes `fundCategory`, configured horizons, primary horizon, and only quality-approved records.
- Fund result produces descriptive metrics plus `adviceStatus`, `reasonCode`, and `action=WAIT` until a category strategy is validated.

- [ ] Write failing tests for missing category, unsupported category, independent horizons, and unvalidated-history exclusion.
- [ ] Run focused Python and Java tests and confirm failure.
- [ ] Extend the fund request contract and fail-closed response.
- [ ] Pass quality-approved records instead of the complete unvalidated quote cache.
- [ ] Include classification in cache material so changes invalidate stale snapshots.
- [ ] Run focused tests and confirm pass.

### Task 4: Migration and regression verification

**Files:**
- Test: existing migration, investment analysis, provider, and frontend investment-detail tests.

**Interfaces:**
- Consumes Tasks 1-3 outputs.
- Produces verified build/test evidence without changing quant-model lifecycle state.

- [ ] Run Python provider and analysis suites.
- [ ] Run focused backend migration and investment analysis tests.
- [ ] Run frontend investment-detail tests and production build if the API response changes affect rendering.
- [ ] Inspect `git diff --check` and the final scoped diff.
