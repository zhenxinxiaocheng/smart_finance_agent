import request from './request'

export function addTransactionAPI(data) {
  return request.post('/transactions', data)
}

export function updateTransactionAPI(id, data) {
  return request.put(`/transactions/${id}`, data)
}

export function deleteTransactionAPI(id) {
  return request.delete(`/transactions/${id}`)
}

export function getTransactionAPI(id) {
  return request.get(`/transactions/${id}`)
}

export function listTransactionsAPI(params) {
  return request.get('/transactions', { params })
}

export function categorySummaryAPI(params) {
  return request.get('/transactions/category-summary', { params })
}
