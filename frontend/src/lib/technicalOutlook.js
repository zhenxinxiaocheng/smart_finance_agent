const DIRECTION_META = {
  BULLISH: { label: '看涨', action: '重点关注', tone: 'text-emerald-600 dark:text-emerald-400', variant: 'secondary' },
  LEAN_BULLISH: { label: '偏强', action: '保持关注', tone: 'text-emerald-600 dark:text-emerald-400', variant: 'secondary' },
  SIDEWAYS: { label: '横盘观察', action: '等待方向', tone: 'text-amber-600 dark:text-amber-400', variant: 'outline' },
  LEAN_BEARISH: { label: '偏弱', action: '谨慎观察', tone: 'text-destructive', variant: 'destructive' },
  BEARISH: { label: '看跌', action: '暂时回避', tone: 'text-destructive', variant: 'destructive' },
}

const CONFIDENCE_LABELS = {
  HIGH: '高置信度',
  MEDIUM: '中等置信度',
  LOW: '低置信度',
}

export function directionMeta(direction, status) {
  const unavailable = {
    PREPARING: '分析待更新', INSUFFICIENT: '历史样本不足',
    BLOCKED: '数据校验未通过', UNAVAILABLE: '分析暂不可用', FAILED: '分析暂不可用',
  }[status]
  return (!unavailable && DIRECTION_META[direction]) || {
    label: unavailable || '分析暂不可用',
    action: '暂不判断',
    tone: 'text-muted-foreground',
    variant: 'outline',
  }
}

export function directionLabel(direction, status) {
  return directionMeta(direction, status).label
}

export function confidenceLabel(confidence) {
  return CONFIDENCE_LABELS[confidence] || '置信度待评估'
}

export function invalidationText(invalidation, formatPrice) {
  if (!invalidation || typeof formatPrice !== 'function') return '暂未形成明确失效条件'
  if (invalidation.type === 'BELOW' && invalidation.price != null) {
    return `跌破 ${formatPrice(invalidation.price)}`
  }
  if (invalidation.type === 'ABOVE' && invalidation.price != null) {
    return `突破 ${formatPrice(invalidation.price)}`
  }
  if (invalidation.type === 'OUTSIDE_RANGE'
      && invalidation.lower != null && invalidation.upper != null) {
    return `离开 ${formatPrice(invalidation.lower)} – ${formatPrice(invalidation.upper)} 区间`
  }
  return '暂未形成明确失效条件'
}
