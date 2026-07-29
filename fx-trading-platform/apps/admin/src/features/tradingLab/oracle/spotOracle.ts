import type {
  TimelineAction,
  TradingLabExecutionPolicy,
  TradingLabInstrumentConfig,
} from '../model/types.ts'
import {
  add,
  compare,
  decimal,
  divide,
  floorToStep,
  multiply,
  subtract,
  toDecimalString,
  type Decimal,
} from './decimal.ts'
import {
  OracleExecutionError,
  type MutableOracleState,
  type SpotInstrumentTick,
  type SpotPositionState,
} from './types.ts'
import { availableMarginUsdt } from './riskOracle.ts'

const ZERO = decimal(0n)
const ONE = decimal(1n)
const DIVISION_SCALE = 18
const SPOT_KEY_PREFIX = 'CRYPTO_SPOT\u0000'
const SUPPORTED_PARAMETERS = new Set([
  'orderType',
  'quantity',
  'quantityUnit',
  'side',
])

type SpotSide = 'BUY' | 'SELL'
type QuantityUnit = 'BASE' | 'QUOTE'

type ParsedOrder = Readonly<{
  side: SpotSide
  quantity: Decimal
  quantityUnit: QuantityUnit
}>

type CalculatedFill = Readonly<{
  baseQuantity: Decimal
  fillPrice: Decimal
  notionalUsdt: Decimal
  feeUsdt: Decimal
  quoteCash: Decimal
}>

function fail(
  action: TimelineAction,
  code: string,
  message: string,
  field = '',
): never {
  const suffix = field === '' ? '' : `.${field}`
  throw new OracleExecutionError({
    path: `timeline.${action.id}${suffix}`,
    code,
    message,
  })
}

function parseDecimal(
  action: TimelineAction,
  value: unknown,
  field: string,
): Decimal {
  if (typeof value !== 'string') {
    return fail(action, 'ORACLE_DECIMAL_INVALID', `${field} 必须是普通十进制字符串`, field)
  }
  try {
    return decimal(value)
  } catch {
    return fail(action, 'ORACLE_DECIMAL_INVALID', `${field} 必须是普通十进制字符串`, field)
  }
}

function requirePositive(
  action: TimelineAction,
  value: Decimal,
  field: string,
): Decimal {
  if (compare(value, ZERO) <= 0) {
    return fail(action, 'ORACLE_QUANTITY_INVALID', `${field} 必须大于 0`, field)
  }
  return value
}

function optionalPositive(
  action: TimelineAction,
  value: unknown,
  field: string,
): Decimal | null {
  if (value === null) {
    return null
  }
  return requirePositive(action, parseDecimal(action, value, field), field)
}

function requireNonNegative(
  action: TimelineAction,
  value: Decimal,
  field: string,
): Decimal {
  if (compare(value, ZERO) < 0) {
    return fail(action, 'ORACLE_RATE_INVALID', `${field} 不得小于 0`, field)
  }
  return value
}

function calculationScale(...values: readonly Decimal[]): number {
  let scale = DIVISION_SCALE
  for (const value of values) {
    if (value.scale > scale) {
      scale = value.scale
    }
  }
  return scale
}

function requireStepAligned(
  action: TimelineAction,
  quantity: Decimal,
  step: Decimal,
): void {
  if (compare(floorToStep(quantity, step), quantity) !== 0) {
    fail(
      action,
      'ORACLE_QUANTITY_STEP_MISMATCH',
      'quantity 不符合 instrument stepSize',
      'parameters.quantity',
    )
  }
}

function parseOrder(action: TimelineAction): ParsedOrder {
  if (action.type !== 'PLACE_ORDER') {
    return fail(action, 'ORACLE_ACTION_UNSUPPORTED', `Spot Oracle 不支持 action ${action.type}`)
  }
  if (action.overrides !== undefined && Object.keys(action.overrides).length > 0) {
    return fail(action, 'ORACLE_OVERRIDE_UNSUPPORTED', 'Spot MARKET 不支持 overrides', 'overrides')
  }

  for (const field of Object.keys(action.parameters)) {
    if (!SUPPORTED_PARAMETERS.has(field)) {
      return fail(
        action,
        'ORACLE_ACTION_PARAMETER_UNSUPPORTED',
        `Spot MARKET 不支持 parameter ${field}`,
        `parameters.${field}`,
      )
    }
  }

  const { orderType, quantity, quantityUnit, side } = action.parameters
  if (orderType !== 'MARKET') {
    return fail(
      action,
      'ORACLE_ORDER_TYPE_UNSUPPORTED',
      `Spot Oracle 仅支持 MARKET，不支持 ${String(orderType)}`,
      'parameters.orderType',
    )
  }
  if (side !== 'BUY' && side !== 'SELL') {
    return fail(action, 'ORACLE_SIDE_INVALID', 'side 必须是 BUY 或 SELL', 'parameters.side')
  }
  if (quantityUnit !== 'BASE' && quantityUnit !== 'QUOTE') {
    return fail(
      action,
      'ORACLE_QUANTITY_UNIT_UNSUPPORTED',
      'quantityUnit 必须是 BASE 或 QUOTE',
      'parameters.quantityUnit',
    )
  }
  if (side === 'SELL' && quantityUnit !== 'BASE') {
    return fail(
      action,
      'ORACLE_QUANTITY_UNIT_UNSUPPORTED',
      'Spot SELL 只接受 BASE quantityUnit',
      'parameters.quantityUnit',
    )
  }

  return {
    side,
    quantity: requirePositive(
      action,
      parseDecimal(action, quantity, 'parameters.quantity'),
      'parameters.quantity',
    ),
    quantityUnit,
  }
}

function validateBoundary(
  action: TimelineAction,
  tick: SpotInstrumentTick,
  instrument: TradingLabInstrumentConfig,
  policy: TradingLabExecutionPolicy,
): void {
  if (policy.matchingMode !== 'SIMPLE') {
    fail(
      action,
      'ORACLE_MATCHING_MODE_UNSUPPORTED',
      `Spot Oracle 仅支持 SIMPLE，不支持 ${policy.matchingMode}`,
    )
  }
  if (
    action.productType !== 'CRYPTO_SPOT'
    || tick.productType !== 'CRYPTO_SPOT'
    || instrument.productType !== 'CRYPTO_SPOT'
    || action.symbol !== tick.symbol
    || action.symbol !== instrument.symbol
  ) {
    fail(
      action,
      'ORACLE_INSTRUMENT_MISMATCH',
      'action、Tick 与 instrument 的 productType/symbol 必须完全一致',
    )
  }
  if (
    instrument.baseAsset.trim() === ''
    || instrument.quoteAsset !== 'USDT'
    || instrument.baseAsset === instrument.quoteAsset
  ) {
    fail(
      action,
      'ORACLE_ASSET_MODEL_UNSUPPORTED',
      'Spot Oracle 需要显式且不同的 baseAsset，并且 quoteAsset 必须是 USDT',
    )
  }
}

function calculateFill(
  action: TimelineAction,
  order: ParsedOrder,
  tick: SpotInstrumentTick,
  instrument: TradingLabInstrumentConfig,
  policy: TradingLabExecutionPolicy,
): CalculatedFill {
  const feeRate = requireNonNegative(
    action,
    parseDecimal(action, policy.takerFeeRate, 'executionPolicy.takerFeeRate'),
    'executionPolicy.takerFeeRate',
  )
  const slippageRate = requireNonNegative(
    action,
    parseDecimal(action, policy.slippageRate, 'executionPolicy.slippageRate'),
    'executionPolicy.slippageRate',
  )
  if (compare(feeRate, ONE) >= 0 || compare(slippageRate, ONE) >= 0) {
    fail(
      action,
      'ORACLE_RATE_INVALID',
      'takerFeeRate 与 slippageRate 必须小于 1',
    )
  }

  const marketPrice = requirePositive(
    action,
    parseDecimal(
      action,
      order.side === 'BUY' ? tick.ask : tick.bid,
      order.side === 'BUY' ? 'tick.ask' : 'tick.bid',
    ),
    order.side === 'BUY' ? 'tick.ask' : 'tick.bid',
  )
  const step = requirePositive(
    action,
    parseDecimal(action, instrument.stepSize, 'instrument.stepSize'),
    'instrument.stepSize',
  )
  const fillPrice = order.side === 'BUY'
    ? multiply(marketPrice, add(ONE, slippageRate))
    : multiply(marketPrice, subtract(ONE, slippageRate))
  requirePositive(action, fillPrice, 'fillPrice')

  let baseQuantity = order.quantity
  if (order.side === 'BUY' && order.quantityUnit === 'QUOTE') {
    const quotePerBaseWithFee = multiply(fillPrice, add(ONE, feeRate))
    const rawBase = divide(
      order.quantity,
      quotePerBaseWithFee,
      step.scale,
      'DOWN',
    )
    baseQuantity = floorToStep(rawBase, step)
  } else {
    requireStepAligned(action, baseQuantity, step)
  }
  requirePositive(action, baseQuantity, 'baseQuantity')

  const minQuantity = requirePositive(
    action,
    parseDecimal(action, instrument.minQty, 'instrument.minQty'),
    'instrument.minQty',
  )
  const maxQuantity = requirePositive(
    action,
    parseDecimal(action, instrument.maxQty, 'instrument.maxQty'),
    'instrument.maxQty',
  )
  const maxFillQuantity = optionalPositive(
    action,
    policy.maxFillQuantityPerTick,
    'executionPolicy.maxFillQuantityPerTick',
  )
  if (compare(baseQuantity, minQuantity) < 0) {
    fail(action, 'ORACLE_QUANTITY_BELOW_MINIMUM', '成交数量低于 minQty')
  }
  if (compare(baseQuantity, maxQuantity) > 0) {
    fail(action, 'ORACLE_QUANTITY_ABOVE_MAXIMUM', '成交数量高于 maxQty')
  }
  if (
    maxFillQuantity !== null
    && compare(baseQuantity, maxFillQuantity) > 0
  ) {
    fail(
      action,
      'ORACLE_MAX_FILL_QUANTITY_EXCEEDED',
      '成交数量超过 maxFillQuantityPerTick',
    )
  }

  const notionalUsdt = multiply(baseQuantity, fillPrice)
  const minNotional = optionalPositive(
    action,
    instrument.minNotional,
    'instrument.minNotional',
  )
  const maxNotional = optionalPositive(
    action,
    instrument.maxNotional,
    'instrument.maxNotional',
  )
  if (minNotional !== null && compare(notionalUsdt, minNotional) < 0) {
    fail(action, 'ORACLE_NOTIONAL_BELOW_MINIMUM', '成交名义价值低于 minNotional')
  }
  if (maxNotional !== null && compare(notionalUsdt, maxNotional) > 0) {
    fail(action, 'ORACLE_NOTIONAL_ABOVE_MAXIMUM', '成交名义价值高于 maxNotional')
  }

  const feeUsdt = multiply(notionalUsdt, feeRate)
  const quoteCash = order.side === 'BUY'
    ? add(notionalUsdt, feeUsdt)
    : subtract(notionalUsdt, feeUsdt)
  if (
    order.side === 'BUY'
    && order.quantityUnit === 'QUOTE'
    && compare(quoteCash, order.quantity) > 0
  ) {
    fail(
      action,
      'ORACLE_QUOTE_BUDGET_EXCEEDED',
      'Spot BUY 实际现金支出不得超过 QUOTE budget',
    )
  }

  return {
    baseQuantity,
    fillPrice,
    notionalUsdt,
    feeUsdt,
    quoteCash,
  }
}

function emptyPosition(instrument: TradingLabInstrumentConfig): SpotPositionState {
  return {
    instrument,
    quantity: ZERO,
    grossQuoteCost: ZERO,
    feeCostUsdt: ZERO,
    netInvestedUsdt: ZERO,
    realizedGrossPnl: ZERO,
    realizedNetPnl: ZERO,
  }
}

function calculateBuyPosition(
  position: SpotPositionState,
  fill: CalculatedFill,
): SpotPositionState {
  return {
    instrument: position.instrument,
    quantity: add(position.quantity, fill.baseQuantity),
    grossQuoteCost: add(position.grossQuoteCost, fill.notionalUsdt),
    feeCostUsdt: add(position.feeCostUsdt, fill.feeUsdt),
    netInvestedUsdt: add(position.netInvestedUsdt, fill.quoteCash),
    realizedGrossPnl: position.realizedGrossPnl,
    realizedNetPnl: position.realizedNetPnl,
  }
}

function calculateSellPosition(
  action: TimelineAction,
  position: SpotPositionState,
  fill: CalculatedFill,
): SpotPositionState {
  if (compare(position.quantity, fill.baseQuantity) < 0) {
    return fail(
      action,
      'ORACLE_SPOT_OVERSELL',
      'Spot SELL 数量超过当前 Spot position',
      'parameters.quantity',
    )
  }

  const closesPosition = compare(position.quantity, fill.baseQuantity) === 0
  const soldRawCost = closesPosition
    ? position.grossQuoteCost
    : divide(
        multiply(position.grossQuoteCost, fill.baseQuantity),
        position.quantity,
        calculationScale(
          position.grossQuoteCost,
          fill.baseQuantity,
          position.quantity,
        ),
        'HALF_UP',
      )
  const realizedGross = subtract(fill.notionalUsdt, soldRawCost)

  return {
    instrument: position.instrument,
    quantity: subtract(position.quantity, fill.baseQuantity),
    grossQuoteCost: closesPosition
      ? ZERO
      : subtract(position.grossQuoteCost, soldRawCost),
    feeCostUsdt: add(position.feeCostUsdt, fill.feeUsdt),
    netInvestedUsdt: subtract(position.netInvestedUsdt, fill.quoteCash),
    realizedGrossPnl: add(position.realizedGrossPnl, realizedGross),
    realizedNetPnl: add(
      position.realizedNetPnl,
      subtract(realizedGross, fill.feeUsdt),
    ),
  }
}

function appendExecutionRecords(
  state: MutableOracleState,
  action: TimelineAction,
  order: ParsedOrder,
  fill: CalculatedFill,
  baseAsset: string,
  baseBalance: Decimal,
  quoteBalance: Decimal,
): void {
  const orderId = `order:${action.id}`
  const tradeId = `trade:${action.id}`
  state.orders.push({
    id: orderId,
    actionId: action.id,
    symbol: action.symbol,
    productType: 'CRYPTO_SPOT',
    side: order.side,
    status: 'FILLED',
    quantity: toDecimalString(fill.baseQuantity),
    price: toDecimalString(fill.fillPrice),
    feeUsdt: toDecimalString(fill.feeUsdt),
  })
  state.trades.push({
    id: tradeId,
    orderId,
    actionId: action.id,
    symbol: action.symbol,
    productType: 'CRYPTO_SPOT',
    side: order.side,
    quantity: toDecimalString(fill.baseQuantity),
    price: toDecimalString(fill.fillPrice),
    notionalUsdt: toDecimalString(fill.notionalUsdt),
    feeUsdt: toDecimalString(fill.feeUsdt),
  })

  const baseAmount = order.side === 'BUY'
    ? fill.baseQuantity
    : subtract(ZERO, fill.baseQuantity)
  const quoteAmount = order.side === 'BUY'
    ? subtract(ZERO, fill.quoteCash)
    : fill.quoteCash
  state.ledger.push(
    {
      actionId: action.id,
      asset: 'USDT',
      type: order.side === 'BUY' ? 'SPOT_BUY_CASH' : 'SPOT_SELL_CASH',
      amount: toDecimalString(quoteAmount),
      balanceAfter: toDecimalString(quoteBalance),
    },
    {
      actionId: action.id,
      asset: baseAsset,
      type: order.side === 'BUY' ? 'SPOT_BUY_BASE' : 'SPOT_SELL_BASE',
      amount: toDecimalString(baseAmount),
      balanceAfter: toDecimalString(baseBalance),
    },
  )
}

export function executeSpotMarketOrder(
  state: MutableOracleState,
  action: TimelineAction,
  tick: SpotInstrumentTick,
  instrument: TradingLabInstrumentConfig,
  policy: TradingLabExecutionPolicy,
): void {
  validateBoundary(action, tick, instrument, policy)
  const order = parseOrder(action)
  const fill = calculateFill(action, order, tick, instrument, policy)
  const positionKey = `${SPOT_KEY_PREFIX}${instrument.symbol}`
  const currentPosition = state.spotPositions.get(positionKey) ?? emptyPosition(instrument)
  const currentBase = state.wallets.get(instrument.baseAsset) ?? ZERO
  const currentQuote = state.wallets.get(instrument.quoteAsset) ?? ZERO

  let nextBase: Decimal
  let nextQuote: Decimal
  let nextPosition: SpotPositionState
  if (order.side === 'BUY') {
    if (compare(currentQuote, fill.quoteCash) < 0) {
      fail(
        action,
        'ORACLE_INSUFFICIENT_BALANCE',
        `Spot BUY 的 ${instrument.quoteAsset} 余额不足`,
        'parameters.quantity',
      )
    }
    nextBase = add(currentBase, fill.baseQuantity)
    nextQuote = subtract(currentQuote, fill.quoteCash)
    if (compare(
      availableMarginUsdt(state, { wallet: nextQuote }),
      ZERO,
    ) < 0) {
      fail(
        action,
        'ORACLE_INSUFFICIENT_BALANCE',
        'Spot BUY 会侵占当前 Perpetual 保证金容量',
        'parameters.quantity',
      )
    }
    nextPosition = calculateBuyPosition(currentPosition, fill)
  } else {
    if (compare(currentBase, fill.baseQuantity) < 0) {
      fail(
        action,
        'ORACLE_SPOT_OVERSELL',
        `Spot SELL 的 ${instrument.baseAsset} 余额不足`,
        'parameters.quantity',
      )
    }
    nextPosition = calculateSellPosition(action, currentPosition, fill)
    nextBase = subtract(currentBase, fill.baseQuantity)
    nextQuote = add(currentQuote, fill.quoteCash)
  }

  state.wallets.set(instrument.baseAsset, nextBase)
  state.wallets.set(instrument.quoteAsset, nextQuote)
  state.spotPositions.set(positionKey, nextPosition)
  appendExecutionRecords(
    state,
    action,
    order,
    fill,
    instrument.baseAsset,
    nextBase,
    nextQuote,
  )
}
