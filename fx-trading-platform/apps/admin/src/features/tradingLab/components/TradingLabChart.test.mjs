import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const componentUrl = new URL('./TradingLabChart.tsx', import.meta.url)
const adapterUrl = new URL('../chart/klineChartAdapter.ts', import.meta.url)
const runtimeUrl = new URL('../chart/chartRuntime.ts', import.meta.url)

test('freezes composite chart props and keeps LOCAL/ACTUAL source selection honest', async () => {
  const source = await readFile(componentUrl, 'utf8')

  for (const contract of [
    /productType:\s*ProductType/u,
    /symbol:\s*string/u,
    /pricePrecision:\s*number/u,
    /ticks:\s*readonly MarketTick\[\]/u,
    /localTicks:\s*readonly MarketTick\[\]/u,
    /markers:\s*readonly TradingLabChartMarker\[\]/u,
    /period:\s*TradingLabChartPeriod/u,
    /mode:\s*TradingLabChartMode/u,
    /visiblePrices:\s*ReadonlySet<PriceType>/u,
    /virtualTime:\s*string/u,
  ]) {
    assert.match(source, contract)
  }
  assert.match(source, /useState<TradingLabChartSource>\('LOCAL'\)/u)
  assert.match(source, />LOCAL</u)
  assert.match(source, />ACTUAL</u)
  assert.match(source, /disabled=\{!actualAvailable\}/u)
  assert.match(source, /尚无实际价格数据/u)
  assert.doesNotMatch(source, /ticks\s*=\s*localTicks/u)
})

test('projects the pure model and evidence markers into one runtime seam', async () => {
  const source = await readFile(componentUrl, 'utf8')

  assert.match(source, /buildTradingLabChartModel\(/u)
  assert.match(source, /buildTradingLabChartMarkerOverlays\(/u)
  assert.match(source, /createTradingLabChartRuntime\(klineChartAdapter\)/u)
  assert.match(source, /runtime\.mount\(container\)/u)
  assert.match(source, /runtime\.update\(/u)
  assert.match(source, /runtime\.dispose\(\)/u)
  assert.match(source, /toTradingLabCanvasPrice\(/u)
  assert.match(source, /primarySeriesKey/u)
  assert.match(source, /virtualCursor/u)
  assert.match(source, /extendData:\s*overlay\.extendData/u)
  assert.doesNotMatch(source, /extendData:\s*Object\.freeze\(\{/u)

  assert.match(source, /className="trading-lab-chart"/u)
  assert.match(source, /className="trading-lab-chart-source-tabs"/u)
  assert.match(source, /className="trading-lab-chart-canvas"/u)
  assert.match(source, /className="trading-lab-chart-empty"/u)
  assert.match(source, /className="trading-lab-chart-status"/u)
  assert.match(source, /data-testid="trading-lab-chart-canvas"/u)
  assert.match(source, /data-testid="trading-lab-chart-event-marker"/u)
  assert.match(source, /data-testid="trading-lab-chart-virtual-time"/u)
})

test('creates a fresh runtime per effect setup and never generates or stores full paths', async () => {
  const source = await readFile(componentUrl, 'utf8')

  assert.match(
    source,
    /useEffect\(\(\) => \{[\s\S]*createTradingLabChartRuntime\(klineChartAdapter\)[\s\S]*return \(\) => \{[\s\S]*runtime\.dispose\(\)/u,
  )
  assert.doesNotMatch(
    source,
    /useState\([^)]*createTradingLabChartRuntime/u,
  )
  for (const forbidden of [
    /generateMarketTicks/u,
    /calculateLocalExpectedScenario/u,
    /useState<[^>]*MarketTick/u,
    /response\.text\(/u,
    /report/u,
    /EventSource/u,
    /fetch\(/u,
  ]) {
    assert.doesNotMatch(source, forbidden)
  }
})

test('stops setup before observing resize when runtime mount fails', async () => {
  const source = await readFile(componentUrl, 'utf8')

  assert.match(
    source,
    /catch \(error\) \{[\s\S]*runtime\.dispose\(\)\s+return\s+\}\s+const observer/u,
  )
})

test('production adapter uses only the KLineCharts v10 lifecycle and data-loader API', async () => {
  const source = await readFile(adapterUrl, 'utf8')
  const runtime = await readFile(runtimeUrl, 'utf8')

  assert.match(
    source,
    /from 'klinecharts'/u,
  )
  assert.match(source, /\binit\(/u)
  assert.match(source, /\bdispose\(/u)
  assert.match(source, /\.setDataLoader\(/u)
  assert.match(source, /\.resetData\(/u)
  assert.match(source, /\.createOverlay\(/u)
  assert.match(source, /\.removeOverlay\(/u)
  assert.match(source, /\.createIndicator\(/u)
  assert.match(source, /\.removeIndicator\(/u)
  assert.match(source, /\bregisterIndicator\(/u)
  assert.match(
    source,
    /\.createIndicator\(indicator,\s*\{\s*pane:\s*\{\s*id:\s*'candle_pane'\s*\},\s*isStack:\s*true,?\s*\}\)/u,
  )
  assert.match(source, /if \(indicatorId === null\)/u)
  assert.match(runtime, /subscribeBar/u)
  assert.match(runtime, /scrollToTimestamp/u)

  for (const forbidden of [
    'applyNewData',
    'applyMoreData',
    'updateData',
    'cdn.',
    'https://',
  ]) {
    assert.equal(source.includes(forbidden), false, forbidden)
  }
})

test('production adapter routes raw decimal strings into candle and price-line tooltips', async () => {
  const source = await readFile(adapterUrl, 'utf8')

  assert.match(
    source,
    /tooltip:\s*\{[\s\S]*legend:\s*\{[\s\S]*template:\s*\(\{\s*current,\s*\}:\s*\{\s*current:\s*KLineData\s*\|\s*null\s*\}\)\s*=>[\s\S]{0,120}buildTradingLabCandleTooltipLegends\(current\)/u,
  )
  assert.match(
    source,
    /createTooltipDataSource:\s*\(\{\s*chart,\s*crosshair\s*\}\)[\s\S]*buildTradingLabPriceLineTooltipLegends\(/u,
  )
})
