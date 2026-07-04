import request from './request'

export function getAgentAuditOverviewAPI() {
  return request.get('/agent-audit/overview')
}
