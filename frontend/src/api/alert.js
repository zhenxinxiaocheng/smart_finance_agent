import request from './request'

export function getUnreadAlertsAPI() {
  return request.get('/alerts/unread')
}

export function getRecentAlertsAPI(limit = 5) {
  return request.get('/alerts/recent', { params: { limit } })
}

export function markAlertReadAPI(id) {
  return request.put(`/alerts/${id}/read`)
}
