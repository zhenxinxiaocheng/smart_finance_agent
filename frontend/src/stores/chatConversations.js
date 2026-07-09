import { ref } from 'vue'
import { defineStore } from 'pinia'
import {
  createChatConversationAPI,
  deleteChatConversationAPI,
  listChatConversationsAPI,
  renameChatConversationAPI
} from '@/api/chat'

function normalizeConversationId(route) {
  const value = Number(route?.query?.conversationId)
  return Number.isFinite(value) && value > 0 ? value : null
}

export const useChatConversationsStore = defineStore('chatConversations', () => {
  const conversations = ref([])
  const conversationLoading = ref(false)
  const currentConversationId = ref(null)
  const editingConversationId = ref(null)
  const editingConversationTitle = ref('')

  async function syncConversationRoute(router, route, conversationId, replace = true) {
    const query = { ...route.query }
    if (conversationId) {
      query.conversationId = String(conversationId)
    } else {
      delete query.conversationId
    }
    if (replace) {
      await router.replace({ path: route.path, query })
    } else {
      await router.push({ path: route.path, query })
    }
  }

  async function loadConversations() {
    conversationLoading.value = true
    try {
      const res = await listChatConversationsAPI()
      conversations.value = Array.isArray(res.data) ? res.data : []
    } catch {
      conversations.value = []
    } finally {
      conversationLoading.value = false
    }
  }

  async function initializeConversation(route, router) {
    await loadConversations()
    const routeId = normalizeConversationId(route)
    if (!routeId) {
      currentConversationId.value = null
      return null
    }
    const routeMatch = routeId && conversations.value.some(item => Number(item.id) === routeId)
    if (routeMatch) {
      currentConversationId.value = routeId
      return routeId
    }
    currentConversationId.value = null
    await syncConversationRoute(router, route, null)
    return null
  }

  function syncCurrentConversationFromRoute(route) {
    currentConversationId.value = normalizeConversationId(route)
    return currentConversationId.value
  }

  async function selectConversation(conversationId, route, router, replace = false) {
    if (!conversationId || Number(conversationId) === Number(normalizeConversationId(route))) return
    await syncConversationRoute(router, route, conversationId, replace)
  }

  async function startNewConversation(route, router) {
    currentConversationId.value = null
    editingConversationId.value = null
    editingConversationTitle.value = ''
    await syncConversationRoute(router, route, null, false)
  }

  async function createConversation(route, router) {
    if (conversationLoading.value) return null
    conversationLoading.value = true
    try {
      const res = await createChatConversationAPI()
      const conversation = res.data
      if (conversation) {
        conversations.value = [conversation, ...conversations.value.filter(item => item.id !== conversation.id)]
        currentConversationId.value = conversation.id
        await syncConversationRoute(router, route, conversation.id, false)
      }
      return conversation || null
    } finally {
      conversationLoading.value = false
    }
  }

  function startRenameConversation(conversation) {
    editingConversationId.value = conversation.id
    editingConversationTitle.value = conversation.title || '新对话'
  }

  function cancelRenameConversation() {
    editingConversationId.value = null
    editingConversationTitle.value = ''
  }

  async function renameConversation(conversation) {
    if (editingConversationId.value !== conversation.id) return null
    const title = editingConversationTitle.value.trim() || '新对话'
    cancelRenameConversation()
    const res = await renameChatConversationAPI(conversation.id, { title })
    const updated = res.data || { ...conversation, title }
    conversations.value = conversations.value.map(item => item.id === conversation.id ? updated : item)
    return updated
  }

  async function deleteConversation(conversation, route, router) {
    await deleteChatConversationAPI(conversation.id)
    conversations.value = conversations.value.filter(item => item.id !== conversation.id)
    await loadConversations()
    if (Number(currentConversationId.value) === Number(conversation.id)) {
      const nextConversation = conversations.value[0]
      if (nextConversation) {
        await selectConversation(nextConversation.id, route, router)
      } else {
        currentConversationId.value = null
        await syncConversationRoute(router, route, null)
      }
    }
  }

  return {
    conversations,
    conversationLoading,
    currentConversationId,
    editingConversationId,
    editingConversationTitle,
    loadConversations,
    initializeConversation,
    syncCurrentConversationFromRoute,
    selectConversation,
    startNewConversation,
    createConversation,
    startRenameConversation,
    cancelRenameConversation,
    renameConversation,
    deleteConversation
  }
})
