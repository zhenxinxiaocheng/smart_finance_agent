# Curator / Reflection Design

## Context

The project already has the Hermes-like base needed for a safe curator loop:

- `ReActAgentService` produces a `traceId`, tool steps, final answer, and `analysis_record`.
- `AgentRunService` exposes run detail by `traceId`.
- `AgentMemoryService` stores user-editable memories and controlled auto memories.
- `AgentSkillService` installs custom Skills.
- `PendingActionService` is the confirmation boundary for writes, custom Skills, and scheduled tasks.

The next increment should not create a self-modifying agent. It should create a review layer that explains what could be improved and routes any lasting behavior change through confirmation.

## Recommended Approach

Use a three-stage curator loop:

1. **Collect run evidence**
   After a ReAct run finishes, use the `traceId`, query, final answer, steps, tool failures, and skill invocations as the evidence bundle.

2. **Generate bounded suggestions**
   Classify suggestions into fixed types:

   - `MEMORY_CANDIDATE`: a low-risk preference or recurring user instruction.
   - `SKILL_CANDIDATE`: a repeated workflow that might become a custom Skill.
   - `SCHEDULE_CANDIDATE`: a repeated monitoring or review task that might become a scheduled task.
   - `RISK_WARNING`: a non-mutating warning about tool failure, stale data, missing confirmation, or uncertain financial advice.

3. **Require confirmation for persistence**
   Curator suggestions are advisory by default. If a suggestion would change future behavior, the user must confirm it through existing or extended pending actions.

## Alternatives Considered

### Direct Auto-Apply

The curator could directly write memory or install Skills after successful runs.

Rejected for now. It is powerful but too risky for a financial assistant because it changes future behavior without a clear user checkpoint.

### Suggestions Only

The curator could only display suggestions without any confirm path.

Safe, but weak. It does not advance the Hermes-like loop where useful behavior can become reusable memory, Skills, or automation.

### Confirmable Suggestions

Recommended. The curator stores suggestions, displays them near run history or chat, and uses pending actions for any lasting changes.

## Data Model

Add an `agent_reflection` table:

- `id`
- `user_id`
- `trace_id`
- `suggestion_type`
- `title`
- `summary`
- `payload`
- `status`
- `created_at`
- `updated_at`
- `deleted`

Suggested statuses:

- `OPEN`: visible suggestion, no user decision yet.
- `ACCEPTED`: user accepted or converted it to a pending action.
- `DISMISSED`: user dismissed it.
- `EXPIRED`: suggestion is no longer relevant.

## Service Boundary

Add `AgentReflectionService`:

- `reflectRun(userId, traceId)`: inspect a completed run and create bounded suggestions.
- `list(userId, status)`: list suggestions.
- `accept(userId, reflectionId)`: convert a suggestion into an existing pending action when possible.
- `dismiss(userId, reflectionId)`: mark a suggestion as dismissed.

First implementation should be deterministic and conservative:

- Create `RISK_WARNING` when a run or tool step failed.
- Create `SKILL_CANDIDATE` when the user explicitly asks to repeat or standardize a workflow but no custom Skill was created.
- Create `SCHEDULE_CANDIDATE` when the user asks for recurring monitoring but no schedule pending action was created.
- Avoid automatic `MEMORY_CANDIDATE` beyond existing `MemoryExtractor` until the acceptance UI is clear.

## UI Boundary

Add a small reflection area to the Agent run detail or chat trace detail:

- Show suggestion type, title, summary, and status.
- Provide `接受` only when the suggestion can be safely converted into a pending action.
- Provide `忽略` for every suggestion.

Do not bury these inside existing run steps. Reflections are post-run review artifacts, not model observations.

## Testing

Use TDD around the backend service:

- Failing tool step creates `RISK_WARNING`.
- Explicit repeated workflow creates `SKILL_CANDIDATE`.
- Recurring monitoring intent creates `SCHEDULE_CANDIDATE`.
- Accepting a `SKILL_CANDIDATE` calls `PendingActionService.prepareCustomSkill`.
- Dismissing only changes reflection status.

Frontend verification should include `npm run build` after wiring UI.

## Non-Goals

- No autonomous memory writes from the curator in the first version.
- No direct Skill installation from the curator.
- No direct schedule creation from the curator.
- No LLM-only opaque decision without a stored evidence payload.
