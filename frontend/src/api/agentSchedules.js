import request from './request'

export function listAgentSchedulesAPI() {
  return request.get('/agent-schedules')
}

export function createAgentScheduleAPI(data) {
  return request.post('/agent-schedules', data)
}

export function setAgentScheduleEnabledAPI(id, enabled) {
  return request.put(`/agent-schedules/${id}/enabled`, null, { params: { enabled } })
}

export function retryAgentScheduleAPI(id) {
  return request.post(`/agent-schedules/${id}/retry`)
}

export function listAgentScheduleRunsAPI(id) {
  return request.get(`/agent-schedules/${id}/runs`)
}

export function deleteAgentScheduleAPI(id) {
  return request.delete(`/agent-schedules/${id}`)
}
