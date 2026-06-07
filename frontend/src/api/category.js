import request from './request'

// 获取分类列表
export function listCategoriesAPI(params) {
  return request.get('/categories', { params })
}

// 添加分类
export function addCategoryAPI(data) {
  return request.post('/categories', data)
}

// 更新分类
export function updateCategoryAPI(id, data) {
  return request.put(`/categories/${id}`, data)
}

// 删除分类
export function deleteCategoryAPI(id) {
  return request.delete(`/categories/${id}`)
}
