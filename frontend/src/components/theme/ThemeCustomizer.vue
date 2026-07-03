<template>
  <Sheet>
    <SheetTrigger as-child>
      <Button variant="ghost" size="icon" aria-label="打开主题设置">
        <SlidersHorizontal />
      </Button>
    </SheetTrigger>
    <SheetContent class="w-[390px] overflow-y-auto bg-background p-0" side="right">
      <div class="border-b px-6 py-5">
        <SheetHeader>
          <SheetTitle>个性化设置</SheetTitle>
          <SheetDescription>调整主题、颜色、密度和内容宽度。</SheetDescription>
        </SheetHeader>
      </div>

      <div class="flex flex-col gap-6 px-6 py-5">
        <section class="flex flex-col gap-3">
          <h3 class="text-sm font-semibold">外观模式</h3>
          <div class="grid grid-cols-3 gap-2">
            <button
              v-for="item in modes"
              :key="item.value"
              type="button"
              :class="optionClass(mode === item.value)"
              @click="setMode(item.value)"
            >
              <component :is="item.icon" class="mx-auto" />
              <span>{{ item.label }}</span>
            </button>
          </div>
        </section>

        <Separator />

        <section class="flex flex-col gap-3">
          <h3 class="text-sm font-semibold">主题色</h3>
          <div class="grid grid-cols-3 gap-2">
            <button
              v-for="item in THEME_COLORS"
              :key="item.value"
              type="button"
              :class="optionClass(themeColor === item.value)"
              @click="setThemeColor(item.value)"
            >
              <span class="mx-auto size-6 rounded-full ring-2 ring-border" :style="{ background: item.swatch }"></span>
              <span>{{ item.label }}</span>
            </button>
          </div>
        </section>

        <Separator />

        <section class="flex flex-col gap-3">
          <h3 class="text-sm font-semibold">显示密度</h3>
          <div class="grid grid-cols-3 gap-2">
            <button
              v-for="item in densities"
              :key="item.value"
              type="button"
              :class="optionClass(density === item.value)"
              @click="setDensity(item.value)"
            >
              <component :is="item.icon" class="mx-auto" />
              <span>{{ item.label }}</span>
            </button>
          </div>
        </section>

        <Separator />

        <section class="flex flex-col gap-3">
          <h3 class="text-sm font-semibold">内容宽度</h3>
          <div class="grid grid-cols-2 gap-2">
            <button
              v-for="item in containers"
              :key="item.value"
              type="button"
              :class="optionClass(container === item.value)"
              @click="setContainer(item.value)"
            >
              <component :is="item.icon" class="mx-auto" />
              <span>{{ item.label }}</span>
            </button>
          </div>
        </section>
      </div>
    </SheetContent>
  </Sheet>
</template>

<script setup>
import {
  AlignJustify,
  Columns3,
  Maximize2,
  Minimize2,
  Monitor,
  Moon,
  Rows3,
  SlidersHorizontal,
  Sun
} from '@lucide/vue'
import { Button } from '@/components/ui/button'
import { Separator } from '@/components/ui/separator'
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
  SheetTrigger
} from '@/components/ui/sheet'
import {
  CONTAINERS,
  DENSITIES,
  THEME_COLORS,
  useAppearance
} from '@/composables/useAppearance'
import { cn } from '@/lib/utils'

const {
  mode,
  themeColor,
  density,
  container,
  setMode,
  setThemeColor,
  setDensity,
  setContainer
} = useAppearance()

const modes = [
  { value: 'light', label: '浅色', icon: Sun },
  { value: 'dark', label: '深色', icon: Moon },
  { value: 'auto', label: '日夜自动', icon: Monitor }
]

const densityIcons = {
  compact: AlignJustify,
  comfortable: Rows3,
  spacious: Columns3
}

const densities = DENSITIES.map(item => ({ ...item, icon: densityIcons[item.value] }))
const containers = [
  { value: 'fluid', label: '铺满', icon: Maximize2 },
  { value: 'boxed', label: '居中', icon: Minimize2 }
]

function optionClass(active) {
  return cn(
    'flex h-[70px] flex-col items-center justify-center gap-2 rounded-lg border bg-background text-xs font-medium text-muted-foreground transition-colors hover:border-primary/60 hover:text-foreground',
    active && 'border-primary bg-primary/10 text-primary ring-1 ring-primary'
  )
}
</script>
