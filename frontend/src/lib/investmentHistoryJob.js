const RECOVERY_STATUSES = new Set(['FAILED', 'PARTIAL'])
const POLLING_STATUSES = new Set(['QUEUED', 'RUNNING', 'RETRY_WAIT', ...RECOVERY_STATUSES])

export function shouldPollHistoryJob(status) {
  return POLLING_STATUSES.has(status)
}

export function createHistoryJobPollingController({
  poll,
  onJob,
  onTerminal,
  intervalMs = 3000,
  recoveryIntervalMs = 60000,
  scheduler = globalThis,
  visibilitySource = globalThis.document
}) {
  let timer = null
  let inFlight = false
  let disposed = false
  let assetId = null
  let status = null
  let generation = 0

  const isVisible = () => !visibilitySource?.hidden
  const isCurrent = context => !disposed
    && context.generation === generation
    && String(context.assetId) === String(assetId)
  const stopTimer = () => {
    if (timer === null) return
    scheduler.clearTimeout(timer)
    timer = null
  }
  const schedule = () => {
    if (disposed || !isVisible() || !shouldPollHistoryJob(status)) {
      stopTimer()
      return
    }
    if (timer !== null || inFlight) return
    timer = scheduler.setTimeout(() => {
      timer = null
      void execute()
    }, RECOVERY_STATUSES.has(status) ? recoveryIntervalMs : intervalMs)
  }
  const execute = async () => {
    if (disposed || inFlight || !shouldPollHistoryJob(status)) return
    const context = { assetId, generation }
    inFlight = true
    try {
      const job = await poll(context)
      if (!isCurrent(context)) return
      const previousStatus = status
      status = job?.status
      onJob?.(job, context)
      if (status === 'SUCCEEDED' || (status === 'PARTIAL' && previousStatus !== status)) {
        stopTimer()
        onTerminal?.(job, context)
      } else if (status === 'FAILED') {
        stopTimer()
      }
    } catch {
      // 瞬时网络失败保持当前状态，后续轮询会继续尝试。
    } finally {
      inFlight = false
      schedule()
    }
  }
  const handleVisibilityChange = () => schedule()

  visibilitySource?.addEventListener?.('visibilitychange', handleVisibilityChange)

  return {
    update(nextAssetId, nextStatus) {
      if (disposed) return
      if (String(nextAssetId) !== String(assetId)) {
        assetId = nextAssetId
        generation += 1
      }
      status = nextStatus
      schedule()
    },
    restart(nextAssetId, nextStatus) {
      if (disposed) return
      stopTimer()
      assetId = nextAssetId
      generation += 1
      status = nextStatus
      schedule()
    },
    dispose() {
      if (disposed) return
      disposed = true
      generation += 1
      stopTimer()
      visibilitySource?.removeEventListener?.('visibilitychange', handleVisibilityChange)
    }
  }
}
