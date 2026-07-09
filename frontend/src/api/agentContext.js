import request from './request'

export function compressAgentContextAPI(data = {}) {
  return request.post('/agent-context/compress', data)
}

export function getAgentContextConfigAPI() {
  return request.get('/agent-context/config')
}

export function getAgentContextUsageAPI(params = {}) {
  return request.get('/agent-context/usage', { params })
}
