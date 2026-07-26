export function startInvestmentRealtimePolling(
  callback,
  intervalMs = 3000,
  scheduler = globalThis,
  visibilitySource = globalThis.document
) {
  let timer = null

  const invoke = force => {
    Promise.resolve(callback(force)).catch(() => {})
  }
  const stopTimer = () => {
    if (timer === null) return
    scheduler.clearInterval(timer)
    timer = null
  }
  const startTimer = () => {
    if (timer !== null || visibilitySource?.hidden) return
    timer = scheduler.setInterval(() => invoke(false), intervalMs)
  }
  const handleVisibilityChange = () => {
    if (visibilitySource?.hidden) {
      stopTimer()
      return
    }
    invoke(true)
    startTimer()
  }

  visibilitySource?.addEventListener?.('visibilitychange', handleVisibilityChange)
  startTimer()

  return () => {
    stopTimer()
    visibilitySource?.removeEventListener?.('visibilitychange', handleVisibilityChange)
  }
}

export function createPrioritizedRefreshRunner(callback) {
  let active = false
  let current = null
  let pending = null
  let disposed = false

  const resolvePending = () => {
    if (!pending) return
    pending.waiters.forEach(({ resolve }) => resolve(undefined))
    pending = null
  }

  const execute = async request => {
    try {
      const value = await callback(request.force, request.silent)
      request.waiters.forEach(({ resolve }) => resolve(value))
    } catch (error) {
      request.waiters.forEach(({ resolve, reject }) => disposed ? resolve(undefined) : reject(error))
    } finally {
      if (disposed) {
        resolvePending()
        active = false
      } else if (pending) {
        const next = pending
        pending = null
        current = next
        void execute(next)
      } else {
        current = null
        active = false
      }
    }
  }

  const run = (force = false, silent = false, mustRun = false) => new Promise((resolve, reject) => {
    if (disposed) {
      resolve(undefined)
      return
    }
    if (active) {
      if (current?.force && !mustRun && (silent || !current.silent)) {
        current.waiters.push({ resolve, reject })
        return
      }
      if (pending) {
        pending.force = pending.force || Boolean(force)
        pending.silent = pending.silent && Boolean(silent)
        pending.waiters.push({ resolve, reject })
      } else {
        pending = {
          force: Boolean(force),
          silent: Boolean(silent),
          waiters: [{ resolve, reject }]
        }
      }
      return
    }

    active = true
    current = {
      force: Boolean(force),
      silent: Boolean(silent),
      waiters: [{ resolve, reject }]
    }
    void execute(current)
  })

  run.dispose = () => {
    disposed = true
    resolvePending()
  }
  return run
}

export function formatQuoteTime(asset) {
  if (asset?.productType === 'STOCK' && asset?.fetchedAt) {
    const match = String(asset.fetchedAt).match(/T(\d{2}:\d{2}:\d{2})/)
    if (match) return match[1]
  }
  return asset?.dataDate || '暂无时间'
}
