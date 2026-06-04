import request from './request'

export function listCategoriesAPI() {
  return request.get('/categories')
}

export function addCategoryAPI(data) {
  return request.post('/categories', data)
}

export function updateCategoryAPI(id, data) {
  return request.put(`/categories/${id}`, data)
}

export function deleteCategoryAPI(id) {
  return request.delete(`/categories/${id}`)
}
