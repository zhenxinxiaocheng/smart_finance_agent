import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { loginAPI, registerAPI, getCurrentUserAPI } from '../api/auth'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem('token') || '')
  const user = ref(JSON.parse(localStorage.getItem('user') || 'null'))

  const isLoggedIn = computed(() => !!token.value)
  const username = computed(() => user.value?.nickname || user.value?.username || '')

  async function login(loginData) {
    const res = await loginAPI(loginData)
    token.value = res.data.token
    user.value = res.data.user
    localStorage.setItem('token', res.data.token)
    localStorage.setItem('user', JSON.stringify(res.data.user))
    return res
  }

  async function register(registerData) {
    const res = await registerAPI(registerData)
    token.value = res.data.token
    user.value = res.data.user
    localStorage.setItem('token', res.data.token)
    localStorage.setItem('user', JSON.stringify(res.data.user))
    return res
  }

  async function fetchUserInfo() {
    try {
      const res = await getCurrentUserAPI()
      user.value = res.data
      localStorage.setItem('user', JSON.stringify(res.data))
    } catch {
      logout()
    }
  }

  function logout() {
    token.value = ''
    user.value = null
    localStorage.removeItem('token')
    localStorage.removeItem('user')
  }

  return { token, user, isLoggedIn, username, login, register, fetchUserInfo, logout }
})
