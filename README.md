# 智财 Agent

一个面向个人记账、财务分析和 Agent 化实验的前后端分离项目。系统包含收支记录、预算画像、统计图表、账单截图导入、RAG 财务知识库、联网搜索、ReAct 工具调用、长期记忆和可管理 Skills。

当前开发方向是把传统“财务问答助手”逐步改造成更接近 Hermes/Harness Agent 的形态：有长期上下文、有可审计工具、有可启停 Skill、有用户确认边界，并允许用户通过对话把稳定行为流程包装成自定义 Skill。

## 功能概览

- 用户注册、登录、JWT 鉴权和当前用户信息获取
- 收入、支出记录的新增、编辑、删除、查询和分类汇总
- 消费分类管理，支持默认分类和自定义分类
- 日、月、年维度统计，展示收支趋势、结余和分类占比
- 财务档案配置，用于描述收入、预算、储蓄目标和风险偏好
- 预算管理和预算预警
- AI 财务助手，支持普通对话、SSE 流式输出和 ReAct 工具调用流程
- Agent 长期记忆，支持用户直接编辑长期指令、自动沉淀低风险偏好、禁用和重置
- Agent Skills，支持内置工具 Skill 化、外部说明型 Skill 安装、启用、禁用、删除和调用审计
- 对话创建自定义 Skill，用户描述一种稳定行为后，Agent 生成待确认 Skill，确认后进入可用能力列表
- Agent 运行轨迹，记录工具步骤、失败原因、调用结果和 traceId
- RAG 财务知识增强和 Tavily 联网搜索
- 账单截图导入，支持上传图片、AI 识别候选交易、人工确认后入库
- 待确认动作机制，记账、预算设置、自定义 Skill 安装等关键写操作需要用户确认

## 技术栈

| 模块 | 技术 |
| --- | --- |
| 后端 | Java 17, Spring Boot 3.2.5, MyBatis-Plus, MySQL, JWT |
| AI | LangChain4j, DashScope Chat/Embedding, RAG, Tavily Search |
| 前端 | Vue 3, Vite, Element Plus, Pinia, Vue Router, Axios, ECharts |
| 测试 | JUnit 5, Mockito, Spring Boot Test, H2 |

## 项目结构

```text
smart_finance_agent/
├── backend/                 # Spring Boot 后端
│   ├── src/main/java/       # Controller、Service、Entity、DTO、Mapper、AI Agent
│   ├── src/main/resources/  # application.yml、schema.sql、data.sql
│   └── src/test/            # 后端单元测试和集成测试
├── frontend/                # Vue 3 前端
│   ├── src/api/             # API 请求封装
│   ├── src/components/      # 通用组件
│   ├── src/router/          # 页面路由
│   └── src/views/           # 页面视图
├── env.example              # 本地配置示例
└── README.md
```

## 本地运行

### 环境要求

- JDK 17+
- Maven 3.8+
- MySQL 8.0+
- Node.js 18+
- npm 9+

### 初始化数据库

```sql
CREATE DATABASE IF NOT EXISTS smart_finance
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
```

后端启动时会读取 `backend/src/main/resources/schema.sql` 和 `data.sql` 初始化表结构和基础数据。

### 配置本地密钥

创建文件：

```text
backend/src/main/resources/application-local.yml
```

写入本地配置：

```yaml
spring:
  datasource:
    password: 你的数据库密码

jwt:
  secret: 你的 JWT 密钥

langchain4j:
  dashscope:
    api-key: 你的 DashScope API Key

search:
  api-key: 你的 Tavily API Key

bill:
  upload-dir: uploads/bills
  ai:
    endpoint: https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
    multimodal-model: qwen3.5-omni-plus-2026-03-15
```

其中 Tavily 配置可选；如果不需要联网搜索，可以留空。

### 启动后端

```powershell
cd backend
mvn spring-boot:run
```

默认地址：

```text
http://localhost:8080
```

### 启动前端

```powershell
cd frontend
npm install
npm run dev
```

前端默认端口在 `frontend/vite.config.js` 中配置为 `3000`：

```text
http://localhost:3000
```

如果端口被占用，Vite 会自动切换到下一个可用端口。

### 快速启动脚本

项目根目录提供了开发启动脚本：

```powershell
.\start-dev.ps1
```

脚本会启动后端和前端，并把日志写入 `.run-logs/`。如果需要手动启动，仍推荐分别执行 `mvn spring-boot:run` 和 `npm run dev`，便于直接观察日志。

## 主要页面

- `/login`：登录
- `/register`：注册
- `/statistics`：收支统计
- `/transactions`：交易记录
- `/profile`：财务档案
- `/bill-import`：账单导入
- `/chat`：AI 财务助手
- `/skills`：Agent 技能管理

## 主要接口

| 模块 | 接口前缀 | 说明 |
| --- | --- | --- |
| 认证 | `/api/auth` | 注册、登录、当前用户 |
| 交易 | `/api/transactions` | 收支记录和分类汇总 |
| 分类 | `/api/categories` | 消费分类管理 |
| 统计/提醒 | `/api/alerts` | 预算提醒读取和已读标记 |
| 预算 | `/api/budgets` | 预算查询、保存、删除 |
| 财务档案 | `/api/financial-profile` | 财务资料读取和保存 |
| 账单导入 | `/api/bills` | 截图上传、识别结果查询、确认入库 |
| AI 助手 | `/api/chat` | 普通对话、ReAct 流式对话、历史记录 |
| Agent 运行 | `/api/agent-runs` | 按 traceId 查询 Agent 运行步骤 |
| Agent 记忆 | `/api/agent-memories` | 长期指令、自动记忆、记忆开关和重置 |
| Agent Skills | `/api/agent-skills` | Skill 列表、安装、启停、删除、调用历史 |
| 待确认动作 | `/api/pending-actions` | AI 生成动作的确认或取消 |

## Agent 能力设计

### ReAct 执行流

聊天页的流式接口为 `POST /api/chat/react/stream`。Agent 会按“思考 -> 工具调用 -> 观察结果 -> 最终回答”的方式运行，前端会展示运行步骤和失败原因。实际执行的工具仍由后端 `ToolRegistry` 控制，模型只能选择已注册工具。

### 长期记忆

长期记忆分两类：

- 用户直接编辑的长期指令，例如“用中文回答”“回复简短”“股票问题先说明风险”。
- 自动沉淀的低风险偏好，例如分类习惯、回答风格、分析偏好。

资产、负债、收入、银行卡、密码、API Key 等敏感信息不会自动沉淀到 Agent 记忆中，仍由用户在财务画像中手动维护。

### Skills

Skills 是 Agent 可读取的能力说明，不直接执行第三方代码。当前支持：

- 内置 Skill：由后端安全工具自动生成，例如搜索、查询收支、预算分析、分类建议。
- 外部说明型 Skill：通过统一来源接口安装，当前实现 GitHub 来源，后续可扩展本地 ZIP、市场、URL、私有仓库。
- 自定义 Skill：用户在聊天中描述行为流程，Agent 生成待确认草稿，用户确认后写入 `agent_skill`。

Skill 可以绑定现有安全工具。禁用后的 Skill 不会进入 prompt，也不能触发绑定工具。

### 待确认动作

所有会改变用户数据的重要动作都必须走 pending action：

- 记录交易
- 设置预算
- 安装对话生成的自定义 Skill

Agent 只能生成待确认动作，最终是否执行由用户确认。

## 对话创建 Skill 示例

在聊天页输入：

```text
以后分析股票前必须先联网搜索，再给出偏看好、偏谨慎或可观察的倾向，并说明风险。把这个流程做成 Skill。
```

预期流程：

1. Agent 调用 `create_custom_skill`。
2. 系统生成 `INSTALL_CUSTOM_SKILL` 待确认动作。
3. 用户点击确认后，Skill 写入 `agent_skill`，来源为 `CUSTOM`。
4. 下一轮相关问题中，该 Skill 会作为启用 Skill 注入 Agent 上下文。

## 账单导入流程

1. 用户在前端上传微信、支付宝或银行卡流水截图。
2. 前端调用 `POST /api/bills/import`。
3. 后端保存原始图片，并通过 `BillAiClient` 调用 DashScope 多模态模型。
4. 模型返回账单类型、置信度、摘要、候选交易和提示信息。
5. 后端保存识别记录和候选交易。
6. 用户在前端检查并修改金额、分类、日期、描述和是否导入。
7. 用户调用 `POST /api/bills/{id}/confirm` 后，系统才写入正式交易记录。

## 测试与构建

后端测试：

```powershell
cd backend
mvn test
```

前端构建：

```powershell
cd frontend
npm run build
```

## 常用配置

| 配置项 | 说明 | 默认值 |
| --- | --- | --- |
| `server.port` | 后端端口 | `8080` |
| `spring.datasource.url` | MySQL 连接地址 | `jdbc:mysql://localhost:3306/smart_finance...` |
| `spring.datasource.username` | MySQL 用户名 | `root` |
| `spring.datasource.password` | MySQL 密码 | 从 `application-local.yml` 读取 |
| `jwt.secret` | JWT 签名密钥 | 从 `application-local.yml` 读取 |
| `langchain4j.dashscope.api-key` | DashScope API Key | 从 `application-local.yml` 或环境变量读取 |
| `langchain4j.dashscope.chat-model.model-name` | 文本对话模型 | `qwen3.6-flash` |
| `langchain4j.dashscope.embedding-model.model-name` | 向量模型 | `text-embedding-v4` |
| `search.api-key` | Tavily Search API Key | 可为空 |
| `bill.upload-dir` | 账单截图上传目录 | `uploads/bills` |
| `bill.ai.multimodal-model` | 账单识别多模态模型 | `qwen3.5-omni-plus-2026-03-15` |

## 开发备注

- 当前后端依赖本地 MySQL，启动前请确认数据库服务已运行。
- `application-local.yml` 用于保存本地密码和 API Key，不应提交到 Git。
- AI 识别结果只作为候选数据，必须经过用户确认才会写入正式交易表。
- 股票、基金、行业问题允许给倾向性建议，但不能承诺收益；涉及实时行情、新闻、政策、汇率的问题必须先联网搜索。
- 外部 Skill 当前只读取说明和元数据，不执行第三方脚本；如果以后支持脚本型 Skill，需要单独设计沙箱、权限、超时和审计。
- 前端通过 Vite 代理把 `/api` 请求转发到 `http://localhost:8080`。
