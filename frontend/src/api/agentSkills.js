import request from './request'

export function listAgentSkillsAPI() {
  return request.get('/agent-skills')
}

export function getAgentSkillAPI(id) {
  return request.get(`/agent-skills/${id}`)
}

export function setAgentSkillEnabledAPI(id, enabled) {
  return request.put(`/agent-skills/${id}/enabled`, { enabled })
}

export function deleteAgentSkillAPI(id) {
  return request.delete(`/agent-skills/${id}`)
}

export function listSkillInvocationsAPI(params = {}) {
  return request.get('/agent-skills/invocations', { params })
}
