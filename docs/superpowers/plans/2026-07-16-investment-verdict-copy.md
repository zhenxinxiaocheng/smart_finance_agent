# Investment Verdict Copy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 消除“等待确认”的歧义，并让基金详情只展示基金适用的动作与指标。

**Architecture:** 复用详情页现有结论映射函数，根据资产类型选择股票或基金布局。评分、行情、个性化数量和后端分析接口保持不变。

**Tech Stack:** Vue 3、Tailwind、Node test runner

## Global Constraints

- 股票 `WAIT` 显示为“中性观察”。
- 基金显示 `定投参考 / 继续持有 / 暂停追加 / 分批止盈`，不显示股票价位区或多周期控件。
- 不修改技术评分、基金评分和后端分析规则。

---

### Task 1: 详情页结论文案与基金布局

**Files:**
- Modify: `frontend/src/views/InvestmentAssetDetail.vue`
- Modify: `frontend/test/investmentDetailPage.test.mjs`

**Interfaces:**
- Produces: 股票中性文案、基金动作标题、基金指标摘要和资产类型专用布局

- [ ] **Step 1: Add page-source regression assertions**

```js
assert.doesNotMatch(pageSource, /等待确认/)
assert.match(pageSource, /基于收益、均线、波动与回撤/)
assert.match(pageSource, /v-if="asset\.productType !== 'MUTUAL_FUND'"/)
```

- [ ] **Step 2: Run the page test and verify assertion failure**

Run: `node --test test/investmentDetailPage.test.mjs`

- [ ] **Step 3: Implement the conditional layout and copy**

股票继续显示周期切换和价位区，并把 `WAIT` 显示为“中性观察”；基金隐藏分析周期、多周期卡和股票价位区，右侧显示明确动作、近一月、近三月、近一年、最大回撤、年化波动及回撤修复状态。

- [ ] **Step 4: Run focused tests and the frontend build**

Run: `node --test test/investmentDetailPage.test.mjs`

Run: `npm run build`

- [ ] **Step 5: Verify the live fund and stock detail pages**

基金页不得出现“等待确认”、短中长期标签或空白股票价位区；股票 `WAIT` 显示“中性观察”。
