import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { investmentHelpText } from '../src/lib/investmentHelpText.js'

const routerSource = readFileSync(new URL('../src/router/index.js', import.meta.url), 'utf8')
const pageSource = readFileSync(new URL('../src/views/InvestmentAssetDetail.vue', import.meta.url), 'utf8')
const chartSource = readFileSync(new URL('../src/components/investment/InvestmentKlineChart.vue', import.meta.url), 'utf8')
const tooltipSource = readFileSync(new URL('../src/components/ui/tooltip/InfoTooltip.vue', import.meta.url), 'utf8')
const horizonDialogSource = readFileSync(new URL('../src/components/investment/HorizonProfileDialog.vue', import.meta.url), 'utf8')
const investmentApiSource = readFileSync(new URL('../src/api/investment.js', import.meta.url), 'utf8')

test('投资详情页使用动态路由和完整分析分区', () => {
  assert.match(routerSource, /path:\s*'stocks\/:assetId'/)
  assert.match(routerSource, /name:\s*'InvestmentAssetDetail'/)
  assert.match(pageSource, /InvestmentKlineChart/)
  assert.match(pageSource, /专业走势研判/)
  assert.match(pageSource, /多周期分析/)
  assert.match(pageSource, /财务警告/)
  assert.match(pageSource, /AI 解读/)
})

test('分析周期来自后端画像且单资产设置可恢复为全局配置', () => {
  assert.match(pageSource, /detail\.value\?\.analysisPreference/)
  assert.match(pageSource, /horizonOptions = computed/)
  assert.doesNotMatch(pageSource, /const horizonOptions = \[/)
  assert.doesNotMatch(pageSource, /shortMinDays|mediumMinDays|longMinDays/)
  assert.match(pageSource, /clearInvestmentAssetHorizonOverrideAPI/)
  assert.match(horizonDialogSource, /恢复全局设置/)
  assert.match(horizonDialogSource, /v-for="\(item, index\) in draft\.settings"/)
  assert.doesNotMatch(horizonDialogSource, /5,?\s*20|20,?\s*120|120,?\s*500/)
  assert.match(investmentApiSource, /\/investment\/horizon-profile/)
})

test('数据不足的周期不展示伪造评分', () => {
  assert.match(pageSource, /item\.data\.status === 'INSUFFICIENT'/)
  assert.match(pageSource, /历史数据不足，暂不判断走势/)
  assert.match(pageSource, /hasUsableQuantModel/)
})

test('风险警告与技术结果在页面上分区呈现', () => {
  assert.match(pageSource, /technicalAnalysis/)
  assert.match(pageSource, /financialWarnings/)
  assert.doesNotMatch(pageSource, /affectsTechnicalAnalysis/)
})

test('投资详情页不提供全局总资产入口', () => {
  assert.doesNotMatch(pageSource, /openWealthDialog|wealthOpen|updateWealthBaselineAPI/)
  assert.match(pageSource, /WEALTH_NOT_INITIALIZED: '现金基准未初始化'/)
})

test('股票中性结论无确认歧义且基金使用专用动作布局', () => {
  assert.doesNotMatch(pageSource, /等待确认/)
  assert.match(pageSource, /近期表现与风险概览/)
  assert.match(pageSource, /v-if="asset\.productType === 'MUTUAL_FUND'" class="space-y-2\.5"/)
  assert.match(pageSource, /v-if="asset\.productType !== 'MUTUAL_FUND'" class="space-y-3"/)
})

test('平板改为上下布局且手机端压缩关键指标和图表高度', () => {
  assert.match(pageSource, /<section class="grid gap-4 xl:grid-cols-12">\s*<Card class="gap-0 py-4 shadow-sm xl:col-span-9">/)
  assert.match(pageSource, /text-\[15px\].*sm:text-xl/)
  assert.match(chartSource, /h-\[360px\].*sm:h-\[420px\].*md:h-\[500px\]/)
})

test('复杂模块提供可悬停和键盘访问的问号说明', () => {
  assert.match(pageSource, /InfoTooltip/)
  assert.match(pageSource, /helpText\.technicalOutlook/)
  assert.match(pageSource, /helpText\.backtest/)
  assert.match(tooltipSource, /TooltipTrigger/)
  assert.match(tooltipSource, /aria-label/)
  assert.match(tooltipSource, /CircleHelp/)
})

test('只解释专业指标且说明用户如何阅读结果', () => {
  assert.doesNotMatch(pageSource, /helpText\.aiExplanation/)
  assert.doesNotMatch(pageSource, /helpText\.financialWarnings/)
  assert.match(investmentHelpText.technicalOutlook, /趋势、动量、量价、波动和价格结构/)
  assert.match(investmentHelpText.quantProbability, /扣除交易成本/)
  assert.match(investmentHelpText.priceZones, /买入\/加仓/)
  assert.match(investmentHelpText.backtest, /样本次数/)
  assert.doesNotMatch(JSON.stringify(investmentHelpText), /方便理解|用通俗中文解释|不保证未来一定上涨/)
})

test('基金收益窗口由策略结果动态渲染', () => {
  assert.match(pageSource, /technical\.value\.returnMetrics/)
  assert.match(pageSource, /v-for="metric in fundReturnMetrics"/)
  assert.doesNotMatch(pageSource, /oneMonthReturn|threeMonthReturn|oneYearReturn/)
})

test('基本面数据要求与 AI 冷却时间由后端结果动态展示', () => {
  assert.match(pageSource, /fundamental\.reason/)
  assert.doesNotMatch(pageSource, /至少需要三期数据和四个有效维度/)
  assert.match(pageSource, /ai\.cooldownMinutes/)
  assert.doesNotMatch(pageSource, /最短刷新间隔 30 分钟/)
})

test('页面和帮助文案不向用户展示分析过程', () => {
  assert.doesNotMatch(pageSource, /结论来自|基金动作基于|价位来自行情/)
  assert.doesNotMatch(JSON.stringify(investmentHelpText), /评分依据|由当前投资账户.*计算|在历史行情中查找|分别计算/)
})

test('结论卡不重复解释显而易见的操作', () => {
  assert.doesNotMatch(pageSource, /现在怎么做|conclusionAction/)
})

test('技术走势是主结论且旧伪数量模块已删除', () => {
  assert.match(pageSource, /activeOutlook/)
  assert.match(pageSource, /outlookDirectionLabel/)
  assert.match(pageSource, /走势失效条件/)
  assert.doesNotMatch(pageSource, /数量参考|personalized|quantityState|buildQuantityReferenceState/)
  assert.match(pageSource, /<details/)
  assert.match(pageSource, /查看详细分析/)
})

test('当前周期的关键价位和主要结论来自同一技术走势规则', () => {
  assert.match(pageSource, /:levels="activeLevels"/)
  assert.match(pageSource, /activeAnalysis\.value\.levels/)
  assert.match(pageSource, /activeAnalysis\.value\.actionZones/)
  assert.match(pageSource, /activeAnalysis\.value\.outlook/)
  assert.doesNotMatch(pageSource, /label:\s*'技术评分'.*topMetrics/)
})

test('只有存在可用模型时才把量化结果作为独立次级模块展示', () => {
  assert.match(investmentApiSource, /getInvestmentQuantAnalysisAPI/)
  assert.match(investmentApiSource, /getQuantActionPlanAPI/)
  assert.match(pageSource, /v-if="hasUsableQuantModel"/)
  assert.match(pageSource, /量化模型（独立结果）/)
  assert.doesNotMatch(pageSource, /refreshInvestmentQuantAnalysisAPI/)
  assert.doesNotMatch(pageSource, /getInvestmentQuantJobAPI/)
  assert.match(pageSource, /profitProbability/)
  assert.match(pageSource, /expectedNetReturn/)
  assert.match(pageSource, /lossProbability/)
  assert.doesNotMatch(pageSource, /orderAmountCny/)
  assert.doesNotMatch(pageSource, /probabilityPositiveExcess/)
  assert.doesNotMatch(pageSource, /topFactors/)
  assert.match(pageSource, /NO_TRADE/)
  assert.match(horizonDialogSource, /targetHoldingDays/)
})
