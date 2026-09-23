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

// 纵轴刻度统一小数位，避免 1.2 和 1.23 混排导致对不齐
function axisValueFormatter(decimals) {
  return value => {
    const parsed = number(value)
    if (parsed == null || !Number.isFinite(parsed)) return ''
    return parsed.toFixed(decimals)
  }
}

// 净值区间越窄，需要的小数位越多
function navAxisDecimals(rows) {
  const values = rows
    .map(row => number(row.nav ?? row.close))
    .filter(value => value != null && Number.isFinite(value))
  if (!values.length) return 2
  const span = Math.max(...values) - Math.min(...values)
  if (span >= 0.1) return 2
  if (span >= 0.01) return 3
  return 4
}

const rgbCache = new Map()

// 主题色可能是 hex / rgb / oklch，直接拼 alpha 不可行。
// 借 canvas 把任意合法 CSS 颜色换算成 RGB，失败时返回 null 由调用方回退原色。
function parseRgb(color) {
  const key = String(color ?? '').trim()
  if (!key) return null
  if (rgbCache.has(key)) return rgbCache.get(key)
  let result = null
  if (typeof document !== 'undefined') {
    try {
      const ctx = document.createElement('canvas').getContext('2d')
      if (ctx) {
        ctx.fillStyle = '#000'
        ctx.fillStyle = key
        ctx.fillRect(0, 0, 1, 1)
        const [r, g, b] = ctx.getImageData(0, 0, 1, 1).data
        result = { r, g, b }
      }
    } catch {
      result = null
    }
  }
  rgbCache.set(key, result)
  return result
}

function withAlpha(color, alpha) {
  const rgb = parseRgb(color)
  return rgb ? `rgba(${rgb.r}, ${rgb.g}, ${rgb.b}, ${alpha})` : color
}

function hueOf(color) {
  const rgb = parseRgb(color)
  if (!rgb) return null
  const r = rgb.r / 255
  const g = rgb.g / 255
  const b = rgb.b / 255
  const max = Math.max(r, g, b)
  const delta = max - Math.min(r, g, b)
  if (delta === 0) return 0
  let hue
  if (max === r) hue = ((g - b) / delta) % 6
  else if (max === g) hue = (b - r) / delta + 2
  else hue = (r - g) / delta + 4
  hue *= 60
  return hue < 0 ? hue + 360 : hue
}

// 均线配色：按与主色的色相距离从远到近排序。
// 净值线用主色，所以离主色最远的颜色给 MA5，最接近主色的排到最后，避免撞色
// （比如玫红主题下洋红和主色几乎分不开）。
function movingAveragePalette(theme, count) {
  const candidates = (theme.palette || []).slice(1)
  if (!candidates.length) return (theme.palette || []).slice(0, count)
  const primaryHue = hueOf(theme.primary)
  if (primaryHue == null) return candidates
  const distance = color => {
    const hue = hueOf(color)
    if (hue == null) return 180
    const raw = Math.abs(hue - primaryHue)
    return Math.min(raw, 360 - raw)
  }
  return [...candidates].sort((left, right) => distance(right) - distance(left))
}

function fundTooltipFormatter(rows, theme) {
  const row = (name, value, color, strong) => `
    <div style="display:flex;align-items:center;gap:8px;margin-top:5px">
      <span style="width:8px;height:8px;border-radius:999px;background:${escapeHtml(color)}"></span>
      <span style="flex:1;color:${escapeHtml(theme.mutedForeground)}">${escapeHtml(name)}</span>
      <strong style="font-weight:${strong ? 600 : 500};font-variant-numeric:tabular-nums">${formatPrice(value)}</strong>
    </div>`

  return params => {
    const items = Array.isArray(params) ? params : [params]
    const index = items[0]?.dataIndex
    const current = rows[index]
    if (!current) return ''
    const nav = number(current.nav ?? current.close)
    const previous = index > 0 ? number(rows[index - 1]?.nav ?? rows[index - 1]?.close) : null
    const delta = nav != null && previous ? nav / previous - 1 : null
    const accent = delta == null ? theme.mutedForeground : delta >= 0 ? (theme.positive || '#10b981') : theme.destructive

    return `
      <div style="min-width:190px">
        <div style="display:flex;align-items:baseline;justify-content:space-between;gap:16px">
          <strong>${escapeHtml(current.date || current.dataDate || current.data_date)}</strong>
          ${delta == null ? '' : `<span style="color:${escapeHtml(accent)};font-weight:600">${delta >= 0 ? '+' : ''}${(delta * 100).toFixed(2)}%</span>`}
        </div>
        ${row('单位净值', nav, theme.primary, true)}
        ${items
          .filter(item => item.seriesName !== '单位净值' && item.value != null)
          .map(item => row(item.seriesName, item.value, item.color, false))
          .join('')}
      </div>`
  }
}

// 缩放滑块统一样式，避免默认那套灰底大方块
function zoomSlider(theme, overrides = {}) {
  return {
    type: 'slider',
    height: 18,
    bottom: 10,
    borderColor: 'transparent',
    backgroundColor: withAlpha(theme.border, 0.28),
    fillerColor: withAlpha(theme.primary, 0.12),
    dataBackground: {
      lineStyle: { color: theme.border, width: 1, opacity: 0.9 },
      areaStyle: { color: theme.border, opacity: 0.35 }
    },
    selectedDataBackground: {
      lineStyle: { color: theme.primary, width: 1 },
      areaStyle: { color: withAlpha(theme.primary, 0.16) }
    },
    handleStyle: { color: theme.card, borderColor: theme.border, borderWidth: 1 },
    moveHandleStyle: { color: theme.border, opacity: 0.55 },
    handleSize: '78%',
    moveHandleSize: 4,
    showDetail: false,
    brushSelect: false,
    textStyle: { color: theme.mutedForeground, fontSize: 9 },
    ...overrides
  }
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
    emphasis: { disabled: true },
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
  // 十字光标上的数值气泡：默认是硬邦邦的黑色直角块，这里改成圆角小胶囊
  const axisPointerLabel = {
    backgroundColor: theme.foreground,
    color: theme.card,
    borderRadius: 4,
    padding: [4, 7],
    fontSize: 11,
    shadowBlur: 8,
    shadowColor: 'rgba(15,23,42,.16)',
    shadowOffsetY: 1
  }
  const common = {
    animation: false,
    backgroundColor: 'transparent',
    color: palette,
    tooltip: {
      ...theme.tooltip,
      trigger: 'axis',
      axisPointer: {
        type: 'cross',
        crossStyle: { color: theme.mutedForeground, opacity: 0.45, type: 'dashed', width: 1 },
        label: axisPointerLabel
      },
      confine: true,
      formatter: productType === 'MUTUAL_FUND' ? fundTooltipFormatter(rows, theme) : stockTooltipFormatter(rows, theme)
    },
    axisPointer: { link: [{ xAxisIndex: 'all' }], label: axisPointerLabel },
    toolbox: { show: false }
  }

  if (productType === 'MUTUAL_FUND') {
    const maColors = movingAveragePalette(theme, activeMAPeriods.length)
    const chartSeries = [
      lineSeries('单位净值', rows.map(row => number(row.nav ?? row.close)), theme.primary, 0, 0, {
        // 净值线是主角：加粗、渐变面积、轻微投影，其余均线全部退到后面
        lineStyle: { width: 2.2, color: theme.primary, shadowColor: withAlpha(theme.primary, 0.18), shadowBlur: 8, shadowOffsetY: 3 },
        areaStyle: {
          color: {
            type: 'linear',
            x: 0, y: 0, x2: 0, y2: 1,
            colorStops: [
              { offset: 0, color: withAlpha(theme.primary, 0.26) },
              { offset: 1, color: withAlpha(theme.primary, 0.01) }
            ]
          }
        },
        z: 3
      }),
      ...activeMAPeriods.map((period, index) =>
        lineSeries(`MA${period}`, rows.map(row => number(row[`ma${period}`])), maColors[index % maColors.length], 0, 0, {
          lineStyle: { width: 1.1, color: maColors[index % maColors.length], opacity: 0.75 },
          z: 2
        }))
    ]
    return {
      ...common,
      legend: {
        top: 0,
        left: 4,
        itemWidth: 14,
        itemHeight: 2,
        itemGap: 16,
        textStyle: { color: theme.mutedForeground, fontSize: 11 },
        inactiveColor: withAlpha(theme.mutedForeground, 0.4)
      },
      // 纵轴在右侧，所以左边只留一点余量，把空间让给右侧刻度
      grid: [{ left: 18, right: 58, top: 38, bottom: 56 }],
      xAxis: [{
        type: 'category',
        data: dates,
        boundaryGap: false,
        axisLine: theme.axisLine,
        axisTick: { show: false },
        // 日期已经显示在提示框里，这里不再重复画一个压在日期标签上的气泡
        axisPointer: { label: { show: false } },
        axisLabel: { ...theme.axisLabel, margin: 10, hideOverlap: true }
      }],
      yAxis: [{
        type: 'value',
        scale: true,
        position: 'right',
        splitNumber: 5,
        axisLine: { show: false },
        axisTick: { show: false },
        splitLine: { ...theme.splitLine, lineStyle: { ...(theme.splitLine?.lineStyle || {}), opacity: 0.5 } },
        axisLabel: { ...theme.axisLabel, margin: 12, formatter: axisValueFormatter(navAxisDecimals(rows)) }
      }],
      dataZoom: [
        { type: 'inside', xAxisIndex: [0], start: Math.max(0, 100 - 12000 / Math.max(rows.length, 1)) },
        zoomSlider(theme, { xAxisIndex: [0] })
      ],
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
  // 日期已经显示在提示框里，只在最底部那条轴上保留十字光标气泡，
  // 否则多个 grid 会各画一个日期气泡，中间那个还会被裁切
  xAxis.forEach((axis, index) => {
    axis.axisPointer = { label: { show: index === xAxis.length - 1 } }
  })
  return {
    ...common,
    legend: { show: false },
    grid,
    xAxis,
    yAxis,
    dataZoom: [
      { type: 'inside', xAxisIndex: axisIndexes, start: Math.max(0, 100 - 12000 / Math.max(rows.length, 1)), end: 100 },
      zoomSlider(theme, { show: rows.length > 1, xAxisIndex: axisIndexes, height: 16, bottom: 8 })
    ],
    series: chartSeries
  }
}
