function cssVar(name, fallback) {
  if (typeof window === 'undefined') return fallback
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return value || fallback
}

export function getChartTheme() {
  const foreground = cssVar('--foreground', '#0f172a')
  const mutedForeground = cssVar('--muted-foreground', '#64748b')
  const border = cssVar('--border', '#e2e8f0')
  const card = cssVar('--card', '#ffffff')
  const primary = cssVar('--primary', '#00a86b')
  const destructive = cssVar('--destructive', '#ef4444')
  const chart2 = cssVar('--chart-2', '#0ea5e9')
  const chart3 = cssVar('--chart-3', '#f59e0b')
  const chart4 = cssVar('--chart-4', '#a855f7')
  const chart5 = cssVar('--chart-5', '#64748b')

  return {
    foreground,
    mutedForeground,
    border,
    card,
    primary,
    destructive,
    palette: [primary, chart2, chart3, chart4, chart5, destructive],
    tooltip: {
      trigger: 'axis',
      backgroundColor: card,
      borderColor: border,
      borderWidth: 1,
      textStyle: { color: foreground, fontSize: 12 },
      extraCssText: 'box-shadow: 0 8px 24px rgba(15,23,42,.12); border-radius: 10px;'
    },
    axisLine: { lineStyle: { color: border } },
    axisLabel: { color: mutedForeground, fontSize: 11 },
    splitLine: { lineStyle: { color: border, type: 'dashed', opacity: 0.7 } }
  }
}
