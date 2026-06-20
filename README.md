# 智能财务 Agent

一个面向个人记账和财务分析的前后端分离系统。项目提供收支记录、分类管理、统计图表、预算提醒、账单截图导入和 AI 财务助手能力，后端通过 Spring Boot 提供 API，前端通过 Vue 3 提供交互界面。

## 功能概览

- 用户注册、登录、JWT 鉴权和当前用户信息获取
- 收入、支出记录的新增、编辑、删除、查询和分类汇总
- 消费分类管理，支持默认分类和自定义分类
- 日、月、年维度统计，展示收支趋势、结余和分类占比
- 财务档案配置，用于描述收入、预算、储蓄目标和风险偏好
- 预算管理和预算预警
- AI 财务助手，支持普通对话和 ReAct 工具调用流程
- RAG 财务知识增强和 Tavily 联网搜索
- 账单截图导入，支持上传图片、AI 识别候选交易、人工确认后入库

## 技术栈

| 模块 | 技术 |
| --- | --- |
| 后端 | Java 17, Spring Boot 3.2.5, MyBatis-Plus, MySQL, JWT |
| AI | LangChain4j, DashScope, RAG, Tavily Search |
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

## 主要页面

- `/login`：登录
- `/register`：注册
- `/statistics`：收支统计
- `/transactions`：交易记录
- `/profile`：财务档案
- `/bill-import`：账单导入
- `/chat`：AI 财务助手

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
| 待确认动作 | `/api/pending-actions` | AI 生成动作的确认或取消 |

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
| `search.api-key` | Tavily Search API Key | 可为空 |
| `bill.upload-dir` | 账单截图上传目录 | `uploads/bills` |
| `bill.ai.multimodal-model` | 账单识别多模态模型 | `qwen3.5-omni-plus-2026-03-15` |

## 开发备注

- 当前后端依赖本地 MySQL，启动前请确认数据库服务已运行。
- `application-local.yml` 用于保存本地密码和 API Key，不应提交到 Git。
- AI 识别结果只作为候选数据，必须经过用户确认才会写入正式交易表。
- 前端通过 Vite 代理把 `/api` 请求转发到 `http://localhost:8080`。
