import { normalizeScenario } from '../model/normalization.ts'
import type {
  MarketPathDefinition,
  SimpleInstrumentPath,
  TimelineAction,
  TradingLabExecutionPolicy,
  TradingLabInstrumentConfig,
  TradingLabScenario,
} from '../model/types.ts'
import { validateScenario } from '../model/validation.ts'
import {
  add,
  compare,
  decimal,
  divide,
  floorToStep,
  multiply,
  quantize,
  subtract,
  toDecimalString,
} from '../oracle/decimal.ts'
import type { Decimal } from '../oracle/decimal.ts'
import type { MarketTick, SpotInstrumentTick } from '../oracle/types.ts'
import { generateMarketTicks } from './marketPath.ts'
import { createPrng, createPrngFromUtf8Domain } from './prng.ts'

export type DecimalRange = Readonly<{
  min: string
  max: string
}>

export type IntegerRange = Readonly<{
  min: number
  max: number
}>

export type RandomScenarioInput = Readonly<{
  baseScenario: TradingLabScenario
  seed: string
  actionCount: number
  durationSeconds: number
  realistic: boolean
  negativeMode: boolean
  priceRange: DecimalRange
  leverageRange: IntegerRange
  fundingRateRange: DecimalRange
  feeRateRange: DecimalRange
  offsetRangeSteps: IntegerRange
  volatilitySteps: IntegerRange
}>

export class RandomScenarioError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'RandomScenarioError'
  }
}

type Random = () => number

type InstrumentAuthority = Readonly<{
  config: TradingLabInstrumentConfig
  identity: string
  minimumQuantity: Decimal
  negativeOversellQuantity: Decimal | null
}>

const UINT32_SIZE = 0x1_0000_0000
const UINT32_SIZE_BIGINT = 0x1_0000_0000n
const ZERO = decimal('0')
const ONE = decimal('1')
const PUBLIC_ORDER_QUANTITY_SCALE = 8
const LEGACY_PERSISTED_QUANTITY_STEP = decimal('0.0001')
const SIMPLE_SPREAD_STEPS = 2
const SIMPLE_DERIVED_PRICE_MARGIN_STEPS = 1n

function failure(message: string): never {
  throw new RandomScenarioError(message)
}

function identity(
  productType: string,
  symbol: string,
): string {
  return `${productType}\u0000${symbol}`
}

function positiveSafeInteger(value: unknown): value is number {
  return Number.isSafeInteger(value) && (value as number) > 0
}

function nonNegativeSafeInteger(value: unknown): value is number {
  return Number.isSafeInteger(value) && (value as number) >= 0
}

function validateIntegerRange(
  range: IntegerRange,
  field: string,
  positive: boolean,
): void {
  const validMinimum = positive
    ? positiveSafeInteger(range?.min)
    : nonNegativeSafeInteger(range?.min)
  if (
    !validMinimum
    || !nonNegativeSafeInteger(range?.max)
    || range.max < range.min
  ) {
    failure(`${field} must be a valid safe-integer range`)
  }
}

function parsedRange(
  range: DecimalRange,
  field: string,
): readonly [Decimal, Decimal] {
  try {
    const minimum = decimal(range?.min)
    const maximum = decimal(range?.max)
    if (compare(maximum, minimum) < 0) {
      failure(`${field} minimum must not exceed maximum`)
    }
    return [minimum, maximum]
  } catch (error) {
    if (error instanceof RandomScenarioError) {
      throw error
    }
    return failure(`${field} must contain plain decimal strings`)
  }
}

function nextUint32(random: Random): bigint {
  const fraction = random()
  if (
    typeof fraction !== 'number'
    || !Number.isFinite(fraction)
    || fraction < 0
    || fraction >= 1
  ) {
    return failure('PRNG returned a value outside [0, 1)')
  }
  return BigInt(Math.floor(fraction * UINT32_SIZE))
}

function chooseBigInt(
  random: Random,
  minimum: bigint,
  maximum: bigint,
): bigint {
  if (maximum < minimum) {
    return failure('Random integer range is empty')
  }
  const size = maximum - minimum + 1n
  return minimum + (size * nextUint32(random)) / UINT32_SIZE_BIGINT
}

function chooseInteger(
  random: Random,
  range: IntegerRange,
): number {
  return Number(chooseBigInt(
    random,
    BigInt(range.min),
    BigInt(range.max),
  ))
}

function persistedPathSeed(
  inputSeed: string,
  instrument: TradingLabInstrumentConfig,
): string {
  const random = createPrngFromUtf8Domain(
    `${inputSeed}\u0000persisted-path-seed-v1\u0000`
      + `${instrument.productType}\u0000${instrument.symbol}`,
  )
  const digest = Array.from(
    { length: 4 },
    () => nextUint32(random).toString(16).padStart(8, '0'),
  ).join('')
  return `market-path-v1-${digest}`
}

function powerOfTen(exponent: number): bigint {
  return 10n ** BigInt(exponent)
}

function coefficientAtScale(value: Decimal, scale: number): bigint {
  return value.coefficient * powerOfTen(scale - value.scale)
}

function greatestCommonDivisor(left: bigint, right: bigint): bigint {
  let current = left
  let remainder = right
  while (remainder !== 0n) {
    const next = current % remainder
    current = remainder
    remainder = next
  }
  return current
}

function decimalFromCoefficient(
  coefficient: bigint,
  scale: number,
): string {
  const negative = coefficient < 0n
  const absolute = negative ? -coefficient : coefficient
  const digits = absolute.toString().padStart(scale + 1, '0')
  const value = scale === 0
    ? digits
    : `${digits.slice(0, -scale)}.${digits.slice(-scale)}`
  return toDecimalString(decimal(`${negative ? '-' : ''}${value}`))
}

function storageCompatibleQuantityStep(step: Decimal): Decimal {
  const scale = Math.max(step.scale, LEGACY_PERSISTED_QUANTITY_STEP.scale)
  const instrumentUnits = coefficientAtScale(step, scale)
  const persistedUnits = coefficientAtScale(
    LEGACY_PERSISTED_QUANTITY_STEP,
    scale,
  )
  const units = (
    instrumentUnits
    / greatestCommonDivisor(instrumentUnits, persistedUnits)
  ) * persistedUnits
  return decimal(decimalFromCoefficient(units, scale))
}

function chooseDecimal(
  random: Random,
  range: readonly [Decimal, Decimal],
): string {
  const scale = Math.max(range[0].scale, range[1].scale)
  const coefficient = chooseBigInt(
    random,
    coefficientAtScale(range[0], scale),
    coefficientAtScale(range[1], scale),
  )
  return decimalFromCoefficient(coefficient, scale)
}

function exactStepUnits(value: Decimal, step: Decimal, field: string): bigint {
  const units = divide(value, step, 0, 'DOWN')
  if (compare(multiply(units, step), value) !== 0) {
    return failure(`${field} must be aligned to its instrument step`)
  }
  return units.coefficient
}

function priceStepBounds(
  range: readonly [Decimal, Decimal],
  tickSize: Decimal,
  symbol: string,
  generatedMarginSteps: bigint,
): readonly [bigint, bigint] {
  const minimum = divide(range[0], tickSize, 0, 'UP').coefficient
    + generatedMarginSteps
  const maximum = divide(range[1], tickSize, 0, 'DOWN').coefficient
    - generatedMarginSteps
  if (minimum < 2n || maximum < minimum) {
    return failure(`priceRange is infeasible for ${symbol}`)
  }
  return [minimum, maximum]
}

function priceFromSteps(steps: bigint, tickSize: Decimal): string {
  return toDecimalString(multiply(decimal(steps), tickSize))
}

function ceilQuantity(
  instrument: TradingLabInstrumentConfig,
  minimumPrice: Decimal,
  maximumPrice: Decimal,
  policy: TradingLabExecutionPolicy,
): InstrumentAuthority {
  try {
    const step = decimal(instrument.stepSize)
    const minimumQuantity = decimal(instrument.minQty)
    const maximumQuantity = decimal(instrument.maxQty)
    const minimumNotional = instrument.minNotional === null
      ? null
      : decimal(instrument.minNotional)
    const maximumNotional = instrument.maxNotional === null
      ? null
      : decimal(instrument.maxNotional)
    const maximumFillQuantity = policy.maxFillQuantityPerTick === null
      ? null
      : decimal(policy.maxFillQuantityPerTick)
    const slippageRate = decimal(policy.slippageRate)
    const feeRate = decimal(policy.takerFeeRate)
    if (
      compare(slippageRate, decimal('0')) < 0
      || compare(slippageRate, ONE) >= 0
      || compare(feeRate, decimal('0')) < 0
      || compare(feeRate, ONE) >= 0
      || (
        maximumFillQuantity !== null
        && compare(maximumFillQuantity, decimal('0')) <= 0
      )
    ) {
      return failure('Generated execution policy is outside the legal SIMPLE range')
    }
    const minimumExecutionPrice = multiply(
      minimumPrice,
      subtract(ONE, slippageRate),
    )
    const maximumExecutionPrice = multiply(
      maximumPrice,
      add(ONE, slippageRate),
    )
    if (compare(minimumExecutionPrice, decimal('0')) <= 0) {
      return failure(`No positive executable price exists for ${instrument.symbol}`)
    }
    const minimumUnits = exactStepUnits(
      minimumQuantity,
      step,
      `${instrument.symbol}.minQty`,
    )
    const lowerUnits = minimumNotional === null
      ? minimumUnits
      : (() => {
          const notionalUnits = divide(
            minimumNotional,
            multiply(minimumExecutionPrice, step),
            0,
            'UP',
          ).coefficient
          return minimumUnits > notionalUnits ? minimumUnits : notionalUnits
        })()
    const maximumQuantityUnits = divide(
      maximumQuantity,
      step,
      0,
      'DOWN',
    ).coefficient
    const maximumFillUnits = maximumFillQuantity === null
      ? null
      : divide(
          maximumFillQuantity,
          step,
          0,
          'DOWN',
        ).coefficient
    const notionalPrice = instrument.productType === 'CRYPTO_SPOT'
      ? multiply(maximumExecutionPrice, add(ONE, feeRate))
      : maximumExecutionPrice
    const maximumNotionalUnits = maximumNotional === null
      ? null
      : divide(
          maximumNotional,
          multiply(notionalPrice, step),
          0,
          'DOWN',
        ).coefficient
    const upperUnits = [
      maximumQuantityUnits,
      ...(maximumFillUnits === null ? [] : [maximumFillUnits]),
      ...(maximumNotionalUnits === null ? [] : [maximumNotionalUnits]),
    ].reduce((minimum, value) => value < minimum ? value : minimum)
    if (lowerUnits > upperUnits) {
      return failure(
        `No legal generated quantity exists for ${instrument.symbol} `
          + 'after maxQty, maxNotional and maxFill intersection',
      )
    }
    const quantity = multiply(
      decimal(lowerUnits),
      step,
    )
    const negativeOversellQuantity = lowerUnits < upperUnits
      ? multiply(decimal(lowerUnits + 1n), step)
      : null
    return {
      config: instrument,
      identity: identity(instrument.productType, instrument.symbol),
      minimumQuantity: quantity,
      negativeOversellQuantity,
    }
  } catch (error) {
    if (error instanceof RandomScenarioError) {
      throw error
    }
    return failure(`Instrument numeric authority is invalid for ${instrument.symbol}`)
  }
}

function spotBuyBudget(
  authority: InstrumentAuthority,
  tick: MarketTick,
  policy: TradingLabExecutionPolicy,
): Decimal {
  const market = tick.instruments.find((candidate) =>
    candidate.productType === 'CRYPTO_SPOT'
    && candidate.symbol === authority.config.symbol)
  if (market === undefined) {
    return failure(
      `Generated Tick lost Spot authority for ${authority.config.symbol}`,
    )
  }
  const spot = market as SpotInstrumentTick
  const fillPrice = multiply(
    decimal(spot.ask),
    add(ONE, decimal(policy.slippageRate)),
  )
  const feeRate = decimal(policy.takerFeeRate)
  const grossQuote = multiply(authority.minimumQuantity, fillPrice)
  const feeQuote = multiply(grossQuote, feeRate)
  const exactBudget = quantize(
    add(grossQuote, feeQuote),
    PUBLIC_ORDER_QUANTITY_SCALE,
    'UP',
  )
  const backendProjectedBudget = add(
    quantize(grossQuote, PUBLIC_ORDER_QUANTITY_SCALE, 'HALF_UP'),
    quantize(feeQuote, PUBLIC_ORDER_QUANTITY_SCALE, 'HALF_UP'),
  )
  const budget = compare(exactBudget, backendProjectedBudget) >= 0
    ? exactBudget
    : backendProjectedBudget
  const step = decimal(authority.config.stepSize)
  const quotePerBaseWithFee = multiply(fillPrice, add(ONE, feeRate))
  const oracleQuantity = floorToStep(
    divide(
      budget,
      quotePerBaseWithFee,
      step.scale,
      'DOWN',
    ),
    step,
  )
  const storageStep = storageCompatibleQuantityStep(step)
  const backendQuantity = floorToStep(
    divide(
      budget,
      quotePerBaseWithFee,
      24,
      'DOWN',
    ),
    storageStep,
  )
  if (
    compare(oracleQuantity, authority.minimumQuantity) !== 0
    || compare(backendQuantity, authority.minimumQuantity) !== 0
  ) {
    return failure(
      `NUMERIC(24,8) quote budget or storage-compatible persistence `
        + `changes the generated quantity for ${authority.config.symbol}`,
    )
  }
  return budget
}

function authorities(
  scenario: TradingLabScenario,
  priceRange: readonly [Decimal, Decimal],
  policy: TradingLabExecutionPolicy,
): InstrumentAuthority[] {
  if (!Array.isArray(scenario.symbols) || scenario.symbols.length === 0) {
    return failure('baseScenario must select at least one instrument')
  }
  const configured = new Map(
    scenario.configSnapshot.instruments.map((instrument) => [
      identity(instrument.productType, instrument.symbol),
      instrument,
    ]),
  )
  const seen = new Set<string>()
  const result = scenario.symbols.map((selected) => {
    const key = identity(selected.productType, selected.symbol)
    if (seen.has(key)) {
      return failure(`baseScenario contains duplicate instrument ${selected.symbol}`)
    }
    seen.add(key)
    const instrument = configured.get(key)
    if (instrument === undefined) {
      return failure(`baseScenario instrument authority is missing for ${selected.symbol}`)
    }
    return ceilQuantity(instrument, priceRange[0], priceRange[1], policy)
  })
  return result.sort((left, right) =>
    left.identity < right.identity ? -1 : left.identity > right.identity ? 1 : 0)
}

function safeCenterSpeed(
  start: bigint,
  target: bigint,
  durationSeconds: number,
  symbol: string,
): number {
  const distance = start >= target ? start - target : target - start
  const duration = BigInt(durationSeconds)
  const speed = distance === 0n
    ? 1n
    : (distance + duration - 1n) / duration
  if (speed > BigInt(Number.MAX_SAFE_INTEGER)) {
    return failure(`Generated path speed is not a safe integer for ${symbol}`)
  }
  return Number(speed)
}

function generatePaths(
  input: RandomScenarioInput,
  virtualStart: string,
  selected: readonly InstrumentAuthority[],
  priceRange: readonly [Decimal, Decimal],
  fundingRange: readonly [Decimal, Decimal],
): MarketPathDefinition {
  const instruments: SimpleInstrumentPath[] = selected.map((authority) => {
    const instrument = authority.config
    const pathSeed = persistedPathSeed(input.seed, instrument)
    const random = createPrngFromUtf8Domain(
      `${input.seed}\u0000path\u0000${instrument.productType}\u0000${instrument.symbol}`,
    )
    const tickSize = decimal(instrument.tickSize)
    const offsetRangeSteps = chooseInteger(random, input.offsetRangeSteps)
    const volatilitySteps = chooseInteger(random, input.volatilitySteps)
    const realisticMarginSteps = input.realistic
      ? BigInt(offsetRangeSteps) + BigInt(volatilitySteps)
      : 0n
    const bounds = priceStepBounds(
      priceRange,
      tickSize,
      instrument.symbol,
      realisticMarginSteps + SIMPLE_DERIVED_PRICE_MARGIN_STEPS,
    )
    const start = chooseBigInt(random, bounds[0], bounds[1])
    const target = chooseBigInt(random, bounds[0], bounds[1])
    const fundingRate = instrument.productType === 'LINEAR_PERP'
      ? chooseDecimal(random, fundingRange)
      : undefined
    return {
      mode: 'SIMPLE',
      productType: instrument.productType,
      symbol: instrument.symbol,
      seed: pathSeed,
      last: {
        start: priceFromSteps(start, tickSize),
        segments: [{
          target: priceFromSteps(target, tickSize),
          durationSeconds: input.durationSeconds,
          offsetRangeSteps,
          volatilitySteps,
          maxStepPerSecond: safeCenterSpeed(
            start,
            target,
            input.durationSeconds,
            instrument.symbol,
          ),
        }],
      },
      spreadSteps: SIMPLE_SPREAD_STEPS,
      indexOffsetSteps: 0,
      basisSteps: instrument.productType === 'LINEAR_PERP' ? 1 : 0,
      ...(fundingRate === undefined ? {} : { fundingRate }),
    }
  })
  return {
    virtualStart,
    realistic: input.realistic,
    instruments,
  }
}

function selectedLeverage(
  random: Random,
  input: RandomScenarioInput,
  selected: readonly InstrumentAuthority[],
): number {
  const perpetual = selected
    .filter((authority) => authority.config.productType === 'LINEAR_PERP')
  if (perpetual.length === 0) {
    if (input.leverageRange.min > 1 || input.leverageRange.max < 1) {
      return failure(
        'leverageRange has no value allowed by a Spot-only selection',
      )
    }
    return 1
  }
  const maximum = perpetual
    .reduce(
      (limit, authority) =>
        authority.config.maxLeverage < limit
          ? authority.config.maxLeverage
          : limit,
      Number.MAX_SAFE_INTEGER,
    )
  const cappedMaximum = Math.min(input.leverageRange.max, maximum)
  if (cappedMaximum < input.leverageRange.min) {
    return failure('leverageRange has no value allowed by every selected instrument')
  }
  return chooseInteger(random, {
    min: input.leverageRange.min,
    max: cappedMaximum,
  })
}

function executionPolicy(
  random: Random,
  base: TradingLabExecutionPolicy,
  feeRange: readonly [Decimal, Decimal],
): TradingLabExecutionPolicy {
  return {
    ...base,
    matchingMode: 'SIMPLE',
    makerFeeRate: chooseDecimal(random, feeRange),
    takerFeeRate: chooseDecimal(random, feeRange),
  }
}

function marketOrder(
  actionIndex: number,
  tickSequence: number,
  authority: InstrumentAuthority,
  side: 'BUY' | 'SELL',
  leverage: number,
  tick: MarketTick,
  policy: TradingLabExecutionPolicy,
): TimelineAction {
  const spot = authority.config.productType === 'CRYPTO_SPOT'
  const quantity = spot && side === 'BUY'
    ? spotBuyBudget(authority, tick, policy)
    : authority.minimumQuantity
  return {
    id: `generated-action-${String(actionIndex + 1).padStart(4, '0')}`,
    sequence: actionIndex + 1,
    type: 'PLACE_ORDER',
    symbol: authority.config.symbol,
    productType: authority.config.productType,
    trigger: {
      type: 'VIRTUAL_TIME',
      atSecond: tickSequence,
    },
    parameters: {
      side,
      orderType: 'MARKET',
      quantity: toDecimalString(quantity),
      quantityUnit: spot && side === 'BUY' ? 'QUOTE' : 'BASE',
      ...(spot
        ? {}
        : {
            positionSide: 'BOTH',
            marginMode: 'CROSS',
            leverage,
          }),
    },
  }
}

function generateActions(
  random: Random,
  input: RandomScenarioInput,
  selected: readonly InstrumentAuthority[],
  leverage: number,
  ticks: readonly MarketTick[],
  policy: TradingLabExecutionPolicy,
): TimelineAction[] {
  const tickSequences = Array.from(
    { length: input.actionCount },
    () => chooseInteger(random, { min: 1, max: input.durationSeconds }),
  ).sort((left, right) => left - right)
  const generatedSpotUnits = new Map<string, number>()
  const negativeSpot = input.negativeMode
    ? selected.find(
        (authority) => authority.config.productType === 'CRYPTO_SPOT',
      )
    : undefined
  const actions: TimelineAction[] = []
  for (let index = 0; index < input.actionCount; index += 1) {
    const authority = index === 0 && negativeSpot !== undefined
      ? negativeSpot
      : selected[chooseInteger(random, {
          min: 0,
          max: selected.length - 1,
        })]!
    const wantsBuy = chooseInteger(random, { min: 0, max: 1 }) === 0
    let side: 'BUY' | 'SELL' = wantsBuy ? 'BUY' : 'SELL'
    if (authority.config.productType === 'CRYPTO_SPOT') {
      const units = generatedSpotUnits.get(authority.identity) ?? 0
      if (input.negativeMode || units === 0) {
        side = 'BUY'
      }
      generatedSpotUnits.set(
        authority.identity,
        side === 'BUY' ? units + 1 : units - 1,
      )
    }
    actions.push(marketOrder(
      index,
      tickSequences[index]!,
      authority,
      side,
      leverage,
      ticks[tickSequences[index]! - 1]!,
      policy,
    ))
  }
  return actions
}

function generatedBalances(
  actions: readonly TimelineAction[],
  selected: readonly InstrumentAuthority[],
  priceMaximum: Decimal,
  policy: TradingLabExecutionPolicy,
  marketPath: MarketPathDefinition,
  durationSeconds: number,
): Record<string, string> {
  const byIdentity = new Map(selected.map((authority) => [
    authority.identity,
    authority,
  ]))
  const fundingRates = new Map(
    marketPath.instruments
      .filter((path) => path.productType === 'LINEAR_PERP')
      .map((path) => [
        identity(path.productType, path.symbol),
        decimal('fundingRate' in path ? path.fundingRate ?? '0' : '0'),
      ]),
  )
  const maximumFillPrice = multiply(
    priceMaximum,
    add(ONE, decimal(policy.slippageRate)),
  )
  const feeRate = decimal(policy.takerFeeRate)
  const duration = decimal(BigInt(durationSeconds))
  let usdt = ZERO
  for (const action of actions) {
    const authority = byIdentity.get(identity(action.productType, action.symbol))
    if (authority === undefined) {
      return failure(`Generated action lost instrument authority for ${action.symbol}`)
    }
    const quantity = decimal(action.parameters.quantity as string)
    if (
      authority.config.productType === 'CRYPTO_SPOT'
      && action.parameters.side === 'BUY'
    ) {
      usdt = add(usdt, quantity)
      continue
    }
    if (authority.config.productType === 'LINEAR_PERP') {
      const maximumEntryNotional = multiply(quantity, maximumFillPrice)
      const maximumMarkNotional = multiply(quantity, priceMaximum)
      const maximumAdverseLoss = add(
        maximumEntryNotional,
        maximumMarkNotional,
      )
      const maximumFee = multiply(maximumEntryNotional, feeRate)
      const fundingRate = fundingRates.get(authority.identity) ?? ZERO
      const absoluteFundingRate = fundingRate.coefficient < 0n
        ? {
            coefficient: -fundingRate.coefficient,
            scale: fundingRate.scale,
          }
        : fundingRate
      const maximumFunding = multiply(
        multiply(maximumMarkNotional, absoluteFundingRate),
        duration,
      )
      usdt = add(
        usdt,
        add(
          add(maximumAdverseLoss, maximumEntryNotional),
          add(maximumFee, maximumFunding),
        ),
      )
    }
  }
  const result: Record<string, string> = {
    USDT: toDecimalString(quantize(
      usdt,
      PUBLIC_ORDER_QUANTITY_SCALE,
      'UP',
    )),
  }
  for (const authority of selected) {
    if (authority.config.productType !== 'CRYPTO_SPOT') {
      continue
    }
    result[authority.config.baseAsset] = '0'
  }
  return result
}

function injectNegativeOversell(
  actions: TimelineAction[],
  selected: readonly InstrumentAuthority[],
  balances: Record<string, string>,
  firstTick: MarketTick,
  policy: TradingLabExecutionPolicy,
): void {
  const spot = selected.find(
    (authority) => authority.config.productType === 'CRYPTO_SPOT',
  )
  if (spot === undefined) {
    failure('Negative random scenario generation requires a selected Spot instrument')
  }
  const oversell = spot.negativeOversellQuantity
  if (oversell === null) {
    failure(`Spot authority has no legal generated oversell for ${spot.config.symbol}`)
  }
  balances[spot.config.baseAsset] = toDecimalString(spot.minimumQuantity)
  actions[0] = {
    ...marketOrder(0, 1, spot, 'SELL', 1, firstTick, policy),
    parameters: {
      side: 'SELL',
      orderType: 'MARKET',
      quantity: toDecimalString(oversell),
      quantityUnit: 'BASE',
    },
    expectedError: {
      status: 400,
      code: 'INSUFFICIENT_BALANCE',
    },
  }
}

function validateInput(
  input: RandomScenarioInput,
): Readonly<{
  base: TradingLabScenario
  priceRange: readonly [Decimal, Decimal]
  fundingRange: readonly [Decimal, Decimal]
  feeRange: readonly [Decimal, Decimal]
}> {
  if (input === null || typeof input !== 'object') {
    return failure('RandomScenarioInput is required')
  }
  createPrng(input.seed)
  if (
    !positiveSafeInteger(input.actionCount)
    || !positiveSafeInteger(input.durationSeconds)
    || typeof input.realistic !== 'boolean'
    || typeof input.negativeMode !== 'boolean'
  ) {
    return failure('actionCount, durationSeconds and modes are invalid')
  }
  validateIntegerRange(input.leverageRange, 'leverageRange', true)
  validateIntegerRange(input.offsetRangeSteps, 'offsetRangeSteps', false)
  validateIntegerRange(input.volatilitySteps, 'volatilitySteps', false)
  const priceRange = parsedRange(input.priceRange, 'priceRange')
  if (compare(priceRange[0], decimal('0')) <= 0) {
    return failure('priceRange must be positive')
  }
  const fundingRange = parsedRange(input.fundingRateRange, 'fundingRateRange')
  if (fundingRange[0].scale > 10 || fundingRange[1].scale > 10) {
    return failure('fundingRateRange must fit ten decimal places')
  }
  const feeRange = parsedRange(input.feeRateRange, 'feeRateRange')
  if (
    compare(feeRange[0], decimal('0')) < 0
    || compare(feeRange[1], ONE) >= 0
  ) {
    return failure('feeRateRange must stay inside [0, 1)')
  }
  let base: TradingLabScenario
  try {
    base = normalizeScenario(input.baseScenario)
  } catch {
    return failure('baseScenario cannot be normalized')
  }
  return { base, priceRange, fundingRange, feeRange }
}

export function generateRandomScenario(
  input: RandomScenarioInput,
): TradingLabScenario {
  const validated = validateInput(input)
  const random = createPrngFromUtf8Domain(`${input.seed}\u0000scenario`)
  const policy = executionPolicy(
    random,
    validated.base.executionPolicy,
    validated.feeRange,
  )
  const selected = authorities(
    validated.base,
    validated.priceRange,
    policy,
  )
  if (
    input.negativeMode
    && !selected.some(
      (authority) => authority.config.productType === 'CRYPTO_SPOT',
    )
  ) {
    return failure('Negative random scenario generation requires a selected Spot instrument')
  }

  const leverage = selectedLeverage(random, input, selected)
  const marketPath = generatePaths(
    input,
    validated.base.marketPath.virtualStart,
    selected,
    validated.priceRange,
    validated.fundingRange,
  )
  const ticks = generateMarketTicks({
    path: marketPath,
    configSnapshot: validated.base.configSnapshot,
  })
  const actions = generateActions(
    random,
    input,
    selected,
    leverage,
    ticks,
    policy,
  )
  const balances = generatedBalances(
    actions,
    selected,
    validated.priceRange[1],
    policy,
    marketPath,
    input.durationSeconds,
  )
  if (input.negativeMode) {
    injectNegativeOversell(actions, selected, balances, ticks[0]!, policy)
  }

  const scenario = normalizeScenario({
    ...validated.base,
    negativeMode: input.negativeMode,
    seed: input.seed,
    executionPolicy: policy,
    marketPath,
    initialBalances: balances,
    defaults: {
      positionMode: 'ONE_WAY',
      marginMode: 'CROSS',
      leverage,
    },
    timeline: actions,
  })
  const errors = validateScenario(scenario).filter(
    (issue) => issue.severity === 'ERROR',
  )
  if (errors.length > 0) {
    return failure(
      `Generated scenario is invalid: ${errors
        .map((issue) => `${issue.path}:${issue.code}`)
        .join(', ')}`,
    )
  }
  return scenario
}
