<script setup>
import { RouterLink, RouterView } from 'vue-router'
const links = [['/quant', '策略'], ['/quant/universes', '资产池'], ['/quant/factors', '因子研究'], ['/quant/tasks', '任务中心'], ['/quant/deployments', '模拟交易']]
</script>

<template>
  <div class="qw quant-workspace w-full pb-8">
    <header class="mb-6 flex flex-wrap items-center justify-between gap-3">
      <h1 class="text-2xl font-semibold">量化工作台</h1>
      <span class="rounded-full bg-muted px-3 py-1 text-xs text-muted-foreground">模拟环境</span>
    </header>
    <nav class="mb-6 flex flex-wrap gap-1 border-b pb-2" aria-label="量化工作台导航">
      <RouterLink v-for="[path, title] in links" :key="path" :to="path" class="rounded-md px-4 py-2 text-sm" :class="($route.path === path || (path === '/quant' && ($route.path.startsWith('/quant/strategies/')||$route.path.startsWith('/quant/experiments/'))) || (path !== '/quant' && $route.path.startsWith(path + '/'))) ? 'bg-muted text-foreground font-medium' : 'text-muted-foreground hover:bg-muted/60'">{{ title }}</RouterLink>
    </nav>
    <RouterView />
  </div>
</template>

<style>
.quant-workspace { max-width:1600px; margin-inline:auto; min-width:0 }
.qw .quant-page-header { margin-bottom:20px }
.qw .quant-back { margin-bottom:8px }
.qw .quant-heading-row { display:flex; align-items:flex-start; justify-content:space-between; gap:16px; flex-wrap:wrap }
.qw .quant-page-header h2 { font-size:22px; line-height:1.35 }
.qw .quant-actions { display:flex; align-items:center; flex-wrap:wrap; gap:8px }
.qw .quant-config-layout { display:grid; grid-template-columns:minmax(0, 1fr) minmax(240px, 300px); gap:24px; align-items:start; min-width:0; width:100% }
.qw .quant-config-layout > * { min-width:0 }
.qw .quant-summary { position:sticky; top:24px }
.qw .quant-summary dl > div { display:flex; justify-content:space-between; gap:16px; padding-block:9px; border-bottom:1px solid var(--border) }
.qw .quant-summary dl > div:last-child { border-bottom:0 }
.qw .quant-summary dd { text-align:right; overflow-wrap:anywhere; font-weight:500; font-variant-numeric:tabular-nums }
.qw .quant-metrics { display:grid; grid-template-columns:repeat(auto-fit,minmax(145px,1fr)); gap:20px; padding:16px 20px; margin-bottom:0; border-block:1px solid var(--border); background:var(--card) }
.qw .quant-metrics dd { font-size:18px }
.qw .quant-metrics-primary { border:1px solid var(--border); border-radius:12px; padding-block:20px }
.qw .quant-metrics-primary dd { font-size:28px; letter-spacing:-.03em }
.qw .quant-empty { padding:32px 20px; text-align:center }
.qw .quant-list-footer { border-top:1px solid var(--border); padding-top:12px; margin-top:16px; font-size:12px; color:var(--muted-foreground) }
.qw .quant-tabs { display:flex; flex-wrap:wrap; gap:4px; border-bottom:1px solid var(--border); padding-bottom:10px; margin-bottom:20px }
.qw .quant-section { min-width:0; margin-bottom:20px }
.qw .quant-detail-meta { display:grid; grid-template-columns:repeat(auto-fit,minmax(180px,1fr)); gap:16px }
.qw .quant-detail-meta dd { margin-top:4px; overflow-wrap:anywhere }
.qw .quant-runtime-grid { grid-template-columns:repeat(3,minmax(0,1fr)); gap:12px 24px }
.qw .quant-runtime-grid > div { min-width:0 }
.qw .quant-status { min-height:22px; padding:2px 7px; font-size:12px; line-height:16px; font-weight:500; white-space:normal }
.qw .quant-validation { margin-bottom:16px; font-size:13px }
.qw .quant-validation p { margin-top:6px }
.qw .quant-research-summary { border-left:3px solid var(--border); padding:4px 0 4px 16px }
.qw .quant-research-section { scroll-margin-top:24px }
.qw .quant-research-section > .quant-secondary { margin-top:12px }
.qw .quant-research-list { list-style:disc; padding-left:20px; margin-top:12px; font-size:13px }
.qw .quant-research-list > li + li { margin-top:8px }
.qw .quant-type-tabs { display:flex; flex-wrap:wrap; gap:2px; background:var(--muted); border-radius:8px; padding:3px }
.qw .quant-type-tabs [aria-pressed=true] { background:var(--background); font-weight:600 }
.qw .quant-field-actions { display:flex; flex-wrap:wrap; gap:8px; margin-top:4px }
.qw .quant-field-stack { display:flex; flex-direction:column; gap:6px; min-width:0 }
.qw .quant-backtest-period { margin-bottom:16px }
.qw .quant-backtest-period .toolbar { margin-top:0; margin-bottom:10px }
.qw .quant-secondary { margin-block:16px; border:1px solid var(--border); border-radius:12px; padding:0 18px 12px }
.qw .quant-secondary:not([open]) { padding-bottom:0 }
.qw .quant-chart { width:100%; min-width:0 }
.qw .quant-chart--equity { height:410px }
.qw .quant-chart--drawdown { height:290px }
.qw .quant-chart--monthly { height:255px }
.qw .fields input,.qw .fields select { max-width:520px }
.qw [data-slot=table-cell] { padding-block:10px }
.qw [data-slot=table-cell] > .flex { flex-wrap:wrap; align-items:center }
@media (max-width:1100px) { .qw .quant-config-layout { grid-template-columns:minmax(0,1fr) } .qw .quant-summary { position:static } }
@media (max-width:640px) { .qw .quant-metrics { grid-template-columns:repeat(2,minmax(0,1fr)); padding:16px; gap:20px } .qw .quant-metrics-primary dd { font-size:22px } .qw .quant-chart--equity { height:340px } .qw .quant-chart--drawdown { height:260px } .qw .quant-chart--monthly { height:235px } .qw .quant-runtime-grid { grid-template-columns:minmax(0,1fr) } }
.qw .panel { background:var(--card); border:1px solid var(--border); border-radius:12px; padding:18px; margin-bottom:20px }
.qw .quant-chart-panel { padding:22px 24px 14px; margin-bottom:14px }
.qw .quant-chart-panel h3 { font-size:15px; font-weight:600; line-height:1.4; letter-spacing:-.01em }
.qw .quant-chart-panel--equity { border-color:color-mix(in oklab,var(--primary) 18%,var(--border)); padding-top:24px }
.qw .quant-chart-panel--equity h3 { font-size:16px }
.qw details.panel > summary { padding:0; min-height:22px }
.qw details.panel[open] > summary { margin-bottom:12px }
.qw .toolbar { display:flex; flex-wrap:wrap; align-items:center; gap:10px; margin-bottom:16px }
.qw select,.qw textarea { border:1px solid var(--input); border-radius:8px; background:transparent; color:var(--foreground); padding:5px 10px; min-width:0; width:100%; font-size:14px }
.qw select { color-scheme: light; height:32px }
.qw .toolbar > [data-slot=input] { height:32px }
.dark .qw select { color-scheme: dark }
.qw select option { background:var(--popover); color:var(--popover-foreground) }
.qw input[type=checkbox] { accent-color:var(--primary) }
.qw label.field { display:flex; flex-direction:column; gap:6px; font-size:13px; color:var(--foreground) }
.qw .fields { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:16px }
.qw .error { color:var(--destructive); font-size:13px; margin-block:12px }
.qw .muted { color:var(--muted-foreground); font-size:13px }
.qw h2 { font-size:18px; font-weight:600; margin-bottom:14px }
.qw h3 { font-size:15px; font-weight:600; margin-bottom:10px }
.qw .empty { padding:28px; text-align:center; color:var(--muted-foreground); font-size:14px }
.qw .table-wrap { overflow:auto; max-height:540px }
.qw .pill { background:var(--muted); border-radius:5px; padding:3px 7px; font-size:12px }
.qw a.link { color:var(--primary); text-decoration:none; text-underline-offset:3px }
.qw .metrics { display:grid; grid-template-columns:repeat(auto-fit,minmax(140px,1fr)); gap:10px; margin-bottom:16px }
.qw .metric { border:1px solid var(--border); border-radius:8px; padding:12px }
.qw .metric strong { display:block; margin-top:5px; font-size:18px }
.qw progress { width:100%; height:8px; accent-color:var(--primary) }
.qw pre { overflow:auto; font-size:12px; white-space:pre-wrap; overflow-wrap:anywhere }
.qw button:focus-visible,.qw input:focus-visible,.qw select:focus-visible,.qw a:focus-visible { outline:2px solid var(--ring); outline-offset:2px }
@media (max-width:640px) { .qw .fields { grid-template-columns:minmax(0,1fr) } .qw .panel { padding:16px } }
@media (max-width:640px) { .qw .quant-chart-panel { padding:18px 14px 10px } }
.qw details > summary { cursor:pointer; font-weight:500; padding:12px 0 }
.qw a.link:hover { text-decoration:underline }
</style>
