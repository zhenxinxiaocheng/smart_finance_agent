# Investment Data Quality Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为股票日线和基金净值建立不可变 Parquet 数据快照、版本化质量规则和强制分析门禁，使每次分析都能追溯到同一份 `datasetVersion`，不合格数据不能产生新的分析或回测结果。

**Architecture:** Python `analysis-service` 是市场数据标准化、Parquet 快照和质量规则执行的唯一内核；Spring Boot 只持久化快照清单、质量结果、用户资产关联和分析版本，并在调用分析前执行门禁；Vue 和 Agent 只读取 Spring Boot 返回的结构化质量报告，不复制阈值、不重新解释规则。现有行情同步、投资详情和分析接口增量接入，不重写投资模块。

**Tech Stack:** Python 3.12、FastAPI、Pydantic 2、PyArrow 25、Java 17、Spring Boot 3.2.5、MyBatis-Plus、Flyway、MySQL/SQLite/H2、Vue 3、Node test runner。

## Global Constraints

- 任何可调阈值、执行模式、版本、存储位置和展示数量上限必须来自版本化配置或环境变量；Python、Java 和 Vue 中不得存在业务数值回退。
- 股票和基金共用质量框架，但规则注册表必须按 `productType` 隔离；基金不得套用 OHLC、成交量、涨跌停或复权规则。
- 标准化失败不写快照；已标准化但质量不合格的数据允许保存为审计快照，但不能生成新分析。
- `datasetVersion` 必须由规范化 Manifest、Parquet 文件哈希和 `schemaVersion` 计算；数据库自增 ID 只用于关联，不是数据版本。
- 快照文件不可覆盖；写入必须使用临时文件、原子移动和文件哈希复核。
- `OBSERVE` 只改变 `decision`，不能篡改 `status`；`status=BLOCKED, decision=ALLOW` 必须带 `QUALITY_OBSERVE_ONLY`，且不得成为后续策略晋级证据。
- `ENFORCE` 下 `status=BLOCKED` 必须得到 `decision=BLOCK`；前端、Agent 和普通用户 API 均无绕过参数。
- `BLOCKED` 或质量服务异常不得覆盖最后一份有效分析；只能返回明确标记的缓存结果或数据不足结果。
- 新分析快照必须绑定 `dataSnapshotId`、`datasetVersion` 和 `qualityRuleSetVersion`；旧记录返回 `LEGACY_UNVERSIONED`。
- 分析缓存键必须包含 `datasetVersion`、周期配置哈希和分析规则版本，不能只比较行情日期。
- 同一 `datasetVersion` 的重放必须先校验 Manifest 与 Parquet 哈希，再把从 Parquet 读出的记录交给分析接口。
- 工作区已有大量用户改动；每个任务只暂存本任务列出的文件，禁止使用 `git add .`。

---

## Runtime Contracts

### Python configuration

Create `analysis-service/config/data-quality-v1.json` as the only source of quality thresholds:

```json
{
  "quality_rule_set_version": "data-quality-v1",
  "schema_version": "market-data-schema-v1",
  "enforcement_mode": "OBSERVE",
  "storage": {
    "root_env": "ANALYSIS_DATA_ROOT",
    "orphan_retention_hours": 24
  },
  "gate": {
    "warning_failures_to_block": 3
  },
  "stock": {
    "max_stale_trading_days": 1,
    "max_missing_trading_day_ratio": "0.02",
    "cross_source_reconciliation_enabled": true,
    "cross_source_required_overlap_days": 1,
    "cross_source_price_deviation_ratio": "0.01",
    "extreme_return_warning_ratio": "0.20",
    "corporate_action_evidence_return_ratio": "0.30",
    "extreme_volume_multiplier": "20"
  },
  "fund": {
    "max_stale_calendar_days": 3,
    "max_missing_nav_day_ratio": "0.05"
  }
}
```

这些是可审计的 v1 初始参数，不是永久市场定义。更改任一参数时必须新增规则集版本文件，不能原地修改已经被历史质量报告引用的版本。

### Canonical record schema

Parquet 每行字段固定为：

```text
product_code, product_type, market, frequency, adjust_type, data_date,
open, high, low, close, volume, provider, adapter_version, fetched_at,
trading_status, adjustment_factor, corporate_action_ref, nav_type, is_estimated
```

- 金额和价格使用 `decimal128(28, 8)`，成交量使用 `decimal128(30, 4)`。
- `data_date` 使用 `date32`，`fetched_at` 使用 UTC `timestamp[us, tz=UTC]`。
- 股票 `nav_type`、`is_estimated` 为空；基金 `open/high/low/volume` 可为空。
- `frequency`、`adjust_type`、`product_type` 来自请求上下文，但输入记录若携带冲突值必须失败，不能静默覆盖。

### Python API

`POST /internal/v1/data/snapshots/validate`

```json
{
  "code": "002632",
  "market": "SZSE",
  "product_type": "STOCK",
  "start_date": "2025-01-01",
  "end_date": "2026-07-18",
  "frequency": "DAY",
  "adjust_type": "QFQ",
  "quality_rule_set_version": "data-quality-v1"
}
```

`POST /internal/v1/data/snapshots/replay`

```json
{
  "dataset_version": "<64-char sha256>",
  "quality_rule_set_version": "data-quality-v1"
}
```

Both endpoints return:

```json
{
  "manifest": { "datasetVersion": "...", "contentHash": "...", "parquetFileHash": "..." },
  "qualityReport": {
    "qualityRuleSetVersion": "data-quality-v1",
    "status": "PASS",
    "decision": "ALLOW",
    "enforcementMode": "OBSERVE",
    "issues": [],
    "summary": {}
  },
  "records": []
}
```

`records` 必须从刚写入或已重放的 Parquet 文件读取，禁止直接回传写入前的内存列表，以证明本次计算输入与可重放输入一致。

`POST /internal/v1/data/snapshots/claim` is an internal lifecycle endpoint. Spring Boot calls it only after the database transaction that stores snapshot metadata commits. It creates a separate claim marker and never mutates Parquet or Manifest content. Unclaimed snapshots older than `storage.orphan_retention_hours` are eligible for cleanup; claimed snapshots are never removed by orphan cleanup.

### Spring API

- `GET /api/investment/assets/{id}/data-quality`: 返回该用户资产最新已持久化的质量报告；无记录返回 `status=NOT_EVALUATED`。
- `POST /api/investment/assets/{id}/data-quality/refresh`: 重新抓取并验证数据，只更新数据质量，不自动绕过门禁或生成分析。
- 原 `GET /api/investment/assets/{id}/detail` 与刷新分析接口保持兼容，在 `sourceStatus` 增加 `datasetVersion`、`qualityRuleSetVersion`、`qualityStatus`、`qualityDecision`、`qualityIssues` 和 `qualityEvidenceEligibility`。

---

## File Map

### Python data kernel

- Modify `analysis-service/requirements.txt`: pin `pyarrow==25.0.0`.
- Create `analysis-service/config/data-quality-v1.json`: all v1 tunable values.
- Create `analysis-service/app/data_quality/__init__.py`: public exports only.
- Create `analysis-service/app/data_quality/models.py`: typed context, Manifest, report and issue models.
- Create `analysis-service/app/data_quality/config.py`: version discovery, validation and environment resolution.
- Create `analysis-service/app/data_quality/snapshot_store.py`: canonical schema, atomic Parquet writer, manifest sidecar, replay and hash verification.
- Create `analysis-service/app/data_quality/rules.py`: common, stock and fund rules.
- Create `analysis-service/app/data_quality/engine.py`: registry, report aggregation and gate decision.
- Create `analysis-service/app/data_quality/service.py`: provider fetch -> canonicalize -> snapshot -> evaluate -> replay orchestration.
- Modify `analysis-service/app/main.py`: request models and two internal endpoints.
- Create tests under `analysis-service/tests/`: `test_data_quality_config.py`, `test_snapshot_store.py`, `test_data_quality_rules.py`, `test_data_quality_endpoints.py`.

### Spring Boot persistence and gate

- Create MySQL and SQLite V9 migrations and mirror the final schema in `backend/src/main/resources/schema.sql` and `backend/src/test/resources/schema-h2.sql`.
- Create entities `InvestmentDataSnapshot`, `InvestmentDataQualityRun`, `InvestmentDataQualityIssue` and matching mapper interfaces.
- Extend `InvestmentAnalysisSnapshot` with the three data lineage fields.
- Create `InvestmentDataQualityProperties`, `investment-data-quality.properties`, typed DTOs, `InvestmentDataQualityService`, `InvestmentDataQualityServiceImpl`, and `InvestmentAnalysisGate`.
- Modify `AnalysisServiceClient`, `InvestmentAnalysisServiceImpl`, `InvestmentAssetDetailResponse`, `InvestmentAssetController`, `InvestmentAgentTools`, and `ToolRegistry`.
- Add focused tests next to the corresponding backend package and extend SQLite migration coverage.

### Frontend and launch

- Modify `frontend/src/api/investment.js`.
- Create `frontend/src/lib/investmentDataQuality.js` and its Node test.
- Create `frontend/src/components/investment/InvestmentDataQualityPanel.vue` and source-contract test.
- Modify `frontend/src/views/InvestmentAssetDetail.vue` and `frontend/test/investmentDetailPage.test.mjs`.
- Modify `start-dev.ps1`, `env.example`, and `.gitignore` to inject `ANALYSIS_DATA_ROOT` without placing a path fallback in Python or Java.

---

### Task 1: Versioned Python configuration and typed contracts

**Files:**
- Modify: `analysis-service/requirements.txt`
- Create: `analysis-service/config/data-quality-v1.json`
- Create: `analysis-service/app/data_quality/__init__.py`
- Create: `analysis-service/app/data_quality/models.py`
- Create: `analysis-service/app/data_quality/config.py`
- Create: `analysis-service/tests/test_data_quality_config.py`

**Interfaces:**

```python
class SnapshotContext(BaseModel):
    product_type: Literal["STOCK", "MUTUAL_FUND"]
    market: str
    code: str
    frequency: Literal["DAY"]
    adjust_type: Literal["QFQ", "HFQ", "NONE"]
    provider: str
    adapter_version: str
    requested_start_date: date
    requested_end_date: date
    fetched_at: datetime

class DataQualityIssue(BaseModel):
    rule_code: str
    severity: Literal["INFO", "WARNING", "CRITICAL"]
    outcome: Literal["PASS", "FAIL", "NOT_APPLICABLE"]
    message: str
    observed: dict[str, Any]
    expected: dict[str, Any]
    affected_dates: list[date]

class DataQualityReport(BaseModel):
    dataset_version: str
    quality_rule_set_version: str
    status: Literal["PASS", "WARN", "BLOCKED"]
    decision: Literal["ALLOW", "BLOCK"]
    enforcement_mode: Literal["OBSERVE", "ENFORCE"]
    evaluated_at: datetime
    issues: list[DataQualityIssue]
    summary: dict[str, Any]
```

- [ ] **Step 1: Write failing configuration tests**

Test that the loader rejects a missing `quality_rule_set_version`, an unknown enforcement mode, negative thresholds, a missing `ANALYSIS_DATA_ROOT`, and a requested version that has no matching JSON file. Test that decimal ratios remain exact `Decimal` values.

```python
def test_missing_storage_environment_fails_without_code_fallback(self):
    with patch.dict(os.environ, {}, clear=True):
        with self.assertRaisesRegex(ValueError, "ANALYSIS_DATA_ROOT"):
            load_data_quality_config("data-quality-v1", self.config_dir)
```

- [ ] **Step 2: Run and verify the missing module failure**

Run: `cd analysis-service; .\.venv\Scripts\python.exe -m unittest tests.test_data_quality_config -v`

Expected: FAIL because `app.data_quality.config` does not exist.

- [ ] **Step 3: Add the pinned dependency and versioned JSON**

Append `pyarrow==25.0.0` to `requirements.txt`. Add the exact JSON from Runtime Contracts. Do not add DuckDB.

- [ ] **Step 4: Implement strict version discovery and validation**

```python
def load_data_quality_config(version: str, config_dir: Path | None = None) -> DataQualityConfig:
    directory = config_dir or Path(__file__).resolve().parents[2] / "config"
    matches = []
    for path in directory.glob("data-quality-*.json"):
        payload = json.loads(path.read_text(encoding="utf-8"))
        if payload.get("quality_rule_set_version") == version:
            matches.append((path, payload))
    if len(matches) != 1:
        raise ValueError(f"quality rule set must resolve exactly once: {version}")
    config = DataQualityConfig.model_validate(matches[0][1])
    root_env = config.storage.root_env
    root_value = os.getenv(root_env)
    if not root_value:
        raise ValueError(f"required environment variable is missing: {root_env}")
    return config.model_copy(update={"storage_root": Path(root_value).resolve()})
```

Pydantic validators must enforce non-empty version strings, positive integer thresholds, ratios in `[0, 1]`, and `MUTUAL_FUND -> adjustType=NONE` at request-model level.

- [ ] **Step 5: Run the focused tests**

Run: `cd analysis-service; .\.venv\Scripts\python.exe -m unittest tests.test_data_quality_config -v`

Expected: PASS with all configuration and model validation cases.

- [ ] **Step 6: Commit only Task 1 files**

```powershell
git add -- analysis-service/requirements.txt analysis-service/config/data-quality-v1.json analysis-service/app/data_quality/__init__.py analysis-service/app/data_quality/models.py analysis-service/app/data_quality/config.py analysis-service/tests/test_data_quality_config.py
git commit -m "feat: add versioned data quality contracts"
```

### Task 2: Atomic immutable Parquet snapshot store

**Files:**
- Create: `analysis-service/app/data_quality/snapshot_store.py`
- Create: `analysis-service/tests/test_snapshot_store.py`

**Interfaces:**

```python
class SnapshotStore:
    def __init__(self, root: Path, schema_version: str): ...
    def write(self, records: Sequence[Mapping[str, Any]], context: SnapshotContext) -> DataSnapshotManifest: ...
    def read(self, dataset_version: str) -> tuple[DataSnapshotManifest, list[dict[str, Any]]]: ...
    def verify(self, manifest: DataSnapshotManifest) -> None: ...
    def claim(self, dataset_version: str) -> None: ...
    def cleanup_unclaimed(self, now: datetime, retention: timedelta) -> list[str]: ...
```

Storage layout:

```text
<root>/snapshots/<first-two-hash-chars>/<datasetVersion>.parquet
<root>/snapshots/<first-two-hash-chars>/<datasetVersion>.manifest.json
<root>/claims/<first-two-hash-chars>/<datasetVersion>.claim
```

- [ ] **Step 1: Write failing snapshot tests**

Cover all of these cases:

- two writes with identical records and a fixed `SnapshotContext.fetched_at` produce the same `datasetVersion` and do not rewrite the existing file;
- changing one close value, `adjustType`, provider, requested interval or schema version changes `datasetVersion`;
- read returns rows in ascending `data_date` order with decimal strings preserved at the API boundary;
- a manually modified Parquet file raises `SnapshotIntegrityError`;
- a missing Manifest or Parquet file raises `SnapshotNotFoundError`;
- a failed temporary write leaves no final Manifest or Parquet file.
- claim creates a separate marker without changing either content hash;
- cleanup removes only unclaimed final pairs older than the configured retention and stale temporary files; claimed snapshots survive cleanup.

- [ ] **Step 2: Run and verify failure**

Run: `cd analysis-service; .\.venv\Scripts\python.exe -m unittest tests.test_snapshot_store -v`

Expected: FAIL because `SnapshotStore` does not exist.

- [ ] **Step 3: Implement canonicalization and the Arrow schema**

```python
CANONICAL_SCHEMA = pa.schema([
    pa.field("product_code", pa.string(), nullable=False),
    pa.field("product_type", pa.string(), nullable=False),
    pa.field("market", pa.string(), nullable=False),
    pa.field("frequency", pa.string(), nullable=False),
    pa.field("adjust_type", pa.string(), nullable=False),
    pa.field("data_date", pa.date32(), nullable=False),
    pa.field("open", pa.decimal128(28, 8)),
    pa.field("high", pa.decimal128(28, 8)),
    pa.field("low", pa.decimal128(28, 8)),
    pa.field("close", pa.decimal128(28, 8), nullable=False),
    pa.field("volume", pa.decimal128(30, 4)),
    pa.field("provider", pa.string(), nullable=False),
    pa.field("adapter_version", pa.string(), nullable=False),
    pa.field("fetched_at", pa.timestamp("us", tz="UTC"), nullable=False),
    pa.field("trading_status", pa.string()),
    pa.field("adjustment_factor", pa.decimal128(28, 12)),
    pa.field("corporate_action_ref", pa.string()),
    pa.field("nav_type", pa.string()),
    pa.field("is_estimated", pa.bool_()),
])
```

Reject unparseable values and conflicting per-row identity fields before creating a temporary file. Sort by the unique key `(product_code, market, frequency, adjust_type, data_date)`.

- [ ] **Step 4: Implement deterministic identity and atomic publish**

1. Write the sorted Arrow table to `<dataset temp id>.parquet.tmp` with fixed compression and no caller-dependent metadata.
2. Hash the temporary Parquet bytes as `parquetFileHash`.
3. Hash canonical JSON rows as `contentHash` for diagnostics.
4. Build the identity Manifest without `datasetVersion`, `storageUri` or database fields.
5. Compute `datasetVersion = sha256(canonical_json(identity_manifest + parquetFileHash + schemaVersion))`.
6. Atomically move Parquet first, write the final Manifest to a temporary sidecar, fsync it, then atomically move the Manifest.
7. If the final version already exists, verify both hashes and return it; never overwrite mismatching content.
8. Do not create a claim marker here; ownership is established only after Spring metadata commits.

- [ ] **Step 5: Read only through Manifest verification**

`read()` must reject dataset versions that do not match `^[0-9a-f]{64}$`, derive the exact hash-prefix path under the configured root, verify both files, then convert the Arrow table to API records. This prevents path traversal, recursive scans and silent file reconstruction.

- [ ] **Step 6: Implement claim and bounded orphan cleanup**

`claim()` writes an atomic zero-content marker after verifying the snapshot. `cleanup_unclaimed()` compares file modification time with the configured retention, skips any version with a claim marker, and deletes Parquet plus Manifest as one orphan pair. It also removes stale `.tmp` files. It must reject a non-positive retention rather than using a code default.

- [ ] **Step 7: Run focused tests**

Run: `cd analysis-service; .\.venv\Scripts\python.exe -m unittest tests.test_snapshot_store -v`

Expected: PASS, including corruption and partial-write cases.

- [ ] **Step 8: Commit only Task 2 files**

```powershell
git add -- analysis-service/app/data_quality/snapshot_store.py analysis-service/tests/test_snapshot_store.py
git commit -m "feat: add immutable parquet snapshot store"
```

### Task 3: Common, stock and fund quality rules

**Files:**
- Create: `analysis-service/app/data_quality/rules.py`
- Create: `analysis-service/app/data_quality/engine.py`
- Create: `analysis-service/tests/test_data_quality_rules.py`

**Interfaces:**

```python
class DataQualityRule(Protocol):
    rule_code: str
    rule_version: str
    severity: Literal["INFO", "WARNING", "CRITICAL"]
    def supports(self, context: SnapshotContext) -> bool: ...
    def evaluate(self, records, manifest, trading_dates, config) -> DataQualityIssue: ...

class DataQualityEngine:
    def __init__(self, config, rules, clock): ...
    def evaluate(self, records, manifest, trading_dates=(), reconciliation_records=()) -> DataQualityReport: ...
```

Rule registry must contain these stable codes:

```text
COMMON_REQUIRED_FIELDS, COMMON_UNIQUE_ORDERED_DATES, COMMON_POSITIVE_VALUES,
MANIFEST_CONTENT_INTEGRITY,
STOCK_OHLC_RELATION, STOCK_ADJUSTMENT_CONSISTENCY, STOCK_STALENESS,
STOCK_UNEXPLAINED_TRADING_GAPS, STOCK_EXTREME_RETURN,
STOCK_EXTREME_VOLUME, STOCK_CORPORATE_ACTION_EVIDENCE,
STOCK_CROSS_SOURCE_RECONCILIATION,
FUND_NAV_TYPE_CONSISTENCY, FUND_ESTIMATED_NAV_FORBIDDEN,
FUND_STALENESS, FUND_UNEXPLAINED_NAV_GAPS
```

- [ ] **Step 1: Write the failing rule matrix tests**

Use fixed dates, fixed configuration objects and no live provider calls. Required cases:

- valid A-share QFQ daily rows -> PASS;
- duplicate date, invalid OHLC, non-positive price, negative volume or mixed adjust type -> BLOCKED;
- one-price limit day `open=high=low=close` -> not an anomaly failure;
- missing trading dates covered by `trading_status=SUSPENDED` -> no unexplained-gap failure;
- corporate-action-sized jump without `adjustment_factor` or `corporate_action_ref` -> CRITICAL failure;
- cross-source comparison with different adjust type -> NOT_APPLICABLE;
- same-date, same-adjust-type secondary-source close deviation above the configured ratio -> FAIL with both providers and values in evidence;
- valid unit NAV fund rows -> PASS without stock rule issues;
- mixed unit/accumulated NAV or any `is_estimated=true` -> BLOCKED;
- warnings reach the configured count -> status BLOCKED;
- OBSERVE maps BLOCKED status to ALLOW plus `QUALITY_OBSERVE_ONLY`;
- ENFORCE maps BLOCKED status to BLOCK.

- [ ] **Step 2: Verify tests fail**

Run: `cd analysis-service; .\.venv\Scripts\python.exe -m unittest tests.test_data_quality_rules -v`

Expected: FAIL because rule and engine types are missing.

- [ ] **Step 3: Implement rule isolation and aggregation**

```python
def decision_for(status: str, mode: str) -> tuple[str, str | None]:
    if status != "BLOCKED":
        return "ALLOW", None
    if mode == "ENFORCE":
        return "BLOCK", None
    return "ALLOW", "QUALITY_OBSERVE_ONLY"
```

Aggregation rules:

- any failed `CRITICAL` issue -> `BLOCKED`;
- failed `WARNING` issue count at or above `gate.warning_failures_to_block` -> `BLOCKED`;
- otherwise any failed `WARNING` -> `WARN`;
- otherwise -> `PASS`.

All thresholds are read through typed config accessors. Structural invariants may be code, but no rule may contain a numeric market threshold literal.

- [ ] **Step 4: Make non-applicable evidence explicit**

Every registered rule returns exactly one issue with `PASS`, `FAIL` or `NOT_APPLICABLE`. The response may omit PASS rows from the public issue list, but `summary` must include counts by outcome and the list of executed rule versions so the run is auditable.

- [ ] **Step 5: Run focused tests**

Run: `cd analysis-service; .\.venv\Scripts\python.exe -m unittest tests.test_data_quality_rules -v`

Expected: PASS for common, stock, fund and enforcement-mode matrices.

- [ ] **Step 6: Commit only Task 3 files**

```powershell
git add -- analysis-service/app/data_quality/rules.py analysis-service/app/data_quality/engine.py analysis-service/tests/test_data_quality_rules.py
git commit -m "feat: add product-aware quality rules"
```

### Task 4: Validate and replay FastAPI endpoints

**Files:**
- Create: `analysis-service/app/data_quality/service.py`
- Modify: `analysis-service/app/providers.py`
- Modify: `analysis-service/app/main.py`
- Modify: `analysis-service/tests/test_provider_normalization.py`
- Create: `analysis-service/tests/test_data_quality_endpoints.py`

**Interfaces:**

```python
class DataQualityService:
    def validate(self, request: SnapshotValidationRequest) -> SnapshotValidationResponse: ...
    def replay(self, request: SnapshotReplayRequest) -> SnapshotValidationResponse: ...
    def claim(self, dataset_version: str) -> None: ...
```

Provider contract additions:

```python
@dataclass(frozen=True)
class ProviderBatch:
    primary_records: list[NormalizedQuote]
    reconciliation_records: list[NormalizedQuote]
    warnings: list[str]

class MarketDataProvider:
    def supports(self, market: str, product_type: str,
                 frequency: str, adjust_type: str) -> bool: ...
```

Extend `NormalizedQuote` with actual `product_type`, `frequency`, `adjust_type`, `trading_status`, `adjustment_factor`, `corporate_action_ref`, `nav_type`, and `is_estimated`. Provider adapters must supply these fields; the snapshot service must never infer them from the request after records return.

- [ ] **Step 1: Write failing endpoint tests**

Use FastAPI dependency overrides or replace the module service with a fake provider registry and temporary snapshot root. Assert:

- missing internal token -> 401;
- end date before start date -> 400;
- unknown rule-set version -> 422;
- provider unavailable -> 503 and no files;
- valid stock/fund requests return Manifest, report and Parquet-read records;
- replay of the returned version returns the same Manifest and records;
- claim creates a lifecycle marker only after explicit invocation;
- tampered Parquet replay returns a structured BLOCKED response with `MANIFEST_CONTENT_INTEGRITY`, not HTTP 200 with ALLOW;
- unexpected rule-engine exception becomes `status=BLOCKED`; in ENFORCE it becomes `decision=BLOCK`.
- a provider that cannot produce the requested adjust type is skipped rather than relabeled;
- Tushare raw daily data is not accepted as QFQ unless that adapter actually applies and records a QFQ transformation;
- when configured, the registry attempts a second provider for the configured overlap days and gives only same-frequency, same-adjust-type rows to reconciliation.

- [ ] **Step 2: Run and verify missing endpoint failure**

Run: `cd analysis-service; .\.venv\Scripts\python.exe -m unittest tests.test_data_quality_endpoints -v`

Expected: FAIL because both routes return 404.

- [ ] **Step 3: Implement the validate orchestration**

```python
batch = registry.daily_quote_candidates(
    request.code, request.market, request.product_type,
    request.start_date, request.end_date,
    request.frequency, request.adjust_type,
    reconcile=config.stock.cross_source_reconciliation_enabled,
)
context = SnapshotContext.from_provider_records(request, batch.primary_records)
manifest = store.write([row.json_dict() for row in batch.primary_records], context)
stored_manifest, stored_records = store.read(manifest.dataset_version)
trading_dates = calendar_for(request, stored_records)
report = engine.evaluate(
    stored_records, stored_manifest, trading_dates,
    [row.json_dict() for row in batch.reconciliation_records],
)
return SnapshotValidationResponse(
    manifest=stored_manifest,
    quality_report=report,
    records=stored_records,
    provider_warnings=batch.warnings,
)
```

Fund requests use NAV dates and pass no stock trading calendar. Stock calendar requests must combine every year in the requested interval and deduplicate dates.

Declare actual adapter capabilities: Tencent history, AKShare A-share history and BaoStock `adjustflag=2` may advertise QFQ only after their normalization tests prove that mapping; AKShare fund NAV advertises `NONE` plus `nav_type=UNIT_NAV`; Tushare `pro.daily` advertises `NONE`, not QFQ. A provider warning cannot change these capabilities.

Before writing a candidate, run canonical parsing and identity-conflict checks. After the Parquet file exists, read it back and execute the complete quality engine, including `MANIFEST_CONTENT_INTEGRITY`, against those read-back records. This preserves the approved order “standardize -> candidate -> quality -> publish metadata” while making the report reference a real `datasetVersion`.

- [ ] **Step 4: Implement replay without provider calls**

Discover the requested versioned config, locate Manifest by indexed version path, verify/read Parquet, evaluate with the requested rule-set version and return stored records. Add a provider fake assertion proving replay performs zero network/provider calls.

- [ ] **Step 5: Add claim and cleanup lifecycle handling**

Add `POST /internal/v1/data/snapshots/claim` with `{ "dataset_version": "..." }`. It verifies the version and creates the claim marker. At service startup and before validate requests, run `cleanup_unclaimed()` using `storage.orphan_retention_hours`; never delete a claimed snapshot.

- [ ] **Step 6: Keep the old daily-quotes endpoint compatible**

Do not remove `/internal/v1/market-data/quotes/daily`. The Spring integration switches in a later task; current consumers and tests must continue to pass.

- [ ] **Step 7: Run Python tests**

Run: `cd analysis-service; .\.venv\Scripts\python.exe -m unittest discover -s tests -p "test_*.py" -v`

Expected: PASS for existing analysis/provider tests plus all four data-quality test modules.

- [ ] **Step 8: Commit only Task 4 files**

```powershell
git add -- analysis-service/app/data_quality/service.py analysis-service/app/providers.py analysis-service/app/main.py analysis-service/tests/test_provider_normalization.py analysis-service/tests/test_data_quality_endpoints.py
git commit -m "feat: expose validated data snapshots"
```

### Task 5: V9 lineage persistence and entity mapping

**Files:**
- Create: `backend/src/main/resources/db/migration/mysql/V9__investment_data_quality.sql`
- Create: `backend/src/main/resources/db/migration/sqlite/V9__investment_data_quality.sql`
- Modify: `backend/src/main/resources/schema.sql`
- Modify: `backend/src/test/resources/schema-h2.sql`
- Create: `backend/src/main/java/com/smartfinance/agent/investment/entity/InvestmentDataSnapshot.java`
- Create: `backend/src/main/java/com/smartfinance/agent/investment/entity/InvestmentDataQualityRun.java`
- Create: `backend/src/main/java/com/smartfinance/agent/investment/entity/InvestmentDataQualityIssue.java`
- Create matching mapper files under `backend/src/main/java/com/smartfinance/agent/investment/mapper/`
- Modify: `backend/src/main/java/com/smartfinance/agent/investment/entity/InvestmentAnalysisSnapshot.java`
- Modify: `backend/src/test/java/com/smartfinance/agent/investment/InvestmentSqliteMigrationTest.java`
- Create: `backend/src/test/java/com/smartfinance/agent/investment/entity/InvestmentDataQualityEntityMappingTest.java`

**Schema:**

- `investment_data_snapshot`: product identity, source identity, interval, count, both hashes, storage URI, schema version, availability status and timestamps; unique `dataset_version`.
- `investment_data_quality_run`: snapshot FK, rule-set version, status, decision, enforcement mode, evidence eligibility, summary JSON and evaluated time.
- `investment_data_quality_issue`: run FK, code, severity, outcome, message, observed/expected JSON and affected dates JSON.
- `investment_analysis_snapshot`: nullable `data_snapshot_id`, `dataset_version`, `quality_rule_set_version`.

- [ ] **Step 1: Extend migration tests first**

Assert Flyway current version `9`, all three tables, the unique dataset-version constraint, the issue/run foreign keys, and all three new analysis columns. Insert a V8-era analysis row, migrate to V9, and prove it remains readable with null lineage fields.

- [ ] **Step 2: Run and verify V8 failure**

Run: `cd backend; mvn -q -Dtest=InvestmentSqliteMigrationTest,InvestmentDataQualityEntityMappingTest test`

Expected: FAIL because V9 and the entities do not exist.

- [ ] **Step 3: Add portable migrations and schema mirrors**

Use MySQL `BIGINT AUTO_INCREMENT`/`JSON or LONGTEXT according to existing conventions` and SQLite `INTEGER PRIMARY KEY AUTOINCREMENT`/`TEXT`. Do not add database defaults for tunable thresholds or enforcement mode. Add indexes for:

```text
investment_data_snapshot(product_id, created_at)
investment_data_quality_run(snapshot_id, evaluated_at)
investment_data_quality_issue(run_id, severity)
investment_analysis_snapshot(dataset_version)
```

- [ ] **Step 4: Add MyBatis-Plus entities and mappers**

Entity field names must map one-to-one to migration columns. Store status-like values as strings so adding future status values does not require enum ordinal migration. Lineage fields on analysis snapshots remain nullable for legacy rows.

- [ ] **Step 5: Run focused persistence tests**

Run: `cd backend; mvn -q -Dtest=InvestmentSqliteMigrationTest,InvestmentDataQualityEntityMappingTest test`

Expected: PASS and migration version 9.

- [ ] **Step 6: Commit only Task 5 files**

```powershell
git add -- backend/src/main/resources/db/migration/mysql/V9__investment_data_quality.sql backend/src/main/resources/db/migration/sqlite/V9__investment_data_quality.sql backend/src/main/resources/schema.sql backend/src/test/resources/schema-h2.sql backend/src/main/java/com/smartfinance/agent/investment/entity/InvestmentDataSnapshot.java backend/src/main/java/com/smartfinance/agent/investment/entity/InvestmentDataQualityRun.java backend/src/main/java/com/smartfinance/agent/investment/entity/InvestmentDataQualityIssue.java backend/src/main/java/com/smartfinance/agent/investment/entity/InvestmentAnalysisSnapshot.java backend/src/main/java/com/smartfinance/agent/investment/mapper/InvestmentDataSnapshotMapper.java backend/src/main/java/com/smartfinance/agent/investment/mapper/InvestmentDataQualityRunMapper.java backend/src/main/java/com/smartfinance/agent/investment/mapper/InvestmentDataQualityIssueMapper.java backend/src/test/java/com/smartfinance/agent/investment/InvestmentSqliteMigrationTest.java backend/src/test/java/com/smartfinance/agent/investment/entity/InvestmentDataQualityEntityMappingTest.java
git commit -m "feat: persist investment data lineage"
```

### Task 6: Typed Spring client for validate and replay

**Files:**
- Modify: `backend/src/main/java/com/smartfinance/agent/investment/service/AnalysisServiceClient.java`
- Modify: `backend/src/test/java/com/smartfinance/agent/investment/service/AnalysisServiceClientTest.java`

**Interfaces:**

```java
public record SnapshotManifest(String datasetVersion, String productType, String market,
        String code, String frequency, String adjustType, String provider,
        String adapterVersion, LocalDate requestedStartDate, LocalDate requestedEndDate,
        LocalDate sampleStartDate, LocalDate sampleEndDate, int recordCount,
        OffsetDateTime fetchedAt, String contentHash, String parquetFileHash,
        String storageFormat, String storageUri, String schemaVersion) {}

public record QualityIssue(String ruleCode, String severity, String outcome, String message,
        Map<String, Object> observed, Map<String, Object> expected, List<LocalDate> affectedDates) {}

public record QualityReport(String datasetVersion, String qualityRuleSetVersion,
        String status, String decision, String enforcementMode,
        String evidenceEligibility, OffsetDateTime evaluatedAt,
        List<QualityIssue> issues, Map<String, Object> summary) {}

public record SnapshotValidationResult(SnapshotManifest manifest,
        QualityReport qualityReport, List<Map<String, Object>> records,
        List<String> providerWarnings) {}
```

- [ ] **Step 1: Add failing HTTP contract tests**

Using `MockRestServiceServer`, assert exact snake_case request fields and camelCase response mapping for:

```java
validateSnapshot(product, startDate, endDate, "DAY", "QFQ", "data-quality-v1")
replaySnapshot(datasetVersion, "data-quality-v1")
claimSnapshot(datasetVersion)
```

Also assert a null response, malformed date, missing Manifest and blank `datasetVersion` fail closed with a descriptive exception.

- [ ] **Step 2: Run and verify missing method failure**

Run: `cd backend; mvn -q -Dtest=AnalysisServiceClientTest test`

Expected: FAIL at compilation because the typed methods and records are missing.

- [ ] **Step 3: Implement the typed methods**

Use `RestClient` exactly as existing methods do, reuse `X-Internal-Token`, and centralize response validation in `requireValidSnapshotResult`. `claimSnapshot` posts only the dataset version and requires a successful acknowledgement. Do not let callers access a raw Map for quality status or decision.

- [ ] **Step 4: Keep existing client methods**

Do not remove `dailyQuotes`, `technicalAnalysis`, `fundAnalysis` or `backtest`; the analysis integration is changed only after persistence and gate services exist.

- [ ] **Step 5: Run client tests**

Run: `cd backend; mvn -q -Dtest=AnalysisServiceClientTest test`

Expected: PASS, including both new endpoint contracts and old client behavior.

- [ ] **Step 6: Commit Task 6 files**

```powershell
git add -- backend/src/main/java/com/smartfinance/agent/investment/service/AnalysisServiceClient.java backend/src/test/java/com/smartfinance/agent/investment/service/AnalysisServiceClientTest.java
git commit -m "feat: add typed snapshot analysis client"
```

### Task 7: Transactional quality service and user-facing API

**Files:**
- Create: `backend/src/main/resources/investment-data-quality.properties`
- Create: `backend/src/main/java/com/smartfinance/agent/investment/config/InvestmentDataQualityProperties.java`
- Create: `backend/src/main/java/com/smartfinance/agent/investment/domain/ValidatedInvestmentSnapshot.java`
- Create: `backend/src/main/java/com/smartfinance/agent/investment/dto/InvestmentDataQualityResponse.java`
- Create: `backend/src/main/java/com/smartfinance/agent/investment/service/InvestmentDataQualityService.java`
- Create: `backend/src/main/java/com/smartfinance/agent/investment/service/InvestmentDataQualityServiceImpl.java`
- Create: `backend/src/main/java/com/smartfinance/agent/investment/service/InvestmentAnalysisGate.java`
- Modify: `backend/src/main/java/com/smartfinance/agent/investment/controller/InvestmentAssetController.java`
- Create: `backend/src/test/java/com/smartfinance/agent/investment/config/InvestmentDataQualityPropertiesTest.java`
- Create: `backend/src/test/java/com/smartfinance/agent/investment/service/InvestmentDataQualityServiceTest.java`
- Create: `backend/src/test/java/com/smartfinance/agent/investment/controller/InvestmentDataQualityControllerTest.java`
- Modify: `backend/src/test/java/com/smartfinance/agent/investment/controller/InvestmentAssetControllerTest.java`

**Configuration:**

```properties
investment.data-quality.parameter-version=investment-data-quality-api-v1
investment.data-quality.quality-rule-set-version=data-quality-v1
investment.data-quality.stock-frequency=DAY
investment.data-quality.stock-adjust-type=QFQ
investment.data-quality.fund-frequency=DAY
investment.data-quality.fund-adjust-type=NONE
investment.data-quality.issue-query-limit=100
investment.data-quality.affected-date-query-limit=30
```

The Java properties class has no field initializers; `@Validated`, `@NotBlank`, `@Min(1)` and `@PropertySource` make missing or invalid keys fail startup.

**Interfaces:**

```java
public interface InvestmentDataQualityService {
    ValidatedInvestmentSnapshot ensureForAnalysis(Long userId, Long assetId,
            InvestmentProduct product, LocalDate startDate, LocalDate endDate, boolean refreshData);
    InvestmentDataQualityResponse latest(Long userId, Long assetId);
    InvestmentDataQualityResponse refresh(Long userId, Long assetId);
}

public final class InvestmentAnalysisGate {
    public GateDecision decide(ValidatedInvestmentSnapshot snapshot);
}
```

- [ ] **Step 1: Write failing service and controller tests**

Required scenarios:

- validate result persists snapshot -> run -> issues in one transaction;
- repeated `datasetVersion` reuses snapshot metadata but appends a new quality run;
- an insert failure rolls back run and issues and leaves the external snapshot unclaimed for retention-based cleanup;
- successful metadata commit calls the Python claim endpoint; claim failure blocks analysis and is retried when the same stored dataset is resolved again;
- asset ownership is checked before returning, replaying or refreshing quality data;
- `ensureForAnalysis(refreshData=false)` replays the newest available snapshot and performs no provider refresh;
- `refreshData=true` calls validate;
- report decision BLOCK returns a blocked domain object, not an exception that could be accidentally ignored;
- no report returns `NOT_EVALUATED`;
- GET and POST controller routes return the typed DTO.

- [ ] **Step 2: Run and verify failures**

Run: `cd backend; mvn -q -Dtest=InvestmentDataQualityPropertiesTest,InvestmentDataQualityServiceTest,InvestmentDataQualityControllerTest test`

Expected: FAIL because all new types are missing.

- [ ] **Step 3: Implement immutable metadata persistence**

Within one Spring transaction:

1. find or insert `investment_data_snapshot` by unique `datasetVersion`;
2. if an existing row has different hashes, storage URI or schema version, mark it `CORRUPT` and fail closed;
3. insert a quality run;
4. insert only non-PASS issues;
5. after transaction commit, claim the external snapshot;
6. return a domain object containing database snapshot ID, typed records and typed report only after claim succeeds.

Never update content identity fields of an existing snapshot.

Implement steps 1-4 inside `TransactionTemplate.execute(...)`, not an outer `@Transactional` method. The transaction is committed when `execute` returns; call `analysisClient.claimSnapshot(datasetVersion)` afterward. This ordering makes a failed database transaction leave an unclaimed cleanup candidate and prevents a claimed file from pointing to rolled-back metadata.

Select `frequency` and `adjustType` from `InvestmentDataQualityProperties` by product type. Do not use a Java ternary containing `DAY`, `QFQ` or `NONE`.

- [ ] **Step 4: Implement the gate**

```java
public GateDecision decide(ValidatedInvestmentSnapshot snapshot) {
    if (snapshot == null || snapshot.report() == null) return GateDecision.BLOCK;
    if ("BLOCK".equals(snapshot.report().decision())) return GateDecision.BLOCK;
    if ("QUALITY_OBSERVE_ONLY".equals(snapshot.report().evidenceEligibility())) {
        return GateDecision.ALLOW_ANALYSIS_NOT_EVIDENCE;
    }
    return GateDecision.ALLOW;
}
```

The gate contains no thresholds and cannot accept an override flag.

- [ ] **Step 5: Add controller routes**

Inject `InvestmentDataQualityService` into the full constructor and update `InvestmentAssetControllerTest` to construct all real dependencies explicitly; do not keep a null-producing constructor branch. Add:

```java
@GetMapping("/{id}/data-quality")
public Result<InvestmentDataQualityResponse> dataQuality(...)

@PostMapping("/{id}/data-quality/refresh")
public Result<InvestmentDataQualityResponse> refreshDataQuality(...)
```

- [ ] **Step 6: Run focused tests**

Run: `cd backend; mvn -q -Dtest=InvestmentDataQualityPropertiesTest,InvestmentDataQualityServiceTest,InvestmentDataQualityControllerTest test`

Expected: PASS for transaction, ownership, gate and API cases.

- [ ] **Step 7: Commit only Task 7 files**

```powershell
git add -- backend/src/main/resources/investment-data-quality.properties backend/src/main/java/com/smartfinance/agent/investment/config/InvestmentDataQualityProperties.java backend/src/main/java/com/smartfinance/agent/investment/domain/ValidatedInvestmentSnapshot.java backend/src/main/java/com/smartfinance/agent/investment/dto/InvestmentDataQualityResponse.java backend/src/main/java/com/smartfinance/agent/investment/service/InvestmentDataQualityService.java backend/src/main/java/com/smartfinance/agent/investment/service/InvestmentDataQualityServiceImpl.java backend/src/main/java/com/smartfinance/agent/investment/service/InvestmentAnalysisGate.java backend/src/main/java/com/smartfinance/agent/investment/controller/InvestmentAssetController.java backend/src/test/java/com/smartfinance/agent/investment/config/InvestmentDataQualityPropertiesTest.java backend/src/test/java/com/smartfinance/agent/investment/service/InvestmentDataQualityServiceTest.java backend/src/test/java/com/smartfinance/agent/investment/controller/InvestmentDataQualityControllerTest.java backend/src/test/java/com/smartfinance/agent/investment/controller/InvestmentAssetControllerTest.java
git commit -m "feat: add investment data quality service"
```

### Task 8: Enforce the gate in investment analysis

**Files:**
- Modify: `backend/src/main/java/com/smartfinance/agent/investment/service/InvestmentAnalysisServiceImpl.java`
- Modify: `backend/src/main/java/com/smartfinance/agent/investment/service/InvestmentSyncWorker.java`
- Modify: `backend/src/main/java/com/smartfinance/agent/investment/dto/InvestmentAssetDetailResponse.java`
- Modify: `backend/src/test/java/com/smartfinance/agent/investment/service/InvestmentServiceIntegrationTest.java`
- Modify: `backend/src/test/java/com/smartfinance/agent/investment/service/InvestmentSyncWorkerTest.java`
- Create: `backend/src/test/java/com/smartfinance/agent/investment/service/InvestmentAnalysisDataQualityGateTest.java`

- [ ] **Step 1: Write failing gate regression tests**

Required cases:

- PASS uses Parquet replay records for technical/fund/backtest and saves lineage fields;
- WARN/ALLOW runs analysis and exposes warnings;
- BLOCK/BLOCKED makes zero technical, fundamental, fund and backtest calls;
- BLOCKED with a previous valid analysis returns it as `analysisStatus=CACHED` and preserves its original `datasetVersion` while exposing the current blocked quality report separately;
- BLOCKED without a previous analysis returns `analysisStatus=BLOCKED`, no score and no personalized quantity;
- quality service exception returns cached analysis or failed-closed output, never READY;
- identical dataset version + preference hash + rule version is a cache hit;
- same quote date but different dataset version is not a cache hit;
- legacy snapshot returns `qualityStatus=LEGACY_UNVERSIONED` and is never treated as strategy evidence;
- OBSERVE-only runs may render analysis but store `analysisStatus=QUALITY_OBSERVE_ONLY`.
- the compatibility quote writer stores the Manifest adjust type passed by the gated path and does not re-infer it from product type.

- [ ] **Step 2: Run and verify current implementation fails**

Run: `cd backend; mvn -q -Dtest=InvestmentAnalysisDataQualityGateTest,InvestmentServiceIntegrationTest test`

Expected: FAIL because current code calls `dailyQuotes`, keys cache by `quoteDate`, and saves no data lineage.

- [ ] **Step 3: Replace the analysis input acquisition path**

At the start of `build(...)`, resolve horizons, calculate the configured requested interval, then call:

```java
ValidatedInvestmentSnapshot validated = dataQualityService.ensureForAnalysis(
        userId, assetId, product, startDate, endDate, refreshQuotes);
GateDecision gateDecision = analysisGate.decide(validated);
List<Map<String, Object>> records = validated.records();
```

Use `records` for analysis calls and `quoteSeries`. Persist them to the legacy `product_daily_quote` table only through a new `syncWorker.persistDailyQuotes(product, response, manifest.adjustType())` overload; the data-quality path must not let `InvestmentSyncWorker` infer the adjust type. No analysis calculation may reload a different set from that table.

- [ ] **Step 4: Enforce block before every computation**

Move the gate check before calls to technical, fundamental, fund and backtest endpoints. On BLOCK, load the latest valid analysis snapshot with non-null dataset lineage, do not call `saveSnapshot`, and add the current report to `sourceStatus`.

- [ ] **Step 5: Make analysis snapshots append-only by data/config identity**

Change `findSnapshot` to order descending and select the latest matching rule version. Cache hit requires:

```java
Objects.equals(snapshot.getDatasetVersion(), validated.manifest().datasetVersion())
    && Objects.equals(snapshot.getPreferenceHash(), preferenceHash)
    && Objects.equals(snapshot.getRuleVersion(), horizonProperties.getAnalysisRuleVersion())
```

When this identity changes, insert a new `InvestmentAnalysisSnapshot`; do not update the prior result. Only AI explanation fields on the same snapshot may be updated later.

- [ ] **Step 6: Populate source status without frontend inference**

Add all of these keys directly from persisted domain objects:

```text
datasetVersion, analysisDatasetVersion, qualityRuleSetVersion, qualityStatus, qualityDecision,
qualityIssues, qualitySummary, qualityEvidenceEligibility,
dataSnapshotId, analysisDataSnapshotId, quoteProvider, adjustType, quoteDate, analysisStatus
```

Do not emit `quoteStatus=READY` when quality status is WARN or BLOCKED.

`datasetVersion`/`dataSnapshotId` always identify the currently evaluated data. `analysisDatasetVersion`/`analysisDataSnapshotId` identify the snapshot that actually produced the displayed analysis; on a BLOCKED cached response these pairs intentionally differ.

- [ ] **Step 7: Run focused analysis tests**

Run: `cd backend; mvn -q -Dtest=InvestmentAnalysisDataQualityGateTest,InvestmentServiceIntegrationTest test`

Expected: PASS; blocked cases verify zero analysis interactions and unchanged prior snapshot count.

- [ ] **Step 8: Commit only Task 8 files**

```powershell
git add -- backend/src/main/java/com/smartfinance/agent/investment/service/InvestmentAnalysisServiceImpl.java backend/src/main/java/com/smartfinance/agent/investment/service/InvestmentSyncWorker.java backend/src/main/java/com/smartfinance/agent/investment/dto/InvestmentAssetDetailResponse.java backend/src/test/java/com/smartfinance/agent/investment/service/InvestmentServiceIntegrationTest.java backend/src/test/java/com/smartfinance/agent/investment/service/InvestmentSyncWorkerTest.java backend/src/test/java/com/smartfinance/agent/investment/service/InvestmentAnalysisDataQualityGateTest.java
git commit -m "feat: gate investment analysis by data quality"
```

### Task 9: Add the read-only Agent quality tool

**Files:**
- Modify: `backend/src/main/java/com/smartfinance/agent/agent/InvestmentAgentTools.java`
- Modify: `backend/src/main/java/com/smartfinance/agent/agent/ToolRegistry.java`
- Modify: `backend/src/test/java/com/smartfinance/agent/agent/ToolRegistryTest.java`
- Create: `backend/src/test/java/com/smartfinance/agent/agent/InvestmentAgentToolsTest.java`

- [ ] **Step 1: Write failing Agent tests**

Assert `dataQuality(assetId)` passes the user ID from `UserIdContext`, serializes the typed report, rejects missing user context, and never exposes mutation methods. Assert the registry manifest contains `get_investment_data_quality` with required integer `assetId` and read-only risk.

- [ ] **Step 2: Run and verify failure**

Run: `cd backend; mvn -q -Dtest=InvestmentAgentToolsTest,ToolRegistryTest test`

Expected: FAIL because the tool is absent.

- [ ] **Step 3: Implement the tool**

```java
public String dataQuality(Long assetId) {
    if (assetId == null) throw new IllegalArgumentException("assetId 不能为空");
    return json(dataQualityService.latest(requiredUserId(), assetId));
}
```

Register:

```java
register("get_investment_data_quality",
        "读取指定投资资产的数据版本、来源、质量状态和问题；只解释结构化结果，不能修改规则或绕过门禁。input: {assetId}",
        input -> investmentAgentTools.dataQuality(requiredLong(input, "assetId")));
```

Add a dedicated `requiredLong` parser that rejects missing, null, non-numeric and non-positive IDs; do not default to an arbitrary asset.

- [ ] **Step 4: Run Agent tests**

Run: `cd backend; mvn -q -Dtest=InvestmentAgentToolsTest,ToolRegistryTest test`

Expected: PASS and the built-in tool risk remains read-only.

- [ ] **Step 5: Commit Task 9 files**

```powershell
git add -- backend/src/main/java/com/smartfinance/agent/agent/InvestmentAgentTools.java backend/src/main/java/com/smartfinance/agent/agent/ToolRegistry.java backend/src/test/java/com/smartfinance/agent/agent/ToolRegistryTest.java backend/src/test/java/com/smartfinance/agent/agent/InvestmentAgentToolsTest.java
git commit -m "feat: expose investment data quality to agent"
```

### Task 10: Render actionable quality state in the investment detail page

**Files:**
- Modify: `frontend/src/api/investment.js`
- Create: `frontend/src/lib/investmentDataQuality.js`
- Create: `frontend/src/lib/investmentDataQuality.test.mjs`
- Create: `frontend/src/components/investment/InvestmentDataQualityPanel.vue`
- Create: `frontend/src/components/investment/InvestmentDataQualityPanel.test.mjs`
- Modify: `frontend/src/views/InvestmentAssetDetail.vue`
- Modify: `frontend/test/investmentDetailPage.test.mjs`

**Frontend contract:**

```javascript
export const normalizeDataQuality = sourceStatus => ({
  status: sourceStatus?.qualityStatus ?? 'NOT_EVALUATED',
  decision: sourceStatus?.qualityDecision ?? 'BLOCK',
  datasetVersion: sourceStatus?.datasetVersion ?? null,
  analysisDatasetVersion: sourceStatus?.analysisDatasetVersion ?? null,
  issues: Array.isArray(sourceStatus?.qualityIssues) ? sourceStatus.qualityIssues : [],
  canRefreshAnalysis: sourceStatus?.qualityStatus !== 'BLOCKED'
    && sourceStatus?.qualityDecision === 'ALLOW',
})
```

The status-to-label map contains UI semantics only: `PASS=数据合格`, `WARN=数据警告`, `BLOCKED=数据已阻断`, `CACHED=缓存分析`, `NOT_EVALUATED=尚未校验`, `LEGACY_UNVERSIONED=历史未版本化`. It contains no thresholds.

- [ ] **Step 1: Write failing pure and source-contract tests**

Assert:

- WARN never maps to “数据正常” or PASS styling;
- BLOCKED returns `canRefreshAnalysis=false` and `canRefreshData=true`;
- dataset short ID is derived from the returned value, not a constant;
- issue message, observed, expected and affected dates come from the backend;
- stock shows provider/frequency/adjust type, fund shows provider/NAV type and no stock OHLC help;
- the page imports both new APIs, renders the panel, disables analysis refresh on BLOCK, and keeps data refresh enabled;
- cached analysis shows both cached analysis dataset version and current blocking report.

- [ ] **Step 2: Run and verify failures**

Run: `cd frontend; node --test src/lib/investmentDataQuality.test.mjs src/components/investment/InvestmentDataQualityPanel.test.mjs test/investmentDetailPage.test.mjs`

Expected: FAIL because helper, panel and API methods are absent.

- [ ] **Step 3: Add API methods and pure helper**

```javascript
export const getInvestmentAssetDataQualityAPI = id =>
  request.get(`/investment/assets/${id}/data-quality`)

export const refreshInvestmentAssetDataQualityAPI = id =>
  request.post(`/investment/assets/${id}/data-quality/refresh`, null, { timeout: 60000 })
```

Keep all display mapping in `investmentDataQuality.js`; the component must not reimplement status logic.

- [ ] **Step 4: Implement the focused panel**

Props: `quality`, `productType`, `refreshing`. Emits: `refresh-data`. Render status badge, latest data date, provider, adjust/NAV type, short dataset version and backend issue messages. For BLOCKED, copy says no new analysis was generated and identifies cached analysis when present.

- [ ] **Step 5: Integrate without creating a second data center page**

Place the panel in the existing data-source/status area. The existing “刷新分析” button uses `:disabled="!dataQuality.canRefreshAnalysis || refreshingAnalysis"`; the panel’s “重新获取数据” button remains available unless a request is already running. After successful data refresh, reload detail; do not automatically submit a second analysis request.

- [ ] **Step 6: Run frontend tests and build**

Run: `cd frontend; node --test src/**/*.test.mjs test/*.test.mjs`

Expected: PASS for all Node tests.

Run: `cd frontend; npm run build`

Expected: Vite build succeeds with no missing import or template compile error.

- [ ] **Step 7: Commit Task 10 files**

```powershell
git add -- frontend/src/api/investment.js frontend/src/lib/investmentDataQuality.js frontend/src/lib/investmentDataQuality.test.mjs frontend/src/components/investment/InvestmentDataQualityPanel.vue frontend/src/components/investment/InvestmentDataQualityPanel.test.mjs frontend/src/views/InvestmentAssetDetail.vue frontend/test/investmentDetailPage.test.mjs
git commit -m "feat: show investment data quality gate"
```

### Task 11: Launch configuration, full verification and staged rollout

**Files:**
- Modify: `start-dev.ps1`
- Modify: `env.example`
- Modify: `.gitignore`
- Create: `analysis-service/tests/fixtures/data_quality/stock_pass.json`
- Create: `analysis-service/tests/fixtures/data_quality/stock_blocked_stale.json`
- Create: `analysis-service/tests/fixtures/data_quality/fund_pass.json`
- Create: `analysis-service/tests/test_data_quality_replay_regression.py`
- Create: `backend/src/test/java/com/smartfinance/agent/investment/service/InvestmentDataQualityEndToEndTest.java`

- [ ] **Step 1: Add failing fixed-sample replay tests**

For each fixture, use a fixed clock and temporary data root. Assert the exact expected status, stable dataset version and stable record hash. The backend test uses a fake HTTP server to return the Python fixture response, runs analysis twice from the same dataset version and verifies identical technical/backtest JSON hashes.

- [ ] **Step 2: Run and verify fixture/version failures**

Run: `cd analysis-service; .\.venv\Scripts\python.exe -m unittest tests.test_data_quality_replay_regression -v`

Run: `cd backend; mvn -q -Dtest=InvestmentDataQualityEndToEndTest test`

Expected: FAIL until fixtures, launch environment and full replay identity are wired.

- [ ] **Step 3: Inject the snapshot root at launch time**

Add a `start-dev.ps1` parameter whose default is a workspace-relative runtime directory, then export it only to the analysis-service process:

```powershell
[string]$AnalysisDataRoot = (Join-Path $PSScriptRoot '.run-data\analysis')

New-Item -ItemType Directory -Force -Path $AnalysisDataRoot | Out-Null
$env:ANALYSIS_DATA_ROOT = (Resolve-Path $AnalysisDataRoot).Path
```

The script is the local launch configuration; Python and Java still have no path fallback. Add `ANALYSIS_DATA_ROOT=` to `env.example` for non-script deployments and ignore `.run-data/` in Git.

- [ ] **Step 4: Make rollout mode explicit**

Keep `data-quality-v1.json` in `OBSERVE` for the first deployment. Verification must prove that blocked status is visible and labeled `QUALITY_OBSERVE_ONLY`. Switching to enforcement is a separate configuration commit that changes only:

```json
"enforcement_mode": "ENFORCE"
```

Before that commit, capture all fixed fixtures and any real-provider false positives. Do not modify historical v1 parameters after reports reference them; if thresholds change, create `data-quality-v2.json` and update Spring’s configured rule-set version in the same release.

- [ ] **Step 5: Run the full automated suite**

```powershell
Set-Location analysis-service
.\.venv\Scripts\python.exe -m unittest discover -s tests -p "test_*.py" -v

Set-Location ..\backend
mvn clean test

Set-Location ..\frontend
node --test
npm run build

Set-Location ..
git diff --check
git status --short
```

Expected: all Python, Maven and Node tests pass; Vite builds; `git diff --check` reports no whitespace errors; status contains only intentional task files plus preserved pre-existing user changes.

- [ ] **Step 6: Run the local API smoke test**

Start the services with `start-dev.ps1`. With user-provided environment variables `SMART_FINANCE_TOKEN` and `SMART_FINANCE_ASSET_ID`, run:

```powershell
$headers = @{ Authorization = "Bearer $env:SMART_FINANCE_TOKEN" }
$id = $env:SMART_FINANCE_ASSET_ID
Invoke-RestMethod -Headers $headers -Uri "http://127.0.0.1:8080/api/investment/assets/$id/data-quality"
Invoke-RestMethod -Method Post -Headers $headers -Uri "http://127.0.0.1:8080/api/investment/assets/$id/data-quality/refresh"
Invoke-RestMethod -Headers $headers -Uri "http://127.0.0.1:8080/api/investment/assets/$id/detail"
```

Expected for a healthy A-share fixture or provider response: actual provider, `datasetVersion`, rule-set version and PASS/WARN status are returned. Expected for stale/invalid data: BLOCKED is returned, no new analysis snapshot is inserted, and the detail response is CACHED or BLOCKED rather than READY.

- [ ] **Step 7: Commit Task 11 files**

```powershell
git add -- start-dev.ps1 env.example .gitignore analysis-service/tests/fixtures/data_quality/stock_pass.json analysis-service/tests/fixtures/data_quality/stock_blocked_stale.json analysis-service/tests/fixtures/data_quality/fund_pass.json analysis-service/tests/test_data_quality_replay_regression.py backend/src/test/java/com/smartfinance/agent/investment/service/InvestmentDataQualityEndToEndTest.java
git commit -m "test: verify replayable data quality gate"
```

---

## Acceptance Checklist

- [ ] 股票与基金质量规则在自动测试中证明隔离。
- [ ] 相同固定输入和 Manifest 生成相同 `datasetVersion`；任何身份字段或 Parquet 内容变化都会改变版本。
- [ ] Parquet 或 Manifest 被篡改后重放失败关闭，不能继续分析。
- [ ] PASS/WARN/BLOCKED 的状态、decision 和证据资格符合 OBSERVE/ENFORCE 语义。
- [ ] BLOCKED 时对技术、基本面、基金分析和回测的调用次数均为零。
- [ ] BLOCKED 不覆盖最后有效分析，并在页面同时展示缓存版本和当前阻断原因。
- [ ] 新分析快照包含数据快照 ID、数据版本和质量规则版本；旧记录明确为 `LEGACY_UNVERSIONED`。
- [ ] 详情页 WARN 不显示为“数据正常”，BLOCKED 不能刷新分析但可以重新获取数据。
- [ ] Agent 工具只能按当前用户读取指定资产质量报告，无修改或绕过能力。
- [ ] Python、Java 和 Vue 中不存在阈值、执行模式或存储路径的业务回退。
- [ ] 全量 Python、Maven、Node 测试和 Vite 构建通过。
- [ ] ENFORCE 切换作为单独配置变更执行，并在固定样本回放通过后才发布。
