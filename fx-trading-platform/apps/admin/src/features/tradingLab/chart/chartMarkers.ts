import type {
  ProductType,
} from '../model/types.ts'
import {
  compare,
  decimal,
} from '../oracle/decimal.ts'
import {
  parseTradingLabUtcTimestamp,
  type TradingLabChartSource,
} from './chartModel.ts'
import { sameTradingLabChartEvidence } from './visibleTickWindow.ts'

export type TradingLabChartMarkerKind =
  | 'EXECUTION'
  | 'OPEN'
  | 'ADD'
  | 'REDUCE'
  | 'TAKE_PROFIT'
  | 'STOP_LOSS'
  | 'FUNDING'
  | 'LIQUIDATION'

export type TradingLabChartMarker = Readonly<{
  id: string
  source: TradingLabChartSource
  kind: TradingLabChartMarkerKind
  productType: ProductType
  symbol: string
  tickSequence: number
  virtualTime: string
  price: string | null
  side: 'BUY' | 'SELL' | null
  actionId: string | null
}>

export type TradingLabChartMarkerPoint =
  | Readonly<{ timestamp: number; value: string }>
  | Readonly<{ timestamp: number }>

export type TradingLabChartMarkerOverlay = Readonly<{
  id: string
  markerId: string
  groupId: string
  name: 'simpleAnnotation' | 'verticalStraightLine'
  source: TradingLabChartSource
  kind: TradingLabChartMarkerKind
  label: string
  timestamp: number
  price: string | null
  side: 'BUY' | 'SELL' | null
  actionId: string | null
  points: readonly TradingLabChartMarkerPoint[]
  extendData: string
  lock: true
  visible: true
}>

export type TradingLabChartMarkerOverlayInput = Readonly<{
  source: TradingLabChartSource
  productType: ProductType
  symbol: string
  markers: readonly TradingLabChartMarker[]
  firstTimestamp: number | null
  lastTimestamp: number | null
}>

export type TradingLabChartMarkerErrorCode =
  | 'CHART_MARKER_INPUT_INVALID'
  | 'CHART_MARKER_INVALID'
  | 'CHART_MARKER_TIME_INVALID'
  | 'CHART_MARKER_PRICE_INVALID'
  | 'CHART_MARKER_WINDOW_INVALID'
  | 'CHART_MARKER_ID_CONFLICT'

export class TradingLabChartMarkerError extends Error {
  readonly code: TradingLabChartMarkerErrorCode

  constructor(code: TradingLabChartMarkerErrorCode, message: string) {
    super(message)
    this.name = 'TradingLabChartMarkerError'
    this.code = code
  }
}

const MARKER_KINDS = [
  'EXECUTION',
  'OPEN',
  'ADD',
  'REDUCE',
  'TAKE_PROFIT',
  'STOP_LOSS',
  'FUNDING',
  'LIQUIDATION',
] as const satisfies readonly TradingLabChartMarkerKind[]

const ZERO = decimal('0')

function fail(
  code: TradingLabChartMarkerErrorCode,
  message: string,
): never {
  throw new TradingLabChartMarkerError(code, message)
}

function isSource(value: unknown): value is TradingLabChartSource {
  return value === 'LOCAL' || value === 'ACTUAL'
}

function isProductType(value: unknown): value is ProductType {
  return value === 'CRYPTO_SPOT' || value === 'LINEAR_PERP'
}

function isMarkerKind(value: unknown): value is TradingLabChartMarkerKind {
  return MARKER_KINDS.some((kind) => kind === value)
}

function markerTimestamp(value: unknown): number {
  try {
    return parseTradingLabUtcTimestamp(value)
  } catch {
    fail('CHART_MARKER_TIME_INVALID', 'marker virtualTime 不是有效 UTC instant')
  }
}

function validatePrice(value: unknown): asserts value is string | null {
  if (value === null) {
    return
  }
  if (typeof value !== 'string') {
    fail('CHART_MARKER_PRICE_INVALID', 'marker price 必须是 decimal string 或 null')
  }
  try {
    if (compare(decimal(value), ZERO) <= 0) {
      fail('CHART_MARKER_PRICE_INVALID', 'marker price 必须大于 0')
    }
  } catch (error) {
    if (error instanceof TradingLabChartMarkerError) {
      throw error
    }
    fail('CHART_MARKER_PRICE_INVALID', 'marker price 不是合法 decimal string')
  }
}

function validateMarker(
  marker: TradingLabChartMarker,
): number {
  if (
    marker === null
    || typeof marker !== 'object'
    || typeof marker.id !== 'string'
    || marker.id.trim().length === 0
    || !isSource(marker.source)
    || !isMarkerKind(marker.kind)
    || !isProductType(marker.productType)
    || typeof marker.symbol !== 'string'
    || marker.symbol.trim().length === 0
    || !Number.isSafeInteger(marker.tickSequence)
    || marker.tickSequence <= 0
    || (
      marker.side !== null
      && marker.side !== 'BUY'
      && marker.side !== 'SELL'
    )
    || (
      marker.actionId !== null
      && (
        typeof marker.actionId !== 'string'
        || marker.actionId.trim().length === 0
      )
    )
  ) {
    fail('CHART_MARKER_INVALID', 'Trading Lab marker shape 无效')
  }
  const timestamp = markerTimestamp(marker.virtualTime)
  validatePrice(marker.price)
  return timestamp
}

export function tradingLabChartMarkerLabel(
  kind: TradingLabChartMarkerKind,
): string {
  switch (kind) {
    case 'EXECUTION':
      return '成交'
    case 'OPEN':
      return '开仓'
    case 'ADD':
      return '加仓'
    case 'REDUCE':
      return '减仓'
    case 'TAKE_PROFIT':
      return '止盈'
    case 'STOP_LOSS':
      return '止损'
    case 'FUNDING':
      return '资金费'
    case 'LIQUIDATION':
      return '强平'
    default:
      return fail('CHART_MARKER_INVALID', '未知 marker kind')
  }
}

export function tradingLabChartMarkerGroupId(input: Readonly<{
  source: TradingLabChartSource
  productType: ProductType
  symbol: string
}>): string {
  if (
    !isSource(input.source)
    || !isProductType(input.productType)
    || typeof input.symbol !== 'string'
    || input.symbol.trim().length === 0
  ) {
    fail('CHART_MARKER_INPUT_INVALID', 'marker group identity 无效')
  }
  return [
    'trading-lab-markers',
    input.source,
    input.productType,
    encodeURIComponent(input.symbol),
  ].join(':')
}

function validateWindow(
  firstTimestamp: number | null,
  lastTimestamp: number | null,
): void {
  if (firstTimestamp === null && lastTimestamp === null) {
    return
  }
  if (
    firstTimestamp === null
    || lastTimestamp === null
    || !Number.isSafeInteger(firstTimestamp)
    || !Number.isSafeInteger(lastTimestamp)
    || firstTimestamp > lastTimestamp
  ) {
    fail('CHART_MARKER_WINDOW_INVALID', 'marker bounded window 无效')
  }
}

export function buildTradingLabChartMarkerOverlays(
  input: TradingLabChartMarkerOverlayInput,
): readonly TradingLabChartMarkerOverlay[] {
  if (
    input === null
    || typeof input !== 'object'
    || !isSource(input.source)
    || !isProductType(input.productType)
    || typeof input.symbol !== 'string'
    || input.symbol.trim().length === 0
    || !Array.isArray(input.markers)
  ) {
    fail('CHART_MARKER_INPUT_INVALID', 'marker overlay input 无效')
  }
  validateWindow(input.firstTimestamp, input.lastTimestamp)
  const groupId = tradingLabChartMarkerGroupId(input)
  const replayById = new Map<string, TradingLabChartMarker>()
  const overlays: TradingLabChartMarkerOverlay[] = []

  for (const marker of input.markers) {
    const timestamp = validateMarker(marker)
    const existing = replayById.get(marker.id)
    if (existing !== undefined) {
      if (sameTradingLabChartEvidence(existing, marker)) {
        continue
      }
      fail(
        'CHART_MARKER_ID_CONFLICT',
        `marker id ${marker.id} payload 冲突`,
      )
    }
    replayById.set(marker.id, marker)

    if (
      marker.source !== input.source
      || marker.productType !== input.productType
      || marker.symbol !== input.symbol
      || input.firstTimestamp === null
      || input.lastTimestamp === null
      || timestamp < input.firstTimestamp
      || timestamp > input.lastTimestamp
    ) {
      continue
    }

    const label = tradingLabChartMarkerLabel(marker.kind)
    const points: readonly TradingLabChartMarkerPoint[] = marker.price === null
      ? Object.freeze([
          Object.freeze({ timestamp }),
          Object.freeze({ timestamp }),
        ])
      : Object.freeze([
          Object.freeze({ timestamp, value: marker.price }),
          Object.freeze({ timestamp, value: marker.price }),
        ])
    overlays.push(Object.freeze({
      id: `${groupId}:${encodeURIComponent(marker.id)}`,
      markerId: marker.id,
      groupId,
      name: marker.price === null
        ? 'verticalStraightLine'
        : 'simpleAnnotation',
      source: marker.source,
      kind: marker.kind,
      label,
      timestamp,
      price: marker.price,
      side: marker.side,
      actionId: marker.actionId,
      points,
      extendData: label,
      lock: true,
      visible: true,
    }))
  }
  return Object.freeze(overlays)
}
