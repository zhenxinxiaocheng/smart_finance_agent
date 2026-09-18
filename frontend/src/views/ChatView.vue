<template>
  <div class="chat-view">
    <div class="chat-layout">
      <section v-if="isNewConversationLanding" class="new-chat-landing">
        <div class="new-chat-panel">
          <h1 class="new-chat-title">今天有什么计划？</h1>
          <div class="new-chat-composer">
            <button class="new-chat-add" type="button" aria-label="添加" disabled>
              <Plus />
            </button>
            <Textarea
              v-model="inputMessage"
              class="new-chat-input chat-input"
              :placeholder="conversationLoading ? '正在准备...' : '有问题，尽管问'"
              :disabled="conversationLoading"
              rows="1"
              @keydown.enter.prevent="handleSend"
              @input="autoResizeInput"
            />
            <Button
              class="new-chat-send"
              size="icon-lg"
              :variant="inputMessage.trim() && !conversationLoading ? 'default' : 'secondary'"
              :disabled="!inputMessage.trim() || conversationLoading"
              @click="handleSend"
            >
              <SendHorizontal />
            </Button>
          </div>
          <div class="new-chat-prompts">
            <Button
              v-for="item in landingSuggestions"
              :key="item"
              variant="outline"
              size="sm"
              class="new-chat-prompt"
              @click="sendSuggestion(item)"
            >
              {{ item }}
            </Button>
          </div>
        </div>
      </section>
      <div v-else class="chat-container">
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
                  <stop stop-color="#0f172a"/>
                  <stop offset="1" stop-color="#020617"/>
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
          <DropdownMenu>
            <DropdownMenuTrigger as-child>
              <Button variant="outline" size="sm" title="预算预警" class="relative">
                <Bell data-icon="inline-start" />
                预算预警
                <span
                  v-if="unreadAlertCount"
                  class="absolute -right-1 -top-1 flex size-4 items-center justify-center rounded-full bg-destructive text-[10px] font-semibold text-primary-foreground"
                >
                  {{ unreadAlertCount > 99 ? '99+' : unreadAlertCount }}
                </span>
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" class="w-80 p-3">
              <div class="alert-panel">
              <div class="alert-panel-head">
                <span>预算预警</span>
                <Badge variant="outline">{{ unreadAlertCount }}</Badge>
              </div>
              <div v-if="alertList.length===0" class="alert-empty">暂无预警消息</div>
              <div
                v-for="a in alertList"
                :key="a.id"
                class="alert-item"
                :class="a.severity === 'CRITICAL' ? 'critical' : 'warning'"
              >
                <div class="alert-item-head">
                  <span>{{ a.message }}</span>
                  <Badge :variant="a.severity === 'CRITICAL' ? 'destructive' : 'secondary'">
                    {{ alertSeverityLabel(a.severity) }}
                  </Badge>
                </div>
                <time>{{ (a.createdAt||'').slice(0,16) }}</time>
              </div>
              </div>
            </DropdownMenuContent>
          </DropdownMenu>
          <Button variant="outline" size="sm" title="新建对话" :disabled="conversationLoading || loading" @click="createNewConversation">
            <Plus data-icon="inline-start" />
            新建对话
          </Button>
        </div>
      </header>

      <div
        class="messages-area"
        :class="{ 'is-populated': messages.length > 0 || showTypingAnimation }"
        ref="messageListRef"
      >
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
                    <stop stop-color="#0f172a"/>
                    <stop offset="1" stop-color="#020617"/>
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
                  <span class="steps-actions">
                    <Button
                      v-if="msg.traceId"
                      class="trace-link"
                      variant="outline"
                      size="xs"
                      type="button"
                      @click.stop.prevent="openRunTrace(msg)"
                    >
                      运行详情
                    </Button>
                    <span class="steps-caret">展开</span>
                  </span>
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
                    <Badge class="step-state" :variant="stepBadgeVariant(step.status)">{{ stepLabel(step.status) }}</Badge>
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
                    <Button size="xs" :disabled="action.confirming" @click="confirmPendingAction(action)">
                      {{ action.confirming ? '处理中' : '确认执行' }}
                    </Button>
                    <Button variant="outline" size="xs" :disabled="action.confirming" @click="cancelPendingAction(action)">取消</Button>
                  </div>
                  <Badge v-else class="pending-status" :variant="action.status === 'CONFIRMED' ? 'secondary' : 'outline'">
                    {{ action.status === 'CONFIRMED' ? '已执行' : '已取消' }}
                  </Badge>
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
                    <stop stop-color="#0f172a"/>
                    <stop offset="1" stop-color="#020617"/>
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
                    <Badge class="step-state" :variant="stepBadgeVariant(step.status)">{{ stepLabel(step.status) }}</Badge>
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
                  <Badge class="pending-status" variant="outline">等待回复完成</Badge>
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
          <Button
            v-for="(item, index) in suggestions"
            :key="index"
            variant="outline"
            size="sm"
            class="suggestion-chip"
            @click="sendSuggestion(item)"
          >
            <span>{{ item }}</span>
          </Button>
        </div>
      </div>

      <div class="input-area-container">
        <div class="composer-shell">
          <Textarea
            v-model="inputMessage"
            class="chat-input"
            :placeholder="loading ? '等待回复...' : '随心输入...'"
            :disabled="loading"
            rows="2"
            @keydown.enter.prevent="handleSend"
            @input="autoResizeInput"
          />
          <div class="composer-toolbar">
            <div class="composer-tools-left">
              <DropdownMenu>
                <DropdownMenuTrigger as-child>
                  <button
                    class="context-orb"
                    :class="contextWindowLevelClass"
                    :style="{ '--context-used': `${contextUsagePercent}%` }"
                    type="button"
                    :title="contextWindowTitle"
                    aria-label="背景信息窗口"
                  >
                    <span class="context-orb-inner"></span>
                  </button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end" class="w-72 p-3">
                  <div class="context-popover">
                    <div class="context-popover-head">
                      <span>背景信息窗口</span>
                      <strong>{{ contextUsagePercentLabel }}</strong>
                    </div>
                    <div class="context-popover-bar">
                      <span :style="{ width: `${contextUsagePercent}%` }"></span>
                    </div>
                    <div class="context-popover-grid">
                      <div>
                        <span>总上下文</span>
                        <strong>{{ contextWindowBudgetLabel }}</strong>
                      </div>
                      <div>
                        <span>已用</span>
                        <strong>{{ contextUsedTokenLabel }}</strong>
                      </div>
                      <div>
                        <span>剩余</span>
                        <strong>{{ contextRemainingTokenLabel }}</strong>
                      </div>
                    </div>
                    <div class="context-popover-meta" v-if="contextCompressedCount || contextDroppedCount">
                      <span v-if="contextCompressedCount">已压缩 {{ contextCompressedCount }} 块</span>
                      <span v-if="contextDroppedCount">已丢弃 {{ contextDroppedCount }} 块</span>
                    </div>
                    <Button
                      class="context-compress-button"
                      size="sm"
                      variant="outline"
                      type="button"
                      :disabled="!canCompressCurrentContext"
                      @click="compressCurrentContext"
                    >
                      {{ compressingContext ? '压缩中...' : '手动压缩当前对话' }}
                    </Button>
                  </div>
                </DropdownMenuContent>
              </DropdownMenu>
            </div>
            <div class="composer-tools-right">
              <Button
                class="send-button"
                size="icon-lg"
                :variant="inputMessage.trim() && !loading ? 'default' : 'secondary'"
                :disabled="!inputMessage.trim() || loading"
                @click="handleSend"
              >
                <SendHorizontal />
              </Button>
            </div>
          </div>
        </div>
      </div>
    </div>
    </div>

    <Dialog v-model:open="traceDrawerVisible">
      <DialogContent class="trace-dialog">
        <DialogHeader class="trace-dialog-header">
          <DialogTitle>Agent 运行详情</DialogTitle>
          <DialogDescription>查看本次 Agent 响应的执行步骤、工具调用和 Skill 调用。</DialogDescription>
        </DialogHeader>
      <div v-if="traceLoading" class="trace-empty">加载中...</div>
      <div v-else-if="!traceDetail" class="trace-empty">暂无运行详情</div>
      <div v-else class="trace-detail">
        <section class="trace-section trace-overview">
          <div class="trace-title-row">
            <h3>{{ statusLabel(traceDetail.status) }}</h3>
            <span>{{ formatDuration(traceDetail.durationMs) }}</span>
          </div>
          <p>{{ traceDetail.query }}</p>
          <code>{{ traceDetail.traceId }}</code>
        </section>

        <section class="trace-section">
          <h3>执行步骤</h3>
          <div v-if="!traceDetail.steps?.length" class="trace-empty small">暂无步骤</div>
          <div v-else class="trace-step-list">
            <article v-for="step in traceDetail.steps" :key="step.stepNumber" class="trace-step-card">
              <div class="trace-step-head">
                <span>{{ step.stepNumber }}. {{ step.tool || 'Agent' }}</span>
                <strong :class="step.success ? 'ok' : 'fail'">{{ stepLabel(step.status) }}</strong>
              </div>
              <p>{{ step.summary || '无摘要' }}</p>
              <details v-if="step.input" class="trace-raw">
                <summary>输入</summary>
                <pre>{{ formatRaw(step.input) }}</pre>
              </details>
              <details v-if="step.observationSummary || step.errorMessage" class="trace-raw">
                <summary>{{ step.errorMessage ? '错误' : '观察结果' }}</summary>
                <pre>{{ step.errorMessage || step.observationSummary }}</pre>
              </details>
            </article>
          </div>
        </section>

        <section class="trace-section">
          <h3>Skill 调用</h3>
          <div v-if="!traceDetail.skillInvocations?.length" class="trace-empty small">暂无 Skill 调用</div>
          <div v-else class="skill-invocation-list">
            <article v-for="item in traceDetail.skillInvocations" :key="item.id" class="skill-invocation-card">
              <div class="trace-step-head">
                <span>{{ item.skillName }}</span>
                <strong :class="item.success ? 'ok' : 'fail'">
                  {{ item.blocked ? '已拦截' : item.success ? '成功' : '失败' }}
                </strong>
              </div>
              <p>{{ item.summary || '无摘要' }}</p>
              <time>{{ formatTime(item.createdAt) }}</time>
            </article>
          </div>
        </section>

        <section class="trace-section">
          <h3>运行反思</h3>
          <div v-if="traceReflectionLoading" class="trace-empty small">加载中...</div>
          <div v-else-if="!traceReflections.length" class="trace-empty small">暂无反思建议</div>
          <div v-else class="reflection-list">
            <article v-for="reflection in traceReflections" :key="reflection.id" class="reflection-card">
              <div class="trace-step-head">
                <span>{{ reflection.title || reflectionTypeLabel(reflection.suggestionType) }}</span>
                <Badge :variant="reflection.status === 'OPEN' ? 'outline' : 'secondary'">
                  {{ reflectionStatusLabel(reflection.status) }}
                </Badge>
              </div>
              <p>{{ reflection.summary }}</p>
              <div v-if="reflection.status === 'OPEN'" class="reflection-actions">
                <Button
                  v-if="canAcceptReflection(reflection)"
                  size="xs"
                  type="button"
                  :disabled="reflection.handling"
                  @click="acceptReflection(reflection)"
                >
                  {{ reflection.handling ? '处理中' : reflectionAcceptLabel(reflection) }}
                </Button>
                <Button
                  variant="outline"
                  size="xs"
                  type="button"
                  :disabled="reflection.handling"
                  @click="dismissReflection(reflection)"
                >
                  忽略
                </Button>
              </div>
            </article>
          </div>
        </section>
      </div>
      </DialogContent>
    </Dialog>
  </div>
</template>

<script setup>
import { computed, ref, onMounted, onUnmounted, nextTick, watch } from 'vue'
import { Bell, Plus, SendHorizontal } from '@lucide/vue'
import { useRoute, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle
} from '@/components/ui/dialog'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger
} from '@/components/ui/dropdown-menu'
import { Textarea } from '@/components/ui/textarea'
import { feedback } from '@/lib/feedback'
import {
  streamReactChatAPI,
  getChatHistoryAPI
} from '../api/chat'
import { getAgentRunDetailAPI } from '../api/agentRuns'
import { compressAgentContextAPI, getAgentContextConfigAPI, getAgentContextUsageAPI } from '../api/agentContext'
import { getUnreadAlertsAPI } from '../api/alert'
import { listPendingActionsAPI, confirmPendingActionAPI, cancelPendingActionAPI } from '../api/pendingAction'
import { listAgentReflectionsAPI, acceptAgentReflectionAPI, dismissAgentReflectionAPI } from '../api/agentReflections'
import { marked } from 'marked'
import { useChatConversationsStore } from '@/stores/chatConversations'

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
const contextUsage = ref(null)
const contextUsageLoading = ref(false)
const contextUsageSource = ref('')
const compressingContext = ref(false)
const traceDrawerVisible = ref(false)
const traceLoading = ref(false)
const traceDetail = ref(null)
const traceReflectionLoading = ref(false)
const traceReflections = ref([])
const route = useRoute()
const router = useRouter()
const conversationStore = useChatConversationsStore()
const { currentConversationId, conversationLoading } = storeToRefs(conversationStore)
let typingTimer = null
let alertTimer = null
let chatAbortController = null
let suppressNextConversationRouteLoad = false
const PENDING_ACTION_NOTICE = 'PENDING_ACTION_NOTICE'
const contextConfig = ref({ maxTokens: 0, reservedOutputTokens: 0, effectiveBudget: 0 })

const suggestions = [
  '我这个月消费情况如何？',
  '帮我分析支出分类占比',
  '有什么省钱建议吗？',
  '我在餐饮上花了多少？',
  '帮我看看我的预算设置'
]

const landingSuggestions = [
  '记录一笔今天的支出',
  '分析我这个月消费',
  '帮我规划下月预算'
]

const isNewConversationLanding = computed(() => {
  return !currentConversationId.value && !loading.value && !showTypingAnimation.value && !historyLoading.value
})

const contextMaxTokens = computed(() => {
  const maxTokens = Number(contextUsage.value?.maxTokens || contextConfig.value.maxTokens || 0)
  return maxTokens > 0 ? maxTokens : (contextConfig.value.effectiveBudget || 12000)
})

const contextUsedTokens = computed(() => Math.max(0, Number(contextUsage.value?.usedTokens || 0)))

const hasContextUsage = computed(() => !!contextUsage.value)

const isEstimatedContextUsage = computed(() => contextUsageSource.value === 'local')

const contextRemainingTokens = computed(() => {
  const remainingTokens = Number(contextUsage.value?.remainingTokens)
  if (Number.isFinite(remainingTokens) && remainingTokens >= 0) {
    return Math.max(0, remainingTokens)
  }
  return Math.max(0, contextMaxTokens.value - contextUsedTokens.value)
})

const contextUsagePercent = computed(() => {
  const ratio = Number(contextUsage.value?.usageRatio)
  if (Number.isFinite(ratio) && ratio > 0) {
    return Math.max(0, Math.min(100, Math.round(ratio * 100)))
  }
  if (!contextMaxTokens.value) return 0
  return Math.max(0, Math.min(100, Math.round((contextUsedTokens.value / contextMaxTokens.value) * 100)))
})

const contextUsagePercentLabel = computed(() => {
  if (contextUsageLoading.value) return '计算中'
  if (!hasContextUsage.value) return '未计算'
  return isEstimatedContextUsage.value ? `${contextUsagePercent.value}%估算` : `${contextUsagePercent.value}%`
})

const contextUsedTokenLabel = computed(() => {
  if (contextUsageLoading.value) return '计算中'
  if (!hasContextUsage.value) return '未计算'
  return formatTokenCount(contextUsedTokens.value)
})

const contextRemainingTokenLabel = computed(() => {
  if (contextUsageLoading.value) return '计算中'
  if (!hasContextUsage.value) return '未计算'
  return formatTokenCount(contextRemainingTokens.value)
})

const contextWindowBudgetLabel = computed(() => formatTokenCount(contextMaxTokens.value))

const contextWindowTitle = computed(() => {
  if (contextUsageLoading.value) {
    return '背景信息窗口：正在计算当前会话上下文占用'
  }
  if (!hasContextUsage.value) {
    return `背景信息窗口：总上下文 ${contextWindowBudgetLabel.value}，当前会话占用未计算`
  }
  if (isEstimatedContextUsage.value) {
    return `背景信息窗口：总上下文 ${contextWindowBudgetLabel.value}，已用约 ${contextUsedTokenLabel.value}，剩余约 ${contextRemainingTokenLabel.value}`
  }
  return `背景信息窗口：总上下文 ${contextWindowBudgetLabel.value}，已用 ${contextUsedTokenLabel.value}，剩余 ${contextRemainingTokenLabel.value}`
})

const contextCompressedCount = computed(() => contextUsage.value?.compressedKeys?.length || 0)

const contextDroppedCount = computed(() => contextUsage.value?.droppedKeys?.length || 0)

const canCompressCurrentContext = computed(() => {
  return Boolean(currentConversationId.value)
    && !compressingContext.value
    && !loading.value
    && messages.value.some(message => ['USER', 'ASSISTANT'].includes(message.role) && !message.noticeType)
})

const latestTraceId = computed(() => {
  const latest = [...messages.value].reverse().find(message => message.role === 'ASSISTANT' && message.traceId)
  return latest?.traceId || ''
})

const contextWindowLevelClass = computed(() => {
  if (!hasContextUsage.value) return 'is-unknown'
  if (contextUsagePercent.value >= 85) return 'is-high'
  if (contextUsagePercent.value >= 60) return 'is-medium'
  return 'is-low'
})

function formatTokenCount(value) {
  const count = Math.max(0, Math.round(Number(value || 0)))
  if (count >= 1000) {
    const next = count / 1000
    return `${Number.isInteger(next) ? next.toFixed(0) : next.toFixed(1)}k`
  }
  return `${count}`
}

function resetChatState() {
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
  contextUsage.value = null
  contextUsageLoading.value = false
  contextUsageSource.value = ''
  isTyping.value = false
  loading.value = false
  messages.value = []
}

async function initializeConversation() {
  await conversationStore.initializeConversation(route, router)
  conversationStore.syncCurrentConversationFromRoute(route)
}

async function createNewConversation() {
  if (conversationLoading.value || loading.value) return
  resetChatState()
  inputMessage.value = ''
  try {
    await conversationStore.startNewConversation(route, router)
  } catch {
    feedback.error('新建对话失败')
  }
  historyLoading.value = false
}

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

function typeText(text, traceId, index = 0) {
  if (index < text.length) {
    isTyping.value = true
    const chunkSize = text[index] === '\n' ? 1 : 28
    const nextIndex = Math.min(index + chunkSize, text.length)
    typingDisplay.value += text.slice(index, nextIndex)
    // 减少滚动频率，提升性能
    if (index % 24 === 0 || text[index] === '\n') {
      scrollToBottom()
    }
    const delay = text[index] === '\n' ? 8 : 1
    typingTimer = setTimeout(() => typeText(text, traceId, nextIndex), delay)
  } else {
    isTyping.value = false
    const finishedSteps = activeSteps.value.map(step => ({ ...step }))
    const finishedPendingActions = activePendingActions.value.map(action => ({ ...action }))
    messages.value.push({
      role: 'ASSISTANT',
      content: text,
      traceId,
      steps: finishedSteps,
      pendingActions: finishedPendingActions
    })
    showTypingAnimation.value = false
    typingDisplay.value = ''
    activeSteps.value = []
    activePendingActions.value = []
    loading.value = false
    conversationStore.loadConversations()
    scrollToBottom()
  }
}

async function handleSend() {
  const text = inputMessage.value.trim()
  if (!text || loading.value || conversationLoading.value) return
  if (!currentConversationId.value) {
    suppressNextConversationRouteLoad = true
    let conversation = null
    try {
      conversation = await conversationStore.createConversation(route, router)
    } catch {
      suppressNextConversationRouteLoad = false
      feedback.error('新建对话失败')
      return
    }
    if (!conversation?.id) {
      suppressNextConversationRouteLoad = false
      feedback.error('新建对话失败')
      return
    }
  }
  if (!currentConversationId.value) return

  messages.value.push({ role: 'USER', content: text })
  inputMessage.value = ''

  document.querySelectorAll('.chat-input').forEach(inputEl => {
    inputEl.style.height = 'auto'
  })

  scrollToBottom()
  loading.value = true
  showTypingAnimation.value = true
  typingDisplay.value = ''
  activeSteps.value = []
  activePendingActions.value = []
  contextUsage.value = null
  contextUsageSource.value = ''
  contextUsageLoading.value = true
  chatAbortController = new AbortController()
  let terminalEventReceived = false

  try {
    await streamReactChatAPI({ conversationId: currentConversationId.value, message: text }, {
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
        typeText(payload.response || '', payload.traceId)
      },
      pending_actions: payload => {
        applyPendingActions(payload.actions || [])
      },
      context_usage: payload => {
        contextUsage.value = normalizeContextUsage(payload?.usage || payload)
        contextUsageSource.value = 'server'
        contextUsageLoading.value = false
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
    contextUsageLoading.value = false
    chatAbortController = null
  }
}

function normalizeContextUsage(payload) {
  if (!payload || typeof payload !== 'object') {
    return null
  }
  return {
    maxTokens: Number(payload.maxTokens || 0),
    reservedOutputTokens: Number(payload.reservedOutputTokens || 0),
    usedTokens: Number(payload.usedTokens || 0),
    remainingTokens: Number(payload.remainingTokens || 0),
    usageRatio: Number(payload.usageRatio || 0),
    blocks: Array.isArray(payload.blocks) ? payload.blocks : [],
    droppedKeys: Array.isArray(payload.droppedKeys) ? payload.droppedKeys : [],
    compressedKeys: Array.isArray(payload.compressedKeys) ? payload.compressedKeys : []
  }
}

function estimateTextTokens(text) {
  const value = String(text || '')
  if (!value.trim()) return 0
  let cjk = 0
  let ascii = 0
  let other = 0
  for (const char of value) {
    const code = char.charCodeAt(0)
    if (code >= 0x4e00 && code <= 0x9fff) {
      cjk += 1
    } else if (code < 128) {
      ascii += 1
    } else {
      other += 1
    }
  }
  return Math.max(1, cjk + other + Math.ceil(ascii / 4))
}

function estimateLocalContextUsage() {
  const historyText = messages.value
    .filter(message => !message.noticeType && ['USER', 'ASSISTANT'].includes(message.role))
    .map(message => `${message.role}: ${message.content || ''}`)
    .join('\n')
  const usedTokens = estimateTextTokens(historyText)
  const maxTokens = contextMaxTokens.value || 12000
  const reservedOutputTokens = Number(contextConfig.value.reservedOutputTokens || 0)
  const promptBudget = Math.max(1, maxTokens - reservedOutputTokens)
  return {
    maxTokens,
    reservedOutputTokens,
    usedTokens,
    remainingTokens: Math.max(0, promptBudget - usedTokens),
    usageRatio: Math.min(1, usedTokens / promptBudget),
    blocks: [{
      key: 'local-history-estimate',
      displayName: '当前会话历史估算',
      sourceType: 'HISTORY',
      tokens: usedTokens,
      hidden: false,
      compressed: false
    }],
    droppedKeys: [],
    compressedKeys: []
  }
}

async function loadContextUsage() {
  if (!currentConversationId.value) {
    contextUsage.value = null
    contextUsageSource.value = ''
    contextUsageLoading.value = false
    return
  }
  contextUsageLoading.value = true
  try {
    const res = await getAgentContextUsageAPI({ conversationId: currentConversationId.value })
    const usage = normalizeContextUsage(res?.data)
    contextUsage.value = usage || estimateLocalContextUsage()
    contextUsageSource.value = usage ? 'server' : (contextUsage.value ? 'local' : '')
  } catch {
    contextUsage.value = estimateLocalContextUsage()
    contextUsageSource.value = contextUsage.value ? 'local' : ''
  } finally {
    contextUsageLoading.value = false
  }
}

async function compressCurrentContext() {
  if (!canCompressCurrentContext.value) {
    if (!currentConversationId.value) {
      feedback.error('请先选择一个对话')
    } else if (!messages.value.some(message => ['USER', 'ASSISTANT'].includes(message.role) && !message.noticeType)) {
      feedback.error('当前对话暂无可压缩内容')
    }
    return
  }
  compressingContext.value = true
  try {
    const payload = {
      conversationId: currentConversationId.value,
      scope: 'CONVERSATION',
      ...(latestTraceId.value ? { traceId: latestTraceId.value } : {})
    }
    const res = await compressAgentContextAPI(payload)
    const summary = res?.data
    if (summary?.compressedTokens || summary?.originalTokens) {
      feedback.success(`已压缩上下文：${formatTokenCount(summary.originalTokens)} -> ${formatTokenCount(summary.compressedTokens)}`)
    } else {
      feedback.success('已压缩当前对话上下文')
    }
    await loadContextUsage()
  } catch (err) {
    console.error('上下文压缩失败:', err)
  } finally {
    compressingContext.value = false
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
  const normalizedActions = normalizePendingActions(actions)
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

function normalizePendingActions(actions) {
  return Array.isArray(actions)
    ? actions.filter(action => action?.status === 'PENDING').map(action => ({ ...action }))
    : []
}

function removeHandledPendingActions() {
  messages.value.forEach(message => {
    if (Array.isArray(message.pendingActions)) {
      message.pendingActions = normalizePendingActions(message.pendingActions)
    }
  })
  messages.value = messages.value.filter(message => {
    return !message.noticeType || message.noticeType !== PENDING_ACTION_NOTICE || message.pendingActions?.length
  })
}

function stepLabel(status) {
  if (status === 'done') return '完成'
  if (status === 'failed') return '失败'
  return '执行中'
}

function stepBadgeVariant(status) {
  if (status === 'failed') return 'destructive'
  if (status === 'done') return 'secondary'
  return 'outline'
}

function alertSeverityLabel(severity) {
  return severity === 'CRITICAL' ? '严重' : '提醒'
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
    removeHandledPendingActions()
    feedback.success('已执行')
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
    removeHandledPendingActions()
    feedback.success('已取消')
  } catch {
    action.confirming = false
  }
}

function sendSuggestion(text) {
  inputMessage.value = text
  handleSend()
}

async function loadHistory() {
  if (!currentConversationId.value) {
    messages.value = []
    contextUsage.value = null
    contextUsageSource.value = ''
    historyLoading.value = false
    return
  }
  historyLoading.value = true
  try {
    const res = await getChatHistoryAPI({ conversationId: currentConversationId.value, limit: 50 })
    if (res.code === 200 && Array.isArray(res.data)) {
      messages.value = res.data.map(m => ({
        role: m.role,
        content: m.content,
        traceId: m.traceId,
        steps: Array.isArray(m.steps) ? m.steps : []
      }))
    }
  } catch (err) {
    console.error('加载聊天历史失败:', err)
  } finally {
    historyLoading.value = false
  }
  try {
    await loadPendingActions()
  } catch (err) {
    console.error('加载待确认操作失败:', err)
  } finally {
    await loadContextUsage()
  }
}

async function loadPendingActions() {
  messages.value = messages.value.filter(message => message.noticeType !== PENDING_ACTION_NOTICE)
  const res = await listPendingActionsAPI({ status: 'PENDING' })
  const actions = normalizePendingActions(res.data)
  if (!actions.length) return
  messages.value.push({
    role: 'ASSISTANT',
    content: '还有操作等待你确认，确认后才会真正写入系统。',
    pendingActions: actions,
    noticeType: PENDING_ACTION_NOTICE
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


async function loadContextConfig() {
  try {
    const res = await getAgentContextConfigAPI()
    if (res?.data) {
      contextConfig.value = {
        maxTokens: Number(res.data.maxTokens) || 0,
        reservedOutputTokens: Number(res.data.reservedOutputTokens) || 0,
        effectiveBudget: Number(res.data.effectiveBudget) || 0
      }
    }
  } catch (e) {
    /* silence; SSE context_usage will overwrite */
  }
}

function clearChat() {
  resetChatState()
}

onMounted(async () => {
  await loadContextConfig()
  await initializeConversation()
  await loadHistory()
  await fetchAlerts()
  if (route.query.traceId) {
    await openRunTrace({ traceId: String(route.query.traceId) })
  }
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

watch(() => route.query.traceId, traceId => {
  if (traceId) {
    openRunTrace({ traceId: String(traceId) })
  }
})

watch(() => route.query.conversationId, async conversationId => {
  const nextId = Number(conversationId)
  if (!Number.isFinite(nextId) || nextId <= 0) {
    resetChatState()
    conversationStore.syncCurrentConversationFromRoute(route)
    historyLoading.value = false
    return
  }
  if (suppressNextConversationRouteLoad) {
    suppressNextConversationRouteLoad = false
    conversationStore.syncCurrentConversationFromRoute(route)
    historyLoading.value = false
    return
  }
  if (Number(currentConversationId.value) === nextId && messages.value.length) return
  resetChatState()
  conversationStore.syncCurrentConversationFromRoute(route)
  await loadHistory()
})

watch(
  () => [route.query.scheduleTraceId, route.query.refresh],
  async ([scheduleTraceId, refresh]) => {
    if (!scheduleTraceId && !refresh) return
    await loadHistory()
    scrollToBottom()
  }
)

async function openRunTrace(message) {
  if (!message?.traceId) return
  traceDrawerVisible.value = true
  traceLoading.value = true
  traceDetail.value = null
  traceReflections.value = []
  try {
    const res = await getAgentRunDetailAPI(message.traceId)
    traceDetail.value = res.data || null
    await loadTraceReflections(message.traceId)
  } catch {
    feedback.error('运行详情加载失败')
  } finally {
    traceLoading.value = false
  }
}

async function loadTraceReflections(traceId) {
  traceReflectionLoading.value = true
  try {
    const res = await listAgentReflectionsAPI()
    const items = Array.isArray(res.data) ? res.data : []
    traceReflections.value = items.filter(item => item.traceId === traceId)
  } catch {
    traceReflections.value = []
  } finally {
    traceReflectionLoading.value = false
  }
}

async function acceptReflection(reflection) {
  if (!canAcceptReflection(reflection)) return
  reflection.handling = true
  try {
    const res = await acceptAgentReflectionAPI(reflection.id)
    Object.assign(reflection, res.data || {}, { handling: false })
    feedback.success(reflectionAcceptSuccessMessage(reflection))
    await loadPendingActions()
  } catch {
    reflection.handling = false
  }
}

async function dismissReflection(reflection) {
  if (!reflection || reflection.status !== 'OPEN') return
  reflection.handling = true
  try {
    const res = await dismissAgentReflectionAPI(reflection.id)
    Object.assign(reflection, res.data || {}, { handling: false })
    feedback.success('已忽略')
  } catch {
    reflection.handling = false
  }
}

function canAcceptReflection(reflection) {
  return reflection?.status === 'OPEN'
    && ['SKILL_CANDIDATE', 'MEMORY_CANDIDATE', 'SCHEDULE_CANDIDATE'].includes(reflection?.suggestionType)
}

function reflectionAcceptLabel(reflection) {
  if (reflection?.suggestionType === 'MEMORY_CANDIDATE') return '生成待确认记忆'
  if (reflection?.suggestionType === 'SCHEDULE_CANDIDATE') return '生成待确认任务'
  return '生成待确认 Skill'
}

function reflectionAcceptSuccessMessage(reflection) {
  if (reflection?.suggestionType === 'MEMORY_CANDIDATE') return '已生成待确认记忆'
  if (reflection?.suggestionType === 'SCHEDULE_CANDIDATE') return '已生成待确认任务'
  return '已生成待确认 Skill'
}

function reflectionTypeLabel(type) {
  if (type === 'SKILL_CANDIDATE') return 'Skill 候选'
  if (type === 'SCHEDULE_CANDIDATE') return '周期任务候选'
  if (type === 'MEMORY_CANDIDATE') return '记忆候选'
  if (type === 'RISK_WARNING') return '风险提示'
  return '反思建议'
}

function reflectionStatusLabel(status) {
  if (status === 'OPEN') return '待处理'
  if (status === 'ACCEPTED') return '已采纳'
  if (status === 'DISMISSED') return '已忽略'
  return status || '未知'
}

function statusLabel(status) {
  if (status === 'COMPLETED') return '已完成'
  if (status === 'FAILED') return '失败'
  if (status === 'RUNNING') return '运行中'
  return status || '未知状态'
}

function formatDuration(value) {
  const ms = Number(value || 0)
  if (!ms) return ''
  if (ms < 1000) return `${ms}ms`
  return `${(ms / 1000).toFixed(1)}s`
}

function formatRaw(value) {
  if (value == null) return ''
  if (typeof value !== 'string') {
    return JSON.stringify(value, null, 2)
  }
  try {
    return JSON.stringify(JSON.parse(value), null, 2)
  } catch {
    return value
  }
}

function formatTime(value) {
  return value ? String(value).slice(0, 19) : ''
}
</script>

<style scoped>
.chat-view {
  height: calc(100vh - 64px - var(--app-page-padding) - var(--app-page-padding));
  min-height: 560px;
  display: flex;
  flex-direction: column;
  --chat-panel: var(--card);
  --chat-panel-foreground: var(--card-foreground);
  --chat-muted: var(--muted);
  --chat-muted-foreground: var(--muted-foreground);
  --chat-border: var(--border);
  --chat-primary: var(--primary);
  --chat-primary-foreground: var(--primary-foreground);
  --chat-shadow: 0 1px 2px rgb(0 0 0 / 0.04);
}

.chat-layout {
  width: 100%;
  height: 100%;
  min-height: 0;
  display: flex;
}

.new-chat-landing {
  width: 100%;
  min-height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 32px 24px 96px;
}

.new-chat-panel {
  width: min(820px, 100%);
  display: grid;
  justify-items: center;
  gap: 28px;
}

.new-chat-title {
  margin: 0;
  color: var(--foreground);
  font-size: clamp(28px, 4vw, 38px);
  font-weight: 700;
  letter-spacing: 0;
  line-height: 1.2;
}

.new-chat-composer {
  width: 100%;
  min-height: 64px;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px 10px 18px;
  border: 1px solid color-mix(in oklab, var(--border) 76%, white 8%);
  border-radius: 999px;
  background: color-mix(in oklab, var(--card) 82%, white 6%);
  box-shadow: 0 18px 45px rgb(0 0 0 / 0.18);
}

.new-chat-add {
  width: 28px;
  height: 28px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: var(--muted-foreground);
}

.new-chat-add svg {
  width: 22px;
  height: 22px;
}

.new-chat-input {
  min-height: 36px !important;
  max-height: 96px;
  flex: 1;
  padding: 5px 0 !important;
  font-size: 16px !important;
}

.new-chat-send {
  flex-shrink: 0;
  border-radius: 999px;
}

.new-chat-prompts {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 12px;
}

.new-chat-prompt {
  border-radius: 999px;
  background: transparent;
}

.chat-container {
  width: 100%;
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--chat-panel);
  border: 1px solid var(--chat-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--chat-shadow);
  position: relative;
}

.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: calc(var(--app-card-padding) * 0.9) var(--app-card-padding);
  background: var(--chat-panel);
  border-bottom: 1px solid var(--chat-border);
  flex-shrink: 0;
}

.header-left {
  display: flex;
  align-items: center;
  min-width: 0;
  gap: 12px;
}

.header-avatar svg {
  width: 36px;
  height: 36px;
  display: block;
}

.header-avatar svg rect,
.ai-avatar svg rect {
  fill: var(--chat-primary);
}

.header-title {
  font-size: 16px;
  font-weight: 650;
  color: var(--chat-panel-foreground);
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
  background: var(--chat-muted-foreground);
  transition: all 0.3s;
}

.status-dot.active {
  background: #22c55e;
  box-shadow: 0 0 6px rgba(34, 197, 94, 0.4);
}

.status-text {
  font-size: 12px;
  color: var(--chat-muted-foreground);
}

.header-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 6px;
}

.alert-panel {
  min-height: 80px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.alert-panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--chat-border);
  color: var(--chat-panel-foreground);
  font-size: 14px;
  font-weight: 700;
}

.alert-empty {
  padding: 22px 0;
  color: var(--chat-muted-foreground);
  font-size: 13px;
  text-align: center;
}

.alert-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px 12px;
  border: 1px solid var(--chat-border);
  border-radius: var(--radius);
  background: var(--chat-panel);
  font-size: 13px;
  line-height: 1.5;
}

.alert-item.critical {
  border-color: rgba(239, 68, 68, 0.28);
  background: rgba(239, 68, 68, 0.06);
}

.alert-item.warning {
  border-color: rgba(245, 158, 11, 0.3);
  background: rgba(245, 158, 11, 0.07);
}

.alert-item-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  color: var(--chat-panel-foreground);
  font-weight: 600;
}

.alert-item time {
  color: var(--chat-muted-foreground);
  font-size: 11px;
}

.messages-area {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow-y: auto;
  padding: var(--app-card-padding);
  background: color-mix(in oklab, var(--chat-muted) 46%, transparent);
  scroll-behavior: smooth;
}

.messages-area.is-populated {
  justify-content: flex-start;
}

.messages-area.is-populated::before {
  content: "";
  flex: 0 0 auto;
  margin-top: auto;
}

.messages-area::-webkit-scrollbar {
  width: 5px;
}

.messages-area::-webkit-scrollbar-track {
  background: transparent;
}

.messages-area::-webkit-scrollbar-thumb {
  background: var(--chat-border);
  border-radius: 10px;
}

.message-wrapper {
  display: flex;
  gap: 10px;
  margin-bottom: 18px;
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
  fill: var(--chat-muted);
}

.message-content {
  max-width: min(78%, 860px);
  display: flex;
  flex-direction: column;
}

.message-label {
  font-size: 11px;
  font-weight: 600;
  color: var(--chat-muted-foreground);
  margin-bottom: 6px;
  letter-spacing: 0.3px;
  text-transform: uppercase;
}

.user-message .message-label {
  text-align: right;
}

.message-bubble {
  padding: 10px 14px;
  font-size: 14px;
  line-height: 1.45;
  word-wrap: break-word;
  white-space: pre-wrap;
}

.ai-bubble {
  background: var(--chat-panel);
  border: 1px solid var(--chat-border);
  border-radius: var(--radius);
  box-shadow: var(--chat-shadow);
  color: var(--chat-panel-foreground);
}

.user-bubble {
  align-self: flex-end;
  min-width: 0;
  max-width: 100%;
  padding: 8px 12px;
  white-space: normal;
  background: var(--chat-primary);
  border-radius: var(--radius);
  color: var(--chat-primary-foreground);
  box-shadow: var(--chat-shadow);
}

.user-bubble :deep(p:first-child) {
  margin-top: 0;
}

.user-bubble :deep(p:last-child) {
  margin-bottom: 0;
}

.typing-bubble {
  min-height: 28px;
  transition: all 0.1s ease-out;
}

.agent-steps {
  margin-bottom: 14px;
  border: 1px solid var(--chat-border);
  border-radius: var(--radius);
  background: var(--chat-muted);
}

.agent-steps summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 34px;
  padding: 8px 10px;
  color: var(--chat-panel-foreground);
  cursor: pointer;
  font-size: 12px;
  font-weight: 600;
  list-style: none;
}

.agent-steps summary::-webkit-details-marker {
  display: none;
}

.steps-caret {
  color: var(--chat-muted-foreground);
  font-size: 11px;
  font-weight: 500;
  white-space: nowrap;
}

.steps-actions {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.trace-link {
  border: 1px solid var(--chat-border);
  border-radius: var(--radius);
  background: var(--chat-panel);
  color: var(--chat-panel-foreground);
  cursor: pointer;
  font-size: 11px;
  font-weight: 600;
  line-height: 1;
  padding: 5px 8px;
}

.trace-link:hover {
  background: var(--chat-muted);
}

.agent-steps[open] .steps-caret {
  color: var(--chat-primary);
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
  grid-template-columns: 18px minmax(0, 1fr) auto;
  align-items: center;
  gap: 7px;
  min-width: 0;
  color: var(--chat-panel-foreground);
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
  background: var(--chat-primary);
  color: var(--chat-primary-foreground);
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
  color: var(--chat-panel-foreground);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.step-state {
  justify-self: end;
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
  border: 1px solid var(--chat-border);
  border-radius: var(--radius);
  background: var(--chat-muted);
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
  color: var(--chat-panel-foreground);
  font-size: 13px;
  font-weight: 700;
}

.pending-summary {
  margin-top: 3px;
  overflow: hidden;
  color: var(--chat-muted-foreground);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pending-buttons {
  display: flex;
  flex-shrink: 0;
  gap: 6px;
}

.pending-status {
  flex-shrink: 0;
}

.typing-bubble :deep(*),
.ai-bubble :deep(*) {
  transition: all 0.15s ease-out;
}

.typing-cursor {
  animation: cursorBlink 0.8s step-end infinite;
  color: var(--chat-primary);
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
  color: var(--chat-muted-foreground);
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
  min-height: 360px;
  text-align: center;
  padding: 40px 20px;
}

.empty-icon-wrapper {
  position: relative;
  margin-bottom: 24px;
}

.empty-icon-bg {
  width: 72px;
  height: 72px;
  border-radius: 50%;
  background: var(--chat-muted);
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
  color: var(--chat-panel-foreground);
  margin: 0 0 8px;
}

.empty-desc {
  font-size: 14px;
  color: var(--chat-muted-foreground);
  margin: 0 0 4px;
  max-width: 360px;
}

.empty-hint {
  font-size: 13px;
  color: var(--chat-muted-foreground);
  margin: 12px 0 0;
  padding: 8px 16px;
  background: var(--chat-panel);
  border-radius: var(--radius);
  border: 1px solid var(--chat-border);
}

.suggestions-bar {
  flex-shrink: 0;
  padding: 10px var(--app-card-padding) 6px;
  background: var(--chat-panel);
  border-top: 1px solid var(--chat-border);
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
  flex-shrink: 0;
}

.input-area-container {
  flex-shrink: 0;
  padding: 10px var(--app-card-padding) calc(var(--app-card-padding) * 0.9);
  background: var(--chat-panel);
}

.composer-shell {
  display: grid;
  gap: 10px;
  background: var(--background);
  border: 1px solid var(--chat-border);
  border-radius: 18px;
  padding: 14px 10px 10px;
  box-shadow: var(--chat-shadow);
  transition: all 0.2s;
}

.composer-shell:focus-within {
  border-color: var(--chat-primary);
  box-shadow: 0 0 0 3px color-mix(in oklab, var(--chat-primary) 18%, transparent), var(--chat-shadow);
}

.chat-input {
  width: 100%;
  border: 0 !important;
  outline: 0 !important;
  font-size: 14px;
  font-family: inherit;
  color: var(--foreground);
  background: transparent !important;
  resize: none;
  line-height: 1.6;
  max-height: 170px;
  min-height: 54px;
  padding: 0 4px;
  box-shadow: none !important;
}

.chat-input:focus,
.chat-input:focus-visible {
  border-color: transparent !important;
  box-shadow: none !important;
  outline: 0 !important;
  --tw-ring-color: transparent !important;
  --tw-ring-shadow: 0 0 #0000 !important;
}

.chat-input::placeholder {
  color: var(--chat-muted-foreground);
}

.chat-input:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.send-button {
  flex-shrink: 0;
  border-radius: 999px;
}

.composer-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-width: 0;
}

.composer-tools-left,
.composer-tools-right {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.composer-tools-left {
  flex: 1;
  overflow: hidden;
}

.composer-tools-right {
  flex-shrink: 0;
}

.context-orb {
  --context-ring: #94a3b8;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  border: 0;
  border-radius: 999px;
  padding: 0;
  background: conic-gradient(var(--context-ring) var(--context-used), color-mix(in oklab, var(--chat-muted-foreground) 28%, transparent) 0);
  cursor: pointer;
  transition: transform 0.15s ease, opacity 0.15s ease;
  flex-shrink: 0;
}

.context-orb:hover {
  transform: scale(1.08);
}

.context-orb-inner {
  width: 10px;
  height: 10px;
  border-radius: 999px;
  background: var(--chat-panel);
}

.context-orb.is-low {
  --context-ring: #22c55e;
}

.context-orb.is-unknown {
  --context-ring: #94a3b8;
  opacity: 0.72;
}

.context-orb.is-medium {
  --context-ring: #f59e0b;
}

.context-orb.is-high {
  --context-ring: #ef4444;
}

.context-popover {
  display: grid;
  gap: 10px;
  font-size: 12px;
}

.context-popover-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: var(--chat-panel-foreground);
}

.context-popover-head strong {
  font-size: 18px;
}

.context-popover-bar {
  height: 6px;
  overflow: hidden;
  border-radius: 999px;
  background: var(--chat-muted);
}

.context-popover-bar span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--chat-primary);
}

.context-popover-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
}

.context-popover-grid div {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.context-popover-grid span,
.context-popover-meta {
  color: var(--chat-muted-foreground);
}

.context-popover-grid strong {
  color: var(--chat-panel-foreground);
  font-weight: 700;
}

.context-popover-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.context-compress-button {
  width: 100%;
}

.input-hint {
  text-align: center;
  font-size: 11px;
  color: var(--chat-muted-foreground);
  margin: 0;
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
  border: 1px solid var(--chat-border);
  text-align: left;
}

:deep(.md-table-wrap th) {
  background: var(--chat-muted);
  font-weight: 600;
  color: var(--chat-panel-foreground);
}

:deep(.ai-bubble strong) {
  color: var(--chat-primary);
}

:deep(.user-bubble strong) {
  color: #fff;
  opacity: 0.95;
}

:deep(.ai-bubble code) {
  background: var(--chat-muted);
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 13px;
  color: var(--chat-panel-foreground);
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

.trace-dialog {
  width: min(680px, calc(100vw - 32px));
  max-width: min(680px, calc(100vw - 32px));
  max-height: min(760px, calc(100vh - 64px));
  display: flex;
  flex-direction: column;
  gap: 0;
  overflow: hidden;
  border-color: var(--border);
  background: color-mix(in oklab, var(--popover) 94%, var(--background) 6%);
  padding: 0;
  box-shadow: 0 24px 70px rgb(0 0 0 / 0.35);
}

.trace-dialog-header {
  flex-shrink: 0;
  border-bottom: 1px solid var(--border);
  background: color-mix(in oklab, var(--popover) 96%, var(--background) 4%);
  padding: 18px 42px 14px 18px;
}

.trace-detail {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 0;
  overflow-y: auto;
  padding: 14px 18px 18px;
}

.trace-section {
  border: 1px solid var(--border);
  border-radius: 8px;
  background: color-mix(in oklab, var(--card) 94%, var(--background) 6%);
  padding: 14px;
}

.trace-section h3 {
  margin: 0 0 10px;
  color: var(--card-foreground);
  font-size: 15px;
}

.trace-overview p,
.trace-step-card p,
.skill-invocation-card p,
.reflection-card p {
  margin: 0 0 10px;
  color: var(--muted-foreground);
  font-size: 13px;
  line-height: 1.5;
}

.trace-overview code {
  display: block;
  overflow-wrap: anywhere;
  border-radius: var(--radius);
  background: var(--muted);
  color: var(--muted-foreground);
  font-size: 12px;
  padding: 8px;
}

.trace-title-row,
.trace-step-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.trace-title-row span,
.trace-step-head strong,
.skill-invocation-card time {
  color: var(--muted-foreground);
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
}

.trace-step-list,
.skill-invocation-list,
.reflection-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.trace-step-card,
.skill-invocation-card,
.reflection-card {
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: color-mix(in oklab, var(--muted) 86%, var(--card) 14%);
  padding: 12px;
}

.trace-step-head span {
  overflow: hidden;
  color: var(--foreground);
  font-size: 13px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.trace-step-head .ok {
  color: #16a34a;
}

.trace-step-head .fail {
  color: #dc2626;
}

.reflection-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}

.trace-raw {
  border-top: 1px solid var(--border);
  margin-top: 8px;
  padding-top: 8px;
}

.trace-raw summary {
  color: var(--foreground);
  cursor: pointer;
  font-size: 12px;
  font-weight: 700;
}

.trace-raw pre {
  overflow: auto;
  max-height: 220px;
  border-radius: var(--radius);
  background: color-mix(in oklab, var(--background) 92%, black 8%);
  color: var(--foreground);
  font-size: 12px;
  line-height: 1.5;
  margin: 8px 0 0;
  padding: 10px;
  white-space: pre-wrap;
}

.trace-empty {
  color: var(--muted-foreground);
  font-size: 13px;
  padding: 18px;
  text-align: center;
}

.trace-empty.small {
  padding: 8px;
}

@media (max-width: 720px) {
  .new-chat-landing {
    align-items: flex-start;
    padding: 72px 8px 48px;
  }

  .new-chat-panel {
    gap: 22px;
  }

  .new-chat-composer {
    min-height: 58px;
    gap: 8px;
    padding: 8px 10px 8px 14px;
  }

  .new-chat-prompts {
    gap: 8px;
  }

  .composer-shell {
    border-radius: 16px;
    padding: 12px 8px 8px;
  }

  .composer-toolbar {
    gap: 6px;
  }

  .composer-tools-left,
  .composer-tools-right {
    gap: 4px;
  }
}
</style>
