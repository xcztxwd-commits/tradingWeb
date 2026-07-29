export const CHART_BAR_BATCH_SIZE = 500

export type TradingLabChartRuntimeSource = 'LOCAL' | 'ACTUAL'
export type TradingLabChartRuntimeMode = 'TICK' | 'KLINE'

export type TradingLabChartRuntimePeriod = Readonly<{
  type: 'second' | 'minute' | 'hour'
  span: number
}>

export type TradingLabChartRuntimeBar = Readonly<{
  timestamp: number
  open: number
  high: number
  low: number
  close: number
  raw: Readonly<{
    open: string
    high: string
    low: string
    close: string
  }>
  [key: string]: unknown
}>

export type TradingLabChartRuntimeOverlayPoint = Readonly<{
  timestamp: number
  value?: number
}>

export type TradingLabChartRuntimeOverlay = Readonly<{
  id: string
  groupId: string
  name: 'simpleAnnotation' | 'verticalStraightLine'
  points: readonly TradingLabChartRuntimeOverlayPoint[]
  extendData: string
}>

export type TradingLabChartRuntimePriceLine = Readonly<{
  key: string
  label: string
  color: string
}>

export type TradingLabChartTooltipLegend = Readonly<{
  title: string
  value: string
}>

export type TradingLabChartRuntimeLoadOlderRequest = Readonly<{
  beforeTimestamp: number | null
  limit: number
  signal: AbortSignal
}>

export type TradingLabChartRuntimeBarPage = Readonly<{
  bars: readonly TradingLabChartRuntimeBar[]
  hasOlder: boolean
}>

export type TradingLabChartRuntimeModel = Readonly<{
  source: TradingLabChartRuntimeSource
  productType: string
  symbol: string
  pricePrecision: number
  mode: TradingLabChartRuntimeMode
  period: TradingLabChartRuntimePeriod
  bars: readonly TradingLabChartRuntimeBar[]
  priceLines: readonly TradingLabChartRuntimePriceLine[]
  overlays: readonly TradingLabChartRuntimeOverlay[]
  virtualCursorTimestamp: number | null
  loadOlder?: (
    request: TradingLabChartRuntimeLoadOlderRequest,
  ) => Promise<TradingLabChartRuntimeBarPage>
}>

export type TradingLabChartDataLoadMore =
  | boolean
  | Readonly<{
      backward?: boolean
      forward?: boolean
    }>

export type TradingLabChartDataLoaderParams = Readonly<{
  type: 'init' | 'forward' | 'backward' | 'update'
  timestamp: number | null
  callback(
    data: readonly TradingLabChartRuntimeBar[],
    more?: TradingLabChartDataLoadMore,
  ): void
}>

export type TradingLabChartSubscribeBarParams = Readonly<{
  callback(data: TradingLabChartRuntimeBar): void
}>

export interface TradingLabChartDataLoader {
  getBars(
    params: TradingLabChartDataLoaderParams,
  ): void | Promise<void>
  subscribeBar?(params: TradingLabChartSubscribeBarParams): void
  unsubscribeBar?(params: Partial<TradingLabChartSubscribeBarParams>): void
}

export interface TradingLabChartRuntimeChart {
  setSymbol(symbol: Record<string, unknown>): void
  setPeriod(period: TradingLabChartRuntimePeriod): void
  setStyles(styles: Record<string, unknown>): void
  setPriceLines(lines: readonly TradingLabChartRuntimePriceLine[]): void
  setDataLoader(loader: TradingLabChartDataLoader): void
  resetData(): void
  removeOverlay(filter: { groupId: string }): boolean
  createOverlay(overlays: readonly TradingLabChartRuntimeOverlay[]): unknown
  scrollToTimestamp(timestamp: number): void
  resize(): void
}

export interface TradingLabChartRuntimeAdapter {
  init(container: HTMLElement): TradingLabChartRuntimeChart | null
  dispose(chart: TradingLabChartRuntimeChart): void
}

export interface TradingLabChartRuntime {
  mount(container: HTMLElement): void
  update(model: TradingLabChartRuntimeModel | null): void
  publishBar(bar: TradingLabChartRuntimeBar): void
  resize(): void
  dispose(): void
}

const PLAIN_POSITIVE_DECIMAL = /^\d+(?:\.\d+)?$/
const MISSING_TOOLTIP_VALUE = '--'

function tooltipValue(
  candidate: unknown,
  key: string,
): string {
  if (candidate === null || typeof candidate !== 'object') {
    return MISSING_TOOLTIP_VALUE
  }
  const value = Reflect.get(candidate, key)
  return (
    typeof value === 'string'
    && PLAIN_POSITIVE_DECIMAL.test(value)
  )
    ? value
    : MISSING_TOOLTIP_VALUE
}

export function buildTradingLabCandleTooltipLegends(
  candidate: unknown,
): TradingLabChartTooltipLegend[] {
  const raw = (
    candidate !== null
    && typeof candidate === 'object'
  )
    ? Reflect.get(candidate, 'raw')
    : null
  return [
    { title: '开: ', value: tooltipValue(raw, 'open') },
    { title: '高: ', value: tooltipValue(raw, 'high') },
    { title: '低: ', value: tooltipValue(raw, 'low') },
    { title: '收: ', value: tooltipValue(raw, 'close') },
  ]
}

export function buildTradingLabPriceLineTooltipLegends(
  candidate: unknown,
  lines: readonly TradingLabChartRuntimePriceLine[],
): TradingLabChartTooltipLegend[] {
  return lines.map((line) => ({
    title: `${line.label}: `,
    value: tooltipValue(candidate, `${line.key}:raw`),
  }))
}

export function toTradingLabCanvasPrice(value: string): number {
  if (!PLAIN_POSITIVE_DECIMAL.test(value)) {
    throw new Error('Canvas price must be a plain positive decimal')
  }
  const converted = Number(value)
  if (!Number.isFinite(converted) || converted <= 0) {
    throw new Error('Canvas price must be finite and positive')
  }
  return converted
}

function validatePeriod(period: TradingLabChartRuntimePeriod): void {
  if (
    !Number.isSafeInteger(period.span)
    || period.span <= 0
    || !['second', 'minute', 'hour'].includes(period.type)
  ) {
    throw new Error('Trading Lab chart period is invalid')
  }
}

function validateBar(
  candidate: TradingLabChartRuntimeBar,
  previousTimestamp: number | null,
): void {
  if (
    !Number.isSafeInteger(candidate.timestamp)
    || candidate.timestamp <= 0
    || (
      previousTimestamp !== null
      && candidate.timestamp <= previousTimestamp
    )
  ) {
    throw new Error('Trading Lab chart bars must use ascending timestamps')
  }
  for (const value of [
    candidate.open,
    candidate.high,
    candidate.low,
    candidate.close,
  ]) {
    if (!Number.isFinite(value) || value <= 0) {
      throw new Error('Trading Lab chart bar contains a non-finite price')
    }
  }
  if (
    candidate.high < candidate.low
    || candidate.high < candidate.open
    || candidate.high < candidate.close
    || candidate.low > candidate.open
    || candidate.low > candidate.close
  ) {
    throw new Error('Trading Lab chart OHLC values are inconsistent')
  }
  for (const value of [
    candidate.raw?.open,
    candidate.raw?.high,
    candidate.raw?.low,
    candidate.raw?.close,
  ]) {
    if (
      typeof value !== 'string'
      || !PLAIN_POSITIVE_DECIMAL.test(value)
    ) {
      throw new Error('Trading Lab chart bar raw price is invalid')
    }
  }
}

function validateBars(
  candidates: readonly TradingLabChartRuntimeBar[],
): void {
  let previousTimestamp: number | null = null
  for (const candidate of candidates) {
    validateBar(candidate, previousTimestamp)
    previousTimestamp = candidate.timestamp
  }
}

function validateLoadedPage(
  candidate: unknown,
  beforeTimestamp: number | null,
): asserts candidate is TradingLabChartRuntimeBarPage {
  if (
    candidate === null
    || typeof candidate !== 'object'
    || !('bars' in candidate)
    || !Array.isArray(candidate.bars)
    || candidate.bars.length > CHART_BAR_BATCH_SIZE
    || !('hasOlder' in candidate)
    || typeof candidate.hasOlder !== 'boolean'
  ) {
    throw new Error('Trading Lab older chart page is invalid')
  }
  validateBars(candidate.bars)
  if (
    beforeTimestamp !== null
    && candidate.bars.some(({ timestamp }) => timestamp >= beforeTimestamp)
  ) {
    throw new Error('Trading Lab older chart page overlaps its cursor')
  }
}

function validateModel(model: TradingLabChartRuntimeModel): void {
  if (
    model.symbol.trim().length === 0
    || model.productType.trim().length === 0
    || !Number.isSafeInteger(model.pricePrecision)
    || model.pricePrecision < 0
  ) {
    throw new Error('Trading Lab chart instrument is invalid')
  }
  validatePeriod(model.period)
  validateBars(model.bars)
  if (
    model.virtualCursorTimestamp !== null
    && (
      !Number.isSafeInteger(model.virtualCursorTimestamp)
      || model.virtualCursorTimestamp <= 0
      || !model.bars.some(
        ({ timestamp }) => timestamp === model.virtualCursorTimestamp,
      )
    )
  ) {
    throw new Error('Trading Lab chart virtual cursor is outside the model')
  }
}

function pageMore(
  forward: boolean,
  backward: boolean,
): Readonly<{ forward: boolean; backward: boolean }> {
  return { forward, backward }
}

function retainedPage(
  model: TradingLabChartRuntimeModel,
  type: TradingLabChartDataLoaderParams['type'],
  timestamp: number | null,
): Readonly<{
  bars: readonly TradingLabChartRuntimeBar[]
  hasForward: boolean
  hasBackward: boolean
  shouldLoadOlder: boolean
}> {
  const all = model.bars
  if (type === 'init') {
    const start = Math.max(0, all.length - CHART_BAR_BATCH_SIZE)
    return {
      bars: all.slice(start),
      hasForward: start > 0 || model.loadOlder !== undefined,
      hasBackward: false,
      shouldLoadOlder: all.length === 0 && model.loadOlder !== undefined,
    }
  }
  if (type === 'update') {
    return {
      bars: all.length === 0 ? [] : all.slice(-1),
      hasForward: false,
      hasBackward: false,
      shouldLoadOlder: false,
    }
  }
  if (type === 'forward') {
    const before = timestamp ?? all[0]?.timestamp ?? null
    const candidates = before === null
      ? []
      : all.filter(({ timestamp: candidate }) => candidate < before)
    const start = Math.max(0, candidates.length - CHART_BAR_BATCH_SIZE)
    return {
      bars: candidates.slice(start),
      hasForward: start > 0 || model.loadOlder !== undefined,
      hasBackward: false,
      shouldLoadOlder: candidates.length === 0 && model.loadOlder !== undefined,
    }
  }

  const after = timestamp ?? all.at(-1)?.timestamp ?? null
  const candidates = after === null
    ? []
    : all.filter(({ timestamp: candidate }) => candidate > after)
  return {
    bars: candidates.slice(0, CHART_BAR_BATCH_SIZE),
    hasForward: false,
    hasBackward: candidates.length > CHART_BAR_BATCH_SIZE,
    shouldLoadOlder: false,
  }
}

export function createTradingLabChartRuntime(
  adapter: TradingLabChartRuntimeAdapter,
): TradingLabChartRuntime {
  let chart: TradingLabChartRuntimeChart | null = null
  let container: HTMLElement | null = null
  let model: TradingLabChartRuntimeModel | null = null
  let appliedModel: TradingLabChartRuntimeModel | null = null
  let disposed = false
  let generation = 0
  let loadRequestGeneration = 0
  let subscribedBarCallback:
    | ((bar: TradingLabChartRuntimeBar) => void)
    | null = null
  let appliedOverlayGroups = new Set<string>()
  const loadControllers = new Set<AbortController>()

  const invalidateLoads = (): void => {
    loadRequestGeneration += 1
    for (const controller of loadControllers) {
      controller.abort()
    }
    loadControllers.clear()
  }

  const isCurrent = (
    candidateGeneration: number,
    candidateLoadRequestGeneration: number,
    candidateModel: TradingLabChartRuntimeModel,
  ): boolean => (
    !disposed
    && generation === candidateGeneration
    && loadRequestGeneration === candidateLoadRequestGeneration
    && model === candidateModel
  )

  const dataLoader: TradingLabChartDataLoader = {
    async getBars(params) {
      invalidateLoads()
      const candidateLoadRequestGeneration = loadRequestGeneration
      const currentModel = model
      if (disposed || currentModel === null) {
        params.callback([], pageMore(false, false))
        return
      }
      const retained = retainedPage(
        currentModel,
        params.type,
        params.timestamp,
      )
      if (!retained.shouldLoadOlder) {
        params.callback(
          retained.bars,
          pageMore(retained.hasForward, retained.hasBackward),
        )
        return
      }

      const loadOlder = currentModel.loadOlder
      if (loadOlder === undefined) {
        params.callback([], pageMore(false, false))
        return
      }
      const candidateGeneration = generation
      const controller = new AbortController()
      loadControllers.add(controller)
      try {
        const loaded = await loadOlder({
          beforeTimestamp: params.timestamp,
          limit: CHART_BAR_BATCH_SIZE,
          signal: controller.signal,
        })
        if (!isCurrent(
          candidateGeneration,
          candidateLoadRequestGeneration,
          currentModel,
        )) {
          return
        }
        validateLoadedPage(loaded, params.timestamp)
        const start = Math.max(
          0,
          loaded.bars.length - CHART_BAR_BATCH_SIZE,
        )
        params.callback(
          loaded.bars.slice(start),
          pageMore(loaded.hasOlder || start > 0, false),
        )
      } catch {
        if (
          isCurrent(
            candidateGeneration,
            candidateLoadRequestGeneration,
            currentModel,
          )
          && !controller.signal.aborted
        ) {
          params.callback([], pageMore(false, false))
        }
      } finally {
        loadControllers.delete(controller)
      }
    },
    subscribeBar(params) {
      if (!disposed) {
        subscribedBarCallback = params.callback
      }
    },
    unsubscribeBar(params) {
      if (
        params.callback === undefined
        || params.callback === subscribedBarCallback
      ) {
        subscribedBarCallback = null
      }
    },
  }

  const applyModel = (
    currentChart: TradingLabChartRuntimeChart,
    nextModel: TradingLabChartRuntimeModel,
  ): void => {
    if (appliedModel === nextModel) {
      return
    }
    validateModel(nextModel)
    invalidateLoads()
    subscribedBarCallback = null
    currentChart.setSymbol({
      ticker: nextModel.symbol,
      name: nextModel.symbol,
      productType: nextModel.productType,
      pricePrecision: nextModel.pricePrecision,
      volumePrecision: 0,
    })
    currentChart.setPeriod(nextModel.period)
    currentChart.setStyles({
      candle: {
        type: nextModel.mode === 'TICK'
          ? 'area'
          : 'candle_solid',
      },
    })
    currentChart.setPriceLines(nextModel.priceLines)
    currentChart.resetData()

    const nextOverlayGroups = new Set(
      nextModel.overlays.map(({ groupId }) => groupId),
    )
    const groupsToRemove = new Set([
      ...appliedOverlayGroups,
      ...nextOverlayGroups,
    ])
    for (const groupId of groupsToRemove) {
      currentChart.removeOverlay({ groupId })
    }
    if (nextModel.overlays.length > 0) {
      currentChart.createOverlay(nextModel.overlays)
    }
    appliedOverlayGroups = nextOverlayGroups
    appliedModel = nextModel

    if (nextModel.virtualCursorTimestamp !== null) {
      currentChart.scrollToTimestamp(nextModel.virtualCursorTimestamp)
    }
  }

  const clearAppliedModel = (
    currentChart: TradingLabChartRuntimeChart,
  ): void => {
    invalidateLoads()
    subscribedBarCallback = null
    currentChart.setPriceLines([])
    currentChart.resetData()
    for (const groupId of appliedOverlayGroups) {
      currentChart.removeOverlay({ groupId })
    }
    appliedOverlayGroups = new Set()
    appliedModel = null
  }

  return {
    mount(nextContainer) {
      if (disposed) {
        throw new Error('Trading Lab chart runtime is disposed')
      }
      if (chart !== null) {
        if (container !== nextContainer) {
          throw new Error('Trading Lab chart runtime is already mounted')
        }
        return
      }
      const initialized = adapter.init(nextContainer)
      if (initialized === null) {
        throw new Error('KLineCharts 初始化失败')
      }
      chart = initialized
      container = nextContainer
      initialized.setDataLoader(dataLoader)
      if (model !== null) {
        applyModel(initialized, model)
      }
    },
    update(nextModel) {
      if (disposed || model === nextModel) {
        return
      }
      if (nextModel !== null) {
        validateModel(nextModel)
      }
      generation += 1
      invalidateLoads()
      model = nextModel
      if (chart !== null) {
        if (nextModel === null) {
          clearAppliedModel(chart)
        } else {
          applyModel(chart, nextModel)
        }
      }
    },
    publishBar(candidate) {
      if (disposed || subscribedBarCallback === null) {
        return
      }
      validateBars([candidate])
      subscribedBarCallback(candidate)
    },
    resize() {
      if (!disposed) {
        chart?.resize()
      }
    },
    dispose() {
      if (disposed) {
        return
      }
      disposed = true
      generation += 1
      invalidateLoads()
      subscribedBarCallback = null
      appliedOverlayGroups.clear()
      appliedModel = null
      model = null
      if (chart !== null) {
        adapter.dispose(chart)
      }
      chart = null
      container = null
    },
  }
}
