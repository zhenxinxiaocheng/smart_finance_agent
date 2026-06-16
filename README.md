# 智财 Agent

个人智能财务代理系统，当前分支面向《软件过程与项目管理》课程，重点展示一个具备清晰模块划分、可测试服务接口、用户确认闭环和可部署前后端结构的财务管理项目。

## 分支说明

- 当前分支：`codex/software-process`
- 课程目标：突出需求分析、模块设计、接口协作、测试验证和项目交付过程。
- 当前账单识别方案：后端直接调用 DashScope 多模态大模型识别账单截图，生成候选交易；用户确认后才写入正式交易记录。

## 主要功能

- 用户注册、登录与 JWT 鉴权。
- 收入、支出记录管理。
- 消费分类、条件筛选和分页浏览。
- 仪表盘统计：收入、支出、余额、储蓄率、分类占比和趋势图。
- AI 财务助手：基于 LangChain4j 与 DashScope 进行自然语言财务问答。
- RAG 理财知识库：支持常见理财知识检索增强回答。
- 联网搜索：可获取实时财经资讯。
- 账单截图导入：
  - 上传微信、支付宝或银行卡流水截图。
  - 多模态模型判断账单类型并抽取候选交易。
  - 系统保存识别记录和候选交易。
  - 用户检查、编辑并确认后，候选交易才进入正式交易表。

## 技术栈

| 模块 | 技术 |
| --- | --- |
| 后端 | Java 17, Spring Boot 3.2.5, MyBatis-Plus, MySQL 8, JWT |
| AI 能力 | LangChain4j, DashScope, RAG, Tavily Search |
| 前端 | Vue 3, Vite, Element Plus, Pinia, Vue Router, Axios, ECharts |
| 测试 | JUnit 5, Mockito, Spring Boot Test, H2 |

## 项目结构

```text
smart_finance_agent/
├── backend/                 # Spring Boot 后端
│   ├── src/main/java/       # 控制器、服务、实体、DTO、Mapper、AI 配置
│   ├── src/main/resources/  # application.yml、schema.sql、data.sql、知识库
│   └── src/test/            # 单元测试与 H2 测试资源
├── frontend/                # Vue 3 前端
│   └── src/                 # 页面、路由、状态管理、API 封装
├── docs/                    # 文档与课程交付资料
└── README.md                # 当前分支说明
```

## 账单导入流程

1. 用户在前端上传账单截图。
2. 前端调用后端 `/api/bills/import`。
3. 后端保存原始图片，并由 `BillAiClient` 调用 DashScope 多模态接口。
4. 多模态模型返回账单类型、置信度、文本摘要、候选交易和提示信息。
5. 后端保存识别记录；当置信度达标且存在候选交易时，保存候选交易。
6. 前端展示候选交易，用户可修改金额、分类、日期、描述和是否导入。
7. 用户调用 `/api/bills/{id}/confirm` 确认后，后端写入正式交易记录。

## 本地运行

### 1. 环境要求

- JDK 17+
- Maven 3.8+
- MySQL 8.0+
- Node.js 18+
- npm 9+

### 2. 初始化数据库

```sql
CREATE DATABASE IF NOT EXISTS smart_finance
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
```

### 3. 配置本地密钥

复制配置模板：

```powershell
copy env.example backend\src\main\resources\application-local.yml
```

填写本地配置：

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
  ai:
    multimodal-model: qwen3.5-omni-plus-2026-03-15
```

### 4. 启动后端

```powershell
cd backend
mvn spring-boot:run
```

默认地址：`http://localhost:8080`。

### 5. 启动前端

```powershell
cd frontend
npm install
npm run dev
```

默认地址：`http://localhost:3000`。

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

代码搜索检查：

```powershell
rg -n "bill.ai.endpoint|bill.ai.multimodal-model" backend/src/main frontend/src README.md
```

## 配置项

| 配置项 | 说明 | 默认值 |
| --- | --- | --- |
| `server.port` | 后端端口 | `8080` |
| `spring.datasource.url` | MySQL 连接地址 | `jdbc:mysql://localhost:3306/smart_finance...` |
| `langchain4j.dashscope.api-key` | DashScope API Key | 本地配置提供 |
| `langchain4j.dashscope.chat-model.model-name` | 财务助手文本模型 | `qwen3.6-flash` |
| `bill.upload-dir` | 账单截图保存目录 | `uploads/bills` |
| `bill.ai.endpoint` | 多模态兼容接口地址 | DashScope chat completions |
| `bill.ai.multimodal-model` | 账单识别多模态模型 | `qwen3.5-omni-plus-2026-03-15` |

## 交付关注点

- 模块职责清晰：前端展示、后端业务、AI 调用、数据持久化相互隔离。
- 接口稳定：账单导入和确认接口保持不变，便于迭代内部实现。
- 风险控制：识别结果只生成候选交易，用户确认后才入库。
- 可测试：账单导入服务和多模态客户端均有后端测试覆盖。
