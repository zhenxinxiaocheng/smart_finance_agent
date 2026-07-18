import axios from 'axios'
import { feedback } from '@/lib/feedback'
import { handleSessionExpired, resetSessionExpiryHandling } from '@/lib/sessionExpiry'

const request = axios.create({
  baseURL: '/api',
  timeout: 30000
})

request.interceptors.request.use(
  config => {
    const token = localStorage.getItem('token')
    if (token) {
      resetSessionExpiryHandling()
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  error => Promise.reject(error)
)

request.interceptors.response.use(
  response => {
    const res = response.data
    if (res.code !== 200) {
      if (res.code === 401) {
        return handleSessionExpired().then(() => Promise.reject(new Error(res.message)))
      }
      feedback.error(res.message || '请求失败')
      return Promise.reject(new Error(res.message))
    }
    return res
  },
  error => {
    if (error.response?.status === 401) {
      return handleSessionExpired().then(() => Promise.reject(error))
    }
    if (error.code === 'ECONNABORTED') {
      feedback.warning('请求超时，AI回复较慢，请稍后重试')
    } else {
      feedback.error(error.message || '网络错误')
    }
    return Promise.reject(error)
  }
)

export default request
