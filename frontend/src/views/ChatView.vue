<template>
  <div class="chat-view">
    <div class="chat-container">
      <header class="chat-header">
        <div class="header-left">
          <div class="header-avatar">
            <svg viewBox="0 0 40 40" fill="none" xmlns="http://www.w3.org/2000/svg">
              <rect width="40" height="40" rx="10" fill="url(#header-grad)"/>
              <path d="M12 28V18l8-4 8 4v10l-8 4-8-4z" fill="white" opacity="0.9"/>
              <path d="M12 18l8 4 8-4" stroke="white" stroke-width="1.5" opacity="0.6"/>
              <path d="M20 22v8" stroke="white" stroke-width="1.5" opacity="0.6"/>
              <defs>
                <linearGradient id="header-grad" x1="0" y1="0" x2="40" y2="40">
                  <stop stop-color="#2563eb"/>
                  <stop offset="1" stop-color="#1d4ed8"/>
                </linearGradient>
              </defs>
            </svg>
          </div>
          <div class="header-info">
            <h1 class="header-title">智财Agent</h1>
            <div class="header-status">
              <span class="status-dot" :class="{ active: !loading }"></span>
              <span class="status-text">{{ loading ? '思考中...' : '在线' }}</span>
            </div>
          </div>
        </div>
        <div class="header-actions">
          <button class="action-btn" title="新建对话" @click="clearChat">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M12 3v18M3 12h18"/>
            </svg>
          </button>
        </div>
      </header>

      <div class="messages-area" ref="messageListRef">
        <div v-if="messages.length === 0" class="empty-state">
          <div class="empty-icon-wrapper">
            <div class="empty-icon-bg"></div>
            <div class="empty-icon">&#x1F4B0;</div>
          </div>
          <h2 class="empty-title">你好！我是智财Agent</h2>
          <p class="empty-desc">我可以帮你分析消费记录、发现省钱机会、生成财务报告</p>
          <p class="empty-hint">试试问我你的财务状况，或点击下方问题快速开始</p>
        </div>

        <div
          v-for="(msg, index) in messages"
          :key="index"
          class="message-wrapper"
          :class="msg.role === 'USER' ? 'user-message' : 'ai-message'"
        >
          <div v-if="msg.role === 'ASSISTANT'" class="message-avatar-box">
            <div class="ai-avatar">
              <svg viewBox="0 0 32 32" fill="none" xmlns="http://www.w3.org/2000/svg">
                <rect width="32" height="32" rx="8" fill="url(#ai-grad)"/>
                <path d="M9 22V14l7-3.5 7 3.5v8l-7 3.5L9 22z" fill="white" opacity="0.9"/>
                <path d="M9 14l7 3.5 7-3.5" stroke="white" stroke-width="1.2" opacity="0.6"/>
                <path d="M16 17.5v6.5" stroke="white" stroke-width="1.2" opacity="0.6"/>
                <defs>
                  <linearGradient id="ai-grad" x1="0" y1="0" x2="32" y2="32">
                    <stop stop-color="#2563eb"/>
                    <stop offset="1" stop-color="#1d4ed8"/>
                  </linearGradient>
                </defs>
              </svg>
            </div>
          </div>

          <div class="message-content">
            <div class="message-label">{{ msg.role === 'USER' ? '你' : '智财Agent' }}</div>
            <div
              v-if="msg.role === 'USER'"
              class="message-bubble user-bubble"
            >
              <span v-html="renderMarkdown(msg.content)"></span>
            </div>
            <div
              v-else
              class="message-bubble ai-bubble"
            >
              <span v-html="renderMarkdown(msg.content)"></span>
            </div>
          </div>

          <div v-if="msg.role === 'USER'" class="message-avatar-box">
            <div class="user-avatar">
              <svg viewBox="0 0 32 32" fill="none" xmlns="http://www.w3.org/2000/svg">
                <rect width="32" height="32" rx="8" fill="#e2e8f0"/>
                <circle cx="16" cy="12" r="4" fill="#64748b"/>
                <ellipse cx="16" cy="24" rx="7" ry="5" fill="#64748b"/>
              </svg>
            </div>
          </div>
        </div>

        <div v-if="showTypingAnimation" class="message-wrapper ai-message">
          <div class="message-avatar-box">
            <div class="ai-avatar">
              <svg viewBox="0 0 32 32" fill="none" xmlns="http://www.w3.org/2000/svg">
                <rect width="32" height="32" rx="8" fill="url(#ai-grad-typing)"/>
                <path d="M9 22V14l7-3.5 7 3.5v8l-7 3.5L9 22z" fill="white" opacity="0.9"/>
                <defs>
                  <linearGradient id="ai-grad-typing" x1="0" y1="0" x2="32" y2="32">
                    <stop stop-color="#2563eb"/>
                    <stop offset="1" stop-color="#1d4ed8"/>
                  </linearGradient>
                </defs>
              </svg>
            </div>
          </div>
          <div class="message-content">
            <div class="message-label">智财Agent</div>
            <div class="message-bubble ai-bubble typing-bubble">
              <span v-html="typingDisplay"></span>
              <span class="typing-cursor" v-if="isTyping">|</span>
              <span class="typing-dots" v-else>
                <span>.</span><span>.</span><span>.</span>
              </span>
            </div>
          </div>
        </div>
      </div>

      <div class="suggestions-bar" v-if="messages.length === 0 || !loading">
        <div class="suggestions-scroll">
          <button
            v-for="(item, index) in suggestions"
            :key="index"
            class="suggestion-chip"
            @click="sendSuggestion(item)"
          >
            <span class="chip-icon">{{ suggestionIcons[index] }}</span>
            <span>{{ item }}</span>
          </button>
        </div>
      </div>

      <div class="input-area-container">
        <div class="input-wrapper">
          <textarea
            v-model="inputMessage"
            class="chat-input"
            :placeholder="loading ? '等待AI回复...' : '输入你的财务问题...'"
            :disabled="loading"
            rows="1"
            @keydown.enter.prevent="handleSend"
            @input="autoResizeInput"
          ></textarea>
          <button
            class="send-button"
            :class="{ active: inputMessage.trim() && !loading }"
            :disabled="!inputMessage.trim() || loading"
            @click="handleSend"
          >
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
              <line x1="22" y1="2" x2="11" y2="13"></line>
              <polygon points="22 2 15 22 11 13 2 9 22 2"></polygon>
            </svg>
          </button>
        </div>
        <p class="input-hint">智财Agent · 基于你的消费数据提供个性化建议</p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, nextTick, watch } from 'vue'
import { sendChatAPI, getChatHistoryAPI } from '../api/chat'
import { marked } from 'marked'

marked.setOptions({
  breaks: true,
  gfm: true
})

const messages = ref([])
const inputMessage = ref('')
const loading = ref(false)
const messageListRef = ref(null)
const showTypingAnimation = ref(false)
const typingDisplay = ref('')
const isTyping = ref(false)
let typingTimer = null

const suggestions = [
  '我这个月消费情况如何？',
  '帮我分析支出分类占比',
  '有什么省钱建议吗？',
  '上个月收支总结如何？',
  '我在餐饮上花了多少？'
]

const suggestionIcons = ['📊', '📈', '💡', '📋', '🍜']

function autoResizeInput(e) {
  const el = e.target
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 150) + 'px'
}

function renderMarkdown(content) {
  if (!content) return ''
  const html = marked.parse(content)
  return html
    .replace(/<table>/g, '<div class="md-table-wrap"><table>')
    .replace(/<\/table>/g, '</table></div>')
}

function scrollToBottom() {
  nextTick(() => {
    nextTick(() => {
      const el = messageListRef.value
      if (el) {
        el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' })
      }
    })
  })
}

function typeText(text, index = 0) {
  if (index < text.length) {
    isTyping.value = true
    typingDisplay.value += text[index]
    scrollToBottom()
    const delay = text[index] === '\n' ? 80 : 20 + Math.random() * 15
    typingTimer = setTimeout(() => typeText(text, index + 1), delay)
  } else {
    isTyping.value = false
    messages.value.push({ role: 'ASSISTANT', content: text })
    showTypingAnimation.value = false
    typingDisplay.value = ''
    loading.value = false
    scrollToBottom()
  }
}

async function handleSend() {
  const text = inputMessage.value.trim()
  if (!text || loading.value) return

  messages.value.push({ role: 'USER', content: text })
  inputMessage.value = ''

  const inputEl = document.querySelector('.chat-input')
  if (inputEl) {
    inputEl.style.height = 'auto'
  }

  scrollToBottom()
  loading.value = true
  showTypingAnimation.value = true
  typingDisplay.value = ''

  try {
    const res = await sendChatAPI({ message: text })
    if (res.code === 200) {
      const response = res.data.response
      await new Promise(resolve => setTimeout(resolve, 300))
      typeText(response)
    } else {
      showTypingAnimation.value = false
      typingDisplay.value = ''
      messages.value.push({
        role: 'ASSISTANT',
        content: '抱歉，我暂时无法处理你的请求，请稍后再试。'
      })
      loading.value = false
      scrollToBottom()
    }
  } catch {
    showTypingAnimation.value = false
    typingDisplay.value = ''
    messages.value.push({
      role: 'ASSISTANT',
      content: '抱歉，我暂时无法处理你的请求，请稍后再试。'
    })
    loading.value = false
    scrollToBottom()
  }
}

function sendSuggestion(text) {
  inputMessage.value = text
  handleSend()
}

async function loadHistory() {
  try {
    const res = await getChatHistoryAPI({ limit: 50 })
    if (res.code === 200) {
      messages.value = res.data || []
    }
  } catch {
    // silently fail
  }
}

function clearChat() {
  if (typingTimer) {
    clearTimeout(typingTimer)
    typingTimer = null
  }
  showTypingAnimation.value = false
  typingDisplay.value = ''
  isTyping.value = false
  loading.value = false
  messages.value = []
}

onMounted(async () => {
  await loadHistory()
  scrollToBottom()
})

watch(messages, () => {
  scrollToBottom()
}, { deep: true })
</script>

<style scoped>
.chat-view {
  height: 100%;
  display: flex;
  justify-content: center;
  --primary: #2563eb;
  --primary-dark: #1d4ed8;
  --primary-light: #dbeafe;
  --primary-50: #eff6ff;
  --accent: #06b6d4;
  --bg: #f0f4f8;
  --surface: #ffffff;
  --text: #1e293b;
  --text-muted: #64748b;
  --text-light: #94a3b8;
  --border: #e2e8f0;
  --shadow: 0 1px 3px rgba(0, 0, 0, 0.06), 0 1px 2px rgba(0, 0, 0, 0.04);
  --shadow-lg: 0 10px 25px rgba(0, 0, 0, 0.08), 0 4px 10px rgba(0, 0, 0, 0.04);
  --radius: 12px;
  --radius-lg: 20px;
}

.chat-container {
  width: 100%;
  max-width: 900px;
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--bg);
  position: relative;
}

.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 24px;
  background: rgba(255, 255, 255, 0.8);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border-bottom: 1px solid var(--border);
  flex-shrink: 0;
  position: sticky;
  top: 0;
  z-index: 10;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 14px;
}

.header-avatar svg {
  width: 40px;
  height: 40px;
  display: block;
}

.header-title {
  font-size: 17px;
  font-weight: 700;
  color: var(--text);
  margin: 0;
  line-height: 1.3;
}

.header-status {
  display: flex;
  align-items: center;
  gap: 6px;
}

.status-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--text-light);
  transition: all 0.3s;
}

.status-dot.active {
  background: #22c55e;
  box-shadow: 0 0 6px rgba(34, 197, 94, 0.4);
}

.status-text {
  font-size: 12px;
  color: var(--text-muted);
}

.header-actions {
  display: flex;
  gap: 6px;
}

.action-btn {
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  border-radius: 8px;
  cursor: pointer;
  color: var(--text-muted);
  transition: all 0.2s;
}

.action-btn:hover {
  background: var(--primary-50);
  color: var(--primary);
}

.messages-area {
  flex: 1;
  overflow-y: auto;
  padding: 24px 24px 8px;
  scroll-behavior: smooth;
}

.messages-area::-webkit-scrollbar {
  width: 5px;
}

.messages-area::-webkit-scrollbar-track {
  background: transparent;
}

.messages-area::-webkit-scrollbar-thumb {
  background: var(--border);
  border-radius: 10px;
}

.message-wrapper {
  display: flex;
  gap: 10px;
  margin-bottom: 24px;
  animation: messageSlideIn 0.35s ease-out;
}

@keyframes messageSlideIn {
  from {
    opacity: 0;
    transform: translateY(12px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.user-message {
  flex-direction: row-reverse;
}

.message-avatar-box {
  flex-shrink: 0;
  margin-top: 18px;
}

.ai-avatar svg,
.user-avatar svg {
  width: 32px;
  height: 32px;
  display: block;
}

.user-avatar svg rect {
  fill: var(--primary-light);
}

.message-content {
  max-width: 75%;
  display: flex;
  flex-direction: column;
}

.message-label {
  font-size: 11px;
  font-weight: 600;
  color: var(--text-muted);
  margin-bottom: 6px;
  letter-spacing: 0.3px;
  text-transform: uppercase;
}

.user-message .message-label {
  text-align: right;
}

.message-bubble {
  padding: 14px 18px;
  font-size: 14px;
  line-height: 1.7;
  word-wrap: break-word;
  white-space: pre-wrap;
}

.ai-bubble {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 4px var(--radius-lg) var(--radius-lg) var(--radius-lg);
  box-shadow: var(--shadow);
  color: var(--text);
}

.user-bubble {
  background: linear-gradient(135deg, var(--primary), var(--primary-dark));
  border-radius: var(--radius-lg) 4px var(--radius-lg) var(--radius-lg);
  color: #fff;
  box-shadow: 0 2px 8px rgba(37, 99, 235, 0.2);
}

.typing-bubble {
  min-height: 28px;
}

.typing-cursor {
  animation: cursorBlink 0.8s step-end infinite;
  color: var(--primary);
  font-weight: 300;
  margin-left: 2px;
}

@keyframes cursorBlink {
  50% { opacity: 0; }
}

.typing-dots span {
  animation: dotPulse 1.4s infinite;
  font-size: 20px;
  font-weight: 700;
  color: var(--text-muted);
}

.typing-dots span:nth-child(2) { animation-delay: 0.2s; }
.typing-dots span:nth-child(3) { animation-delay: 0.4s; }

@keyframes dotPulse {
  0%, 20% { opacity: 0; }
  50% { opacity: 1; }
  100% { opacity: 0; }
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  min-height: 400px;
  text-align: center;
  padding: 40px 20px;
}

.empty-icon-wrapper {
  position: relative;
  margin-bottom: 24px;
}

.empty-icon-bg {
  width: 80px;
  height: 80px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--primary-light), #c7d2fe);
  animation: pulseGlow 2s ease-in-out infinite;
}

@keyframes pulseGlow {
  0%, 100% { transform: scale(1); opacity: 0.7; }
  50% { transform: scale(1.1); opacity: 1; }
}

.empty-icon {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  font-size: 36px;
}

.empty-title {
  font-size: 20px;
  font-weight: 700;
  color: var(--text);
  margin: 0 0 8px;
}

.empty-desc {
  font-size: 14px;
  color: var(--text-muted);
  margin: 0 0 4px;
  max-width: 360px;
}

.empty-hint {
  font-size: 13px;
  color: var(--text-light);
  margin: 12px 0 0;
  padding: 8px 16px;
  background: var(--surface);
  border-radius: 8px;
  border: 1px solid var(--border);
}

.suggestions-bar {
  flex-shrink: 0;
  padding: 8px 24px 4px;
  overflow: hidden;
}

.suggestions-scroll {
  display: flex;
  gap: 8px;
  overflow-x: auto;
  padding: 4px 0;
  -ms-overflow-style: none;
  scrollbar-width: none;
}

.suggestions-scroll::-webkit-scrollbar {
  display: none;
}

.suggestion-chip {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  border: 1px solid var(--border);
  border-radius: 20px;
  background: var(--surface);
  cursor: pointer;
  font-size: 13px;
  color: var(--text);
  white-space: nowrap;
  transition: all 0.2s;
  flex-shrink: 0;
}

.suggestion-chip:hover {
  border-color: var(--primary);
  background: var(--primary-50);
  color: var(--primary);
  box-shadow: 0 2px 8px rgba(37, 99, 235, 0.12);
}

.chip-icon {
  font-size: 15px;
}

.input-area-container {
  flex-shrink: 0;
  padding: 8px 24px 16px;
}

.input-wrapper {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: 8px 8px 8px 18px;
  box-shadow: var(--shadow);
  transition: all 0.2s;
}

.input-wrapper:focus-within {
  border-color: var(--primary);
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.1), var(--shadow);
}

.chat-input {
  flex: 1;
  border: none;
  outline: none;
  font-size: 14px;
  font-family: inherit;
  color: var(--text);
  background: transparent;
  resize: none;
  line-height: 1.6;
  max-height: 150px;
  min-height: 24px;
}

.chat-input::placeholder {
  color: var(--text-light);
}

.chat-input:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.send-button {
  width: 42px;
  height: 42px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 12px;
  background: var(--border);
  color: var(--text-light);
  cursor: not-allowed;
  transition: all 0.2s;
  flex-shrink: 0;
}

.send-button.active {
  background: var(--primary);
  color: #fff;
  cursor: pointer;
  box-shadow: 0 2px 8px rgba(37, 99, 235, 0.25);
}

.send-button.active:hover {
  background: var(--primary-dark);
  transform: scale(1.05);
}

.send-button.active:active {
  transform: scale(0.95);
}

.input-hint {
  text-align: center;
  font-size: 11px;
  color: var(--text-light);
  margin: 8px 0 0;
  letter-spacing: 0.2px;
}

:deep(.md-table-wrap) {
  overflow-x: auto;
  margin: 8px 0;
}

:deep(.md-table-wrap table) {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

:deep(.md-table-wrap th),
:deep(.md-table-wrap td) {
  padding: 8px 12px;
  border: 1px solid var(--border);
  text-align: left;
}

:deep(.md-table-wrap th) {
  background: var(--primary-50);
  font-weight: 600;
  color: var(--text);
}

:deep(.ai-bubble strong) {
  color: var(--primary);
}

:deep(.user-bubble strong) {
  color: #fff;
  opacity: 0.95;
}

:deep(.ai-bubble code) {
  background: var(--primary-50);
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 13px;
  color: var(--primary-dark);
}

:deep(.user-bubble code) {
  background: rgba(255, 255, 255, 0.2);
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 13px;
}

:deep(.ai-bubble ul),
:deep(.ai-bubble ol) {
  padding-left: 20px;
  margin: 6px 0;
}

:deep(.ai-bubble li) {
  margin-bottom: 4px;
}

:deep(.ai-bubble p) {
  margin: 6px 0;
}

:deep(.ai-bubble p:first-child) {
  margin-top: 0;
}

:deep(.ai-bubble p:last-child) {
  margin-bottom: 0;
}
</style>
