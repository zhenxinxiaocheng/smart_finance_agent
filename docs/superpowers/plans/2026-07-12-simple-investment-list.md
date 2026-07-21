# Simple Investment List Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the ledger-first `/stocks` experience with a code-first stock/fund list that supports resolve, add, query, edit, delete, detail, and single-asset refresh.

**Architecture:** Add a user-owned `investment_asset` aggregate above the existing immutable ledger. Python resolves product metadata and market data without database access; Java owns assets, creates a private default account, and translates quantity/cost edits into reversible `TRANSFER_IN` ledger records. Vue renders one add bar, one asset table, and a detail drawer while moving ledger/import tools behind an advanced section.

**Tech Stack:** Vue 3, Vite, shadcn-vue components, Spring Boot 3.2.5, MyBatis-Plus, Flyway 12.10.0, MySQL, SQLite, FastAPI, AKShare, JUnit 5, unittest.

## Global Constraints

- The primary user action requires only asset type and code.
- Quantity and average cost are optional until the user edits the added asset.
- Java remains the only business-database writer.
- Python never connects to MySQL or SQLite.
- Existing investment transactions remain immutable; edits create reversal and replacement records.
- The first version uses daily prices and fund NAV, not real-time quotes.
- A failed provider response must display its source and error instead of a fabricated price.
- Do not create additional routes or sidebar entries.
- Do not commit or stage files unless the user explicitly authorizes Git operations.

---

### Task 1: Product metadata resolver

**Files:**
- Modify: `analysis-service/app/providers.py`
- Modify: `analysis-service/app/main.py`
- Modify: `analysis-service/tests/test_provider_normalization.py`
- Modify: `backend/src/main/java/com/smartfinance/agent/investment/service/AnalysisServiceClient.java`
- Test: `backend/src/test/java/com/smartfinance/agent/investment/service/AnalysisServiceClientTest.java`

**Interfaces:**
- Produces Python `POST /internal/v1/products/resolve` with `{product_type, code}`.
- Produces Java `ResolvedProduct resolveProduct(String productType, String code)`.
- Response fields: `productType`, `code`, `name`, `market`, `currency`, `provider`, `dataDate`, `latestPrice`, `warnings`.

- [ ] **Step 1: Write failing Python resolver tests**

```python
def test_infer_a_share_market():
    assert infer_a_share_market("600519") == "SSE"
    assert infer_a_share_market("000001") == "SZSE"
    assert infer_a_share_market("920001") == "BSE"

def test_normalize_resolved_product():
    value = normalize_resolved_product("STOCK", "600519", "贵州茅台", "SSE", "CNY", "AKSHARE")
    assert value["code"] == "600519"
    assert value["name"] == "贵州茅台"
```

- [ ] **Step 2: Run the Python tests and verify the missing resolver failure**

Run: `.venv\Scripts\python.exe -m unittest discover -s tests -v`

Expected: FAIL because `infer_a_share_market` and `normalize_resolved_product` are absent.

- [ ] **Step 3: Implement deterministic market inference and AKShare lookup**

```python
def infer_a_share_market(code: str) -> str:
    if code.startswith(("6", "5")):
        return "SSE"
    if code.startswith(("8", "4", "9")):
        return "BSE"
    return "SZSE"
```

Use `ak.stock_individual_info_em(symbol=code)` for A-share names and `ak.fund_individual_basic_info_xq(symbol=code)` with `ak.fund_open_fund_info_em(...)` for domestic fund metadata/NAV. Convert upstream exceptions to `ProviderUnavailable` including provider name.

- [ ] **Step 4: Expose the authenticated FastAPI endpoint**

```python
class ProductResolveRequest(BaseModel):
    product_type: str
    code: str

@app.post("/internal/v1/products/resolve", dependencies=[Depends(internal_auth)])
def resolve_product(request: ProductResolveRequest):
    return resolve_product_metadata(request.product_type, request.code)
```

- [ ] **Step 5: Add and test the Java client mapping**

Create a `ResolvedProduct` record in `AnalysisServiceClient` and map the response without `double` monetary fields:

```java
public record ResolvedProduct(String productType, String code, String name, String market,
                              String currency, String provider, LocalDate dataDate,
                              BigDecimal latestPrice, List<String> warnings) {}
```

Run: `mvn -q -Dtest=AnalysisServiceClientTest test`

Expected: PASS with an exact request body and mapped `BigDecimal` price.

---

### Task 2: User asset aggregate and migrations

**Files:**
- Create: `backend/src/main/java/com/smartfinance/agent/investment/entity/InvestmentAsset.java`
- Create: `backend/src/main/java/com/smartfinance/agent/investment/mapper/InvestmentAssetMapper.java`
- Create: `backend/src/main/java/com/smartfinance/agent/investment/dto/InvestmentAssetCreateRequest.java`
- Create: `backend/src/main/java/com/smartfinance/agent/investment/dto/InvestmentAssetUpdateRequest.java`
- Create: `backend/src/main/java/com/smartfinance/agent/investment/dto/InvestmentAssetView.java`
- Create: `backend/src/main/resources/db/migration/mysql/V5__investment_asset.sql`
- Create: `backend/src/main/resources/db/migration/sqlite/V5__investment_asset.sql`
- Modify: `backend/src/main/resources/schema.sql`
- Modify: `backend/src/test/resources/schema-h2.sql`
- Test: `backend/src/test/java/com/smartfinance/agent/investment/InvestmentSqliteMigrationTest.java`

**Interfaces:**
- `investment_asset` uniquely identifies `(user_id, product_id)` for active rows.
- Nullable holding fields: `quantity`, `average_cost`, `note`.
- State fields: `sync_status`, `sync_error`, `current_transaction_id`, `deleted`.

- [ ] **Step 1: Extend the SQLite migration test to require V5**

```java
assertThat(tableNames(connection)).contains("investment_asset");
assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("5");
```

- [ ] **Step 2: Run the migration test and verify failure**

Run: `mvn -q -Dtest=InvestmentSqliteMigrationTest test`

Expected: FAIL because `investment_asset` does not exist.

- [ ] **Step 3: Add equivalent MySQL, SQLite, and H2 schemas**

Use these logical columns in every dialect:

```sql
id, user_id, account_id, product_id, quantity, average_cost, note,
current_transaction_id, sync_status, sync_error, created_at, updated_at, deleted
```

Add an active-user/product lookup index and preserve soft deletion.

- [ ] **Step 4: Add entity, mapper, request, and view types**

`InvestmentAssetView` includes resolved product fields plus calculated position fields, so the frontend never joins products and positions itself.

- [ ] **Step 5: Run migration verification**

Run: `mvn -q -Dtest=InvestmentSqliteMigrationTest test`

Expected: PASS and Flyway current version `5`.

---

### Task 3: Asset CRUD translated to immutable ledger

**Files:**
- Create: `backend/src/main/java/com/smartfinance/agent/investment/service/InvestmentAssetService.java`
- Create: `backend/src/main/java/com/smartfinance/agent/investment/service/InvestmentAssetServiceImpl.java`
- Create: `backend/src/main/java/com/smartfinance/agent/investment/controller/InvestmentAssetController.java`
- Test: `backend/src/test/java/com/smartfinance/agent/investment/service/InvestmentAssetServiceIntegrationTest.java`
- Modify: `backend/src/test/java/com/smartfinance/agent/service/ServiceIntegrationTestConfig.java`

**Interfaces:**
- `resolve(userId, productType, code)` returns metadata only and writes nothing.
- `create(userId, request)` creates a default private account when absent and adds an asset with optional holding values.
- `update(userId, assetId, request)` reverses the previous synthetic transaction and creates a replacement `TRANSFER_IN` transaction.
- `delete(userId, assetId)` reverses the active synthetic transaction and soft-deletes the asset.
- `list(userId)` and `get(userId, assetId)` always enforce ownership.

- [ ] **Step 1: Write failing integration tests for code-only add and ownership**

```java
var asset = service.create(7L, request("STOCK", "600519", null, null));
assertThat(asset.getName()).isEqualTo("贵州茅台");
assertThat(asset.getQuantity()).isNull();
assertThatThrownBy(() -> service.get(8L, asset.getId())).isInstanceOf(IllegalArgumentException.class);
```

- [ ] **Step 2: Write failing tests for edit and delete ledger behavior**

```java
service.update(7L, asset.getId(), update("10", "1500"));
service.update(7L, asset.getId(), update("8", "1480"));
assertThat(transactions(7L)).extracting("eventType")
    .containsExactly("TRANSFER_IN", "REVERSAL", "TRANSFER_IN");
service.delete(7L, asset.getId());
assertThat(service.list(7L)).isEmpty();
```

- [ ] **Step 3: Implement default account and code-only creation**

Create one user-owned account named `默认投资账户` with type `VIRTUAL` only when the user has no simplified asset account. Resolve the product through `AnalysisServiceClient`, upsert `investment_product`, then insert `investment_asset`.

- [ ] **Step 4: Implement edit using reversal and replacement**

For a non-null target quantity greater than zero, create:

```java
TRANSFER_IN quantity=targetQuantity price=targetAverageCost fee=0 source=ASSET_CRUD
```

Reverse `current_transaction_id` before replacement. Set the new transaction id on the asset in the same database transaction.

- [ ] **Step 5: Implement soft delete and ownership checks**

Delete must reverse the current synthetic transaction, set `deleted=1`, and retain product, transactions, and quotes.

- [ ] **Step 6: Expose REST endpoints**

```text
POST   /api/investment/assets/resolve
POST   /api/investment/assets
GET    /api/investment/assets
GET    /api/investment/assets/{id}
PUT    /api/investment/assets/{id}
DELETE /api/investment/assets/{id}
POST   /api/investment/assets/{id}/sync
```

- [ ] **Step 7: Run service and controller tests**

Run: `mvn -q -Dtest=InvestmentAssetServiceIntegrationTest,InvestmentControllerTest test`

Expected: PASS for code-only add, edit, delete, duplicate prevention, and cross-user rejection.

---

### Task 4: Replace `/stocks` with a mainstream list layout

**Files:**
- Create: `frontend/src/components/investment/InvestmentAddBar.vue`
- Create: `frontend/src/components/investment/InvestmentAssetTable.vue`
- Create: `frontend/src/components/investment/InvestmentAssetDrawer.vue`
- Modify: `frontend/src/views/InvestmentAnalysis.vue`
- Modify: `frontend/src/api/investment.js`

**Interfaces:**
- Add bar emits `added` after resolve confirmation and asset creation.
- Table emits `select`, `edit`, `remove`, and `sync` with asset id.
- Drawer receives `asset`, supports quantity/cost/note editing, and emits `saved`.

- [ ] **Step 1: Add frontend API functions**

```javascript
export const resolveInvestmentAssetAPI = data => request.post('/investment/assets/resolve', data)
export const createInvestmentAssetAPI = data => request.post('/investment/assets', data)
export const listInvestmentAssetsAPI = () => request.get('/investment/assets')
export const updateInvestmentAssetAPI = (id, data) => request.put(`/investment/assets/${id}`, data)
export const deleteInvestmentAssetAPI = id => request.delete(`/investment/assets/${id}`)
export const syncInvestmentAssetAPI = id => request.post(`/investment/assets/${id}/sync`)
```

- [ ] **Step 2: Build the add bar with only type and code**

The default type is `STOCK`. Normalize code to uppercase, disable submit while resolving, show the resolved name before final creation, and display provider errors inline.

- [ ] **Step 3: Build the primary asset table**

Columns: asset, latest price, daily data date, quantity, average cost, market value, profit/loss, status, actions. Empty state copy: `输入股票或基金代码开始添加`.

- [ ] **Step 4: Build the detail/edit drawer**

Only editable fields are quantity, average cost, and note. Advanced information shows market, currency, provider status, sync error, and internal transactions without exposing event-type inputs.

- [ ] **Step 5: Reduce the page hierarchy**

Remove summary-card dominance and the large left-side tab panel. Keep compact total asset/profit figures above the list. Put CSV import and raw ledger inside a collapsed `高级功能` section below the list.

- [ ] **Step 6: Build the frontend**

Run: `npm run build`

Expected: Vite exits `0`; `/stocks` remains a lazily loaded single route.

---

### Task 5: End-to-end verification and running services

**Files:**
- Modify only files required by failures found in this task.

**Interfaces:**
- Verifies the complete browser → Java → Python → provider → Java database flow.

- [ ] **Step 1: Run Python tests**

Run: `.venv\Scripts\python.exe -m unittest discover -s tests -v`

Expected: all resolver, normalization, and provider fallback tests pass.

- [ ] **Step 2: Run complete backend tests**

Run: `mvn test`

Expected: zero failures and zero errors.

- [ ] **Step 3: Run frontend production build**

Run: `npm run build`

Expected: exit code `0`; existing dependency-size warnings are allowed.

- [ ] **Step 4: Restart all three services**

Run: `.\start-dev.ps1`

Expected: FastAPI `8090`, Spring Boot `8080`, and Vite `3000` pass startup checks.

- [ ] **Step 5: Verify the supported product flow**

Add A-share `600519` and fund `000001`, edit quantity/cost, refresh, delete one asset, then reload `/stocks`. Confirm persisted state, clear provider labels, and no account/ledger fields in the primary flow.

- [ ] **Step 6: Verify unsupported provider behavior**

Attempt HK `00700` and US `AAPL` only when those types are enabled. Confirm the UI reports the provider/network failure and does not create a false latest price.
