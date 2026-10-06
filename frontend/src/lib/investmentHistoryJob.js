// 详情页只读后，历史数据任务状态不再驱动页面内容。
// 这里只保留对后端任务状态的纯文本解释，供后台状态面板等只读场景使用。

export function describeHistoryJob(status) {
  return ({
    QUEUED: '正在排队补齐历史数据',
    RUNNING: '正在补齐历史数据',
    RETRY_WAIT: '历史数据准备将自动重试',
    PARTIAL: '历史数据已部分补齐',
    SUCCEEDED: '历史数据已补齐',
    SKIPPED: '本地历史数据已是最新',
    FAILED: '历史数据准备失败',
  }[status] || '尚未开始准备历史数据')
}

// 只有从未有过有效历史的产品才需要提示“历史数据准备中”。
export function needsHistoryPreparation(dataState, historyEndDate) {
  return !historyEndDate && dataState !== 'READY'
}
