# 开发与运行说明

[返回项目介绍](README.md)

本文仅供本地开发和定位现有代码，不是生产部署指南。项目尚未达到上线程度：类 Hermes Agent 能力仍在完善，量化工作台正在重构且未接入 Agent，进一步的量化分析尚未完成重构。当前相关分析缺乏科学依据与有效性验证，安全技术引入和登录注册的验证码、接码流程也尚未完成。平台面向管理个人财务并开展自主投资研究的个人用户，产品 Logo 尚未确定。

首次运行所需的数据库、本地配置和启动命令见 [README](README.md#本地运行)。下面列出的技术、页面与接口说明代码入口，不表示相关功能已经完成。

## 目录与调用关系

```text
smart_finance_agent/
├── frontend/                       # Vue 页面、组件、API 封装与前端测试
├── backend/
│   ├── src/main/java/              # 认证、财务、Agent、投资与量化业务
│   ├── src/main/resources/         # 应用配置、知识资源、Flyway 迁移
│   └── src/test/                   # Java 单元与集成测试
├── analysis-service/
│   ├── app/                        # FastAPI、数据来源适配与计算引擎
│   ├── config/                     # 数据契约、质量与策略配置
│   └── tests/                      # Python 回归测试
├── env.example                    # 本地敏感配置参考
├── DEVELOPMENT.md
└── README.md
```

- 浏览器通过 `/api` 调用 Java 后端；Vite 开发服务器代理这些请求。
- Java 后端处理用户权限、业务数据、Agent 工具与研究任务，并负责业务数据库写入。
- Python 分析服务提供行情来源适配、标准化、质量检查与量化计算。
- 量化研究复用 `MarketDataService` 和现有数据准备链路。个人资产 ID 与共享产品 ID 含义不同，不能互相替换。

## 配置与端口

| 配置 | 当前默认值或用途 |
| --- | --- |
| 后端端口 | `8080`，可通过 `server.port` 覆盖 |
| 前端端口 | `3000`，定义于 `frontend/vite.config.js` |
| `VITE_API_TARGET` | 前端开发代理目标，默认 `http://localhost:8080` |
| `ANALYSIS_SERVICE_URL` | 后端调用分析服务的地址，默认 `http://127.0.0.1:8090` |
| `ANALYSIS_INTERNAL_TOKEN` | Java 与 Python 的共享令牌，二者必须一致；本地默认 `dev-analysis-token` |
| `REDIS_HOST` / `REDIS_PORT` | 默认 `127.0.0.1:6379`；密码与数据库可通过对应环境变量设置 |
| `DASHSCOPE_VL_MODEL` | 账单识别视觉模型名；也可设置 `bill.ai.multimodal-model` |
| `TAVILY_API_KEY` | 可选的联网检索密钥 |
| `MARKET_DATA_FULL_LIBRARY_ENABLED` | 默认 `false`，显式启用完整市场库采集 |

后端默认导入 `backend/src/main/resources/application-local.yml`。数据库用户名、密码、JWT 密钥和 DashScope Key 放在该文件或通过受支持的环境变量配置，不提交到仓库。

`application-mysql.yml` 开启 Flyway；后端启动会核对数据库版本并执行未应用的迁移。后续结构变化追加迁移文件，已经执行的迁移不能直接修改。Java 与 Python 使用同一份数据契约配置，升级相关规则时应一起更新两端。

### 后端端口被占用

例如改用 `8088`，分别在后端与前端终端执行：

```powershell
# 后端终端，工作目录 backend
mvn spring-boot:run "-Dspring-boot.run.profiles=mysql" "-Dspring-boot.run.arguments=--server.port=8088"
```

```powershell
# 前端终端，工作目录 frontend
$env:VITE_API_TARGET = "http://127.0.0.1:8088"
npm run dev -- --port 3000 --strictPort
```

如果更换分析服务端口，同时调整 uvicorn 的 `--port` 和后端的 `ANALYSIS_SERVICE_URL`。更换共享令牌时，在两个服务各自的终端设置相同的 `ANALYSIS_INTERNAL_TOKEN` 后重启。

### 检查启动状态

```powershell
Invoke-RestMethod http://127.0.0.1:8090/health
Invoke-WebRequest http://localhost:3000
```

分析服务健康接口应返回 `status: UP`。后端日志应出现 Tomcat 启动及应用启动完成记录；未登录访问 `/api/auth/me` 返回 `401` 是鉴权行为，不是公开健康接口。

## 页面与接口入口

| 场景 | 页面 | 主要接口 |
| --- | --- | --- |
| 注册、登录 | `/register`、`/login` | `/api/auth` |
| 对话、运行步骤 | `/chat` | `/api/chat`、`/api/agent-runs` |
| 财务画像 | `/profile` | `/api/financial-profile`、`/api/agent-memories` |
| 记账与统计 | `/transactions`、`/statistics` | `/api/transactions`、`/api/transactions/statistics`、`/api/categories` |
| 账单导入 | `/bill-import` | `/api/bills` |
| 技能与周期任务 | `/skills`、`/schedules` | `/api/agent-skills`、`/api/agent-schedules` |
| 审计、反思与确认 | `/agent-audit`、`/reflections`、`/pending-actions` | `/api/agent-audit`、`/api/agent-reflections`、`/api/pending-actions` |
| 投资资产与详情 | `/stocks`、`/stocks/:assetId` | `/api/investment` |
| 策略与参数实验 | `/quant`、`/quant/strategies/:id`、`/quant/experiments/:id` | `/api/quant/v2` |
| 市场数据与资产池 | `/quant/data`、`/quant/data/:productId`、`/quant/universes` | `/api/quant/v2/market-data`、`/api/quant/v2/universes` |
| 因子、任务与模拟 | `/quant/factors`、`/quant/tasks`、`/quant/deployments` | `/api/quant/v2` 下对应资源 |

其他接口包括预算、通知、Agent 上下文等。具体参数以 Controller、DTO 和前端 API 封装为准，本表用于定位实现。

## Agent 与数据边界

- 流式对话入口为 `POST /api/chat/react/stream`。运行步骤、工具调用与待确认动作沿用现有 Agent 链路。
- 图片识别只产生候选交易，确认接口才写入正式账目；对话生成的关键写动作通过待确认接口执行或取消。
- 长期指令、自动记忆、反思候选和 Skills 是不同资源。外部说明型 Skill 不等于任意脚本执行能力。
- 个人投资资产、研究资产池和共享产品数据分别管理；资产池可以引用个人资产或共享市场产品。
- 数据需求复用已有采集队列和持久化服务。采集成功事实、区间准备和质量资格含义不同，不能用最后一条行情或任务完成状态代替完整性证明。
- 现有模拟组合代码包含回测成功、`qualification.status = QUALIFIED` 等检查，并使用 `PAPER` 请求。这些是代码中的流程条件，不是对量化方法科学有效性或产品可上线性的验证；相关链路仍在重构，量化工作台尚未接入 Agent。
- 现有基础鉴权和待确认动作不代表完成系统安全建设。计划中的安全技术、上线验证，以及登录注册验证码和接码流程仍待完成。

更详细的市场数据规则和量化模块职责见 [README 中的文档入口](README.md#开发文档)。

## 验证命令

以下每组命令从项目根目录进入对应模块后执行。选取与改动相关的检查，不把历史测试记录当作当前通过结果。工程测试或构建通过不能替代安全验证、量化方法有效性研究，也不代表达到上线条件。

### 前端构建与现有测试

```powershell
cd frontend
npm run build
$frontendTests = @(rg --files src -g '*.test.mjs')
node --test @frontendTests
npm run test:auth-layout
```

认证布局测试通过 `playwright-core` 启动本机 Chrome。若使用其他 Chromium 路径，先设置 `PLAYWRIGHT_EXECUTABLE_PATH`。测试覆盖登录与注册页的大屏、手机、平板和低高度窗口。

### 后端编译与测试

```powershell
cd backend
mvn compile
mvn test
```

仅运行指定用例时，在 PowerShell 中给 Maven 参数加引号：

```powershell
mvn "-Dtest=WorkbenchServiceTest,WorkbenchWiringHttpTest" test
```

### 分析服务测试

测试环境另需安装 pytest：

```powershell
cd analysis-service
.\.venv\Scripts\python.exe -m pip install pytest
.\.venv\Scripts\python.exe -m pytest
```

## 常见问题

- **依赖安装失败：** 当前 NumPy、SciPy 要求 Python 3.12+；Vite 8 要求 Node.js `^20.19.0 || >=22.12.0`。先核对版本，前端按锁文件执行 `npm ci`。
- **前端能打开，API 请求失败：** 检查后端是否启动、`VITE_API_TARGET` 是否与实际端口一致，以及数据库连接和迁移日志。
- **分析服务拒绝请求：** 检查两端共享令牌和 `ANALYSIS_SERVICE_URL`，不要把内部服务直接作为浏览器业务 API 使用。
- **AI 或图片识别不可用：** 检查 DashScope Key、模型名和对应服务权限；图片识别需要支持图像输入的模型。RAG 初始化失败时会跳过检索，聊天可继续，但不代表知识检索正常。
- **研究任务等待数据：** 检查数据中心中的实际需求与采集结果。免费来源可能限流或缺失，不应补造行情来让任务显示成功。

## 演示图片

README 的五张截图位于 `frontend/public/readme-*.png`：统计、Agent 收支查询、待确认记账、Skills 和因子配置。旧登录页与财务画像截图保留在仓库中，未作为本版功能演示。

本版截图仅展示开发进度，图中 Logo 为暂用设计。截图来自独立演示账号，账目均为示例数据；因子表单未提交研究，待确认午餐未执行入账。更新截图时使用隔离的演示数据，并同时核对配文，避免展示真实用户信息，或把配置界面与代码中的状态描述为已经完成的产品能力、科学验证或上线资格。
