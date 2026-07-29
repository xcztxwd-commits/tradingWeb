import type { InstrumentTick, MarketTick } from '../oracle/types.ts'
import {
  compare,
  decimal,
} from '../oracle/decimal.ts'
import {
  type TradingLabChartMarker,
  type TradingLabChartMarkerKind,
} from './chartMarkers.ts'
import {
  MAX_VISIBLE_TICK_LIMIT,
  sameTradingLabChartEvidence,
  selectVisibleTickWindow,
} from './visibleTickWindow.ts'

export const MAX_TRADING_LAB_ACTUAL_TICKS = MAX_VISIBLE_TICK_LIMIT

export type TradingLabRunChartEvidence = Readonly<{
  actualTicks: readonly MarketTick[]
  markers: readonly TradingLabChartMarker[]
  markerProofs: readonly TradingLabChartMarkerProof[]
  lastTickSequence: number | null
  lastVirtualTime: string | null
}>

export type TradingLabChartMarkerProof = Readonly<{
  id: string
  evidence: string
}>

export type TradingLabRunChartEvidenceEvent = Readonly<{
  id: string
  name: string
  data: unknown
}>

export type TradingLabRunChartEvidenceErrorCode =
  | 'TRADING_LAB_CHART_TICK_MALFORMED'
  | 'TRADING_LAB_CHART_TICK_CONFLICT'
  | 'TRADING_LAB_CHART_TICK_SEQUENCE_GAP'
  | 'TRADING_LAB_CHART_TICK_TIME_ORDER'
  | 'TRADING_LAB_CHART_MARKER_CONFLICT'

export class TradingLabRunChartEvidenceError extends Error {
  readonly code: TradingLabRunChartEvidenceErrorCode

  constructor(
    code: TradingLabRunChartEvidenceErrorCode,
    message: string,
  ) {
    super(message)
    this.name = 'TradingLabRunChartEvidenceError'
    this.code = code
  }
}

const EMPTY_TICKS = Object.freeze([]) as readonly MarketTick[]
const EMPTY_MARKERS =
  Object.freeze([]) as readonly TradingLabChartMarker[]
const EMPTY_MARKER_PROOFS =
  Object.freeze([]) as readonly TradingLabChartMarkerProof[]
const ZERO = decimal('0')

export const EMPTY_TRADING_LAB_RUN_CHART_EVIDENCE =
  Object.freeze<TradingLabRunChartEvidence>({
    actualTicks: EMPTY_TICKS,
    markers: EMPTY_MARKERS,
    markerProofs: EMPTY_MARKER_PROOFS,
    lastTickSequence: null,
    lastVirtualTime: null,
  })

export function createTradingLabRunChartEvidence():
TradingLabRunChartEvidence {
  return EMPTY_TRADING_LAB_RUN_CHART_EVIDENCE
}

export function reduceTradingLabRunChartEvidence(
  evidence: TradingLabRunChartEvidence,
  event: TradingLabRunChartEvidenceEvent,
): TradingLabRunChartEvidence {
  if (event.name === 'tick') {
    return reduceTick(evidence, event.data)
  }
  if (event.name === 'checkpoint') {
    return reduceCheckpointMarkers(evidence, event.data)
  }
  return evidence
}

function reduceTick(
  evidence: TradingLabRunChartEvidence,
  dataValue: unknown,
): TradingLabRunChartEvidence {
  const tick = actualTick(dataValue)
  const existing = evidence.actualTicks.find(
    (candidate) => candidate.sequence === tick.sequence,
  )
  if (existing !== undefined) {
    if (sameTradingLabChartEvidence(existing, tick)) {
      return evidence
    }
    fail(
      'TRADING_LAB_CHART_TICK_CONFLICT',
      `ACTUAL Tick ${tick.sequence} durable replay conflicts`,
    )
  }
  if (
    evidence.lastTickSequence !== null
    && tick.sequence <= evidence.lastTickSequence
  ) {
    fail(
      'TRADING_LAB_CHART_TICK_CONFLICT',
      `ACTUAL Tick ${tick.sequence} is outside the retained replay window`,
    )
  }
  const expectedSequence = evidence.lastTickSequence === null
    ? 1
    : evidence.lastTickSequence + 1
  if (tick.sequence !== expectedSequence) {
    fail(
      'TRADING_LAB_CHART_TICK_SEQUENCE_GAP',
      `ACTUAL Tick sequence expected ${expectedSequence}`,
    )
  }
  if (
    evidence.lastVirtualTime !== null
    && utcMilliseconds(tick.virtualTime)
      <= utcMilliseconds(evidence.lastVirtualTime)
  ) {
    fail(
      'TRADING_LAB_CHART_TICK_TIME_ORDER',
      'ACTUAL Tick virtualTime must be strictly increasing',
    )
  }

  const appended = [...evidence.actualTicks, tick]
  const visible = appended.length > MAX_TRADING_LAB_ACTUAL_TICKS
    ? selectVisibleTickWindow(appended, {
        limit: MAX_TRADING_LAB_ACTUAL_TICKS,
      }).ticks
    : appended
  return Object.freeze({
    ...evidence,
    actualTicks: Object.freeze([...visible]),
    lastTickSequence: tick.sequence,
    lastVirtualTime: tick.virtualTime,
  })
}

function actualTick(dataValue: unknown): MarketTick {
  const data = plainRecord(dataValue)
  const payload = plainRecord(data?.payload)
  const sequence = positiveSafeInteger(payload?.tickSequence)
  const virtualTime = utcInstant(data?.virtualTime)
  const rawInstruments = payload?.instruments
  if (
    data === null
    || payload === null
    || sequence === null
    || virtualTime === null
    || !Array.isArray(rawInstruments)
    || rawInstruments.length === 0
    || rawInstruments.length > 2_048
  ) {
    return malformedTick()
  }

  const instruments: InstrumentTick[] = []
  const identities = new Set<string>()
  for (const raw of rawInstruments) {
    const instrument = actualInstrument(raw)
    const identity = `${instrument.productType}:${instrument.symbol}`
    if (identities.has(identity)) {
      return malformedTick()
    }
    identities.add(identity)
    instruments.push(instrument)
  }
  return Object.freeze({
    sequence,
    virtualTime,
    instruments: Object.freeze(instruments),
    fundingRates: Object.freeze([]),
  })
}

function actualInstrument(value: unknown): InstrumentTick {
  const record = plainRecord(value)
  if (record?.productType === 'CRYPTO_SPOT') {
    if (!sameKeys(record, [
      'productType',
      'symbol',
      'bid',
      'ask',
      'last',
    ])) {
      return malformedTick()
    }
    return Object.freeze({
      productType: 'CRYPTO_SPOT',
      symbol: requiredText(record.symbol),
      bid: positiveDecimal(record.bid),
      ask: positiveDecimal(record.ask),
      last: positiveDecimal(record.last),
    })
  }
  if (record?.productType === 'LINEAR_PERP') {
    if (!sameKeys(record, [
      'productType',
      'symbol',
      'bid',
      'ask',
      'last',
      'mark',
      'index',
    ])) {
      return malformedTick()
    }
    return Object.freeze({
      productType: 'LINEAR_PERP',
      symbol: requiredText(record.symbol),
      bid: positiveDecimal(record.bid),
      ask: positiveDecimal(record.ask),
      last: positiveDecimal(record.last),
      mark: positiveDecimal(record.mark),
      index: positiveDecimal(record.index),
    })
  }
  return malformedTick()
}

function reduceCheckpointMarkers(
  evidence: TradingLabRunChartEvidence,
  dataValue: unknown,
): TradingLabRunChartEvidence {
  const data = plainRecord(dataValue)
  const payload = plainRecord(data?.payload)
  const state = plainRecord(payload?.state)
  const tickSequence = positiveSafeInteger(payload?.tickSequence)
  if (state === null || tickSequence === null) {
    return evidence
  }

  const ordersById = orderEvidenceById(stateRows(state.orders))
  const candidates: MarkerCandidate[] = []
  for (const row of stateRows(state.trades)) {
    const marker = tradeMarker(row, ordersById, tickSequence)
    if (marker !== null) candidates.push(marker)
  }
  for (const row of stateRows(state.fundingSettlements)) {
    const marker = fundingMarker(row, tickSequence)
    if (marker !== null) candidates.push(marker)
  }
  if (candidates.length === 0) {
    return evidence
  }

  const byId = new Map(
    evidence.markers.map((marker) => [marker.id, marker]),
  )
  const proofById = new Map(
    evidence.markerProofs.map((proof) => [proof.id, proof.evidence]),
  )
  const appended = [...evidence.markers]
  const appendedProofs = [...evidence.markerProofs]
  let changed = false
  for (const candidate of candidates) {
    const existing = byId.get(candidate.marker.id)
    if (existing !== undefined) {
      if (
        !sameMarkerEvidence(existing, candidate.marker)
        || proofById.get(candidate.marker.id) !== candidate.proof
      ) {
        fail(
          'TRADING_LAB_CHART_MARKER_CONFLICT',
          `ACTUAL marker ${candidate.marker.id} durable replay conflicts`,
        )
      }
      continue
    }
    byId.set(candidate.marker.id, candidate.marker)
    proofById.set(candidate.marker.id, candidate.proof)
    appended.push(candidate.marker)
    appendedProofs.push(Object.freeze({
      id: candidate.marker.id,
      evidence: candidate.proof,
    }))
    changed = true
  }
  return changed
    ? Object.freeze({
        ...evidence,
        markers: Object.freeze(appended),
        markerProofs: Object.freeze(appendedProofs),
      })
    : evidence
}

type OrderMarkerEvidence = Readonly<{
  origin: string | null
  protectionType: string | null
  reduceOnly: boolean | null
}>

type MarkerCandidate = Readonly<{
  marker: TradingLabChartMarker
  proof: string
}>

function orderEvidenceById(
  rows: readonly unknown[],
): Map<string, OrderMarkerEvidence> {
  const result = new Map<string, OrderMarkerEvidence>()
  for (const raw of rows) {
    const row = plainRecord(raw)
    const id = optionalText(row?.id)
    if (row === null || id === null) continue
    const candidate = Object.freeze({
      origin: optionalText(row.origin),
      protectionType: optionalText(row.protectionType),
      reduceOnly: typeof row.reduceOnly === 'boolean'
        ? row.reduceOnly
        : null,
    })
    const existing = result.get(id)
    if (
      existing !== undefined
      && !sameTradingLabChartEvidence(existing, candidate)
    ) {
      fail(
        'TRADING_LAB_CHART_MARKER_CONFLICT',
        `ACTUAL order ${id} durable replay conflicts`,
      )
    }
    result.set(id, candidate)
  }
  return result
}

function tradeMarker(
  value: unknown,
  ordersById: ReadonlyMap<string, OrderMarkerEvidence>,
  tickSequence: number,
): MarkerCandidate | null {
  const row = plainRecord(value)
  const id = optionalText(row?.id)
  const orderId = optionalText(row?.orderId)
  const productType = markerProductType(row?.productType)
  const symbol = optionalText(row?.symbol)
  const side = markerSide(row?.side)
  const virtualTime = utcInstant(row?.executedAt)
  if (
    row === null
    || id === null
    || orderId === null
    || productType === null
    || symbol === null
    || side === null
    || virtualTime === null
  ) {
    return null
  }
  const order = ordersById.get(orderId)
  const marker = Object.freeze<TradingLabChartMarker>({
    id: `trade:${id}`,
    source: 'ACTUAL',
    kind: markerKind(order),
    productType,
    symbol,
    tickSequence,
    virtualTime,
    price: markerPrice(row.price),
    side,
    actionId: null,
  })
  return Object.freeze({
    marker,
    proof: JSON.stringify([
      'TRADE',
      id,
      orderId,
      productType,
      symbol,
      side,
      scalarProof(row.price),
      virtualTime,
      order?.origin ?? null,
      order?.protectionType ?? null,
      order?.reduceOnly ?? null,
    ]),
  })
}

function fundingMarker(
  value: unknown,
  tickSequence: number,
): MarkerCandidate | null {
  const row = plainRecord(value)
  const id = optionalText(row?.id)
  const symbol = optionalText(row?.symbol)
  const virtualTime = utcInstant(row?.fundingTime)
  if (
    row === null
    || id === null
    || symbol === null
    || virtualTime === null
  ) {
    return null
  }
  const marker = Object.freeze<TradingLabChartMarker>({
    id: `funding:${id}`,
    source: 'ACTUAL',
    kind: 'FUNDING',
    productType: 'LINEAR_PERP',
    symbol,
    tickSequence,
    virtualTime,
    price: markerPrice(row.markPrice),
    side: null,
    actionId: null,
  })
  return Object.freeze({
    marker,
    proof: JSON.stringify([
      'FUNDING',
      id,
      symbol,
      virtualTime,
      scalarProof(row.markPrice),
    ]),
  })
}

function markerKind(
  order: OrderMarkerEvidence | undefined,
): TradingLabChartMarkerKind {
  if (order?.origin === 'LIQUIDATION') return 'LIQUIDATION'
  if (order?.protectionType === 'TAKE_PROFIT') return 'TAKE_PROFIT'
  if (order?.protectionType === 'STOP_LOSS') return 'STOP_LOSS'
  if (order?.reduceOnly === true) return 'REDUCE'
  return 'EXECUTION'
}

function sameMarkerEvidence(
  left: TradingLabChartMarker,
  right: TradingLabChartMarker,
): boolean {
  return sameTradingLabChartEvidence(
    {
      ...left,
      tickSequence: 0,
    },
    {
      ...right,
      tickSequence: 0,
    },
  )
}

function markerProductType(
  value: unknown,
): 'CRYPTO_SPOT' | 'LINEAR_PERP' | null {
  return value === 'CRYPTO_SPOT' || value === 'LINEAR_PERP'
    ? value
    : null
}

function markerSide(value: unknown): 'BUY' | 'SELL' | null {
  return value === 'BUY' || value === 'SELL' ? value : null
}

function positiveDecimal(value: unknown): string {
  const parsed = optionalPositiveDecimal(value)
  return parsed ?? malformedTick()
}

function optionalPositiveDecimal(value: unknown): string | null {
  if (typeof value !== 'string' || value.length > 128) return null
  try {
    return compare(decimal(value), ZERO) > 0 ? value : null
  } catch {
    return null
  }
}

function markerPrice(value: unknown): string | null {
  return optionalPositiveDecimal(value)
}

function scalarProof(value: unknown): string {
  if (value === null) return 'null'
  if (value === undefined) return 'undefined'
  if (typeof value === 'string') return `string:${value}`
  if (typeof value === 'number' && Number.isFinite(value)) {
    return `number:${value}`
  }
  if (typeof value === 'boolean') return `boolean:${value}`
  return `unsupported:${Object.prototype.toString.call(value)}`
}

function stateRows(value: unknown): readonly unknown[] {
  if (Array.isArray(value)) return value
  const page = plainRecord(value)
  if (
    page === null
    || page.complete !== true
    || !Array.isArray(page.items)
    || !Number.isSafeInteger(page.total)
    || (page.total as number) < 0
    || page.total !== page.items.length
  ) {
    return []
  }
  return page.items
}

function requiredText(value: unknown): string {
  return optionalText(value) ?? malformedTick()
}

function optionalText(value: unknown): string | null {
  if (typeof value !== 'string') return null
  const normalized = value.trim()
  return normalized.length > 0 && normalized.length <= 256
    ? normalized
    : null
}

function utcInstant(value: unknown): string | null {
  if (typeof value !== 'string') return null
  try {
    utcMilliseconds(value)
    return value
  } catch {
    return null
  }
}

function utcMilliseconds(value: unknown): number {
  if (
    typeof value !== 'string'
    || !/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,3}))?Z$/u
      .test(value)
  ) {
    throw new TypeError('Trading Lab chart time must be a UTC Z instant')
  }
  const milliseconds = Date.parse(value)
  if (!Number.isFinite(milliseconds)) {
    throw new TypeError('Trading Lab chart time must be valid')
  }
  const fraction = (
    value.match(/\.(\d{1,3})Z$/u)?.[1] ?? ''
  ).padEnd(3, '0') || '000'
  const normalized = value.replace(/(?:\.\d{1,3})?Z$/u, `.${fraction}Z`)
  if (new Date(milliseconds).toISOString() !== normalized) {
    throw new TypeError('Trading Lab chart time must be valid')
  }
  return milliseconds
}

function positiveSafeInteger(value: unknown): number | null {
  return (
    typeof value === 'number'
    && Number.isSafeInteger(value)
    && value > 0
  )
    ? value
    : null
}

function sameKeys(
  value: Record<string, unknown>,
  expected: readonly string[],
): boolean {
  const keys = Object.keys(value)
  return (
    keys.length === expected.length
    && keys.every((key) => expected.includes(key))
  )
}

function plainRecord(value: unknown): Record<string, unknown> | null {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    return null
  }
  const prototype = Object.getPrototypeOf(value)
  return prototype === Object.prototype || prototype === null
    ? value as Record<string, unknown>
    : null
}

function malformedTick(): never {
  return fail(
    'TRADING_LAB_CHART_TICK_MALFORMED',
    'Durable ACTUAL MARKET_TICK evidence is malformed',
  )
}

function fail(
  code: TradingLabRunChartEvidenceErrorCode,
  message: string,
): never {
  throw new TradingLabRunChartEvidenceError(code, message)
}
