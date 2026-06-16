import request from './request'

export function getBudgetsAPI(month) {
  return request.get('/budgets', { params: { month } })
}

export function saveBudgetAPI(data) {
  return request.put('/budgets', data)
}

export function deleteBudgetAPI(id) {
  return request.delete(`/budgets/${id}`)
}
