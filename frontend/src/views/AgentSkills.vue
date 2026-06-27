<template>
  <div class="skills-page">
    <section class="skills-main">
      <header class="page-head">
        <div>
          <h1>Agent 技能</h1>
          <p>启用、禁用和审计 Agent 可使用的 Skills。</p>
        </div>
        <button class="refresh-btn" @click="loadSkills">刷新</button>
      </header>

      <div class="filter-row">
        <el-input v-model="keyword" placeholder="搜索技能" clearable />
        <el-select v-model="category" placeholder="全部分类" clearable>
          <el-option v-for="item in categories" :key="item" :label="item" :value="item" />
        </el-select>
      </div>

      <div v-if="loading" class="empty-state">加载中...</div>
      <div v-else-if="filteredSkills.length === 0" class="empty-state">暂无技能</div>
      <div v-else class="skill-grid">
        <article
          v-for="skill in filteredSkills"
          :key="skill.id"
          class="skill-card"
          :class="{ active: selectedSkill?.id === skill.id, disabled: !isEnabled(skill) }"
          @click="selectSkill(skill)"
        >
          <div class="skill-card-head">
            <div>
              <h2>{{ skill.name || skill.skillKey }}</h2>
              <p>{{ skill.skillKey }}</p>
            </div>
            <el-switch
              :model-value="isEnabled(skill)"
              :loading="skill.updating"
              @click.stop
              @change="value => toggleSkill(skill, value)"
            />
          </div>
          <p class="skill-desc">{{ skill.description || '暂无描述' }}</p>
          <div class="skill-meta">
            <span>{{ skill.category || '未分类' }}</span>
            <span :class="riskClass(skill.riskLevel)">{{ riskLabel(skill.riskLevel) }}</span>
            <span>{{ sourceLabel(skill) }}</span>
          </div>
        </article>
      </div>
    </section>

    <aside class="detail-panel">
      <template v-if="selectedSkill">
        <div class="detail-head">
          <div>
            <h2>{{ selectedSkill.name || selectedSkill.skillKey }}</h2>
            <p>{{ selectedSkill.sourceType }} · {{ selectedSkill.version || '1.0.0' }}</p>
          </div>
          <button v-if="!Number(selectedSkill.builtIn)" class="danger-btn" @click="deleteSkill(selectedSkill)">卸载</button>
        </div>

        <dl class="detail-list">
          <div>
            <dt>风险等级</dt>
            <dd>{{ riskLabel(selectedSkill.riskLevel) }}</dd>
          </div>
          <div>
            <dt>绑定工具</dt>
            <dd>{{ selectedSkill.boundTools || '无' }}</dd>
          </div>
          <div>
            <dt>来源</dt>
            <dd>{{ selectedSkill.sourceUri }}</dd>
          </div>
        </dl>

        <section class="instruction-box">
          <h3>Skill 说明</h3>
          <pre>{{ selectedSkill.instructionText || selectedSkill.description || '暂无说明' }}</pre>
        </section>

        <section class="invocation-box">
          <div class="section-title">
            <h3>调用历史</h3>
            <button class="ghost-btn" @click="loadInvocations(selectedSkill)">刷新</button>
          </div>
          <div v-if="invocationLoading" class="empty-state small">加载中...</div>
          <div v-else-if="invocations.length === 0" class="empty-state small">暂无调用记录</div>
          <div v-else class="invocation-list">
            <div v-for="item in invocations" :key="item.id" class="invocation-item">
              <div class="invocation-top">
                <span>{{ item.skillName }}</span>
                <span :class="item.success ? 'ok' : 'fail'">{{ item.success ? '成功' : item.blocked ? '已拦截' : '失败' }}</span>
              </div>
              <p>{{ item.summary }}</p>
              <time>{{ formatTime(item.createdAt) }}</time>
            </div>
          </div>
        </section>
      </template>
      <div v-else class="empty-state">选择一个技能查看详情</div>
    </aside>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  deleteAgentSkillAPI,
  listAgentSkillsAPI,
  listSkillInvocationsAPI,
  setAgentSkillEnabledAPI
} from '../api/agentSkills'

const skills = ref([])
const selectedSkill = ref(null)
const invocations = ref([])
const loading = ref(false)
const invocationLoading = ref(false)
const keyword = ref('')
const category = ref('')

const categories = computed(() => [...new Set(skills.value.map(skill => skill.category).filter(Boolean))])

const filteredSkills = computed(() => {
  const key = keyword.value.trim().toLowerCase()
  return skills.value.filter(skill => {
    const matchKeyword = !key
      || `${skill.name || ''} ${skill.skillKey || ''} ${skill.description || ''}`.toLowerCase().includes(key)
    const matchCategory = !category.value || skill.category === category.value
    return matchKeyword && matchCategory
  })
})

function isEnabled(skill) {
  return Number(skill?.enabled ?? 0) === 1
}

function sourceLabel(skill) {
  if (Number(skill?.builtIn ?? 0) === 1) return '内置'
  return skill?.sourceType || '外部'
}

function riskLabel(risk) {
  if (risk === 'REQUIRES_CONFIRMATION') return '需确认'
  if (risk === 'EXTERNAL_INFORMATION') return '外部信息'
  return '只读'
}

function riskClass(risk) {
  if (risk === 'REQUIRES_CONFIRMATION') return 'risk-confirm'
  if (risk === 'EXTERNAL_INFORMATION') return 'risk-external'
  return 'risk-read'
}

async function loadSkills() {
  loading.value = true
  try {
    const res = await listAgentSkillsAPI()
    skills.value = Array.isArray(res.data) ? res.data : []
    selectedSkill.value = selectedSkill.value
      ? skills.value.find(item => item.id === selectedSkill.value.id) || skills.value[0] || null
      : skills.value[0] || null
    if (selectedSkill.value) {
      await loadInvocations(selectedSkill.value)
    }
  } finally {
    loading.value = false
  }
}

async function toggleSkill(skill, enabled) {
  skill.updating = true
  try {
    const res = await setAgentSkillEnabledAPI(skill.id, enabled)
    Object.assign(skill, res.data || {}, { updating: false })
    if (selectedSkill.value?.id === skill.id) {
      selectedSkill.value = skill
    }
  } catch {
    skill.updating = false
  }
}

async function deleteSkill(skill) {
  await ElMessageBox.confirm(`确定卸载 ${skill.name || skill.skillKey} 吗？`, '卸载技能', {
    confirmButtonText: '卸载',
    cancelButtonText: '取消',
    type: 'warning'
  })
  await deleteAgentSkillAPI(skill.id)
  ElMessage.success('技能已卸载')
  selectedSkill.value = null
  await loadSkills()
}

function selectSkill(skill) {
  selectedSkill.value = skill
  loadInvocations(skill)
}

async function loadInvocations(skill) {
  if (!skill) return
  invocationLoading.value = true
  try {
    const res = await listSkillInvocationsAPI({ skillName: skill.skillKey, limit: 30 })
    invocations.value = Array.isArray(res.data) ? res.data : []
  } finally {
    invocationLoading.value = false
  }
}

function formatTime(value) {
  return value ? String(value).slice(0, 19) : ''
}

onMounted(async () => {
  await loadSkills()
})
</script>

<style scoped>
.skills-page {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 360px;
  gap: 20px;
  height: 100%;
  padding: 24px;
  background: #f3f6fb;
  color: #172033;
  overflow: auto;
}

.skills-main,
.detail-panel,
.install-panel {
  background: #fff;
  border: 1px solid #dfe7f2;
  border-radius: 8px;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}

.skills-main {
  padding: 20px;
  min-width: 0;
}

.page-head,
.skill-card-head,
.detail-head,
.section-title,
.invocation-top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.page-head h1,
.detail-head h2,
.skill-card h2 {
  margin: 0;
  color: #101828;
}

.page-head h1 {
  font-size: 22px;
}

.page-head p,
.skill-card-head p,
.detail-head p,
.skill-desc,
.invocation-item p,
.empty-state {
  margin: 6px 0 0;
  color: #667085;
  font-size: 13px;
  line-height: 1.5;
}

.install-panel {
  margin: 18px 0;
  padding: 14px;
}

.filter-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 180px;
  margin-bottom: 16px;
  gap: 10px;
  align-items: center;
}

.refresh-btn,
.ghost-btn,
.danger-btn {
  height: 36px;
  border: 0;
  border-radius: 6px;
  padding: 0 14px;
  cursor: pointer;
  font-weight: 600;
}

.refresh-btn,
.ghost-btn {
  background: #eef4ff;
  color: #1d4ed8;
}

.danger-btn {
  background: #fff1f2;
  color: #be123c;
}

.skill-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px;
}

.skill-card {
  border: 1px solid #dfe7f2;
  border-radius: 8px;
  padding: 14px;
  cursor: pointer;
  transition: border-color 0.2s, box-shadow 0.2s;
}

.skill-card.active {
  border-color: #2563eb;
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.10);
}

.skill-card.disabled {
  opacity: 0.62;
}

.skill-card h2 {
  font-size: 15px;
}

.skill-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
}

.skill-meta span {
  border-radius: 999px;
  background: #f1f5f9;
  color: #475569;
  padding: 3px 8px;
  font-size: 12px;
}

.skill-meta .risk-confirm {
  background: #fff7ed;
  color: #c2410c;
}

.skill-meta .risk-external {
  background: #ecfeff;
  color: #0e7490;
}

.skill-meta .risk-read {
  background: #f0fdf4;
  color: #15803d;
}

.detail-panel {
  padding: 18px;
  min-width: 0;
}

.detail-list {
  display: grid;
  gap: 10px;
  margin: 18px 0;
}

.detail-list div {
  border-bottom: 1px solid #edf2f7;
  padding-bottom: 10px;
}

.detail-list dt {
  color: #667085;
  font-size: 12px;
}

.detail-list dd {
  margin: 4px 0 0;
  color: #172033;
  font-size: 13px;
  overflow-wrap: anywhere;
}

.instruction-box,
.invocation-box {
  margin-top: 18px;
}

.instruction-box h3,
.invocation-box h3 {
  margin: 0 0 10px;
  font-size: 15px;
}

.instruction-box pre {
  margin: 0;
  max-height: 260px;
  overflow: auto;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  border: 1px solid #e5edf6;
  border-radius: 8px;
  background: #f8fafc;
  padding: 12px;
  color: #334155;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.6;
}

.invocation-list {
  display: grid;
  gap: 10px;
}

.invocation-item {
  border: 1px solid #e5edf6;
  border-radius: 8px;
  padding: 10px;
}

.invocation-item time {
  display: block;
  margin-top: 6px;
  color: #98a2b3;
  font-size: 12px;
}

.ok {
  color: #15803d;
}

.fail {
  color: #b42318;
}

.empty-state {
  padding: 24px;
  text-align: center;
}

.empty-state.small {
  padding: 12px;
}

@media (max-width: 1100px) {
  .skills-page {
    grid-template-columns: 1fr;
  }

  .detail-panel {
    order: -1;
  }
}

@media (max-width: 720px) {
  .skills-page {
    padding: 12px;
  }

  .filter-row {
    grid-template-columns: 1fr;
  }
}
</style>
