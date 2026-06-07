<template>
  <el-select
    v-model="selectedValue"
    :placeholder="placeholder"
    :disabled="disabled"
    :clearable="clearable"
    filterable
    :filter-method="handleFilter"
    :loading="loading"
    :popper-class="popperClass"
    fit-input-width
    style="width: 100%; min-width: 160px"
    @change="handleChange"
    @clear="handleClear"
    @visible-change="handleVisibleChange"
  >
    <!-- 搜索结果为空时的提示 -->
    <el-option v-if="filteredCategories.length === 0 && searchQuery" disabled value="">
      <div class="no-result">
        <el-icon><Search /></el-icon>
        <span>未找到「{{ searchQuery }}」相关分类</span>
      </div>
    </el-option>

    <!-- 分类选项 -->
    <el-option-group v-for="(group, groupName) in groupedCategories" :key="groupName" :label="groupName">
      <el-option
        v-for="cat in group"
        :key="cat.id || cat.name"
        :value="cat.name"
        :label="cat.name"
      >
        <div class="category-option">
          <span class="category-name" v-html="highlightText(cat.name, searchQuery)"></span>
        </div>
      </el-option>
    </el-option-group>

    <!-- 无分组时直接显示选项 -->
    <template v-if="!hasGroups">
      <el-option
        v-for="cat in filteredCategories"
        :key="cat.id || cat.name"
        :value="cat.name"
        :label="cat.name"
      >
        <div class="category-option">
          <span class="category-name" v-html="highlightText(cat.name, searchQuery)"></span>
        </div>
      </el-option>
    </template>
  </el-select>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { Search } from '@element-plus/icons-vue'
import { listCategoriesAPI } from '../api/category'

const props = defineProps({
  modelValue: {
    type: String,
    default: ''
  },
  placeholder: {
    type: String,
    default: '请选择分类'
  },
  disabled: {
    type: Boolean,
    default: false
  },
  clearable: {
    type: Boolean,
    default: true
  },
  type: {
    type: String,
    default: 'EXPENSE', // 'EXPENSE' | 'INCOME' | 'ALL'
    validator: v => ['EXPENSE', 'INCOME', 'ALL'].includes(v)
  },
  // 支持直接传入分类列表
  categories: {
    type: Array,
    default: null
  },
  // 是否分组显示
  grouped: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['update:modelValue', 'change', 'clear', 'categories-loaded'])

const selectedValue = ref(props.modelValue)
const loading = ref(false)
const searchQuery = ref('')
const categories = ref([])
const popperClass = 'category-select-popper'

// 防抖
let debounceTimer = null

// 监听传入值变化
watch(() => props.modelValue, val => {
  selectedValue.value = val
})

// 监听type变化，重新加载
watch(() => props.type, () => {
  fetchCategories()
})

// 搜索过滤（同时按类型过滤）
const filteredCategories = computed(() => {
  let result = categories.value
  
  // 按类型过滤（前端侧过滤，后端暂不支持）
  if (props.type === 'EXPENSE') {
    result = result.filter(cat => !isIncomeName(cat.name))
  } else if (props.type === 'INCOME') {
    result = result.filter(cat => isIncomeName(cat.name))
  }
  
  if (!searchQuery.value) {
    return result
  }
  const query = searchQuery.value.toLowerCase()
  return result.filter(cat => 
    cat.name.toLowerCase().includes(query) ||
    (cat.icon && cat.icon.toLowerCase().includes(query))
  )
})

// 分组显示（基于分类名称语义自动区分收入/支出）
const groupedCategories = computed(() => {
  if (!props.grouped) return {}
  const groups = { '支出': [], '收入': [] }
  categories.value.forEach(cat => {
    const groupName = isIncomeName(cat.name) ? '收入' : '支出'
    groups[groupName].push(cat)
  })
  // 移除空分组
  if (groups['支出'].length === 0) delete groups['支出']
  if (groups['收入'].length === 0) delete groups['收入']
  return groups
})

const hasGroups = computed(() => Object.keys(groupedCategories.value).length > 0)

// 高亮匹配文本
function highlightText(text, query) {
  if (!query) return text
  const regex = new RegExp(`(${escapeRegExp(query)})`, 'gi')
  return text.replace(regex, '<span class="highlight">$1</span>')
}

function escapeRegExp(str) {
  return str.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

// 判断分类名称是否为收入类型（与后端 SmartCategorizationService 逻辑一致）
function isIncomeName(name) {
  if (!name) return false
  return name.includes('工资') || name.includes('薪') || name.includes('兼职')
    || name.includes('投资') || name.includes('收益') || name.includes('退款')
    || name.includes('转账') || name.includes('收入') || name.includes('奖金')
    || name.includes('报酬') || name.includes('分红') || name.includes('利息')
}

// 处理搜索（防抖）
function handleFilter(query) {
  searchQuery.value = query
}

// 获取分类数据
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
  emit('update:modelValue', val)
  emit('change', val)
}

function handleClear() {
  emit('update:modelValue', '')
  emit('clear')
}

function handleVisibleChange(visible) {
  if (!visible) {
    // 关闭下拉时清空搜索
    setTimeout(() => {
      searchQuery.value = ''
    }, 200)
  }
}

// 强制刷新分类列表
function refresh() {
  fetchCategories()
}

// 暴露方法给父组件
defineExpose({ refresh })

onMounted(() => {
  fetchCategories()
})
</script>

<style scoped>
.no-result {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--text-muted);
  padding: 10px 0;
  font-size: 13px;
}
</style>

<style>
.category-select-popper .category-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}
.category-select-popper .category-name {
  font-size: 14px;
  font-weight: 500;
  color: var(--text);
}
.category-select-popper .highlight {
  color: var(--primary);
  font-weight: 600;
  background: var(--primary-surface);
  border-radius: 4px;
  padding: 0 4px;
}
/* 美化 Element Plus 下拉菜单 */
.category-select-popper .el-select-dropdown__item {
  padding: 8px 16px !important;
  margin: 2px 8px !important;
  border-radius: 8px !important;
  transition: all 0.15s ease !important;
}
.category-select-popper .el-select-dropdown__item:hover {
  background: var(--primary-50) !important;
}
.category-select-popper .el-select-dropdown__item.is-selected {
  background: var(--primary-surface) !important;
  color: var(--primary) !important;
  font-weight: 600 !important;
}
.category-select-popper .el-select-dropdown__item.is-disabled {
  color: var(--text-muted) !important;
  opacity: 0.7 !important;
}
.category-select-popper .el-select-group__title {
  font-size: 12px !important;
  font-weight: 700 !important;
  color: var(--text-muted) !important;
  padding: 10px 16px 6px !important;
  text-transform: uppercase !important;
  letter-spacing: 0.5px !important;
}
</style>
