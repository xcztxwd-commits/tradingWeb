const CANONICAL_DURABLE_ID = /^(?:0|[1-9]\d*)$/u
const UTC_Z_INSTANT =
  /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,3}))?Z$/u
const MAX_DURABLE_ID = 9_223_372_036_854_775_807n
const MAX_PUBLIC_DEPTH = 32
const MAX_PUBLIC_COLLECTION = 2_048
const MAX_PUBLIC_STRING = 64 * 1024
const MAX_DISPLAY_CHARACTERS = 512
const FORBIDDEN_OBJECT_KEYS = new Set([
  '__proto__',
  'constructor',
  'prototype',
])

export const TRADING_LAB_ACTUAL_STATE_PAGE_SIZE = 50

export const TRADING_LAB_ACTUAL_STATE_GROUPS = Object.freeze([
  'summary',
  'walletBalances',
  'assetLedger',
  'cashLedger',
  'orders',
  'trades',
  'positions',
  'fundingSettlements',
] as const)

export type TradingLabActualStateGroup =
  (typeof TRADING_LAB_ACTUAL_STATE_GROUPS)[number]

export type TradingLabActualStatePrimitive = string | number | boolean | null
export type TradingLabActualStateValue =
  | TradingLabActualStatePrimitive
  | readonly TradingLabActualStateValue[]
  | { readonly [key: string]: TradingLabActualStateValue }
export type TradingLabActualStateObject = Readonly<{
  [key: string]: TradingLabActualStateValue
}>

export type TradingLabActualState = Readonly<{
  summary: TradingLabActualStateObject
  walletBalances: readonly TradingLabActualStateValue[]
  assetLedger: readonly TradingLabActualStateValue[]
  cashLedger: readonly TradingLabActualStateValue[]
  orders: readonly TradingLabActualStateValue[]
  trades: readonly TradingLabActualStateValue[]
  positions: readonly TradingLabActualStateValue[]
  fundingSettlements: readonly TradingLabActualStateValue[]
}>

export type TradingLabActualStateSnapshot = Readonly<{
  availability: 'AVAILABLE'
  sourceEventId: string
  tickSequence: number
  virtualTime: string
  correlationId: string | null
  state: TradingLabActualState
}>

export type TradingLabActualStateUnavailableReason =
  | 'NO_CHECKPOINT'
  | 'MALFORMED_CHECKPOINT'

export type TradingLabActualStateUnavailable = Readonly<{
  availability: 'UNAVAILABLE'
  reason: TradingLabActualStateUnavailableReason
  sourceEventId: string | null
  tickSequence: number | null
  virtualTime: string | null
}>

export type TradingLabActualStateResult =
  | TradingLabActualStateSnapshot
  | TradingLabActualStateUnavailable

export type TradingLabActualStatePage<Row> = Readonly<{
  page: number
  pageCount: number
  totalRows: number
  rows: readonly Row[]
}>

export function createTradingLabActualStateUnavailable(
  reason: TradingLabActualStateUnavailableReason,
  metadata: Readonly<{
    sourceEventId?: string | null
    tickSequence?: number | null
    virtualTime?: string | null
  }> = {},
): TradingLabActualStateUnavailable {
  return Object.freeze({
    availability: 'UNAVAILABLE',
    reason,
    sourceEventId: metadata.sourceEventId ?? null,
    tickSequence: metadata.tickSequence ?? null,
    virtualTime: metadata.virtualTime ?? null,
  })
}

export function projectTradingLabActualState(
  event: unknown,
): TradingLabActualStateResult {
  const eventRecord = plainRecord(event)
  const sourceEventId = durableEventId(eventRecord?.id)
  const data = plainRecord(eventRecord?.data)
  const payload = plainRecord(data?.payload)
  const tickSequence = positiveSafeInteger(payload?.tickSequence)
  const virtualTime = utcInstant(data?.virtualTime)
  const malformed = () => createTradingLabActualStateUnavailable(
    'MALFORMED_CHECKPOINT',
    { sourceEventId, tickSequence, virtualTime },
  )

  if (
    eventRecord === null
    || eventRecord.name !== 'checkpoint'
    || sourceEventId === null
    || data === null
    || payload === null
    || tickSequence === null
    || virtualTime === null
  ) {
    return malformed()
  }

  const root = plainRecord(payload.state)
  if (
    root === null
    || !sameKeys(root, TRADING_LAB_ACTUAL_STATE_GROUPS)
    || plainRecord(root.summary) === null
    || !TRADING_LAB_ACTUAL_STATE_GROUPS
      .slice(1)
      .every((group) => Array.isArray(root[group]))
  ) {
    return malformed()
  }

  try {
    const cloned = clonePublicValue(root, 0)
    const state = plainRecord(cloned)
    if (state === null) return malformed()
    return Object.freeze({
      availability: 'AVAILABLE',
      sourceEventId,
      tickSequence,
      virtualTime,
      correlationId: boundedIdentityText(data.correlationId),
      state: state as TradingLabActualState,
    })
  } catch {
    return malformed()
  }
}

export function paginateTradingLabActualStateRows<Row>(
  rows: readonly Row[],
  requestedPage: number,
): TradingLabActualStatePage<Row> {
  const totalRows = rows.length
  const pageCount = Math.max(
    1,
    Math.ceil(totalRows / TRADING_LAB_ACTUAL_STATE_PAGE_SIZE),
  )
  const normalizedPage = Number.isSafeInteger(requestedPage)
    && requestedPage > 0
    ? Math.min(requestedPage, pageCount)
    : 1
  const offset = (normalizedPage - 1) * TRADING_LAB_ACTUAL_STATE_PAGE_SIZE
  return Object.freeze({
    page: normalizedPage,
    pageCount,
    totalRows,
    rows: Object.freeze(
      rows.slice(offset, offset + TRADING_LAB_ACTUAL_STATE_PAGE_SIZE),
    ),
  })
}

export function formatTradingLabActualStateValue(
  value: TradingLabActualStateValue,
): string {
  const text = typeof value === 'string'
    ? value
    : value === null
      ? 'null'
      : typeof value === 'object'
        ? JSON.stringify(value)
        : String(value)
  return text.length <= MAX_DISPLAY_CHARACTERS
    ? text
    : `${text.slice(0, MAX_DISPLAY_CHARACTERS)}…`
}

function clonePublicValue(
  value: unknown,
  depth: number,
): TradingLabActualStateValue {
  if (
    value === null
    || typeof value === 'boolean'
    || (typeof value === 'number' && Number.isFinite(value))
  ) {
    return value
  }
  if (typeof value === 'string') {
    if (value.length > MAX_PUBLIC_STRING) throw new TypeError('string')
    return value
  }
  if (depth >= MAX_PUBLIC_DEPTH) throw new TypeError('depth')
  if (Array.isArray(value)) {
    if (value.length > MAX_PUBLIC_COLLECTION) throw new TypeError('collection')
    return Object.freeze(
      value.map((child) => clonePublicValue(child, depth + 1)),
    )
  }
  const record = plainRecord(value)
  if (record === null) throw new TypeError('object')
  const entries = Object.entries(record)
  if (entries.length > MAX_PUBLIC_COLLECTION) {
    throw new TypeError('collection')
  }
  const clone: Record<string, TradingLabActualStateValue> = {}
  for (const [key, child] of entries) {
    if (
      key.length > MAX_PUBLIC_STRING
      || FORBIDDEN_OBJECT_KEYS.has(key)
    ) {
      throw new TypeError('key')
    }
    Object.defineProperty(clone, key, {
      configurable: false,
      enumerable: true,
      writable: false,
      value: clonePublicValue(child, depth + 1),
    })
  }
  return Object.freeze(clone)
}

function durableEventId(value: unknown): string | null {
  if (
    typeof value !== 'string'
    || !CANONICAL_DURABLE_ID.test(value)
  ) {
    return null
  }
  return BigInt(value) <= MAX_DURABLE_ID ? value : null
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

function utcInstant(value: unknown): string | null {
  if (typeof value !== 'string') return null
  const match = value.match(UTC_Z_INSTANT)
  if (match === null) return null
  const milliseconds = Date.parse(value)
  if (!Number.isFinite(milliseconds)) return null
  const fraction = (match[7] ?? '').padEnd(3, '0') || '000'
  const canonical = value.replace(
    /(?:\.\d{1,3})?Z$/u,
    `.${fraction}Z`,
  )
  return new Date(milliseconds).toISOString() === canonical ? value : null
}

function boundedIdentityText(value: unknown): string | null {
  if (typeof value !== 'string') return null
  const normalized = value.trim()
  return normalized.length > 0 && normalized.length <= MAX_PUBLIC_STRING
    ? normalized
    : null
}

function sameKeys(
  value: Record<string, unknown>,
  expected: readonly string[],
): boolean {
  const keys = Object.keys(value)
  return keys.length === expected.length
    && keys.every((key) => expected.includes(key))
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
