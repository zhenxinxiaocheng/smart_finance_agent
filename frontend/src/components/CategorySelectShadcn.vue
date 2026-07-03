<template>
  <div class="flex w-full min-w-40 items-center gap-2">
    <Select
      :model-value="selectedValue"
      :disabled="disabled || loading"
      @update:model-value="handleChange"
      @update:open="handleVisibleChange"
    >
      <SelectTrigger class="w-full">
        <SelectValue :placeholder="loading ? '加载分类中...' : placeholder" />
      </SelectTrigger>
      <SelectContent>
        <SelectItem v-if="filteredCategories.length === 0" value="__empty__" disabled>
          暂无可用分类
        </SelectItem>

        <template v-else-if="hasGroups">
          <SelectGroup v-for="(group, groupName) in groupedCategories" :key="groupName">
            <SelectLabel>{{ groupName }}</SelectLabel>
            <SelectItem v-for="cat in group" :key="cat.id || cat.name" :value="cat.name">
              {{ cat.name }}
            </SelectItem>
          </SelectGroup>
        </template>

        <template v-else>
          <SelectItem v-for="cat in filteredCategories" :key="cat.id || cat.name" :value="cat.name">
            {{ cat.name }}
          </SelectItem>
        </template>
      </SelectContent>
    </Select>

    <Button
      v-if="clearable && selectedValue"
      type="button"
      variant="ghost"
      size="icon-sm"
      :disabled="disabled"
      @click="handleClear"
    >
      <X />
    </Button>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { X } from '@lucide/vue'
import { Button } from '@/components/ui/button'
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectLabel,
  SelectTrigger,
  SelectValue
} from '@/components/ui/select'
import { listCategoriesAPI } from '../api/category'

const props = defineProps({
  modelValue: { type: String, default: '' },
  placeholder: { type: String, default: '请选择分类' },
  disabled: { type: Boolean, default: false },
  clearable: { type: Boolean, default: true },
  type: {
    type: String,
    default: 'EXPENSE',
    validator: v => ['EXPENSE', 'INCOME', 'ALL'].includes(v)
  },
  categories: { type: Array, default: null },
  grouped: { type: Boolean, default: false }
})

const emit = defineEmits(['update:modelValue', 'change', 'clear', 'categories-loaded'])

const selectedValue = ref(props.modelValue)
const loading = ref(false)
const categories = ref([])

watch(() => props.modelValue, val => {
  selectedValue.value = val
})

watch(() => props.type, () => {
  fetchCategories()
})

const filteredCategories = computed(() => {
  let result = categories.value
  if (props.type === 'EXPENSE') {
    result = result.filter(cat => !isIncomeName(cat.name))
  } else if (props.type === 'INCOME') {
    result = result.filter(cat => isIncomeName(cat.name))
  }
  return result
})

const groupedCategories = computed(() => {
  if (!props.grouped) return {}
  const groups = { '支出': [], '收入': [] }
  filteredCategories.value.forEach(cat => {
    const groupName = isIncomeName(cat.name) ? '收入' : '支出'
    groups[groupName].push(cat)
  })
  if (groups['支出'].length === 0) delete groups['支出']
  if (groups['收入'].length === 0) delete groups['收入']
  return groups
})

const hasGroups = computed(() => Object.keys(groupedCategories.value).length > 0)

function isIncomeName(name) {
  if (!name) return false
  return name.includes('工资') || name.includes('薪') || name.includes('兼职')
    || name.includes('投资') || name.includes('收益') || name.includes('退款')
    || name.includes('转账') || name.includes('收入') || name.includes('奖金')
    || name.includes('报酬') || name.includes('分红') || name.includes('利息')
}

async function fetchCategories() {
  if (props.categories) {
    categories.value = props.categories
    emit('categories-loaded', props.categories)
    return
  }

  loading.value = true
  try {
    const res = await listCategoriesAPI()
    if (res.code === 200) {
      categories.value = res.data || []
      emit('categories-loaded', res.data)
    }
  } catch (err) {
    console.error('Failed to load categories:', err)
  } finally {
    loading.value = false
  }
}

function handleChange(val) {
  if (val === '__empty__') return
  selectedValue.value = val
  emit('update:modelValue', val)
  emit('change', val)
}

function handleClear() {
  selectedValue.value = ''
  emit('update:modelValue', '')
  emit('clear')
}

function handleVisibleChange(open) {
  if (open && categories.value.length === 0) {
    fetchCategories()
  }
}

function refresh() {
  fetchCategories()
}

defineExpose({ refresh })

onMounted(() => {
  fetchCategories()
})
</script>
