<template>
  <div class="mx-auto max-w-[1440px] space-y-5 pb-8">
    <template v-if="loading">
      <div class="detail-fade-in flex items-center justify-between gap-4">
        <div class="flex items-center gap-3"><Skeleton class="size-8" /><div class="space-y-2"><Skeleton class="h-6 w-44" /><Skeleton class="h-4 w-64" /></div></div>
        <Skeleton class="h-8 w-32" />
      </div>
      <div class="detail-fade-in detail-delay-1 grid grid-cols-2 gap-3 lg:grid-cols-4"><Skeleton v-for="n in 4" :key="n" class="h-24" /></div>
      <div class="detail-fade-in detail-delay-2 grid gap-4 xl:grid-cols-12"><Skeleton class="h-[560px] xl:col-span-9" /><Skeleton class="h-[560px] xl:col-span-3" /></div>
      <div class="detail-fade-in detail-delay-3 grid gap-4 md:grid-cols-3"><Skeleton v-for="n in 3" :key="n" class="h-40" /></div>
    </template>

    <div v-else-if="error" class="detail-fade-in grid min-h-[440px] place-items-center">
      <div class="max-w-md text-center">
        <div class="mx-auto mb-4 grid size-12 place-items-center rounded-full bg-destructive/10 text-destructive"><TriangleAlert /></div>
        <h1 class="text-lg font-semibold">详情暂时无法加载</h1>
        <p class="mt-2 text-sm text-muted-foreground">{{ error }}</p>
        <div class="mt-5 flex justify-center gap-2"><Button variant="outline" @click="returnToAssetList">返回列表</Button><Button @click="loadAll">重新加载</Button></div>
      </div>
    </div>

    <template v-else-if="detail?.asset">
      <header class="detail-fade-in flex flex-col gap-4 xl:flex-row xl:items-start xl:justify-between">
        <div class="flex min-w-0 items-start gap-3">
          <Button variant="ghost" size="icon" class="mt-0.5" title="返回资产列表" @click="returnToAssetList"><ArrowLeft /></Button>
          <div class="min-w-0">
            <div class="flex flex-wrap items-center gap-2">
              <h1 class="truncate text-2xl font-semibold tracking-tight">{{ asset.name }}</h1>
              <Badge variant="outline">{{ asset.productType === 'MUTUAL_FUND' ? '基金' : '股票' }}</Badge>
              <Badge v-if="asset.productType === 'MUTUAL_FUND'" variant="outline">{{ fundCategoryLabel(asset.fundCategory || sourceStatus.fundCategory) }}</Badge>
              <Badge variant="secondary">{{ sourceLabel }}</Badge>
            </div>
            <div class="mt-1.5 flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-muted-foreground">
              <span class="font-mono text-foreground">{{ asset.code }}</span><span>{{ asset.market }}</span>
              <span>{{ sourceStatus.adjustType === 'QFQ' ? '前复权' : '单位净值' }}</span>
              <span class="inline-flex items-center gap-1"><Clock3 class="size-3.5" />{{ dataTime }}</span>
            </div>
          </div>
        </div>
        <div class="flex flex-wrap items-center gap-2 pl-11 xl:pl-0">
          <Button variant="outline" @click="preferenceOpen = true"><SlidersHorizontal />分析周期</Button>
          <Button variant="outline" @click="editOpen = true"><Pencil />编辑持仓</Button>
          <Button variant="outline" @click="openQuantLab"><FlaskConical />量化模型管理</Button>
          <Button variant="outline" :disabled="refreshingData" @click="refreshData"><RefreshCw :class="refreshingData && 'animate-spin'" />重新拉取数据</Button>
        </div>
      </header>

      <Alert v-if="qualityBlocked" class="detail-fade-in detail-delay-1" variant="destructive">
        <ShieldAlert class="size-4" />
        <AlertTitle>数据完整性校验未通过</AlertTitle>
        <AlertDescription class="mt-2">
          当前不展示任何分析结论。系统正在自动重新获取并校验数据，通过后会生成全新的结果。
          <Button variant="outline" size="sm" class="mt-3" :disabled="refreshingData" @click="refreshData">立即重试</Button>
        </AlertDescription>
      </Alert>

      <Alert v-else-if="qualityWaiting" class="detail-fade-in detail-delay-1">
        <Clock3 class="size-4" />
        <AlertTitle>数据校验暂不可用</AlertTitle>
        <AlertDescription class="mt-2">
          当前不展示分析结论。系统会自动重试，校验恢复后再生成新的结果。
        </AlertDescription>
      </Alert>

      <Alert v-if="historyJobNotice" class="detail-fade-in detail-delay-1" :variant="historyJobStatus === 'FAILED' ? 'destructive' : 'default'">
        <Clock3 class="size-4" />
        <AlertTitle>
          <template v-if="historyJobStatus === 'QUEUED'">正在排队补齐历史数据</template>
          <template v-else-if="historyJobStatus === 'RUNNING'">正在补齐历史数据</template>
          <template v-else-if="historyJobStatus === 'RETRY_WAIT'">历史数据准备将自动重试</template>
          <template v-else-if="historyJobStatus === 'PARTIAL'">历史数据已部分补齐</template>
          <template v-else>历史数据准备未完成</template>
        </AlertTitle>
        <AlertDescription class="mt-2">
          {{ historyJobNotice }}
          <Button v-if="historyJobStatus === 'FAILED'" variant="outline" size="sm" class="mt-3" :disabled="refreshingData" @click="refreshData">重新拉取数据</Button>
        </AlertDescription>
      </Alert>

      <section class="detail-fade-in detail-delay-1 grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Card v-for="metric in topMetrics" :key="metric.label" class="gap-2 py-4 shadow-sm">
          <CardContent class="px-3 sm:px-4">
            <div class="flex items-center gap-1 text-xs text-muted-foreground">
              <span>{{ metric.label }}</span>
              <InfoTooltip v-if="metric.help" :content="metric.help" :label="`了解${metric.label}`" />
            </div>
            <div class="mt-1 truncate text-[15px] font-semibold tabular-nums sm:text-xl" :class="metric.tone">{{ metric.value }}</div>
            <div class="mt-1 truncate text-xs text-muted-foreground">{{ metric.hint }}</div>
          </CardContent>
        </Card>
      </section>

      <section class="detail-fade-in detail-delay-2 grid gap-4 xl:grid-cols-12">
        <Card class="gap-0 py-4 shadow-sm xl:col-span-9">
          <CardContent class="px-3 sm:px-5">
            <InvestmentKlineChart
              :series="detail.quoteSeries"
              :product-type="asset.productType"
              :levels="activeLevels"
              :history-warning="technical.status === 'INSUFFICIENT' ? technical.reason : ''"
              :indicator-config="technical.indicatorPeriods"
            />
          </CardContent>
        </Card>

        <Card class="gap-0 overflow-hidden py-0 shadow-sm xl:col-span-3">
          <div class="border-b bg-muted/30 px-4 py-3">
            <div class="flex items-center justify-between gap-2">
              <div class="flex flex-wrap items-center gap-1.5">
                <Button v-for="item in horizonOptions" :key="item.value" size="sm" class="h-7 px-2" :variant="activeHorizon === item.value ? 'secondary' : 'ghost'" @click="activeHorizon = item.value">{{ item.label }}</Button>
              </div>
              <InfoTooltip :content="helpText.analysisPeriod" label="了解分析周期" />
            </div>
          </div>
          <div class="space-y-5 p-5">
            <div v-if="!isFund">
              <div class="flex items-center justify-between gap-3">
                <span class="text-sm font-semibold">专业走势研判</span>
                <Badge :variant="activeDirection.variant">{{ confidenceLabel(activeOutlook.confidence) }}</Badge>
              </div>
              <p class="mt-3 text-2xl font-semibold" :class="activeDirection.tone">{{ outlookDirectionLabel(activeOutlook.direction) }}</p>
              <p class="mt-1 text-sm leading-6 text-muted-foreground">{{ activeDirection.action }} · {{ activePeriodText }}</p>
              <p v-if="activeAnalysis.status === 'INSUFFICIENT'" class="mt-3 text-sm leading-6 text-muted-foreground">{{ activeAnalysis.reason || '历史数据不足，暂时无法判断走势。' }}</p>
              <ul v-else class="mt-4 space-y-2 text-sm leading-6">
                <li v-for="reason in activeOutlook.reasons || []" :key="reason" class="flex gap-2"><span class="text-primary">•</span><span>{{ reason }}</span></li>
              </ul>
            </div>

            <div v-else>
              <div>
                <div class="flex items-center gap-2">
                  <span class="whitespace-nowrap text-sm font-semibold">基金表现研判</span>
                  <Badge v-if="fundAdviceExperimental" variant="outline" class="shrink-0">实验性建议</Badge>
                </div>
              </div>
              <p class="mt-3 text-2xl font-semibold" :class="verdictTone(activeVerdict)">{{ activeHeadline }}</p>
              <p class="mt-1 text-sm leading-6 text-muted-foreground">{{ fundAdviceUnavailable ? fundAdviceMessage : fundAdviceExperimental ? '根据当前周期的净值趋势、基准相对表现、波动与回撤状态生成。' : '根据净值趋势、波动和回撤状态生成。' }}</p>
            </div>

            <div v-if="!isFund && activeOutlook.risks?.length" class="rounded-lg border border-amber-500/30 bg-amber-500/5 p-3">
              <p class="text-xs font-medium text-amber-700 dark:text-amber-300">需要留意</p>
              <ul class="mt-1.5 space-y-1 text-xs leading-5 text-muted-foreground">
                <li v-for="risk in activeOutlook.risks" :key="risk">• {{ risk }}</li>
              </ul>
            </div>

            <div v-if="asset.productType === 'MUTUAL_FUND'" class="space-y-2.5">
              <ActionPriceRow
                v-if="hasMetric(activeFundPeriod.score)"
                label="周期评分"
                :value="`${decimal(activeFundPeriod.score)} / 100`"
              />
              <ActionPriceRow
                label="周期收益"
                :value="percent(activeFundPeriod.return)"
                :tone="tone(activeFundPeriod.return)"
              />
              <ActionPriceRow
                label="周期年化波动"
                :value="percent(activeFundPeriod.annualizedVolatility, false)"
              />
              <ActionPriceRow
                label="当前回撤"
                :value="percent(activeFundPeriod.currentDrawdown, false)"
                :tone="tone(activeFundPeriod.currentDrawdown)"
              />
              <ActionPriceRow
                label="周期最大回撤"
                :value="percent(activeFundPeriod.maxDrawdown, false)"
                tone="text-destructive"
              />
              <ActionPriceRow
                label="回撤状态"
                :value="drawdownStatusLabel(activeFundPeriod.drawdownStatus)"
              />
              <ActionPriceRow
                v-if="hasMetric(activeFundPeriod.periodAverageNav)"
                label="周期平均净值"
                :value="singlePrice(activeFundPeriod.periodAverageNav)"
              />
              <ActionPriceRow
                v-if="hasMetric(activeFundPeriod.latestToPeriodAverage)"
                label="净值相对周期均值"
                :value="percent(activeFundPeriod.latestToPeriodAverage)"
                :tone="tone(activeFundPeriod.latestToPeriodAverage)"
              />
              <ActionPriceRow
                v-if="activeFundPeriod.observationCount"
                label="观测净值点"
                :value="`${activeFundPeriod.observationCount} 个`"
              />
              <ActionPriceRow
                v-if="activeFundPeriod.startDate && activeFundPeriod.endDate"
                label="周期数据区间"
                :value="`${activeFundPeriod.startDate} → ${activeFundPeriod.endDate}`"
              />
              <details class="group rounded-lg border bg-muted/10 px-3 py-2.5">
                <summary class="flex cursor-pointer list-none items-center justify-between gap-2 text-sm font-medium">
                  <span class="flex items-center gap-2">
                    <span>专业指标</span>
                    <span v-if="!hasFundBenchmarkMetrics" class="text-xs font-normal text-muted-foreground">数据待准备</span>
                  </span>
                  <ChevronDown class="size-4 shrink-0 text-muted-foreground transition-transform group-open:rotate-180" />
                </summary>
                <div class="mt-3 space-y-2.5 border-t pt-3">
                  <div class="rounded-md border bg-background/60 px-3 py-2 text-xs">
                    <div class="flex items-center justify-between gap-3">
                      <span class="text-muted-foreground">对比基准</span>
                      <strong class="text-right">{{ fundBenchmarkLabel }}</strong>
                    </div>
                    <div v-if="fundBenchmarkSampleLabel" class="mt-1.5 flex items-center justify-between gap-3">
                      <span class="text-muted-foreground">指标样本</span>
                      <strong :class="fundBenchmarkSampleTone">{{ fundBenchmarkSampleLabel }}</strong>
                    </div>
                  </div>
                  <ActionPriceRow
                    label="基准收益"
                    :help="fundMetricHelp.benchmarkReturn"
                    :value="percent(activeFundPeriod.benchmarkReturn)"
                    :tone="tone(activeFundPeriod.benchmarkReturn)"
                  />
                  <ActionPriceRow
                    label="跟踪差"
                    :help="fundMetricHelp.trackingDifference"
                    :value="percent(activeFundPeriod.trackingDifference)"
                    :tone="tone(activeFundPeriod.trackingDifference)"
                  />
                  <ActionPriceRow
                    label="年化跟踪误差"
                    :help="fundMetricHelp.trackingError"
                    :value="percent(activeFundPeriod.trackingError, false)"
                  />
                  <ActionPriceRow
                    label="相关系数"
                    :help="fundMetricHelp.correlation"
                    :value="decimal(activeFundPeriod.correlation)"
                  />
                  <ActionPriceRow
                    label="Beta"
                    :help="fundMetricHelp.beta"
                    :value="decimal(activeFundPeriod.beta)"
                  />
                  <ActionPriceRow
                    label="回归 Alpha（年化）"
                    :help="fundMetricHelp.alpha"
                    :value="percent(activeFundPeriod.regressionAlpha)"
                    :tone="tone(activeFundPeriod.regressionAlpha)"
                  />
                  <ActionPriceRow
                    label="R²"
                    :help="fundMetricHelp.rSquared"
                    :value="decimal(activeFundPeriod.rSquared)"
                  />
                  <ActionPriceRow
                    label="信息比率 IR"
                    :help="fundMetricHelp.informationRatio"
                    :value="decimal(activeFundPeriod.informationRatio)"
                    :tone="tone(activeFundPeriod.informationRatio)"
                  />
                </div>
              </details>
            </div>
            <div v-else class="space-y-2.5">
              <div class="flex items-center gap-1 pb-0.5 text-xs text-muted-foreground"><span>关键价位参考</span><InfoTooltip :content="helpText.priceZones" label="了解关键价位" /></div>
              <ActionPriceRow label="买入观察区" :value="zoneText(priceZones.buy)" tone="text-emerald-600 dark:text-emerald-400" />
              <ActionPriceRow label="加仓观察区" :value="zoneText(priceZones.add)" />
              <ActionPriceRow label="持有区" :value="zoneText(priceZones.hold)" />
              <ActionPriceRow label="减仓观察区" :value="zoneText(priceZones.reduce)" tone="text-amber-600 dark:text-amber-400" />
              <ActionPriceRow label="跌破风险位" :value="riskPriceText(priceZones.risk)" tone="text-destructive" />
            </div>

            <div v-if="!isFund" class="rounded-lg border bg-muted/20 p-3">
              <div class="text-xs text-muted-foreground">走势失效条件</div>
              <div class="mt-1 text-sm font-semibold">{{ invalidationText(activeOutlook.invalidation, singlePrice) }}</div>
            </div>

            <details v-if="hasUsableQuantModel" class="rounded-lg border bg-muted/10 px-3 py-2.5">
              <summary class="cursor-pointer select-none text-sm font-medium">量化模型（独立结果）</summary>
              <div class="mt-3 grid grid-cols-2 gap-2 text-xs">
                <div class="rounded-md border bg-background p-2"><span class="text-muted-foreground">盈利概率</span><strong class="mt-1 block">{{ probabilityPercent(quant.profitProbability) }}</strong></div>
                <div class="rounded-md border bg-background p-2"><span class="text-muted-foreground">扣费后收益</span><strong class="mt-1 block" :class="tone(quant.expectedNetReturn)">{{ decimalPercent(quant.expectedNetReturn) }}</strong></div>
                <div class="rounded-md border bg-background p-2"><span class="text-muted-foreground">亏损概率</span><strong class="mt-1 block">{{ probabilityPercent(quant.lossProbability) }}</strong></div>
                <div class="rounded-md border bg-background p-2"><span class="text-muted-foreground">模型动作</span><strong class="mt-1 block">{{ quantActionLabel(quant.action) }}</strong></div>
              </div>
              <p v-if="quant.riskFlags?.length" class="mt-2 text-xs leading-5 text-amber-700 dark:text-amber-300">{{ quant.riskFlags.map(riskFlagLabel).join('；') }}</p>
            </details>
          </div>
        </Card>
      </section>

      <section v-if="asset.productType !== 'MUTUAL_FUND'" class="detail-fade-in detail-delay-3 space-y-3">
        <div class="flex items-center gap-2">
          <h2 class="font-semibold">多周期分析</h2>
          <InfoTooltip :content="helpText.multiHorizon" label="了解多周期分析" />
          <Badge v-if="cycleDifference" variant="outline" class="gap-1">周期分歧<InfoTooltip :content="helpText.cycleDifference" label="了解周期分歧" /></Badge>
        </div>
        <div class="grid gap-3 md:grid-cols-3">
          <Card v-for="item in horizonCards" :key="item.key" class="gap-3 py-4 shadow-sm">
            <CardHeader class="px-4">
              <div class="flex items-center justify-between gap-2"><CardTitle class="text-base">{{ item.label }}</CardTitle><Badge :variant="directionMeta(item.data.outlook?.direction).variant">{{ outlookDirectionLabel(item.data.outlook?.direction) }}</Badge></div>
              <CardDescription>{{ horizonRange(item.data) }}</CardDescription>
            </CardHeader>
            <CardContent class="px-4">
              <p v-if="item.data.status === 'INSUFFICIENT'" class="text-sm leading-6 text-muted-foreground">{{ item.data.reason || '历史数据不足，暂不判断走势。' }}</p>
              <template v-else>
                <div class="flex items-end justify-between gap-3"><span class="text-xs text-muted-foreground">判断可信度</span><strong class="text-sm">{{ confidenceLabel(item.data.outlook?.confidence) }}</strong></div>
                <p class="mt-3 text-sm leading-6 text-muted-foreground">{{ item.data.outlook?.reasons?.[0] || '当前指标尚未形成明确证据。' }}</p>
              </template>
            </CardContent>
          </Card>
        </div>
      </section>

      <section class="detail-fade-in detail-delay-4 grid gap-4 xl:grid-cols-12">
        <Card class="shadow-sm xl:col-span-12">
          <CardHeader>
            <div class="flex items-start justify-between gap-3">
              <div>
                <div class="flex items-center gap-1"><CardTitle>{{ asset.productType === 'MUTUAL_FUND' ? '基金表现' : '长期基本面与估值' }}</CardTitle><InfoTooltip v-if="asset.productType !== 'MUTUAL_FUND'" :content="helpText.fundamental" label="了解长期基本面与估值" /></div>
                <CardDescription>{{ asset.productType === 'MUTUAL_FUND' ? '近期表现与风险概览' : '长期价值判断不被短期技术信号修改' }}</CardDescription>
              </div>
              <Badge :variant="fundamental.status === 'READY' ? 'secondary' : 'outline'">{{ fundamentalVerdict }}</Badge>
            </div>
          </CardHeader>
          <CardContent>
            <div v-if="asset.productType === 'MUTUAL_FUND'" class="grid grid-cols-2 gap-3 sm:grid-cols-3">
              <MetricMini
                v-for="metric in fundFullHistoryMetrics"
                :key="metric.label"
                :label="metric.label"
                :value="metric.value"
                :tone="metric.tone"
              />
            </div>
            <div v-else-if="fundamental.status === 'READY'" class="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
              <div v-for="dimension in fundamentalDimensions" :key="dimension.key" class="rounded-lg border bg-muted/20 p-3">
                <div class="text-xs text-muted-foreground">{{ dimension.label }}</div><div class="mt-1.5 text-lg font-semibold">{{ formatScore(dimension.data.score) }}</div>
                <div class="mt-2 h-1 overflow-hidden rounded-full bg-muted"><div class="h-full rounded-full bg-primary" :style="{ width: `${Number(dimension.data.score || 0)}%` }" /></div>
              </div>
            </div>
            <div v-else class="grid min-h-32 place-items-center rounded-lg border border-dashed bg-muted/20 px-6 text-center text-sm text-muted-foreground">
              {{ fundamental.reason || '当前策略的数据覆盖不足，暂不形成基本面结论。' }}
            </div>
          </CardContent>
        </Card>

      </section>

      <section class="detail-fade-in detail-delay-5 grid gap-4 xl:grid-cols-12">
        <Card class="shadow-sm xl:col-span-5">
          <CardHeader><CardTitle class="flex items-center gap-2"><ShieldAlert class="size-4" />财务警告</CardTitle><CardDescription>根据账户与市场风险规则实时生成</CardDescription></CardHeader>
          <CardContent class="space-y-2.5">
            <Alert v-for="warning in detail.financialWarnings || []" :key="warning.code" :variant="warning.severity === 'ERROR' ? 'destructive' : 'default'" class="py-2.5">
              <TriangleAlert v-if="warning.severity === 'WARNING'" class="text-amber-600 dark:text-amber-400" /><Info v-else />
              <AlertTitle>{{ warningTitle(warning.code) }}</AlertTitle>
              <AlertDescription>{{ warning.message }}</AlertDescription>
            </Alert>
            <div v-if="!(detail.financialWarnings || []).length" class="rounded-lg border border-dashed bg-muted/20 p-4 text-sm text-muted-foreground">
              当前没有触发有证据支持的财务或风控警告。
            </div>
            <p class="rounded-lg bg-muted/30 px-3 py-2 text-xs leading-5 text-muted-foreground">
              {{ disclaimer.text || '结果仅用于辅助分析，不连接券商，也不会自动交易。' }}
            </p>
          </CardContent>
        </Card>

        <details class="group overflow-hidden rounded-xl border bg-card shadow-sm xl:col-span-7">
          <summary class="flex cursor-pointer list-none items-center justify-between gap-3 px-5 py-4 marker:hidden hover:bg-muted/30">
            <div><p class="font-semibold">查看详细分析</p><p class="mt-1 text-sm text-muted-foreground">历史回测与 AI 解读</p></div>
            <ChevronDown class="size-4 text-muted-foreground transition-transform group-open:rotate-180" />
          </summary>
          <div class="grid gap-4 border-t p-4 md:grid-cols-[minmax(0,0.82fr)_minmax(0,1.18fr)]">
            <div class="rounded-lg border bg-muted/10 p-4">
              <div class="flex items-center gap-1"><h3 class="font-semibold">历史回测</h3><InfoTooltip :content="helpText.backtest" label="了解历史回测" /></div>
              <p class="mt-1 text-xs leading-5 text-muted-foreground">只用当时可见数据，不使用未来信息生成信号</p>
              <div class="mt-4 space-y-3">
                <MetricLine label="同方向历史出现" :value="`${activeBacktest.occurrences || 0} 次`" />
                <MetricLine label="之后上涨占比" :value="percent(activeBacktest.positiveRate, false)" />
                <MetricLine label="之后下跌占比" :value="percent(activeBacktest.negativeRate, false)" />
                <MetricLine label="中位前瞻收益" :value="percent(activeBacktest.medianForwardReturn)" :tone="tone(activeBacktest.medianForwardReturn)" />
                <MetricLine label="最大不利波动" :value="percent(activeBacktest.maximumAdverseExcursion, false)" tone="text-destructive" />
                <MetricLine label="走势失效占比" :value="percent(activeBacktest.invalidationRate, false)" />
              </div>
            </div>
            <div class="rounded-lg border bg-muted/10 p-4">
              <h3 class="flex items-center gap-2 font-semibold"><Sparkles class="size-4 text-primary" />AI 解读</h3>
              <p class="mt-1 text-xs leading-5 text-muted-foreground">只解释已保存的计算结果，不会改写交易结论</p>
              <div class="mt-4 rounded-lg border bg-background p-4">
                <p class="text-base font-semibold leading-7">{{ ai.summary || '正在生成简短解读…' }}</p>
                <div v-if="ai.reasons.length" class="mt-4">
                  <p class="text-xs font-medium text-muted-foreground">为什么</p>
                  <ul class="mt-2 space-y-1.5 text-sm leading-6">
                    <li v-for="reason in ai.reasons" :key="reason">• {{ reason }}</li>
                  </ul>
                </div>
                <div v-if="ai.risks.length" class="mt-4">
                  <p class="text-xs font-medium text-muted-foreground">需要留意</p>
                  <ul class="mt-2 space-y-1.5 text-sm leading-6 text-amber-700 dark:text-amber-300">
                    <li v-for="risk in ai.risks" :key="risk">• {{ risk }}</li>
                  </ul>
                </div>
                <details v-if="ai.technicalDetails" class="mt-4 rounded-md border bg-muted/20">
                  <summary class="cursor-pointer px-3 py-2 text-xs font-medium">查看技术详情</summary>
                  <p class="border-t px-3 py-3 text-xs leading-6 text-muted-foreground whitespace-pre-line">{{ ai.technicalDetails }}</p>
                </details>
              </div>
              <div class="mt-3 flex flex-wrap items-center justify-between gap-2 text-xs text-muted-foreground">
                <span>{{ ai.status === 'READY' ? '已保存解读' : '后台生成中' }}</span>
                <span>最短刷新 {{ ai.cooldownMinutes }} 分钟</span>
              </div>
            </div>
          </div>
        </details>
      </section>

      <InvestmentAssetDrawer v-model:open="editOpen" :asset="asset" @saved="loadAll" />

      <HorizonProfileDialog
        v-model:open="preferenceOpen"
        :profile="horizonProfile"
        :saving="savingPreference"
        scope="asset"
        @save="savePreference"
        @clear="clearPreference"
      />

    </template>
  </div>
</template>

<script setup>
import { computed, defineComponent, h, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowLeft, ChevronDown, Clock3, Info, Pencil, RefreshCw, ShieldAlert,
  FlaskConical, SlidersHorizontal, Sparkles, TriangleAlert
} from '@lucide/vue'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { InfoTooltip } from '@/components/ui/tooltip'
import InvestmentAssetDrawer from '@/components/investment/InvestmentAssetDrawer.vue'
import HorizonProfileDialog from '@/components/investment/HorizonProfileDialog.vue'
import InvestmentKlineChart from '@/components/investment/InvestmentKlineChart.vue'
import { normalizeAiExplanation } from '@/lib/investmentExplanation'
import { investmentHelpText as helpText } from '@/lib/investmentHelpText'
import { createHistoryJobPollingController } from '@/lib/investmentHistoryJob'
import { clearInvestmentDetailPath } from '@/lib/investmentNavigation'
import { isUsableQuantAnalysis } from '@/lib/quantDisplay'
import {
  confidenceLabel,
  directionLabel as outlookDirectionLabel,
  directionMeta,
  invalidationText,
} from '@/lib/technicalOutlook'
import { feedback } from '@/lib/feedback'
import {
  clearInvestmentAssetHorizonOverrideAPI,
  getInvestmentAssetDetailAPI,
  getInvestmentHistoryJobAPI,
  getQuantActionPlanAPI,
  getInvestmentQuantAnalysisAPI,
  refreshInvestmentAssetDataQualityAPI,
  updateInvestmentAssetHorizonOverrideAPI,
} from '@/api/investment'

const route = useRoute()
const router = useRouter()
const loading = ref(true)
const refreshingData = ref(false)
const savingPreference = ref(false)
const error = ref('')
const detail = ref(null)
const quant = ref({ status: 'UNAVAILABLE', action: 'PAUSE', riskFlags: ['MODEL_UNAVAILABLE'] })
const activeHorizon = ref('')
const editOpen = ref(false)
const preferenceOpen = ref(false)

const asset = computed(() => detail.value?.asset || {})
const technical = computed(() => detail.value?.technicalAnalysis || {})
const fundamental = computed(() => detail.value?.fundamentalAnalysis || {})
const backtest = computed(() => detail.value?.backtestSummary || {})
const ai = computed(() => normalizeAiExplanation(detail.value?.aiExplanation))
const disclaimer = computed(() => detail.value?.disclaimer || {})
const sourceStatus = computed(() => detail.value?.sourceStatus || {})
const historyJob = computed(() => sourceStatus.value.historyJob || {})
const historyJobStatus = computed(() => historyJob.value.status)
const historyJobNotice = computed(() => ({
  QUEUED: '最新价格已可使用，历史行情和分析会在后台继续准备。',
  RUNNING: '历史行情和分析正在后台准备，完成后页面会自动更新。',
  RETRY_WAIT: '服务正在稍后重试，当前已保存的数据仍可继续查看。',
  PARTIAL: '部分历史数据暂不可用，页面已更新可用结果。',
  FAILED: '本次准备未完成，可点击“重新拉取数据”再次尝试。',
}[historyJobStatus.value] || ''))
const qualityBlocked = computed(() => sourceStatus.value.dataState === 'BLOCKED')
const qualityWaiting = computed(() => sourceStatus.value.dataState === 'WAITING')
const activeFundPeriod = computed(() => isFund.value ? activeAnalysis.value : null)
const hasFundBenchmarkMetrics = computed(() => [
  'benchmarkReturn',
  'trackingDifference',
  'trackingError',
  'correlation',
  'beta',
  'regressionAlpha',
  'rSquared',
  'informationRatio',
].some(key => hasMetric(activeFundPeriod.value?.[key])))
const fundBenchmark = computed(() => technical.value.benchmark || {})
const fundBenchmarkLabel = computed(() => fundBenchmark.value.name || fundBenchmark.value.code || '尚未准备')
const fundBenchmarkSampleLabel = computed(() => {
  const count = activeFundPeriod.value?.benchmarkMetricObservationCount
  const recommended = activeFundPeriod.value?.benchmarkMetricRecommendedObservationCount
  if (count == null) return ''
  if (activeFundPeriod.value?.benchmarkMetricStatus === 'ADEQUATE_SAMPLE') return `${count} 个收益率样本，样本充足`
  if (activeFundPeriod.value?.benchmarkMetricStatus === 'LOW_SAMPLE') return `${count} / ${recommended}，样本偏少`
  return `${count} / ${recommended}，暂不足以计算`
})
const fundBenchmarkSampleTone = computed(() => activeFundPeriod.value?.benchmarkMetricStatus === 'ADEQUATE_SAMPLE'
  ? 'text-emerald-600 dark:text-emerald-400'
  : 'text-amber-600 dark:text-amber-400')
const fundMetricHelp = {
  benchmarkReturn: '同一周期内，基金所跟踪基准的涨跌幅，用来判断市场本身的表现。',
  trackingDifference: '基金周期收益减去基准周期收益。正数表示跑赢基准，负数表示跑输基准。',
  trackingError: '衡量基金与基准收益差异的波动程度。数值越低，通常说明跟踪越稳定。',
  correlation: '取值范围为 -1 到 1。越接近 1，基金与基准的涨跌方向越一致。',
  beta: '衡量基金对基准波动的敏感度。接近 1 表示波动相近，大于 1 通常波动更大。',
  alpha: '扣除基准波动影响后估算的年化超额收益，不代表未来一定能够获得。',
  rSquared: '取值范围为 0 到 1。越接近 1，说明基金波动越能由当前基准解释。',
  informationRatio: '单位跟踪风险带来的超额收益。通常越高越好，但会受样本区间影响。',
}
const fundFullHistory = computed(() => technical.value.fullHistory || {})
const fundFullHistoryMetrics = computed(() => [
  { label: '历史区间收益', value: percent(fundFullHistory.value.windowReturn) },
  { label: '历史区间年化波动', value: percent(fundFullHistory.value.annualizedVolatility, false) },
  { label: '历史最大回撤', value: percent(fundFullHistory.value.maxDrawdown, false), tone: 'text-destructive' },
  { label: '当前回撤', value: percent(fundFullHistory.value.currentDrawdown, false), tone: Number(fundFullHistory.value.currentDrawdown || 0) < 0 ? 'text-destructive' : '' },
  { label: '回撤状态', value: drawdownStatusLabel(fundFullHistory.value.drawdownStatus) },
  { label: '数据区间', value: fundFullHistory.value.startDate && fundFullHistory.value.endDate
    ? `${fundFullHistory.value.startDate} → ${fundFullHistory.value.endDate}`
    : '-' },
])
const horizonProfile = computed(() => detail.value?.analysisPreference || { settings: [] })
const horizonOptions = computed(() => (horizonProfile.value.settings || []).map(item => ({
  value: item.code,
  label: item.displayName,
  primary: Boolean(item.primary),
})))
const isFund = computed(() => asset.value.productType === 'MUTUAL_FUND')
const fundAdviceUnavailable = computed(() => isFund.value && activeAnalysis.value.adviceStatus === 'UNAVAILABLE')
const fundAdviceExperimental = computed(() => isFund.value && activeAnalysis.value.adviceStatus === 'EXPERIMENTAL')
const fundAdviceMessage = computed(() => ({
  FUND_CATEGORY_UNAVAILABLE: '基金分类尚未完成，当前只展示历史统计，不提供操作建议。',
  FUND_STRATEGY_UNAVAILABLE: '该基金类型的专属策略尚未接入，当前只展示历史统计，不套用指数基金规则。',
  BENCHMARK_UNAVAILABLE: '该指数基金的基准行情尚未准备完成，当前不提供操作建议。',
  BENCHMARK_INCOMPLETE: '该指数基金的基准配置不完整，当前不提供操作建议。',
  BENCHMARK_ALIGNMENT_INSUFFICIENT: '该周期内基金与基准的同日收益样本不足，当前不提供操作建议。',
}[activeAnalysis.value.reasonCode] || '该基金分类的专属策略尚未通过验证，当前只展示历史统计，不提供操作建议。'))
const activeAnalysis = computed(() => technical.value.horizons?.[activeHorizon.value] || technical.value)
const activeOutlook = computed(() => activeAnalysis.value.outlook || technical.value.outlook || {})
const activeDirection = computed(() => directionMeta(activeOutlook.value.direction))
const activeBacktest = computed(() => backtest.value.horizons?.[activeHorizon.value] || backtest.value)
const activeLevels = computed(() => activeAnalysis.value.levels || technical.value.levels || {})
const hasUsableQuantModel = computed(() => isUsableQuantAnalysis(quant.value))
const priceZones = computed(() => isFund.value
  ? technical.value.actionZones || {}
  : activeAnalysis.value.actionZones || technical.value.actionZones || {})
const activeVerdict = computed(() => isFund.value
  ? activeAnalysis.value?.verdict || actionVerdict(activeAnalysis.value?.action)
  : activeAnalysis.value.verdict || technical.value.verdict || actionVerdict(technical.value.action))
const activeHeadline = computed(() => fundAdviceUnavailable.value
  ? '暂无操作建议'
  : activeAnalysis.value.status === 'INSUFFICIENT'
  ? '数据不足'
  : activeAnalysis.value.status === 'BLOCKED'
    ? '数据已阻断'
    : activeAnalysis.value.status === 'UNAVAILABLE'
      ? '等待重新校验'
  : isFund.value ? fundActionLabel(activeAnalysis.value?.action) : verdictLabel(activeVerdict.value))
const activePeriodText = computed(() => horizonRange(activeAnalysis.value))
const cycleDifference = computed(() => new Set(Object.values(technical.value.horizons || {}).map(item => item.outlook?.direction).filter(Boolean)).size > 1)
const horizonCards = computed(() => horizonOptions.value.map(item => ({ key: item.value, label: item.label, data: technical.value.horizons?.[item.value] || {} })))
const fundamentalDimensions = computed(() => {
  const labels = { growth: '成长', profitability: '盈利', cashQuality: '现金质量', resilience: '财务韧性', valuation: '估值' }
  return Object.entries(fundamental.value.dimensions || {}).map(([key, data]) => ({ key, label: labels[key] || key, data }))
})
const fundamentalVerdict = computed(() => {
  if (asset.value.productType === 'MUTUAL_FUND') return '历史统计'
  return ({ ATTRACTIVE: '长期较有吸引力', FAIR: '长期中性', CAUTIOUS: '长期需谨慎', INSUFFICIENT: '数据不足' }[fundamental.value.verdict] || '数据不足')
})
const sourceLabel = computed(() => ({
  READY: '数据正常',
  STABLE_CACHE: '分析服务重试中',
  BLOCKED: '数据已阻断',
  WAITING: '等待数据校验',
  PREPARING: '数据准备中',
}[sourceStatus.value.dataState] || '数据准备中'))
const dataTime = computed(() => sourceStatus.value.quoteDate || asset.value.dataDate || '暂无日期')
const topMetrics = computed(() => [
  { label: asset.value.productType === 'MUTUAL_FUND' ? '最新净值' : '当前价格', value: originalMoney(asset.value.latestPrice, asset.value.currency), hint: `${signedPercent(asset.value.changePercent)} 今日涨跌`, tone: tone(asset.value.changePercent) },
  { label: '持仓市值', value: money(asset.value.marketValueCny), hint: asset.value.quantity == null ? '尚未填写持仓' : `${decimal(asset.value.quantity)} ${asset.value.productType === 'MUTUAL_FUND' ? '份' : '股'}` },
  { label: '持仓盈亏', value: signedMoney(asset.value.unrealizedPnlCny), hint: `${signedPercent(asset.value.holdingReturnPercent)} 持仓收益`, tone: tone(asset.value.unrealizedPnlCny) },
  isFund.value
    ? { label: '基金研判', value: activeHeadline.value, hint: activePeriodText.value, tone: verdictTone(activeVerdict.value) }
    : { label: '走势研判', value: outlookDirectionLabel(activeOutlook.value.direction), hint: `${confidenceLabel(activeOutlook.value.confidence)} · ${activePeriodText.value}`, tone: activeDirection.value.tone, help: helpText.technicalOutlook },
])

const ActionPriceRow = defineComponent({ props: { label: String, value: String, tone: String, help: String }, setup: props => () => h('div', { class: 'flex items-center justify-between gap-3 border-b border-border/60 pb-2 text-sm last:border-0 last:pb-0' }, [h('span', { class: 'flex min-w-0 items-center gap-1 text-muted-foreground' }, [h('span', props.label), props.help ? h(InfoTooltip, { content: props.help, label: `了解${props.label}` }) : null]), h('strong', { class: ['tabular-nums text-right', props.tone] }, props.value || '-')]) })
const MetricMini = defineComponent({ props: { label: String, value: String, tone: String }, setup: props => () => h('div', { class: 'rounded-lg border bg-muted/20 p-3' }, [h('div', { class: 'text-xs text-muted-foreground' }, props.label), h('div', { class: ['mt-1.5 font-semibold tabular-nums', props.tone] }, props.value)]) })
const MetricLine = defineComponent({ props: { label: String, value: String, tone: String }, setup: props => () => h('div', { class: 'flex items-center justify-between border-b border-border/60 pb-2 text-sm last:border-0' }, [h('span', { class: 'text-muted-foreground' }, props.label), h('strong', { class: ['tabular-nums', props.tone] }, props.value)]) })

let componentDisposed = false
let detailRequestToken = 0
const isCurrentAsset = assetId => !componentDisposed && String(route.params.assetId) === String(assetId)
const historyJobPolling = createHistoryJobPollingController({
  poll: async ({ assetId }) => {
    const response = await getInvestmentHistoryJobAPI(assetId)
    return response.data || {}
  },
  onJob: (nextHistoryJob, { assetId }) => {
    if (!isCurrentAsset(assetId)) return
    detail.value = {
      ...detail.value,
      sourceStatus: { ...sourceStatus.value, historyJob: nextHistoryJob }
    }
  },
  onTerminal: (_job, { assetId }) => {
    if (isCurrentAsset(assetId)) void loadAll(assetId)
  }
})

watch(() => route.params.assetId, async () => {
  const assetId = route.params.assetId
  historyJobPolling.update(assetId, undefined)
  await loadAll(assetId)
}, { immediate: true })
watch(horizonOptions, options => {
  if (options.some(item => item.value === activeHorizon.value)) return
  activeHorizon.value = options.find(item => item.primary)?.value || options[0]?.value || ''
}, { immediate: true })
watch(activeHorizon, value => { if (value) loadQuantAnalysis() })

onBeforeUnmount(() => {
  componentDisposed = true
  detailRequestToken += 1
  historyJobPolling.dispose()
})

async function loadAll(assetId = route.params.assetId) {
  const requestToken = ++detailRequestToken
  loading.value = true
  error.value = ''
  try {
    const response = await getInvestmentAssetDetailAPI(assetId)
    if (!isCurrentAsset(assetId) || requestToken !== detailRequestToken) return
    detail.value = response.data
  } catch {
    if (!isCurrentAsset(assetId) || requestToken !== detailRequestToken) return
    error.value = '系统正在恢复数据服务，请稍后重新加载'
  } finally {
    if (!isCurrentAsset(assetId) || requestToken !== detailRequestToken) return
    loading.value = false
    syncHistoryJobPolling()
  }
}

async function refreshData() {
  if (refreshingData.value) return
  const assetId = route.params.assetId
  const previousHistoryJobStatus = historyJobStatus.value
  refreshingData.value = true
  historyJobPolling.restart(assetId, undefined)
  try {
    const response = await refreshInvestmentAssetDataQualityAPI(assetId)
    if (!isCurrentAsset(assetId)) return
    detail.value = response.data
    historyJobPolling.update(assetId, response.data?.sourceStatus?.historyJob?.status)
    feedback.success('已开始后台准备历史数据')
  } catch {
    if (isCurrentAsset(assetId)) historyJobPolling.update(assetId, previousHistoryJobStatus)
  } finally {
    if (!componentDisposed) refreshingData.value = false
  }
}

function syncHistoryJobPolling() {
  if (!componentDisposed) historyJobPolling.update(route.params.assetId, historyJobStatus.value)
}

async function loadQuantAnalysis() {
  if (!route.params.assetId || !activeHorizon.value) return
  try {
    const [analysisResponse, actionPlanResponse] = await Promise.all([
      getInvestmentQuantAnalysisAPI(route.params.assetId, activeHorizon.value),
      getQuantActionPlanAPI(route.params.assetId, activeHorizon.value),
    ])
    quant.value = {
      ...(analysisResponse.data || {}),
      ...(actionPlanResponse.data || {}),
    }
  } catch {
    quant.value = { status: 'UNAVAILABLE', action: 'PAUSE', riskFlags: ['RESULT_UNAVAILABLE'], userMessage: '暂无有效量化模型' }
  }
}

function openQuantLab() {
  router.push({
    path: '/quant-lab',
    query: { assetId: route.params.assetId, horizonCode: activeHorizon.value }
  })
}

function returnToAssetList() {
  clearInvestmentDetailPath()
  router.push('/stocks')
}

async function savePreference(payload) {
  if (savingPreference.value) return
  savingPreference.value = true
  try {
    const response = await updateInvestmentAssetHorizonOverrideAPI(route.params.assetId, payload)
    detail.value = response.data
    quant.value = { status: 'UNAVAILABLE', action: 'PAUSE', riskFlags: ['MODEL_REFRESH_REQUIRED'], userMessage: '周期已更新，系统将自动重新训练该周期的量化模型。' }
    preferenceOpen.value = false
    feedback.success('该资产的周期设置已保存')
  } finally { savingPreference.value = false }
}

async function clearPreference() {
  if (savingPreference.value) return
  savingPreference.value = true
  try {
    const response = await clearInvestmentAssetHorizonOverrideAPI(route.params.assetId)
    detail.value = response.data
    quant.value = { status: 'UNAVAILABLE', action: 'PAUSE', riskFlags: ['MODEL_REFRESH_REQUIRED'], userMessage: '周期已恢复为全局设置，系统将自动重新训练该周期的量化模型。' }
    preferenceOpen.value = false
    feedback.success('已恢复全局周期设置')
  } finally { savingPreference.value = false }
}

function verdictLabel(value) { return ({ FAVORABLE: '值得关注', WAIT: '中性观察', WEAK: '技术偏弱' }[value] || '等待数据') }
function verdictTone(value) { return value === 'WEAK' ? 'text-destructive' : value === 'FAVORABLE' ? 'text-emerald-600 dark:text-emerald-400' : 'text-amber-600 dark:text-amber-400' }
function actionVerdict(value) { return ({ BUY: 'FAVORABLE', ACCUMULATE: 'FAVORABLE', HOLD: 'WAIT', REDUCE: 'WEAK', PAUSE: 'WEAK', TAKE_PROFIT: 'WAIT', WAIT: 'WAIT' }[value] || 'WAIT') }
function fundActionLabel(value) {
  const hasPosition = Number(asset.value.quantity || 0) > 0
  return ({
    BUY: hasPosition ? '加仓' : '买入',
    ACCUMULATE: hasPosition ? '加仓' : '买入',
    HOLD: hasPosition ? '继续持有' : '继续观望',
    REDUCE: hasPosition ? '减仓' : '继续观望',
    PAUSE: hasPosition ? '减仓' : '继续观望',
    TAKE_PROFIT: hasPosition ? '分批减仓' : '继续观望',
    WAIT: '暂无操作建议',
  }[value] || '等待数据')
}
function fundCategoryLabel(value) {
  return ({
    INDEX_FUND: '境内指数基金',
    QDII_INDEX_FUND: '海外指数基金',
    OTHER_INDEX_FUND: '其他指数基金',
    ACTIVE_EQUITY_FUND: '主动股票基金',
    HYBRID_FUND: '混合基金',
    BOND_FUND: '债券基金',
    MONEY_MARKET_FUND: '货币基金',
    QDII_FUND: 'QDII 基金',
    COMMODITY_FUND: '商品基金',
    ACTIVE_FUND: '主动基金',
  }[value] || '类型待确认')
}
function horizonRange(value) {
  if (value?.targetDays != null) {
    return `最近 ${value.targetDays} 个交易日（设置范围 ${value.minimumDays}–${value.maximumDays}）`
  }
  return value?.minimumDays != null ? `${value.minimumDays}–${value.maximumDays} 个交易日` : '等待足够历史数据'
}
function missingZoneReason() {
  if (asset.value.latestPrice == null) return '缺少最新价格'
  if (activeAnalysis.value.status === 'INSUFFICIENT') return '历史数据不足'
  return '当前未形成有效区间'
}
function zoneText(zone) { if (!zone) return missingZoneReason(); const low = zone.low ?? zone.price; const high = zone.high ?? zone.price; return low == null ? missingZoneReason() : low === high ? singlePrice(low) : `${singlePrice(low)} – ${singlePrice(high)}` }
function riskPriceText(zone) { return zone?.price == null ? missingZoneReason() : singlePrice(zone.price) }
function singlePrice(value) { return value == null ? '暂无数据' : new Intl.NumberFormat('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 4 }).format(Number(value)) }
function formatScore(value) { return value == null ? '-' : Number(value).toFixed(1) }
function money(value) { return value == null ? '-' : new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY', maximumFractionDigits: 2 }).format(Number(value)) }
function originalMoney(value, currency = 'CNY') { if (value == null) return '-'; try { return new Intl.NumberFormat('zh-CN', { style: 'currency', currency, maximumFractionDigits: 4 }).format(Number(value)) } catch { return `${value} ${currency}` } }
function signedMoney(value) { if (value == null) return '-'; const n = Number(value); return `${n > 0 ? '+' : ''}${money(n)}` }
function signedPercent(value) { return value == null ? '-' : `${Number(value) > 0 ? '+' : ''}${Number(value).toFixed(2)}%` }
function percent(value, signed = true) { if (value == null) return '-'; const n = Number(value); return `${signed && n > 0 ? '+' : ''}${n.toFixed(2)}%` }
function decimal(value) { return value == null ? '-' : new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 4 }).format(Number(value)) }
function hasMetric(value) { return value !== null && value !== undefined && Number.isFinite(Number(value)) }
function tone(value) { const n = Number(value || 0); return n > 0 ? 'text-emerald-600 dark:text-emerald-400' : n < 0 ? 'text-destructive' : '' }
function warningTitle(code) { return ({ WEALTH_NOT_INITIALIZED: '现金基准未初始化', RESERVE_LOW: '备用金不足', CONCENTRATION_HIGH: '持仓集中度较高', VOLATILITY_HIGH: '市场波动偏高', DRAWDOWN_HIGH: '历史回撤偏大', LIQUIDITY_LOW: '流动性偏低', DATA_INCOMPLETE: '数据完整性不足', MODEL_DRIFT: '模型表现漂移', SAVINGS_GOAL: '储蓄目标提醒', RISK_PREFERENCE: '风险偏好提醒' }[code] || '财务提醒') }
function quantActionLabel(value) { return ({ BUY_WATCH: '买入观察', ADD: '加仓', HOLD: '持有', REDUCE: '减仓', EXIT: '退出', NO_TRADE: '暂不交易' }[value] || '暂不交易') }
function probabilityPercent(value) { return value == null ? '-' : `${(Number(value) * 100).toFixed(1)}%` }
function decimalPercent(value) { return value == null ? '-' : `${Number(value) > 0 ? '+' : ''}${(Number(value) * 100).toFixed(2)}%` }
function riskFlagLabel(value) { return ({ MODEL_NOT_VALIDATED: '模型尚未通过样本外验证，当前不交易', MODEL_UNAVAILABLE: '模型尚未完成训练，当前不交易', DATA_NOT_READY: '可靠数据正在准备中，当前不交易', RESULT_UNAVAILABLE: '有效模型结果正在准备中', MODEL_REFRESH_REQUIRED: '周期已变化，需要更新模型', CASH_BENCHMARK: '当前使用现金收益作为比较基准', FUNDAMENTALS_UNAVAILABLE: '历史基本面公告数据不足，本次模型主要使用行情因子' }[value] || '当前风险条件不支持交易') }
function drawdownStatusLabel(value) {
  return ({ RECOVERED: '已回到前高', RECOVERING: '修复中', IN_DRAWDOWN: '回撤中' }[value] || '等待数据')
}
</script>
