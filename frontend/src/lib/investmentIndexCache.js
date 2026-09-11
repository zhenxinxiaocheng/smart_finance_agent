const CACHE_PREFIX = 'investment-index-watchlist:v1'

function cacheKey(accountKey) {
  const normalized = String(accountKey || '').trim()
  return normalized ? `${CACHE_PREFIX}:${normalized}` : null
}

export function readInvestmentIndexCache(storage, accountKey) {
  const key = cacheKey(accountKey)
  if (!storage || !key) return []
  try {
    const value = JSON.parse(storage.getItem(key) || '[]')
    return Array.isArray(value) ? value : []
  } catch {
    return []
  }
}

export function writeInvestmentIndexCache(storage, accountKey, indexes) {
  const key = cacheKey(accountKey)
  if (!storage || !key || !Array.isArray(indexes)) return
  try {
    storage.setItem(key, JSON.stringify(indexes))
  } catch {
    // Storage may be unavailable in privacy-restricted browser contexts.
  }
}
