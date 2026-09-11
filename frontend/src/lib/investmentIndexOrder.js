export function moveInvestmentIndex(indexes, draggedCode, targetCode) {
  const result = Array.isArray(indexes) ? [...indexes] : []
  const fromIndex = result.findIndex(item => item.indexCode === draggedCode)
  const targetIndex = result.findIndex(item => item.indexCode === targetCode)
  if (fromIndex < 0 || targetIndex < 0 || fromIndex === targetIndex) return result
  const [draggedItem] = result.splice(fromIndex, 1)
  result.splice(targetIndex, 0, draggedItem)
  return result
}

export function moveInvestmentIndexToPosition(indexes, draggedCode, position) {
  const result = Array.isArray(indexes) ? [...indexes] : []
  const fromIndex = result.findIndex(item => item.indexCode === draggedCode)
  if (fromIndex < 0 || !Number.isFinite(position) || !result.length) return result
  const targetIndex = Math.max(0, Math.min(result.length - 1, Math.round(position)))
  if (fromIndex === targetIndex) return result
  const [draggedItem] = result.splice(fromIndex, 1)
  result.splice(targetIndex, 0, draggedItem)
  return result
}
