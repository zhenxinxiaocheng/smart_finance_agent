# Analysis Service

本机 Python sidecar，负责市场数据 Provider、标准化、数据质量与组合指标。业务数据库只由 Spring Boot 写入。

```powershell
python -m pip install -r requirements.txt
$env:ANALYSIS_INTERNAL_TOKEN='dev-analysis-token'
python -m uvicorn app.main:app --host 127.0.0.1 --port 8090
```

可选配置 `TUSHARE_TOKEN`；未配置时按 AKShare、BaoStock 顺序降级。

采集使用有界、可复用的 AKShare 子进程及 HTTP 连接；超时会回收该进程，后续请求重新创建。连接复用不缓存接口返回值，每次调用仍执行数据源适配器。

| 环境变量 | 默认值 | 范围 |
| --- | --- | --- |
| `AKSHARE_WORKER_COUNT` | 4 | 1–16 个采集进程 |
| `AKSHARE_WORKER_MAX_TASKS` | 100 | 1–10000 次调用后回收进程 |

每次调用的期限包含等待空闲进程的时间；容量等待超时不计入来源故障。数据源连续失败后的冷却参数在策略和市场目录配置中设置。服务退出时关闭采集入口并回收进程，失败后的来源降级也不能再启动进程；应用生命周期启动时重新开放入口。

后端 `MARKET_DATA_WORKER_CHUNK_DAYS` 控制已知上市日期股票的回补窗口，默认 3650 天，可设为 1–3650。基金及未知上市日期产品仍按原有完整窗口处理，失败不会推进断点。后台任务并发保持原有配置。

行情采集和数据质量默认共用 `config/quote-availability-v1.json`：股票与场内 ETF 按交易所、时区和收盘时间确定目标；境内基金、QDII 和 FOF 按基金分类确定保守披露窗口。披露时点与延迟可在配置中调整，不能当作所有基金合同的保证。QDII 的净值日期不能直接取中美交易日交集，境内开放而境外休市时仍可能因汇率变化更新净值。

质量配置默认使用 `data-quality-v5.json`，保留 ENFORCE。已确认日历的基金按应公布的交易日计算陈旧程度；货币基金、未知分类、QDII 和 FOF 等尚未确认估值日历的情况，不把自然日空缺判为数据缺失，也不据此宣称完整历史已验证。旧版质量配置仍保留原有语义，回放使用采集时点。

完整历史回补与分析样本分别检查。已有有效行情满足分析窗口时可以生成分析，各周期继续执行样本数量、收益序列和质量校验；这不会写入完整历史准备凭据，回补仍由原有任务继续处理。暂未生成分析但样本足够时，页面显示等待分析的状态，避免误报“数据不足”。

A 股日历首次加载合并并发请求，并通过已有 AKShare 隔离采集进程执行；缓存期限在 `technical-strategy-v5.json` 的 `providers.trading_calendar_cache_ttl_seconds` 中配置。日历服务不可用时，后端使用保守请求上界并继续运行，开发者日志记录故障；降级上界不构成休市或历史完整性的证据。
