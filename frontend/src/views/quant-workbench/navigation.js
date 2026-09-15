const localSource = path => typeof path === 'string' && /^\/(quant|stocks)(?:[/?#]|$)/.test(path) && !/[\\\r\n]/.test(path)

export function pageKey(route) {
  const selection = ['/quant/tasks', '/quant/deployments'].includes(route.path)
    ? `${route.query.type || ''}:${route.query.id || ''}` : ''
  return `${route.path}:${selection}`
}

export function navigationRecord(to, from, previous, current) {
  const key = pageKey(to)
  // A restored history entry (including refresh) already owns its source.
  if (current.quantNavigation?.key === key) return current.quantNavigation
  if (previous.position === current.position && previous.quantNavigation) {
    return { key, source: previous.quantNavigation.source || null }
  }
  if (key === pageKey(from)) return { key, source: previous.quantNavigation?.source || null }
  return { key, source: localSource(from.fullPath) && from.fullPath !== to.fullPath
    ? { path: from.fullPath, position: previous.position } : null }
}

export function returnDestination(route, state, fallback) {
  const source = state.quantNavigation?.source
  if (state.quantNavigation?.key === pageKey(route) && localSource(source?.path) && source.path !== route.fullPath) {
    const delta = source.position - state.position
    if (Number.isInteger(delta) && delta < 0) return { delta }
    return { path: source.path }
  }
  return { path: fallback }
}

export function returnToSource(router, route, fallback = '/quant') {
  const target = returnDestination(route, router.options.history.state || {}, fallback)
  if (target.delta) return router.go(target.delta)
  const resolved = router.resolve(target.path)
  // Replacement must not create a source pointing back at the detail being left.
  return router.replace({ path: resolved.path, query: resolved.query, hash: resolved.hash,
    state: { quantNavigation: { key: pageKey(resolved), source: null } } })
}

export function installQuantNavigation(router) {
  const history = router.options.history
  let previous = { ...history.state }
  router.afterEach((to, from, failure) => {
    if (failure) return
    if (localSource(to.path)) {
      const record = navigationRecord(to, from, previous, history.state || {})
      history.replace(to.fullPath, { ...history.state, quantNavigation: record })
    }
    previous = { ...history.state }
  })
}
