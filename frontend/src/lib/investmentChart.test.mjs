import assert from 'node:assert/strict'
import test from 'node:test'

import { buildInvestmentChartOption } from './investmentChart.js'

const theme = {
  foreground: '#111', mutedForeground: '#666', border: '#ddd', card: '#fff',
  primary: '#7c3aed', positive: '#10b981', destructive: '#ef4444', palette: ['#7c3aed', '#0ea5e9', '#f59e0b', '#a855f7', '#64748b'],
  axisLine: {}, axisLabel: {}, splitLine: {}, tooltip: {}
}

const series = Array.from({ length: 30 }, (_, index) => ({
  date: `2026-06-${String(index + 1).padStart(2, '0')}`,
  open: 10 + index / 10, close: 10.2 + index / 10, high: 10.5 + index / 10, low: 9.8 + index / 10,
  volume: 1000 + index * 20, ma5: 10 + index / 10, ma10: 9.9 + index / 10, ma20: 9.8 + index / 10,
  macd: index / 20, macdDiff: index / 30, macdSignal: index / 40, rsi: 55, atr: 1.2
}))

test('股票图表包含 K 线、成交量、均线和所选副图', () => {
  const option = buildInvestmentChartOption({
    series, productType: 'STOCK', indicator: 'MACD', theme,
    levels: { support: { low: 10, high: 10.4 }, resistance: { low: 13, high: 13.4 } }
  })

  assert.ok(option.series.some(item => item.type === 'candlestick'))
  assert.ok(option.series.some(item => item.name === '成交量' && item.type === 'bar'))
  assert.ok(option.series.some(item => item.name === 'MA5' && item.type === 'line'))
  assert.ok(option.series.some(item => item.name === 'MACD'))
  assert.ok(option.dataZoom.length >= 2)
})

test('场外基金使用净值线且不伪造 K 线', () => {
  const fundSeries = series.map(item => ({ date: item.date, nav: item.close, ma5: item.ma5, ma10: item.ma10, ma20: item.ma20 }))
  const option = buildInvestmentChartOption({ series: fundSeries, productType: 'MUTUAL_FUND', indicator: 'NONE', theme })

  assert.ok(option.series.some(item => item.name === '单位净值' && item.type === 'line'))
  assert.equal(option.series.some(item => item.type === 'candlestick'), false)
})

test('均线列表完全来自分析结果字段', () => {
  const customSeries = series.map(({ ma5, ma10, ma20, ...item }) => ({
    ...item,
    ma3: item.close,
    ma7: item.close - 0.1,
  }))
  const option = buildInvestmentChartOption({
    series: customSeries, productType: 'STOCK', indicator: 'RSI', theme,
  })
  const names = option.series.map(item => item.name)

  assert.ok(names.includes('MA3'))
  assert.ok(names.includes('MA7'))
  assert.equal(names.includes('MA5'), false)
})

test('单条行情限制蜡烛宽度并隐藏无意义的缩放滑块', () => {
  const option = buildInvestmentChartOption({
    series: series.slice(0, 1), productType: 'STOCK', indicator: 'MACD', theme
  })
  const candle = option.series.find(item => item.type === 'candlestick')

  assert.equal(candle.barMaxWidth, 12)
  assert.equal(option.dataZoom.find(item => item.type === 'slider').show, false)
})

test('股票图表使用精简图例、最新价线和专业行情提示框', () => {
  const option = buildInvestmentChartOption({
    series, productType: 'STOCK', indicator: 'MACD', theme
  })
  const candle = option.series.find(item => item.type === 'candlestick')
  const tooltip = option.tooltip.formatter([
    {
      seriesType: 'candlestick', seriesName: 'K线', axisValue: series.at(-1).date,
      dataIndex: series.length - 1, data: [series.at(-1).open, series.at(-1).close, series.at(-1).low, series.at(-1).high]
    }
  ])

  assert.equal(option.legend.show, false)
  assert.equal(candle.markLine.data[0].yAxis, series.at(-1).close)
  assert.match(tooltip, /开/)
  assert.match(tooltip, /收/)
  assert.match(tooltip, /成交量/)
})

test('股票图表只渲染用户启用的均线并移除悬浮工具箱', () => {
  const option = buildInvestmentChartOption({
    series, productType: 'STOCK', indicator: 'RSI', theme, visibleMAs: [5, 10, 20]
  })
  const names = option.series.map(item => item.name)

  assert.ok(names.includes('MA5'))
  assert.ok(names.includes('MA20'))
  assert.equal(names.includes('MA60'), false)
  assert.equal(names.includes('MA250'), false)
  assert.equal(option.toolbox.show, false)
})

test('涨跌色不跟随可变主题色', () => {
  const option = buildInvestmentChartOption({ series, productType: 'STOCK', indicator: 'MACD', theme })
  const candle = option.series.find(item => item.type === 'candlestick')
  const volume = option.series.find(item => item.name === '成交量')
  const macd = option.series.find(item => item.name === 'MACD')

  assert.equal(candle.itemStyle.color, theme.positive)
  assert.equal(candle.itemStyle.color0, theme.destructive)
  assert.equal(volume.data[0].itemStyle.color, theme.positive)
  assert.equal(macd.itemStyle.color({ value: 1 }), theme.positive)
  assert.equal(macd.itemStyle.color({ value: -1 }), theme.destructive)
})
