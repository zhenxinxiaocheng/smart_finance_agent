# 投资数据质量门禁与可重放快照 v1 设计

## 1. 状态与范围

- 日期：2026-07-18
- 状态：已批准，进入实施计划与开发阶段
- 市场范围：A 股日线研究；基金沿用同一框架，但使用独立的净值质量规则
- 交付边界：数据快照、质量校验、分析阻断、详情展示和 Agent 只读查询
- 非目标：本批次不建设因子中心、策略中心、事件驱动回测、模拟交易或券商连接

## 2. 目标

本批次解决三个问题：

1. 数据源超时、数据过期或口径冲突时，页面不能继续显示“数据正常”。
2. 不合格数据不能生成新的技术结论、因子、策略信号或回测结果。
3. 每次分析必须绑定不可变的数据版本，后续能够使用同一份数据重放。

验收时必须证明：同一 `datasetVersion`、规则版本和分析配置产生相同结果；数据被判定为 `BLOCKED` 时，不生成新的投资结论。

## 3. 核心决策

### 3.1 采用内部质量规则引擎

v1 不直接引入 Great Expectations。系统实现轻量的 `DataQualityRule` 接口和规则注册表，原因是：

- 当前服务只处理有限的股票日线和基金净值，完整 GX 运行时会扩大依赖和部署面。
- 内部规则可以直接表达 A 股交易日、复权、涨跌停和基金净值发布日期等领域约束。
- 规则结果、严重级别和阻断语义仍采用标准的“规则集合 -> 验证结果 -> 失败动作”模式，未来可以接入 GX 而不改变业务 API。

### 3.2 快照文件使用 Parquet，业务状态仍由 Spring Boot 持久化

- Python 量化内核负责将标准化记录写成不可变 Parquet 文件。
- 快照目录由 `ANALYSIS_DATA_ROOT` 配置，不在代码中写死路径。
- Spring Boot 负责保存快照清单、质量结果、用户资产关联和分析版本。
- Python 不直接修改 MySQL/SQLite 业务表。
- DuckDB 只作为后续研究查询能力预留，本批次不依赖 DuckDB 执行业务请求。

### 3.3 不可调的结构事实与可调阈值分离

以下属于数据结构事实，由代码表达：

- 日期、证券代码、数据频率和价格字段必须可解析。
- 价格必须为正数，成交量不能为负数。
- `low <= open/close <= high`。
- 同一证券、交易日、频率和复权口径不能重复。

以下属于业务阈值，必须来自版本化配置：

- 最大允许过期交易日数。
- 最大缺失比例。
- 跨源价格偏差阈值。
- 异常收益和异常成交量告警阈值。
- `WARN` 升级为 `BLOCKED` 的数量或比例条件。
- 观察模式或强制阻断模式。

任何可调数值不得散落在 Python、Java 或前端代码中。

## 4. 数据流

```text
Provider 原始响应
  -> Provider Adapter 标准化
  -> 构建候选 DataSnapshot
  -> DataQualityEngine 执行规则
  -> 写入不可变 Parquet 与 Manifest
  -> Spring Boot 保存快照和质量元数据
  -> AnalysisGate 判断 PASS/WARN/BLOCKED
  -> 分析、因子、策略或回测
  -> 结果绑定 datasetVersion
```

规则执行和快照写入使用同一批标准化记录，避免“校验的数据”和“计算的数据”不一致。

## 5. 领域对象

### 5.1 DataSnapshotManifest

至少包含：

- `datasetVersion`
- `productType`、`market`、`code`
- `frequency`
- `adjustType`
- `provider`、`adapterVersion`
- `requestedStartDate`、`requestedEndDate`
- `sampleStartDate`、`sampleEndDate`
- `recordCount`
- `fetchedAt`
- `contentHash`
- `storageFormat`、`storageUri`
- `schemaVersion`

`datasetVersion` 由规范化 Manifest、Parquet 内容哈希和 schemaVersion 计算，不使用数据库自增 ID 充当数据版本。

### 5.2 DataQualityRule

每条规则声明：

- `ruleCode`
- 支持的产品类型、市场、频率和复权口径
- `ruleVersion`
- `severity`
- 所需配置键
- 输入字段
- 校验结果和可解释证据

### 5.3 DataQualityReport

- `datasetVersion`
- `qualityRuleSetVersion`
- `status`: `PASS | WARN | BLOCKED`
- `decision`: `ALLOW | BLOCK`
- `evaluatedAt`
- `issues`
- `summary`
- `enforcementMode`: `OBSERVE | ENFORCE`

### 5.4 DataQualityIssue

- `ruleCode`
- `severity`: `INFO | WARNING | CRITICAL`
- `outcome`: `PASS | FAIL | NOT_APPLICABLE`
- `message`
- `observed`
- `expected`
- `affectedDates`

前端展示后端生成的说明，不自行解释规则代码或阈值。

## 6. v1 规则集

### 6.1 股票日线公共规则

- 必填字段和类型可解析。
- 日期严格递增且无重复。
- OHLC 关系合法。
- 价格为正、成交量非负。
- 记录证券、市场、频率和复权口径一致。
- 最新交易日相对官方交易日历不过期。
- 请求区间内的交易日缺口不超过配置阈值。
- provider 与 adapterVersion 存在。
- 内容哈希和记录数与快照清单一致。

### 6.2 A 股专属规则

- QFQ、HFQ 和未复权数据不能在同一快照中混用。
- 停牌日允许缺失，但必须有可解释状态；无法解释的缺口按配置升级。
- 一字涨跌停可以出现 `open = high = low = close`，不能被普通异常值规则误杀。
- 公司行为附近的价格跳变需要复权因子或事件证据；证据缺失时阻断研究级快照。
- 跨源对账只比较相同交易日和相同复权口径。

### 6.3 基金净值规则

- 使用净值日期而不是股票交易时段判断时效。
- 净值必须为正，日期不能重复。
- 不要求 OHLC、成交量或股票复权规则。
- 区分单位净值、累计净值和估算净值，禁止混用为同一序列。
- 估算净值只能用于临时展示，不能进入正式因子或回测快照。

## 7. 阻断规则

### 7.1 PASS

- 允许生成新分析和后续研究结果。
- 结果绑定当前 datasetVersion 和质量规则版本。

### 7.2 WARN

- 允许分析，但必须把警告写入结果和页面。
- 不能用前端文案把 WARN 显示成“数据正常”。
- 是否允许进入生产因子由后续因子版本的最低质量要求决定。

### 7.3 BLOCKED

- 禁止生成新的分析、因子、策略信号和回测结果。
- 不覆盖上一份有效分析快照。
- 可以展示上一份缓存，但必须标注缓存日期、原 datasetVersion 和当前阻断原因。
- Agent 只能解释阻断原因，不能要求绕过门禁。

`status` 表示数据本身的质量，`decision` 表示本次是否实际放行。`OBSERVE` 模式下可以出现
`status=BLOCKED, decision=ALLOW`，仅用于上线前收集误报；这类结果必须标记
`QUALITY_OBSERVE_ONLY`，不能作为因子或策略晋级证据。`ENFORCE` 模式下
`status=BLOCKED` 必须得到 `decision=BLOCK`。模式来自运行配置并记录到报告，不能由前端临时绕过。

## 8. 持久化设计

### 8.1 investment_data_snapshot

保存：产品、datasetVersion、provider、adapterVersion、频率、复权口径、样本区间、记录数、内容哈希、storageUri、schemaVersion、抓取时间和创建时间。

- `dataset_version` 唯一。
- 历史快照不可更新内容，只能新增。
- 文件丢失或哈希不匹配时标记不可用，不静默重建同一版本。

### 8.2 investment_data_quality_run

保存：snapshotId、规则集版本、状态、执行模式、汇总 JSON 和执行时间。

### 8.3 investment_data_quality_issue

保存每条失败或告警规则的代码、严重级别、observed/expected JSON 和受影响日期。

### 8.4 investment_analysis_snapshot 扩展

新增：

- `data_snapshot_id`
- `dataset_version`
- `quality_rule_set_version`

历史分析仍可读取；没有数据版本的旧记录明确标记为 `LEGACY_UNVERSIONED`，不能晋级为正式策略证据。

MySQL 和 SQLite 使用同一领域字段，分别提供 Flyway migration。

## 9. 服务边界

### 9.1 Python analysis-service

新增建议模块：

```text
app/data_quality/models.py
app/data_quality/rules.py
app/data_quality/engine.py
app/data_quality/snapshot_store.py
app/data_quality/config.py
config/data-quality-v1.json
```

内部接口：

- `POST /internal/v1/data/snapshots/validate`
- `POST /internal/v1/data/snapshots/replay`

响应必须包含 Manifest、QualityReport 和可供本次计算使用的 datasetVersion。

### 9.2 Spring Boot

新增：

- 快照、质量运行和问题实体及 Mapper。
- `InvestmentDataQualityService`。
- `InvestmentAnalysisGate`。
- 数据快照元数据持久化和分析结果绑定。

用户 API：

- `GET /api/investment/assets/{id}/data-quality`
- `POST /api/investment/assets/{id}/data-quality/refresh`

原详情 API 保持兼容，在 `sourceStatus` 中增加 datasetVersion、qualityStatus 和质量摘要。

### 9.3 前端

- 详情页沿用现有数据状态区域，不新建独立数据中心页面。
- Badge 使用 `PASS/WARN/BLOCKED/CACHED` 的真实状态。
- 展示数据日期、来源、复权口径、datasetVersion 短标识和具体问题。
- `BLOCKED` 时禁用“刷新分析”，但允许“重新获取数据”。
- 股票和基金展示各自适用字段。

### 9.4 Agent

新增只读工具：

- `get_investment_data_quality(assetId)`

工具只能读取结构化报告。Agent 不得修改规则阈值、执行模式或质量结论。

## 10. 错误处理

- Provider 请求失败：保留旧快照，不伪造新快照；同步批次记录失败原因。
- 标准化失败：不写 Parquet，不产生 datasetVersion。
- 质量校验异常：按 `BLOCKED` 处理，不能因校验器自身报错而放行。
- Parquet 写入失败：分析阻断，数据库不保存可用快照记录。
- 文件成功但数据库事务失败：文件视为孤儿，由配置化清理任务处理。
- 文件哈希不匹配：快照永久标记不可用，并产生 CRITICAL issue。
- 分析服务不可用：可以显示旧分析，不能生成新结论。
- Agent 不可用：不影响数据门禁和已有量化结果。

## 11. 配置

Python 使用版本化 `config/data-quality-v1.json`，Spring Boot 使用 `investment-data-quality.properties` 并支持环境变量覆盖。

配置至少包含：

- 规则集版本和 schemaVersion。
- 股票、基金分别的时效与缺口阈值。
- 跨源对账阈值。
- 异常收益和成交量告警阈值。
- enforcementMode。
- 快照根目录、保留策略和孤儿清理周期。
- API 查询、问题列表和日期明细上限。

启动时验证配置完整性。缺失关键配置时服务启动失败，不回退到代码默认值。

## 12. 测试

### 12.1 Python

- 每条规则的正常、边界和失败样本。
- 停牌、一字板、公司行为和复权口径测试。
- 股票与基金规则隔离测试。
- 相同记录生成相同 datasetVersion。
- 任意记录或 Manifest 改变后 datasetVersion 改变。
- Parquet 写入、读取和哈希校验。
- 规则配置缺失和非法值启动失败。

### 12.2 Spring Boot

- 快照、质量报告和问题事务一致性。
- PASS/WARN/BLOCKED 门禁行为。
- BLOCKED 不覆盖最后有效分析。
- 旧分析返回 `LEGACY_UNVERSIONED`。
- MySQL/SQLite migration 和实体映射。
- Agent 工具只读和用户资产隔离。

### 12.3 前端

- WARN 不显示为“数据正常”。
- BLOCKED 显示原因并禁用刷新分析。
- 缓存分析显示原数据日期和版本。
- 股票、基金的字段和帮助文本不同。

### 12.4 端到端

- 腾讯超时、备用源成功：按配置生成 WARN 或 PASS，并展示实际 provider。
- 最新交易日过期：BLOCKED，不产生新分析。
- OHLC 非法：BLOCKED。
- 复权口径混用：BLOCKED。
- 合格的道明光学历史数据：PASS，详情接口返回 datasetVersion，分析状态 READY。
- 使用同一 datasetVersion 重放：结果哈希一致。

## 13. 兼容与发布

实施分四步：

1. 建立快照、规则和持久化，短期使用 `OBSERVE` 收集现有数据问题；观察结果不得作为正式策略证据。
2. 修复误报和数据适配问题，完成固定样本回放测试。
3. 切换 `ENFORCE`，阻断新的不合格分析。
4. 为因子引擎开放只接受合格 datasetVersion 的输入接口。

不重写现有投资模块。原行情同步、详情 API 和页面结构增量接入门禁；旧数据与旧分析保持可读。

## 14. 验收标准

- 超时、过期、缺口、OHLC、复权或哈希问题均有结构化结果。
- 任何 WARN/BLOCKED 都不会显示为“数据正常”。
- 最终切换到 `ENFORCE` 后，BLOCKED 数据不能生成新的分析或回测。
- 同一 datasetVersion 能重放出相同输入和结果。
- 每个分析结果能追溯 provider、adapterVersion、复权口径、质量规则版本和数据时间。
- 股票和基金使用同一框架、不同规则，不互相套用。
- 所有可调阈值和路径均由版本化配置或环境变量提供。
- Agent 只能查询和解释质量报告，不能绕过门禁。
- 现有股票列表、详情、持仓、财富和 Agent 主流程保持兼容。
