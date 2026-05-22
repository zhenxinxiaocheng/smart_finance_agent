<template>
  <div class="transaction-page">
    <div class="page-header">
      <div class="header-text">
        <h1 class="page-title">消费记录</h1>
        <p class="page-subtitle">管理和查看你的所有收支记录</p>
      </div>
      <el-button type="primary" size="large" @click="showAddDialog">
        <el-icon><Plus /></el-icon>
        新增记录
      </el-button>
    </div>

    <div class="filter-section">
      <div class="filter-row">
        <div class="filter-group">
          <span class="filter-label">类型</span>
          <div class="filter-chips">
            <span
              class="chip"
              :class="{ active: filter.type === '' }"
              @click="filter.type = ''; fetchData()"
            >全部</span>
            <span
              class="chip chip-income"
              :class="{ active: filter.type === 'INCOME' }"
              @click="filter.type = 'INCOME'; fetchData()"
            >收入</span>
            <span
              class="chip chip-expense"
              :class="{ active: filter.type === 'EXPENSE' }"
              @click="filter.type = 'EXPENSE'; fetchData()"
            >支出</span>
          </div>
        </div>
        <div class="filter-group">
          <span class="filter-label">分类</span>
          <el-select v-model="filter.category" placeholder="全部分类" clearable size="default" @change="fetchData">
            <el-option v-for="cat in categories" :key="cat" :label="cat" :value="cat" />
          </el-select>
        </div>
        <div class="filter-group">
          <span class="filter-label">日期</span>
          <el-date-picker
            v-model="filter.dateRange"
            type="daterange"
            range-separator="至"
            start-placeholder="开始"
            end-placeholder="结束"
            value-format="YYYY-MM-DD"
            @change="fetchData"
          />
        </div>
        <div class="filter-actions">
          <el-button @click="resetFilter">重置</el-button>
        </div>
      </div>
    </div>

    <div class="table-card">
      <div class="table-header">
        <div class="table-info">
          共 <strong>{{ total }}</strong> 条记录
        </div>
      </div>

      <el-table :data="tableData" v-loading="loading" style="width: 100%" :header-cell-style="{ background: 'transparent' }">
        <el-table-column prop="transactionDate" label="日期" width="130" />
        <el-table-column prop="type" label="类型" width="90">
          <template #default="{ row }">
            <span class="type-badge" :class="row.type === 'INCOME' ? 'income' : 'expense'">
              <el-icon :size="14">
                <Top v-if="row.type === 'INCOME'" />
                <Bottom v-else />
              </el-icon>
              {{ row.type === 'INCOME' ? '收入' : '支出' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="category" label="分类" width="120">
          <template #default="{ row }">
            <span class="category-tag">{{ row.category }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="amount" label="金额" width="160" sortable>
          <template #default="{ row }">
            <span class="amount-value" :class="row.type === 'INCOME' ? 'income' : 'expense'">
              {{ row.type === 'INCOME' ? '+' : '-' }}¥{{ Number(row.amount).toFixed(2) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="备注" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="desc-text">{{ row.description || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="180" />
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <div class="action-btns">
              <el-button text size="small" @click="showEditDialog(row)">
                <el-icon><Edit /></el-icon>
              </el-button>
              <el-button text type="danger" size="small" @click="handleDelete(row.id)">
                <el-icon><Delete /></el-icon>
              </el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrap">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          background
          @size-change="fetchData"
          @current-change="fetchData"
        />
      </div>
    </div>

    <el-dialog
      v-model="dialogVisible"
      :title="isEdit ? '编辑记录' : '新增记录'"
      width="520px"
      :close-on-click-modal="false"
      class="fin-dialog"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px" label-position="top">
        <el-form-item label="类型" prop="type">
          <el-radio-group v-model="form.type" class="type-radio-group">
            <el-radio-button value="EXPENSE">
              <el-icon><Bottom /></el-icon> 支出
            </el-radio-button>
            <el-radio-button value="INCOME">
              <el-icon><Top /></el-icon> 收入
            </el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="分类" prop="category">
              <el-select v-model="form.category" placeholder="选择分类" style="width: 100%">
                <el-option v-for="cat in categories" :key="cat" :label="cat" :value="cat" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="日期" prop="transactionDate">
              <el-date-picker
                v-model="form.transactionDate"
                type="date"
                placeholder="选择日期"
                value-format="YYYY-MM-DD"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="金额" prop="amount">
          <el-input-number
            v-model="form.amount"
            :min="0.01"
            :precision="2"
            :step="10"
            style="width: 100%"
            controls-position="right"
          />
        </el-form-item>
        <el-form-item label="备注" prop="description">
          <el-input v-model="form.description" :rows="3" placeholder="可选备注（如：午餐、地铁充值等）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitLoading" @click="handleSubmit">
          {{ isEdit ? '保存更改' : '添加记录' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { listTransactionsAPI, addTransactionAPI, updateTransactionAPI, deleteTransactionAPI } from '../api/transaction'
import { ElMessage, ElMessageBox } from 'element-plus'

const tableData = ref([])
const loading = ref(false)
const page = ref(1)
const size = ref(20)
const total = ref(0)

const filter = reactive({
  type: '',
  category: '',
  dateRange: null
})

const categories = ['餐饮', '交通', '购物', '住房', '娱乐', '工资', '其他']

const dialogVisible = ref(false)
const isEdit = ref(false)
const editId = ref(null)
const submitLoading = ref(false)
const formRef = ref(null)

const form = reactive({
  type: 'EXPENSE',
  category: '',
  amount: 0,
  transactionDate: '',
  description: ''
})

const rules = {
  type: [{ required: true, message: '请选择类型' }],
  category: [{ required: true, message: '请选择分类' }],
  amount: [{ required: true, message: '请输入金额' }],
  transactionDate: [{ required: true, message: '请选择日期' }]
}

async function fetchData() {
  loading.value = true
  try {
    const params = { page: page.value, size: size.value }
    if (filter.type) params.type = filter.type
    if (filter.category) params.category = filter.category
    if (filter.dateRange) {
      params.startDate = filter.dateRange[0]
      params.endDate = filter.dateRange[1]
    }
    const res = await listTransactionsAPI(params)
    if (res.code === 200) {
      tableData.value = res.data.records || []
      total.value = res.data.total || 0
    }
  } finally {
    loading.value = false
  }
}

function resetFilter() {
  filter.type = ''
  filter.category = ''
  filter.dateRange = null
  page.value = 1
  fetchData()
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
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  submitLoading.value = true
  try {
    if (isEdit.value) {
      await updateTransactionAPI(editId.value, form)
      ElMessage.success('更新成功')
    } else {
      await addTransactionAPI(form)
      ElMessage.success('添加成功')
    }
    dialogVisible.value = false
    fetchData()
  } finally {
    submitLoading.value = false
  }
}

function handleDelete(id) {
  ElMessageBox.confirm('确定要删除该记录吗？', '提示', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning'
  }).then(async () => {
    try {
      await deleteTransactionAPI(id)
      ElMessage.success('删除成功')
      fetchData()
    } catch {}
  }).catch(() => {})
}

onMounted(fetchData)
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
  margin-bottom: 24px;
}

.page-title {
  font-size: 24px;
  font-weight: 700;
  color: var(--text);
  margin: 0;
}

.page-subtitle {
  font-size: 14px;
  color: var(--text-muted);
  margin-top: 4px;
}

.filter-section {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: 20px 24px;
  margin-bottom: 20px;
  box-shadow: var(--shadow);
}

.filter-row {
  display: flex;
  align-items: flex-end;
  gap: 24px;
  flex-wrap: wrap;
}

.filter-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.filter-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.filter-chips {
  display: flex;
  gap: 6px;
}

.chip {
  padding: 6px 16px;
  border-radius: 20px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  background: var(--bg);
  color: var(--text-secondary);
  transition: var(--transition);
  border: 1.5px solid transparent;
}

.chip:hover {
  background: var(--primary-surface);
  color: var(--primary);
}

.chip.active {
  background: var(--primary);
  color: #fff;
  border-color: var(--primary);
}

.chip.chip-income.active { background: #1e3a8a; border-color: #1e3a8a; }
.chip.chip-income:hover:not(.active) { color: #1e3a8a; background: #eef2ff; }
.chip.chip-expense.active { background: #ef4444; border-color: #ef4444; }
.chip.chip-expense:hover:not(.active) { color: #ef4444; background: #fef2f2; }

.filter-actions {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  padding-bottom: 2px;
}

.table-card {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow);
  overflow: hidden;
}

.table-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 24px;
  border-bottom: 1px solid var(--border);
}

.table-info {
  font-size: 14px;
  color: var(--text-muted);
}

.table-info strong {
  color: var(--text);
  font-weight: 600;
}

.type-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 12px;
  border-radius: 6px;
  font-size: 13px;
  font-weight: 600;
}

.type-badge.income {
  background: #eef2ff;
  color: #1e3a8a;
}

.type-badge.expense {
  background: #fef2f2;
  color: #ef4444;
}

.category-tag {
  background: var(--bg);
  padding: 4px 10px;
  border-radius: 6px;
  font-size: 13px;
  color: var(--text-secondary);
}

.amount-value {
  font-size: 15px;
  font-weight: 700;
  font-feature-settings: 'tnum';
}

.amount-value.income { color: #1e3a8a; }
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

:deep(.el-table th.el-table__cell) {
  background: var(--bg) !important;
  font-weight: 600;
  color: var(--text-secondary);
  font-size: 12px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

:deep(.el-table__row) {
  transition: background 0.15s;
}

:deep(.el-table__row:hover) {
  background: var(--primary-50) !important;
}

.type-radio-group {
  display: flex;
  gap: 0;
}

:deep(.el-radio-button__inner) {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 20px;
}

:deep(.el-radio-button.is-active .el-radio-button__inner) {
  background: var(--primary);
  border-color: var(--primary);
}
</style>
