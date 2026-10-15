# 量化工作台后端

按业务职责组织代码，HTTP 路径仍为 `/api/quant/v2`。

| 包 | 职责 |
| --- | --- |
| `api` | 接收请求、按资源类型调用对应业务服务 |
| `configuration` | 资产池、因子集、策略配置、冻结版本及关联检查 |
| `market` | 两类资产池成员解析、行情快照、准备需求与跟踪指数 |
| `task` | 研究任务创建、取消、重试、删除、领取和执行 |
| `deployment` | 模拟组合生命周期、执行结果和事件保存 |
| `query` | 权限范围内的记录读取、前端响应和冻结研究上下文 |
| `client` | Python 分析服务通信、执行结果和异常解释 |
| `scheduling` | 定时触发和线程池配置 |
| `support` | 本模块共用的 JSON、字段转换和校验函数 |
| `experiment` | 参数敏感性实验，复用普通任务执行入口 |

## 调用边界

- Controller 调用业务服务；调度器只触发任务或组合执行服务。
- SQL 和占位符构造集中在 Repository。普通业务服务不接收其他 Service 的数据库或事务字段。
- 行情读取复用 `MarketDataService`，数据准备复用市场需求与历史准备服务。
- 业务服务使用构造器注入。创建任务和冻结版本、创建配置和成员快照、组合状态和事件写入等，沿用 `TransactionTemplate` 的原子事务边界。
- 领取任务、准备后回写、完成或失败回写保留 claim token、revision 及原有 lease 条件，不能用无条件更新替代。
- 新建任务统一通过数据准备入口。历史任务已冻结 `assets` 时，仍可按原请求执行，不重新解释历史结果。
- `assetIds` 对应用户个人资产，`productIds` 对应共享市场证券。旧资产池和冻结持仓中的标的 ID 不得因拆包被替换。
- 现有接口与历史 JSON 的动态配置仍保留 `Map`；类型化协议迁移不属于此次结构整理。

## 验证入口

`WorkbenchServiceTest` 保留原有业务回归，通过 `WorkbenchTestContext` 装配真实业务服务和仓储；该装配器仅存在于测试源码。

`WorkbenchWiringHttpTest` 使用 Spring 扫描普通工作台 Bean、H2 和 MockMvc 验证依赖装配、原接口、权限与事务回滚。参数实验另有完整集成用例。`WorkbenchArchitectureTest` 防止 SQL、事务和业务逻辑重新堆入调度器或原大类。
