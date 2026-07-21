const number = value => value == null || value === '' ? null : Number(value)

export function availableMovingAveragePeriods(rows = []) {
  const periods = new Set()
  rows.forEach(row => Object.keys(row || {}).forEach(key => {
    const match = /^ma(\d+)$/.exec(key)
    if (match && number(row[key]) != null) periods.add(Number(match[1]))
  }))
  return [...periods].sort((left, right) => left - right)
}

function escapeHtml(value) {
  return String(value ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;')
}

function formatPrice(value) {
  const parsed = number(value)
  if (parsed == null || !Number.isFinite(parsed)) return '--'
  return parsed.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 4 })
}

function formatVolume(value) {
  const parsed = number(value)
  if (parsed == null || !Number.isFinite(parsed)) return '--'
  if (Math.abs(parsed) >= 100000000) return `${(parsed / 100000000).toFixed(2)} 亿`
  if (Math.abs(parsed) >= 10000) return `${(parsed / 10000).toFixed(2)} 万`
  return parsed.toLocaleString('zh-CN')
}

function stockTooltipFormatter(rows, theme) {
  return params => {
    const items = Array.isArray(params) ? params : [params]
    const candle = items.find(item => item?.seriesType === 'candlestick')
    const index = candle?.dataIndex ?? items[0]?.dataIndex
    const row = rows[index]
    if (!row) return ''
    const rise = number(row.close) >= number(row.open)
    const accent = rise ? (theme.positive || '#10b981') : theme.destructive
    const indicatorRows = items
      .filter(item => item?.seriesType === 'line' && item.value != null)
      .slice(0, 6)
      .map(item => `<span style="color:${escapeHtml(item.color)}">${escapeHtml(item.seriesName)}</span> ${formatPrice(item.value)}`)
      .join('&nbsp;&nbsp;')
    return `
      <div style="min-width:220px">
        <div style="display:flex;justify-content:space-between;gap:16px;margin-bottom:8px">
          <strong>${escapeHtml(row.date || row.dataDate || row.data_date)}</strong>
          <span style="color:${accent};font-weight:600">${rise ? '上涨' : '下跌'}</span>
        </div>
        <div style="display:grid;grid-template-columns:repeat(4,1fr);gap:4px 12px">
          <span>开 ${formatPrice(row.open)}</span><span>高 ${formatPrice(row.high)}</span>
          <span>低 ${formatPrice(row.low)}</span><span>收 ${formatPrice(row.close)}</span>
        </div>
        <div style="margin-top:7px;color:${escapeHtml(theme.mutedForeground)}">成交量 ${formatVolume(row.volume)}</div>
        ${indicatorRows ? `<div style="margin-top:7px;padding-top:7px;border-top:1px solid ${escapeHtml(theme.border)};font-size:11px">${indicatorRows}</div>` : ''}
      </div>`
  }
}

function lineSeries(name, data, color, xAxisIndex = 0, yAxisIndex = 0, extra = {}) {
  return {
    name,
    type: 'line',
    data,
    xAxisIndex,
    yAxisIndex,
    showSymbol: false,
    connectNulls: false,
    animation: false,
    lineStyle: { width: 1.35, color },
    itemStyle: { color },
    emphasis: { focus: 'series' },
    ...extra
  }
}

function zoneMarkArea(levels, theme) {
  const data = []
  if (levels?.support?.low != null && levels?.support?.high != null) {
    data.push([
      { name: `支撑 ${levels.support.low}–${levels.support.high}`, yAxis: Number(levels.support.low), itemStyle: { color: theme.primary, opacity: 0.08 } },
      { yAxis: Number(levels.support.high) }
    ])
  }
  if (levels?.resistance?.low != null && levels?.resistance?.high != null) {
    data.push([
      { name: `压力 ${levels.resistance.low}–${levels.resistance.high}`, yAxis: Number(levels.resistance.low), itemStyle: { color: theme.destructive, opacity: 0.07 } },
      { yAxis: Number(levels.resistance.high) }
    ])
  }
  return data.length ? { silent: true, label: { color: theme.mutedForeground, fontSize: 10 }, data } : undefined
}

function stockIndicatorSeries(rows, indicator, palette, theme, indicatorConfig = {}) {
  if (indicator === 'MACD') {
    return [
      { name: 'MACD', type: 'bar', xAxisIndex: 2, yAxisIndex: 2, data: rows.map(row => number(row.macd)), animation: false,
        itemStyle: { color: params => Number(params.value) >= 0 ? (theme.positive || '#10b981') : theme.destructive } },
      lineSeries('DIFF', rows.map(row => number(row.macdDiff)), palette[1], 2, 2),
      lineSeries('DEA', rows.map(row => number(row.macdSignal)), palette[2], 2, 2)
    ]
  }
  if (indicator === 'RSI') {
    const rsiBounds = Array.isArray(indicatorConfig.rsiBounds) ? indicatorConfig.rsiBounds : []
    return [lineSeries('RSI', rows.map(row => number(row.rsi)), palette[1], 2, 2, {
      markLine: rsiBounds.length
        ? { silent: true, symbol: 'none', label: { show: false }, lineStyle: { type: 'dashed', opacity: 0.5 }, data: rsiBounds.map(value => ({ yAxis: value })) }
        : undefined
    })]
  }
  if (indicator === 'KDJ') {
    return [
      lineSeries('K', rows.map(row => number(row.kdjK)), palette[0], 2, 2),
      lineSeries('D', rows.map(row => number(row.kdjD)), palette[1], 2, 2),
      lineSeries('J', rows.map(row => number(row.kdjJ)), palette[2], 2, 2)
    ]
  }
  if (indicator === 'ATR') {
    return [lineSeries('ATR', rows.map(row => number(row.atr)), palette[2], 2, 2)]
  }
  return []
}

export function buildInvestmentChartOption({
  series = [],
  productType = 'STOCK',
  indicator = 'MACD',
  theme,
  levels = {},
  visibleMAs,
  indicatorConfig = {},
}) {
  const rows = Array.isArray(series) ? series : []
  const dates = rows.map(row => row.date || row.dataDate || row.data_date)
  const palette = theme.palette || [theme.primary, '#0ea5e9', '#f59e0b', '#a855f7', '#64748b']
  const positive = theme.positive || '#10b981'
  const availableMAPeriods = availableMovingAveragePeriods(rows)
  const activeMAPeriods = Array.isArray(visibleMAs)
    ? availableMAPeriods.filter(period => visibleMAs.includes(period))
    : availableMAPeriods
  const common = {
    animation: false,
    backgroundColor: 'transparent',
    color: palette,
    tooltip: {
      ...theme.tooltip,
      trigger: 'axis',
      axisPointer: { type: 'cross', crossStyle: { color: theme.mutedForeground, opacity: 0.55 } },
      confine: true,
      formatter: productType === 'MUTUAL_FUND' ? undefined : stockTooltipFormatter(rows, theme)
    },
    axisPointer: { link: [{ xAxisIndex: 'all' }], label: { backgroundColor: theme.foreground } },
    toolbox: { show: false }
  }

  if (productType === 'MUTUAL_FUND') {
    const chartSeries = [
      lineSeries('单位净值', rows.map(row => number(row.nav ?? row.close)), theme.primary, 0, 0, {
        lineStyle: { width: 2, color: theme.primary }, areaStyle: { color: theme.primary, opacity: 0.08 }
      }),
      ...activeMAPeriods.map((period, index) =>
        lineSeries(`MA${period}`, rows.map(row => number(row[`ma${period}`])), palette[(index + 1) % palette.length]))
    ]
    return {
      ...common,
      legend: { top: 0, left: 8, itemWidth: 16, itemHeight: 3, textStyle: { color: theme.mutedForeground, fontSize: 10 } },
      grid: [{ left: 58, right: 24, top: 42, bottom: 54 }],
      xAxis: [{ type: 'category', data: dates, boundaryGap: false, axisLine: theme.axisLine, axisLabel: theme.axisLabel, axisTick: { show: false } }],
      yAxis: [{ type: 'value', scale: true, position: 'right', splitLine: theme.splitLine, axisLabel: theme.axisLabel }],
      dataZoom: [{ type: 'inside', xAxisIndex: [0], start: Math.max(0, 100 - 12000 / Math.max(rows.length, 1)) }, { type: 'slider', xAxisIndex: [0], height: 18, bottom: 12, borderColor: theme.border, textStyle: { color: theme.mutedForeground } }],
      series: chartSeries
    }
  }

  const showIndicator = indicator !== 'NONE' && indicator !== 'BOLL'
  const mainBottom = showIndicator ? '48%' : '31%'
  const volumeTop = showIndicator ? '57%' : '73%'
  const chartSeries = [
    {
      name: 'K线', type: 'candlestick', xAxisIndex: 0, yAxisIndex: 0,
      data: rows.map(row => [number(row.open), number(row.close), number(row.low), number(row.high)]),
      barMaxWidth: 12,
      itemStyle: { color: positive, color0: theme.destructive, borderColor: positive, borderColor0: theme.destructive },
      markArea: zoneMarkArea(levels, theme),
      markLine: rows.length ? {
        silent: true,
        symbol: 'none',
        lineStyle: { color: theme.mutedForeground, width: 1, type: 'dashed', opacity: 0.65 },
        label: {
          show: true,
          position: 'end',
          color: theme.foreground,
          backgroundColor: theme.card,
          borderColor: theme.border,
          borderWidth: 1,
          borderRadius: 4,
          padding: [3, 5],
          formatter: ({ value }) => formatPrice(value)
        },
        data: [{ yAxis: number(rows.at(-1)?.close), name: '最新价' }]
      } : undefined
    },
    ...activeMAPeriods.map((period, index) =>
      lineSeries(`MA${period}`, rows.map(row => number(row[`ma${period}`])), palette[index % palette.length])),
    {
      name: '成交量', type: 'bar', xAxisIndex: 1, yAxisIndex: 1, animation: false,
      data: rows.map((row, index) => ({
        value: number(row.volume),
        itemStyle: { color: number(row.close) >= number(row.open) ? positive : theme.destructive, opacity: 0.58 },
        _index: index
      }))
    }
  ]
  if (indicator === 'BOLL') {
    chartSeries.push(
      lineSeries('BOLL上轨', rows.map(row => number(row.bollUpper)), palette[2]),
      lineSeries('BOLL中轨', rows.map(row => number(row.bollMid)), palette[1]),
      lineSeries('BOLL下轨', rows.map(row => number(row.bollLower)), palette[2])
    )
  }
  if (showIndicator) chartSeries.push(...stockIndicatorSeries(rows, indicator, palette, theme, indicatorConfig))

  const xAxis = [
    { type: 'category', data: dates, boundaryGap: true, axisLine: theme.axisLine, axisLabel: { show: false }, axisTick: { show: false }, min: 'dataMin', max: 'dataMax' },
    { type: 'category', gridIndex: 1, data: dates, boundaryGap: true, axisLine: theme.axisLine, axisLabel: { show: !showIndicator, ...theme.axisLabel }, axisTick: { show: false }, min: 'dataMin', max: 'dataMax' }
  ]
  const yAxis = [
    { type: 'value', scale: true, position: 'right', splitLine: theme.splitLine, axisLabel: theme.axisLabel },
    { type: 'value', gridIndex: 1, scale: true, position: 'right', splitNumber: 2, splitLine: { show: false }, axisLabel: { show: false } }
  ]
  const grid = [
    { left: 58, right: 52, top: 44, bottom: mainBottom },
    { left: 58, right: 52, top: volumeTop, height: showIndicator ? '12%' : '17%' }
  ]
  if (showIndicator) {
    grid.push({ left: 58, right: 52, top: '75%', bottom: 42 })
    xAxis.push({ type: 'category', gridIndex: 2, data: dates, boundaryGap: true, axisLine: theme.axisLine, axisLabel: theme.axisLabel, axisTick: { show: false }, min: 'dataMin', max: 'dataMax' })
    yAxis.push({ type: 'value', gridIndex: 2, scale: true, position: 'right', splitNumber: 2, splitLine: theme.splitLine, axisLabel: { ...theme.axisLabel, fontSize: 9 } })
  }
  const axisIndexes = showIndicator ? [0, 1, 2] : [0, 1]
  return {
    ...common,
    legend: { show: false },
    grid,
    xAxis,
    yAxis,
    dataZoom: [
      { type: 'inside', xAxisIndex: axisIndexes, start: Math.max(0, 100 - 12000 / Math.max(rows.length, 1)), end: 100 },
      { type: 'slider', show: rows.length > 1, xAxisIndex: axisIndexes, height: 16, bottom: 8, borderColor: theme.border, textStyle: { color: theme.mutedForeground, fontSize: 9 } }
    ],
    series: chartSeries
  }
}
