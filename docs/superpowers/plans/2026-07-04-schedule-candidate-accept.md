# Schedule Candidate Accept Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow `SCHEDULE_CANDIDATE` reflections with explicit daily/weekly/monthly intent to become confirmable schedule pending actions.

**Architecture:** `AgentReflectionService.accept` converts schedule candidates into the existing `CREATE_AGENT_SCHEDULE` pending action. Cron inference stays deterministic and conservative: daily uses 09:00 every day, weekly uses Monday 09:00, monthly uses day 1 at 09:00. Ambiguous schedule candidates remain advisory.

**Tech Stack:** Spring Boot 3.2.5, Mockito/JUnit 5, existing `PendingActionService.prepareSchedule`, Vue 3 trace drawer.

---

### Task 1: Backend Schedule Candidate Accept

**Files:**
- Modify: `backend/src/main/java/com/smartfinance/agent/service/impl/AgentReflectionServiceImpl.java`
- Modify: `backend/src/test/java/com/smartfinance/agent/service/impl/AgentReflectionServiceImplTest.java`

- [x] **Step 1: Write failing tests**

Add tests covering:

- daily schedule candidate converts to `prepareSchedule(..., "0 0 9 * * ?", ..., "Asia/Shanghai")`
- ambiguous schedule candidate is rejected instead of inventing a cron

- [x] **Step 2: Run red test**

Run:

```powershell
mvn -q "-Dtest=AgentReflectionServiceImplTest" test
```

Expected: fail because `SCHEDULE_CANDIDATE` cannot be accepted yet.

Current result: red test failed because schedule candidates still raised `This reflection cannot be accepted automatically yet`.

- [x] **Step 3: Implement conservative schedule conversion**

Add `toScheduleDraft` helper and wire `SCHEDULE_CANDIDATE` into `accept`.

- [x] **Step 4: Run green test**

Run the same Maven command and verify it passes.

Current result: targeted Maven test passed.

### Task 2: Frontend Schedule Candidate Label

**Files:**
- Modify: `frontend/src/views/ChatView.vue`

- [x] **Step 1: Update accept eligibility and label**

Allow `SCHEDULE_CANDIDATE` and show `生成待确认任务`.

- [x] **Step 2: Run frontend build**

Run:

```powershell
npm run build
```

Expected: build succeeds with only existing warnings.

Current result: `npm run build` passed with existing Rolldown pure annotation, large chunk, and router dynamic import warnings.

### Task 3: Verification and Documentation

**Files:**
- Modify: `README.md`
- Modify: `docs/hermes-agent-transformation-report.md`
- Modify: `docs/superpowers/plans/2026-07-04-schedule-candidate-accept.md`

- [x] **Step 1: Document boundary**

Document that only explicit daily/weekly/monthly schedule candidates can be converted; ambiguous ones stay advisory.

- [x] **Step 2: Run verification**

Run:

```powershell
mvn clean test
npm run build
git diff --check
```

Expected:

- backend tests pass
- frontend build passes
- `git diff --check` has no whitespace errors beyond LF/CRLF warnings

Current result:

- `mvn clean test`: `Tests run: 123, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.
- `npm run build`: build succeeded with existing warnings.
- `git diff --check`: no whitespace errors, only LF/CRLF warnings.
