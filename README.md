

<p align="center">
  <img src="https://trae-api-cn.mchost.guru/api/ide/v1/text_to_image?prompt=A modern fintech logo with a minimalist blue shield and a glowing cyan dollar sign inside, dark blue background, tech style, clean lines, professional financial feel&image_size=square_hd" width="120" alt="logo" />
</p>

<h1 align="center">智财Agent</h1>
<p align="center"><strong>个人智能财务代理系统</strong></p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-17-blue?style=flat-square&logo=openjdk" />
  <img src="https://img.shields.io/badge/Spring_Boot-3.2.5-brightgreen?style=flat-square&logo=springboot" />
  <img src="https://img.shields.io/badge/Vue_3-8.0-4fc08d?style=flat-square&logo=vuedotjs" />
  <img src="https://img.shields.io/badge/AI-Agent-f472b6?style=flat-square&logo=openai" />
  <img src="https://img.shields.io/badge/MySQL-8.0-4479a1?style=flat-square&logo=mysql" />
  <img src="https://img.shields.io/badge/license-MIT-yellow?style=flat-square" />
</p>

---

## 简介

**智财Agent** 是一款基于 **AI Agent** 技术的个人智能财务管理系统。它不仅能帮你记录和管理日常收支，还能像专业理财顾问一样，通过自然语言对话分析你的财务状况，提供个性化的理财建议。

### 核心能力

- **智能记账** — 记录和管理收支明细，多维度筛选和统计
- **AI 财务顾问** — 通过自然语言对话了解财务状况，获得专业理财建议
- **消费洞察** — 支出分类分析、月度趋势、储蓄率评估
- **联网搜索** — 实时获取最新财经资讯和市场动态
- **RAG 知识库** — 内置理财专业知识库（紧急备用金、资产配置、基金定投、保险规划等）

## 技术栈

### 后端

| 技术 | 用途 |
|------|------|
| Java 17 | 开发语言 |
| Spring Boot 3.2.5 | 应用框架 |
| MyBatis-Plus | ORM 持久层 |
| MySQL 8.0 | 数据库 |
| LangChain4j 0.35.0 | AI Agent 框架 |
| DashScope (通义千问) | 大语言模型 + Embedding |
| Tavily API | 联网搜索 |
| JWT | 身份认证 |

### 前端

| 技术 | 用途 |
|------|------|
| Vue 3 (Composition API) | 前端框架 |
| Vite | 构建工具 |
| Element Plus | UI 组件库 |
| ECharts + vue-echarts | 数据可视化 |
| Pinia | 状态管理 |
| Vue Router | 路由管理 |
| Axios | HTTP 请求 |
| marked | Markdown 渲染 |

## 项目结构

```
smart_finance_agent/
├── backend/                          # 后端项目
│   ├── src/main/java/com/smartfinance/agent/
│   │   ├── agent/                    # AI Agent 层
│   │   │   ├── FinancialAiService.java     # @AiService 接口（Agent 对话入口）
│   │   │   ├── FinancialTools.java         # 财务数据查询工具
│   │   │   └── WebSearchTool.java          # 联网搜索工具
│   │   ├── config/                   # 配置类
│   │   │   ├── LangChain4jConfig.java      # LLM 模型配置
│   │   │   ├── RagConfig.java              # RAG 知识库配置
│   │   │   └── BatchEmbeddingModel.java    # Embedding 批处理
│   │   ├── controller/               # REST API 控制器
│   │   ├── service/                  # 业务逻辑层
│   │   ├── mapper/                   # MyBatis 数据访问
│   │   ├── entity/                   # 数据实体
│   │   ├── dto/                      # 数据传输对象
│   │   └── common/                   # 通用工具（Result、异常处理等）
│   └── src/main/resources/
│       ├── application.yml           # 公共配置
│       ├── application-local.yml     # ⚠️ 本地敏感配置（不上传 GitHub）
│       ├── schema.sql                # 数据库表结构（启动时自动创建）
│       ├── data.sql                  # 测试数据（启动时自动插入）
│       └── knowledge/                # RAG 理财知识文档库
└── frontend/                         # 前端项目
    └── src/
        ├── views/                    # 页面组件
        │   ├── Dashboard.vue         # 仪表盘（统计卡片 + 图表）
        │   ├── Transaction.vue       # 消费记录（表格 + 筛选）
        │   ├── ChatView.vue          # 智能助手（对话界面）
        │   ├── Login.vue             # 登录
        │   └── Register.vue          # 注册
        ├── layouts/                  # 布局组件
        ├── api/                      # API 接口封装
        └── stores/                   # Pinia 状态管理
```

## 快速开始

### 前置条件

- JDK 17+
- Maven 3.8+
- MySQL 8.0+
- Node.js 18+
- npm 9+

### 1. 克隆项目

```bash
git clone https://github.com/your-username/smart_finance_agent.git
cd smart_finance_agent
```

### 2. 初始化数据库

在 MySQL 中创建数据库（只需这一步，表结构会自动创建）：

```sql
CREATE DATABASE IF NOT EXISTS smart_finance DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

项目启动时，Spring Boot 会自动执行 `schema.sql` 创建表结构，并执行 `data.sql` 插入测试数据（含一个测试用户 `test_user`，密码 `123456`）。如果表已存在，不会重复创建，可放心反复启动。

### 3. 配置本地密钥

```bash
# 复制密钥模板
copy env.example backend\src\main\resources\application-local.yml
```

编辑 `application-local.yml`，填入你的真实密钥：

```yaml
spring:
  datasource:
    password: 你的数据库密码
jwt:
  secret: 你的JWT密钥（任意长字符串）
langchain4j:
  dashscope:
    api-key: sk-你的DashScope API Key    # 从 https://dashscope.aliyuncs.com 获取
search:
  api-key: tvly-你的Tavily API Key       # 从 https://app.tavily.com 获取（可选）
```

### 4. 启动后端

```bash
cd backend
mvn spring-boot:run
```

后端默认运行在 `http://localhost:8080`。

### 5. 启动前端

```bash
cd frontend
npm install
npm run dev
```

前端默认运行在 `http://localhost:3000`。

### 6. 登录使用

打开浏览器访问 `http://localhost:3000`，注册账号后即可开始使用。

> 也可使用内置测试账号：**用户名** `test_user` / **密码** `123456`（含预置的测试消费数据，方便体验 AI 分析功能）

## 功能预览

###   仪表盘

- 4 张大号统计卡片：总收入、总支出、结余、储蓄率
- 环形饼图：支出分类分布
- 折线趋势图：月度收支变化（支持 30天 / 90天 / 全年切换）
- 最近交易列表

###   智能助手

> 向 AI Agent 提问，获取专业的财务分析

**示例问题：**

| 问题类型 | 示例 |
|---------|------|
| 财务概览 | "我这个月花了多少钱？" |
| 支出分析 | "哪类消费最多？帮我分析一下" |
| 理财建议 | "我的储蓄率怎么样？有什么建议？" |
| 紧急备用金 | "帮我分析一下紧急备用金是否充足" |
| 资产配置 | "我今年28岁，风险偏好稳健，怎么配资产？" |
| 联网搜索 | "最近有什么财经新闻？" |

###   消费记录

- 多维度筛选：类型、分类、日期范围
- 收入/支出颜色区分
- 新增/编辑/删除记录
- 分页浏览

## AI Agent 架构

```
用户提问
   │
   ▼
┌─────────────────────────────┐
│   FinancialAiService        │  ← @AiService 接口
│   (系统提示词 + 记忆)        │
└─────────────┬───────────────┘
              │
      ┌───────┴───────┐
      │               │
      ▼               ▼
┌──────────┐   ┌──────────┐
│ Tool 调用 │   │ RAG 检索 │
│          │   │          │
│ 财务工具  │   │ 理财知识  │
│ 搜索工具  │   │ 文档库    │
└──────────┘   └──────────┘
      │               │
      ▼               ▼
  ┌─────────────────────┐
  │   LLM (通义千问)     │
  │   推理 + 生成回复    │
  └─────────┬───────────┘
            ▼
        返回结果
```

### 关键组件

- **@AiService** — `FinancialAiService.java` 定义 Agent 的行为和系统提示词
- **@Tool** — `FinancialTools.java` 提供查询财务数据的工具方法
- **RAG** — `RagConfig.java` 配置 Embedding 模型和向量检索
- **Memory** — `MessageWindowChatMemory` 维护对话历史（每个用户独立）
- **搜索** — `WebSearchTool.java` 调用 Tavily API 获取实时信息

## 配置参考

### application.yml

| 配置项 | 说明 | 默认值 |
|-------|------|--------|
| `server.port` | 后端端口 | 8080 |
| `spring.datasource.url` | 数据库连接地址 | MySQL localhost |
| `spring.sql.init.mode` | 启动时自动执行 SQL 脚本 | always |
| `spring.sql.init.continue-on-error` | SQL 执行出错时继续启动 | true |
| `jwt.expiration` | Token 过期时间(ms) | 86400000 |
| `langchain4j.dashscope.chat-model.model-name` | AI 模型 | qwen3.5-plus |
| `langchain4j.dashscope.chat-model.temperature` | 模型温度 | 0.8 |
| `langchain4j.dashscope.embedding-model.model-name` | 嵌入模型 | text-embedding-v3 |

### 前端代理配置

`vite.config.js` 中配置了 API 代理到 `http://localhost:8080`。

## 许可

[MIT License](LICENSE)