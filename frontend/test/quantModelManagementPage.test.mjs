import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const pageSource = readFileSync(
  new URL('../src/views/QuantModelManagement.vue', import.meta.url),
  'utf8',
)
const routerSource = readFileSync(new URL('../src/router/index.js', import.meta.url), 'utf8')
const assetDetailSource = readFileSync(
  new URL('../src/views/InvestmentAssetDetail.vue', import.meta.url),
  'utf8',
)

test('普通入口进入量化模型管理，实验室只保留在专家模式', () => {
  assert.match(routerSource, /path:\s*'quant-lab'[\s\S]*QuantModelManagement\.vue/)
  assert.match(routerSource, /path:\s*'quant-lab\/expert'[\s\S]*QuantResearchLab\.vue/)
  assert.match(assetDetailSource, /量化模型管理/)
  assert.doesNotMatch(assetDetailSource, /更新量化模型/)
})

test('普通模型管理页面提供自动训练和可执行操作', () => {
  assert.match(pageSource, /自动训练状态/)
  assert.match(pageSource, /当前使用模型/)
  assert.match(pageSource, /观察中的新模型/)
  assert.match(pageSource, /立即重新训练/)
  assert.match(pageSource, /计划金额/)
  assert.match(pageSource, /停止条件/)
})

test('普通模型管理页面不暴露研究参数和内部验证术语', () => {
  assert.doesNotMatch(pageSource, /ElasticNet|XGBoost|Alpha|DM\s*p|Deflated|PBO|模型哈希|数据版本/)
})
