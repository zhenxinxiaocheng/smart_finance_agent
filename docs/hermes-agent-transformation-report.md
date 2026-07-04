# 类 Hermes Agent 改造报告

## 1. 改造目标

本项目原本是面向个人记账、预算和财务分析的智能财务助手。本轮改造不把它推倒重写成通用 Agent 平台，而是在保留财务产品定位的前提下，逐步补齐类 Hermes Agent 的核心能力：

- 长期记忆：沉淀用户偏好、低风险行为习惯和 Agent 使用偏好。
- Skills：把稳定工作流沉淀为可启停、可审计、可确认的能力。
- 工具网关：所有真实执行仍由后端注册工具完成，模型只做选择和参数组织。
- 待确认动作：涉及写入数据、安装能力或改变未来行为的动作必须由用户确认。
- 运行轨迹：每次 ReAct 执行都能通过 traceId 追踪步骤、工具调用和结果。
- 周期自动化：稳定财务任务可以按计划自动运行。
- 后续 curator：基于运行结果提出记忆、Skill 或自动化改进建议，但不越权直接修改系统。

## 2. 当前基线

项目已经具备一组可继续演进的 Hermes-like 基础：

- `ReActAgentService`：负责模型决策、工具调用、Observation 汇总和最终回答。
- `ToolRegistry`：集中注册安全工具，并记录 Skill 调用审计。
- `AgentMemoryService` 与 `MemoryExtractor`：支持长期指令和自动记忆沉淀。
- `AgentSkillService` 与 `CustomSkillTool`：支持内置 Skill、外部 Skill、对话生成自定义 Skill 草稿。
- `PendingActionService`：为记账、预算设置、自定义 Skill 安装和周期任务创建提供确认边界。
- `AgentRunService`：记录 ReAct run 和 step，前端可按 traceId 回放。

因此推荐路线是增量改造：继续复用现有 ReAct、Memory、Skills、pending action 和 trace，而不是另起一套执行框架。

## 3. 已完成改造

### 3.1 周期任务自动化

新增后端周期任务能力，让系统从“用户主动问答”扩展到“Agent 可按计划主动执行财务任务”。

新增模块：

- `AgentSchedule`
- `AgentScheduleMapper`
- `AgentScheduleService`
- `AgentScheduleServiceImpl`
- `AgentScheduleRunner`
- `AgentScheduleController`
- `AgentScheduleRequest`

新增 `agent_schedule` 表，核心字段包括：

- `user_id`
- `trace_id`
- `name`
- `description`
- `cron_expression`
- `timezone`
- `task_query`
- `enabled`
- `last_run_at`
- `next_run_at`
- `run_count`
- `consecutive_failures`
- `last_status`
- `last_answer`

执行链路：

```text
agent_schedule.next_run_at 到期
  -> AgentScheduleRunner 扫描 due schedules
  -> 调用 ReActAgentService.run(userId, taskQuery)
  -> 写回 traceId / lastStatus / lastAnswer / runCount / nextRunAt
```

### 3.2 对话创建周期任务

新增 `create_agent_schedule` 工具，用户在聊天中表达“每周复盘预算”“每天提醒我看异常支出”“每月自动帮我生成财务摘要”这类需求时，ReAct 可以生成一个待确认周期任务。

当前链路：

```text
用户提出周期执行需求
  -> ReAct 决策调用 create_agent_schedule
  -> AgentScheduleTool 生成 CREATE_AGENT_SCHEDULE pending action
  -> 用户确认
  -> PendingActionService 调用 AgentScheduleService.create(...)
  -> 周期任务正式启用
```

关键约束：

- `create_agent_schedule` 风险等级是 `REQUIRES_CONFIRMATION`。
- Agent 只起草任务，不直接创建长期自动化。
- 一次性偏好不能自动升级为周期任务。
- 周期任务创建需要 `name`、`description`、`cronExpression`、`taskQuery`、`timezone`。

### 3.3 Trace 串联

补齐 `analysis_record.trace_id`，让分析记录能够和 Agent run/step 轨迹串起来。周期任务执行后也会把本次 ReAct 的 traceId 回写到 `agent_schedule.trace_id`。

### 3.4 前端周期任务管理

新增 `/schedules` 周期任务管理页，支持查看任务总览、搜索筛选、手动创建、启停、删除、查看最近结果和跳转 traceId。手动创建时提供每天、每周、每月 cron 模板和人类可读执行说明，高级用户仍可切换到自定义 Spring cron。聊天页的 pending action 卡片也已针对 `CREATE_AGENT_SCHEDULE` 展示 cron、时区和执行内容。

### 3.5 周期任务运行历史

新增 `agent_schedule_run` 表和 `/api/agent-schedules/{id}/runs` 接口。每次后台周期任务执行都会记录 scheduleId、userId、traceId、状态、答案、错误信息、开始/结束时间和耗时。前端周期任务详情页可查看最近运行历史，并通过 traceId 跳转到 Agent 运行详情。

### 3.6 周期任务执行锁

新增 `agent_schedule.lock_until` 执行锁字段。后台扫描到到期任务后，会先通过带条件的数据库更新抢占锁，只有抢锁成功的进程才会执行 ReAct；执行结束后清空锁并写入运行历史。锁有过期时间，避免进程异常退出后任务永久卡住。

### 3.7 失败熔断

新增 `agent_schedule.consecutive_failures` 连续失败计数字段。周期任务执行成功后会清零连续失败次数；执行失败后会记录错误摘要并累加失败次数。连续失败达到 3 次时，任务会自动停用并清空 `next_run_at`，避免模型、网络或外部服务持续异常时无限重复执行。用户重新启用任务后，会清零失败次数并重新计算下一次执行时间。

## 4. 架构取舍

### 4.1 为什么拆分 `AgentScheduleService` 和 `AgentScheduleRunner`

如果 `AgentScheduleServiceImpl` 同时负责创建周期任务和执行到期任务，它就需要依赖 `ReActAgentService`。但对话创建周期任务的链路是：

```text
ToolRegistry -> AgentScheduleTool -> PendingActionService -> AgentScheduleService
```

而 `ReActAgentService` 本身又依赖 `ToolRegistry`，这会让 Spring Bean 依赖关系变得容易成环。

因此当前拆分为：

- `AgentScheduleService`：只负责定义管理。
- `AgentScheduleRunner`：只负责后台扫描和执行。

这个拆分让对话工具可以安全创建 pending action，同时不会把后台执行器绕回工具网关。

### 4.2 为什么周期任务也要 pending action

周期任务不是一次性查询，它会持续触发 Agent 行为。即使 `taskQuery` 是只读分析，也会改变用户系统状态和未来行为，因此必须由用户确认。

这和自定义 Skill 安装保持一致：Agent 可以起草，用户决定是否生效。

## 5. 验证状态

第一阶段完成后曾通过后端全量测试：

```powershell
cd backend
mvn clean test
```

结果：

```text
Tests run: 103, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

第二阶段当前已通过目标测试：

```powershell
mvn clean -q "-Dtest=AgentScheduleServiceImplTest,AgentScheduleRunnerTest,PendingActionServiceImplTest,ToolRegistryTest,ReActAgentServiceTest#run_shouldPersistTraceIdOnAnalysisRecord" test
```

目标测试覆盖：

- 周期任务创建和下一次执行时间计算。
- 到期任务通过 `AgentScheduleRunner` 调用 ReAct 并回写结果。
- `CREATE_AGENT_SCHEDULE` pending action 的准备和确认。
- `create_agent_schedule` 工具注册、调用审计和确认风险等级。
- `analysis_record.trace_id` 持久化。

执行锁、运行历史和失败熔断改动后已重新运行后端全量测试：

```powershell
mvn clean test
```

结果：

```text
Tests run: 109, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

前端周期任务页改动后已重新运行生产构建：

```powershell
npm run build
```

结果：构建成功，仅保留 Vite/Rolldown 对 `@vueuse/core` pure annotation 和大 chunk 的既有警告。

cron 模板辅助生成逻辑已增加前端纯函数测试：

```powershell
node test/agentScheduleCron.test.mjs
```

覆盖每天、每周、每月模板和非法时间格式。

Curator / Reflection 前端接入后已重新运行：

```powershell
npm run build
```

结果：构建成功，仍仅有 Rolldown 对 `@vueuse/core` pure annotation、router 动态导入和大 chunk 的既有警告。

Curator / Reflection 后端接入后已补跑目标测试：

```powershell
mvn -q "-Dtest=AgentReflectionControllerTest,AgentReflectionServiceImplTest,ChatServiceImplTest" test
```

结果：测试通过，退出码 0。

Curator / Reflection 接入后已重新运行后端全量测试：

```powershell
mvn clean test
```

结果：

```text
Tests run: 118, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## 6. 当前风险

- 周期任务仍要求后端接收 Spring cron 格式；前端已提供模板辅助，但尚未提供自然语言转 cron 的安全校验层。
- 周期任务已具备连续失败熔断和固定退避重试，尚未提供用户可配置重试间隔、指数退避或失败通知。
- 前端已具备基础管理页、cron 模板辅助和运行历史列表，但还没有对复杂 cron 的可视化解释器。
- Curator / Reflection 当前只对 `SKILL_CANDIDATE` 提供安全 accept 转换；周期任务候选仍需要更可靠的 cron 提取或用户编辑流程。

## 7. 后续路线

### 阶段 3：前端管理与确认体验

- 已完成周期任务列表、启停、删除和手动创建入口。
- 已完成每天、每周、每月 cron 模板辅助和可读执行说明。
- 已在 pending action 卡片中展示 cron、时区和执行内容。
- 已完成周期任务运行历史展示和 traceId 跳转。
- 后续可继续增强复杂 cron 的可视化解释和确认影响说明。

### 阶段 4：Curator / Reflection

- 已补充 Curator / Reflection 设计文档和实现计划，明确第一版只做建议与确认，不做自我修改。
- 已新增 `agent_reflection` 表、`AgentReflectionService` 和确定性反思规则的后端最小闭环。
- 已在 `ChatServiceImpl` 的运行完成链路中调用反思服务，使完成后的 trace 可以生成反思建议。
- 已新增 `/api/agent-reflections`，支持建议列表、dismiss，以及把安全的 Skill 候选转成待确认安装动作。
- 已在运行详情抽屉中展示当前 traceId 的反思建议，并支持采纳 Skill、记忆和明确频率的周期任务候选，或忽略建议。
- 已新增确认式 `MEMORY_CANDIDATE`：Curator 只生成长期记忆候选，采纳后创建 `INSTALL_AGENT_MEMORY` pending action，用户确认后才写入 `AgentMemoryService`。
- 已支持 `SCHEDULE_CANDIDATE` 的保守采纳：仅每天、每周、每月明确频率会生成 `CREATE_AGENT_SCHEDULE` pending action；模糊“定期”不自动推断 cron。
- 已增加反思生成幂等保护：同一用户、同一 traceId、同一建议类型不会重复插入建议，避免运行完成回调或重试造成建议堆积。
- 已新增 `/reflections` 反思收件箱页面，并让 `/api/agent-reflections` 支持按状态和建议类型过滤，方便集中处理记忆、Skill、周期任务和风险建议。
- 已支持 `SCHEDULE_CANDIDATE` 的人工校准采纳：收件箱会先打开可编辑任务草稿，用户调整 cron、时区和执行内容后再生成 `CREATE_AGENT_SCHEDULE` pending action。
- 已新增 `/pending-actions` 待确认动作收件箱，集中展示 Agent 起草的交易、预算、记忆、Skill 和周期任务动作，并支持筛选、查看 payload、确认和取消。
- 在 ReAct run 结束后生成低风险反思结果。
- 把建议分为记忆候选、Skill 改进候选、周期任务候选和风险警告。
- 所有会改变未来行为的建议继续走 pending action。

### 阶段 5：运行治理

- 已增加周期任务执行锁。
- 已增加连续失败次数阈值和自动停用熔断。
- 已增加固定退避重试：第一次失败 15 分钟后重试，第二次失败 60 分钟后重试，第三次失败进入熔断停用。
- 已接入周期任务失败反思：后台自动化失败会生成 `RISK_WARNING`，带有 scheduleId、任务内容、错误信息和连续失败次数，并进入反思收件箱。
- 后续可增加用户可配置重试间隔、指数退避和失败通知。
- 已增加任务运行历史列表。
- 已支持按 traceId 查看周期任务生成的 Agent run 详情。

## 8. 结论

项目已经从“财务问答助手”推进到“具备长期记忆、Skills、运行轨迹、反思收件箱、待确认动作收件箱和带失败治理的周期自动化雏形的财务 Agent”。下一步最值得优先做的是运行治理增强，例如周期任务失败通知、用户可配置重试策略和更细的执行风险提示。
