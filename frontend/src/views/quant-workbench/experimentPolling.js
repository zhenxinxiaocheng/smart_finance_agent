import { explainExperimentError } from './experimentExplanation.js'

export function pollingErrorKind(error) {
  if (error?.code === 'ERR_CANCELED') return 'cancel'
  // Business errors may use an HTTP 200 envelope; the structured code remains authoritative.
  const status = error?.response?.status === 200 ? error.response.data?.code : error?.response?.status
  if (status === 401) return 'session'
  if (status == null || status === 408 || status === 429 || (status >= 500 && status <= 599)) return 'retry'
  return 'stop'
}

export function createExperimentPolling(fetchDetail, publish) {
  let id = '', generation = 0, disposed = false, inFlight = null, stopped = false
  let state = { detail: null, loading: false, warning: false, error: null }
  const update = changes => { state = { ...state, ...changes }; publish(state) }
  const shouldPoll = () => !disposed && !stopped && ['QUEUED', 'RUNNING'].includes(state.detail?.status)

  async function refresh(silent) {
    if (disposed || inFlight || !id) return false
    const requestedId = id, requestedGeneration = generation
    const controller = new AbortController()
    inFlight = controller
    const current = () => !disposed && requestedId === id && requestedGeneration === generation
    if (!silent) update({ loading: true, error: null, warning: false })
    try {
      const detail = await fetchDetail(requestedId, { silent, signal: controller.signal })
      if (!current()) return false
      update({ detail, warning: false, error: null })
      return true
    } catch (error) {
      if (!current()) return false
      const kind = pollingErrorKind(error)
      if (kind === 'cancel') return false
      stopped = kind !== 'retry'
      if (silent && kind === 'retry') update({ warning: true })
      else update({ warning: false, error: kind === 'session' ? null : explainExperimentError({ response: error?.response }) })
      return false
    } finally {
      if (current()) { inFlight = null; update({ loading: false }) }
    }
  }

  return {
    load(nextId) {
      if (disposed) return Promise.resolve(false)
      if (nextId !== id) {
        generation++
        inFlight?.abort()
        inFlight = null
        id = nextId
        update({ detail: null, error: null, warning: false, loading: false })
      }
      stopped = false
      return refresh(false)
    },
    poll: () => shouldPoll() ? refresh(true) : Promise.resolve(false),
    shouldPoll,
    dispose() { disposed = true; generation++; inFlight?.abort(); inFlight = null },
  }
}
