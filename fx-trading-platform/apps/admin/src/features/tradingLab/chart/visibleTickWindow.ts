import type { MarketTick } from '../oracle/types.ts'

export const MAX_VISIBLE_TICK_LIMIT = 10_000
export const DEFAULT_VISIBLE_TICK_LIMIT = 10_000

export type VisibleTickWindowRequest = Readonly<{
  limit?: number
  beforeSequence?: number
}>

export type VisibleTickWindow = Readonly<{
  ticks: readonly MarketTick[]
  limit: number
  totalCount: number
  firstSequence: number | null
  lastSequence: number | null
  hasOlder: boolean
  hasNewer: boolean
  olderBeforeSequence: number | null
}>

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function positiveSafeInteger(value: unknown, label: string): number {
  if (
    typeof value !== 'number'
    || !Number.isSafeInteger(value)
    || value <= 0
  ) {
    throw new RangeError(`${label} must be a positive safe integer`)
  }
  return value
}

function visibleLimit(value: unknown): number {
  const limit = positiveSafeInteger(value, 'visible Tick limit')
  if (limit > MAX_VISIBLE_TICK_LIMIT) {
    throw new RangeError(
      `visible Tick limit must not exceed ${MAX_VISIBLE_TICK_LIMIT}`,
    )
  }
  return limit
}

function utcMilliseconds(value: unknown): number {
  if (
    typeof value !== 'string'
    || !/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,3}))?Z$/u
      .test(value)
  ) {
    throw new TypeError('visible Tick virtualTime must be a UTC Z instant')
  }
  const milliseconds = Date.parse(value)
  if (!Number.isFinite(milliseconds)) {
    throw new TypeError('visible Tick virtualTime must be valid')
  }
  const fraction = (
    value.match(/\.(\d{1,3})Z$/u)?.[1] ?? ''
  ).padEnd(3, '0') || '000'
  const normalized = value.replace(/(?:\.\d{1,3})?Z$/u, `.${fraction}Z`)
  if (new Date(milliseconds).toISOString() !== normalized) {
    throw new TypeError('visible Tick virtualTime must be valid')
  }
  return milliseconds
}

export function sameTradingLabChartEvidence(
  left: unknown,
  right: unknown,
): boolean {
  if (Object.is(left, right)) {
    return true
  }
  if (Array.isArray(left) || Array.isArray(right)) {
    return (
      Array.isArray(left)
      && Array.isArray(right)
      && left.length === right.length
      && left.every((value, index) => (
        sameTradingLabChartEvidence(value, right[index])
      ))
    )
  }
  if (!isRecord(left) || !isRecord(right)) {
    return false
  }
  const leftKeys = Object.keys(left).sort()
  const rightKeys = Object.keys(right).sort()
  return (
    leftKeys.length === rightKeys.length
    && leftKeys.every((key, index) => (
      key === rightKeys[index]
      && sameTradingLabChartEvidence(left[key], right[key])
    ))
  )
}

function canonicalTicks(source: readonly MarketTick[]): MarketTick[] {
  if (!Array.isArray(source)) {
    throw new TypeError('visible Tick source must be an array')
  }
  const canonical: MarketTick[] = []
  let previousSequence = 0
  let previousMilliseconds: number | null = null
  let previousTick: MarketTick | null = null

  for (const candidate of source) {
    if (
      !isRecord(candidate)
      || !Array.isArray(candidate.instruments)
      || !Array.isArray(candidate.fundingRates)
    ) {
      throw new TypeError('visible Tick row is malformed')
    }
    const sequence = positiveSafeInteger(
      candidate.sequence,
      'visible Tick sequence',
    )
    const milliseconds = utcMilliseconds(candidate.virtualTime)
    if (sequence < previousSequence) {
      throw new RangeError('visible Tick sequence must be ordered')
    }
    if (sequence === previousSequence) {
      if (
        previousTick === null
        || previousMilliseconds !== milliseconds
        || !sameTradingLabChartEvidence(previousTick, candidate)
      ) {
        throw new Error('visible Tick replay conflicts with its sequence')
      }
      continue
    }
    if (
      previousMilliseconds !== null
      && milliseconds <= previousMilliseconds
    ) {
      throw new RangeError('visible Tick time must be strictly increasing')
    }
    canonical.push(candidate as MarketTick)
    previousSequence = sequence
    previousMilliseconds = milliseconds
    previousTick = candidate as MarketTick
  }
  return canonical
}

function firstIndexAtOrAfter(
  ticks: readonly MarketTick[],
  sequence: number,
): number {
  let low = 0
  let high = ticks.length
  while (low < high) {
    const middle = low + Math.floor((high - low) / 2)
    const candidate = ticks[middle]
    if (candidate !== undefined && candidate.sequence < sequence) {
      low = middle + 1
    } else {
      high = middle
    }
  }
  return low
}

export function selectVisibleTickWindow(
  source: readonly MarketTick[],
  request: VisibleTickWindowRequest = {},
): VisibleTickWindow {
  const limit = visibleLimit(
    request.limit ?? DEFAULT_VISIBLE_TICK_LIMIT,
  )
  const beforeSequence = request.beforeSequence === undefined
    ? null
    : positiveSafeInteger(
        request.beforeSequence,
        'visible Tick beforeSequence',
      )
  const ticks = canonicalTicks(source)
  const end = beforeSequence === null
    ? ticks.length
    : firstIndexAtOrAfter(ticks, beforeSequence)
  const start = Math.max(0, end - limit)
  const visible = ticks.slice(start, end)
  const firstSequence = visible[0]?.sequence ?? null
  const lastSequence = visible.at(-1)?.sequence ?? null
  const hasOlder = start > 0
  const hasNewer = end < ticks.length

  return {
    ticks: visible,
    limit,
    totalCount: ticks.length,
    firstSequence,
    lastSequence,
    hasOlder,
    hasNewer,
    olderBeforeSequence: hasOlder ? firstSequence : null,
  }
}
