function emitFeedback(type, message, options = {}) {
  window.dispatchEvent(new CustomEvent('app-feedback', {
    detail: { type, message, ...options }
  }))
}

export const feedback = {
  success(message, options) {
    emitFeedback('success', message, options)
  },
  warning(message, options) {
    emitFeedback('warning', message, options)
  },
  error(message, options) {
    emitFeedback('error', message, options)
  },
  info(message, options) {
    emitFeedback('info', message, options)
  }
}

export function confirmAction(message) {
  return Promise.resolve(window.confirm(message))
}
