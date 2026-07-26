const LAST_DETAIL_PATH_KEY = 'investment:last-detail-path'
const ASSET_DETAIL_PATH = /^\/stocks\/[^/?#]+$/

function defaultStorage() {
  return typeof window === 'undefined' ? null : window.sessionStorage
}

export function rememberInvestmentDetailPath(path, storage = defaultStorage()) {
  if (!storage || !ASSET_DETAIL_PATH.test(String(path || ''))) return
  storage.setItem(LAST_DETAIL_PATH_KEY, path)
}

export function resolveInvestmentEntryPath(storage = defaultStorage()) {
  if (!storage) return '/stocks'
  const path = storage.getItem(LAST_DETAIL_PATH_KEY)
  return ASSET_DETAIL_PATH.test(String(path || '')) ? path : '/stocks'
}

export function clearInvestmentDetailPath(storage = defaultStorage()) {
  storage?.removeItem(LAST_DETAIL_PATH_KEY)
}
