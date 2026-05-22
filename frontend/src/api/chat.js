import request from './request'

export function sendChatAPI(data) {
  return request.post('/chat', data, {
    timeout: 120000
  })
}

export function getChatHistoryAPI(params) {
  return request.get('/chat/history', { params })
}
