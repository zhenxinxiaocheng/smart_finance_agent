<template>
  <div class="mx-auto flex max-w-[1400px] flex-col gap-5">
    <div class="flex flex-col gap-4 md:flex-row md:items-start md:justify-between">
      <div class="min-w-0">
        <h1 class="text-2xl font-semibold tracking-normal text-foreground">消费记录</h1>
        <p class="mt-1 text-sm text-muted-foreground">管理和查看你的所有收支记录。</p>
      </div>
      <div class="flex flex-wrap gap-2">
        <Button variant="outline" @click="showCategoryDialog">
          <Settings data-icon="inline-start" />
          管理分类
        </Button>
        <Button @click="showAddDialog">
          <Plus data-icon="inline-start" />
          新增记录
        </Button>
      </div>
    </div>

    <Card>
      <CardHeader>
        <CardTitle class="text-base">筛选</CardTitle>
        <CardDescription>按类型、分类和日期范围定位记录。</CardDescription>
      </CardHeader>
      <CardContent class="grid gap-4 lg:grid-cols-[220px_minmax(180px,260px)_minmax(260px,360px)_auto] lg:items-end">
        <div class="flex flex-col gap-2">
          <Label>类型</Label>
          <div class="grid grid-cols-3 gap-2">
            <Button :variant="filter.type === '' ? 'default' : 'outline'" @click="setTypeFilter('')">全部</Button>
            <Button :variant="filter.type === 'INCOME' ? 'default' : 'outline'" @click="setTypeFilter('INCOME')">收入</Button>
            <Button :variant="filter.type === 'EXPENSE' ? 'destructive' : 'outline'" @click="setTypeFilter('EXPENSE')">支出</Button>
          </div>
        </div>
        <div class="flex flex-col gap-2">
          <Label>分类</Label>
          <CategorySelect
            ref="filterCategorySelectRef"
            v-model="filter.category"
            placeholder="全部分类"
            type="ALL"
            @change="fetchData"
          />
        </div>
        <div class="flex flex-col gap-2">
          <Label>日期</Label>
          <div class="grid grid-cols-2 gap-2">
            <Input v-model="filter.startDate" type="date" aria-label="开始日期" @change="fetchData" />
            <Input v-model="filter.endDate" type="date" aria-label="结束日期" @change="fetchData" />
          </div>
        </div>
        <Button variant="outline" @click="resetFilter">
          <RotateCcw data-icon="inline-start" />
          重置
        </Button>
      </CardContent>
    </Card>

    <Card>
      <CardHeader class="flex flex-row items-center justify-between gap-3">
        <div>
          <CardTitle class="text-base">记录列表</CardTitle>
          <CardDescription>共 {{ total }} 条记录</CardDescription>
        </div>
        <Badge variant="secondary">{{ page }} / {{ Math.max(Math.ceil(total / size), 1) }} 页</Badge>
      </CardHeader>
      <CardContent class="p-0">
        <div class="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead class="min-w-28">日期</TableHead>
                <TableHead class="min-w-24">类型</TableHead>
                <TableHead class="min-w-28">分类</TableHead>
                <TableHead class="min-w-36 text-right">金额</TableHead>
                <TableHead class="min-w-52">备注</TableHead>
                <TableHead class="min-w-44">创建时间</TableHead>
                <TableHead class="min-w-24 text-right">操作</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              <template v-if="loading">
                <TableRow v-for="index in 5" :key="index">
                  <TableCell colspan="7">
                    <Skeleton class="h-8 w-full rounded-md" />
                  </TableCell>
                </TableRow>
              </template>
              <TableEmpty v-else-if="tableData.length === 0" :colspan="7">
                暂无消费记录
              </TableEmpty>
              <TableRow
                v-for="row in tableData"
                v-else
                :key="row.id"
                :class="selectedTransactionId && Number(row.id) === selectedTransactionId ? 'bg-primary/5 ring-1 ring-inset ring-primary/30' : ''"
              >
                <TableCell class="font-medium">{{ row.transactionDate }}</TableCell>
                <TableCell>
                  <Badge :variant="row.type === 'INCOME' ? 'secondary' : 'destructive'">
                    <ArrowUpRight v-if="row.type === 'INCOME'" data-icon="inline-start" />
                    <ArrowDownLeft v-else data-icon="inline-start" />
                    {{ row.type === 'INCOME' ? '收入' : '支出' }}
                  </Badge>
                </TableCell>
                <TableCell>
                  <Badge variant="outline">{{ row.category || '-' }}</Badge>
                </TableCell>
                <TableCell class="text-right font-semibold tabular-nums" :class="row.type === 'EXPENSE' ? 'text-destructive' : 'text-foreground'">
                  {{ formatAmount(row) }}
                </TableCell>
                <TableCell class="max-w-[260px] truncate text-muted-foreground">{{ row.description || '-' }}</TableCell>
                <TableCell class="text-muted-foreground">{{ row.createdAt }}</TableCell>
                <TableCell>
                  <div class="flex justify-end gap-1">
                    <Button size="icon-sm" variant="ghost" @click="showEditDialog(row)">
                      <Pencil />
                    </Button>
                    <Button size="icon-sm" variant="destructive" @click="handleDelete(row.id)">
                      <Trash2 />
                    </Button>
                  </div>
                </TableCell>
              </TableRow>
            </TableBody>
          </Table>
        </div>
      </CardContent>
      <CardFooter class="justify-between gap-3 border-t max-sm:flex-col max-sm:items-stretch">
        <div class="flex items-center gap-2 text-sm text-muted-foreground">
          <span>每页</span>
          <Select :model-value="String(size)" @update:model-value="handleSizeChange">
            <SelectTrigger class="h-8 w-20">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectGroup>
                <SelectItem value="10">10</SelectItem>
                <SelectItem value="20">20</SelectItem>
                <SelectItem value="50">50</SelectItem>
              </SelectGroup>
            </SelectContent>
          </Select>
          <span>条</span>
        </div>
        <div class="flex items-center justify-end gap-2">
          <Button variant="outline" size="sm" :disabled="page <= 1" @click="goPage(page - 1)">上一页</Button>
          <span class="min-w-24 text-center text-sm text-muted-foreground">第 {{ page }} / {{ totalPages }} 页</span>
          <Button variant="outline" size="sm" :disabled="page >= totalPages" @click="goPage(page + 1)">下一页</Button>
        </div>
      </CardFooter>
    </Card>

    <Dialog v-model:open="dialogVisible">
      <DialogContent class="sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>{{ isEdit ? '编辑记录' : '新增记录' }}</DialogTitle>
          <DialogDescription>补充收支类型、分类、日期和金额。</DialogDescription>
        </DialogHeader>
        <div class="flex flex-col gap-4">
          <div class="flex flex-col gap-2">
            <Label>类型</Label>
            <div class="grid w-full grid-cols-2 gap-2">
              <Button type="button" :variant="form.type === 'EXPENSE' ? 'destructive' : 'outline'" @click="form.type = 'EXPENSE'">
                <ArrowDownLeft data-icon="inline-start" />
                支出
              </Button>
              <Button type="button" :variant="form.type === 'INCOME' ? 'default' : 'outline'" @click="form.type = 'INCOME'">
                <ArrowUpRight data-icon="inline-start" />
                收入
              </Button>
            </div>
          </div>
          <div class="grid gap-4 md:grid-cols-2">
            <div class="flex flex-col gap-2">
              <Label>分类</Label>
              <CategorySelect
                ref="categorySelectRef"
                v-model="form.category"
                placeholder="选择分类"
                :type="form.type"
              />
            </div>
            <div class="flex flex-col gap-2">
              <Label>日期</Label>
              <Input v-model="form.transactionDate" type="date" />
            </div>
          </div>
          <div class="flex flex-col gap-2">
            <Label>金额</Label>
            <Input v-model.number="form.amount" min="0.01" step="10" type="number" />
          </div>
          <div class="flex flex-col gap-2">
            <Label>备注</Label>
            <Textarea v-model="form.description" rows="3" placeholder="可选备注（如：午餐、地铁充值等）" />
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" @click="dialogVisible = false">取消</Button>
          <Button :disabled="submitLoading" @click="handleSubmit">
            <Loader2 v-if="submitLoading" data-icon="inline-start" class="animate-spin" />
            {{ isEdit ? '保存更改' : '添加记录' }}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>

    <Dialog v-model:open="categoryDialogVisible">
      <DialogContent class="sm:max-w-[640px]">
        <DialogHeader>
          <DialogTitle>管理消费分类</DialogTitle>
          <DialogDescription>管理你的消费分类，所有分类均可自由编辑和删除。</DialogDescription>
        </DialogHeader>

        <div class="flex justify-end">
          <Button size="sm" @click="showAddCategoryForm">
            <Plus data-icon="inline-start" />
            新增分类
          </Button>
        </div>

        <Card v-if="showCategoryForm" class="bg-muted/30">
          <CardContent class="p-4">
            <div class="flex flex-col gap-4">
              <div class="grid gap-4 md:grid-cols-2">
                <div class="flex flex-col gap-2">
                  <Label>分类名称</Label>
                  <Input v-model="catForm.name" placeholder="如：教育" maxlength="20" />
                </div>
                <div class="flex flex-col gap-2">
                  <Label>图标标识</Label>
                  <Input v-model="catForm.icon" placeholder="如：education" />
                </div>
              </div>
              <div class="flex flex-col gap-2">
                <Label>排序</Label>
                <Input v-model.number="catForm.sortOrder" min="0" step="1" type="number" />
              </div>
            </div>
            <div class="flex justify-end gap-2">
              <Button size="sm" variant="outline" @click="cancelCategoryForm">取消</Button>
              <Button size="sm" :disabled="catSubmitLoading" @click="handleCategorySubmit">
                <Loader2 v-if="catSubmitLoading" data-icon="inline-start" class="animate-spin" />
                {{ catEditId ? '保存更改' : '添加分类' }}
              </Button>
            </div>
          </CardContent>
        </Card>

        <ScrollArea class="max-h-[360px] pr-3">
          <div class="flex flex-col gap-2">
            <div v-for="cat in categories" :key="cat.id" class="flex items-center justify-between gap-3 rounded-lg border p-3">
              <div class="flex min-w-0 items-center gap-3">
                <Avatar class="size-8">
                  <AvatarFallback>{{ cat.name.charAt(0) }}</AvatarFallback>
                </Avatar>
                <span class="truncate text-sm font-medium">{{ cat.name }}</span>
              </div>
              <div class="flex gap-1">
                <Button size="icon-sm" variant="ghost" @click="showEditCategoryForm(cat)">
                  <Pencil />
                </Button>
                <Button size="icon-sm" variant="destructive" @click="handleDeleteCategory(cat.id, cat.name)">
                  <Trash2 />
                </Button>
              </div>
            </div>
            <Alert v-if="categories.length === 0">
              <AlertTitle>暂无分类</AlertTitle>
              <AlertDescription>点击“新增分类”创建你的第一条消费分类。</AlertDescription>
            </Alert>
          </div>
        </ScrollArea>
      </DialogContent>
    </Dialog>
  </div>
</template>

<script setup>
import { computed, ref, reactive, onMounted, watch } from 'vue'
import {
  ArrowDownLeft,
  ArrowUpRight,
  Loader2,
  Pencil,
  Plus,
  RotateCcw,
  Settings,
  Trash2
} from '@lucide/vue'
import { useRoute } from 'vue-router'
import { listTransactionsAPI, addTransactionAPI, updateTransactionAPI, deleteTransactionAPI, getTransactionAPI } from '../api/transaction'
import { listCategoriesAPI, addCategoryAPI, updateCategoryAPI, deleteCategoryAPI } from '../api/category'
import CategorySelect from '../components/CategorySelectShadcn.vue'
import { confirmAction, feedback } from '@/lib/feedback'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Avatar, AvatarFallback } from '@/components/ui/avatar'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle
} from '@/components/ui/card'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { ScrollArea } from '@/components/ui/scroll-area'
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue
} from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import {
  Table,
  TableBody,
  TableCell,
  TableEmpty,
  TableHead,
  TableHeader,
  TableRow
} from '@/components/ui/table'
import { Textarea } from '@/components/ui/textarea'

const route = useRoute()
const tableData = ref([])
const loading = ref(false)
const page = ref(1)
const size = ref(20)
const total = ref(0)
const totalPages = computed(() => Math.max(Math.ceil(total.value / size.value), 1))
const selectedTransactionId = ref(route.query.transactionId ? Number(route.query.transactionId) : null)

const filter = reactive({
  type: '',
  category: '',
  startDate: '',
  endDate: ''
})

const categories = ref([])
const categorySelectRef = ref(null)
const filterCategorySelectRef = ref(null)

async function fetchCategories() {
  try {
    const res = await listCategoriesAPI()
    if (res.code === 200) {
      categories.value = res.data || []
    }
  } catch {}
}

const dialogVisible = ref(false)
const isEdit = ref(false)
const editId = ref(null)
const submitLoading = ref(false)

const form = reactive({
  type: 'EXPENSE',
  category: '',
  amount: 0,
  transactionDate: '',
  description: ''
})

const categoryDialogVisible = ref(false)
const showCategoryForm = ref(false)
const catEditId = ref(null)
const catSubmitLoading = ref(false)

const catForm = reactive({
  name: '',
  icon: '',
  sortOrder: 1
})

function showCategoryDialog() {
  categoryDialogVisible.value = true
  cancelCategoryForm()
}

function showAddCategoryForm() {
  catEditId.value = null
  catForm.name = ''
  catForm.icon = ''
  catForm.sortOrder = categories.value.length + 1
  showCategoryForm.value = true
}

function showEditCategoryForm(cat) {
  catEditId.value = cat.id
  catForm.name = cat.name
  catForm.icon = cat.icon || ''
  catForm.sortOrder = cat.sortOrder || 0
  showCategoryForm.value = true
}

function cancelCategoryForm() {
  showCategoryForm.value = false
  catEditId.value = null
  catForm.name = ''
  catForm.icon = ''
  catForm.sortOrder = 0
}

async function handleCategorySubmit() {
  if (!catForm.name.trim()) {
    feedback.warning('请输入分类名称')
    return
  }
  catSubmitLoading.value = true
  try {
    const data = {
      name: catForm.name,
      icon: catForm.icon || null,
      sortOrder: catForm.sortOrder || 0
    }
    if (catEditId.value) {
      await updateCategoryAPI(catEditId.value, data)
      feedback.success('分类更新成功')
    } else {
      await addCategoryAPI(data)
      feedback.success('分类添加成功')
    }
    showCategoryForm.value = false
    await fetchCategories()
    // 刷新所有分类选择器
    categorySelectRef.value?.refresh()
    filterCategorySelectRef.value?.refresh()
  } finally {
    catSubmitLoading.value = false
  }
}

async function handleDeleteCategory(id, name) {
  const confirmed = await confirmAction(`确定要删除分类「${name}」吗？删除后不可恢复。`)
  if (!confirmed) return
  try {
    await deleteCategoryAPI(id)
    feedback.success(`分类「${name}」已删除`)
    await fetchCategories()
    // 刷新所有分类选择器
    categorySelectRef.value?.refresh()
    filterCategorySelectRef.value?.refresh()
  } catch {}
}

async function fetchData() {
  loading.value = true
  try {
    const params = { page: page.value, size: size.value }
    if (filter.type) params.type = filter.type
    if (filter.category) params.category = filter.category
    if (filter.startDate) params.startDate = filter.startDate
    if (filter.endDate) params.endDate = filter.endDate
    const res = await listTransactionsAPI(params)
    if (res.code === 200) {
      tableData.value = res.data.records || []
      total.value = res.data.total || 0
      await pinSelectedTransaction()
    }
  } finally {
    loading.value = false
  }
}

async function pinSelectedTransaction() {
  if (!selectedTransactionId.value) return
  const exists = tableData.value.some(item => Number(item.id) === selectedTransactionId.value)
  if (exists) return
  try {
    const res = await getTransactionAPI(selectedTransactionId.value)
    if (res.code === 200 && res.data) {
      tableData.value = [{ ...res.data, _auditPinned: true }, ...tableData.value]
    }
  } catch {}
}

function resetFilter() {
  filter.type = ''
  filter.category = ''
  filter.startDate = ''
  filter.endDate = ''
  page.value = 1
  fetchData()
}

function setTypeFilter(type) {
  filter.type = type
  page.value = 1
  fetchData()
}

function handleSizeChange(value) {
  size.value = Number(value)
  page.value = 1
  fetchData()
}

function goPage(nextPage) {
  page.value = Math.min(Math.max(Number(nextPage), 1), totalPages.value)
  fetchData()
}

function formatAmount(row) {
  const prefix = row.type === 'INCOME' ? '+' : '-'
  return `${prefix}¥${Number(row.amount || 0).toFixed(2)}`
}

function showAddDialog() {
  isEdit.value = false
  editId.value = null
  form.type = 'EXPENSE'
  form.category = ''
  form.amount = 0
  form.transactionDate = ''
  form.description = ''
  dialogVisible.value = true
}

function showEditDialog(row) {
  isEdit.value = true
  editId.value = row.id
  form.type = row.type
  form.category = row.category
  form.amount = Number(row.amount)
  form.transactionDate = row.transactionDate
  form.description = row.description || ''
  dialogVisible.value = true
}

async function handleSubmit() {
  if (!form.category) {
    feedback.warning('请选择分类')
    return
  }
  if (!form.transactionDate) {
    feedback.warning('请选择日期')
    return
  }
  if (Number(form.amount || 0) <= 0) {
    feedback.warning('请输入有效金额')
    return
  }
  submitLoading.value = true
  try {
    if (isEdit.value) {
      await updateTransactionAPI(editId.value, form)
      feedback.success('更新成功')
    } else {
      await addTransactionAPI(form)
      feedback.success('添加成功')
    }
    dialogVisible.value = false
    fetchData()
  } finally {
    submitLoading.value = false
  }
}

async function handleDelete(id) {
  const confirmed = await confirmAction('确定要删除该记录吗？')
  if (!confirmed) return
  try {
    await deleteTransactionAPI(id)
    feedback.success('删除成功')
    fetchData()
  } catch {}
}

onMounted(() => {
  fetchData()
  fetchCategories()
})

watch(() => route.query.transactionId, transactionId => {
  selectedTransactionId.value = transactionId ? Number(transactionId) : null
  fetchData()
})
</script>

<style scoped>
.transaction-page {
  max-width: 1400px;
  margin: 0 auto;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 28px;
}

.header-text {
  flex: 1;
}

.page-title {
  font-size: 28px;
  font-weight: 800;
  color: var(--text);
  margin: 0;
  letter-spacing: -0.5px;
}

.page-subtitle {
  font-size: 14px;
  color: var(--text-muted);
  margin-top: 6px;
  font-weight: 500;
}

.header-actions {
  display: flex;
  gap: 12px;
  flex-shrink: 0;
}

.filter-section {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 20px;
  margin-bottom: 24px;
  box-shadow: var(--shadow);
}

.filter-row {
  display: flex;
  align-items: flex-end;
  gap: 28px;
  flex-wrap: wrap;
}

.filter-group {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.filter-label {
  font-size: 12px;
  font-weight: 700;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.8px;
}

.filter-chips {
  display: flex;
  gap: 8px;
}

.chip {
  padding: 7px 14px;
  border-radius: var(--radius);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  background: var(--bg);
  color: var(--text-secondary);
  transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1);
  border: 1px solid transparent;
}

.chip:hover {
  background: var(--primary-surface);
  color: var(--primary);
  transform: translateY(-1px);
}

.chip.active {
  background: var(--primary);
  color: #fff;
  border-color: var(--primary);
  box-shadow: var(--shadow-sm);
}

.chip.chip-income.active {
  background: var(--primary);
  border-color: var(--primary);
}
.chip.chip-income:hover:not(.active) { color: var(--primary); background: var(--primary-surface); border-color: var(--border); }
.chip.chip-expense.active {
  background: var(--danger);
  border-color: var(--danger);
}
.chip.chip-expense:hover:not(.active) { color: #ef4444; background: #fef2f2; border-color: #fee2e2; }

.filter-actions {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  padding-bottom: 2px;
}

.table-card {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  box-shadow: var(--shadow);
  overflow: hidden;
}

.table-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20px 24px;
  border-bottom: 1px solid var(--border);
  background: #fff;
}

.table-info {
  font-size: 14px;
  color: var(--text-muted);
  font-weight: 500;
}

.table-info strong {
  color: var(--primary);
  font-weight: 700;
  font-size: 16px;
}

.type-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 4px 10px;
  border-radius: var(--radius);
  font-size: 13px;
  font-weight: 600;
  transition: all 0.2s ease;
  white-space: nowrap;
  flex-direction: row;
  min-width: 80px;
}

.type-badge.income {
  background: var(--primary-surface);
  color: var(--text);
}

.type-badge.expense {
  background: var(--danger-light);
  color: #ef4444;
}

.category-tag {
  background: var(--primary-surface);
  padding: 4px 10px;
  border-radius: var(--radius);
  font-size: 13px;
  font-weight: 500;
  color: var(--text-secondary);
  border: 1px solid rgba(0,0,0,0.03);
}

.amount-value {
  font-size: 15px;
  font-weight: 700;
  font-feature-settings: 'tnum';
}

.amount-value.income { color: var(--text); }
.amount-value.expense { color: #ef4444; }

.desc-text {
  font-size: 13px;
  color: var(--text-muted);
}

.action-btns {
  display: flex;
  gap: 4px;
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  padding: 16px 24px;
  border-top: 1px solid var(--border);
}

.type-radio-group {
  display: flex;
  gap: 0;
}

.category-mgr-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--border);
}

.category-mgr-desc {
  font-size: 13px;
  color: var(--text-muted);
  margin: 0;
}

.category-form-card {
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 16px;
  margin-bottom: 16px;
}

.category-form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
}

.category-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.category-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  transition: border-color 0.2s;
}

.category-item:hover {
  border-color: var(--primary);
}

.cat-info {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 160px;
  flex-shrink: 0;
}

.cat-icon {
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--primary);
  color: #fff;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 700;
  flex-shrink: 0;
}

.cat-detail {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.cat-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--text);
}

.cat-actions {
  display: flex;
  gap: 4px;
  flex-shrink: 0;
}
</style>
