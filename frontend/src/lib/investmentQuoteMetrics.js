export function signedPercent(value) {
  if (value == null || value === '') return '-'
  const number = Number(value)
  if (!Number.isFinite(number)) return '-'
  const formatted = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 }).format(number)
  return `${number > 0 ? '+' : ''}${formatted}%`
}

export function compactNumber(value) {
  if (value == null || value === '') return '-'
  const number = Number(value)
  if (!Number.isFinite(number)) return '-'
  const absolute = Math.abs(number)
  if (absolute >= 100000000) return `${decimal(number / 100000000)}亿`
  if (absolute >= 10000) return `${decimal(number / 10000)}万`
  return decimal(number)
}

function decimal(value) {
  return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2, useGrouping: false }).format(value)
}
