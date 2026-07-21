export function createInvestmentAssetDraftController(form) {
  let wasOpen = false
  let previousAssetId = null

  return {
    sync(open, asset) {
      const assetId = asset?.id ?? null
      const shouldHydrate = Boolean(open && asset && (!wasOpen || assetId !== previousAssetId))
      wasOpen = open
      previousAssetId = assetId

      if (!shouldHydrate) return false
      form.quantity = asset.quantity == null ? '' : String(asset.quantity)
      form.averageCost = asset.averageCost == null ? '' : String(asset.averageCost)
      form.note = asset.note || ''
      return true
    }
  }
}
