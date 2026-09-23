<template>
  <div class="mx-auto flex max-w-[1400px] flex-col gap-5">
    <div class="flex flex-col gap-4 md:flex-row md:items-start md:justify-between">
      <div class="min-w-0">
        <h1 class="text-2xl font-semibold tracking-normal text-foreground">账单导入</h1>
        <p class="mt-1 max-w-3xl text-sm text-muted-foreground">
          上传微信、支付宝或银行卡流水截图，由多模态大模型识别账单来源并抽取交易内容，确认后再写入正式消费记录。
        </p>
      </div>
      <Badge variant="secondary" class="w-fit">
        <ScanLine data-icon="inline-start" />
        多模态账单识别
      </Badge>
    </div>

    <Card>
      <CardHeader>
        <CardTitle class="text-base">上传账单截图</CardTitle>
        <CardDescription>支持常见支付流水截图。建议先对姓名、卡号、订单号等隐私信息打码。</CardDescription>
      </CardHeader>
      <CardContent class="flex flex-col gap-4">
        <button
          type="button"
          class="flex w-full flex-col items-center gap-3 rounded-xl border border-dashed bg-muted/25 py-8 text-center transition-colors hover:bg-muted/40"
          @click="fileInputRef?.click()"
        >
          <div class="flex size-12 items-center justify-center rounded-lg bg-muted text-muted-foreground">
            <UploadCloud />
          </div>
          <div class="text-sm font-medium text-foreground">点击选择账单截图</div>
          <div class="text-xs text-muted-foreground">图片只用于识别候选交易，确认后才写入正式记录。</div>
        </button>
        <input ref="fileInputRef" class="hidden" type="file" accept="image/*" @change="handleFileChange" />

        <Alert v-if="selectedFile">
          <FileImage data-icon="inline-start" />
          <AlertTitle>{{ selectedFile.name }}</AlertTitle>
          <AlertDescription>文件已选择，可以开始识别候选交易。</AlertDescription>
          <AlertAction>
            <Button :disabled="loading" @click="submitImport">
              <Loader2 v-if="loading" data-icon="inline-start" class="animate-spin" />
              开始识别
            </Button>
          </AlertAction>
        </Alert>
      </CardContent>
    </Card>

    <Card v-if="candidates.length">
      <CardHeader class="flex flex-col gap-4 md:flex-row md:items-start md:justify-between">
        <div>
          <CardTitle class="text-base">候选交易</CardTitle>
          <CardDescription>请检查金额、类型、分类和日期。只有确认后才会进入正式交易记录。</CardDescription>
        </div>
        <Button :disabled="confirming" @click="confirmImport">
          <Loader2 v-if="confirming" data-icon="inline-start" class="animate-spin" />
          <CheckCircle2 v-else data-icon="inline-start" />
          确认导入选中交易
        </Button>
      </CardHeader>
      <CardContent class="p-0">
        <div class="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead class="min-w-20">导入</TableHead>
                <TableHead class="min-w-40">金额</TableHead>
                <TableHead class="min-w-40">类型</TableHead>
                <TableHead class="min-w-40">分类</TableHead>
                <TableHead class="min-w-44">日期</TableHead>
                <TableHead class="min-w-60">描述</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              <TableRow v-for="row in candidates" :key="row.id || `${row.transactionDate}-${row.amount}-${row.description}`">
                <TableCell>
                  <Checkbox v-model="row.selected" :disabled="confirming || row.status === 'CONFIRMED'" />
                </TableCell>
                <TableCell>
                  <Input v-model.number="row.amount" :disabled="confirming || row.status === 'CONFIRMED'" min="0.01" step="0.01" type="number" />
                </TableCell>
                <TableCell>
                  <div class="grid grid-cols-2 gap-2">
                    <Button size="sm" :disabled="confirming || row.status === 'CONFIRMED'" :variant="row.type === 'EXPENSE' ? 'destructive' : 'outline'" @click="row.type = 'EXPENSE'">支出</Button>
                    <Button size="sm" :disabled="confirming || row.status === 'CONFIRMED'" :variant="row.type === 'INCOME' ? 'default' : 'outline'" @click="row.type = 'INCOME'">收入</Button>
                  </div>
                </TableCell>
                <TableCell>
                  <Input v-model="row.category" :disabled="confirming || row.status === 'CONFIRMED'" placeholder="如 餐饮 / 购物" />
                </TableCell>
                <TableCell>
                  <Input v-model="row.transactionDate" :disabled="confirming || row.status === 'CONFIRMED'" type="date" />
                </TableCell>
                <TableCell>
                  <Input v-model="row.description" :disabled="confirming || row.status === 'CONFIRMED'" placeholder="补充描述" />
                </TableCell>
              </TableRow>
            </TableBody>
          </Table>
        </div>
      </CardContent>
    </Card>

    <Alert v-else-if="result && !loading">
      <AlertTitle>暂无候选交易</AlertTitle>
      <AlertDescription>非账单图片、低置信度或多模态抽取失败时会出现这种情况。</AlertDescription>
    </Alert>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { CheckCircle2, FileImage, Loader2, ScanLine, UploadCloud } from '@lucide/vue'
import { Alert, AlertAction, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Checkbox } from '@/components/ui/checkbox'
import { Input } from '@/components/ui/input'
import { feedback } from '@/lib/feedback'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow
} from '@/components/ui/table'
import { importBillAPI, confirmBillAPI } from '../api/bill'

const fileInputRef = ref(null)
const selectedFile = ref(null)
const loading = ref(false)
const confirming = ref(false)
const result = ref(null)
const candidates = ref([])

function handleFileChange(event) {
  selectedFile.value = event.target.files?.[0] || null
  result.value = null
  candidates.value = []
}

async function submitImport() {
  if (!selectedFile.value) {
    feedback.warning('请先选择账单图片')
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
      feedback.success('识别完成，请确认候选交易')
    } else {
      feedback.warning('识别完成，但未生成候选交易')
    }
  } finally {
    loading.value = false
  }
}

async function confirmImport() {
  if (confirming.value) return
  const billId = result.value?.id
  if (!billId) return
  const selectedCount = candidates.value.filter(item => item.selected).length
  if (!selectedCount) {
    feedback.warning('请至少选择一条候选交易')
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
    const res = await confirmBillAPI(billId, payload)
    if (result.value?.id !== billId) return
    feedback.success(`已导入 ${res.data.length} 条交易记录`)
    candidates.value = candidates.value.map(item => ({
      ...item,
      selected: false,
      status: item.status === 'CONFIRMED' || item.selected ? 'CONFIRMED' : 'IGNORED'
    }))
  } finally {
    confirming.value = false
  }
}
</script>
