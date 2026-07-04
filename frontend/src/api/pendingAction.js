import request from './request'

export function listPendingActionsAPI(params = {}) {
  return request.get('/pending-actions', { params })
}

export function confirmPendingActionAPI(id) {
  return request.post(`/pending-actions/${id}/confirm`)
}

export function cancelPendingActionAPI(id) {
  return request.post(`/pending-actions/${id}/cancel`)
}
