import request from './request'

export function loginAPI(data) {
  return request.post('/auth/login', data)
}

export function registerAPI(data) {
  return request.post('/auth/register', data)
}

export function getCurrentUserAPI() {
  return request.get('/auth/me')
}
