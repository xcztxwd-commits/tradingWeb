import type {
  AdvancedPerpetualPath,
  AdvancedSpotPath,
  MarketPathDefinition,
  ProductType,
  ScalarPricePath,
  SimpleInstrumentPath,
  TradingLabConfigSnapshot,
  TradingLabInstrumentConfig,
} from '../model/types.ts'
import {
  compare,
  decimal,
  multiply,
  toDecimalString,
  type Decimal,
} from '../oracle/decimal.ts'
import type {
  InstrumentTick,
  MarketTick,
  PerpetualInstrumentTick,
  SpotInstrumentTick,
} from '../oracle/types.ts'
import {
  PrngSeedError,
  createPrng,
  createPrngFromUtf8Domain,
} from './prng.ts'

export type MarketPathErrorCode =
  | 'MARKET_PATH_VIRTUAL_START_INVALID'
  | 'MARKET_PATH_INSTRUMENT_MISSING'
  | 'MARKET_PATH_INSTRUMENT_EXTRA'
  | 'MARKET_PATH_INSTRUMENT_DUPLICATE'
  | 'MARKET_PATH_DURATION_MISMATCH'
  | 'MARKET_PATH_PRICE_INVALID'
  | 'MARKET_PATH_TARGET_TICK_MISMATCH'
  | 'MARKET_PATH_TARGET_UNREACHABLE'
  | 'MARKET_PATH_BID_ASK_INVALID'
  | 'MARKET_PATH_SEED_INVALID'
  | 'MARKET_PATH_SHAPE_INVALID'
  | 'MARKET_PATH_FUNDING_RATE_INVALID'

export class MarketPathError extends Error {
  readonly code: MarketPathErrorCode

  constructor(code: MarketPathErrorCode, message: string) {
    super(message)
    this.name = 'MarketPathError'
    this.code = code
  }
}

type GeneratedPoint = Readonly<{
  steps: bigint
  isSegmentFinal: boolean
}>

type CompiledInstrument = Readonly<{
  productType: ProductType
  symbol: string
  durationSeconds: number
  ticks: readonly InstrumentTick[]
  fundingRate?: string
}>

const UINT32_RANGE = 4_294_967_296
const UINT32_RANGE_BIGINT = 4_294_967_296n

function fail(code: MarketPathErrorCode, message: string): never {
  throw new MarketPathError(code, message)
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function record(value: unknown, label: string): Record<string, unknown> {
  if (!isRecord(value)) {
    fail('MARKET_PATH_SHAPE_INVALID', `${label} must be an object`)
  }
  return value
}

function compareText(left: string, right: string): -1 | 0 | 1 {
  if (left < right) {
    return -1
  }
  if (left > right) {
    return 1
  }
  return 0
}

function assertExactKeys(
  value: Record<string, unknown>,
  expected: readonly string[],
  label: string,
): void {
  const actual = Object.keys(value).sort()
  const canonicalExpected = [...expected].sort()
  if (
    actual.length !== canonicalExpected.length
    || actual.some((key, index) => key !== canonicalExpected[index])
  ) {
    fail(
      'MARKET_PATH_SHAPE_INVALID',
      `${label} must contain exactly ${canonicalExpected.join(', ')}`,
    )
  }
}

function positiveSafeInteger(value: unknown, label: string): number {
  if (!Number.isSafeInteger(value) || typeof value !== 'number' || value <= 0) {
    fail('MARKET_PATH_SHAPE_INVALID', `${label} must be a positive safe integer`)
  }
  return value
}

function nonNegativeSafeInteger(value: unknown, label: string): number {
  if (!Number.isSafeInteger(value) || typeof value !== 'number' || value < 0) {
    fail('MARKET_PATH_SHAPE_INVALID', `${label} must be a non-negative safe integer`)
  }
  return value
}

function signedSafeInteger(value: unknown, label: string): number {
  if (!Number.isSafeInteger(value) || typeof value !== 'number') {
    fail('MARKET_PATH_SHAPE_INVALID', `${label} must be a signed safe integer`)
  }
  return value
}

function canonicalVirtualStart(value: unknown): number {
  if (typeof value !== 'string') {
    fail('MARKET_PATH_VIRTUAL_START_INVALID', 'virtualStart must be a UTC Z instant')
  }
  const match = value.match(
    /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,3}))?Z$/,
  )
  if (match === null) {
    fail('MARKET_PATH_VIRTUAL_START_INVALID', 'virtualStart must be a UTC Z instant')
  }
  const milliseconds = Date.parse(value)
  if (!Number.isFinite(milliseconds)) {
    fail('MARKET_PATH_VIRTUAL_START_INVALID', 'virtualStart must be a valid UTC Z instant')
  }
  const fraction = (match[7] ?? '').padEnd(3, '0') || '000'
  const normalized = value.replace(/(?:\.\d{1,3})?Z$/, `.${fraction}Z`)
  if (new Date(milliseconds).toISOString() !== normalized) {
    fail('MARKET_PATH_VIRTUAL_START_INVALID', 'virtualStart must be a valid UTC Z instant')
  }
  return milliseconds
}

function instrumentIdentity(productType: ProductType, symbol: string): string {
  return `${productType}\0${symbol}`
}

function pathIdentity(value: Record<string, unknown>, label: string): {
  productType: ProductType
  symbol: string
} {
  const { productType, symbol } = value
  if (productType !== 'CRYPTO_SPOT' && productType !== 'LINEAR_PERP') {
    fail('MARKET_PATH_SHAPE_INVALID', `${label}.productType is invalid`)
  }
  if (typeof symbol !== 'string' || symbol.length === 0 || symbol.trim().length === 0) {
    fail('MARKET_PATH_SHAPE_INVALID', `${label}.symbol must not be blank`)
  }
  return { productType, symbol }
}

function parseTickSize(instrument: TradingLabInstrumentConfig): Decimal {
  try {
    const tick = decimal(instrument.tickSize)
    if (compare(tick, decimal(0n)) <= 0) {
      fail(
        'MARKET_PATH_PRICE_INVALID',
        `${instrument.productType}/${instrument.symbol} tickSize must be positive`,
      )
    }
    return tick
  } catch (error) {
    if (error instanceof MarketPathError) {
      throw error
    }
    fail(
      'MARKET_PATH_PRICE_INVALID',
      `${instrument.productType}/${instrument.symbol} tickSize must be a plain positive decimal`,
    )
  }
}

function powerOfTen(exponent: number): bigint {
  return 10n ** BigInt(exponent)
}

function priceSteps(
  value: unknown,
  tickSize: Decimal,
  label: string,
): bigint {
  let price: Decimal
  try {
    if (typeof value !== 'string') {
      fail('MARKET_PATH_PRICE_INVALID', `${label} must be a plain decimal string`)
    }
    price = decimal(value)
  } catch (error) {
    if (error instanceof MarketPathError) {
      throw error
    }
    fail('MARKET_PATH_PRICE_INVALID', `${label} must be a plain decimal string`)
  }
  if (compare(price, decimal(0n)) <= 0) {
    fail('MARKET_PATH_PRICE_INVALID', `${label} must be positive`)
  }

  const scale = price.scale > tickSize.scale ? price.scale : tickSize.scale
  const priceCoefficient = (
    price.coefficient * powerOfTen(scale - price.scale)
  )
  const tickCoefficient = (
    tickSize.coefficient * powerOfTen(scale - tickSize.scale)
  )
  if (priceCoefficient % tickCoefficient !== 0n) {
    fail(
      'MARKET_PATH_TARGET_TICK_MISMATCH',
      `${label} must be an exact multiple of tickSize`,
    )
  }
  return priceCoefficient / tickCoefficient
}

function priceText(steps: bigint, tickSize: Decimal): string {
  if (steps <= 0n) {
    fail('MARKET_PATH_PRICE_INVALID', 'generated price must stay positive')
  }
  return toDecimalString(multiply(decimal(steps), tickSize))
}

function validateSeed(seed: unknown, label: string): string {
  try {
    if (typeof seed !== 'string') {
      fail('MARKET_PATH_SEED_INVALID', `${label} seed is invalid`)
    }
    createPrng(seed)
    return seed
  } catch (error) {
    if (error instanceof MarketPathError) {
      throw error
    }
    if (error instanceof PrngSeedError) {
      fail('MARKET_PATH_SEED_INVALID', `${label} seed is invalid`)
    }
    throw error
  }
}

function randomSignedSteps(next: () => number, maximum: number): bigint {
  const uint32 = BigInt(next() * UINT32_RANGE)
  const maximumSteps = BigInt(maximum)
  const span = maximumSteps * 2n + 1n
  return (uint32 * span) / UINT32_RANGE_BIGINT - maximumSteps
}

function createDomainPrng(seed: string, label: string): () => number {
  try {
    return createPrngFromUtf8Domain(seed)
  } catch (error) {
    if (error instanceof PrngSeedError) {
      fail('MARKET_PATH_SEED_INVALID', `${label} domain seed is invalid`)
    }
    throw error
  }
}

function absolute(value: bigint): bigint {
  return value < 0n ? -value : value
}

function compileScalarPath(input: Readonly<{
  scalar: ScalarPricePath
  tickSize: Decimal
  realistic: boolean
  seed: string
  productType: ProductType
  symbol: string
  lane: string
}>): readonly GeneratedPoint[] {
  const scalarValue = record(input.scalar, `${input.lane} path`)
  assertExactKeys(scalarValue, ['start', 'segments'], `${input.lane} path`)
  if (!Array.isArray(scalarValue.segments) || scalarValue.segments.length === 0) {
    fail('MARKET_PATH_SHAPE_INVALID', `${input.lane}.segments must not be empty`)
  }

  let segmentStart = priceSteps(
    scalarValue.start,
    input.tickSize,
    `${input.lane}.start`,
  )
  const points: GeneratedPoint[] = []
  let totalDuration = 0

  for (let segmentIndex = 0; segmentIndex < scalarValue.segments.length; segmentIndex += 1) {
    const segment = record(
      scalarValue.segments[segmentIndex],
      `${input.lane}.segments[${segmentIndex}]`,
    )
    assertExactKeys(
      segment,
      [
        'target',
        'durationSeconds',
        'offsetRangeSteps',
        'volatilitySteps',
        'maxStepPerSecond',
      ],
      `${input.lane}.segments[${segmentIndex}]`,
    )
    const duration = positiveSafeInteger(
      segment.durationSeconds,
      `${input.lane}.segments[${segmentIndex}].durationSeconds`,
    )
    const offsetRange = nonNegativeSafeInteger(
      segment.offsetRangeSteps,
      `${input.lane}.segments[${segmentIndex}].offsetRangeSteps`,
    )
    const volatility = nonNegativeSafeInteger(
      segment.volatilitySteps,
      `${input.lane}.segments[${segmentIndex}].volatilitySteps`,
    )
    const maxStep = positiveSafeInteger(
      segment.maxStepPerSecond,
      `${input.lane}.segments[${segmentIndex}].maxStepPerSecond`,
    )
    if (!Number.isSafeInteger(totalDuration + duration)) {
      fail('MARKET_PATH_SHAPE_INVALID', `${input.lane} duration is too large`)
    }
    totalDuration += duration

    const target = priceSteps(
      segment.target,
      input.tickSize,
      `${input.lane}.segments[${segmentIndex}].target`,
    )
    const delta = target - segmentStart
    const distance = absolute(delta)
    const requiredStep = (
      distance + BigInt(duration) - 1n
    ) / BigInt(duration)
    if (requiredStep > BigInt(maxStep)) {
      fail(
        'MARKET_PATH_TARGET_UNREACHABLE',
        `${input.lane}.segments[${segmentIndex}] exceeds maxStepPerSecond`,
      )
    }

    const next = input.realistic
      ? createDomainPrng(
          `${input.seed}\0${input.productType}\0${input.symbol}\0${input.lane}\0${segmentIndex}`,
          `${input.productType}/${input.symbol}/${input.lane}/${segmentIndex}`,
        )
      : null
    const segmentOffset = next === null
      ? 0n
      : randomSignedSteps(next, offsetRange)
    const direction = delta < 0n ? -1n : 1n

    for (let second = 1; second <= duration; second += 1) {
      const isSegmentFinal = second === duration
      let generated = (
        segmentStart
        + direction * ((distance * BigInt(second)) / BigInt(duration))
      )
      if (next !== null && !isSegmentFinal) {
        generated += (
          segmentOffset * BigInt(duration - second)
        ) / BigInt(duration)
        generated += randomSignedSteps(next, volatility)
        if (generated < 1n) {
          generated = 1n
        }
      }
      if (isSegmentFinal) {
        generated = target
      }
      points.push({ steps: generated, isSegmentFinal })
    }
    segmentStart = target
  }

  return points
}

function canonicalFundingRate(value: unknown, label: string): string {
  try {
    if (typeof value !== 'string') {
      fail('MARKET_PATH_FUNDING_RATE_INVALID', `${label} must be a decimal string`)
    }
    return toDecimalString(decimal(value))
  } catch (error) {
    if (error instanceof MarketPathError) {
      throw error
    }
    fail('MARKET_PATH_FUNDING_RATE_INVALID', `${label} must be a decimal string`)
  }
}

function simpleTicks(
  path: SimpleInstrumentPath,
  instrument: TradingLabInstrumentConfig,
  tickSize: Decimal,
  realistic: boolean,
  seed: string,
): CompiledInstrument {
  const pathValue = record(path, `${path.productType}/${path.symbol}`)
  if (
    path.productType === 'LINEAR_PERP'
    && !Object.hasOwn(pathValue, 'fundingRate')
  ) {
    fail(
      'MARKET_PATH_FUNDING_RATE_INVALID',
      `${path.productType}/${path.symbol} fundingRate is missing`,
    )
  }
  assertExactKeys(
    pathValue,
    path.productType === 'LINEAR_PERP'
      ? [
          'mode',
          'productType',
          'symbol',
          'seed',
          'last',
          'spreadSteps',
          'indexOffsetSteps',
          'basisSteps',
          'fundingRate',
        ]
      : [
          'mode',
          'productType',
          'symbol',
          'seed',
          'last',
          'spreadSteps',
          'indexOffsetSteps',
          'basisSteps',
        ],
    `${path.productType}/${path.symbol}`,
  )
  const spreadSteps = positiveSafeInteger(pathValue.spreadSteps, 'spreadSteps')
  const indexOffsetSteps = signedSafeInteger(pathValue.indexOffsetSteps, 'indexOffsetSteps')
  const basisSteps = signedSafeInteger(pathValue.basisSteps, 'basisSteps')
  const lastPoints = compileScalarPath({
    scalar: path.last,
    tickSize,
    realistic,
    seed,
    productType: path.productType,
    symbol: path.symbol,
    lane: 'last',
  })
  const halfSpread = BigInt(spreadSteps) / 2n
  const ticks = lastPoints.map(({ steps: last }): InstrumentTick => {
    const bid = last - halfSpread
    const ask = bid + BigInt(spreadSteps)
    if (bid <= 0n || ask <= 0n) {
      fail(
        'MARKET_PATH_PRICE_INVALID',
        `${path.productType}/${path.symbol} derived bid/ask must stay positive`,
      )
    }
    if (path.productType === 'CRYPTO_SPOT') {
      const tick: SpotInstrumentTick = {
        productType: 'CRYPTO_SPOT',
        symbol: path.symbol,
        bid: priceText(bid, tickSize),
        ask: priceText(ask, tickSize),
        last: priceText(last, tickSize),
      }
      return tick
    }

    const index = last + BigInt(indexOffsetSteps)
    const mark = index + BigInt(basisSteps)
    if (index <= 0n || mark <= 0n) {
      fail(
        'MARKET_PATH_PRICE_INVALID',
        `${path.productType}/${path.symbol} derived index/mark must stay positive`,
      )
    }
    const tick: PerpetualInstrumentTick = {
      productType: 'LINEAR_PERP',
      symbol: path.symbol,
      bid: priceText(bid, tickSize),
      ask: priceText(ask, tickSize),
      last: priceText(last, tickSize),
      mark: priceText(mark, tickSize),
      index: priceText(index, tickSize),
    }
    return tick
  })
  const fundingRate = path.productType === 'LINEAR_PERP'
    ? canonicalFundingRate(pathValue.fundingRate, `${path.symbol}.fundingRate`)
    : undefined

  return {
    productType: instrument.productType,
    symbol: instrument.symbol,
    durationSeconds: ticks.length,
    ticks,
    ...(fundingRate === undefined ? {} : { fundingRate }),
  }
}

function advancedSpotTicks(
  path: AdvancedSpotPath,
  instrument: TradingLabInstrumentConfig,
  tickSize: Decimal,
  realistic: boolean,
  seed: string,
): CompiledInstrument {
  const pathValue = record(path, `${path.productType}/${path.symbol}`)
  assertExactKeys(
    pathValue,
    ['mode', 'productType', 'symbol', 'seed', 'prices'],
    `${path.productType}/${path.symbol}`,
  )
  const prices = record(pathValue.prices, `${path.symbol}.prices`)
  assertExactKeys(prices, ['bid', 'ask', 'last'], `${path.symbol}.prices`)
  const bid = compileScalarPath({
    scalar: path.prices.bid,
    tickSize,
    realistic,
    seed,
    productType: path.productType,
    symbol: path.symbol,
    lane: 'bid',
  })
  const ask = compileScalarPath({
    scalar: path.prices.ask,
    tickSize,
    realistic,
    seed,
    productType: path.productType,
    symbol: path.symbol,
    lane: 'ask',
  })
  const last = compileScalarPath({
    scalar: path.prices.last,
    tickSize,
    realistic,
    seed,
    productType: path.productType,
    symbol: path.symbol,
    lane: 'last',
  })
  if (bid.length !== ask.length || bid.length !== last.length) {
    fail(
      'MARKET_PATH_DURATION_MISMATCH',
      `${path.productType}/${path.symbol} lanes must have equal duration`,
    )
  }

  const ticks = bid.map((bidPoint, index): SpotInstrumentTick => {
    const askPoint = ask[index]
    const lastPoint = last[index]
    if (askPoint === undefined || lastPoint === undefined) {
      fail('MARKET_PATH_DURATION_MISMATCH', `${path.symbol} lane is incomplete`)
    }
    let askSteps = askPoint.steps
    if (bidPoint.steps >= askSteps) {
      if (bidPoint.isSegmentFinal || askPoint.isSegmentFinal) {
        fail(
          'MARKET_PATH_BID_ASK_INVALID',
          `${path.productType}/${path.symbol} has a crossed segment-final spread`,
        )
      }
      askSteps = bidPoint.steps + 1n
    }
    return {
      productType: 'CRYPTO_SPOT',
      symbol: path.symbol,
      bid: priceText(bidPoint.steps, tickSize),
      ask: priceText(askSteps, tickSize),
      last: priceText(lastPoint.steps, tickSize),
    }
  })

  return {
    productType: instrument.productType,
    symbol: instrument.symbol,
    durationSeconds: ticks.length,
    ticks,
  }
}

function advancedPerpetualTicks(
  path: AdvancedPerpetualPath,
  instrument: TradingLabInstrumentConfig,
  tickSize: Decimal,
  realistic: boolean,
  seed: string,
): CompiledInstrument {
  const pathValue = record(path, `${path.productType}/${path.symbol}`)
  if (!Object.hasOwn(pathValue, 'fundingRate')) {
    fail(
      'MARKET_PATH_FUNDING_RATE_INVALID',
      `${path.productType}/${path.symbol} fundingRate is missing`,
    )
  }
  assertExactKeys(
    pathValue,
    ['mode', 'productType', 'symbol', 'seed', 'fundingRate', 'prices'],
    `${path.productType}/${path.symbol}`,
  )
  const prices = record(pathValue.prices, `${path.symbol}.prices`)
  assertExactKeys(
    prices,
    ['bid', 'ask', 'last', 'mark', 'index'],
    `${path.symbol}.prices`,
  )
  const lanes = {
    bid: compileScalarPath({
      scalar: path.prices.bid,
      tickSize,
      realistic,
      seed,
      productType: path.productType,
      symbol: path.symbol,
      lane: 'bid',
    }),
    ask: compileScalarPath({
      scalar: path.prices.ask,
      tickSize,
      realistic,
      seed,
      productType: path.productType,
      symbol: path.symbol,
      lane: 'ask',
    }),
    last: compileScalarPath({
      scalar: path.prices.last,
      tickSize,
      realistic,
      seed,
      productType: path.productType,
      symbol: path.symbol,
      lane: 'last',
    }),
    mark: compileScalarPath({
      scalar: path.prices.mark,
      tickSize,
      realistic,
      seed,
      productType: path.productType,
      symbol: path.symbol,
      lane: 'mark',
    }),
    index: compileScalarPath({
      scalar: path.prices.index,
      tickSize,
      realistic,
      seed,
      productType: path.productType,
      symbol: path.symbol,
      lane: 'index',
    }),
  } as const
  const duration = lanes.bid.length
  if (Object.values(lanes).some((lane) => lane.length !== duration)) {
    fail(
      'MARKET_PATH_DURATION_MISMATCH',
      `${path.productType}/${path.symbol} lanes must have equal duration`,
    )
  }

  const ticks = lanes.bid.map((bidPoint, index): PerpetualInstrumentTick => {
    const askPoint = lanes.ask[index]
    const lastPoint = lanes.last[index]
    const markPoint = lanes.mark[index]
    const indexPoint = lanes.index[index]
    if (
      askPoint === undefined
      || lastPoint === undefined
      || markPoint === undefined
      || indexPoint === undefined
    ) {
      fail('MARKET_PATH_DURATION_MISMATCH', `${path.symbol} lane is incomplete`)
    }
    let askSteps = askPoint.steps
    if (bidPoint.steps >= askSteps) {
      if (bidPoint.isSegmentFinal || askPoint.isSegmentFinal) {
        fail(
          'MARKET_PATH_BID_ASK_INVALID',
          `${path.productType}/${path.symbol} has a crossed segment-final spread`,
        )
      }
      askSteps = bidPoint.steps + 1n
    }
    return {
      productType: 'LINEAR_PERP',
      symbol: path.symbol,
      bid: priceText(bidPoint.steps, tickSize),
      ask: priceText(askSteps, tickSize),
      last: priceText(lastPoint.steps, tickSize),
      mark: priceText(markPoint.steps, tickSize),
      index: priceText(indexPoint.steps, tickSize),
    }
  })

  return {
    productType: instrument.productType,
    symbol: instrument.symbol,
    durationSeconds: ticks.length,
    ticks,
    fundingRate: canonicalFundingRate(
      pathValue.fundingRate,
      `${path.symbol}.fundingRate`,
    ),
  }
}

function compileInstrument(
  path: MarketPathDefinition['instruments'][number],
  instrument: TradingLabInstrumentConfig,
  realistic: boolean,
): CompiledInstrument {
  const value = record(path, `${instrument.productType}/${instrument.symbol}`)
  if (value.mode !== 'SIMPLE' && value.mode !== 'ADVANCED') {
    fail('MARKET_PATH_SHAPE_INVALID', `${instrument.symbol}.mode is invalid`)
  }
  const seed = validateSeed(value.seed, `${instrument.productType}/${instrument.symbol}`)
  const tickSize = parseTickSize(instrument)
  if (value.mode === 'SIMPLE') {
    return simpleTicks(
      path as SimpleInstrumentPath,
      instrument,
      tickSize,
      realistic,
      seed,
    )
  }
  if (instrument.productType === 'CRYPTO_SPOT') {
    return advancedSpotTicks(
      path as AdvancedSpotPath,
      instrument,
      tickSize,
      realistic,
      seed,
    )
  }
  return advancedPerpetualTicks(
    path as AdvancedPerpetualPath,
    instrument,
    tickSize,
    realistic,
    seed,
  )
}

function validateConfigInstruments(
  configSnapshot: TradingLabConfigSnapshot,
): Map<string, TradingLabInstrumentConfig> {
  const configValue = record(configSnapshot, 'configSnapshot')
  if (!Array.isArray(configValue.instruments) || configValue.instruments.length === 0) {
    fail('MARKET_PATH_SHAPE_INVALID', 'configSnapshot.instruments must not be empty')
  }
  const byIdentity = new Map<string, TradingLabInstrumentConfig>()
  for (let index = 0; index < configValue.instruments.length; index += 1) {
    const value = record(configValue.instruments[index], `configSnapshot.instruments[${index}]`)
    const identity = pathIdentity(value, `configSnapshot.instruments[${index}]`)
    const key = instrumentIdentity(identity.productType, identity.symbol)
    if (byIdentity.has(key)) {
      fail(
        'MARKET_PATH_INSTRUMENT_DUPLICATE',
        `configSnapshot contains duplicate ${identity.productType}/${identity.symbol}`,
      )
    }
    byIdentity.set(key, value as TradingLabInstrumentConfig)
  }
  return byIdentity
}

export function generateMarketTicks(input: Readonly<{
  path: MarketPathDefinition
  configSnapshot: TradingLabConfigSnapshot
}>): readonly MarketTick[] {
  const inputValue = record(input, 'input')
  assertExactKeys(inputValue, ['path', 'configSnapshot'], 'input')
  const pathValue = record(inputValue.path, 'path')
  assertExactKeys(pathValue, ['virtualStart', 'realistic', 'instruments'], 'path')
  if (typeof pathValue.realistic !== 'boolean') {
    fail('MARKET_PATH_SHAPE_INVALID', 'path.realistic must be boolean')
  }
  if (!Array.isArray(pathValue.instruments) || pathValue.instruments.length === 0) {
    fail('MARKET_PATH_SHAPE_INVALID', 'path.instruments must not be empty')
  }
  const virtualStartMilliseconds = canonicalVirtualStart(pathValue.virtualStart)
  const configByIdentity = validateConfigInstruments(input.configSnapshot)
  const pathByIdentity = new Map<
    string,
    MarketPathDefinition['instruments'][number]
  >()

  for (let index = 0; index < pathValue.instruments.length; index += 1) {
    const value = record(pathValue.instruments[index], `path.instruments[${index}]`)
    const identity = pathIdentity(value, `path.instruments[${index}]`)
    const key = instrumentIdentity(identity.productType, identity.symbol)
    if (pathByIdentity.has(key)) {
      fail(
        'MARKET_PATH_INSTRUMENT_DUPLICATE',
        `path contains duplicate ${identity.productType}/${identity.symbol}`,
      )
    }
    pathByIdentity.set(
      key,
      value as MarketPathDefinition['instruments'][number],
    )
  }

  for (const [key, instrumentPath] of pathByIdentity) {
    if (!configByIdentity.has(key)) {
      fail(
        'MARKET_PATH_INSTRUMENT_EXTRA',
        `path contains extra ${instrumentPath.productType}/${instrumentPath.symbol}`,
      )
    }
  }
  const sortedPaths = [...pathByIdentity.entries()].sort((left, right) => (
    compareText(left[1].productType, right[1].productType)
    || compareText(left[1].symbol, right[1].symbol)
  ))
  const compiled = sortedPaths.map(([identity, instrumentPath]) => {
    const instrument = configByIdentity.get(identity)
    if (instrument === undefined) {
      fail(
        'MARKET_PATH_INSTRUMENT_EXTRA',
        `path has no authority for ${instrumentPath.productType}/${instrumentPath.symbol}`,
      )
    }
    return compileInstrument(
      instrumentPath,
      instrument,
      pathValue.realistic as boolean,
    )
  })
  const durationSeconds = compiled[0]?.durationSeconds
  if (
    durationSeconds === undefined
    || compiled.some((instrument) => instrument.durationSeconds !== durationSeconds)
  ) {
    fail(
      'MARKET_PATH_DURATION_MISMATCH',
      'every selected instrument must have the same duration',
    )
  }

  const finalMilliseconds = virtualStartMilliseconds + durationSeconds * 1000
  if (
    !Number.isSafeInteger(finalMilliseconds)
    || !Number.isFinite(finalMilliseconds)
  ) {
    fail(
      'MARKET_PATH_VIRTUAL_START_INVALID',
      'virtualStart plus path duration must be a valid instant',
    )
  }
  try {
    new Date(finalMilliseconds).toISOString()
  } catch {
    fail(
      'MARKET_PATH_VIRTUAL_START_INVALID',
      'virtualStart plus path duration must be a valid instant',
    )
  }

  const fundingRates = compiled
    .filter((instrument) => instrument.productType === 'LINEAR_PERP')
    .map((instrument) => {
      if (instrument.fundingRate === undefined) {
        fail(
          'MARKET_PATH_FUNDING_RATE_INVALID',
          `${instrument.symbol} fundingRate is missing`,
        )
      }
      return {
        symbol: instrument.symbol,
        rate: instrument.fundingRate,
      }
    })
    .sort((left, right) => compareText(left.symbol, right.symbol))

  return Array.from({ length: durationSeconds }, (_, index): MarketTick => ({
    sequence: index + 1,
    virtualTime: new Date(
      virtualStartMilliseconds + (index + 1) * 1000,
    ).toISOString(),
    instruments: compiled.map((instrument) => {
      const tick = instrument.ticks[index]
      if (tick === undefined) {
        fail('MARKET_PATH_DURATION_MISMATCH', `${instrument.symbol} Tick is missing`)
      }
      return { ...tick }
    }),
    fundingRates: fundingRates.map((fundingRate) => ({ ...fundingRate })),
  }))
}
