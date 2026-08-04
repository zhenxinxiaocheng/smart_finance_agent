# Continuous Quant Training Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One user request starts a durable training session that keeps improving candidates, always exposes a non-tradable technical signal, and promotes only strictly validated models to simulated trading.

**Architecture:** Python evaluates every configured candidate and returns search evidence. Spring owns the durable session: rejected batches remain eligible for another generation, while data or benchmark preparation is retried independently. The frontend submits once and polls the session instead of re-enabling the button immediately.

**Tech Stack:** Python, FastAPI, scikit-learn/XGBoost, Spring Boot 3.2, MyBatis-Plus, Vue 3.

## Global Constraints

- Never lower strict validation thresholds to manufacture a result.
- DRAFT or technical-signal results cannot generate money, shares, or paper orders.
- Page opening cannot trigger training.
- Identical active requests must be reused.

---

### Task 1: Exhaust configured candidate search

**Files:**
- Modify: `analysis-service/app/quant/auto_search.py`
- Modify: `analysis-service/config/quant-research-v2.json`
- Test: `analysis-service/tests/test_quant_auto_search.py`

- [ ] Verify a rejected candidate does not stop remaining configured candidates.
- [ ] Return evaluated count, failure reasons, and whether another generation is required.
- [ ] Run the focused auto-search tests.

### Task 2: Durable Spring training session

**Files:**
- Modify: `backend/src/main/java/com/smartfinance/agent/investment/quant/QuantTrainingOrchestrator.java`
- Modify: `backend/src/main/java/com/smartfinance/agent/investment/quant/QuantModelManagementService.java`
- Modify: `backend/src/main/java/com/smartfinance/agent/investment/quant/QuantJobWorker.java`
- Test: `backend/src/test/java/com/smartfinance/agent/investment/quant/QuantServiceImplIntegrationTest.java`

- [ ] Keep a rejected automatic session in an optimizing state.
- [ ] Start the next fingerprinted generation without duplicating an active generation.
- [ ] Expose a technical-signal-ready state separately from a validated trading model.
- [ ] Run focused Spring tests.

### Task 3: Single-submit frontend

**Files:**
- Modify: `frontend/src/views/QuantModelManagement.vue`
- Test: `frontend/src/views/QuantModelManagement.test.ts`

- [ ] Keep the button disabled while the session is queued, running, preparing data, or optimizing.
- [ ] Poll management state after submission.
- [ ] Emit only one success notification per session.
- [ ] Run the focused frontend test or build.

### Task 4: Runtime acceptance

- [ ] Restart through `start-dev.ps1` on backend 8088, analysis 8090, frontend 3000.
- [ ] Submit one 010736 short-horizon session.
- [ ] Verify one active session, multiple candidate evaluations, technical signal availability, and no DRAFT order generation.
