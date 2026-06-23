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
          <el-popover placement="bottom-end" :width="340" trigger="click" popper-class="alert-pop">
            <template #reference>
              <button class="action-btn" title="预算预警">
                <el-badge :value="unreadAlertCount" :hidden="unreadAlertCount===0" :max="99">
                  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 8A6 6 0 006 8c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.73 21a2 2 0 01-3.46 0"/></svg>
                </el-badge>
              </button>
  </template>
    <div class="alert-panel" style="min-height:80px">
      <div style="font-weight:600;font-size:14px;margin-bottom:10px;color:#1e293b;border-bottom:1px solid #e2e8f0;padding-bottom:8px">&#x1f514; 预算预警</div>
      <div v-if="alertList.length===0" style="text-align:center;color:#94a3b8;padding:24px 0;font-size:13px">&#x2705; 暂无预警消息</div>
      <div v-for="a in alertList" :key="a.id" style="padding:10px 12px;margin-bottom:8px;border-radius:8px;font-size:13px;line-height:1.5" :style="{background:a.severity==='CRITICAL'?'#fef2f2':'#fffbeb',color:a.severity==='CRITICAL'?'#991b1b':'#92400e',border:a.severity==='CRITICAL'?'1px solid #fecaca':'1px solid #fde68a'}">
        <div style="font-weight:500;margin-bottom:3px">{{ a.message }}</div>
        <div style="font-size:11px;opacity:0.6">{{ (a.createdAt||'').slice(0,16) }}</div>
      </div>
    </div>
  </el-popover>
          <button class="action-btn" title="新建对话" @click="clearChat">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M12 3v18M3 12h18"/>
            </svg>
          </button>
        </div>
      </header>

      <div class="messages-area" ref="messageListRef">
        <div v-if="messages.length === 0 && !historyLoading" class="empty-state">
          <div class="empty-icon-wrapper">
            <div class="empty-icon-bg"></div>
            <div class="empty-icon">&#x1F4B0;</div>
          </div>
          <h2 class="empty-title">你好！我是智财Agent</h2>
          <p class="empty-desc">我可以帮你分析消费行为、监控财务状况、规划预算，还能快速记账</p>
          <p class="empty-hint">试试用自然语言记账，或点击下方问题快速开始</p>
        </div>

        <div
          v-for="(msg, index) in messages"
          :key="msg.role + '-' + index + '-' + (msg.content?.slice(0, 20))"
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
              <details v-if="msg.steps?.length" class="agent-steps">
                <summary>
                  <span>{{ stepsSummary(msg.steps) }}</span>
                  <span class="steps-caret">展开</span>
                </summary>
                <div class="steps-list">
                  <div
                    v-for="step in msg.steps"
                    :key="step.stepNumber"
                    class="agent-step"
                    :class="step.status"
                  >
                    <span class="step-index">{{ step.stepNumber }}</span>
                    <span class="step-summary">{{ step.summary }}</span>
                    <span class="step-state">{{ stepLabel(step.status) }}</span>
                  </div>
                </div>
              </details>
              <span v-html="renderMarkdown(msg.content)"></span>
              <div v-if="msg.pendingActions?.length" class="pending-actions">
                <div
                  v-for="action in msg.pendingActions"
                  :key="action.id"
                  class="pending-action-card"
                  :class="(action.status || '').toLowerCase()"
                >
                  <div class="pending-copy">
                    <div class="pending-title">{{ action.title || '待确认操作' }}</div>
                    <div class="pending-summary">{{ action.summary }}</div>
                  </div>
                  <div v-if="action.status === 'PENDING'" class="pending-buttons">
                    <button class="pending-btn primary" :disabled="action.confirming" @click="confirmPendingAction(action)">
                      {{ action.confirming ? '处理中' : '确认执行' }}
                    </button>
                    <button class="pending-btn ghost" :disabled="action.confirming" @click="cancelPendingAction(action)">取消</button>
                  </div>
                  <div v-else class="pending-status">
                    {{ action.status === 'CONFIRMED' ? '已执行' : '已取消' }}
                  </div>
                </div>
              </div>
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
              <details v-if="activeSteps.length" class="agent-steps" open>
                <summary>
                  <span>{{ stepsSummary(activeSteps) }}</span>
                  <span class="steps-caret">收起</span>
                </summary>
                <div class="steps-list">
                  <div
                    v-for="step in activeSteps"
                    :key="step.stepNumber"
                    class="agent-step"
                    :class="step.status"
                  >
                    <span class="step-index">{{ step.stepNumber }}</span>
                    <span class="step-summary">{{ step.summary }}</span>
                    <span class="step-state">{{ stepLabel(step.status) }}</span>
                  </div>
                </div>
              </details>
              <span v-html="renderMarkdown(typingDisplay)"></span>
              <div v-if="activePendingActions.length" class="pending-actions">
                <div
                  v-for="action in activePendingActions"
                  :key="action.id"
                  class="pending-action-card"
                  :class="(action.status || '').toLowerCase()"
                >
                  <div class="pending-copy">
                    <div class="pending-title">{{ action.title || '待确认操作' }}</div>
                    <div class="pending-summary">{{ action.summary }}</div>
                  </div>
                  <div class="pending-status">等待回复完成</div>
                </div>
              </div>
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
            :placeholder="loading ? '等待AI回复...' : '输入财务问题，或直接记账...'"
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
        <p class="input-hint">智财Agent · 消费分析 + 理财顾问 · 试试说「分析一下我的消费结构」</p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, nextTick, watch } from 'vue'
import { streamReactChatAPI, getChatHistoryAPI } from '../api/chat'
import { getUnreadAlertsAPI } from '../api/alert'
import { listPendingActionsAPI, confirmPendingActionAPI, cancelPendingActionAPI } from '../api/pendingAction'
import { ElMessage } from 'element-plus'
import { marked } from 'marked'

marked.setOptions({
  breaks: true,
  gfm: true
})

const messages = ref([])
const inputMessage = ref('')
const loading = ref(false)
const unreadAlertCount = ref(0)
const alertList = ref([])
const messageListRef = ref(null)
const showTypingAnimation = ref(false)
const typingDisplay = ref('')
const isTyping = ref(false)
const historyLoading = ref(true)
const activeSteps = ref([])
const activePendingActions = ref([])
let typingTimer = null
let alertTimer = null
let chatAbortController = null

const suggestions = [
  '我这个月消费情况如何？',
  '帮我分析支出分类占比',
  '有什么省钱建议吗？',
  '我在餐饮上花了多少？',
  '帮我看看我的预算设置'
]

const suggestionIcons = ['📝', '💰', '📊', '📈', '💡', '🍜']

function autoResizeInput(e) {
  const el = e.target
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 150) + 'px'
}

function renderMarkdown(content) {
  if (!content) return ''
  
  // 处理不完整的Markdown语法
  let safeContent = content
  
  // 处理不完整的代码块
  const codeBlockStart = (safeContent.match(/```/g) || []).length
  if (codeBlockStart % 2 !== 0) {
    safeContent += '\n```'
  }
  
  // 处理不完整的列表项（确保不会因为截断导致问题）
  // 处理不完整的加粗/斜体等内联格式
  
  try {
    const html = marked.parse(safeContent)
    return html
      .replace(/<table>/g, '<div class="md-table-wrap"><table>')
      .replace(/<\/table>/g, '</table></div>')
  } catch {
    // 如果Markdown解析失败，返回纯文本
    return safeContent.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  }
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
    const chunkSize = text[index] === '\n' ? 1 : 6
    const nextIndex = Math.min(index + chunkSize, text.length)
    typingDisplay.value += text.slice(index, nextIndex)
    // 减少滚动频率，提升性能
    if (index % 24 === 0 || text[index] === '\n') {
      scrollToBottom()
    }
    const delay = text[index] === '\n' ? 24 : 8
    typingTimer = setTimeout(() => typeText(text, nextIndex), delay)
  } else {
    isTyping.value = false
    const finishedSteps = activeSteps.value.map(step => ({ ...step }))
    const finishedPendingActions = activePendingActions.value.map(action => ({ ...action }))
    messages.value.push({
      role: 'ASSISTANT',
      content: text,
      steps: finishedSteps,
      pendingActions: finishedPendingActions
    })
    showTypingAnimation.value = false
    typingDisplay.value = ''
    activeSteps.value = []
    activePendingActions.value = []
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
  activeSteps.value = []
  activePendingActions.value = []
  chatAbortController = new AbortController()
  let terminalEventReceived = false

  try {
    await streamReactChatAPI({ message: text }, {
      step_started: payload => {
        upsertStep({
          stepNumber: payload.stepNumber,
          summary: payload.summary || '正在分析',
          tool: payload.tool,
          status: 'running'
        })
      },
      step_finished: payload => {
        upsertStep({
          stepNumber: payload.stepNumber,
          status: payload.success ? 'done' : 'failed'
        })
      },
      final: async payload => {
        terminalEventReceived = true
        await new Promise(resolve => setTimeout(resolve, 200))
        typeText(payload.response || '')
      },
      pending_actions: payload => {
        applyPendingActions(payload.actions || [])
      },
      error: payload => {
        terminalEventReceived = true
        finishWithError(payload.message)
      }
    }, chatAbortController.signal)
    if (!terminalEventReceived && loading.value) {
      finishWithError('连接已结束，但没有收到完整回复，请稍后再试。')
    }
  } catch {
    if (!chatAbortController?.signal.aborted) {
      finishWithError()
    }
  } finally {
    chatAbortController = null
  }
}

function upsertStep(step) {
  const index = activeSteps.value.findIndex(item => item.stepNumber === step.stepNumber)
  if (index >= 0) {
    const nextStep = { ...activeSteps.value[index], ...step }
    if (!step.summary) {
      nextStep.summary = activeSteps.value[index].summary
    }
    activeSteps.value[index] = nextStep
  } else {
    activeSteps.value.push(step)
  }
  scrollToBottom()
}

function applyPendingActions(actions) {
  const normalizedActions = Array.isArray(actions) ? actions.map(action => ({ ...action })) : []
  if (showTypingAnimation.value) {
    activePendingActions.value = normalizedActions
  } else {
    const lastAssistant = [...messages.value].reverse().find(message => message.role === 'ASSISTANT')
    if (lastAssistant) {
      lastAssistant.pendingActions = normalizedActions
    }
  }
  scrollToBottom()
}

function stepLabel(status) {
  if (status === 'done') return '完成'
  if (status === 'failed') return '失败'
  return '执行中'
}

function stepsSummary(steps) {
  const total = steps?.length || 0
  const running = steps?.some(step => step.status === 'running')
  const failed = steps?.filter(step => step.status === 'failed').length || 0
  if (running) return `Agent 正在执行 ${total} 个步骤`
  if (failed) return `Agent 完成 ${total} 个步骤，其中 ${failed} 个失败`
  return `Agent 已完成 ${total} 个步骤`
}

function finishWithError(message = '抱歉，我暂时无法处理你的请求，请稍后再试。') {
  const finishedSteps = activeSteps.value.map(step => ({ ...step }))
  const finishedPendingActions = activePendingActions.value.map(action => ({ ...action }))
  showTypingAnimation.value = false
  typingDisplay.value = ''
  activeSteps.value = []
  activePendingActions.value = []
  messages.value.push({
    role: 'ASSISTANT',
    content: message,
    steps: finishedSteps,
    pendingActions: finishedPendingActions
  })
  loading.value = false
  scrollToBottom()
}

async function confirmPendingAction(action) {
  if (!action || action.status !== 'PENDING') return
  action.confirming = true
  try {
    const res = await confirmPendingActionAPI(action.id)
    Object.assign(action, res.data || {}, { confirming: false })
    ElMessage.success('已执行')
  } catch {
    action.confirming = false
  }
}

async function cancelPendingAction(action) {
  if (!action || action.status !== 'PENDING') return
  action.confirming = true
  try {
    const res = await cancelPendingActionAPI(action.id)
    Object.assign(action, res.data || {}, { confirming: false })
    ElMessage.success('已取消')
  } catch {
    action.confirming = false
  }
}

function sendSuggestion(text) {
  inputMessage.value = text
  handleSend()
}

async function loadHistory() {
  historyLoading.value = true
  try {
    const res = await getChatHistoryAPI({ limit: 50 })
    if (res.code === 200 && Array.isArray(res.data)) {
      messages.value = res.data.map(m => ({
        role: m.role,
        content: m.content,
        traceId: m.traceId,
        steps: Array.isArray(m.steps) ? m.steps : []
      }))
    }
    await loadPendingActions()
  } catch (err) {
    console.error('加载聊天历史失败:', err)
  } finally {
    historyLoading.value = false
  }
}

async function loadPendingActions() {
  const res = await listPendingActionsAPI()
  const actions = Array.isArray(res.data) ? res.data : []
  if (!actions.length) return
  messages.value.push({
    role: 'ASSISTANT',
    content: '还有操作等待你确认，确认后才会真正写入系统。',
    pendingActions: actions
  })
}

async function fetchAlerts() {
  try {
    const res = await getUnreadAlertsAPI()
    if (res.code === 200 && Array.isArray(res.data)) {
      alertList.value = res.data
      unreadAlertCount.value = res.data.length
    }
  } catch (err) {
    console.error('获取预警失败:', err)
  }
}

function clearChat() {
  if (chatAbortController) {
    chatAbortController.abort()
    chatAbortController = null
  }
  if (typingTimer) {
    clearTimeout(typingTimer)
    typingTimer = null
  }
  showTypingAnimation.value = false
  typingDisplay.value = ''
  activeSteps.value = []
  activePendingActions.value = []
  isTyping.value = false
  loading.value = false
  messages.value = []
}

onMounted(async () => {
  await loadHistory()
  await fetchAlerts()
  alertTimer = setInterval(fetchAlerts, 30000)
  scrollToBottom()
})

onUnmounted(() => {
  if (chatAbortController) {
    chatAbortController.abort()
    chatAbortController = null
  }
  if (typingTimer) {
    clearTimeout(typingTimer)
    typingTimer = null
  }
  if (alertTimer) {
    clearInterval(alertTimer)
    alertTimer = null
  }
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
  padding: 12px 16px;
  font-size: 14px;
  line-height: 1.55;
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
  transition: all 0.1s ease-out;
}

.agent-steps {
  margin-bottom: 14px;
  border: 1px solid #dbeafe;
  border-radius: 10px;
  background: #f8fbff;
}

.agent-steps summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 34px;
  padding: 8px 10px;
  color: #1e40af;
  cursor: pointer;
  font-size: 12px;
  font-weight: 600;
  list-style: none;
}

.agent-steps summary::-webkit-details-marker {
  display: none;
}

.steps-caret {
  color: var(--text-muted);
  font-size: 11px;
  font-weight: 500;
  white-space: nowrap;
}

.agent-steps[open] .steps-caret {
  color: #2563eb;
}

.agent-steps:not([open]) .steps-caret::after {
  content: "⌄";
  margin-left: 4px;
}

.agent-steps[open] .steps-caret::after {
  content: "⌃";
  margin-left: 4px;
}

.steps-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 0 10px 10px;
}

.agent-step {
  display: grid;
  grid-template-columns: 18px minmax(0, 1fr) 44px;
  align-items: center;
  gap: 7px;
  min-width: 0;
  color: var(--text);
}

.agent-step.done {
  color: #166534;
}

.agent-step.failed {
  color: #991b1b;
}

.step-index {
  width: 18px;
  height: 18px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: var(--primary);
  color: #fff;
  font-size: 11px;
  font-weight: 700;
}

.agent-step.done .step-index {
  background: #16a34a;
}

.agent-step.failed .step-index {
  background: #dc2626;
}

.step-summary {
  overflow: hidden;
  color: var(--text);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.step-state {
  color: var(--text-muted);
  font-size: 11px;
  text-align: right;
}

.pending-actions {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 12px;
}

.pending-action-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid #bfdbfe;
  border-radius: 8px;
  background: #f8fbff;
}

.pending-action-card.confirmed {
  border-color: #bbf7d0;
  background: #f0fdf4;
}

.pending-action-card.cancelled {
  border-color: #e2e8f0;
  background: #f8fafc;
  opacity: 0.78;
}

.pending-copy {
  min-width: 0;
}

.pending-title {
  color: var(--text);
  font-size: 13px;
  font-weight: 700;
}

.pending-summary {
  margin-top: 3px;
  overflow: hidden;
  color: var(--text-muted);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pending-buttons {
  display: flex;
  flex-shrink: 0;
  gap: 6px;
}

.pending-btn {
  height: 30px;
  padding: 0 12px;
  border: 1px solid transparent;
  border-radius: 7px;
  cursor: pointer;
  font-size: 12px;
  font-weight: 600;
}

.pending-btn:disabled {
  cursor: wait;
  opacity: 0.7;
}

.pending-btn.primary {
  background: var(--primary);
  color: #fff;
}

.pending-btn.ghost {
  border-color: var(--border);
  background: var(--surface);
  color: var(--text-muted);
}

.pending-status {
  flex-shrink: 0;
  color: var(--text-muted);
  font-size: 12px;
}

.typing-bubble :deep(*),
.ai-bubble :deep(*) {
  transition: all 0.15s ease-out;
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
  margin: 4px 0;
}

:deep(.ai-bubble li) {
  margin-bottom: 2px;
}

:deep(.ai-bubble p) {
  margin: 4px 0;
}

:deep(.ai-bubble p:first-child) {
  margin-top: 0;
}

:deep(.ai-bubble p:last-child) {
  margin-bottom: 0;
}

:deep(.ai-bubble h1),
:deep(.ai-bubble h2),
:deep(.ai-bubble h3),
:deep(.ai-bubble h4) {
  margin: 8px 0 4px;
  line-height: 1.4;
}

:deep(.ai-bubble br) {
  display: block;
  content: "";
  margin-top: 2px;
}
</style>
