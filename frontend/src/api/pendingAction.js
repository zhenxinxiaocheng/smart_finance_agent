import request from './request'

export function listPendingActionsAPI() {
  return request.get('/pending-actions')
}

export function confirmPendingActionAPI(id) {
  return request.post(`/pending-actions/${id}/confirm`)
}

export function cancelPendingActionAPI(id) {
  return request.post(`/pending-actions/${id}/cancel`)
}
