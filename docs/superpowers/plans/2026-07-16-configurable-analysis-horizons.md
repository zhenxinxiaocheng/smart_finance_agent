# Configurable Analysis Horizons Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将短期、中期、长期从散落硬编码升级为用户全局可配置、单资产可覆盖、全链路版本可追溯的周期画像，并确保分析、行情取数和回测都使用同一份解析结果。

**Architecture:** Spring Boot 是周期配置的唯一事实源，保存版本化用户画像和单资产覆盖，并向 Python 传递已经解析好的具体天数。Python 不解释 `SHORT/MEDIUM/LONG` 的市场含义，只按数值周期计算；Vue 复用一个通用编辑器维护全局画像和单资产覆盖。旧的 `investment_analysis_preference` 数据通过 V8 迁移保留，但运行时切换到规范化的 profile/setting 模型。

**Tech Stack:** Java 17、Spring Boot 3.2.5、MyBatis-Plus、Flyway 12、MySQL/SQLite/H2、Python 3 + FastAPI/Pydantic、Vue 3、Node test runner。

## Global Constraints

- 短期、中期、长期是用户语义标签，不得在前端、Spring Boot、Python 或回测代码中分别写死统一天数。
- 阻断性校验仅包括周期代码唯一、天数为正整数、最小天数不大于最大天数；区间重叠、空档和非常见命名不得阻止保存。
- 平台示例默认值只能存在于一处版本化 Spring 配置中；Python 和前端不得自带周期默认值。
- 配置解析优先级固定为：单资产覆盖 > 用户全局周期画像 > 平台示例默认值。
- 数据不足或策略不兼容时返回明确原因，不回退到隐藏默认周期，也不使用默认 50 分伪造结论。
- 每次分析与回测必须记录解析后的具体周期参数和 `horizonProfileVersion`。
- 保持现有 A 股日线、只做多、不连接券商、不自动下单边界。
- 工作区已有大量用户改动；每次只暂存任务明确列出的文件，禁止使用 `git add .`。

---

## File Map

### Backend domain and configuration

- Create `backend/src/main/java/com/smartfinance/agent/investment/domain/HorizonSetting.java`: 单个用户周期的不可变值对象。
- Create `backend/src/main/java/com/smartfinance/agent/investment/domain/ResolvedHorizonProfile.java`: 合并后的周期画像、版本和 Python 请求转换。
- Create `backend/src/main/java/com/smartfinance/agent/investment/config/InvestmentHorizonProperties.java`: 唯一平台模板和能力配置源。
- Create `backend/src/main/java/com/smartfinance/agent/investment/service/InvestmentHorizonService.java`: 全局、资产覆盖、解析和清除覆盖接口。
- Create `backend/src/main/java/com/smartfinance/agent/investment/service/InvestmentHorizonServiceImpl.java`: 配置叠加、版本新增和持久化。

### Backend persistence and API

- Create `backend/src/main/java/com/smartfinance/agent/investment/entity/InvestmentHorizonProfile.java` and `InvestmentHorizonSetting.java`: 规范化、可保留历史版本的实体。
- Create matching mapper interfaces under `backend/src/main/java/com/smartfinance/agent/investment/mapper/`.
- Create request/response DTOs under `backend/src/main/java/com/smartfinance/agent/investment/dto/`.
- Create `backend/src/main/java/com/smartfinance/agent/investment/controller/InvestmentHorizonController.java`: 用户全局画像 API。
- Modify `InvestmentAssetController`, `InvestmentAnalysisService`, `InvestmentAnalysisServiceImpl`, `InvestmentAssetDetailResponse`, `InvestmentAnalysisSnapshot`, and `AnalysisServiceClient` to use the resolved profile.
- Add V8 migrations for MySQL and SQLite; mirror schema in `schema.sql` and H2 test schema.

### Python analysis

- Modify `analysis-service/app/main.py`: require caller-supplied horizons and primary code; validate only structure.
- Modify `analysis-service/app/analysis.py`: iterate arbitrary codes, derive lookback from numbers, expose per-horizon insufficiency, use the primary horizon for top-level output, and backtest every configured horizon.
- Modify `analysis-service/tests/test_analysis_endpoints.py` and `test_analysis_engine.py`.

### Frontend

- Create `frontend/src/lib/horizonProfile.js`: normalize, clone and structurally validate API profiles.
- Create `frontend/src/lib/horizonProfile.test.mjs`.
- Create `frontend/src/components/investment/HorizonProfileDialog.vue`: reusable global/asset editor.
- Modify `frontend/src/api/investment.js`, `InvestmentAnalysis.vue`, `InvestmentAssetDetail.vue`, and `frontend/test/investmentDetailPage.test.mjs`.

---

### Task 1: Pure horizon model and single-source platform template

**Files:**
- Create: `backend/src/main/java/com/smartfinance/agent/investment/domain/HorizonSetting.java`
- Create: `backend/src/main/java/com/smartfinance/agent/investment/domain/ResolvedHorizonProfile.java`
- Create: `backend/src/main/java/com/smartfinance/agent/investment/config/InvestmentHorizonProperties.java`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/com/smartfinance/agent/investment/domain/ResolvedHorizonProfileTest.java`

**Interfaces:**
- Produces: `HorizonSetting(code, displayName, sortOrder, minHoldingDays, maxHoldingDays, primary, sourceScope)`.
- Produces: `ResolvedHorizonProfile(version, templateVersion, settings, warnings)` with `analysisRanges()`, `primaryCode()`, and `requiredHistoryDays()`.
- Produces: `InvestmentHorizonProperties.toTemplateProfile()` as the only source of example defaults.

- [ ] **Step 1: Write the failing domain test**

```java
class ResolvedHorizonProfileTest {
    @Test
    void arbitraryLabelsAndDaysDriveRangesWithoutMarketDefinitions() {
        ResolvedHorizonProfile profile = new ResolvedHorizonProfile(
                "template:v1|global:4|asset:2", "v1", List.of(
                new HorizonSetting("WAVE", "波段", 10, 7, 45, true, "ASSET"),
                new HorizonSetting("POSITION", "配置", 20, 80, 260, false, "GLOBAL")
        ), List.of());

        assertThat(profile.analysisRanges()).containsEntry("WAVE", List.of(7, 45))
                .containsEntry("POSITION", List.of(80, 260));
        assertThat(profile.primaryCode()).isEqualTo("WAVE");
        assertThat(profile.requiredHistoryDays()).isEqualTo(260);
    }
}
```

- [ ] **Step 2: Run the test and verify the missing types fail**

Run: `cd backend; mvn -q -Dtest=ResolvedHorizonProfileTest test`

Expected: FAIL because `HorizonSetting` and `ResolvedHorizonProfile` do not exist.

- [ ] **Step 3: Implement the immutable domain types**

```java
public record HorizonSetting(String code, String displayName, int sortOrder,
                             int minHoldingDays, int maxHoldingDays,
                             boolean primary, String sourceScope) {
    public HorizonSetting {
        code = Objects.requireNonNull(code).trim().toUpperCase(Locale.ROOT);
        displayName = Objects.requireNonNull(displayName).trim();
        if (code.isBlank() || displayName.isBlank()) throw new IllegalArgumentException("周期代码和名称不能为空");
        if (minHoldingDays < 1 || maxHoldingDays < minHoldingDays) {
            throw new IllegalArgumentException("周期天数必须为正整数，且最小天数不能大于最大天数");
        }
    }
}

public record ResolvedHorizonProfile(String version, String templateVersion,
                                     List<HorizonSetting> settings, List<String> warnings) {
    public ResolvedHorizonProfile {
        settings = settings.stream().sorted(Comparator.comparingInt(HorizonSetting::sortOrder)).toList();
        warnings = List.copyOf(warnings);
        if (settings.isEmpty()) throw new IllegalArgumentException("至少需要一个分析周期");
        if (settings.stream().map(HorizonSetting::code).distinct().count() != settings.size()) {
            throw new IllegalArgumentException("周期代码不能重复");
        }
    }

    public Map<String, List<Integer>> analysisRanges() {
        Map<String, List<Integer>> result = new LinkedHashMap<>();
        settings.forEach(item -> result.put(item.code(), List.of(item.minHoldingDays(), item.maxHoldingDays())));
        return result;
    }

    public String primaryCode() {
        return settings.stream().filter(HorizonSetting::primary).findFirst().orElse(settings.get(0)).code();
    }

    public int requiredHistoryDays() {
        return settings.stream().mapToInt(HorizonSetting::maxHoldingDays).max().orElseThrow();
    }
}
```

- [ ] **Step 4: Add the single configuration source**

Add one versioned template under `investment.analysis-horizons` in `application.yml`:

```yaml
investment:
  analysis-horizons:
    template-version: horizon-template-v1
    max-history-trading-days: 2500
    defaults:
      - code: SHORT
        display-name: 短期
        sort-order: 10
        min-holding-days: 5
        max-holding-days: 20
        primary: true
      - code: MEDIUM
        display-name: 中期
        sort-order: 20
        min-holding-days: 20
        max-holding-days: 120
        primary: false
      - code: LONG
        display-name: 长期
        sort-order: 30
        min-holding-days: 120
        max-holding-days: 500
        primary: false
```

Bind this list with `@Component` and `@ConfigurationProperties(prefix = "investment.analysis-horizons")`; do not initialize interval defaults in Java.

- [ ] **Step 5: Run the focused test**

Run: `cd backend; mvn -q -Dtest=ResolvedHorizonProfileTest test`

Expected: PASS with 1 test and 0 failures.

- [ ] **Step 6: Commit only Task 1 files**

```powershell
git add -- backend/src/main/java/com/smartfinance/agent/investment/domain/HorizonSetting.java backend/src/main/java/com/smartfinance/agent/investment/domain/ResolvedHorizonProfile.java backend/src/main/java/com/smartfinance/agent/investment/config/InvestmentHorizonProperties.java backend/src/main/resources/application.yml backend/src/test/java/com/smartfinance/agent/investment/domain/ResolvedHorizonProfileTest.java
git commit -m "feat: add configurable horizon domain"
```

### Task 2: Versioned persistence and legacy migration

**Files:**
- Create: `backend/src/main/resources/db/migration/mysql/V8__configurable_horizon_profiles.sql`
- Create: `backend/src/main/resources/db/migration/sqlite/V8__configurable_horizon_profiles.sql`
- Modify: `backend/src/main/resources/schema.sql`
- Modify: `backend/src/test/resources/schema-h2.sql`
- Create: `backend/src/main/java/com/smartfinance/agent/investment/entity/InvestmentHorizonProfile.java`
- Create: `backend/src/main/java/com/smartfinance/agent/investment/entity/InvestmentHorizonSetting.java`
- Create: `backend/src/main/java/com/smartfinance/agent/investment/mapper/InvestmentHorizonProfileMapper.java`
- Create: `backend/src/main/java/com/smartfinance/agent/investment/mapper/InvestmentHorizonSettingMapper.java`
- Modify: `backend/src/main/java/com/smartfinance/agent/investment/entity/InvestmentAnalysisSnapshot.java`
- Modify: `backend/src/test/java/com/smartfinance/agent/investment/InvestmentSqliteMigrationTest.java`

**Interfaces:**
- Produces: profile headers keyed by user, scope, asset, version, active flag.
- Produces: arbitrary setting rows keyed by profile and horizon code.
- Adds snapshot fields `horizonProfileVersion` and `horizonConfigJson`.

- [ ] **Step 1: Extend the SQLite migration test first**

After Flyway migration, assert version `8`, both new tables, and both snapshot columns:

```java
assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("8");
assertTableExists(connection, "investment_horizon_profile");
assertTableExists(connection, "investment_horizon_setting");
assertColumnExists(connection, "investment_analysis_snapshot", "horizon_profile_version");
assertColumnExists(connection, "investment_analysis_snapshot", "horizon_config_json");
```

Add a second test that migrates through V7, inserts one legacy `investment_analysis_preference`, then migrates V8 and verifies six legacy values became three setting rows with scope `ASSET` and source `LEGACY`.

- [ ] **Step 2: Verify migration tests fail at version 7**

Run: `cd backend; mvn -q -Dtest=InvestmentSqliteMigrationTest test`

Expected: FAIL because V8 and the new tables do not exist.

- [ ] **Step 3: Add normalized V8 tables and snapshot columns**

Use equivalent MySQL and SQLite DDL with these exact logical columns:

```sql
CREATE TABLE investment_horizon_profile (
    id INTEGER PRIMARY KEY,
    user_id INTEGER NOT NULL,
    scope_type VARCHAR(16) NOT NULL,
    asset_id INTEGER,
    version INTEGER NOT NULL,
    template_version VARCHAR(64) NOT NULL,
    source VARCHAR(24) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    effective_from TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE investment_horizon_setting (
    id INTEGER PRIMARY KEY,
    profile_id INTEGER NOT NULL,
    horizon_code VARCHAR(32) NOT NULL,
    display_name VARCHAR(50) NOT NULL,
    sort_order INTEGER NOT NULL,
    min_holding_days INTEGER NOT NULL,
    max_holding_days INTEGER NOT NULL,
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(profile_id, horizon_code)
);
```

Use `AUTO_INCREMENT` for MySQL and `AUTOINCREMENT` for SQLite. Add query indexes for `(user_id, scope_type, asset_id, active, version)`. Migrate legacy rows as inactive-history-safe version 1 asset profiles, then insert three settings with values copied from the old columns. Do not drop the legacy table in this migration.

- [ ] **Step 4: Add MyBatis entities, mappers and snapshot fields**

Entities map one-to-one to the DDL. Mapper interfaces contain no SQL and extend `BaseMapper<InvestmentHorizonProfile>` and `BaseMapper<InvestmentHorizonSetting>`.

- [ ] **Step 5: Mirror schema and run migration tests**

Run: `cd backend; mvn -q -Dtest=InvestmentSqliteMigrationTest test`

Expected: PASS, current Flyway version 8, legacy values preserved.

- [ ] **Step 6: Commit only persistence files**

```powershell
git add -- backend/src/main/resources/db/migration/mysql/V8__configurable_horizon_profiles.sql backend/src/main/resources/db/migration/sqlite/V8__configurable_horizon_profiles.sql backend/src/main/resources/schema.sql backend/src/test/resources/schema-h2.sql backend/src/main/java/com/smartfinance/agent/investment/entity/InvestmentHorizonProfile.java backend/src/main/java/com/smartfinance/agent/investment/entity/InvestmentHorizonSetting.java backend/src/main/java/com/smartfinance/agent/investment/entity/InvestmentAnalysisSnapshot.java backend/src/main/java/com/smartfinance/agent/investment/mapper/InvestmentHorizonProfileMapper.java backend/src/main/java/com/smartfinance/agent/investment/mapper/InvestmentHorizonSettingMapper.java backend/src/test/java/com/smartfinance/agent/investment/InvestmentSqliteMigrationTest.java
git commit -m "feat: persist versioned horizon profiles"
```

### Task 3: Profile resolution service and REST contracts

**Files:**
- Create: `backend/src/main/java/com/smartfinance/agent/investment/dto/HorizonSettingRequest.java`
- Create: `backend/src/main/java/com/smartfinance/agent/investment/dto/HorizonSettingResponse.java`
- Create: `backend/src/main/java/com/smartfinance/agent/investment/dto/HorizonProfileRequest.java`
- Create: `backend/src/main/java/com/smartfinance/agent/investment/dto/HorizonProfileResponse.java`
- Create: `backend/src/main/java/com/smartfinance/agent/investment/service/InvestmentHorizonService.java`
- Create: `backend/src/main/java/com/smartfinance/agent/investment/service/InvestmentHorizonServiceImpl.java`
- Create: `backend/src/main/java/com/smartfinance/agent/investment/controller/InvestmentHorizonController.java`
- Modify: `backend/src/main/java/com/smartfinance/agent/investment/controller/InvestmentAssetController.java`
- Modify: `backend/src/main/java/com/smartfinance/agent/investment/service/InvestmentAnalysisService.java`
- Test: `backend/src/test/java/com/smartfinance/agent/investment/service/InvestmentHorizonServiceTest.java`
- Test: `backend/src/test/java/com/smartfinance/agent/investment/controller/InvestmentAssetControllerTest.java`

**Interfaces:**
- `ResolvedHorizonProfile resolve(long userId, Long assetId)`.
- `HorizonProfileResponse global(long userId)` and `saveGlobal(long userId, HorizonProfileRequest request)`.
- `HorizonProfileResponse saveAssetOverride(long userId, long assetId, HorizonProfileRequest request)`.
- `HorizonProfileResponse clearAssetOverride(long userId, long assetId)`.
- HTTP `GET/PUT /api/investment/horizon-profile` and `PUT/DELETE /api/investment/assets/{id}/analysis-preference`.
- `HorizonSettingRequest(code, displayName, sortOrder, minHoldingDays, maxHoldingDays, primary)`.
- `HorizonSettingResponse(code, displayName, sortOrder, minHoldingDays, maxHoldingDays, primary, sourceScope)`.
- `HorizonProfileResponse(version, templateVersion, sourceScope, hasAssetOverride, maxHistoryTradingDays, settings, warnings)`.

- [ ] **Step 1: Write service tests for precedence and permissive ranges**

```java
@Test
void assetSettingsOverlayGlobalAndTemplateByCode() {
    when(profileMapper.selectList(any())).thenReturn(List.of(globalHeader(), assetHeader()));
    when(settingMapper.selectList(any())).thenReturn(List.of(
            setting(globalHeaderId, "SHORT", 9, 70),
            setting(assetHeaderId, "SHORT", 100, 160)));

    ResolvedHorizonProfile result = service.resolve(7L, 11L);

    assertThat(result.analysisRanges().get("SHORT")).containsExactly(100, 160);
    assertThat(result.version()).contains("global:2").contains("asset:4");
}

@Test
void overlappingAndUnusuallyNamedRangesAreSaved() {
    HorizonProfileRequest request = request(
            setting("FAST", "我的短期", 10, 10, 100, true),
            setting("SLOW", "我的长期", 20, 50, 80, false));

    assertThatCode(() -> service.saveGlobal(7L, request)).doesNotThrowAnyException();
}
```

- [ ] **Step 2: Run focused tests and verify failure**

Run: `cd backend; mvn -q -Dtest=InvestmentHorizonServiceTest,InvestmentAssetControllerTest test`

Expected: FAIL because the service and new DTOs do not exist.

- [ ] **Step 3: Implement generic DTO validation**

`HorizonSettingRequest` uses `@NotBlank`, `@Min(1)` and no `@Max`. `HorizonProfileRequest` uses `@NotEmpty @Valid List<HorizonSettingRequest> settings`. Service validation rejects duplicate normalized codes and multiple primary settings, but does not reject overlap, gaps or nonstandard display names.

- [ ] **Step 4: Implement version append and resolution**

Within one transaction, saving a scope must:

1. Read the current active header for that user/scope/asset.
2. Mark it inactive without deleting it.
3. Insert a new header with version `previous + 1` and source `USER` or `ASSET`.
4. Insert every request setting as its own row.
5. Resolve template, active global and active asset maps in that order.
6. Build version text `template:<templateVersion>|global:<n-or-0>|asset:<n-or-0>`.

- [ ] **Step 5: Implement authenticated controllers**

```java
@RestController
@RequestMapping("/api/investment/horizon-profile")
public class InvestmentHorizonController {
    @GetMapping
    public Result<HorizonProfileResponse> get(@RequestAttribute Long userId) {
        return Result.success(service.global(userId));
    }

    @PutMapping
    public Result<HorizonProfileResponse> put(@RequestAttribute Long userId,
                                               @Valid @RequestBody HorizonProfileRequest request) {
        return Result.success(service.saveGlobal(userId, request));
    }
}
```

Update the asset controller to accept the generic request and add DELETE for restoring inheritance. Always call `assetService.get(userId, id)` before saving or clearing an asset override.

- [ ] **Step 6: Run service and controller tests**

Run: `cd backend; mvn -q -Dtest=InvestmentHorizonServiceTest,InvestmentAssetControllerTest test`

Expected: PASS with precedence, versioning, overlap acceptance and authenticated delegation covered.

- [ ] **Step 7: Commit only Task 3 files**

```powershell
git add -- backend/src/main/java/com/smartfinance/agent/investment/dto/HorizonSettingRequest.java backend/src/main/java/com/smartfinance/agent/investment/dto/HorizonSettingResponse.java backend/src/main/java/com/smartfinance/agent/investment/dto/HorizonProfileRequest.java backend/src/main/java/com/smartfinance/agent/investment/dto/HorizonProfileResponse.java backend/src/main/java/com/smartfinance/agent/investment/service/InvestmentHorizonService.java backend/src/main/java/com/smartfinance/agent/investment/service/InvestmentHorizonServiceImpl.java backend/src/main/java/com/smartfinance/agent/investment/controller/InvestmentHorizonController.java backend/src/main/java/com/smartfinance/agent/investment/controller/InvestmentAssetController.java backend/src/main/java/com/smartfinance/agent/investment/service/InvestmentAnalysisService.java backend/src/test/java/com/smartfinance/agent/investment/service/InvestmentHorizonServiceTest.java backend/src/test/java/com/smartfinance/agent/investment/controller/InvestmentAssetControllerTest.java
git commit -m "feat: resolve global and asset horizon profiles"
```

### Task 4: Python contract without label-based defaults

**Files:**
- Modify: `analysis-service/app/main.py`
- Modify: `analysis-service/app/analysis.py`
- Modify: `analysis-service/tests/test_analysis_endpoints.py`
- Modify: `analysis-service/tests/test_analysis_engine.py`

**Interfaces:**
- `TechnicalAnalysisRequest` requires `horizons` and `primaryHorizon`.
- `analyze_technical(records, horizons, primary_horizon)` iterates arbitrary codes.
- `BacktestRequest` requires `horizons`; `backtest_horizons(records, horizons)` returns one result per code.

- [ ] **Step 1: Replace fixed-name tests with arbitrary user codes**

```python
def test_user_codes_and_numbers_are_authoritative(self):
    result = analyze_technical(
        price_records(620),
        {"WAVE": [7, 45], "POSITION": [80, 260]},
        "POSITION",
    )
    self.assertEqual({"WAVE", "POSITION"}, set(result["horizons"]))
    self.assertEqual(result["horizons"]["POSITION"]["score"], result["score"])
    self.assertEqual(135, result["horizons"]["WAVE"]["levelLookbackDays"])

def test_insufficient_horizon_has_no_fabricated_score(self):
    result = analyze_technical(price_records(200), {"PERSONAL_LONG": [300, 900]}, "PERSONAL_LONG")
    horizon = result["horizons"]["PERSONAL_LONG"]
    self.assertEqual("INSUFFICIENT", horizon["status"])
    self.assertNotIn("score", horizon)
```

Add endpoint tests that missing `horizons` raises Pydantic validation, `horizon_days=121` is no longer rejected by a fixed upper bound, and malformed `[max, min]` is rejected.

- [ ] **Step 2: Run Python tests and verify current hardcoding fails**

Run: `cd analysis-service; .\.venv\Scripts\python.exe -m unittest tests.test_analysis_engine tests.test_analysis_endpoints -v`

Expected: FAIL because the engine loops only over `SHORT/MEDIUM/LONG`, uses label lookback constants and supplies request defaults.

- [ ] **Step 3: Require and structurally validate horizons**

Use a Pydantic `field_validator` that requires a non-empty mapping; each value must contain exactly two positive integers with `minimum <= maximum`. Validate `primaryHorizon` exists in the mapping. Do not define interval defaults in Python.

- [ ] **Step 4: Make analysis label-agnostic**

Replace the hardcoded name loop and `minimum_level_lookbacks` mapping with:

```python
for name, bounds in horizons.items():
    minimum, maximum = int(bounds[0]), int(bounds[1])
    if len(closes) < maximum:
        horizon_results[name] = {
            "status": "INSUFFICIENT",
            "minimumDays": minimum,
            "maximumDays": maximum,
            "availableHistoryDays": len(closes),
            "requiredHistoryDays": maximum,
            "reason": "可用历史数据不足以覆盖用户配置周期",
        }
        continue
    level_lookback = min(len(closes), max(20, maximum * 3))
```

Set top-level score, verdict, levels and action zones from `primary_horizon`. If the primary horizon is insufficient, return top-level `status="INSUFFICIENT"` and no default score.

- [ ] **Step 5: Backtest all configured horizons**

`backtest_horizons` evaluates each range at integer midpoint `(minimum + maximum) // 2`, includes `minimumDays`, `maximumDays` and `evaluationDays`, and preserves an `INSUFFICIENT` result instead of substituting 20 days.

- [ ] **Step 6: Run Python tests**

Run: `cd analysis-service; .\.venv\Scripts\python.exe -m unittest discover -s tests -v`

Expected: all analysis-service tests PASS.

- [ ] **Step 7: Commit only Python task files**

```powershell
git add -- analysis-service/app/main.py analysis-service/app/analysis.py analysis-service/tests/test_analysis_endpoints.py analysis-service/tests/test_analysis_engine.py
git commit -m "feat: make analysis horizons label agnostic"
```

### Task 5: Use one resolved profile for data, analysis, cache and backtest

**Files:**
- Modify: `backend/src/main/java/com/smartfinance/agent/investment/service/AnalysisServiceClient.java`
- Modify: `backend/src/main/java/com/smartfinance/agent/investment/service/InvestmentAnalysisServiceImpl.java`
- Modify: `backend/src/main/java/com/smartfinance/agent/investment/dto/InvestmentAssetDetailResponse.java`
- Modify: `backend/src/main/java/com/smartfinance/agent/investment/entity/InvestmentAnalysisSnapshot.java`
- Modify: `backend/src/test/java/com/smartfinance/agent/investment/service/AnalysisServiceClientTest.java`
- Modify: `backend/src/test/java/com/smartfinance/agent/investment/service/InvestmentAnalysisSignalTest.java`

**Interfaces:**
- `AnalysisServiceClient.technicalAnalysis(records, ranges, primaryCode)`.
- `AnalysisServiceClient.backtest(records, ranges)`.
- Detail response exposes typed `analysisPreference` with version, inherited/override source, settings and warnings.

- [ ] **Step 1: Write client contract tests with nonstandard codes**

Expect this body exactly:

```json
{
  "records": [{"data_date":"2026-07-14","close":"10.50"}],
  "horizons": {"WAVE":[7,45],"POSITION":[80,260]},
  "primaryHorizon": "WAVE"
}
```

Expect backtest body to contain the same `horizons` mapping and no `horizon_days:20` field.

- [ ] **Step 2: Write service helper tests for dynamic history**

```java
assertThat(InvestmentAnalysisServiceImpl.calendarLookbackDays(20)).isGreaterThan(20);
assertThat(InvestmentAnalysisServiceImpl.calendarLookbackDays(900)).isGreaterThan(900);
assertThat(InvestmentAnalysisServiceImpl.shouldRefreshQuotes(320, 900, false)).isTrue();
assertThat(InvestmentAnalysisServiceImpl.shouldRefreshQuotes(920, 900, false)).isFalse();
```

- [ ] **Step 3: Verify tests fail on fixed 20/650/three-year behavior**

Run: `cd backend; mvn -q -Dtest=AnalysisServiceClientTest,InvestmentAnalysisSignalTest test`

Expected: FAIL because the current client has a single-day backtest contract and the service has fixed quote rules.

- [ ] **Step 4: Integrate `InvestmentHorizonService` into analysis**

Resolve once near the start of `build`. Use that same object to:

- Compute the preference hash from version plus resolved settings.
- Select the quote fetch start date from `requiredHistoryDays()` and `calendarLookbackDays`.
- Pass `analysisRanges()` and `primaryCode()` to technical analysis.
- Pass all ranges to backtest.
- Save `horizonProfileVersion` and serialized resolved configuration on the snapshot.
- Return the typed profile in the detail response.

Remove the old `preference()`, `horizons()`, `preferenceMap()` and fixed backtest `20`. Do not fall back to old fields when the new service is available.

- [ ] **Step 5: Remove the 650-row result truncation**

Retain deterministic date de-duplication, but return all persisted quotes required by the resolved profile. Provider capability is read from `InvestmentHorizonProperties.maxHistoryTradingDays`; if the user asks for more, keep the setting and add an analysis warning rather than rewriting it.

- [ ] **Step 6: Run backend investment tests**

Run: `cd backend; mvn -q -Dtest='com.smartfinance.agent.investment.**' test`

Expected: all investment tests PASS.

- [ ] **Step 7: Commit only integration files**

```powershell
git add -- backend/src/main/java/com/smartfinance/agent/investment/service/AnalysisServiceClient.java backend/src/main/java/com/smartfinance/agent/investment/service/InvestmentAnalysisServiceImpl.java backend/src/main/java/com/smartfinance/agent/investment/dto/InvestmentAssetDetailResponse.java backend/src/main/java/com/smartfinance/agent/investment/entity/InvestmentAnalysisSnapshot.java backend/src/test/java/com/smartfinance/agent/investment/service/AnalysisServiceClientTest.java backend/src/test/java/com/smartfinance/agent/investment/service/InvestmentAnalysisSignalTest.java
git commit -m "feat: apply horizon profiles across analysis"
```

### Task 6: Reusable global and per-asset editor

**Files:**
- Create: `frontend/src/lib/horizonProfile.js`
- Create: `frontend/src/lib/horizonProfile.test.mjs`
- Create: `frontend/src/components/investment/HorizonProfileDialog.vue`
- Modify: `frontend/src/api/investment.js`
- Modify: `frontend/src/views/InvestmentAnalysis.vue`
- Modify: `frontend/src/views/InvestmentAssetDetail.vue`
- Modify: `frontend/test/investmentDetailPage.test.mjs`

**Interfaces:**
- `normalizeHorizonProfile(value)` returns an ordered editable profile with no local interval defaults.
- `validateHorizonSettings(settings)` returns Chinese structural errors only.
- API: `get/updateInvestmentHorizonProfileAPI`, `update/clearInvestmentAssetHorizonOverrideAPI`.

- [ ] **Step 1: Write helper tests before the component**

```javascript
test('保留任意标签、重叠区间和大周期', () => {
  const profile = normalizeHorizonProfile({ settings: [
    { code: 'WAVE', displayName: '我的短期', sortOrder: 20, minHoldingDays: 10, maxHoldingDays: 100 },
    { code: 'SLOW', displayName: '我的长期', sortOrder: 10, minHoldingDays: 50, maxHoldingDays: 900 }
  ] })
  assert.deepEqual(profile.settings.map(item => item.code), ['SLOW', 'WAVE'])
  assert.equal(validateHorizonSettings(profile.settings), '')
})

test('只拒绝重复代码和反向区间', () => {
  assert.match(validateHorizonSettings([
    { code: 'X', displayName: '一', minHoldingDays: 20, maxHoldingDays: 10 },
    { code: 'X', displayName: '二', minHoldingDays: 30, maxHoldingDays: 50 }
  ]), /最小天数|重复/)
})
```

- [ ] **Step 2: Run helper tests and verify failure**

Run: `cd frontend; node --test src/lib/horizonProfile.test.mjs`

Expected: FAIL because the helper does not exist.

- [ ] **Step 3: Implement helper and generic dialog**

The dialog renders `profile.settings` with editable display name, minimum, maximum and primary radio. It does not initialize `5/20/120/500`. It shows inheritance source and profile version, permits overlap, and emits `save` with the generic list.

- [ ] **Step 4: Add global and asset API methods**

```javascript
export const getInvestmentHorizonProfileAPI = () => request.get('/investment/horizon-profile')
export const updateInvestmentHorizonProfileAPI = data => request.put('/investment/horizon-profile', data)
export const updateInvestmentAssetHorizonOverrideAPI = (id, data) => request.put(`/investment/assets/${id}/analysis-preference`, data, { timeout: 60000 })
export const clearInvestmentAssetHorizonOverrideAPI = id => request.delete(`/investment/assets/${id}/analysis-preference`, { timeout: 60000 })
```

- [ ] **Step 5: Replace both page-specific forms**

- `InvestmentAnalysis.vue` adds “周期偏好” beside the page title and edits the global profile.
- `InvestmentAssetDetail.vue` replaces fixed `shortMinDays` fields with `HorizonProfileDialog`, saves an asset override, and offers “恢复全局设置”.
- Horizon buttons and cards come from `analysisPreference.settings`; they are not fixed `SHORT/MEDIUM/LONG` arrays.
- When the primary horizon is insufficient, show the backend reason and no score.

- [ ] **Step 6: Run frontend tests and build**

Run: `cd frontend; node --test src/lib/horizonProfile.test.mjs test/investmentDetailPage.test.mjs`

Expected: PASS.

Run: `cd frontend; npm run build`

Expected: exit 0; existing bundle-size warnings are acceptable, new compile errors are not.

- [ ] **Step 7: Commit only frontend horizon files**

```powershell
git add -- frontend/src/lib/horizonProfile.js frontend/src/lib/horizonProfile.test.mjs frontend/src/components/investment/HorizonProfileDialog.vue frontend/src/api/investment.js frontend/src/views/InvestmentAnalysis.vue frontend/src/views/InvestmentAssetDetail.vue frontend/test/investmentDetailPage.test.mjs
git commit -m "feat: add user horizon profile editor"
```

### Task 7: Cross-layer acceptance and hardcode audit

**Files:**
- Modify only files required to fix failures discovered by the commands below.

**Interfaces:**
- Produces one verified slice where UI, Java, Python, cache and backtest share one profile version and numeric configuration.

- [ ] **Step 1: Run the Python suite**

Run: `cd analysis-service; .\.venv\Scripts\python.exe -m unittest discover -s tests -v`

Expected: all tests PASS.

- [ ] **Step 2: Run backend investment tests**

Run: `cd backend; mvn -q -Dtest='com.smartfinance.agent.investment.**' test`

Expected: 0 failures and 0 errors.

- [ ] **Step 3: Run the full backend suite**

Run: `cd backend; mvn -q test`

Expected: 0 failures and 0 errors.

- [ ] **Step 4: Run frontend tests and production build**

Run: `cd frontend; node --test --test-isolation=none`

Expected: all tests PASS.

Run: `cd frontend; npm run build`

Expected: exit 0.

- [ ] **Step 5: Audit fixed horizon definitions**

Run:

```powershell
rg -n "shortMinDays|shortMaxDays|mediumMinDays|mediumMaxDays|longMinDays|longMaxDays|horizon_days\s*[:=]\s*20|SHORT.*\[5,\s*20\]|MEDIUM.*\[20,\s*120\]|LONG.*\[120,\s*500\]|result\.size\(\) <= 650|minusYears\(3\)" backend/src/main frontend/src analysis-service/app
```

Expected: no runtime matches. Matches are allowed only in V7 legacy migration/history compatibility files and explicit tests that verify legacy migration.

- [ ] **Step 6: Verify repository scope**

Run: `git status --short` and inspect every modified/untracked path. Confirm no unrelated user file was staged or altered by this plan.

- [ ] **Step 7: Commit only acceptance fixes if needed**

Acceptance fixes must be made in the task that owns the failing behavior, followed by that task's exact `git add -- ...` command. If cross-layer fixes touch the three primary integration files, use:

```powershell
git add -- backend/src/main/java/com/smartfinance/agent/investment/service/InvestmentAnalysisServiceImpl.java analysis-service/app/analysis.py frontend/src/lib/horizonProfile.js
git diff --cached --name-only
git commit -m "test: verify configurable horizons end to end"
```

Create the commit only when `git diff --cached --name-only` lists at least one of those files. If no acceptance fixes were required, do not create an empty commit.

---

## Deferred Follow-up Plans

This plan deliberately establishes a trustworthy parameter and version boundary; it does not pretend that the current shared trend score is already a genuine multi-horizon edge. Continue with separate plans in this order:

1. Point-in-time market/fundamental data quality and provider consistency.
2. Horizon-aware factor definitions, factor evaluation and decay measurement.
3. Event-driven portfolio backtest with A-share trading constraints and full costs.
4. Versioned strategy center, Champion-Challenger replacement and rollback.
5. Portfolio/risk veto layer and simulation ledger.
6. Agent research tools, monitoring, explanations and confirmable configuration changes.
