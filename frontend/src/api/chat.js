import request from './request'

export function sendChatAPI(data) {
  return request.post('/chat', data, {
    timeout: 120000
  })
}

export async function streamReactChatAPI(data, handlers = {}, signal) {
  const token = localStorage.getItem('token')
  const response = await fetch('/api/chat/react/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    body: JSON.stringify(data),
    signal
  })

  if (response.status === 401) {
    localStorage.removeItem('token')
    localStorage.removeItem('user')
    const router = await import('../router/index.js')
    router.default.push('/login')
    throw new Error('登录已过期')
  }

  if (!response.ok || !response.body) {
    throw new Error(`流式请求失败：${response.status}`)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    buffer = buffer.replace(/\r\n/g, '\n')

    let boundary = buffer.indexOf('\n\n')
    while (boundary >= 0) {
      const chunk = buffer.slice(0, boundary)
      buffer = buffer.slice(boundary + 2)
      dispatchSseChunk(chunk, handlers)
      boundary = buffer.indexOf('\n\n')
    }
  }

  if (buffer.trim()) {
    dispatchSseChunk(buffer, handlers)
  }
}

function dispatchSseChunk(chunk, handlers) {
  let eventName = 'message'
  const dataLines = []

  chunk.split(/\r?\n/).forEach(line => {
    if (line.startsWith('event:')) {
      eventName = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).trimStart())
    }
  })

  if (!dataLines.length) return

  const rawData = dataLines.join('\n')
  let payload = rawData
  try {
    payload = JSON.parse(rawData)
  } catch {
    // Keep raw string payload for non-JSON SSE data.
  }

  handlers[eventName]?.(payload)
  handlers.message?.(eventName, payload)
}

export function getChatHistoryAPI(params) {
  return request.get('/chat/history', { params })
}
