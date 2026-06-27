import request from './request'

export function getAgentRunDetailAPI(traceId) {
  return request.get(`/agent-runs/${traceId}`)
}
