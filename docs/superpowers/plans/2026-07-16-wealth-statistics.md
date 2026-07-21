# Wealth Statistics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将现金基准、日常收支和投资当前市值统一为总资产，并把总资产入口迁移到统计页。

**Architecture:** 后端 `/api/wealth` 是唯一财富汇总数据源，复用现有 `wealth_baseline` 表的非投资余额字段保存现金基准。财务画像负责设置现金基准，统计页负责读取和展示总资产构成，投资详情只保留财富数据产生的警告与数量参考。

**Tech Stack:** Spring Boot 3、MyBatis-Plus、JUnit 5、Mockito、Vue 3、Tailwind、shadcn-vue、Node test、Vite

## Global Constraints

- 股票基金投资资产按当前市值计算。
- 财务画像中的现金不包含投资账户现金。
- 投资入金、出金和买卖只改变资产构成，不能重复改变总资产。
- 财务状况只影响数量参考和财务警告，不能影响技术评分、支撑位或压力位。
- 不新增历史总资产曲线或每日资产快照。
- 保留工作区现有未提交修改，不执行 Git 提交。

---

### Task 1: 将财富接口改为现金基准语义

**Files:**
- Modify: `backend/src/test/java/com/smartfinance/agent/wealth/WealthCalculatorTest.java`
- Modify: `backend/src/test/java/com/smartfinance/agent/wealth/WealthControllerTest.java`
- Modify: `backend/src/main/java/com/smartfinance/agent/wealth/WealthCalculator.java`
- Modify: `backend/src/main/java/com/smartfinance/agent/wealth/dto/WealthBaselineRequest.java`
- Modify: `backend/src/main/java/com/smartfinance/agent/wealth/dto/WealthOverviewResponse.java`
- Modify: `backend/src/main/java/com/smartfinance/agent/wealth/controller/WealthController.java`
- Modify: `backend/src/main/java/com/smartfinance/agent/wealth/service/WealthService.java`

**Interfaces:**
- Consumes: `PUT /api/wealth/baseline` authenticated `userId`.
- Produces: request `{ cashBalance: BigDecimal }` and overview fields `dailyCash`, `cashBaseline`, `cashBaselineAt`.

- [ ] **Step 1: Write failing calculator and controller tests**

```java
assertThat(result.dailyCash()).isEqualByComparingTo("77000");
request.setCashBalance(new BigDecimal("80000"));
verify(service).setBaseline(7L, new BigDecimal("80000"));
```

- [ ] **Step 2: Run focused tests and verify the renamed contract fails**

Run: `mvn -Dtest=WealthCalculatorTest,WealthControllerTest test`

Expected: compilation fails because `dailyCash` and `cashBalance` do not exist yet.

- [ ] **Step 3: Implement the renamed DTO and calculator contract**

```java
public record Result(BigDecimal dailyCash, BigDecimal investmentTotal, BigDecimal totalAssets) {}

@Data
public class WealthBaselineRequest {
    @NotNull @DecimalMin("0")
    private BigDecimal cashBalance;
}
```

`WealthOverviewResponse` must expose `dailyCash`, `cashBaseline`, `cashBaselineAt`; `totalAssets` must be nullable before initialization. Remove API-facing `nonInvestmentBalance`, `enteredTotalAssets`, and `baselineAt`.

- [ ] **Step 4: Run focused tests**

Run: `mvn -Dtest=WealthCalculatorTest,WealthControllerTest test`

Expected: both test classes pass.

### Task 2: 修改财富服务计算和投资警告读取

**Files:**
- Create: `backend/src/test/java/com/smartfinance/agent/wealth/WealthServiceImplTest.java`
- Modify: `backend/src/main/java/com/smartfinance/agent/wealth/service/WealthServiceImpl.java`
- Modify: `backend/src/main/java/com/smartfinance/agent/investment/service/InvestmentAnalysisServiceImpl.java`

**Interfaces:**
- Consumes: `WealthCalculator.calculate(cashBaseline, income, expense, netInvestmentTransfer, investmentCash, holdingMarketValue)`.
- Produces: initialized and uninitialized `WealthOverviewResponse` values matching Task 1.

- [ ] **Step 1: Add service tests for baseline and uninitialized behavior**

```java
assertThat(service.overview(7L).getTotalAssets()).isNull();
assertThat(service.overview(7L).getInvestmentTotal()).isEqualByComparingTo("25000");

service.setBaseline(7L, new BigDecimal("80000"));
verify(baselineMapper).insert(argThat(value ->
        value.getBaselineNonInvestmentBalance().compareTo(new BigDecimal("80000")) == 0));
```

- [ ] **Step 2: Run the service test and verify old semantics fail**

Run: `mvn -Dtest=WealthServiceImplTest test`

Expected: failure because the old implementation subtracts investment assets and populates total assets before initialization.

- [ ] **Step 3: Implement cash-baseline semantics**

```java
baseline.setEnteredTotalAssets(cashBalance); // compatibility column
baseline.setBaselineNonInvestmentBalance(cashBalance);
baseline.setBaselineAt(LocalDateTime.now());
```

For initialized users set `dailyCash`, `cashBaseline`, `cashBaselineAt` and calculated `totalAssets`. For uninitialized users leave `totalAssets` and `dailyCash` null while still returning investment breakdown. Update reserve warning code to read `wealth.getDailyCash()`.

- [ ] **Step 4: Run wealth and investment tests**

Run: `mvn -Dtest=WealthCalculatorTest,WealthControllerTest,WealthServiceImplTest,InvestmentAnalysisServiceImplTest test`

Expected: all selected tests pass.

### Task 3: 移除投资详情页的总资产入口

**Files:**
- Modify: `frontend/test/investmentDetailPage.test.mjs`
- Modify: `frontend/src/views/InvestmentAssetDetail.vue`
- Create: `frontend/src/api/wealth.js`
- Modify: `frontend/src/api/investment.js`

**Interfaces:**
- Consumes: investment detail API only.
- Produces: wealth API functions isolated in `@/api/wealth` for profile and statistics pages.

- [ ] **Step 1: Add a source regression assertion**

```js
assert.doesNotMatch(pageSource, /openWealthDialog|wealthOpen|updateWealthBaselineAPI/)
```

- [ ] **Step 2: Run the page test and verify it fails**

Run: `node --test test/investmentDetailPage.test.mjs`

Expected: failure because the old total-assets dialog remains.

- [ ] **Step 3: Remove the button, dialog, state and API calls**

Delete the `总资产` button, wealth dialog, wealth refs, `openWealthDialog`, `saveWealth`, `WalletCards` import, and wealth request from `loadAll`. Move these exports to `src/api/wealth.js`:

```js
export const getWealthOverviewAPI = () => request.get('/wealth/overview')
export const updateWealthBaselineAPI = cashBalance =>
  request.put('/wealth/baseline', { cashBalance })
```

- [ ] **Step 4: Run the page test**

Run: `node --test test/investmentDetailPage.test.mjs`

Expected: all investment detail tests pass.

### Task 4: 在财务画像维护现金基准

**Files:**
- Create: `frontend/src/lib/wealthProfile.js`
- Create: `frontend/src/lib/wealthProfile.test.mjs`
- Modify: `frontend/src/views/FinancialProfile.vue`

**Interfaces:**
- Consumes: `getWealthOverviewAPI()` and `updateWealthBaselineAPI(cashBalance)` from Task 3.
- Produces: `hasCashBaselineChanged(initialized, original, current)` for safe save decisions.

- [ ] **Step 1: Write helper tests**

```js
assert.equal(hasCashBaselineChanged(false, null, '0'), true)
assert.equal(hasCashBaselineChanged(true, 80000, '80000'), false)
assert.equal(hasCashBaselineChanged(true, 80000, '82000'), true)
```

- [ ] **Step 2: Run the helper test and verify it fails**

Run: `node --test src/lib/wealthProfile.test.mjs`

Expected: failure because `wealthProfile.js` does not exist.

- [ ] **Step 3: Implement the helper and profile UI**

```js
export function hasCashBaselineChanged(initialized, original, current) {
  if (current === '' || current == null) return false
  if (!initialized) return true
  return Number(original) !== Number(current)
}
```

Add “当前现金余额” to the basic-information grid with copy “不包含投资账户余额”. Load wealth in the existing `Promise.all`. Save the baseline only when the helper returns true; changing other profile fields must not reset the cash baseline time.

- [ ] **Step 4: Run the helper test**

Run: `node --test src/lib/wealthProfile.test.mjs`

Expected: all helper tests pass.

### Task 5: 统计页展示统一总资产及构成

**Files:**
- Create: `frontend/src/lib/wealthStatistics.js`
- Create: `frontend/src/lib/wealthStatistics.test.mjs`
- Modify: `frontend/src/views/statistics/DailyStats.vue`

**Interfaces:**
- Consumes: wealth overview contract from Task 1 and seven-day transaction summary.
- Produces: display-ready asset cards and asset-composition rows without recomputing total assets in the browser.

- [ ] **Step 1: Write view-model tests**

```js
const model = buildWealthStatistics({ initialized: true, totalAssets: 102000, dailyCash: 77000,
  investmentTotal: 25000, investmentCash: 3000, holdingMarketValue: 22000 }, 12000, 5000, 9)
assert.equal(model.cards[0].value, 102000)
assert.equal(model.cards[3].value, 7000)
assert.equal(model.composition[2].value, 22000)
assert.equal(buildWealthStatistics({ initialized: false, investmentTotal: 25000 }, 0, 0, 0).cards[0].value, null)
```

- [ ] **Step 2: Run the view-model test and verify it fails**

Run: `node --test src/lib/wealthStatistics.test.mjs`

Expected: failure because `wealthStatistics.js` does not exist.

- [ ] **Step 3: Implement the view model and statistics layout**

Fetch transactions and wealth with `Promise.allSettled`. The four primary cards are total assets, daily cash, investment assets, and period balance. Add composition rows for daily cash, investment account cash, holding market value, period income, period expense, and transaction count. Uninitialized total assets renders “待初始化” and a link to `/profile`.

- [ ] **Step 4: Run frontend tests and build**

Run: `node --test src/lib/wealthStatistics.test.mjs src/lib/wealthProfile.test.mjs test/investmentDetailPage.test.mjs`

Expected: all selected tests pass.

Run: `npm run build`

Expected: Vite build exits with code 0.

### Task 6: 全量回归验证

**Files:**
- Verify only; do not modify unrelated files.

**Interfaces:**
- Consumes: completed backend and frontend changes.
- Produces: verification evidence for the user.

- [ ] **Step 1: Run the backend focused suite**

Run: `mvn -Dtest=WealthCalculatorTest,WealthControllerTest,WealthServiceImplTest,InvestmentAnalysisServiceImplTest test`

Expected: selected tests pass.

- [ ] **Step 2: Run all frontend Node tests**

Run: `node --test "src/**/*.test.mjs" "test/**/*.test.mjs"`

Expected: all discovered tests pass.

- [ ] **Step 3: Build the frontend**

Run: `npm run build`

Expected: Vite build exits with code 0 and produces `dist` assets.

- [ ] **Step 4: Inspect the final diff scope**

Run: `git status --short`

Expected: only the planned wealth, profile, statistics, investment-detail tests/docs are newly changed by this task; all pre-existing unrelated modifications remain untouched.
