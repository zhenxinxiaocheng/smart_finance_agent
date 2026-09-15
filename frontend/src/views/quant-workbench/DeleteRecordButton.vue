<script setup>
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter } from '@/components/ui/dialog'
import { quant } from '@/api/quantWorkbench'
import { feedback } from '@/lib/feedback'
import { useOperation } from './shared'

const props = defineProps({ resource: { type: String, required: true }, record: { type: Object, required: true }, disabled: Boolean, hideTrigger: Boolean })
const emit = defineEmits(['deleted'])
const open = defineModel('open', { type: Boolean, default: false })
const { busy, error, run } = useOperation()
const descriptions = {
  universes: '删除后将从资产池列表移除，已有资产和行情数据保留。仍被策略使用的资产池需要先解除关联。',
  factors: '删除后将从因子组合列表移除，历史研究结果保留。仍被策略使用的组合需要先解除关联。',
  deployments: '删除后将从模拟组合列表移除，历史账本保留。请先停止组合并等待清仓和结算完成。',
}
function remove() {
  run(async () => {
    await quant.remove(props.resource, props.record.id)
    open.value = false
    feedback.success('已删除')
    emit('deleted', props.record.id)
  })
}
</script>

<template>
  <Button v-if="!hideTrigger" type="button" size="sm" variant="ghost" class="text-destructive hover:text-destructive" :disabled="disabled || busy" @click="open=true">删除</Button>
  <Dialog :open="open" @update:open="value=>{if(!busy)open=value}">
    <DialogContent>
      <DialogHeader>
        <DialogTitle>删除“{{record.name || '此记录'}}”？</DialogTitle>
        <DialogDescription>{{descriptions[resource] || '删除后将从任务列表移除，不能再选择此结果发起新任务或模拟部署。已经生成的关联结果保留，正在执行的任务需要先取消。'}}</DialogDescription>
      </DialogHeader>
      <p v-if="error" class="text-sm text-destructive" role="alert">{{error}}</p>
      <DialogFooter>
        <Button variant="outline" :disabled="busy" @click="open=false">取消</Button>
        <Button variant="destructive" :disabled="busy" @click="remove">{{busy?'正在删除…':'确认删除'}}</Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>
