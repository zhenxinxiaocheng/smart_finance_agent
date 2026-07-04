# Curator Reflection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a safe Curator / Reflection layer that reviews completed Agent runs and suggests memory, Skill, schedule, or risk follow-ups without directly changing future behavior.

**Architecture:** Store post-run suggestions in a new `agent_reflection` table. A backend `AgentReflectionService` creates conservative suggestions from existing run evidence and converts accepted suggestions into existing pending actions where safe. The frontend displays reflections near trace details and lets users accept or dismiss them.

**Tech Stack:** Spring Boot 3.2.5, MyBatis-Plus, H2/MySQL schema, Vue 3, Element Plus, existing pending-action APIs.

---

### Task 1: Reflection Entity and Schema

**Files:**
- Create: `backend/src/main/java/com/smartfinance/agent/entity/AgentReflection.java`
- Create: `backend/src/main/java/com/smartfinance/agent/mapper/AgentReflectionMapper.java`
- Modify: `backend/src/main/resources/schema.sql`
- Modify: `backend/src/test/resources/schema-h2.sql`

- [x] **Step 1: Write failing mapper/service compile test**

Create a test that imports `AgentReflection` and `AgentReflectionMapper`, then fails because they do not exist yet.

- [x] **Step 2: Run red test**

Run:

```powershell
mvn -q "-Dtest=AgentReflectionServiceImplTest" test
```

Expected: test compilation fails because reflection classes do not exist.

- [x] **Step 3: Add entity, mapper, and schema**

Create fields matching the design: `id`, `userId`, `traceId`, `suggestionType`, `title`, `summary`, `payload`, `status`, timestamps, and logical delete.

- [x] **Step 4: Run targeted test**

Run the same Maven test and verify compilation moves past missing classes.

### Task 2: Reflection Service

**Files:**
- Create: `backend/src/main/java/com/smartfinance/agent/service/AgentReflectionService.java`
- Create: `backend/src/main/java/com/smartfinance/agent/service/impl/AgentReflectionServiceImpl.java`
- Create: `backend/src/test/java/com/smartfinance/agent/service/impl/AgentReflectionServiceImplTest.java`

- [x] **Step 1: Write failing tests**

Cover:

- failed run evidence creates `RISK_WARNING`
- recurring monitoring intent creates `SCHEDULE_CANDIDATE`
- repeated workflow intent creates `SKILL_CANDIDATE`
- dismiss updates status to `DISMISSED`

- [x] **Step 2: Run red tests**

Run:

```powershell
mvn -q "-Dtest=AgentReflectionServiceImplTest" test
```

Expected: failures because service methods are missing or return no reflections.

- [x] **Step 3: Implement conservative deterministic rules**

Use existing `AgentRunService.detail(userId, traceId)` evidence. Do not call the LLM in the first pass.

- [x] **Step 4: Run green tests**

Run the same Maven test and verify it passes.

### Task 3: Accept and Dismiss API

**Files:**
- Create: `backend/src/main/java/com/smartfinance/agent/controller/AgentReflectionController.java`
- Create: `backend/src/test/java/com/smartfinance/agent/controller/AgentReflectionControllerTest.java`

- [x] **Step 1: Write controller tests**

Cover list, accept, and dismiss endpoints.

- [x] **Step 2: Implement controller**

Add routes under `/api/agent-reflections`.

- [x] **Step 3: Run targeted controller tests**

Run:

```powershell
mvn -q "-Dtest=AgentReflectionControllerTest,AgentReflectionServiceImplTest" test
```

Expected: pass.

Current result: targeted tests passed with `AgentReflectionControllerTest`, `AgentReflectionServiceImplTest`, and `ChatServiceImplTest`.

Current implementation boundary: `accept` safely converts `SKILL_CANDIDATE` into an existing `INSTALL_CUSTOM_SKILL` pending action and marks the reflection `ACCEPTED`. `RISK_WARNING` and `SCHEDULE_CANDIDATE` remain advisory until a safer conversion rule is implemented.

Run completion integration: `ChatServiceImpl` invokes `AgentReflectionService.reflectRun` after `agentRunService.completeRun`, so the trace drawer can display generated suggestions for completed runs.

### Task 4: Frontend Reflection Display

**Files:**
- Create: `frontend/src/api/agentReflections.js`
- Modify: `frontend/src/views/ChatView.vue`

- [x] **Step 1: Add API wrapper**

Expose list, accept, and dismiss calls.

- [x] **Step 2: Display reflections in trace detail**

Show title, summary, status, accept button when applicable, and dismiss button.

- [x] **Step 3: Run frontend build**

Run:

```powershell
npm run build
```

Expected: build succeeds with only existing Rolldown/chunk warnings.

Current result: `npm run build` passed with the existing Rolldown pure annotation, large chunk, and router dynamic import warnings.

### Task 5: Verification and Documentation

**Files:**
- Modify: `README.md`
- Modify: `docs/hermes-agent-transformation-report.md`

- [x] **Step 1: Document Curator / Reflection**

Explain that suggestions are advisory and persistent behavior changes still require pending actions.

- [x] **Step 2: Run backend and frontend verification**

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

- `mvn clean test`: `Tests run: 118, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.
- `npm run build`: build succeeded with the existing Rolldown pure annotation, large chunk, and router dynamic import warnings.
- `git diff --check`: no whitespace errors, only LF/CRLF warnings.
