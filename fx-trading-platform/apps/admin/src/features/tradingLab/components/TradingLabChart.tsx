import {
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react'

import {
  buildTradingLabChartMarkerOverlays,
  type TradingLabChartMarker,
  type TradingLabChartMarkerOverlay,
} from '../chart/chartMarkers.ts'
import {
  buildTradingLabChartModel,
  type TradingLabChartMode,
  type TradingLabChartModel,
  type TradingLabChartPeriod,
  type TradingLabChartSeries,
  type TradingLabChartSource,
} from '../chart/chartModel.ts'
import {
  createTradingLabChartRuntime,
  toTradingLabCanvasPrice,
  type TradingLabChartRuntime,
  type TradingLabChartRuntimeBar,
  type TradingLabChartRuntimeModel,
  type TradingLabChartRuntimeOverlay,
  type TradingLabChartRuntimePeriod,
  type TradingLabChartRuntimePriceLine,
} from '../chart/chartRuntime.ts'
import { klineChartAdapter } from '../chart/klineChartAdapter.ts'
import type {
  PriceType,
  ProductType,
} from '../model/types.ts'
import type { MarketTick } from '../oracle/types.ts'

export type {
  TradingLabChartMode,
  TradingLabChartPeriod,
}

export type TradingLabChartProps = Readonly<{
  productType: ProductType
  symbol: string
  pricePrecision: number
  ticks: readonly MarketTick[]
  localTicks: readonly MarketTick[]
  markers: readonly TradingLabChartMarker[]
  period: TradingLabChartPeriod
  mode: TradingLabChartMode
  visiblePrices: ReadonlySet<PriceType>
  virtualTime: string
}>

type PreparedChart =
  | Readonly<{
      runtimeModel: TradingLabChartRuntimeModel
      issues: readonly string[]
      error: null
    }>
  | Readonly<{
      runtimeModel: null
      issues: readonly []
      error: string
    }>

const PRICE_LINE_COLORS: Readonly<Record<PriceType, string>> = {
  BID: '#16a34a',
  ASK: '#dc2626',
  LAST: '#2563eb',
  MARK: '#7c3aed',
  INDEX: '#d97706',
}

function boundedMessage(value: unknown): string {
  const raw = value instanceof Error
    ? value.message
    : 'Trading Lab 图表模型无效'
  const plain = raw.trim()
  return (plain.length === 0 ? 'Trading Lab 图表模型无效' : plain)
    .slice(0, 240)
}

function runtimePeriod(
  period: TradingLabChartPeriod,
): TradingLabChartRuntimePeriod {
  switch (period) {
    case '1s':
      return { type: 'second', span: 1 }
    case '1m':
      return { type: 'minute', span: 1 }
    case '5m':
      return { type: 'minute', span: 5 }
    case '15m':
      return { type: 'minute', span: 15 }
    case '1h':
      return { type: 'hour', span: 1 }
  }
}

function seriesAt(
  model: TradingLabChartModel,
  key: string | null,
): TradingLabChartSeries | undefined {
  return key === null
    ? undefined
    : model.series.find((candidate) => candidate.key === key)
}

function runtimeBars(
  model: TradingLabChartModel,
  primary: TradingLabChartSeries | undefined,
): readonly TradingLabChartRuntimeBar[] {
  if (primary === undefined) {
    return []
  }
  const closeBySeries = new Map(
    model.series.map((series) => [
      series.key,
      new Map(series.bars.map((bar) => [bar.timestamp, bar.close])),
    ]),
  )
  return primary.bars.map((bar): TradingLabChartRuntimeBar => {
    const additional: Record<string, unknown> = {}
    for (const series of model.series) {
      const rawClose = closeBySeries.get(series.key)?.get(bar.timestamp)
      if (rawClose === undefined) {
        continue
      }
      additional[series.key] = toTradingLabCanvasPrice(rawClose)
      additional[`${series.key}:raw`] = rawClose
    }
    return Object.freeze({
      timestamp: bar.timestamp,
      open: toTradingLabCanvasPrice(bar.open),
      high: toTradingLabCanvasPrice(bar.high),
      low: toTradingLabCanvasPrice(bar.low),
      close: toTradingLabCanvasPrice(bar.close),
      raw: Object.freeze({
        open: bar.open,
        high: bar.high,
        low: bar.low,
        close: bar.close,
      }),
      ...additional,
    })
  })
}

function runtimePriceLines(
  model: TradingLabChartModel,
  primary: TradingLabChartSeries | undefined,
): readonly TradingLabChartRuntimePriceLine[] {
  return model.series
    .filter((series) => series.key !== primary?.key)
    .map((series) => Object.freeze({
      key: series.key,
      label: `${series.source} ${series.priceType}`,
      color: PRICE_LINE_COLORS[series.priceType],
    }))
}

function runtimeOverlay(
  overlay: TradingLabChartMarkerOverlay,
): TradingLabChartRuntimeOverlay {
  return Object.freeze({
    id: overlay.id,
    groupId: overlay.groupId,
    name: overlay.name,
    points: Object.freeze(overlay.points.map((point) => (
      'value' in point
        ? Object.freeze({
            timestamp: point.timestamp,
            value: toTradingLabCanvasPrice(point.value),
          })
        : Object.freeze({ timestamp: point.timestamp })
    ))),
    extendData: overlay.extendData,
  })
}

function prepareChart(input: Readonly<{
  source: TradingLabChartSource
  productType: ProductType
  symbol: string
  pricePrecision: number
  ticks: readonly MarketTick[]
  markers: readonly TradingLabChartMarker[]
  period: TradingLabChartPeriod
  mode: TradingLabChartMode
  visiblePrices: ReadonlySet<PriceType>
  virtualTime: string
}>): PreparedChart {
  try {
    const model = buildTradingLabChartModel({
      source: input.source,
      productType: input.productType,
      symbol: input.symbol,
      pricePrecision: input.pricePrecision,
      ticks: input.ticks,
      period: input.period,
      mode: input.mode,
      visiblePrices: input.visiblePrices,
      virtualTime: input.virtualTime,
    })
    const primary = seriesAt(model, model.primarySeriesKey)
    const overlays = buildTradingLabChartMarkerOverlays({
      source: input.source,
      productType: input.productType,
      symbol: input.symbol,
      markers: input.markers,
      firstTimestamp: model.firstTimestamp,
      lastTimestamp: model.lastTimestamp,
    })
    return {
      runtimeModel: Object.freeze({
        source: model.source,
        productType: model.productType,
        symbol: model.symbol,
        pricePrecision: model.pricePrecision,
        mode: model.mode,
        period: runtimePeriod(model.period),
        bars: Object.freeze(runtimeBars(model, primary)),
        priceLines: Object.freeze(runtimePriceLines(model, primary)),
        overlays: Object.freeze(overlays.map(runtimeOverlay)),
        virtualCursorTimestamp: model.virtualCursor?.timestamp ?? null,
      }),
      issues: Object.freeze(model.issues.map(({ message }) => message)),
      error: null,
    }
  } catch (error) {
    return {
      runtimeModel: null,
      issues: [],
      error: boundedMessage(error),
    }
  }
}

export function TradingLabChart({
  productType,
  symbol,
  pricePrecision,
  ticks,
  localTicks,
  markers,
  period,
  mode,
  visiblePrices,
  virtualTime,
}: TradingLabChartProps) {
  const [requestedSource, setRequestedSource] =
    useState<TradingLabChartSource>('LOCAL')
  const [runtimeError, setRuntimeError] = useState<string | null>(null)
  const containerRef = useRef<HTMLDivElement>(null)
  const runtimeRef = useRef<TradingLabChartRuntime | null>(null)
  const actualAvailable = ticks.length > 0
  const source = requestedSource === 'ACTUAL' && !actualAvailable
    ? 'LOCAL'
    : requestedSource
  const sourceTicks = source === 'LOCAL' ? localTicks : ticks

  const prepared = useMemo(() => prepareChart({
    source,
    productType,
    symbol,
    pricePrecision,
    ticks: sourceTicks,
    markers,
    period,
    mode,
    visiblePrices,
    virtualTime,
  }), [
    markers,
    mode,
    period,
    pricePrecision,
    productType,
    source,
    sourceTicks,
    symbol,
    virtualTime,
    visiblePrices,
  ])

  useEffect(() => {
    const container = containerRef.current
    if (container === null) {
      return
    }
    const runtime = createTradingLabChartRuntime(klineChartAdapter)
    runtimeRef.current = runtime
    try {
      runtime.mount(container)
      setRuntimeError(null)
    } catch (error) {
      setRuntimeError(boundedMessage(error))
      runtimeRef.current = null
      runtime.dispose()
      return
    }

    const observer = typeof ResizeObserver === 'function'
      ? new ResizeObserver(() => runtime.resize())
      : null
    observer?.observe(container)

    return () => {
      observer?.disconnect()
      if (runtimeRef.current === runtime) {
        runtimeRef.current = null
      }
      runtime.dispose()
    }
  }, [])

  useEffect(() => {
    const runtime = runtimeRef.current
    if (runtime === null) {
      return
    }
    try {
      runtime.update(prepared.runtimeModel)
      setRuntimeError(null)
    } catch (error) {
      runtime.update(null)
      setRuntimeError(boundedMessage(error))
    }
  }, [prepared])

  const error = runtimeError ?? prepared.error
  const empty = (
    prepared.runtimeModel === null
    || prepared.runtimeModel.bars.length === 0
  )

  return (
    <div className="trading-lab-chart">
      <div
        className="trading-lab-chart-source-tabs"
        aria-label="图表数据来源"
      >
        <button
          type="button"
          aria-pressed={source === 'LOCAL'}
          onClick={() => setRequestedSource('LOCAL')}
        >LOCAL</button>
        <button
          type="button"
          aria-pressed={source === 'ACTUAL'}
          disabled={!actualAvailable}
          onClick={() => setRequestedSource('ACTUAL')}
        >ACTUAL</button>
      </div>

      {!actualAvailable ? (
        <p className="trading-lab-chart-status">
          尚无实际价格数据，ACTUAL 不会使用 LOCAL 数据替代。
        </p>
      ) : (
        <p className="trading-lab-chart-status">
          当前显示 {source} · {productType}/{symbol}
        </p>
      )}

      <time
        className="trading-lab-chart-virtual-time"
        data-testid="trading-lab-chart-virtual-time"
        dateTime={virtualTime}
      >
        {virtualTime}
      </time>

      {error === null ? null : (
        <p className="trading-lab-chart-empty" role="alert">{error}</p>
      )}
      {error === null && empty ? (
        <p className="trading-lab-chart-empty">
          当前 {source} 窗口没有可显示的价格序列。
        </p>
      ) : null}

      <div
        ref={containerRef}
        className="trading-lab-chart-canvas"
        data-testid="trading-lab-chart-canvas"
        aria-label={`${source} ${productType}/${symbol} 路径图表`}
      />

      {prepared.runtimeModel === null
        || prepared.runtimeModel.overlays.length === 0 ? null : (
          <div
            className="trading-lab-chart-marker-evidence"
            aria-label="当前可见 ACTUAL 事件标记"
          >
            {prepared.runtimeModel.overlays.map((overlay) => (
              <span
                key={overlay.id}
                data-testid="trading-lab-chart-event-marker"
                data-marker-id={overlay.id}
              >
                {overlay.extendData}
              </span>
            ))}
          </div>
        )}

      {prepared.issues.length === 0 ? null : (
        <ul className="trading-lab-chart-status">
          {prepared.issues.map((issue) => (
            <li key={issue}>{issue}</li>
          ))}
        </ul>
      )}
    </div>
  )
}
