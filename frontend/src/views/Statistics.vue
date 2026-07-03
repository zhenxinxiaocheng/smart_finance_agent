<template>
  <div class="flex flex-col gap-[var(--app-section-gap)]">
    <div class="flex flex-col gap-2">
      <h1 class="text-2xl font-semibold tracking-normal text-foreground">统计</h1>
      <p class="text-sm text-muted-foreground">按日、月、年查看收入支出结构和趋势。</p>
    </div>

    <Tabs :model-value="activeTab" class="flex flex-col gap-[var(--app-section-gap)]" @update:model-value="switchTab">
      <TabsList class="grid h-11 w-full grid-cols-3 md:w-[480px]">
        <TabsTrigger v-for="tab in tabs" :key="tab.value" :value="tab.value" class="gap-2">
          <component :is="tab.icon" data-icon="inline-start" />
          {{ tab.label }}
        </TabsTrigger>
      </TabsList>

      <transition name="slide-fade" mode="out-in">
        <TabsContent v-if="activeTab === 'daily'" :key="'daily-' + lastRefresh" value="daily" class="m-0 min-h-[600px]">
          <DailyStats />
        </TabsContent>
        <TabsContent v-else-if="activeTab === 'monthly'" :key="'monthly-' + lastRefresh" value="monthly" class="m-0 min-h-[600px]">
          <MonthlyStats />
        </TabsContent>
        <TabsContent v-else :key="'yearly-' + lastRefresh" value="yearly" class="m-0 min-h-[600px]">
          <YearlyStats />
        </TabsContent>
      </transition>
    </Tabs>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { BarChart3, CalendarDays, PieChart } from '@lucide/vue'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import DailyStats from './statistics/DailyStats.vue'
import MonthlyStats from './statistics/MonthlyStats.vue'
import YearlyStats from './statistics/YearlyStats.vue'

const tabs = [
  { label: '日常', value: 'daily', icon: CalendarDays },
  { label: '月统计', value: 'monthly', icon: BarChart3 },
  { label: '年统计', value: 'yearly', icon: PieChart }
]

const activeTab = ref('daily')
const lastRefresh = ref(Date.now())

function switchTab(value) {
  activeTab.value = value
  lastRefresh.value = Date.now()
}
</script>

<style scoped>
.slide-fade-enter-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}

.slide-fade-leave-active {
  transition: opacity 0.15s ease, transform 0.15s ease;
}

.slide-fade-enter-from {
  opacity: 0;
  transform: translateY(8px);
}

.slide-fade-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}
</style>
