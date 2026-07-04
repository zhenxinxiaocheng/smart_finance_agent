import request from './request'

export function listAgentReflectionsAPI(params = {}) {
  return request.get('/agent-reflections', { params })
}

export function acceptAgentReflectionAPI(id, payload = null) {
  return request.post(`/agent-reflections/${id}/accept`, payload)
}

export function dismissAgentReflectionAPI(id) {
  return request.post(`/agent-reflections/${id}/dismiss`)
}
