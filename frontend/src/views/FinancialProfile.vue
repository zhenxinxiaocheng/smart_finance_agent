<template>
  <div class="profile-page">
    <div class="page-header">
      <div>
        <h1>财务画像</h1>
        <p>维护长期财务背景，智能助手会据此调整预算、省钱和风险建议。</p>
      </div>
      <el-button type="primary" size="large" :loading="saving" @click="handleSave">
        保存画像
      </el-button>
    </div>

    <div class="profile-layout">
      <section class="profile-panel form-panel">
        <div class="section-title">
          <h2>基础信息</h2>
          <span>用于判断建议尺度</span>
        </div>

        <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
          <el-row :gutter="16">
            <el-col :span="12">
              <el-form-item label="身份阶段" prop="lifeStage">
                <el-select v-model="form.lifeStage" placeholder="请选择" clearable>
                  <el-option label="学生" value="学生" />
                  <el-option label="上班族" value="上班族" />
                  <el-option label="自由职业" value="自由职业" />
                  <el-option label="家庭管理者" value="家庭管理者" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="风险偏好" prop="riskPreference">
                <el-segmented v-model="form.riskPreference" :options="riskOptions" />
              </el-form-item>
            </el-col>
          </el-row>

          <el-row :gutter="16">
            <el-col :span="12">
              <el-form-item label="月收入" prop="monthlyIncome">
                <el-input-number v-model="form.monthlyIncome" :min="0" :precision="2" :step="500" controls-position="right" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="固定支出" prop="fixedExpense">
                <el-input-number v-model="form.fixedExpense" :min="0" :precision="2" :step="100" controls-position="right" />
              </el-form-item>
            </el-col>
          </el-row>

          <div class="section-title compact">
            <h2>长期目标</h2>
            <span>用于判断当前消费是否影响目标</span>
          </div>

          <el-row :gutter="16">
            <el-col :span="12">
              <el-form-item label="储蓄目标金额" prop="savingsGoalAmount">
                <el-input-number v-model="form.savingsGoalAmount" :min="0" :precision="2" :step="500" controls-position="right" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="目标期限" prop="savingsGoalDeadline">
                <el-date-picker
                  v-model="form.savingsGoalDeadline"
                  type="month"
                  value-format="YYYY-MM"
                  placeholder="选择月份"
                  style="width: 100%"
                />
              </el-form-item>
            </el-col>
          </el-row>

          <div class="section-title compact">
            <h2>本月预算设置</h2>
            <span>{{ currentBudgetMonth }}</span>
          </div>

          <el-row :gutter="16">
            <el-col :span="12">
              <el-form-item label="本月总预算" prop="monthlyBudgetGoal">
                <el-input-number v-model="form.monthlyBudgetGoal" :min="0" :precision="2" :step="200" controls-position="right" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="总预算预警阈值">
                <div class="percent-input-wrap">
                  <el-input-number v-model="totalBudgetThreshold" :min="1" :max="100" :precision="0" :step="5" controls-position="right" />
                  <span class="percent-suffix">%</span>
                </div>
              </el-form-item>
            </el-col>
          </el-row>

          <div class="budget-editor">
            <div class="budget-editor-head">
              <span>分类预算</span>
              <el-button text type="primary" @click="addCategoryBudget">新增分类预算</el-button>
            </div>
            <div v-if="!categoryBudgets.length" class="budget-empty">暂未设置分类预算，可按餐饮、购物等分别控制。</div>
            <div v-for="(item, index) in categoryBudgets" :key="item.key" class="budget-row">
              <el-select v-model="item.category" placeholder="选择分类" class="budget-category">
                <el-option
                  v-for="category in categories"
                  :key="category.id"
                  :label="category.name"
                  :value="category.name"
                />
              </el-select>
              <el-input-number v-model="item.amount" :min="0" :precision="2" :step="100" controls-position="right" class="budget-amount" />
              <el-input-number v-model="item.alertThreshold" :min="1" :max="100" :precision="0" :step="5" controls-position="right" class="budget-threshold" />
              <el-button text type="danger" @click="removeCategoryBudget(index)">删除</el-button>
            </div>
          </div>

          <el-form-item label="补充偏好">
            <el-input
              v-model="form.notes"
              type="textarea"
              :rows="4"
              maxlength="500"
              show-word-limit
              placeholder="例如：优先攒应急金；少买数码产品；不接受高风险投资。"
            />
          </el-form-item>
        </el-form>
      </section>

      <aside class="profile-side">
        <section class="profile-panel summary-panel">
          <div class="section-title">
            <h2>目标概览</h2>
            <span>{{ hasProfile ? '已配置' : '待完善' }}</span>
          </div>
          <div class="metric-list">
            <div class="metric-row">
              <span>可支配收入</span>
              <strong>{{ money(disposableIncome) }}</strong>
            </div>
            <div class="metric-row">
              <span>预算占收入</span>
              <strong>{{ budgetRatio }}</strong>
            </div>
            <div class="metric-row">
              <span>储蓄目标</span>
              <strong>{{ money(form.savingsGoalAmount) }}</strong>
            </div>
          </div>
          <div class="risk-strip" :class="form.riskPreference?.toLowerCase()">
            {{ riskText }}
          </div>
        </section>

        <section class="profile-panel agent-panel">
          <div class="section-title">
            <h2>Agent 会如何使用</h2>
          </div>
          <ul>
            <li>回答省钱建议时，参考月收入、固定支出和月度预算目标。</li>
            <li>回答储蓄问题时，判断当前消费是否影响目标期限。</li>
            <li>回答理财问题时，按风险偏好控制建议边界。</li>
          </ul>
          <el-button plain type="primary" @click="goChat">去问智能助手</el-button>
        </section>

        <section class="profile-panel alert-panel">
          <div class="section-title">
            <h2>最近预警</h2>
            <span>{{ recentAlerts.length }} 条</span>
          </div>
          <div v-if="!recentAlerts.length" class="alert-empty">保存预算后，达到阈值或超支时会显示在这里。</div>
          <div v-for="alert in recentAlerts" :key="alert.id" class="alert-item" :class="alert.severity?.toLowerCase()">
            <div class="alert-top">
              <strong>{{ alert.category === 'ALL' ? '总预算' : alert.category }}</strong>
              <span>{{ alertLabel(alert) }}</span>
            </div>
            <p>{{ alert.message }}</p>
          </div>
        </section>
      </aside>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getFinancialProfileAPI, saveFinancialProfileAPI } from '../api/financialProfile'
import { getBudgetsAPI, saveBudgetAPI, deleteBudgetAPI } from '../api/budget'
import { getRecentAlertsAPI } from '../api/alert'
import { listCategoriesAPI } from '../api/category'

const router = useRouter()
const formRef = ref(null)
const saving = ref(false)
const loading = ref(false)
const hasProfile = ref(false)
const categories = ref([])
const categoryBudgets = ref([])
const removedBudgetIds = ref([])
const recentAlerts = ref([])
const totalBudgetId = ref(null)
const totalBudgetThreshold = ref(80)
const currentBudgetMonth = ref(currentMonth())

const form = reactive({
  lifeStage: '',
  monthlyIncome: 0,
  fixedExpense: 0,
  riskPreference: 'STEADY',
  savingsGoalAmount: 0,
  savingsGoalDeadline: '',
  monthlyBudgetGoal: 0,
  notes: ''
})

const riskOptions = [
  { label: '保守', value: 'CONSERVATIVE' },
  { label: '稳健', value: 'STEADY' },
  { label: '进取', value: 'AGGRESSIVE' }
]

const rules = {
  monthlyIncome: [{ type: 'number', min: 0, message: '月收入不能为负数' }],
  fixedExpense: [{ type: 'number', min: 0, message: '固定支出不能为负数' }],
  savingsGoalAmount: [{ type: 'number', min: 0, message: '储蓄目标不能为负数' }],
  monthlyBudgetGoal: [{ type: 'number', min: 0, message: '预算目标不能为负数' }]
}

const disposableIncome = computed(() => Math.max(Number(form.monthlyIncome || 0) - Number(form.fixedExpense || 0), 0))

const budgetRatio = computed(() => {
  const income = Number(form.monthlyIncome || 0)
  if (!income || !form.monthlyBudgetGoal) return '-'
  return `${Math.round((Number(form.monthlyBudgetGoal) / income) * 100)}%`
})

const riskText = computed(() => {
  if (form.riskPreference === 'CONSERVATIVE') return '保守型：优先保障现金流和低波动目标'
  if (form.riskPreference === 'AGGRESSIVE') return '进取型：可接受更高波动，但仍需保留应急金'
  return '稳健型：兼顾储蓄进度和日常生活质量'
})

function money(value) {
  return `¥${Number(value || 0).toFixed(2)}`
}

function applyProfile(profile) {
  form.lifeStage = profile.lifeStage || ''
  form.monthlyIncome = Number(profile.monthlyIncome || 0)
  form.fixedExpense = Number(profile.fixedExpense || 0)
  form.riskPreference = profile.riskPreference || 'STEADY'
  form.savingsGoalAmount = Number(profile.savingsGoalAmount || 0)
  form.savingsGoalDeadline = profile.savingsGoalDeadline || ''
  form.monthlyBudgetGoal = Number(profile.monthlyBudgetGoal || 0)
  form.notes = profile.notes || ''
  hasProfile.value = Boolean(profile.id)
}

function applyBudgetData(data) {
  const items = data?.items || []
  currentBudgetMonth.value = data?.month || currentMonth()
  removedBudgetIds.value = []

  const totalBudget = items.find(item => item.category === 'ALL')
  totalBudgetId.value = totalBudget?.id || null
  totalBudgetThreshold.value = totalBudget?.alertThreshold || 80
  if (totalBudget) {
    form.monthlyBudgetGoal = Number(totalBudget.budgetAmount || 0)
  }

  categoryBudgets.value = items
    .filter(item => item.category !== 'ALL')
    .map(item => ({
      key: item.id || `${item.category}-${item.month}`,
      id: item.id || null,
      category: item.category,
      amount: Number(item.budgetAmount || 0),
      alertThreshold: item.alertThreshold || 80
    }))
}

async function loadProfile() {
  loading.value = true
  try {
    const [profileRes, budgetRes, alertRes, categoryRes] = await Promise.all([
      getFinancialProfileAPI(),
      getBudgetsAPI(currentBudgetMonth.value),
      getRecentAlertsAPI(5),
      listCategoriesAPI()
    ])
    applyProfile(profileRes.data || {})
    applyBudgetData(budgetRes.data || {})
    recentAlerts.value = alertRes.data || []
    categories.value = categoryRes.data || []
  } finally {
    loading.value = false
  }
}

async function handleSave() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    const profileRes = await saveFinancialProfileAPI({ ...form })
    applyProfile(profileRes.data || {})
    await syncBudgets()
    const [budgetRes, alertRes] = await Promise.all([
      getBudgetsAPI(currentBudgetMonth.value),
      getRecentAlertsAPI(5)
    ])
    applyBudgetData(budgetRes.data || {})
    recentAlerts.value = alertRes.data || []
    ElMessage.success('财务画像和预算已保存')
  } finally {
    saving.value = false
  }
}

async function syncBudgets() {
  if (Number(form.monthlyBudgetGoal || 0) > 0) {
    await saveBudgetAPI({
      category: 'ALL',
      month: currentBudgetMonth.value,
      amount: Number(form.monthlyBudgetGoal),
      alertThreshold: totalBudgetThreshold.value || 80
    })
  } else if (totalBudgetId.value) {
    await deleteBudgetAPI(totalBudgetId.value)
  }

  for (const id of removedBudgetIds.value) {
    await deleteBudgetAPI(id)
  }

  for (const item of categoryBudgets.value) {
    if (!item.category || Number(item.amount || 0) <= 0) {
      continue
    }
    await saveBudgetAPI({
      category: item.category,
      month: currentBudgetMonth.value,
      amount: Number(item.amount),
      alertThreshold: item.alertThreshold || 80
    })
  }
}

function addCategoryBudget() {
  categoryBudgets.value.push({
    key: `new-${Date.now()}-${categoryBudgets.value.length}`,
    id: null,
    category: '',
    amount: 0,
    alertThreshold: 80
  })
}

function removeCategoryBudget(index) {
  const [removed] = categoryBudgets.value.splice(index, 1)
  if (removed?.id) {
    removedBudgetIds.value.push(removed.id)
  }
}

function alertLabel(alert) {
  return alert.alertType === 'OVERRUN' ? '已超支' : `${alert.usagePercent}%`
}

function currentMonth() {
  const now = new Date()
  const month = `${now.getMonth() + 1}`.padStart(2, '0')
  return `${now.getFullYear()}-${month}`
}

function goChat() {
  router.push('/chat')
}

onMounted(loadProfile)
</script>

<style scoped>
.profile-page {
  max-width: 1180px;
  margin: 0 auto;
}

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 22px;
}

.page-header h1 {
  margin: 0;
  color: var(--text);
  font-size: 28px;
  font-weight: 800;
}

.page-header p {
  margin: 8px 0 0;
  color: var(--text-secondary);
  font-size: 14px;
}

.profile-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 360px;
  gap: 20px;
  align-items: start;
}

.profile-panel {
  border: 1px solid var(--border);
  border-radius: 12px;
  background: var(--bg-card);
  box-shadow: var(--shadow);
}

.form-panel {
  padding: 24px;
}

.profile-side {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.summary-panel,
.agent-panel {
  padding: 20px;
}

.section-title {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 18px;
}

.section-title.compact {
  margin-top: 18px;
}

.section-title h2 {
  margin: 0;
  color: var(--text);
  font-size: 16px;
  font-weight: 700;
}

.section-title span {
  color: var(--text-muted);
  font-size: 12px;
}

:deep(.el-input-number) {
  width: 100%;
}

.percent-input-wrap {
  position: relative;
}

.percent-input-wrap :deep(.el-input-number .el-input__wrapper) {
  padding-right: 30px;
}

.percent-suffix {
  position: absolute;
  top: 50%;
  right: 36px;
  transform: translateY(-50%);
  color: var(--text-muted);
  font-size: 13px;
  font-weight: 600;
  pointer-events: none;
}

:deep(.el-segmented) {
  width: 100%;
}

:deep(.el-segmented__item) {
  flex: 1;
}

.metric-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.metric-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px;
  border-radius: 8px;
  background: var(--bg);
}

.metric-row span {
  color: var(--text-secondary);
  font-size: 13px;
}

.metric-row strong {
  color: var(--text);
  font-size: 15px;
}

.risk-strip {
  margin-top: 14px;
  padding: 12px;
  border-radius: 8px;
  background: #eef2ff;
  color: #1e3a8a;
  font-size: 13px;
  line-height: 1.6;
}

.risk-strip.conservative {
  background: #ecfdf5;
  color: #047857;
}

.risk-strip.aggressive {
  background: #fff7ed;
  color: #c2410c;
}

.agent-panel ul {
  margin: 0 0 18px;
  padding-left: 18px;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.8;
}

.budget-editor {
  margin-bottom: 18px;
}

.budget-editor-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
  color: var(--text-secondary);
  font-size: 13px;
  font-weight: 600;
}

.budget-empty,
.alert-empty {
  padding: 12px;
  border-radius: 8px;
  background: var(--bg);
  color: var(--text-muted);
  font-size: 13px;
}

.budget-row {
  display: grid;
  grid-template-columns: minmax(0, 1.1fr) minmax(0, 1fr) 110px 56px;
  gap: 10px;
  align-items: center;
  margin-bottom: 10px;
}

.budget-category,
.budget-amount,
.budget-threshold {
  width: 100%;
}

.alert-panel {
  padding: 20px;
}

.alert-item {
  padding: 12px;
  border-radius: 8px;
  background: #fff7ed;
  margin-top: 10px;
}

.alert-item.critical {
  background: #fef2f2;
}

.alert-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 6px;
}

.alert-top strong {
  color: var(--text);
  font-size: 13px;
}

.alert-top span {
  color: var(--text-muted);
  font-size: 12px;
}

.alert-item p {
  margin: 0;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.6;
}

@media (max-width: 960px) {
  .profile-layout {
    grid-template-columns: 1fr;
  }

  .page-header {
    flex-direction: column;
  }

  .budget-row {
    grid-template-columns: 1fr;
  }
}
</style>
