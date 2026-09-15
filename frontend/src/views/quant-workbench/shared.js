import { onBeforeUnmount, ref } from 'vue'
import { feedback } from '@/lib/feedback'
import { researchLabels } from './researchExplanation.js'
import { operationMessage, userMessage } from './presentation.js'

export const labels = {
  momentum:'动量', trend:'趋势', volatility:'波动', drawdown:'回撤', reversal:'反转', volume:'成交量', liquidity:'流动性', RECEIVABLE:'待到账', SETTLEMENT:'到账',
  ATTENTION_REQUIRED:'运行存在风险提示', WAITING_EXECUTION:'等待模拟成交', MONITORING:'持续监测中', WAITING_SIGNAL:'等待交易信号',
  FILLED:'已全部成交', PARTIALLY_FILLED:'部分成交', PARTIALLY_FILLED_CANCELLED:'部分成交，余单已取消', REJECTED:'已拒绝',
  SUSPENDED_OR_PRICE_LIMIT:'停牌或触及涨跌停限制', INSUFFICIENT_CASH_OR_POSITION:'可用资金或持仓不足', INSUFFICIENT_CASH_AFTER_ROUNDING:'金额取整后可用资金不足',
  DRAWDOWN_LIMIT:'触发回撤限制', BEFORE_SIGNAL_START:'早于策略恢复日期', PAUSE:'组合暂停', STOP:'组合停止', LIQUIDATE:'模拟清仓',
  DRAFT: '草稿', ACTIVE: '启用', ARCHIVED: '已归档', QUEUED: '排队中', PENDING: '待处理', RUNNING: '运行中',
  SUCCEEDED: '已完成', PARTIAL: '部分完成', FAILED: '失败', CANCELLED: '已取消', CANCELLING: '取消中', PAUSED: '已暂停', STOPPED: '已停止',
  QUALIFIED: '可继续模拟', UNQUALIFIED: '暂不能继续模拟', STOCK: '股票', ETF: 'ETF', FUND: '场外基金',
  TREND: '趋势策略', MULTI_FACTOR: '多因子策略', ML_ELASTIC_NET: 'Elastic Net', ML_XGBOOST: 'XGBoost',
  TRAINING: '模型训练', FACTOR: '因子研究', BACKTEST: '组合回测', BUY: '买入', SELL: '卖出',
  EXECUTING:'计算中', COMPLETED:'已完成', STOPPING:'等待清仓和结算',
  ...researchLabels,
}
export const label = value => labels[value] || userMessage(value, null, '—')
export const format = value => value == null || typeof value === 'object' ? '—' : String(value)
export const formatTime = value => {
  if(value==null||value==='')return '—'
  const date=new Date(value)
  return Number.isNaN(date.getTime())?'—':date.toLocaleString('zh-CN',{hour12:false})
}
export const clone = value => JSON.parse(JSON.stringify(value))
export const metricNames = {netReturn:'净收益',fees:'总费用',tradeCount:'成交笔数',observations:'观察日数',annualReturn:'年化收益',annualizedReturn:'年化收益',totalReturn:'累计收益',maxDrawdown:'最大回撤',sharpe:'夏普比率',sharpeRatio:'夏普比率',volatility:'波动率',turnover:'换手率',totalFees:'总费用',totalSlippage:'总滑点',benchmarkReturn:'基准收益',excessReturn:'超额收益',initialCash:'初始资金',finalEquity:'最终权益',holdoutMse:'留出集均方误差',validationMse:'验证集均方误差',baselineMse:'基线均方误差',holdoutIC:'留出集 IC',trainSamples:'训练样本数',validationSamples:'验证样本数',holdoutSamples:'留出样本数',trainEnd:'训练截止日',holdoutStart:'留出集起始日',evaluatedThrough:'最终留出截止日'}
export const metricValue=(key,value)=> typeof value!=='number'?format(value):['netReturn','annualReturn','annualizedReturn','totalReturn','maxDrawdown','volatility','turnover','benchmarkReturn','excessReturn'].includes(key)?`${(value*100).toFixed(2)}%`:value.toLocaleString(undefined,{maximumFractionDigits:4})
export const activeTask = value => ['QUEUED', 'PENDING', 'RUNNING', 'CANCELLING'].includes(value)
export function useOperation() {
  const busy = ref(false)
  const error = ref('')
  let pendingLoad
  async function run(fn) {
    if (busy.value) return
    busy.value = true
    error.value = ''
  try { return await fn() } catch (e) {
    const message = operationMessage(e)
    error.value = message
    if (!e.__feedbackShown) feedback.error(message, { duration: 5000 })
  }
    finally { busy.value = false; if(pendingLoad){const next=pendingLoad;pendingLoad=null;void run(next)} }
  }
  function load(fn){if(busy.value){pendingLoad=fn;return}return run(fn)}
  return { busy, error, run, load }
}
export function usePoll(fn, shouldPoll) {
  let timer
  let disposed = false
  async function tick() {
    try { if (shouldPoll()) await fn() } catch { /* The owning view displays errors. */ }
    if (!disposed) timer = setTimeout(tick, 4000)
  }
  timer = setTimeout(tick, 4000)
  onBeforeUnmount(() => { disposed = true; clearTimeout(timer) })
}
