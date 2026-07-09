<template>
  <section v-if="conversations.length" class="conversation-panel">
    <div class="conversation-panel-list">
      <div
        v-for="conversation in conversations"
        :key="conversation.id"
        class="conversation-panel-item"
        :class="{ active: Number(conversation.id) === Number(currentConversationId) }"
        role="button"
        tabindex="0"
        @click="selectConversation(conversation.id, route, router)"
        @keydown.enter.prevent="selectConversation(conversation.id, route, router)"
        @keydown.space.prevent="selectConversation(conversation.id, route, router)"
      >
        <MessageSquareText class="conversation-panel-icon" />
        <span v-if="editingConversationId !== conversation.id" class="conversation-panel-title">
          {{ conversation.title || '新对话' }}
        </span>
        <input
          v-else
          v-model="editingConversationTitle"
          class="conversation-panel-input"
          @click.stop
          @keydown.enter.stop.prevent="handleRenameConversation(conversation)"
          @keydown.esc.stop.prevent="cancelRenameConversation"
          @blur="handleRenameConversation(conversation)"
        />
        <span class="conversation-panel-actions">
          <button type="button" title="重命名" @click.stop="handleStartRename(conversation)">
            <Pencil />
          </button>
          <button type="button" title="删除" :disabled="busy || deleteSaving" @click.stop="openDeleteDialog(conversation)">
            <Trash2 />
          </button>
        </span>
      </div>
    </div>
  </section>

  <Dialog v-model:open="deleteDialogVisible">
    <DialogContent class="sm:max-w-[420px]">
      <DialogHeader>
        <DialogTitle>删除对话</DialogTitle>
        <DialogDescription>删除后，这个对话和聊天记录会从列表中移除。</DialogDescription>
      </DialogHeader>
      <p class="rounded-lg border bg-muted/40 p-3 text-sm font-medium">
        {{ deletingConversation?.title || '新对话' }}
      </p>
      <DialogFooter>
        <Button variant="outline" :disabled="deleteSaving" @click="deleteDialogVisible = false">取消</Button>
        <Button variant="destructive" :disabled="deleteSaving" @click="confirmDeleteConversation">
          <Loader2 v-if="deleteSaving" data-icon="inline-start" class="animate-spin" />
          确认删除
        </Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>

<script setup>
import { nextTick, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { Loader2, MessageSquareText, Pencil, Trash2 } from '@lucide/vue'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle
} from '@/components/ui/dialog'
import { feedback } from '@/lib/feedback'
import { useChatConversationsStore } from '@/stores/chatConversations'

defineProps({
  busy: {
    type: Boolean,
    default: false
  }
})

const route = useRoute()
const router = useRouter()
const conversationStore = useChatConversationsStore()
const deleteDialogVisible = ref(false)
const deleteSaving = ref(false)
const deletingConversation = ref(null)
const {
  conversations,
  conversationLoading,
  currentConversationId,
  editingConversationId,
  editingConversationTitle
} = storeToRefs(conversationStore)
const {
  selectConversation,
  loadConversations,
  startRenameConversation,
  cancelRenameConversation,
  renameConversation,
  deleteConversation
} = conversationStore

onMounted(() => {
  if (!conversationLoading.value && !conversations.value.length) {
    loadConversations()
  }
})

function handleStartRename(conversation) {
  startRenameConversation(conversation)
  nextTick(() => {
    document.querySelector('.conversation-panel-input')?.focus()
  })
}

async function handleRenameConversation(conversation) {
  try {
    await renameConversation(conversation)
  } catch {
    feedback.error('重命名失败')
  }
}

function openDeleteDialog(conversation) {
  if (!conversation) return
  deletingConversation.value = conversation
  deleteDialogVisible.value = true
}

async function confirmDeleteConversation() {
  if (!deletingConversation.value) return
  deleteSaving.value = true
  try {
    await deleteConversation(deletingConversation.value, route, router)
    deleteDialogVisible.value = false
    deletingConversation.value = null
  } catch {
    feedback.error('删除对话失败')
  } finally {
    deleteSaving.value = false
  }
}
</script>

<style scoped>
.conversation-panel {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.conversation-panel-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 0;
}

.conversation-panel-item {
  width: 100%;
  min-height: 40px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px;
  border: 1px solid transparent;
  border-radius: 10px;
  background: transparent;
  color: color-mix(in oklab, var(--sidebar-foreground) 92%, white 8%);
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.conversation-panel-item:hover,
.conversation-panel-item.active {
  background: color-mix(in oklab, var(--sidebar-accent) 82%, transparent);
  border-color: color-mix(in oklab, var(--sidebar-border) 72%, transparent);
}

.conversation-panel-item:focus-visible {
  outline: 2px solid color-mix(in oklab, var(--sidebar-primary) 70%, white 30%);
  outline-offset: 1px;
}

.conversation-panel-icon {
  width: 16px;
  height: 16px;
  flex-shrink: 0;
  color: color-mix(in oklab, var(--sidebar-foreground) 52%, transparent);
}

.conversation-panel-title {
  min-width: 0;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
}

.conversation-panel-input {
  min-width: 0;
  flex: 1;
  height: 28px;
  border: 1px solid color-mix(in oklab, var(--sidebar-border) 78%, transparent);
  border-radius: 10px;
  padding: 0 8px;
  background: color-mix(in oklab, var(--sidebar) 82%, black 18%);
  color: color-mix(in oklab, var(--sidebar-foreground) 96%, white 4%);
  font-size: 13px;
}

.conversation-panel-actions {
  display: flex;
  align-items: center;
  gap: 2px;
  opacity: 0;
  transition: opacity 0.15s ease;
}

.conversation-panel-item:hover .conversation-panel-actions,
.conversation-panel-item.active .conversation-panel-actions {
  opacity: 1;
}

.conversation-panel-actions button {
  width: 24px;
  height: 24px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: color-mix(in oklab, var(--sidebar-foreground) 52%, transparent);
  cursor: pointer;
}

.conversation-panel-actions button:hover:not(:disabled) {
  background: color-mix(in oklab, var(--sidebar-accent) 92%, transparent);
  color: color-mix(in oklab, var(--sidebar-foreground) 96%, white 4%);
}

.conversation-panel-actions button:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}

.conversation-panel-actions svg {
  width: 14px;
  height: 14px;
}

</style>
