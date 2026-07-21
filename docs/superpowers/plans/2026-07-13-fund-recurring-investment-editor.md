# Fund Recurring Investment Editor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在每个基金资产的编辑抽屉中提供一条可新增、修改、启停和删除的定投计划。

**Architecture:** 使用资产级 REST 接口将计划绑定到现有 `accountId + productId`，后端负责权限、基金类型校验和下次执行日期计算。前端把定投表单拆成独立组件与独立草稿状态，仅由基金编辑抽屉加载，避免实时行情轮询覆盖输入。

**Tech Stack:** Spring Boot 3.2.5、MyBatis-Plus、Jakarta Validation、JUnit 5、Vue 3、RUI/shadcn 组件、Node Test Runner。

## Global Constraints

- 仅基金显示定投功能，股票调用资产定投接口必须返回业务错误。
- 同一用户、账户和产品只保留一条计划。
- 金额使用 `BigDecimal`，必须大于 0。
- 频率只支持 `WEEKLY` 和 `MONTHLY`；执行日范围分别为 1-7 和 1-28。
- 下次执行日期由后端计算，前端不得提交该字段。
- 计划不自动扣款、下单、生成交易流水或修改持仓。
- 删除计划不影响资产和持仓；删除基金资产时同步删除计划。
- 保留现有 `/api/investment/plans` 接口兼容性。

---

### Task 1: 定投日期规则与请求模型

**Files:**
- Create: `backend/src/main/java/com/smartfinance/agent/investment/domain/InvestmentPlanDates.java`
- Create: `backend/src/main/java/com/smartfinance/agent/investment/dto/InvestmentAssetPlanRequest.java`
- Create: `backend/src/test/java/com/smartfinance/agent/investment/domain/InvestmentPlanDatesTest.java`

**Interfaces:**
- Produces: `InvestmentPlanDates.next(LocalDate today, String frequency, int executionDay): LocalDate`
- Produces: `InvestmentAssetPlanRequest` with `BigDecimal amount`, `String frequency`, `Integer executionDay`, `Boolean enabled`

- [ ] **Step 1: Write the failing date-rule test**

```java
@Test
void next_shouldChooseCurrentOrNextWeeklyAndMonthlyOccurrence() {
    LocalDate monday = LocalDate.of(2026, 7, 13);
    assertThat(InvestmentPlanDates.next(monday, "WEEKLY", 1)).isEqualTo(monday);
    assertThat(InvestmentPlanDates.next(monday, "WEEKLY", 5)).isEqualTo(LocalDate.of(2026, 7, 17));
    assertThat(InvestmentPlanDates.next(monday, "WEEKLY", 7)).isEqualTo(LocalDate.of(2026, 7, 19));
    assertThat(InvestmentPlanDates.next(LocalDate.of(2026, 7, 20), "MONTHLY", 15))
            .isEqualTo(LocalDate.of(2026, 8, 15));
    assertThat(InvestmentPlanDates.next(LocalDate.of(2026, 7, 10), "MONTHLY", 15))
            .isEqualTo(LocalDate.of(2026, 7, 15));
}
```

- [ ] **Step 2: Run the test and verify RED**

Run: `cd backend; mvn -q -Dtest=InvestmentPlanDatesTest test`

Expected: compilation failure because `InvestmentPlanDates` does not exist.

- [ ] **Step 3: Implement validation and date calculation**

```java
public final class InvestmentPlanDates {
    private InvestmentPlanDates() {}

    public static LocalDate next(LocalDate today, String frequency, int executionDay) {
        String normalized = frequency == null ? "" : frequency.trim().toUpperCase(Locale.ROOT);
        if ("WEEKLY".equals(normalized)) {
            if (executionDay < 1 || executionDay > 7) throw new IllegalArgumentException("每周执行日必须在 1 至 7 之间");
            int offset = Math.floorMod(executionDay - today.getDayOfWeek().getValue(), 7);
            return today.plusDays(offset);
        }
        if ("MONTHLY".equals(normalized)) {
            if (executionDay < 1 || executionDay > 28) throw new IllegalArgumentException("每月执行日必须在 1 至 28 之间");
            LocalDate candidate = today.withDayOfMonth(executionDay);
            return candidate.isBefore(today) ? today.plusMonths(1).withDayOfMonth(executionDay) : candidate;
        }
        throw new IllegalArgumentException("定投频率仅支持 WEEKLY 或 MONTHLY");
    }
}
```

`InvestmentAssetPlanRequest` 使用 `@NotNull`、`@DecimalMin(value = "0", inclusive = false)` 和 `@NotBlank`，不包含账户、产品或日期字段。

- [ ] **Step 4: Run the test and verify GREEN**

Run: `cd backend; mvn -q -Dtest=InvestmentPlanDatesTest test`

Expected: `InvestmentPlanDatesTest` passes.

- [ ] **Step 5: Commit the task**

```powershell
git add backend/src/main/java/com/smartfinance/agent/investment/domain/InvestmentPlanDates.java backend/src/main/java/com/smartfinance/agent/investment/dto/InvestmentAssetPlanRequest.java backend/src/test/java/com/smartfinance/agent/investment/domain/InvestmentPlanDatesTest.java
git commit -m "feat: add recurring investment date rules"
```

### Task 2: 资产级定投 CRUD 与删除联动

**Files:**
- Modify: `backend/src/main/java/com/smartfinance/agent/investment/service/InvestmentAssetService.java`
- Modify: `backend/src/main/java/com/smartfinance/agent/investment/service/InvestmentAssetServiceImpl.java`
- Modify: `backend/src/main/java/com/smartfinance/agent/investment/controller/InvestmentAssetController.java`
- Modify: `backend/src/main/java/com/smartfinance/agent/investment/service/InvestmentServiceImpl.java`
- Modify: `backend/src/test/java/com/smartfinance/agent/investment/service/InvestmentAssetServiceIntegrationTest.java`
- Modify: `backend/src/test/java/com/smartfinance/agent/investment/controller/InvestmentAssetControllerTest.java`

**Interfaces:**
- Consumes: `InvestmentPlanDates.next(...)` and `InvestmentAssetPlanRequest`
- Produces: `InvestmentAssetService.getPlan(userId, assetId)` returning nullable `InvestmentPlan`
- Produces: `InvestmentAssetService.savePlan(userId, assetId, request)` returning `InvestmentPlan`
- Produces: `InvestmentAssetService.deletePlan(userId, assetId)` returning `void`

- [ ] **Step 1: Write failing service integration tests**

Add a fund resolver stub for code `010736`, create a fund asset, then assert:

```java
var created = assetService.savePlan(7L, fund.getId(), planRequest("500", "MONTHLY", 15, true));
assertThat(created.getAccountId()).isEqualTo(fund.getAccountId());
assertThat(created.getProductId()).isEqualTo(fund.getProductId());
assertThat(assetService.getPlan(7L, fund.getId()).getId()).isEqualTo(created.getId());

var updated = assetService.savePlan(7L, fund.getId(), planRequest("800", "WEEKLY", 5, false));
assertThat(updated.getId()).isEqualTo(created.getId());
assertThat(updated.getAmount()).isEqualByComparingTo("800");
assertThat(updated.getEnabled()).isZero();

assertThatThrownBy(() -> assetService.getPlan(8L, fund.getId()))
        .isInstanceOf(IllegalArgumentException.class);
assertThatThrownBy(() -> assetService.savePlan(7L, stock.getId(), planRequest("100", "MONTHLY", 10, true)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("基金");
```

Add deletion assertions: `deletePlan` makes `getPlan` return `null`; deleting the asset also removes the plan while leaving no new investment transaction.

- [ ] **Step 2: Run service tests and verify RED**

Run: `cd backend; mvn -q -Dtest=InvestmentAssetServiceIntegrationTest test`

Expected: compilation failure for the three missing service methods.

- [ ] **Step 3: Implement asset-scoped plan lookup and upsert**

Inject `InvestmentPlanMapper` into `InvestmentAssetServiceImpl`. Every plan method must first call `requireAsset(userId, assetId)`, load its product, and require `MUTUAL_FUND`. Query plans by `userId + accountId + productId` and `LIMIT 1`.

`savePlan` must update the existing row instead of inserting another one:

```java
InvestmentPlan plan = findPlan(userId, asset);
boolean wasEnabled = plan != null && Integer.valueOf(1).equals(plan.getEnabled());
if (plan == null) {
    plan = new InvestmentPlan();
    plan.setUserId(userId);
    plan.setAccountId(asset.getAccountId());
    plan.setProductId(asset.getProductId());
    plan.setCreatedAt(LocalDateTime.now());
}
String frequency = request.getFrequency().trim().toUpperCase(Locale.ROOT);
boolean scheduleChanged = !Objects.equals(plan.getFrequency(), frequency)
        || !Objects.equals(plan.getExecutionDay(), request.getExecutionDay());
plan.setAmount(request.getAmount());
plan.setCurrency(product.getCurrency());
plan.setFrequency(frequency);
plan.setExecutionDay(request.getExecutionDay());
plan.setEnabled(Boolean.TRUE.equals(request.getEnabled()) ? 1 : 0);
if (plan.getId() == null || scheduleChanged || (!wasEnabled && plan.getEnabled() == 1)) {
    plan.setNextExecutionDate(InvestmentPlanDates.next(LocalDate.now(), frequency, request.getExecutionDay()));
}
plan.setUpdatedAt(LocalDateTime.now());
if (plan.getId() == null) planMapper.insert(plan); else planMapper.updateById(plan);
return plan;
```

`deletePlan` deletes only the matched plan. Existing asset `delete` calls the same internal delete before reversal and soft deletion. Existing generic `createPlan` checks for an existing row with the same user/account/product and throws `该基金已有定投计划`.

- [ ] **Step 4: Add controller routes and delegation tests**

```java
@GetMapping("/{id}/plan")
public Result<InvestmentPlan> getPlan(@RequestAttribute Long userId, @PathVariable Long id) {
    return Result.success(assetService.getPlan(userId, id));
}

@PutMapping("/{id}/plan")
public Result<InvestmentPlan> savePlan(@RequestAttribute Long userId, @PathVariable Long id,
                                       @Valid @RequestBody InvestmentAssetPlanRequest request) {
    return Result.success(assetService.savePlan(userId, id, request));
}

@DeleteMapping("/{id}/plan")
public Result<Void> deletePlan(@RequestAttribute Long userId, @PathVariable Long id) {
    assetService.deletePlan(userId, id);
    return Result.success();
}
```

Controller tests verify exact authenticated `userId` and `assetId` delegation for all three routes.

- [ ] **Step 5: Run backend investment tests and verify GREEN**

Run: `cd backend; mvn -q -Dtest=InvestmentPlanDatesTest,InvestmentAssetServiceIntegrationTest,InvestmentAssetControllerTest,InvestmentServiceIntegrationTest test`

Expected: all selected tests pass with zero failures.

- [ ] **Step 6: Commit the task**

```powershell
git add backend/src/main/java/com/smartfinance/agent/investment backend/src/test/java/com/smartfinance/agent/investment
git commit -m "feat: add asset scoped recurring investment plans"
```

### Task 3: 前端定投 API 与表单状态

**Files:**
- Modify: `frontend/src/api/investment.js`
- Create: `frontend/src/lib/investmentPlanForm.js`
- Create: `frontend/src/lib/investmentPlanForm.test.mjs`

**Interfaces:**
- Produces: `getInvestmentAssetPlanAPI(id)`, `saveInvestmentAssetPlanAPI(id, data)`, `deleteInvestmentAssetPlanAPI(id)`
- Produces: `createInvestmentPlanForm()` and `syncInvestmentPlanForm(form, plan)`

- [ ] **Step 1: Write failing form-state tests**

```javascript
test('empty plan uses a simple enabled monthly default', () => {
  const form = createInvestmentPlanForm()
  syncInvestmentPlanForm(form, null)
  assert.deepEqual(form, { amount: '', frequency: 'MONTHLY', executionDay: 1, enabled: true })
})

test('weekly plan keeps backend values and exposes seven execution days', () => {
  const form = createInvestmentPlanForm()
  syncInvestmentPlanForm(form, { amount: 500, frequency: 'WEEKLY', executionDay: 5, enabled: 0 })
  assert.equal(form.amount, '500')
  assert.equal(form.enabled, false)
  assert.equal(executionDayOptions('WEEKLY').length, 7)
  assert.equal(executionDayOptions('MONTHLY').length, 28)
})
```

- [ ] **Step 2: Run the test and verify RED**

Run: `cd frontend; node --test --test-isolation=none src/lib/investmentPlanForm.test.mjs`

Expected: module-not-found failure for `investmentPlanForm.js`.

- [ ] **Step 3: Implement API wrappers and pure form helpers**

```javascript
export const getInvestmentAssetPlanAPI = id => request.get(`/investment/assets/${id}/plan`)
export const saveInvestmentAssetPlanAPI = (id, data) => request.put(`/investment/assets/${id}/plan`, data)
export const deleteInvestmentAssetPlanAPI = id => request.delete(`/investment/assets/${id}/plan`)
```

The helper must reset frequency-dependent invalid days to `1`, serialize amount with `Number(form.amount)`, and never include account, product, or next-execution date in the payload.

- [ ] **Step 4: Run the helper tests and verify GREEN**

Run: `cd frontend; node --test --test-isolation=none src/lib/investmentPlanForm.test.mjs`

Expected: all form-state tests pass.

- [ ] **Step 5: Commit the task**

```powershell
git add frontend/src/api/investment.js frontend/src/lib/investmentPlanForm.js frontend/src/lib/investmentPlanForm.test.mjs
git commit -m "feat: add recurring investment form state"
```

### Task 4: 基金编辑抽屉定投卡片

**Files:**
- Create: `frontend/src/components/investment/InvestmentPlanEditor.vue`
- Create: `frontend/src/components/investment/InvestmentPlanEditor.test.mjs`
- Modify: `frontend/src/components/investment/InvestmentAssetDrawer.vue`

**Interfaces:**
- Consumes: Task 3 API wrappers and form helpers
- Produces: `<InvestmentPlanEditor :asset="asset" />` rendered only for `MUTUAL_FUND`

- [ ] **Step 1: Write the failing component contract test**

Read the two Vue files as UTF-8 and assert:

```javascript
assert.match(drawerSource, /asset\.productType === 'MUTUAL_FUND'/)
assert.match(drawerSource, /<InvestmentPlanEditor :asset="asset"/)
assert.match(editorSource, /定投金额/)
assert.match(editorSource, /每周|WEEKLY/)
assert.match(editorSource, /每月|MONTHLY/)
assert.match(editorSource, /下次执行/)
assert.match(editorSource, /删除定投计划/)
assert.match(editorSource, /不会自动买入/)
```

- [ ] **Step 2: Run the contract test and verify RED**

Run: `cd frontend; node --test --test-isolation=none src/components/investment/InvestmentPlanEditor.test.mjs`

Expected: file-not-found failure for `InvestmentPlanEditor.vue`.

- [ ] **Step 3: Implement the editor component**

Use the existing `Input`, `Select`, `Switch`, `Button` and `Dialog` components. Load the plan when `asset.id` changes, keep `loading/saving/deleting` states, and show:

- amount input;
- frequency select;
- frequency-dependent execution-day select;
- enabled switch;
- read-only next-execution date when a plan exists;
- primary save button;
- destructive delete button and confirmation dialog only when a plan exists;
- compact copy `仅记录计划，不会自动买入`.

On successful save or delete, update local plan state and use `feedback.success` without closing the asset drawer.

- [ ] **Step 4: Mount only for funds**

In `InvestmentAssetDrawer.vue`, place the editor below “编辑持仓”:

```vue
<InvestmentPlanEditor
  v-if="asset.productType === 'MUTUAL_FUND'"
  :asset="asset"
/>
```

Do not add any route, top-level tab, or table action button.

- [ ] **Step 5: Run frontend tests and build**

Run: `cd frontend; node --test --test-isolation=none`

Expected: all frontend tests pass.

Run: `cd frontend; npm run build`

Expected: Vite exits with code 0; existing Rolldown annotation and chunk-size warnings may remain.

- [ ] **Step 6: Run browser acceptance on `/stocks`**

Verify a fund drawer shows one plan form, a stock drawer shows no plan form, save/update does not close the drawer, deletion requires confirmation, and each request targets the current authenticated asset.

- [ ] **Step 7: Commit the task**

```powershell
git add frontend/src/components/investment/InvestmentPlanEditor.vue frontend/src/components/investment/InvestmentPlanEditor.test.mjs frontend/src/components/investment/InvestmentAssetDrawer.vue
git commit -m "feat: add fund recurring investment editor"
```

### Task 5: Final regression verification

**Files:**
- Verify only; no expected source changes.

**Interfaces:**
- Consumes: Tasks 1-4 complete feature.
- Produces: verified backend/frontend/browser evidence.

- [ ] **Step 1: Run backend regression tests**

Run: `cd backend; mvn -q -Dtest=InvestmentPlanDatesTest,InvestmentAssetServiceIntegrationTest,InvestmentAssetControllerTest,InvestmentServiceIntegrationTest,InvestmentSqliteMigrationTest test`

Expected: all selected tests pass with zero failures.

- [ ] **Step 2: Run frontend regression tests**

Run: `cd frontend; node --test --test-isolation=none`

Expected: all frontend tests pass with zero failures.

- [ ] **Step 3: Run production build**

Run: `cd frontend; npm run build`

Expected: exit code 0.

- [ ] **Step 4: Inspect final diff**

Run: `git diff --check`

Expected: no whitespace errors. Confirm no new route, no automatic transaction creation, and no unrelated files are staged.
