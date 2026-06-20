import request from './request'

export function importBillAPI(file) {
  const formData = new FormData()
  formData.append('file', file)
  return request.post('/bills/import', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 120000
  })
}

export function getBillAPI(id) {
  return request.get(`/bills/${id}`)
}

export function confirmBillAPI(id, data) {
  return request.post(`/bills/${id}/confirm`, data)
}
