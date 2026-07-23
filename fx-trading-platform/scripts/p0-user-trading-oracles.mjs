export const DEMO_RATES = Object.freeze({
  makerFeeRate: '0.0002',
  takerFeeRate: '0.0005',
  slippageRate: '0.0001'
})

export const PERSISTED_QUANTITY_STEP = '0.0001'

const MONEY_SCALE = 8
const ROUNDING = new Set(['DOWN', 'FLOOR', 'CEILING', 'HALF_UP'])
const ZERO = Object.freeze({ units: 0n, scale: 0 })
const MONEY_INCREMENT = Object.freeze({ units: 1n, scale: MONEY_SCALE })

function decimal(value, name = 'value') {
  if (typeof value !== 'string') {
    throw new TypeError(`${name} must be a decimal string`)
  }
  const match = /^([+-]?)(\d+)(?:\.(\d+))?$/.exec(value)
  if (!match) {
    throw new TypeError(`${name} must be a decimal string`)
  }
  const fraction = match[3] ?? ''
  const sign = match[1] === '-' ? -1n : 1n
  return {
    units: sign * BigInt(`${match[2]}${fraction}`),
    scale: fraction.length
  }
}

function pow10(exponent) {
  if (!Number.isSafeInteger(exponent) || exponent < 0) {
    throw new RangeError('decimal scale must be a non-negative safe integer')
  }
  return 10n ** BigInt(exponent)
}

function requireRounding(rounding) {
  if (!ROUNDING.has(rounding)) {
    throw new TypeError(`rounding must be one of ${[...ROUNDING].join(', ')}`)
  }
}

function divideInteger(numerator, denominator, rounding) {
  requireRounding(rounding)
  if (denominator === 0n) throw new RangeError('division by zero')
  if (denominator < 0n) {
    numerator = -numerator
    denominator = -denominator
  }
  const quotient = numerator / denominator
  const remainder = numerator % denominator
  if (remainder === 0n || rounding === 'DOWN') return quotient

  const direction = numerator < 0n ? -1n : 1n
  if (rounding === 'FLOOR') return numerator < 0n ? quotient - 1n : quotient
  if (rounding === 'CEILING') return numerator > 0n ? quotient + 1n : quotient
  return (remainder < 0n ? -remainder : remainder) * 2n >= denominator
    ? quotient + direction
    : quotient
}

function roundFixed(value, scale, rounding) {
  requireRounding(rounding)
  if (!Number.isSafeInteger(scale) || scale < 0) {
    throw new RangeError('decimal scale must be a non-negative safe integer')
  }
  if (value.scale <= scale) {
    return { units: value.units * pow10(scale - value.scale), scale }
  }
  return {
    units: divideInteger(value.units, pow10(value.scale - scale), rounding),
    scale
  }
}

function formatFixed(value) {
  const negative = value.units < 0n
  const digits = (negative ? -value.units : value.units)
    .toString()
    .padStart(value.scale + 1, '0')
  if (value.scale === 0) return `${negative ? '-' : ''}${digits}`
  return `${negative ? '-' : ''}${digits.slice(0, -value.scale)}.${digits.slice(-value.scale)}`
}

function normalizeFixed(value) {
  let { units, scale } = value
  while (scale > 0 && units % 10n === 0n) {
    units /= 10n
    scale -= 1
  }
  return { units, scale }
}

function normalized(value) {
  return formatFixed(normalizeFixed(value))
}

function commonUnits(left, right) {
  const scale = Math.max(left.scale, right.scale)
  return {
    left: left.units * pow10(scale - left.scale),
    right: right.units * pow10(scale - right.scale),
    scale
  }
}

function add(left, right) {
  const aligned = commonUnits(left, right)
  return { units: aligned.left + aligned.right, scale: aligned.scale }
}

function subtract(left, right) {
  const aligned = commonUnits(left, right)
  return { units: aligned.left - aligned.right, scale: aligned.scale }
}

function multiply(left, right) {
  return { units: left.units * right.units, scale: left.scale + right.scale }
}

function compare(left, right) {
  const aligned = commonUnits(left, right)
  return aligned.left < aligned.right ? -1 : aligned.left > aligned.right ? 1 : 0
}

function absolute(value) {
  return { units: value.units < 0n ? -value.units : value.units, scale: value.scale }
}

function minimum(left, right) {
  return compare(left, right) <= 0 ? left : right
}

function maximum(left, right) {
  return compare(left, right) >= 0 ? left : right
}

function divideFixed(numerator, denominator, scale, rounding) {
  if (denominator.units === 0n) throw new RangeError('division by zero')
  const exponent = denominator.scale + scale - numerator.scale
  const scaledNumerator = exponent >= 0
    ? numerator.units * pow10(exponent)
    : numerator.units
  const scaledDenominator = exponent >= 0
    ? denominator.units
    : denominator.units * pow10(-exponent)
  return {
    units: divideInteger(scaledNumerator, scaledDenominator, rounding),
    scale
  }
}

function money(value) {
  return roundFixed(value, MONEY_SCALE, 'HALF_UP')
}

function moneyString(value) {
  return formatFixed(money(value))
}

function positive(value, name) {
  const result = decimal(value, name)
  if (result.units <= 0n) throw new RangeError(`${name} must be positive`)
  return result
}

function nonNegative(value, name) {
  const result = decimal(value, name)
  if (result.units < 0n) throw new RangeError(`${name} must be non-negative`)
  return result
}

function rulesStep(rules) {
  if (!rules || typeof rules !== 'object') throw new TypeError('symbol rules are required')
  return positive(rules.stepSize ?? rules.quantityStep, 'rules.stepSize')
}

function rulesTick(rules) {
  if (!rules || typeof rules !== 'object') throw new TypeError('symbol rules are required')
  return positive(rules.tickSize ?? rules.priceTick, 'rules.tickSize')
}

function greatestCommonDivisor(left, right) {
  left = left < 0n ? -left : left
  right = right < 0n ? -right : right
  while (right !== 0n) {
    const remainder = left % right
    left = right
    right = remainder
  }
  return left
}

function compatibleStep(rules) {
  const instrument = rulesStep(rules)
  const persistence = positive(
    rules.persistenceStep ?? PERSISTED_QUANTITY_STEP,
    'rules.persistenceStep'
  )
  const aligned = commonUnits(instrument, persistence)
  const gcd = greatestCommonDivisor(aligned.left, aligned.right)
  return normalizeFixed({
    units: aligned.left / gcd * aligned.right,
    scale: aligned.scale
  })
}

function isAligned(value, step) {
  const aligned = commonUnits(value, step)
  return aligned.left % aligned.right === 0n
}

function requireAligned(value, step, message = 'quantity must match the effective step') {
  if (!isAligned(value, step)) throw new RangeError(message)
}

function roundToStep(value, step, rounding) {
  if (value.units < 0n) throw new RangeError('step-aligned value must be non-negative')
  const exponent = step.scale - value.scale
  const numerator = exponent >= 0
    ? value.units * pow10(exponent)
    : value.units
  const denominator = exponent >= 0
    ? step.units
    : step.units * pow10(-exponent)
  const stepCount = divideInteger(numerator, denominator, rounding)
  return { units: stepCount * step.units, scale: step.scale }
}

function floorRatioToStep(numerator, denominator, step) {
  const divisor = multiply(denominator, step)
  const exponent = divisor.scale - numerator.scale
  const scaledNumerator = exponent >= 0
    ? numerator.units * pow10(exponent)
    : numerator.units
  const scaledDenominator = exponent >= 0
    ? divisor.units
    : divisor.units * pow10(-exponent)
  const count = divideInteger(scaledNumerator, scaledDenominator, 'FLOOR')
  return { units: count * step.units, scale: step.scale }
}

function directionalDifference(side, exitPrice, entryPrice) {
  if (side === 'LONG') return subtract(exitPrice, entryPrice)
  if (side === 'SHORT') return subtract(entryPrice, exitPrice)
  throw new TypeError('side must be LONG or SHORT')
}

function marketProjection(productType, side, bid, ask) {
  if (productType !== 'CRYPTO_SPOT' && productType !== 'LINEAR_PERP') {
    throw new TypeError('productType must be CRYPTO_SPOT or LINEAR_PERP')
  }
  if (side !== 'BUY' && side !== 'SELL') {
    throw new TypeError('side must be BUY or SELL')
  }
  if (compare(bid, ask) > 0) throw new RangeError('bid must not exceed ask')
  const referencePrice = side === 'BUY' ? ask : bid
  let slippage = multiply(referencePrice, decimal(DEMO_RATES.slippageRate))
  let filledPrice = side === 'BUY'
    ? add(referencePrice, slippage)
    : subtract(referencePrice, slippage)
  if (productType === 'LINEAR_PERP') {
    filledPrice = money(filledPrice)
    slippage = money(side === 'BUY'
      ? subtract(filledPrice, referencePrice)
      : subtract(referencePrice, filledPrice))
  }
  return { referencePrice, filledPrice, slippage }
}

function fieldTolerance(value) {
  return divideFixed(value, decimal('2'), value.scale + 1, 'HALF_UP')
}

function requireQuantity(quantity, rules, name = 'quantity') {
  const parsed = positive(quantity, name)
  const step = compatibleStep(rules)
  requireAligned(parsed, step)
  return parsed
}

export function roundDecimal(value, scale, rounding) {
  return formatFixed(roundFixed(decimal(value), scale, rounding))
}

export function divideDecimal(numerator, denominator, scale, rounding) {
  return formatFixed(divideFixed(
    decimal(numerator, 'numerator'),
    decimal(denominator, 'denominator'),
    scale,
    rounding
  ))
}

export function floorToStep(value, step) {
  return formatFixed(roundToStep(
    nonNegative(value, 'value'),
    positive(step, 'step'),
    'FLOOR'
  ))
}

export function effectiveQuantityStep(rules) {
  return normalized(compatibleStep(rules))
}

export function tolerancesFromRules(rules) {
  const tick = rulesTick(rules)
  const step = compatibleStep(rules)
  return {
    price: normalized(fieldTolerance(tick)),
    quantity: normalized(fieldTolerance(step)),
    amount: normalized(fieldTolerance(MONEY_INCREMENT))
  }
}

export function withinTolerance(actual, expected, tolerance) {
  return compare(
    absolute(subtract(decimal(actual, 'actual'), decimal(expected, 'expected'))),
    nonNegative(tolerance, 'tolerance')
  ) <= 0
}

export function alignPriceToTick(price, rules) {
  return formatFixed(roundToStep(
    positive(price, 'price'),
    rulesTick(rules),
    'FLOOR'
  ))
}

export function marketFillOracle({ productType, side, bid, ask }) {
  const projection = marketProjection(
    productType,
    side,
    positive(bid, 'bid'),
    positive(ask, 'ask')
  )
  const format = productType === 'LINEAR_PERP' ? formatFixed : normalized
  return {
    referencePrice: normalized(projection.referencePrice),
    filledPrice: format(projection.filledPrice),
    slippage: format(projection.slippage),
    slippageRate: DEMO_RATES.slippageRate,
    feeRate: DEMO_RATES.takerFeeRate,
    liquidityRole: 'TAKER'
  }
}

export function quantityFromUnit({ unit, quantity, authorityMark, rules }) {
  const original = positive(quantity, 'quantity')
  const mark = positive(authorityMark, 'authorityMark')
  const step = compatibleStep(rules)
  let baseQuantity
  if (unit === 'BASE') {
    requireAligned(original, step)
    baseQuantity = original
  } else if (unit === 'QUOTE' || unit === 'USDT_NOTIONAL') {
    baseQuantity = floorRatioToStep(original, mark, step)
  } else if (unit === 'CONTRACTS') {
    if (!isAligned(original, decimal('1'))) {
      throw new RangeError('CONTRACTS quantity must be integral')
    }
    baseQuantity = multiply(
      multiply(original, positive(rules.contractSize, 'rules.contractSize')),
      positive(rules.contractMultiplier, 'rules.contractMultiplier')
    )
    requireAligned(baseQuantity, step)
  } else {
    throw new TypeError('unit must be BASE, QUOTE, USDT_NOTIONAL or CONTRACTS')
  }
  if (baseQuantity.units <= 0n) throw new RangeError('quantity converts to zero')
  if (rules.minQty !== undefined
      && compare(baseQuantity, positive(rules.minQty, 'rules.minQty')) < 0) {
    throw new RangeError('quantity is below the symbol minimum')
  }
  if (rules.minNotional !== undefined
      && compare(multiply(baseQuantity, mark), positive(rules.minNotional, 'rules.minNotional')) < 0) {
    throw new RangeError('notional is below the symbol minimum')
  }
  return formatFixed(roundFixed(baseQuantity, step.scale, 'DOWN'))
}

export function spotBuyOracle({
  quoteBudget,
  fillPrice,
  feeRate = DEMO_RATES.takerFeeRate,
  previousGrossQuoteCost = '0',
  previousNetBase = '0',
  rules
}) {
  const budget = positive(quoteBudget, 'quoteBudget')
  const price = positive(fillPrice, 'fillPrice')
  const rate = nonNegative(feeRate, 'feeRate')
  const step = compatibleStep(rules)
  let grossBase = floorRatioToStep(budget, price, step)
  let quoteSpent = money(multiply(grossBase, price))
  while (grossBase.units > 0n && compare(quoteSpent, budget) > 0) {
    grossBase = subtract(grossBase, step)
    quoteSpent = money(multiply(grossBase, price))
  }
  if (grossBase.units <= 0n) throw new RangeError('quote budget is below one effective step')

  const baseFee = money(multiply(grossBase, rate))
  const netBase = money(subtract(grossBase, baseFee))
  const cumulativeGrossQuoteCost = money(add(
    nonNegative(previousGrossQuoteCost, 'previousGrossQuoteCost'),
    quoteSpent
  ))
  const currentNetBase = money(add(
    nonNegative(previousNetBase, 'previousNetBase'),
    netBase
  ))
  const averageCost = divideFixed(
    cumulativeGrossQuoteCost,
    currentNetBase,
    MONEY_SCALE,
    'HALF_UP'
  )
  return {
    effectiveStep: normalized(step),
    grossBase: formatFixed(grossBase),
    quoteSpent: formatFixed(quoteSpent),
    baseFee: formatFixed(baseFee),
    netBase: formatFixed(netBase),
    cumulativeGrossQuoteCost: formatFixed(cumulativeGrossQuoteCost),
    currentNetBase: formatFixed(currentNetBase),
    averageCost: formatFixed(averageCost),
    feeAsset: 'BASE',
    tolerances: tolerancesFromRules(rules)
  }
}

export function spotSellOracle({
  soldBase,
  fillPrice,
  feeRate = DEMO_RATES.takerFeeRate,
  averageCost,
  rules
}) {
  const quantity = requireQuantity(soldBase, rules, 'soldBase')
  const price = positive(fillPrice, 'fillPrice')
  const rate = nonNegative(feeRate, 'feeRate')
  const cost = nonNegative(averageCost, 'averageCost')
  const grossQuote = money(multiply(quantity, price))
  const quoteFee = money(multiply(grossQuote, rate))
  const rawCostBasis = multiply(quantity, cost)
  const costBasis = money(rawCostBasis)
  const realizedPnl = money(subtract(subtract(grossQuote, rawCostBasis), quoteFee))
  return {
    grossQuote: formatFixed(grossQuote),
    quoteFee: formatFixed(quoteFee),
    netQuote: moneyString(subtract(grossQuote, quoteFee)),
    costBasis: formatFixed(costBasis),
    realizedPnl: formatFixed(realizedPnl),
    feeAsset: 'QUOTE',
    tolerances: tolerancesFromRules(rules)
  }
}

export function walletBalanceOracle({ total, available, locked, rules }) {
  const totalValue = decimal(total, 'total')
  const availableValue = decimal(available, 'available')
  const lockedValue = decimal(locked, 'locked')
  const tolerance = tolerancesFromRules(rules).amount
  const balanced = withinTolerance(
    normalized(totalValue),
    normalized(add(availableValue, lockedValue)),
    tolerance
  )
  const nonNegativeBalances = availableValue.units >= 0n && lockedValue.units >= 0n
  return {
    balanced,
    nonNegative: nonNegativeBalances,
    valid: balanced && nonNegativeBalances,
    tolerance
  }
}

export function spotOrderHoldOracle({
  side,
  orderType,
  baseQuantity,
  limitPrice,
  stopTriggerPrice,
  ask,
  baseAsset,
  worstFeeRate = DEMO_RATES.takerFeeRate,
  slippageRate = DEMO_RATES.slippageRate
}) {
  if (side !== 'BUY' && side !== 'SELL') {
    throw new TypeError('side must be BUY or SELL')
  }
  if (!['LIMIT', 'STOP_MARKET', 'OCO'].includes(orderType)) {
    throw new TypeError('orderType must be LIMIT, STOP_MARKET or OCO')
  }
  if (typeof baseAsset !== 'string' || !/^[A-Z0-9]+$/.test(baseAsset)) {
    throw new TypeError('baseAsset must be an uppercase asset code')
  }
  const quantity = positive(baseQuantity, 'baseQuantity')
  const feeMultiplier = add(decimal('1'), nonNegative(worstFeeRate, 'worstFeeRate'))
  const slippageMultiplier = add(decimal('1'), nonNegative(slippageRate, 'slippageRate'))
  const sellHold = {
    amount: formatFixed(roundFixed(quantity, MONEY_SCALE, 'CEILING')),
    currency: baseAsset
  }
  const limitHold = () => side === 'SELL'
    ? sellHold
    : {
        amount: formatFixed(roundFixed(
          multiply(
            multiply(quantity, positive(limitPrice, 'limitPrice')),
            feeMultiplier
          ),
          MONEY_SCALE,
          'CEILING'
        )),
        currency: 'USDT'
      }
  const stopHold = () => {
    if (side === 'SELL') return sellHold
    const worstReference = maximum(
      positive(stopTriggerPrice, 'stopTriggerPrice'),
      positive(ask, 'ask')
    )
    return {
      amount: formatFixed(roundFixed(
        multiply(
          multiply(multiply(quantity, worstReference), slippageMultiplier),
          feeMultiplier
        ),
        MONEY_SCALE,
        'CEILING'
      )),
      currency: 'USDT'
    }
  }

  if (orderType === 'LIMIT') {
    return { ...limitHold(), basis: 'LIMIT', shared: false }
  }
  if (orderType === 'STOP_MARKET') {
    return { ...stopHold(), basis: 'STOP_MARKET', shared: false }
  }
  const limit = limitHold()
  const stop = stopHold()
  const stopIsLarger = compare(decimal(stop.amount), decimal(limit.amount)) > 0
  return {
    ...(stopIsLarger ? stop : limit),
    basis: stopIsLarger ? 'STOP_MARKET' : 'LIMIT',
    shared: true
  }
}

export function fundingSettlementOracle({
  side,
  marginMode,
  quantity,
  markPrice,
  fundingRate,
  balanceBefore,
  marginHeld,
  previousFundingPnl = '0'
}) {
  if (side !== 'LONG' && side !== 'SHORT') {
    throw new TypeError('side must be LONG or SHORT')
  }
  if (marginMode !== 'CROSS' && marginMode !== 'ISOLATED') {
    throw new TypeError('marginMode must be CROSS or ISOLATED')
  }
  const notional = money(multiply(
    positive(quantity, 'quantity'),
    positive(markPrice, 'markPrice')
  ))
  const unsignedCashflow = multiply(notional, decimal(fundingRate, 'fundingRate'))
  const settlementAmount = money(side === 'LONG'
    ? subtract(ZERO, unsignedCashflow)
    : unsignedCashflow)
  const balance = money(nonNegative(balanceBefore, 'balanceBefore'))
  const margin = money(nonNegative(marginHeld, 'marginHeld'))
  const previousFunding = money(decimal(previousFundingPnl, 'previousFundingPnl'))
  const fundingPool = marginMode === 'ISOLATED'
    ? maximum(money(add(margin, previousFunding)), ZERO)
    : balance
  const shortfall = money(maximum(
    subtract(ZERO, add(fundingPool, settlementAmount)),
    ZERO
  ))
  const appliedCashflow = money(add(settlementAmount, shortfall))
  const balanceAfter = marginMode === 'CROSS'
    ? money(maximum(add(balance, appliedCashflow), ZERO))
    : balance
  const isolatedMarginAfter = marginMode === 'ISOLATED'
    ? money(maximum(add(fundingPool, appliedCashflow), ZERO))
    : money(ZERO)
  const fundingPnlAfter = money(add(previousFunding, appliedCashflow))
  const ledgerAmount = settlementAmount.units === 0n
    ? null
    : marginMode === 'CROSS'
      ? formatFixed(settlementAmount)
      : shortfall.units > 0n
        ? formatFixed(subtract(ZERO, shortfall))
        : null
  return {
    notional: formatFixed(notional),
    settlementAmount: formatFixed(settlementAmount),
    appliedCashflow: formatFixed(appliedCashflow),
    shortfall: formatFixed(shortfall),
    balanceAfter: formatFixed(balanceAfter),
    isolatedMarginAfter: formatFixed(isolatedMarginAfter),
    fundingPnlAfter: formatFixed(fundingPnlAfter),
    ledgerAmount
  }
}

export function transferConservationOracle({
  direction,
  amount,
  spotAvailable,
  perpBalance,
  perpEquity,
  perpFreeMargin
}) {
  if (direction !== 'SPOT_TO_PERP' && direction !== 'PERP_TO_SPOT') {
    throw new TypeError('direction must be SPOT_TO_PERP or PERP_TO_SPOT')
  }
  const transfer = money(positive(amount, 'amount'))
  const spot = money(nonNegative(spotAvailable, 'spotAvailable'))
  const balance = money(nonNegative(perpBalance, 'perpBalance'))
  const equity = money(decimal(perpEquity, 'perpEquity'))
  const freeMargin = money(nonNegative(perpFreeMargin, 'perpFreeMargin'))
  if (direction === 'SPOT_TO_PERP' && compare(spot, transfer) < 0) {
    throw new RangeError('transfer amount exceeds Spot available')
  }
  if (direction === 'PERP_TO_SPOT'
    && (compare(balance, transfer) < 0 || compare(freeMargin, transfer) < 0)) {
    throw new RangeError('transfer amount exceeds Perp available')
  }
  const spotAvailableAfter = money(direction === 'SPOT_TO_PERP'
    ? subtract(spot, transfer)
    : add(spot, transfer))
  const perpBalanceAfter = money(direction === 'SPOT_TO_PERP'
    ? add(balance, transfer)
    : subtract(balance, transfer))
  const perpEquityAfter = money(direction === 'SPOT_TO_PERP'
    ? add(equity, transfer)
    : subtract(equity, transfer))
  const perpFreeMarginAfter = money(direction === 'SPOT_TO_PERP'
    ? add(freeMargin, transfer)
    : subtract(freeMargin, transfer))
  return {
    spotAvailableAfter: formatFixed(spotAvailableAfter),
    perpBalanceAfter: formatFixed(perpBalanceAfter),
    perpEquityAfter: formatFixed(perpEquityAfter),
    perpFreeMarginAfter: formatFixed(perpFreeMarginAfter),
    combinedBefore: formatFixed(money(add(spot, balance))),
    combinedAfter: formatFixed(money(add(spotAvailableAfter, perpBalanceAfter)))
  }
}

export function perpOpeningHoldOracle({
  baseQuantity,
  worstPrice,
  leverage,
  worstFeeRate = DEMO_RATES.takerFeeRate,
  rules
}) {
  const quantity = requireQuantity(baseQuantity, rules, 'baseQuantity')
  const price = positive(worstPrice, 'worstPrice')
  const leverageValue = positive(leverage, 'leverage')
  const feeRate = nonNegative(worstFeeRate, 'worstFeeRate')
  const notional = money(multiply(quantity, price))
  const openingInitialMargin = divideFixed(
    notional,
    leverageValue,
    MONEY_SCALE,
    'HALF_UP'
  )
  const feeBuffer = money(multiply(notional, feeRate))
  const holdAmount = money(add(openingInitialMargin, feeBuffer))
  return {
    notional: formatFixed(notional),
    openingInitialMargin: formatFixed(openingInitialMargin),
    feeBuffer: formatFixed(feeBuffer),
    holdAmount: formatFixed(holdAmount),
    holdCurrency: 'USDT',
    tolerances: tolerancesFromRules(rules)
  }
}

export function perpPositionOracle({
  side,
  quantity,
  entryPrice,
  markPrice,
  leverage,
  positionMargin,
  maintenanceMarginRate,
  rules
}) {
  const baseQuantity = requireQuantity(quantity, rules)
  const entry = positive(entryPrice, 'entryPrice')
  const mark = positive(markPrice, 'markPrice')
  const leverageValue = positive(leverage, 'leverage')
  const margin = positive(positionMargin, 'positionMargin')
  const maintenanceRate = nonNegative(maintenanceMarginRate, 'maintenanceMarginRate')
  const entryNotional = money(multiply(baseQuantity, entry))
  const markNotional = money(multiply(baseQuantity, mark))
  const initialMargin = divideFixed(entryNotional, leverageValue, MONEY_SCALE, 'HALF_UP')
  const maintenanceMargin = money(multiply(markNotional, maintenanceRate))
  const unrealizedPnl = money(multiply(
    directionalDifference(side, mark, entry),
    baseQuantity
  ))
  const roiPercent = divideFixed(
    multiply(unrealizedPnl, decimal('100')),
    margin,
    MONEY_SCALE,
    'HALF_UP'
  )
  return {
    entryNotional: formatFixed(entryNotional),
    markNotional: formatFixed(markNotional),
    initialMargin: formatFixed(initialMargin),
    maintenanceMargin: formatFixed(maintenanceMargin),
    unrealizedPnl: formatFixed(unrealizedPnl),
    roiPercent: formatFixed(roiPercent),
    tolerances: tolerancesFromRules(rules)
  }
}

export function perpCloseOracle({
  side,
  quantity,
  entryPrice,
  closeFillPrice,
  closeFeeRate = DEMO_RATES.takerFeeRate,
  openingFee = '0',
  fundingCashflow = '0',
  rules
}) {
  const baseQuantity = requireQuantity(quantity, rules)
  const entry = positive(entryPrice, 'entryPrice')
  const close = positive(closeFillPrice, 'closeFillPrice')
  const grossRealizedPnl = money(multiply(
    directionalDifference(side, close, entry),
    baseQuantity
  ))
  const closeFee = money(multiply(
    multiply(baseQuantity, close),
    nonNegative(closeFeeRate, 'closeFeeRate')
  ))
  const cashDelta = money(add(
    subtract(
      subtract(grossRealizedPnl, nonNegative(openingFee, 'openingFee')),
      closeFee
    ),
    decimal(fundingCashflow, 'fundingCashflow')
  ))
  return {
    grossRealizedPnl: formatFixed(grossRealizedPnl),
    closeFee: formatFixed(closeFee),
    cashDelta: formatFixed(cashDelta),
    tolerances: tolerancesFromRules(rules)
  }
}

export function partialCloseOracle({
  side,
  originalQuantity,
  oldMargin,
  entryPrice,
  closeFillPrice,
  closeFeeRate = DEMO_RATES.takerFeeRate,
  previousPositionRealizedPnl = '0',
  rules
}) {
  const original = requireQuantity(originalQuantity, rules, 'originalQuantity')
  const step = compatibleStep(rules)
  const fraction = decimal('0.30')
  const closed = multiply(original, fraction)
  requireAligned(closed, step, '30% closed quantity must match the effective step')
  const remaining = subtract(original, closed)
  const oldMarginValue = nonNegative(oldMargin, 'oldMargin')
  const releasedMargin = money(multiply(oldMarginValue, fraction))
  const remainingMargin = money(subtract(oldMarginValue, releasedMargin))
  const close = perpCloseOracle({
    side,
    quantity: normalized(closed),
    entryPrice,
    closeFillPrice,
    closeFeeRate,
    rules
  })
  const tradeRealizedPnl = decimal(close.grossRealizedPnl)
  const positionRealizedPnl = money(add(
    decimal(previousPositionRealizedPnl, 'previousPositionRealizedPnl'),
    tradeRealizedPnl
  ))
  return {
    entryPrice: normalized(decimal(entryPrice, 'entryPrice')),
    closedQuantity: formatFixed(roundFixed(closed, step.scale, 'DOWN')),
    remainingQuantity: formatFixed(roundFixed(remaining, step.scale, 'DOWN')),
    releasedMargin: formatFixed(releasedMargin),
    remainingMargin: formatFixed(remainingMargin),
    tradeRealizedPnl: formatFixed(tradeRealizedPnl),
    positionRealizedPnl: formatFixed(positionRealizedPnl),
    closeFee: close.closeFee,
    tolerances: tolerancesFromRules(rules)
  }
}

export function projectedNetOracle({
  side,
  quantity,
  entryPrice,
  closingBid,
  closingAsk,
  executionPath,
  limitPrice,
  openingFee = '0',
  rules
}) {
  const baseQuantity = requireQuantity(quantity, rules)
  const entry = positive(entryPrice, 'entryPrice')
  const bid = positive(closingBid, 'closingBid')
  const ask = positive(closingAsk, 'closingAsk')
  const closingSide = side === 'LONG' ? 'SELL' : side === 'SHORT' ? 'BUY' : null
  if (!closingSide) throw new TypeError('side must be LONG or SHORT')

  const marketPricing = new Set([
    'MARKET',
    'STOP_MARKET',
    'MARKET_PROTECTION',
    'LIQUIDATION'
  ]).has(executionPath)
  let liquidityRole
  let closeFill
  if (marketPricing) {
    liquidityRole = 'TAKER'
    closeFill = marketProjection('LINEAR_PERP', closingSide, bid, ask).filledPrice
  } else if (executionPath === 'IMMEDIATE_LIMIT' || executionPath === 'RESTING_LIMIT') {
    liquidityRole = executionPath === 'RESTING_LIMIT' ? 'MAKER' : 'TAKER'
    const limit = positive(limitPrice, 'limitPrice')
    closeFill = closingSide === 'SELL'
      ? (compare(bid, limit) >= 0 ? bid : limit)
      : (compare(ask, limit) <= 0 ? ask : limit)
  } else {
    throw new TypeError('unsupported executionPath')
  }
  closeFill = money(closeFill)
  const feeRate = decimal(
    liquidityRole === 'MAKER' ? DEMO_RATES.makerFeeRate : DEMO_RATES.takerFeeRate
  )
  const projectedGrossPnl = money(multiply(
    directionalDifference(side, closeFill, entry),
    baseQuantity
  ))
  const projectedCloseFee = money(multiply(
    multiply(baseQuantity, closeFill),
    feeRate
  ))
  const projectedNetFromNow = money(subtract(projectedGrossPnl, projectedCloseFee))
  const projectedWholeTradeNet = money(subtract(
    projectedNetFromNow,
    nonNegative(openingFee, 'openingFee')
  ))
  return {
    liquidityRole,
    projectedCloseFill: formatFixed(closeFill),
    projectedGrossPnl: formatFixed(projectedGrossPnl),
    projectedCloseFee: formatFixed(projectedCloseFee),
    projectedNetFromNow: formatFixed(projectedNetFromNow),
    projectedWholeTradeNet: formatFixed(projectedWholeTradeNet),
    tolerances: tolerancesFromRules(rules)
  }
}

export function isolatedLiquidationOracle({
  side,
  quantity,
  entryPrice,
  markPrice,
  marginHeld,
  fundingPnl = '0',
  maintenanceMarginRate,
  closeTakerFeeRate = DEMO_RATES.takerFeeRate,
  rules
}) {
  const baseQuantity = requireQuantity(quantity, rules)
  const entry = positive(entryPrice, 'entryPrice')
  const mark = positive(markPrice, 'markPrice')
  const margin = nonNegative(marginHeld, 'marginHeld')
  const funding = decimal(fundingPnl, 'fundingPnl')
  const maintenanceRate = nonNegative(maintenanceMarginRate, 'maintenanceMarginRate')
  const closeRate = nonNegative(closeTakerFeeRate, 'closeTakerFeeRate')
  const effectiveIsolatedMargin = money(add(margin, funding))
  const unrealizedPnl = money(multiply(
    directionalDifference(side, mark, entry),
    baseQuantity
  ))
  const markNotional = money(multiply(baseQuantity, mark))
  const maintenanceMargin = money(multiply(markNotional, maintenanceRate))
  const estimatedCloseTakerFee = money(multiply(markNotional, closeRate))
  const isolatedEquity = money(add(effectiveIsolatedMargin, unrealizedPnl))
  const isolatedThreshold = money(add(maintenanceMargin, estimatedCloseTakerFee))
  const rateTotal = add(maintenanceRate, closeRate)
  const denominatorRate = side === 'LONG'
    ? subtract(decimal('1'), rateTotal)
    : add(decimal('1'), rateTotal)
  if (denominatorRate.units <= 0n) {
    throw new RangeError('liquidation denominator must be positive')
  }
  const numerator = side === 'LONG'
    ? subtract(multiply(entry, baseQuantity), effectiveIsolatedMargin)
    : add(multiply(entry, baseQuantity), effectiveIsolatedMargin)
  const estimatedRaw = divideFixed(
    numerator,
    multiply(baseQuantity, denominatorRate),
    MONEY_SCALE,
    'HALF_UP'
  )
  const estimatedLiquidationPrice = compare(estimatedRaw, ZERO) < 0
    ? roundFixed(ZERO, MONEY_SCALE, 'DOWN')
    : estimatedRaw
  return {
    effectiveIsolatedMargin: formatFixed(effectiveIsolatedMargin),
    unrealizedPnl: formatFixed(unrealizedPnl),
    isolatedEquity: formatFixed(isolatedEquity),
    maintenanceMargin: formatFixed(maintenanceMargin),
    estimatedCloseTakerFee: formatFixed(estimatedCloseTakerFee),
    isolatedThreshold: formatFixed(isolatedThreshold),
    estimatedLiquidationPrice: formatFixed(estimatedLiquidationPrice),
    liquidatable: compare(isolatedEquity, isolatedThreshold) <= 0,
    tolerances: tolerancesFromRules(rules)
  }
}

export function crossLiquidationOracle({
  perpBalance,
  isolatedPrincipal = '0',
  positions
}) {
  const balance = nonNegative(perpBalance, 'perpBalance')
  const isolated = nonNegative(isolatedPrincipal, 'isolatedPrincipal')
  if (!Array.isArray(positions) || positions.length === 0) {
    throw new TypeError('positions must be a non-empty array')
  }
  let crossUpl = ZERO
  let crossMaintenance = ZERO
  let estimatedCloseTakerFees = ZERO
  const tolerances = []
  for (const [index, position] of positions.entries()) {
    if (!position?.rules || typeof position.rules !== 'object') {
      throw new TypeError(`positions[${index}].rules are required`)
    }
    const quantity = requireQuantity(
      position.quantity,
      position.rules,
      `positions[${index}].quantity`
    )
    const entry = positive(position.entryPrice, `positions[${index}].entryPrice`)
    const mark = positive(position.markPrice, `positions[${index}].markPrice`)
    const maintenanceRate = nonNegative(
      position.maintenanceMarginRate,
      `positions[${index}].maintenanceMarginRate`
    )
    const closeRate = nonNegative(
      position.closeTakerFeeRate ?? DEMO_RATES.takerFeeRate,
      `positions[${index}].closeTakerFeeRate`
    )
    const markNotional = money(multiply(quantity, mark))
    crossUpl = add(crossUpl, money(multiply(
      directionalDifference(position.side, mark, entry),
      quantity
    )))
    crossMaintenance = add(
      crossMaintenance,
      money(multiply(markNotional, maintenanceRate))
    )
    estimatedCloseTakerFees = add(
      estimatedCloseTakerFees,
      money(multiply(markNotional, closeRate))
    )
    tolerances.push(tolerancesFromRules(position.rules))
  }
  const crossEquity = money(add(subtract(balance, isolated), crossUpl))
  crossMaintenance = money(crossMaintenance)
  estimatedCloseTakerFees = money(estimatedCloseTakerFees)
  const crossThreshold = money(add(crossMaintenance, estimatedCloseTakerFees))
  return {
    crossEquity: formatFixed(crossEquity),
    crossMaintenance: formatFixed(crossMaintenance),
    estimatedCloseTakerFees: formatFixed(estimatedCloseTakerFees),
    crossThreshold: formatFixed(crossThreshold),
    liquidatable: compare(crossEquity, crossThreshold) <= 0,
    tolerances
  }
}

export function liquidationFeeOracle({
  filledQuantity,
  executionPrice,
  liquidationFeeRate,
  collectionCapacity,
  uncoveredCoreDebit = '0',
  rules
}) {
  const nominalLiquidationFee = money(multiply(
    multiply(positive(filledQuantity, 'filledQuantity'), positive(executionPrice, 'executionPrice')),
    nonNegative(liquidationFeeRate, 'liquidationFeeRate')
  ))
  const capacity = money(nonNegative(collectionCapacity, 'collectionCapacity'))
  const chargedLiquidationFee = minimum(nominalLiquidationFee, capacity)
  const uncollectedLiquidationFee = money(subtract(
    nominalLiquidationFee,
    chargedLiquidationFee
  ))
  const bankruptcyShortfall = money(add(
    nonNegative(uncoveredCoreDebit, 'uncoveredCoreDebit'),
    uncollectedLiquidationFee
  ))
  return {
    nominalLiquidationFee: formatFixed(nominalLiquidationFee),
    chargedLiquidationFee: formatFixed(chargedLiquidationFee),
    uncollectedLiquidationFee: formatFixed(uncollectedLiquidationFee),
    bankruptcyShortfall: formatFixed(bankruptcyShortfall),
    tolerance: tolerancesFromRules(rules).amount
  }
}
