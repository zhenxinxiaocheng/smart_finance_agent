import request from './request'

export function getFinancialProfileAPI() {
  return request.get('/financial-profile')
}

export function saveFinancialProfileAPI(data) {
  return request.put('/financial-profile', data)
}
