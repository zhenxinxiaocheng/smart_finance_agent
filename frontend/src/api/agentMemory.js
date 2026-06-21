import request from './request'

export function listAgentMemoriesAPI() {
  return request.get('/agent-memories')
}

export function getAgentMemoryPreferencesAPI() {
  return request.get('/agent-memories/preferences')
}

export function updateAgentMemoryPreferencesAPI(data) {
  return request.put('/agent-memories/preferences', data)
}

export function createAgentMemoryAPI(data) {
  return request.post('/agent-memories', data)
}

export function setAgentMemoryDisabledAPI(id, disabled) {
  return request.put(`/agent-memories/${id}/disabled`, null, {
    params: { disabled }
  })
}

export function deleteAgentMemoryAPI(id) {
  return request.delete(`/agent-memories/${id}`)
}

export function resetAgentMemoriesAPI() {
  return request.delete('/agent-memories')
}
