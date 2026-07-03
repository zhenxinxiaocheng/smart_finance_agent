import { ref, watchEffect } from 'vue'
import { useStorage } from '@vueuse/core'

export const THEME_COLORS = [
  { value: 'emerald', label: '翡翠绿', swatch: '#00a86b' },
  { value: 'blue', label: '蓝色', swatch: '#1d8cf8' },
  { value: 'violet', label: '紫色', swatch: '#6d5dfc' },
  { value: 'rose', label: '玫红', swatch: '#d9468f' },
  { value: 'orange', label: '橙色', swatch: '#d97706' },
  { value: 'slate', label: '石板灰', swatch: '#64748b' }
]

export const DENSITIES = [
  { value: 'compact', label: '紧凑' },
  { value: 'comfortable', label: '舒适' },
  { value: 'spacious', label: '宽松' }
]

export const CONTAINERS = [
  { value: 'fluid', label: '铺满' },
  { value: 'boxed', label: '居中' }
]

const DAY_START_HOUR = 7
const NIGHT_START_HOUR = 18

const mode = useStorage('sfa-mode', 'auto')
const themeColor = useStorage('sfa-theme-color', 'emerald')
const density = useStorage('sfa-density', 'comfortable')
const container = useStorage('sfa-container', 'fluid')
const currentHour = ref(new Date().getHours())

let installed = false
let clockTimer = null

function resolveMode(value) {
  if (value === 'auto') {
    return currentHour.value >= NIGHT_START_HOUR || currentHour.value < DAY_START_HOUR
      ? 'dark'
      : 'light'
  }

  return value === 'dark' ? 'dark' : 'light'
}

export function installAppearance() {
  if (installed || typeof document === 'undefined') return
  installed = true

  clockTimer = window.setInterval(() => {
    currentHour.value = new Date().getHours()
  }, 60000)

  watchEffect(() => {
    const root = document.documentElement
    root.classList.toggle('dark', resolveMode(mode.value) === 'dark')
    root.dataset.appearanceMode = mode.value || 'auto'
    root.dataset.themeColor = themeColor.value || 'emerald'
    root.dataset.density = density.value || 'comfortable'
    root.dataset.container = container.value || 'fluid'
  })
}

export function useAppearance() {
  function setMode(value) {
    mode.value = value
  }

  function setThemeColor(value) {
    themeColor.value = value
  }

  function setDensity(value) {
    density.value = value
  }

  function setContainer(value) {
    container.value = value
  }

  return {
    mode,
    themeColor,
    density,
    container,
    setMode,
    setThemeColor,
    setDensity,
    setContainer
  }
}
