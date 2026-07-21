export function startInvestmentRealtimePolling(callback, intervalMs = 3000, scheduler = globalThis) {
  const timer = scheduler.setInterval(() => {
    Promise.resolve(callback()).catch(() => {})
  }, intervalMs)
  return () => scheduler.clearInterval(timer)
}

export function formatQuoteTime(asset) {
  if (asset?.productType === 'STOCK' && asset?.fetchedAt) {
    const match = String(asset.fetchedAt).match(/T(\d{2}:\d{2}:\d{2})/)
    if (match) return match[1]
  }
  return asset?.dataDate || '暂无时间'
}
