# Quant Model Contract Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the real Spring Boot to FastAPI quant training request succeed and verify asset 6 produces a persisted prediction visible in the UI.

**Architecture:** Keep the existing Python model service and Spring orchestration. Extend only the FastAPI request contract to accept the two fields already consumed by the quant engine, protect the contract with an endpoint-level regression test, then verify the asynchronous job and persisted prediction end to end.

**Tech Stack:** Python 3, FastAPI, Pydantic v2, unittest, Spring Boot, Vue 3

## Global Constraints

- Preserve the existing quant architecture and database schema.
- Do not hard-code new environment-specific values.
- Do not alter unrelated dirty-worktree changes.
- Completion requires a real asset 6 training result, not only unit tests.

---

### Task 1: Reproduce the request-contract failure

**Files:**
- Modify: `analysis-service/tests/test_analysis_endpoints.py`

**Interfaces:**
- Consumes: `QuantJobRequest`
- Produces: regression coverage for `benchmarkCode` and `currentWeight`

- [ ] Add a test constructing `QuantJobRequest` with the exact camelCase fields sent by Spring Boot.
- [ ] Assert `model_dump(by_alias=True)` preserves both fields.
- [ ] Run the focused test and confirm it fails with `extra_forbidden`.

### Task 2: Accept and validate portfolio context

**Files:**
- Modify: `analysis-service/app/main.py`

**Interfaces:**
- Consumes: JSON `benchmarkCode: string` and `currentWeight: number`
- Produces: aliased Pydantic fields forwarded unchanged to `QuantJobService`

- [ ] Add `benchmark_code` with alias `benchmarkCode`.
- [ ] Add `current_weight` with alias `currentWeight`, constrained to `0 <= value <= 1`.
- [ ] Run the focused test and the complete analysis-service test suite.

### Task 3: Verify the running system

**Files:**
- Runtime only; no source file changes expected.

**Interfaces:**
- Consumes: `POST /api/investment/assets/6/quant-analysis/refresh`
- Produces: a completed quant job and `GET .../quant-analysis?horizonCode=MEDIUM` response

- [ ] Restart the analysis service so the corrected request model is active.
- [ ] Trigger asset 6 MEDIUM training through the authenticated UI.
- [ ] Poll until the job is terminal.
- [ ] Verify the latest quant response is no longer `MODEL_UNAVAILABLE`.
- [ ] Verify the page renders probability, confidence, regime, target weight, and prediction interval.
