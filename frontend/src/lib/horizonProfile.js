function integer(value) {
  const number = Number(value)
  return Number.isInteger(number) ? number : null
}

export function normalizeHorizonProfile(value) {
  const source = value && typeof value === 'object' ? value : {}
  const settings = Array.isArray(source.settings)
    ? source.settings.map((item, index) => ({
        code: String(item?.code ?? '').trim().toUpperCase(),
        displayName: String(item?.displayName ?? '').trim(),
        sortOrder: integer(item?.sortOrder) ?? (index + 1) * 10,
        minHoldingDays: integer(item?.minHoldingDays),
        maxHoldingDays: integer(item?.maxHoldingDays),
        targetHoldingDays: integer(item?.targetHoldingDays),
        primary: Boolean(item?.primary),
        sourceScope: item?.sourceScope == null ? undefined : String(item.sourceScope),
      }))
    : []

  settings.sort((left, right) => left.sortOrder - right.sortOrder)
  return {
    version: source.version == null ? '' : String(source.version),
    templateVersion: source.templateVersion == null ? '' : String(source.templateVersion),
    sourceScope: source.sourceScope == null ? 'TEMPLATE' : String(source.sourceScope),
    hasAssetOverride: Boolean(source.hasAssetOverride),
    maxHistoryTradingDays: integer(source.maxHistoryTradingDays),
    warnings: Array.isArray(source.warnings) ? source.warnings.map(String) : [],
    settings,
  }
}

export function validateHorizonSettings(settings) {
  if (!Array.isArray(settings) || settings.length === 0) return '至少需要一个分析周期'
  const errors = []
  const codes = new Set()
  let primaryCount = 0

  settings.forEach((item, index) => {
    const label = `第 ${index + 1} 个周期`
    const code = String(item?.code ?? '').trim().toUpperCase()
    const displayName = String(item?.displayName ?? '').trim()
    const minimum = integer(item?.minHoldingDays)
    const maximum = integer(item?.maxHoldingDays)
    const target = integer(item?.targetHoldingDays)
    if (!code || !displayName) errors.push(`${label}的代码和名称不能为空`)
    if (code && codes.has(code)) errors.push(`周期代码 ${code} 重复`)
    codes.add(code)
    if (minimum == null || maximum == null || minimum < 1 || maximum < 1) {
      errors.push(`${label}的天数必须为正整数`)
    } else if (minimum > maximum) {
      errors.push(`${label}的最小天数不能大于最大天数`)
    } else if (target == null || target < minimum || target > maximum) {
      errors.push(`${label}的目标天数必须位于最小和最大天数之间`)
    }
    if (item?.primary) primaryCount += 1
  })
  if (primaryCount > 1) errors.push('只能设置一个主要周期')
  return errors.join('；')
}

export function profileSavePayload(profile) {
  const normalized = normalizeHorizonProfile(profile)
  return {
    settings: normalized.settings.map((item, index) => ({
      code: item.code,
      displayName: item.displayName,
      sortOrder: integer(item.sortOrder) ?? (index + 1) * 10,
      minHoldingDays: item.minHoldingDays,
      maxHoldingDays: item.maxHoldingDays,
      targetHoldingDays: item.targetHoldingDays,
      primary: item.primary,
    })),
  }
}

export function chooseHorizonCode(profile, requestedCode) {
  const settings = normalizeHorizonProfile(profile).settings
  const requested = String(requestedCode ?? '').trim().toUpperCase()
  if (settings.some(item => item.code === requested)) return requested
  return settings.find(item => item.primary)?.code || settings[0]?.code || ''
}

export function horizonDisplayName(profile, code) {
  const normalizedCode = String(code ?? '').trim().toUpperCase()
  if (!normalizedCode) return '未选择周期'
  const setting = normalizeHorizonProfile(profile).settings.find(
    item => item.code === normalizedCode
  )
  return setting?.displayName || normalizedCode
}
