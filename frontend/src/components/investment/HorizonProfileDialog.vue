<template>
  <Dialog :open="open" @update:open="emit('update:open', $event)">
    <DialogContent class="max-h-[88vh] overflow-y-auto sm:max-w-[760px]">
      <DialogHeader>
        <DialogTitle>{{ scope === 'asset' ? '该资产的分析周期' : '周期偏好' }}</DialogTitle>
        <DialogDescription>
          周期名称和天数由你定义，区间可以重叠。系统会按这些具体天数分析和回测。
        </DialogDescription>
      </DialogHeader>

      <Alert v-for="warning in draft.warnings" :key="warning" class="py-2.5">
        <TriangleAlert />
        <AlertTitle>数据能力提醒</AlertTitle>
        <AlertDescription>{{ warning }}</AlertDescription>
      </Alert>

      <div class="space-y-3">
        <div class="hidden grid-cols-[1fr_1.3fr_82px_18px_82px_90px_64px_36px] gap-2 px-1 text-xs text-muted-foreground sm:grid">
          <span>代码</span><span>显示名称</span><span>最少天数</span><span /><span>最多天数</span><span>目标天数</span><span>主周期</span><span />
        </div>
        <div
          v-for="(item, index) in draft.settings"
          :key="`${item.code}-${index}`"
          class="grid gap-2 rounded-lg border bg-muted/15 p-3 sm:grid-cols-[1fr_1.3fr_82px_18px_82px_90px_64px_36px] sm:items-center sm:border-0 sm:bg-transparent sm:p-0"
        >
          <Input v-model="item.code" aria-label="周期代码" placeholder="例如 WAVE" @blur="item.code = item.code.trim().toUpperCase()" />
          <Input v-model="item.displayName" aria-label="周期名称" placeholder="例如 波段" />
          <Input v-model.number="item.minHoldingDays" aria-label="最少天数" type="number" min="1" />
          <span class="hidden text-center text-muted-foreground sm:block">至</span>
          <Input v-model.number="item.maxHoldingDays" aria-label="最多天数" type="number" min="1" />
          <Input v-model.number="item.targetHoldingDays" aria-label="目标持有天数" type="number" min="1" />
          <label class="flex items-center gap-2 text-sm sm:justify-center">
            <input
              :checked="item.primary"
              :name="`primary-horizon-${scope}`"
              type="radio"
              @change="setPrimary(index)"
            >
            <span class="sm:sr-only">主要周期</span>
          </label>
          <Button variant="ghost" size="icon" title="删除周期" :disabled="draft.settings.length === 1" @click="remove(index)">
            <Trash2 />
          </Button>
        </div>
        <Button variant="outline" size="sm" @click="addSetting"><Plus />新增周期</Button>
      </div>

      <p v-if="error" class="text-sm text-destructive">{{ error }}</p>

      <DialogFooter class="gap-2 sm:justify-between">
        <Button
          v-if="scope === 'asset' && draft.hasAssetOverride"
          variant="ghost"
          :disabled="saving"
          @click="emit('clear')"
        >
          <Undo2 />恢复全局设置
        </Button>
        <span v-else />
        <div class="flex justify-end gap-2">
          <Button variant="outline" :disabled="saving" @click="emit('update:open', false)">取消</Button>
          <Button :disabled="saving" @click="submit">
            <Loader2 v-if="saving" class="animate-spin" />保存并重新分析
          </Button>
        </div>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>

<script setup>
import { ref, watch } from 'vue'
import { Loader2, Plus, Trash2, TriangleAlert, Undo2 } from '@lucide/vue'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { normalizeHorizonProfile, profileSavePayload, validateHorizonSettings } from '@/lib/horizonProfile'

const props = defineProps({
  open: { type: Boolean, default: false },
  profile: { type: Object, default: () => ({ settings: [] }) },
  scope: { type: String, default: 'global' },
  saving: { type: Boolean, default: false },
})
const emit = defineEmits(['update:open', 'save', 'clear'])
const draft = ref(normalizeHorizonProfile(props.profile))
const error = ref('')

watch(
  () => [props.open, props.profile],
  () => {
    if (!props.open) return
    draft.value = normalizeHorizonProfile(props.profile)
    error.value = ''
  },
  { deep: true, immediate: true },
)

function addSetting() {
  const sortOrder = Math.max(0, ...draft.value.settings.map(item => Number(item.sortOrder) || 0)) + 10
  draft.value.settings.push({
    code: '', displayName: '', sortOrder,
    minHoldingDays: null, maxHoldingDays: null, targetHoldingDays: null,
    primary: draft.value.settings.length === 0,
  })
}

function remove(index) {
  const [removed] = draft.value.settings.splice(index, 1)
  if (removed?.primary && draft.value.settings.length) draft.value.settings[0].primary = true
}

function setPrimary(index) {
  draft.value.settings.forEach((item, itemIndex) => { item.primary = itemIndex === index })
}

function submit() {
  error.value = validateHorizonSettings(draft.value.settings)
  if (error.value) return
  emit('save', profileSavePayload(draft.value))
}
</script>
