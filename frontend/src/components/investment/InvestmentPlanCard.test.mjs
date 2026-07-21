import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import test from 'node:test'

const drawerSource = readFileSync(new URL('./InvestmentAssetDrawer.vue', import.meta.url), 'utf8')
const cardUrl = new URL('./InvestmentPlanCard.vue', import.meta.url)
const cardSource = existsSync(cardUrl) ? readFileSync(cardUrl, 'utf8') : ''

test('基金编辑抽屉挂载定投卡片且股票不显示', () => {
  assert.match(drawerSource, /InvestmentPlanCard/)
  assert.match(drawerSource, /asset\.productType === 'MUTUAL_FUND'/)
})

test('定投卡片同步展示实际投入并复用现有计划能力', { skip: !cardSource }, () => {
  assert.match(cardSource, /当前实际投入/)
  assert.match(cardSource, /每期定投金额/)
  assert.match(cardSource, /listInvestmentPlansAPI/)
  assert.match(cardSource, /createInvestmentPlanAPI/)
  assert.match(cardSource, /setInvestmentPlanEnabledAPI/)
  assert.match(cardSource, /不会自动下单/)
})

test('定投频率支持每日且每日不显示执行日', { skip: !cardSource }, () => {
  assert.match(cardSource, /value="DAILY">每日/)
  assert.match(cardSource, /form\.frequency !== 'DAILY'/)
})

test('基金定投卡片组件存在', () => {
  assert.ok(cardSource, 'InvestmentPlanCard.vue 尚未实现')
})
