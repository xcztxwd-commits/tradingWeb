import type {
  PriceType,
  ProductType,
} from '../model/types.ts'
import {
  compare,
  decimal,
  type Decimal,
} from '../oracle/decimal.ts'
import type {
  InstrumentTick,
  MarketTick,
} from '../oracle/types.ts'
import { sameTradingLabChartEvidence } from './visibleTickWindow.ts'

export type TradingLabChartSource = 'LOCAL' | 'ACTUAL'
export type TradingLabChartPeriod = '1s' | '1m' | '5m' | '15m' | '1h'
export type TradingLabChartMode = 'TICK' | 'KLINE'

export type TradingLabChartBar = Readonly<{
  timestamp: number
  firstSequence: number
  lastSequence: number
  open: string
  high: string
  low: string
  close: string
}>

export type TradingLabChartSeries = Readonly<{
  key: string
  source: TradingLabChartSource
  productType: ProductType
  symbol: string
  priceType: PriceType
  bars: readonly TradingLabChartBar[]
}>

export type TradingLabChartModelIssueCode =
  | 'PRICE_LANE_UNAVAILABLE'
  | 'NO_VISIBLE_PRICE_SERIES'
  | 'VIRTUAL_TIME_OUTSIDE_WINDOW'

export type TradingLabChartModelIssue = Readonly<{
  code: TradingLabChartModelIssueCode
  message: string
  priceType?: PriceType
}>

export type TradingLabChartVirtualCursor = Readonly<{
  timestamp: number
  tickSequence: number
}>

export type TradingLabChartModel = Readonly<{
  source: TradingLabChartSource
  productType: ProductType
  symbol: string
  pricePrecision: number
  mode: TradingLabChartMode
  period: TradingLabChartPeriod
  periodMs: number
  series: readonly TradingLabChartSeries[]
  primarySeriesKey: string | null
  virtualCursor: TradingLabChartVirtualCursor | null
  firstTimestamp: number | null
  lastTimestamp: number | null
  issues: readonly TradingLabChartModelIssue[]
}>

export type TradingLabChartModelInput = Readonly<{
  source: TradingLabChartSource
  productType: ProductType
  symbol: string
  pricePrecision: number
  ticks: readonly MarketTick[]
  period: TradingLabChartPeriod
  mode: TradingLabChartMode
  visiblePrices: ReadonlySet<PriceType>
  virtualTime: string
}>

export type TradingLabChartModelErrorCode =
  | 'CHART_INPUT_INVALID'
  | 'CHART_PRICE_PRECISION_INVALID'
  | 'CHART_SEQUENCE_INVALID'
  | 'CHART_SEQUENCE_CONFLICT'
  | 'CHART_SEQUENCE_ORDER_INVALID'
  | 'CHART_TIME_INVALID'
  | 'CHART_TIME_ORDER_INVALID'
  | 'CHART_INSTRUMENT_MISSING'
  | 'CHART_INSTRUMENT_DUPLICATE'
  | 'CHART_PRICE_INVALID'

export class TradingLabChartModelError extends Error {
  readonly code: TradingLabChartModelErrorCode

  constructor(code: TradingLabChartModelErrorCode, message: string) {
    super(message)
    this.name = 'TradingLabChartModelError'
    this.code = code
  }
}

type ParsedPrice = Readonly<{
  text: string
  value: Decimal
}>

type NormalizedTick = Readonly<{
  sequence: number
  timestamp: number
  prices: Readonly<Partial<Record<PriceType, ParsedPrice>>>
}>

type MutableBar = {
  timestamp: number
  firstSequence: number
  lastSequence: number
  open: string
  high: string
  low: string
  close: string
  highValue: Decimal
  lowValue: Decimal
}

const PRICE_TYPES = [
  'BID',
  'ASK',
  'LAST',
  'MARK',
  'INDEX',
] as const satisfies readonly PriceType[]

const PRIMARY_PRIORITY = [
  'LAST',
  'MARK',
  'INDEX',
  'BID',
  'ASK',
] as const satisfies readonly PriceType[]

const PERIOD_MILLISECONDS: Readonly<Record<TradingLabChartPeriod, number>> = {
  '1s': 1_000,
  '1m': 60_000,
  '5m': 300_000,
  '15m': 900_000,
  '1h': 3_600_000,
}

const UTC_INSTANT = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,3}))?Z$/
const ZERO = decimal('0')

function fail(
  code: TradingLabChartModelErrorCode,
  message: string,
): never {
  throw new TradingLabChartModelError(code, message)
}

function isProductType(value: unknown): value is ProductType {
  return value === 'CRYPTO_SPOT' || value === 'LINEAR_PERP'
}

function isPriceType(value: unknown): value is PriceType {
  return PRICE_TYPES.some((priceType) => priceType === value)
}

function isPeriod(value: unknown): value is TradingLabChartPeriod {
  return (
    value === '1s'
    || value === '1m'
    || value === '5m'
    || value === '15m'
    || value === '1h'
  )
}

export function parseTradingLabUtcTimestamp(value: unknown): number {
  if (typeof value !== 'string') {
    fail('CHART_TIME_INVALID', '图表虚拟时间必须是 UTC 字符串')
  }
  const match = UTC_INSTANT.exec(value)
  if (match === null) {
    fail('CHART_TIME_INVALID', '图表虚拟时间必须是 UTC instant')
  }
  const fraction = (match[2] ?? '').padEnd(3, '0')
  const canonical = `${match[1]}.${fraction}Z`
  const timestamp = Date.parse(canonical)
  if (
    !Number.isFinite(timestamp)
    || new Date(timestamp).toISOString() !== canonical
  ) {
    fail('CHART_TIME_INVALID', '图表虚拟时间不是有效 UTC instant')
  }
  return timestamp
}

function parsedPrice(value: unknown, label: string): ParsedPrice {
  if (typeof value !== 'string') {
    fail('CHART_PRICE_INVALID', `${label} 必须是 decimal string`)
  }
  try {
    const parsed = decimal(value)
    if (compare(parsed, ZERO) <= 0) {
      fail('CHART_PRICE_INVALID', `${label} 必须大于 0`)
    }
    return Object.freeze({ text: value, value: parsed })
  } catch (error) {
    if (error instanceof TradingLabChartModelError) {
      throw error
    }
    fail('CHART_PRICE_INVALID', `${label} 不是合法 decimal string`)
  }
}

function selectedInstrument(
  instruments: unknown,
  productType: ProductType,
  symbol: string,
  sequence: number,
): InstrumentTick {
  if (!Array.isArray(instruments)) {
    fail('CHART_INPUT_INVALID', `Tick ${sequence} instruments 必须是数组`)
  }
  const selected = instruments.filter((instrument): instrument is InstrumentTick => (
    instrument !== null
    && typeof instrument === 'object'
    && (instrument as { productType?: unknown }).productType === productType
    && (instrument as { symbol?: unknown }).symbol === symbol
  ))
  if (selected.length === 0) {
    fail(
      'CHART_INSTRUMENT_MISSING',
      `Tick ${sequence} 缺少 ${productType}/${symbol}`,
    )
  }
  if (selected.length !== 1) {
    fail(
      'CHART_INSTRUMENT_DUPLICATE',
      `Tick ${sequence} 重复 ${productType}/${symbol}`,
    )
  }
  return selected[0]
}

function pricesFor(
  instrument: InstrumentTick,
  sequence: number,
): Readonly<Partial<Record<PriceType, ParsedPrice>>> {
  const prefix = `Tick ${sequence} ${instrument.productType}/${instrument.symbol}`
  const prices: Partial<Record<PriceType, ParsedPrice>> = {
    BID: parsedPrice(instrument.bid, `${prefix} BID`),
    ASK: parsedPrice(instrument.ask, `${prefix} ASK`),
    LAST: parsedPrice(instrument.last, `${prefix} LAST`),
  }
  if (instrument.productType === 'LINEAR_PERP') {
    prices.MARK = parsedPrice(instrument.mark, `${prefix} MARK`)
    prices.INDEX = parsedPrice(instrument.index, `${prefix} INDEX`)
  }
  return Object.freeze(prices)
}

function normalizeTicks(
  ticks: readonly MarketTick[],
  productType: ProductType,
  symbol: string,
): readonly NormalizedTick[] {
  if (!Array.isArray(ticks)) {
    fail('CHART_INPUT_INVALID', 'ticks 必须是数组')
  }
  const normalized: NormalizedTick[] = []
  const replayBySequence = new Map<number, MarketTick>()
  let priorSequence: number | null = null
  let priorTimestamp: number | null = null

  for (const tick of ticks) {
    if (
      tick === null
      || typeof tick !== 'object'
      || !Number.isSafeInteger(tick.sequence)
      || tick.sequence <= 0
    ) {
      fail('CHART_SEQUENCE_INVALID', 'Tick sequence 必须是正 safe integer')
    }
    if (priorSequence !== null && tick.sequence < priorSequence) {
      fail('CHART_SEQUENCE_ORDER_INVALID', 'Tick sequence 必须严格递增')
    }
    const existing = replayBySequence.get(tick.sequence)
    if (existing !== undefined) {
      if (sameTradingLabChartEvidence(existing, tick)) {
        continue
      }
      fail(
        'CHART_SEQUENCE_CONFLICT',
        `Tick sequence ${tick.sequence} payload 冲突`,
      )
    }

    const timestamp = parseTradingLabUtcTimestamp(tick.virtualTime)
    if (priorSequence !== null && tick.sequence <= priorSequence) {
      fail('CHART_SEQUENCE_ORDER_INVALID', 'Tick sequence 必须严格递增')
    }
    if (priorTimestamp !== null && timestamp <= priorTimestamp) {
      fail('CHART_TIME_ORDER_INVALID', 'Tick virtualTime 必须严格递增')
    }
    const instrument = selectedInstrument(
      tick.instruments,
      productType,
      symbol,
      tick.sequence,
    )
    normalized.push(Object.freeze({
      sequence: tick.sequence,
      timestamp,
      prices: pricesFor(instrument, tick.sequence),
    }))
    replayBySequence.set(tick.sequence, tick)
    priorSequence = tick.sequence
    priorTimestamp = timestamp
  }
  return Object.freeze(normalized)
}

function finishBar(bar: MutableBar): TradingLabChartBar {
  return Object.freeze({
    timestamp: bar.timestamp,
    firstSequence: bar.firstSequence,
    lastSequence: bar.lastSequence,
    open: bar.open,
    high: bar.high,
    low: bar.low,
    close: bar.close,
  })
}

function barsFor(
  ticks: readonly NormalizedTick[],
  priceType: PriceType,
  periodMs: number,
  mode: TradingLabChartMode,
): readonly TradingLabChartBar[] {
  const bars: TradingLabChartBar[] = []
  let current: MutableBar | null = null

  for (const tick of ticks) {
    const price = tick.prices[priceType]
    if (price === undefined) {
      continue
    }
    const timestamp = mode === 'TICK'
      ? tick.timestamp
      : Math.floor(tick.timestamp / periodMs) * periodMs
    if (current === null || current.timestamp !== timestamp) {
      if (current !== null) {
        bars.push(finishBar(current))
      }
      current = {
        timestamp,
        firstSequence: tick.sequence,
        lastSequence: tick.sequence,
        open: price.text,
        high: price.text,
        low: price.text,
        close: price.text,
        highValue: price.value,
        lowValue: price.value,
      }
      continue
    }
    current.lastSequence = tick.sequence
    current.close = price.text
    if (compare(price.value, current.highValue) > 0) {
      current.high = price.text
      current.highValue = price.value
    }
    if (compare(price.value, current.lowValue) < 0) {
      current.low = price.text
      current.lowValue = price.value
    }
  }
  if (current !== null) {
    bars.push(finishBar(current))
  }
  return Object.freeze(bars)
}

export function tradingLabChartSeriesKey(input: Readonly<{
  source: TradingLabChartSource
  productType: ProductType
  symbol: string
  priceType: PriceType
}>): string {
  return `${input.source}:${input.productType}:${input.symbol}:${input.priceType}`
}

function validateInput(input: TradingLabChartModelInput): void {
  if (
    input === null
    || typeof input !== 'object'
    || (input.source !== 'LOCAL' && input.source !== 'ACTUAL')
    || !isProductType(input.productType)
    || typeof input.symbol !== 'string'
    || input.symbol.trim().length === 0
    || (input.mode !== 'TICK' && input.mode !== 'KLINE')
    || !isPeriod(input.period)
    || !(input.visiblePrices instanceof Set)
  ) {
    fail('CHART_INPUT_INVALID', 'Trading Lab chart input 无效')
  }
  if (
    !Number.isSafeInteger(input.pricePrecision)
    || input.pricePrecision < 0
  ) {
    fail(
      'CHART_PRICE_PRECISION_INVALID',
      'pricePrecision 必须是非负 safe integer',
    )
  }
  for (const priceType of input.visiblePrices) {
    if (!isPriceType(priceType)) {
      fail('CHART_INPUT_INVALID', 'visiblePrices 包含未知价格类型')
    }
  }
}

function virtualCursorFor(
  ticks: readonly NormalizedTick[],
  primarySeries: TradingLabChartSeries | undefined,
  mode: TradingLabChartMode,
  periodMs: number,
  virtualTimestamp: number,
): TradingLabChartVirtualCursor | null {
  const first = ticks[0]
  const last = ticks.at(-1)
  if (
    first === undefined
    || last === undefined
    || primarySeries === undefined
    || virtualTimestamp < first.timestamp
    || virtualTimestamp > last.timestamp
  ) {
    return null
  }
  let selected = first
  for (const tick of ticks) {
    if (tick.timestamp > virtualTimestamp) {
      break
    }
    selected = tick
  }
  const timestamp = mode === 'TICK'
    ? selected.timestamp
    : Math.floor(virtualTimestamp / periodMs) * periodMs
  if (!primarySeries.bars.some((bar) => bar.timestamp === timestamp)) {
    return null
  }
  return Object.freeze({
    timestamp,
    tickSequence: selected.sequence,
  })
}

export function buildTradingLabChartModel(
  input: TradingLabChartModelInput,
): TradingLabChartModel {
  validateInput(input)
  const period = input.mode === 'TICK' ? '1s' : input.period
  const periodMs = PERIOD_MILLISECONDS[period]
  const ticks = normalizeTicks(input.ticks, input.productType, input.symbol)
  const issues: TradingLabChartModelIssue[] = []
  const series: TradingLabChartSeries[] = []

  for (const priceType of PRICE_TYPES) {
    if (!input.visiblePrices.has(priceType)) {
      continue
    }
    const available = (
      input.productType === 'LINEAR_PERP'
      || (priceType !== 'MARK' && priceType !== 'INDEX')
    )
    if (!available) {
      issues.push(Object.freeze({
        code: 'PRICE_LANE_UNAVAILABLE',
        priceType,
        message: `${input.productType}/${input.symbol} 不提供 ${priceType}`,
      }))
      continue
    }
    const bars = barsFor(ticks, priceType, periodMs, input.mode)
    if (bars.length === 0) {
      continue
    }
    series.push(Object.freeze({
      key: tradingLabChartSeriesKey({
        source: input.source,
        productType: input.productType,
        symbol: input.symbol,
        priceType,
      }),
      source: input.source,
      productType: input.productType,
      symbol: input.symbol,
      priceType,
      bars,
    }))
  }

  const primary = PRIMARY_PRIORITY
    .map((priceType) => series.find((candidate) => (
      candidate.priceType === priceType
    )))
    .find((candidate) => candidate !== undefined)
  if (series.length === 0) {
    issues.push(Object.freeze({
      code: 'NO_VISIBLE_PRICE_SERIES',
      message: '当前窗口没有可显示的价格序列',
    }))
  }

  const virtualTimestamp = parseTradingLabUtcTimestamp(input.virtualTime)
  const virtualCursor = virtualCursorFor(
    ticks,
    primary,
    input.mode,
    periodMs,
    virtualTimestamp,
  )
  if (primary !== undefined && virtualCursor === null) {
    issues.push(Object.freeze({
      code: 'VIRTUAL_TIME_OUTSIDE_WINDOW',
      message: '虚拟时间不在当前窗口',
    }))
  }

  return Object.freeze({
    source: input.source,
    productType: input.productType,
    symbol: input.symbol,
    pricePrecision: input.pricePrecision,
    mode: input.mode,
    period,
    periodMs,
    series: Object.freeze(series),
    primarySeriesKey: primary?.key ?? null,
    virtualCursor,
    firstTimestamp: ticks[0]?.timestamp ?? null,
    lastTimestamp: ticks.at(-1)?.timestamp ?? null,
    issues: Object.freeze(issues),
  })
}
