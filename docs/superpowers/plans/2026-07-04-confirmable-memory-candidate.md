# Confirmable Memory Candidate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add confirmable `MEMORY_CANDIDATE` reflections so useful user preferences can become long-term Agent memory only after user confirmation.

**Architecture:** `AgentReflectionService` creates conservative memory suggestions from completed run evidence. Accepting a memory candidate creates an `INSTALL_AGENT_MEMORY` pending action; confirming that action calls `AgentMemoryService.createManual`. The frontend reuses the existing reflection card and pending-action confirmation surfaces.

**Tech Stack:** Spring Boot 3.2.5, MyBatis-Plus, Jackson, Vue 3, Element Plus, existing `PendingActionService` and `AgentMemoryService`.

---

### Task 1: Pending Memory Action

**Files:**
- Modify: `backend/src/main/java/com/smartfinance/agent/service/PendingActionService.java`
- Modify: `backend/src/main/java/com/smartfinance/agent/service/impl/PendingActionServiceImpl.java`
- Modify: `backend/src/test/java/com/smartfinance/agent/service/impl/PendingActionServiceImplTest.java`

- [x] **Step 1: Write failing test**

Add `prepareMemory_shouldCreatePendingAction_thenConfirmCreatesManualMemory` to `PendingActionServiceImplTest`.

- [x] **Step 2: Run red test**

Run:

```powershell
mvn -q "-Dtest=PendingActionServiceImplTest" test
```

Expected: compile fails until `prepareMemory` and `AgentMemoryService` wiring exist.

Current result: red test failed at compilation because `PendingActionServiceImpl` did not accept `AgentMemoryService` and `prepareMemory` did not exist.

- [x] **Step 3: Implement pending action**

Add `prepareMemory(Long userId, AgentMemoryRequest request)` and support `INSTALL_AGENT_MEMORY` in `confirm`.

- [x] **Step 4: Run green test**

Run the same Maven test and verify it passes.

Current result: targeted Maven test passed.

### Task 2: Reflection Memory Candidate

**Files:**
- Modify: `backend/src/main/java/com/smartfinance/agent/service/impl/AgentReflectionServiceImpl.java`
- Modify: `backend/src/test/java/com/smartfinance/agent/service/impl/AgentReflectionServiceImplTest.java`

- [x] **Step 1: Write failing tests**

Cover preference intent creating `MEMORY_CANDIDATE` and accepting it creating a memory pending action.

- [x] **Step 2: Run red tests**

Run:

```powershell
mvn -q "-Dtest=AgentReflectionServiceImplTest" test
```

Expected: fail until reflection memory detection and accept conversion exist.

Current result: red test failed at compilation because `PendingActionService.prepareMemory` did not exist.

- [x] **Step 3: Implement conservative memory detection**

Detect explicit preference or remember intent such as `记住`, `以后回答`, `以后都`, `偏好`, `习惯`, and avoid direct memory writes.

- [x] **Step 4: Run green tests**

Run the same Maven test and verify it passes.

Current result: targeted Maven test passed.

### Task 3: Frontend Accept Button

**Files:**
- Modify: `frontend/src/views/ChatView.vue`

- [x] **Step 1: Update accept eligibility and label**

Allow `MEMORY_CANDIDATE` and show `生成待确认记忆`.

- [x] **Step 2: Run frontend build**

Run:

```powershell
npm run build
```

Expected: build succeeds with only existing Rolldown/chunk warnings.

Current result: `npm run build` passed with the existing Rolldown pure annotation, large chunk, and router dynamic import warnings.

### Task 4: Verification and Documentation

**Files:**
- Modify: `docs/hermes-agent-transformation-report.md`
- Modify: `docs/superpowers/plans/2026-07-04-confirmable-memory-candidate.md`

- [x] **Step 1: Document behavior**

Record that memory candidates are confirmable and never auto-applied by Curator.

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

- `mvn clean test`: `Tests run: 121, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.
- `npm run build`: build succeeded with existing warnings.
- `git diff --check`: no whitespace errors, only LF/CRLF warnings.
