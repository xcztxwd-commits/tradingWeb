import assert from 'node:assert/strict'
import test from 'node:test'

import {
  buildTradingLabCandleTooltipLegends,
  buildTradingLabPriceLineTooltipLegends,
  CHART_BAR_BATCH_SIZE,
  createTradingLabChartRuntime,
  toTradingLabCanvasPrice,
  type TradingLabChartDataLoader,
  type TradingLabChartRuntimeAdapter,
  type TradingLabChartRuntimeBar,
  type TradingLabChartRuntimeChart,
  type TradingLabChartRuntimeModel,
  type TradingLabChartRuntimeOverlay,
  type TradingLabChartRuntimePriceLine,
} from './chartRuntime.ts'

type LoaderResult = Readonly<{
  bars: TradingLabChartRuntimeBar[]
  more: boolean | { backward?: boolean; forward?: boolean } | undefined
}>

function bar(timestamp: number, value = timestamp): TradingLabChartRuntimeBar {
  return {
    timestamp,
    open: value,
    high: value,
    low: value,
    close: value,
    raw: {
      open: String(value),
      high: String(value),
      low: String(value),
      close: String(value),
    },
  }
}

function bars(from: number, to: number): TradingLabChartRuntimeBar[] {
  return Array.from(
    { length: to - from + 1 },
    (_, index) => bar(from + index),
  )
}

function overlay(
  id: string,
  groupId: string,
  timestamp: number,
): TradingLabChartRuntimeOverlay {
  return {
    id,
    groupId,
    name: 'simpleAnnotation',
    points: [{ timestamp, value: 100 }],
    extendData: id,
  }
}

function model(
  overrides: Partial<TradingLabChartRuntimeModel> = {},
): TradingLabChartRuntimeModel {
  return {
    source: 'LOCAL',
    productType: 'CRYPTO_SPOT',
    symbol: 'XBT-USDT-LAB',
    pricePrecision: 2,
    mode: 'TICK',
    period: { type: 'second', span: 1 },
    bars: bars(1, 3),
    priceLines: [],
    overlays: [],
    virtualCursorTimestamp: 2,
    ...overrides,
  }
}

class FakeChart implements TradingLabChartRuntimeChart {
  readonly symbols: Array<Record<string, unknown>> = []
  readonly periods: Array<{ type: 'second' | 'minute' | 'hour'; span: number }> = []
  readonly styles: Array<Record<string, unknown>> = []
  readonly priceLineUpdates: TradingLabChartRuntimePriceLine[][] = []
  readonly removedOverlayGroups: string[] = []
  readonly createdOverlayBatches: TradingLabChartRuntimeOverlay[][] = []
  readonly scrolledTimestamps: number[] = []
  dataLoader: TradingLabChartDataLoader | null = null
  setDataLoaderCount = 0
  resetDataCount = 0
  resizeCount = 0

  setSymbol(symbol: Record<string, unknown>): void {
    this.symbols.push(symbol)
  }

  setPeriod(period: { type: 'second' | 'minute' | 'hour'; span: number }): void {
    this.periods.push(period)
  }

  setStyles(styles: Record<string, unknown>): void {
    this.styles.push(styles)
  }

  setDataLoader(loader: TradingLabChartDataLoader): void {
    this.setDataLoaderCount += 1
    this.dataLoader = loader
  }

  setPriceLines(lines: readonly TradingLabChartRuntimePriceLine[]): void {
    this.priceLineUpdates.push([...lines])
  }

  resetData(): void {
    this.resetDataCount += 1
  }

  removeOverlay(filter: { groupId: string }): boolean {
    this.removedOverlayGroups.push(filter.groupId)
    return true
  }

  createOverlay(overlays: readonly TradingLabChartRuntimeOverlay[]): void {
    this.createdOverlayBatches.push([...overlays])
  }

  scrollToTimestamp(timestamp: number): void {
    this.scrolledTimestamps.push(timestamp)
  }

  resize(): void {
    this.resizeCount += 1
  }
}

class FakeAdapter implements TradingLabChartRuntimeAdapter {
  readonly chart: FakeChart | null
  initCount = 0
  disposeCount = 0
  disposedCharts: TradingLabChartRuntimeChart[] = []

  constructor(chart: FakeChart | null = new FakeChart()) {
    this.chart = chart
  }

  init(_container: HTMLElement): TradingLabChartRuntimeChart | null {
    this.initCount += 1
    return this.chart
  }

  dispose(chart: TradingLabChartRuntimeChart): void {
    this.disposeCount += 1
    this.disposedCharts.push(chart)
  }
}

class ResetLoadingFakeChart extends FakeChart {
  readonly initCallbacks: TradingLabChartRuntimeBar[][] = []
  private hasSymbol = false
  private hasPeriod = false

  private requestInit(): void {
    if (
      !this.hasSymbol
      || !this.hasPeriod
      || this.dataLoader === null
    ) {
      return
    }
    void this.dataLoader.getBars({
      type: 'init',
      timestamp: null,
      callback: (data) => {
        this.initCallbacks.push([...data])
      },
    })
  }

  override setSymbol(symbol: Record<string, unknown>): void {
    super.setSymbol(symbol)
    this.hasSymbol = true
    this.requestInit()
  }

  override setPeriod(
    period: TradingLabChartRuntimeModel['period'],
  ): void {
    super.setPeriod(period)
    this.hasPeriod = true
    this.requestInit()
  }

  override resetData(): void {
    super.resetData()
    this.requestInit()
  }
}

function requireLoader(chart: FakeChart): TradingLabChartDataLoader {
  assert.notEqual(chart.dataLoader, null)
  return chart.dataLoader!
}

async function load(
  loader: TradingLabChartDataLoader,
  type: 'init' | 'forward' | 'backward' | 'update',
  timestamp: number | null,
): Promise<LoaderResult> {
  return await new Promise((resolve) => {
    void loader.getBars({
      type,
      timestamp,
      callback(data, more) {
        resolve({ bars: [...data], more })
      },
    })
  })
}

test('mounts exactly once, updates without re-init, and disposes once', () => {
  const adapter = new FakeAdapter()
  const runtime = createTradingLabChartRuntime(adapter)
  const container = {} as HTMLElement
  const first = model()

  runtime.update(first)
  runtime.mount(container)
  runtime.mount(container)

  assert.equal(adapter.initCount, 1)
  assert.equal(adapter.chart?.setDataLoaderCount, 1)
  assert.equal(adapter.chart?.resetDataCount, 1)
  assert.deepEqual(adapter.chart?.symbols.at(-1), {
    ticker: 'XBT-USDT-LAB',
    name: 'XBT-USDT-LAB',
    productType: 'CRYPTO_SPOT',
    pricePrecision: 2,
    volumePrecision: 0,
  })
  assert.deepEqual(adapter.chart?.periods.at(-1), {
    type: 'second',
    span: 1,
  })
  assert.deepEqual(adapter.chart?.styles.at(-1), {
    candle: { type: 'area' },
  })
  assert.deepEqual(adapter.chart?.priceLineUpdates.at(-1), [])

  const second = model({
    source: 'ACTUAL',
    productType: 'LINEAR_PERP',
    symbol: 'XBT-USDT-LAB-PERP',
    pricePrecision: 4,
    mode: 'KLINE',
    period: { type: 'minute', span: 5 },
    priceLines: [{
      key: 'ACTUAL:LINEAR_PERP:XBT-USDT-LAB-PERP:MARK',
      label: 'ACTUAL MARK',
      color: '#2563eb',
    }],
  })
  runtime.update(second)
  runtime.update(second)

  assert.equal(adapter.initCount, 1)
  assert.equal(adapter.chart?.setDataLoaderCount, 1)
  assert.equal(adapter.chart?.resetDataCount, 2)
  assert.deepEqual(adapter.chart?.styles.at(-1), {
    candle: { type: 'candle_solid' },
  })
  assert.deepEqual(adapter.chart?.priceLineUpdates.at(-1), second.priceLines)

  runtime.dispose()
  runtime.dispose()

  assert.equal(adapter.disposeCount, 1)
  assert.equal(adapter.disposedCharts[0], adapter.chart)

  const strictModeRuntime = createTradingLabChartRuntime(adapter)
  strictModeRuntime.update(first)
  strictModeRuntime.mount(container)
  assert.equal(adapter.initCount, 2)
  strictModeRuntime.dispose()
})

test('data loader reads the latest model and pages retained bars in ascending batches of 500', async () => {
  assert.equal(CHART_BAR_BATCH_SIZE, 500)
  const adapter = new FakeAdapter()
  const runtime = createTradingLabChartRuntime(adapter)
  runtime.update(model({
    bars: bars(1, 1_200),
    virtualCursorTimestamp: 1_100,
  }))
  runtime.mount({} as HTMLElement)

  const loader = requireLoader(adapter.chart!)
  const initial = await load(loader, 'init', null)
  assert.deepEqual(
    initial.bars.map((candidate) => candidate.timestamp),
    bars(701, 1_200).map((candidate) => candidate.timestamp),
  )
  assert.deepEqual(initial.more, { forward: true, backward: false })

  const older = await load(loader, 'forward', 701)
  assert.deepEqual(
    older.bars.map((candidate) => candidate.timestamp),
    bars(201, 700).map((candidate) => candidate.timestamp),
  )
  assert.deepEqual(older.more, { forward: true, backward: false })

  const oldest = await load(loader, 'forward', 201)
  assert.deepEqual(
    oldest.bars.map((candidate) => candidate.timestamp),
    bars(1, 200).map((candidate) => candidate.timestamp),
  )
  assert.deepEqual(oldest.more, { forward: false, backward: false })

  runtime.update(model({
    source: 'ACTUAL',
    bars: bars(2_001, 2_003),
    virtualCursorTimestamp: 2_002,
  }))
  const latest = await load(loader, 'init', null)
  assert.deepEqual(
    latest.bars.map((candidate) => candidate.timestamp),
    [2_001, 2_002, 2_003],
  )
  assert.equal(adapter.chart?.scrolledTimestamps.at(-1), 2_002)

  runtime.dispose()
})

test('generation-fences stale async older loads and aborts them on update or dispose', async () => {
  const adapter = new FakeAdapter()
  const runtime = createTradingLabChartRuntime(adapter)
  let resolveOlder: (page: {
    bars: readonly TradingLabChartRuntimeBar[]
    hasOlder: boolean
  }) => void = () => {
    assert.fail('older page request was not started')
  }
  let receivedSignal = new AbortController().signal
  runtime.update(model({
    bars: [],
    virtualCursorTimestamp: null,
    loadOlder(request) {
      receivedSignal = request.signal
      return new Promise((resolve) => {
        resolveOlder = resolve
      })
    },
  }))
  runtime.mount({} as HTMLElement)
  const loader = requireLoader(adapter.chart!)
  let staleCallbacks = 0
  const pending = loader.getBars({
    type: 'forward',
    timestamp: 500,
    callback() {
      staleCallbacks += 1
    },
  })

  runtime.update(model({
    source: 'ACTUAL',
    bars: [bar(900)],
    virtualCursorTimestamp: 900,
  }))
  assert.equal(receivedSignal.aborted, true)
  resolveOlder({ bars: bars(1, 600), hasOlder: true })
  await pending
  assert.equal(staleCallbacks, 0)

  let resolveDisposed: () => void = () => {
    assert.fail('disposed page request was not started')
  }
  runtime.update(model({
    bars: [],
    virtualCursorTimestamp: null,
    loadOlder: async () => {
      await new Promise<void>((resolve) => {
        resolveDisposed = resolve
      })
      return { bars: [bar(1)], hasOlder: false }
    },
  }))
  const disposedPending = loader.getBars({
    type: 'forward',
    timestamp: 2,
    callback() {
      staleCallbacks += 1
    },
  })
  runtime.dispose()
  resolveDisposed()
  await disposedPending
  assert.equal(staleCallbacks, 0)
})

test('latest reset-triggered async init invalidates an older request in the same model generation', async () => {
  const chart = new ResetLoadingFakeChart()
  const adapter = new FakeAdapter(chart)
  const requests: Array<{
    signal: AbortSignal
    resolve: (page: {
      bars: readonly TradingLabChartRuntimeBar[]
      hasOlder: boolean
    }) => void
  }> = []
  const runtime = createTradingLabChartRuntime(adapter)
  runtime.update(model({
    bars: [],
    virtualCursorTimestamp: null,
    loadOlder: ({ signal }) => new Promise((resolve) => {
      requests.push({ signal, resolve })
    }),
  }))

  runtime.mount({} as HTMLElement)

  assert.equal(requests.length, 2)
  assert.equal(requests[0]?.signal.aborted, true)
  assert.equal(requests[1]?.signal.aborted, false)

  requests[1]?.resolve({ bars: [bar(200)], hasOlder: false })
  await Promise.resolve()
  assert.deepEqual(chart.initCallbacks, [[bar(200)]])

  requests[0]?.resolve({ bars: [bar(100)], hasOlder: false })
  await Promise.resolve()
  assert.deepEqual(chart.initCallbacks, [[bar(200)]])
  runtime.dispose()
})

test('fails closed when an older provider overlaps its cursor or exceeds 500 bars', async () => {
  const adapter = new FakeAdapter()
  const runtime = createTradingLabChartRuntime(adapter)
  let page = {
    bars: [bar(500)],
    hasOlder: false,
  }
  runtime.update(model({
    bars: [],
    virtualCursorTimestamp: null,
    loadOlder: async () => page,
  }))
  runtime.mount({} as HTMLElement)
  const loader = requireLoader(adapter.chart!)

  const overlapping = await load(loader, 'forward', 500)
  assert.deepEqual(overlapping, {
    bars: [],
    more: { forward: false, backward: false },
  })

  page = {
    bars: bars(1, CHART_BAR_BATCH_SIZE + 1),
    hasOlder: false,
  }
  const oversized = await load(
    loader,
    'forward',
    CHART_BAR_BATCH_SIZE + 2,
  )
  assert.deepEqual(oversized, {
    bars: [],
    more: { forward: false, backward: false },
  })
  runtime.dispose()
})

test('publishes a subscribed bar directly without React or data reset', () => {
  const adapter = new FakeAdapter()
  const runtime = createTradingLabChartRuntime(adapter)
  runtime.update(model())
  runtime.mount({} as HTMLElement)
  const loader = requireLoader(adapter.chart!)
  const published: TradingLabChartRuntimeBar[] = []
  const callback = (candidate: TradingLabChartRuntimeBar): void => {
    published.push(candidate)
  }

  loader.subscribeBar?.({ callback })
  const resetCount = adapter.chart!.resetDataCount
  runtime.publishBar(bar(4, 104))

  assert.deepEqual(published, [bar(4, 104)])
  assert.equal(adapter.chart?.resetDataCount, resetCount)

  loader.unsubscribeBar?.({ callback })
  runtime.publishBar(bar(5, 105))
  assert.equal(published.length, 1)

  runtime.dispose()
  runtime.publishBar(bar(6, 106))
  assert.equal(published.length, 1)
})

test('reconciles marker groups, scrolls only verified cursors, and resizes the mounted chart', () => {
  const adapter = new FakeAdapter()
  const runtime = createTradingLabChartRuntime(adapter)
  const local = model({
    overlays: [
      overlay('local-open', 'trading-lab:LOCAL:CRYPTO_SPOT:XBT', 1),
      overlay('local-add', 'trading-lab:LOCAL:CRYPTO_SPOT:XBT', 2),
    ],
    virtualCursorTimestamp: 2,
  })
  runtime.update(local)
  runtime.mount({} as HTMLElement)

  assert.deepEqual(adapter.chart?.removedOverlayGroups, [
    'trading-lab:LOCAL:CRYPTO_SPOT:XBT',
  ])
  assert.deepEqual(
    adapter.chart?.createdOverlayBatches[0]?.map(({ id }) => id),
    ['local-open', 'local-add'],
  )
  assert.deepEqual(adapter.chart?.scrolledTimestamps, [2])

  runtime.update(model({
    source: 'ACTUAL',
    overlays: [
      overlay('actual-stop', 'trading-lab:ACTUAL:CRYPTO_SPOT:XBT', 3),
    ],
    virtualCursorTimestamp: null,
  }))
  assert.deepEqual(adapter.chart?.removedOverlayGroups, [
    'trading-lab:LOCAL:CRYPTO_SPOT:XBT',
    'trading-lab:LOCAL:CRYPTO_SPOT:XBT',
    'trading-lab:ACTUAL:CRYPTO_SPOT:XBT',
  ])
  assert.deepEqual(
    adapter.chart?.createdOverlayBatches.at(-1)?.map(({ id }) => id),
    ['actual-stop'],
  )
  assert.deepEqual(adapter.chart?.scrolledTimestamps, [2])

  runtime.resize()
  assert.equal(adapter.chart?.resizeCount, 1)
  runtime.dispose()
})

test('clears stale data, indicators, and overlays when the current model becomes unavailable', async () => {
  const adapter = new FakeAdapter()
  const runtime = createTradingLabChartRuntime(adapter)
  runtime.update(model({
    priceLines: [{
      key: 'LOCAL:CRYPTO_SPOT:XBT:ASK',
      label: 'LOCAL ASK',
      color: '#dc2626',
    }],
    overlays: [
      overlay('local-open', 'trading-lab:LOCAL:CRYPTO_SPOT:XBT', 1),
    ],
  }))
  runtime.mount({} as HTMLElement)
  const resetBeforeClear = adapter.chart!.resetDataCount

  runtime.update(null)

  assert.equal(adapter.chart?.resetDataCount, resetBeforeClear + 1)
  assert.deepEqual(adapter.chart?.priceLineUpdates.at(-1), [])
  assert.equal(
    adapter.chart?.removedOverlayGroups.at(-1),
    'trading-lab:LOCAL:CRYPTO_SPOT:XBT',
  )
  const empty = await load(requireLoader(adapter.chart!), 'init', null)
  assert.deepEqual(empty.bars, [])
  runtime.dispose()
})

test('fails explicitly when KLineCharts init returns null', () => {
  const runtime = createTradingLabChartRuntime(new FakeAdapter(null))
  runtime.update(model())

  assert.throws(
    () => runtime.mount({} as HTMLElement),
    /KLineCharts 初始化失败/,
  )
})

test('converts decimal strings only at the Canvas seam and rejects non-finite or non-positive values', () => {
  assert.equal(toTradingLabCanvasPrice('123.4500'), 123.45)
  assert.equal(
    toTradingLabCanvasPrice('9007199254740993.125'),
    9_007_199_254_740_994,
  )
  for (const value of [
    '',
    '0',
    '-1',
    'NaN',
    'Infinity',
    '1e10000',
  ]) {
    assert.throws(
      () => toTradingLabCanvasPrice(value),
      /Canvas price/,
    )
  }
})

test('uses authoritative raw decimal strings for candle and price-line tooltips', () => {
  const priceLine: TradingLabChartRuntimePriceLine = {
    key: 'LOCAL:CRYPTO_SPOT:XBT-USDT-LAB:ASK',
    label: 'LOCAL ASK',
    color: '#dc2626',
  }
  const preciseBar: TradingLabChartRuntimeBar = {
    timestamp: 1,
    open: 9_007_199_254_740_994,
    high: 9_007_199_254_740_996,
    low: 9_007_199_254_740_992,
    close: 9_007_199_254_740_994,
    raw: {
      open: '9007199254740993.125',
      high: '9007199254740995.875',
      low: '9007199254740991.625',
      close: '9007199254740993.625',
    },
    [priceLine.key]: 9_007_199_254_740_994,
    [`${priceLine.key}:raw`]: '9007199254740993.875',
  }

  assert.deepEqual(buildTradingLabCandleTooltipLegends(preciseBar), [
    { title: '开: ', value: '9007199254740993.125' },
    { title: '高: ', value: '9007199254740995.875' },
    { title: '低: ', value: '9007199254740991.625' },
    { title: '收: ', value: '9007199254740993.625' },
  ])
  assert.deepEqual(
    buildTradingLabPriceLineTooltipLegends(preciseBar, [priceLine]),
    [{ title: 'LOCAL ASK: ', value: '9007199254740993.875' }],
  )
  assert.deepEqual(buildTradingLabCandleTooltipLegends(null), [
    { title: '开: ', value: '--' },
    { title: '高: ', value: '--' },
    { title: '低: ', value: '--' },
    { title: '收: ', value: '--' },
  ])
  assert.deepEqual(
    buildTradingLabPriceLineTooltipLegends({}, [priceLine]),
    [{ title: 'LOCAL ASK: ', value: '--' }],
  )
})
