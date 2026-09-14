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
      const apiError = new Error(res.code === 401 ? res.message : res.message || '请求失败')
      apiError.response = response
      apiError.config = response.config
      if (res.code === 401) {
        return handleSessionExpired().then(() => Promise.reject(apiError))
      }
      if (response.config?.silentFeedback !== true) {
        feedback.error(res.message || '请求失败')
        apiError.__feedbackShown = true
      }
      return Promise.reject(apiError)
    }
    return res
  },
  error => {
    if (error.response?.status === 401) {
      return handleSessionExpired().then(() => Promise.reject(error))
    }
    const detail = error.response?.data?.detail
    const message = error.response?.data?.message || (typeof detail === 'string' ? detail : detail?.message) || error.message || '网络错误'
    if (error.config?.silentFeedback !== true) {
      if (error.code === 'ECONNABORTED') {
        feedback.warning('请求超时，AI回复较慢，请稍后重试')
      } else {
        feedback.error(message)
      }
      error.__feedbackShown = true
    }
    return Promise.reject(error)
  }
)

export default request
