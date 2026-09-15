import { researchLabels } from './researchExplanation.js'

export const needsTraining = type => ['ML_ELASTIC_NET', 'ML_XGBOOST'].includes(type)

export function userMessage(value, code, fallback = '操作未完成，请稍后重试。') {
  if (researchLabels[code]) return researchLabels[code]
  if (researchLabels[value]) return researchLabels[value]
  if (typeof value !== 'string' || !/[\u3400-\u9fff]/.test(value)) return fallback
  if (/Traceback|Exception|Error\b|\b[A-Z][A-Z0-9]+_[A-Z0-9_]+\b|\b(?:config|provenance|runtime)\.|[{}]|(?:[A-Za-z]:\\)|\/(?:app|src)\//.test(value)) return fallback
  return value
}

export function operationMessage(error) {
  const body = error?.response?.data || {}
  const detail = body.detail
  const code = body.data?.reasonCode || body.data?.errorCode || detail?.code || error?.errorCode
  if (error?.response?.status === 404) return '记录不存在或已删除，请返回后重新选择。'
  if (error?.response?.status === 403) return '当前账户无法访问这条记录。'
  if (!error?.response && ['ERR_NETWORK', 'ECONNABORTED'].includes(error?.code)) return '连接暂时不可用，请稍后重试。'
  return userMessage(body.message || (typeof detail === 'string' ? detail : detail?.message) || error?.message, code)
}
