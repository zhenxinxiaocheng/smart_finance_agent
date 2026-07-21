export function createSessionExpiryHandler({ clearSession, redirectToLogin }) {
  let handlingPromise = null

  return {
    handle() {
      if (!handlingPromise) {
        clearSession()
        handlingPromise = Promise.resolve().then(redirectToLogin)
      }
      return handlingPromise
    },
    reset() {
      handlingPromise = null
    }
  }
}

const sessionExpiryHandler = createSessionExpiryHandler({
  clearSession() {
    localStorage.removeItem('token')
    localStorage.removeItem('user')
  },
  async redirectToLogin() {
    const [{ useAuthStore }, { default: router }] = await Promise.all([
      import('../stores/auth.js'),
      import('../router/index.js')
    ])
    useAuthStore().logout()
    if (router.currentRoute.value.name !== 'Login') {
      await router.replace({ name: 'Login' })
    }
  }
})

export function handleSessionExpired() {
  return sessionExpiryHandler.handle()
}

export function resetSessionExpiryHandling() {
  sessionExpiryHandler.reset()
}
