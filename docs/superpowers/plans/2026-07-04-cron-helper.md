# Cron Helper Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a safe cron helper to the Agent schedule creation dialog so users can choose daily, weekly, or monthly templates without hand-writing Spring cron.

**Architecture:** Put cron generation and human-readable schedule text in a pure frontend helper so it can be tested with Node. Keep the backend as the final authority by continuing to send the generated `cronExpression` through the existing create API and Spring `CronExpression.parse(...)` validation.

**Tech Stack:** Vue 3, Element Plus, Vite, Node `assert` tests.

---

### Task 1: Cron Helper Pure Function

**Files:**
- Create: `frontend/test/agentScheduleCron.test.mjs`
- Create: `frontend/src/utils/agentScheduleCron.js`

- [x] **Step 1: Write the failing test**

Create `frontend/test/agentScheduleCron.test.mjs` with assertions for daily, weekly, and monthly templates.

- [x] **Step 2: Run red test**

Run: `node test/agentScheduleCron.test.mjs`

Expected: fail because `frontend/src/utils/agentScheduleCron.js` does not exist.

- [x] **Step 3: Implement helper**

Create `frontend/src/utils/agentScheduleCron.js` with `buildCronExpression`, `describeCronTemplate`, and option constants.

- [x] **Step 4: Run green test**

Run: `node test/agentScheduleCron.test.mjs`

Expected: pass.

### Task 2: Vue Dialog Integration

**Files:**
- Modify: `frontend/src/views/AgentSchedules.vue`

- [x] **Step 1: Add template fields**

Add frequency, time, weekday, and month day controls before the cron input.

- [x] **Step 2: Wire helper into form state**

Import the helper and update `form.cronExpression` whenever the template changes, while keeping a custom cron option.

- [x] **Step 3: Show readable confirmation text**

Display a compact explanation such as `每周一 09:00 执行`.

### Task 3: Documentation and Verification

**Files:**
- Modify: `README.md`
- Modify: `docs/hermes-agent-transformation-report.md`

- [x] **Step 1: Document cron helper**

Mention safe daily, weekly, and monthly templates.

- [x] **Step 2: Verify**

Run:

```powershell
node test/agentScheduleCron.test.mjs
npm run build
git diff --check
```
