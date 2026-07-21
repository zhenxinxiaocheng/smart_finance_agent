import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const layoutSource = readFileSync(
  new URL('../src/layouts/MainLayoutShadcn.vue', import.meta.url),
  'utf8'
)

test('侧边栏菜单使用互不重复的语义图标', () => {
  const menuItems = [...layoutSource.matchAll(/label:\s*'([^']+)',\s*icon:\s*(\w+)/g)]
    .map(([, label, icon]) => ({ label, icon }))

  assert.ok(menuItems.length > 0, '应能读取侧边栏菜单配置')

  const duplicateIcons = menuItems
    .filter((item, index, items) => items.findIndex(candidate => candidate.icon === item.icon) !== index)
    .map(item => item.icon)

  assert.deepEqual(duplicateIcons, [])
})

test('折叠侧边栏仍提供菜单名称', () => {
  assert.match(layoutSource, /:aria-label="item\.label"/)
  assert.match(layoutSource, /:title="isCollapse \? item\.label : undefined"/)
})
