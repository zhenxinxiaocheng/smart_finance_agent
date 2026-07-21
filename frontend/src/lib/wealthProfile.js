export function hasCashBaselineChanged(initialized, original, current) {
  if (current === '' || current == null) return false
  if (!initialized) return true
  return Number(original) !== Number(current)
}
