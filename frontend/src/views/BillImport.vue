<template>
  <div class="bill-import-page">
    <section class="hero">
      <div>
        <h1>账单导入</h1>
        <p>上传微信、支付宝或银行卡流水截图，由多模态大模型识别账单来源并抽取交易内容，确认后再写入正式消费记录。</p>
      </div>
      <el-tag type="primary" size="large">多模态账单识别</el-tag>
    </section>

    <section class="upload-panel">
      <el-upload
        drag
        action="#"
        :auto-upload="false"
        :show-file-list="false"
        accept="image/*"
        :on-change="handleFileChange"
      >
        <el-icon class="upload-icon"><UploadFilled /></el-icon>
        <div class="el-upload__text">拖拽账单截图到这里，或点击选择图片</div>
        <template #tip>
          <div class="upload-tip">建议先对姓名、卡号、订单号等隐私信息打码。</div>
        </template>
      </el-upload>

      <div v-if="selectedFile" class="selected-file">
        <span>{{ selectedFile.name }}</span>
        <el-button type="primary" :loading="loading" @click="submitImport">
          开始识别
        </el-button>
      </div>
    </section>

    <section v-if="candidates.length" class="candidate-panel">
      <div class="candidate-header">
        <div>
          <h2>候选交易</h2>
          <p>请检查金额、类型、分类和日期。只有确认后才会进入正式交易记录。</p>
        </div>
        <el-button type="success" :loading="confirming" @click="confirmImport">
          确认导入选中交易
        </el-button>
      </div>

      <el-table :data="candidates" border>
        <el-table-column width="72" label="导入">
          <template #default="{ row }">
            <el-checkbox v-model="row.selected" />
          </template>
        </el-table-column>
        <el-table-column label="金额" min-width="140">
          <template #default="{ row }">
            <el-input-number v-model="row.amount" :min="0.01" :precision="2" :step="1" />
          </template>
        </el-table-column>
        <el-table-column label="类型" width="130">
          <template #default="{ row }">
            <el-select v-model="row.type">
              <el-option label="支出" value="EXPENSE" />
              <el-option label="收入" value="INCOME" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="分类" min-width="140">
          <template #default="{ row }">
            <el-input v-model="row.category" placeholder="如 餐饮 / 购物" />
          </template>
        </el-table-column>
        <el-table-column label="日期" min-width="160">
          <template #default="{ row }">
            <el-date-picker v-model="row.transactionDate" type="date" value-format="YYYY-MM-DD" />
          </template>
        </el-table-column>
        <el-table-column label="描述" min-width="220">
          <template #default="{ row }">
            <el-input v-model="row.description" />
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-empty
      v-else-if="result && !loading"
      description="当前没有候选交易。非账单图片、低置信度或多模态抽取失败时会出现这种情况。"
    />
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { importBillAPI, confirmBillAPI } from '../api/bill'

const selectedFile = ref(null)
const loading = ref(false)
const confirming = ref(false)
const result = ref(null)
const candidates = ref([])

function handleFileChange(uploadFile) {
  selectedFile.value = uploadFile.raw
  result.value = null
  candidates.value = []
}

async function submitImport() {
  if (!selectedFile.value) {
    ElMessage.warning('请先选择账单图片')
    return
  }
  loading.value = true
  try {
    const res = await importBillAPI(selectedFile.value)
    result.value = res.data
    candidates.value = (res.data.candidates || []).map(item => ({
      ...item,
      selected: item.status !== 'CONFIRMED' && item.status !== 'IGNORED'
    }))
    if (candidates.value.length) {
      ElMessage.success('识别完成，请确认候选交易')
    } else {
      ElMessage.warning('识别完成，但未生成候选交易')
    }
  } finally {
    loading.value = false
  }
}

async function confirmImport() {
  const selectedCount = candidates.value.filter(item => item.selected).length
  if (!selectedCount) {
    ElMessage.warning('请至少选择一条候选交易')
    return
  }
  confirming.value = true
  try {
    const payload = {
      candidates: candidates.value.map(item => ({
        id: item.id,
        selected: item.selected,
        amount: item.amount,
        type: item.type,
        category: item.category,
        description: item.description,
        transactionDate: item.transactionDate
      }))
    }
    const res = await confirmBillAPI(result.value.id, payload)
    ElMessage.success(`已导入 ${res.data.length} 条交易记录`)
    candidates.value = candidates.value.map(item => ({
      ...item,
      selected: false,
      status: item.selected ? 'CONFIRMED' : 'IGNORED'
    }))
  } finally {
    confirming.value = false
  }
}

</script>

<style scoped>
.bill-import-page {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.hero,
.upload-panel,
.candidate-panel {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 24px;
}

.hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.hero h1 {
  margin: 0 0 8px;
  font-size: 28px;
  color: var(--text-primary);
}

.hero p,
.candidate-header p {
  margin: 0;
  color: var(--text-secondary);
}

.upload-icon {
  font-size: 42px;
  color: var(--primary);
}

.upload-tip {
  color: var(--text-secondary);
  font-size: 13px;
}

.selected-file {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 16px;
  padding: 12px 16px;
  background: var(--bg);
  border-radius: 6px;
}

.panel-title {
  margin-bottom: 16px;
  font-size: 18px;
  font-weight: 700;
  color: var(--text-primary);
}

.candidate-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
}

.candidate-header h2 {
  margin: 0 0 6px;
  font-size: 20px;
  color: var(--text-primary);
}

@media (max-width: 960px) {
  .hero,
  .candidate-header {
    align-items: flex-start;
    flex-direction: column;
  }

}
</style>
