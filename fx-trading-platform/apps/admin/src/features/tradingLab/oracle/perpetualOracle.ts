import type {
  MarginMode,
  PositionMode,
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
  type FundingRateTick,
  type MutableOracleState,
  type PerpetualInstrumentTick,
  type PerpetualPositionState,
} from './types.ts'
import {
  availableMarginUsdt,
  isolatedPrincipalUsdt,
} from './riskOracle.ts'

const ZERO = decimal(0n)
const ONE = decimal(1n)
const DIVISION_SCALE = 18

type ParsedPerpetualOrder = Readonly<{
  side: 'BUY' | 'SELL'
  direction: 'LONG' | 'SHORT'
  positionSide: 'BOTH' | 'LONG' | 'SHORT'
  marginMode: MarginMode
  leverage: number
  quantity: Decimal
  fillPrice: Decimal
  notional: Decimal
  feeRate: Decimal
  fee: Decimal
}>

function fail(
  action: TimelineAction,
  code: string,
  message: string,
  field = '',
): never {
  throw new OracleExecutionError({
    path: `timeline.${action.id}${field === '' ? '' : `.${field}`}`,
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

function positive(
  action: TimelineAction,
  value: Decimal,
  field: string,
): Decimal {
  if (compare(value, ZERO) <= 0) {
    return fail(action, 'ORACLE_DECIMAL_INVALID', `${field} 必须大于零`, field)
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
  return positive(action, parseDecimal(action, value, field), field)
}

function nonNegative(
  action: TimelineAction,
  value: Decimal,
  field: string,
): Decimal {
  if (compare(value, ZERO) < 0) {
    return fail(action, 'ORACLE_RATE_INVALID', `${field} 不得小于零`, field)
  }
  return value
}

function negate(value: Decimal): Decimal {
  return subtract(ZERO, value)
}

function min(left: Decimal, right: Decimal): Decimal {
  return compare(left, right) <= 0 ? left : right
}

function settingsKey(instrument: TradingLabInstrumentConfig): string {
  return `${instrument.productType}\u0000${instrument.symbol}`
}

function positionKey(
  instrument: TradingLabInstrumentConfig,
  positionSide: 'BOTH' | 'LONG' | 'SHORT',
): string {
  return `${settingsKey(instrument)}\u0000${positionSide}`
}

function settings(
  state: MutableOracleState,
  action: TimelineAction,
  instrument: TradingLabInstrumentConfig,
) {
  const current = state.settings.get(settingsKey(instrument))
  if (current === undefined) {
    return fail(action, 'ORACLE_SETTINGS_MISSING', 'Perpetual 品种缺少场景设置')
  }
  return current
}

function openPositions(
  state: MutableOracleState,
  instrument: TradingLabInstrumentConfig,
): Array<[string, PerpetualPositionState]> {
  return Array.from(state.perpetualPositions.entries()).filter(([, position]) =>
    position.instrument.productType === instrument.productType
    && position.instrument.symbol === instrument.symbol
    && compare(position.quantity, ZERO) > 0)
}

function requiredInitialMargin(
  quantity: Decimal,
  entryPrice: Decimal,
  leverage: number,
): Decimal {
  return divide(
    multiply(quantity, entryPrice),
    decimal(BigInt(leverage)),
    DIVISION_SCALE,
    'HALF_UP',
  )
}

function maintenanceMargin(
  action: TimelineAction,
  quantity: Decimal,
  markPrice: Decimal,
  instrument: TradingLabInstrumentConfig,
): Decimal {
  return multiply(
    multiply(quantity, markPrice),
    nonNegative(
      action,
      parseDecimal(
        action,
        instrument.maintenanceMarginRate,
        'instrument.maintenanceMarginRate',
      ),
      'instrument.maintenanceMarginRate',
    ),
  )
}

function unrealized(
  direction: 'LONG' | 'SHORT',
  quantity: Decimal,
  entryPrice: Decimal,
  markPrice: Decimal,
): Decimal {
  return multiply(
    direction === 'LONG'
      ? subtract(markPrice, entryPrice)
      : subtract(entryPrice, markPrice),
    quantity,
  )
}

function realized(
  direction: 'LONG' | 'SHORT',
  quantity: Decimal,
  entryPrice: Decimal,
  fillPrice: Decimal,
): Decimal {
  return multiply(
    direction === 'LONG'
      ? subtract(fillPrice, entryPrice)
      : subtract(entryPrice, fillPrice),
    quantity,
  )
}

function realizedNet(position: {
  realizedGrossPnl: Decimal
  fundingPnlUsdt: Decimal
  tradingFeeUsdt: Decimal
  liquidationFeeUsdt: Decimal
}): Decimal {
  return subtract(
    add(position.realizedGrossPnl, position.fundingPnlUsdt),
    add(position.tradingFeeUsdt, position.liquidationFeeUsdt),
  )
}

function estimatedCloseFee(
  position: PerpetualPositionState,
  policy: TradingLabExecutionPolicy,
): Decimal {
  return multiply(
    multiply(position.quantity, position.markPrice),
    decimal(policy.takerFeeRate),
  )
}

function isolatedRiskUnsafe(
  position: PerpetualPositionState,
  policy: TradingLabExecutionPolicy,
): boolean {
  const equity = add(
    add(position.isolatedMargin, position.isolatedFundingPendingUsdt),
    position.unrealizedGrossPnl,
  )
  const threshold = add(
    position.maintenanceMargin,
    estimatedCloseFee(position, policy),
  )
  return compare(equity, threshold) <= 0
}

function validateBoundary(
  action: TimelineAction,
  tick: PerpetualInstrumentTick,
  instrument: TradingLabInstrumentConfig,
  policy: TradingLabExecutionPolicy,
): void {
  if (
    action.productType !== 'LINEAR_PERP'
    || tick.productType !== 'LINEAR_PERP'
    || instrument.productType !== 'LINEAR_PERP'
    || action.symbol !== tick.symbol
    || action.symbol !== instrument.symbol
  ) {
    fail(action, 'ORACLE_INSTRUMENT_MISMATCH', 'action、Tick 与 Perpetual instrument 必须完全一致')
  }
  if (
    instrument.quoteAsset !== 'USDT'
    || instrument.marginAsset !== 'USDT'
    || instrument.settlementAsset !== 'USDT'
  ) {
    fail(action, 'ORACLE_ASSET_MODEL_UNSUPPORTED', 'Perpetual Oracle 只支持显式 USDT 模型')
  }
  if (policy.matchingMode !== 'SIMPLE') {
    fail(action, 'ORACLE_MATCHING_MODE_UNSUPPORTED', 'Perpetual Oracle 当前只支持 SIMPLE')
  }
}

function applySettingsAction(
  state: MutableOracleState,
  action: TimelineAction,
  instrument: TradingLabInstrumentConfig,
  policy: TradingLabExecutionPolicy,
): boolean {
  if (
    action.type !== 'SET_POSITION_MODE'
    && action.type !== 'SET_MARGIN_MODE'
    && action.type !== 'SET_LEVERAGE'
  ) {
    return false
  }

  const current = settings(state, action, instrument)
  const positions = openPositions(state, instrument)
  if (action.type === 'SET_POSITION_MODE') {
    const next = action.parameters.positionMode
    if (next !== 'ONE_WAY' && next !== 'HEDGE') {
      fail(action, 'ORACLE_POSITION_MODE_INVALID', 'positionMode 必须是 ONE_WAY 或 HEDGE')
    }
    if (positions.length > 0) {
      fail(action, 'ORACLE_POSITION_MODE_HAS_OPEN_POSITION', '存在持仓时不能切换 positionMode')
    }
    state.settings.set(settingsKey(instrument), {
      ...current,
      positionMode: next as PositionMode,
    })
    return true
  }
  if (action.type === 'SET_MARGIN_MODE') {
    const next = action.parameters.marginMode
    if (next !== 'CROSS' && next !== 'ISOLATED') {
      fail(action, 'ORACLE_MARGIN_MODE_INVALID', 'marginMode 必须是 CROSS 或 ISOLATED')
    }
    if (positions.length > 0) {
      fail(action, 'ORACLE_MARGIN_MODE_HAS_OPEN_POSITION', '存在持仓时不能切换 marginMode')
    }
    state.settings.set(settingsKey(instrument), {
      ...current,
      marginMode: next as MarginMode,
    })
    return true
  }

  const nextLeverage = action.parameters.leverage
  if (
    !Number.isSafeInteger(nextLeverage)
    || (nextLeverage as number) < 1
    || (nextLeverage as number) > instrument.maxLeverage
  ) {
    fail(action, 'ORACLE_LEVERAGE_INVALID', 'leverage 超出 instrument 范围')
  }

  const replacements = new Map<string, PerpetualPositionState>()
  for (const [key, position] of positions) {
    const nextInitial = requiredInitialMargin(
      position.quantity,
      position.entryPrice,
      nextLeverage as number,
    )
    const manualAdjustment = position.marginMode === 'ISOLATED'
      ? subtract(position.isolatedMargin, position.initialMargin)
      : ZERO
    const next: PerpetualPositionState = {
      ...position,
      leverage: nextLeverage as number,
      initialMargin: nextInitial,
      isolatedMargin: position.marginMode === 'ISOLATED'
        ? add(nextInitial, manualAdjustment)
        : ZERO,
    }
    replacements.set(key, next)
  }
  const wallet = state.wallets.get('USDT') ?? ZERO
  if (compare(availableMarginUsdt(state, { replacements, wallet }), ZERO) < 0) {
    fail(action, 'ORACLE_INSUFFICIENT_MARGIN', '调整 leverage 后 USDT 可用保证金不足')
  }
  for (const position of replacements.values()) {
    if (
      position.marginMode === 'ISOLATED'
      && (
        compare(position.isolatedMargin, ZERO) < 0
        || isolatedRiskUnsafe(position, policy)
      )
    ) {
      fail(action, 'ORACLE_LEVERAGE_CHANGE_UNSAFE', '调整 leverage 后 isolated equity 不安全')
    }
  }

  state.settings.set(settingsKey(instrument), {
    ...current,
    leverage: nextLeverage as number,
  })
  for (const [key, position] of replacements) {
    state.perpetualPositions.set(key, position)
  }
  return true
}

function selectedPosition(
  state: MutableOracleState,
  action: TimelineAction,
  instrument: TradingLabInstrumentConfig,
): [string, PerpetualPositionState] {
  const currentSettings = settings(state, action, instrument)
  const requested = action.parameters.positionSide
  const side = currentSettings.positionMode === 'ONE_WAY'
    ? 'BOTH'
    : requested === 'LONG' || requested === 'SHORT'
      ? requested
      : fail(action, 'ORACLE_POSITION_SIDE_REQUIRED', 'HEDGE 动作必须指定 LONG 或 SHORT')
  const key = positionKey(instrument, side)
  const position = state.perpetualPositions.get(key)
  if (position === undefined || compare(position.quantity, ZERO) <= 0) {
    return fail(action, 'ORACLE_POSITION_NOT_FOUND', '未找到可调整的 Perpetual position')
  }
  return [key, position]
}

function applyMarginAction(
  state: MutableOracleState,
  action: TimelineAction,
  instrument: TradingLabInstrumentConfig,
  policy: TradingLabExecutionPolicy,
): boolean {
  if (action.type !== 'ADD_MARGIN' && action.type !== 'REMOVE_MARGIN') {
    return false
  }
  const [key, position] = selectedPosition(state, action, instrument)
  if (position.marginMode !== 'ISOLATED') {
    fail(action, 'ORACLE_MARGIN_MODE_INVALID', 'ADD/REMOVE_MARGIN 仅支持 ISOLATED')
  }
  const amount = positive(
    action,
    parseDecimal(action, action.parameters.amount, 'parameters.amount'),
    'parameters.amount',
  )
  const nextMargin = action.type === 'ADD_MARGIN'
    ? add(position.isolatedMargin, amount)
    : subtract(position.isolatedMargin, amount)
  if (compare(nextMargin, ZERO) < 0) {
    fail(action, 'ORACLE_MARGIN_REMOVAL_UNSAFE', '移除保证金超过当前 isolated margin')
  }
  if (
    action.type === 'REMOVE_MARGIN'
    && isolatedRiskUnsafe({ ...position, isolatedMargin: nextMargin }, policy)
  ) {
    fail(action, 'ORACLE_MARGIN_REMOVAL_UNSAFE', '移除后 isolated equity 不高于强平阈值')
  }
  const next = { ...position, isolatedMargin: nextMargin }
  const wallet = state.wallets.get('USDT') ?? ZERO
  if (compare(
    availableMarginUsdt(state, {
      replacements: new Map([[key, next]]),
      wallet,
    }),
    ZERO,
  ) < 0) {
    fail(action, 'ORACLE_INSUFFICIENT_MARGIN', 'USDT 可用保证金不足')
  }
  state.perpetualPositions.set(key, next)
  return true
}

function applyFunding(
  state: MutableOracleState,
  action: TimelineAction,
  tick: PerpetualInstrumentTick,
  fundingRates: readonly FundingRateTick[],
  instrument: TradingLabInstrumentConfig,
): boolean {
  if (action.type !== 'APPLY_FUNDING') {
    return false
  }
  const funding = fundingRates.find((candidate) => candidate.symbol === instrument.symbol)
  if (funding === undefined) {
    fail(action, 'ORACLE_FUNDING_RATE_MISSING', '当前 Tick 缺少显式 funding rate')
  }
  const rate = parseDecimal(action, funding.rate, 'tick.fundingRates.rate')
  const mark = positive(action, parseDecimal(action, tick.mark, 'tick.mark'), 'tick.mark')
  const requested = action.parameters.positionSide
  const targets = openPositions(state, instrument).filter(([, position]) =>
    requested === undefined || requested === position.positionSide)
  if (targets.length === 0) {
    fail(action, 'ORACLE_POSITION_NOT_FOUND', '没有可结算 funding 的 position')
  }

  const replacements = new Map<string, PerpetualPositionState>()
  let fundingPnl = ZERO
  let crossCashFlow = ZERO
  for (const [key, position] of targets) {
    const notional = multiply(position.quantity, mark)
    const flow = position.direction === 'LONG'
      ? negate(multiply(notional, rate))
      : multiply(notional, rate)
    const next = {
      ...position,
      markPrice: mark,
      fundingPnlUsdt: add(position.fundingPnlUsdt, flow),
      isolatedFundingPendingUsdt: position.marginMode === 'ISOLATED'
        ? add(position.isolatedFundingPendingUsdt, flow)
        : position.isolatedFundingPendingUsdt,
    }
    next.realizedNetPnl = realizedNet(next)
    replacements.set(key, next)
    fundingPnl = add(fundingPnl, flow)
    if (position.marginMode === 'CROSS') {
      crossCashFlow = add(crossCashFlow, flow)
    }
  }
  const wallet = state.wallets.get('USDT') ?? ZERO
  const nextWallet = add(wallet, crossCashFlow)
  state.wallets.set('USDT', nextWallet)
  for (const [key, position] of replacements) {
    state.perpetualPositions.set(key, position)
  }
  state.ledger.push({
    actionId: action.id,
    asset: 'USDT',
    type: 'PERPETUAL_FUNDING',
    amount: toDecimalString(fundingPnl),
    balanceAfter: toDecimalString(nextWallet),
  })
  return true
}

function parseOrder(
  state: MutableOracleState,
  action: TimelineAction,
  tick: PerpetualInstrumentTick,
  instrument: TradingLabInstrumentConfig,
  policy: TradingLabExecutionPolicy,
): ParsedPerpetualOrder {
  const currentSettings = settings(state, action, instrument)
  if (action.parameters.orderType !== 'MARKET') {
    fail(action, 'ORACLE_ORDER_TYPE_UNSUPPORTED', 'Perpetual Oracle 当前只支持 MARKET')
  }
  if (action.parameters.reduceOnly === true) {
    fail(action, 'ORACLE_REDUCE_ONLY_UNSUPPORTED', 'Perpetual Oracle 尚未证明 reduceOnly 语义')
  }
  const side = action.parameters.side
  if (side !== 'BUY' && side !== 'SELL') {
    fail(action, 'ORACLE_SIDE_INVALID', 'side 必须是 BUY 或 SELL')
  }
  const quantityUnit = action.parameters.quantityUnit
  if (quantityUnit !== 'BASE' && quantityUnit !== 'CONTRACTS') {
    fail(action, 'ORACLE_QUANTITY_UNIT_UNSUPPORTED', 'Perpetual quantityUnit 只支持 BASE 或 CONTRACTS')
  }
  let quantity = positive(
    action,
    parseDecimal(action, action.parameters.quantity, 'parameters.quantity'),
    'parameters.quantity',
  )
  if (quantityUnit === 'CONTRACTS') {
    quantity = multiply(
      quantity,
      positive(
        action,
        parseDecimal(action, instrument.contractSize, 'instrument.contractSize'),
        'instrument.contractSize',
      ),
    )
  }
  const step = positive(
    action,
    parseDecimal(action, instrument.stepSize, 'instrument.stepSize'),
    'instrument.stepSize',
  )
  if (compare(floorToStep(quantity, step), quantity) !== 0) {
    fail(action, 'ORACLE_QUANTITY_STEP_MISMATCH', 'quantity 不符合 stepSize')
  }
  const minQuantity = positive(
    action,
    parseDecimal(action, instrument.minQty, 'instrument.minQty'),
    'instrument.minQty',
  )
  const maxQuantity = positive(
    action,
    parseDecimal(action, instrument.maxQty, 'instrument.maxQty'),
    'instrument.maxQty',
  )
  const maxFillQuantity = optionalPositive(
    action,
    policy.maxFillQuantityPerTick,
    'executionPolicy.maxFillQuantityPerTick',
  )
  if (compare(quantity, minQuantity) < 0) {
    fail(action, 'ORACLE_QUANTITY_BELOW_MINIMUM', '成交数量低于 minQty')
  }
  if (compare(quantity, maxQuantity) > 0) {
    fail(action, 'ORACLE_QUANTITY_ABOVE_MAXIMUM', '成交数量高于 maxQty')
  }
  if (
    maxFillQuantity !== null
    && compare(quantity, maxFillQuantity) > 0
  ) {
    fail(
      action,
      'ORACLE_MAX_FILL_QUANTITY_EXCEEDED',
      '成交数量超过 maxFillQuantityPerTick',
    )
  }

  const positionSide = currentSettings.positionMode === 'ONE_WAY'
    ? action.parameters.positionSide === undefined
      || action.parameters.positionSide === 'BOTH'
      ? 'BOTH'
      : fail(action, 'ORACLE_POSITION_SIDE_INVALID', 'ONE_WAY 只支持 BOTH')
    : action.parameters.positionSide === 'LONG'
      || action.parameters.positionSide === 'SHORT'
      ? action.parameters.positionSide
      : fail(action, 'ORACLE_POSITION_SIDE_REQUIRED', 'HEDGE 必须指定 LONG 或 SHORT')
  const direction = side === 'BUY' ? 'LONG' as const : 'SHORT' as const
  const marginMode = action.parameters.marginMode ?? currentSettings.marginMode
  if (marginMode !== 'CROSS' && marginMode !== 'ISOLATED') {
    fail(action, 'ORACLE_MARGIN_MODE_INVALID', 'marginMode 必须是 CROSS 或 ISOLATED')
  }
  const leverage = action.parameters.leverage ?? currentSettings.leverage
  if (
    !Number.isSafeInteger(leverage)
    || (leverage as number) < 1
    || (leverage as number) > instrument.maxLeverage
  ) {
    fail(action, 'ORACLE_LEVERAGE_INVALID', 'leverage 超出 instrument 范围')
  }
  const feeRate = nonNegative(
    action,
    parseDecimal(action, policy.takerFeeRate, 'executionPolicy.takerFeeRate'),
    'executionPolicy.takerFeeRate',
  )
  const slippage = nonNegative(
    action,
    parseDecimal(action, policy.slippageRate, 'executionPolicy.slippageRate'),
    'executionPolicy.slippageRate',
  )
  if (compare(feeRate, ONE) >= 0 || compare(slippage, ONE) >= 0) {
    fail(action, 'ORACLE_RATE_INVALID', 'fee/slippage 必须小于 1')
  }
  const market = positive(
    action,
    parseDecimal(action, side === 'BUY' ? tick.ask : tick.bid, 'tick.price'),
    'tick.price',
  )
  const fillPrice = side === 'BUY'
    ? multiply(market, add(ONE, slippage))
    : multiply(market, subtract(ONE, slippage))
  const notional = multiply(quantity, fillPrice)
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
  if (minNotional !== null && compare(notional, minNotional) < 0) {
    fail(action, 'ORACLE_NOTIONAL_BELOW_MINIMUM', '成交名义价值低于 minNotional')
  }
  if (maxNotional !== null && compare(notional, maxNotional) > 0) {
    fail(action, 'ORACLE_NOTIONAL_ABOVE_MAXIMUM', '成交名义价值高于 maxNotional')
  }
  const fee = multiply(notional, feeRate)
  return {
    side,
    direction,
    positionSide,
    marginMode,
    leverage: leverage as number,
    quantity,
    fillPrice,
    notional,
    feeRate,
    fee,
  }
}

function emptyPosition(
  instrument: TradingLabInstrumentConfig,
  positionSide: 'BOTH' | 'LONG' | 'SHORT',
  direction: 'LONG' | 'SHORT',
  marginMode: MarginMode,
  leverage: number,
): PerpetualPositionState {
  return {
    instrument,
    positionSide,
    direction,
    marginMode,
    status: 'CLOSED',
    quantity: ZERO,
    entryPrice: ZERO,
    markPrice: ZERO,
    leverage,
    initialMargin: ZERO,
    isolatedMargin: ZERO,
    maintenanceMargin: ZERO,
    unrealizedGrossPnl: ZERO,
    realizedGrossPnl: ZERO,
    tradingFeeUsdt: ZERO,
    fundingPnlUsdt: ZERO,
    isolatedFundingPendingUsdt: ZERO,
    liquidationFeeUsdt: ZERO,
    realizedNetPnl: ZERO,
    estimatedLiquidationPrice: null,
  }
}

function calculateOrderPosition(
  action: TimelineAction,
  current: PerpetualPositionState,
  order: ReturnType<typeof parseOrder>,
  markPrice: Decimal,
): {
  position: PerpetualPositionState
  cashDelta: Decimal
  bankruptcyShortfall: Decimal
  increasesExposure: boolean
  closesExposure: boolean
  rawClosingCash: Decimal
  openingFee: Decimal
} {
  const isHedge = current.positionSide !== 'BOTH'
  const addsHedge = current.positionSide === 'LONG'
    ? order.side === 'BUY'
    : order.side === 'SELL'
  const sameDirection = compare(current.quantity, ZERO) === 0
    || (isHedge ? addsHedge : current.direction === order.direction)
  const closesExposure = !sameDirection
    && compare(current.quantity, ZERO) > 0
  let quantity: Decimal
  let direction = current.direction
  let entryPrice: Decimal
  let grossDelta = ZERO
  let closing = ZERO

  if (sameDirection) {
    quantity = add(current.quantity, order.quantity)
    direction = isHedge
      ? current.positionSide === 'LONG' ? 'LONG' : 'SHORT'
      : order.direction
    entryPrice = compare(current.quantity, ZERO) === 0
      ? order.fillPrice
      : divide(
          add(
            multiply(current.quantity, current.entryPrice),
            multiply(order.quantity, order.fillPrice),
          ),
          quantity,
          DIVISION_SCALE,
          'HALF_UP',
        )
  } else {
    if (isHedge && compare(order.quantity, current.quantity) > 0) {
      return fail(action, 'ORACLE_HEDGE_OVERCLOSE', 'HEDGE close 不允许反向超额')
    }
    closing = min(current.quantity, order.quantity)
    grossDelta = realized(
      current.direction,
      closing,
      current.entryPrice,
      order.fillPrice,
    )
    const comparison = compare(order.quantity, current.quantity)
    if (comparison < 0) {
      quantity = subtract(current.quantity, order.quantity)
      entryPrice = current.entryPrice
    } else if (comparison === 0) {
      quantity = ZERO
      entryPrice = current.entryPrice
    } else {
      quantity = subtract(order.quantity, current.quantity)
      direction = order.direction
      entryPrice = order.fillPrice
    }
  }

  const initialMargin = compare(quantity, ZERO) === 0
    ? ZERO
    : requiredInitialMargin(quantity, entryPrice, order.leverage)
  const closesIsolated = closesExposure
    && current.marginMode === 'ISOLATED'
  const isolatedFundingSettlement = closesIsolated
    ? multiply(
        current.isolatedFundingPendingUsdt,
        divide(closing, current.quantity, DIVISION_SCALE, 'HALF_UP'),
      )
    : ZERO
  const remainingIsolatedFunding = closesIsolated
    ? subtract(
        current.isolatedFundingPendingUsdt,
        isolatedFundingSettlement,
      )
    : current.isolatedFundingPendingUsdt
  const retainedIsolatedMargin =
    closesIsolated && direction === current.direction
      ? multiply(
          current.isolatedMargin,
          divide(quantity, current.quantity, DIVISION_SCALE, 'HALF_UP'),
        )
      : ZERO
  const closingFee = closesExposure
    ? multiply(multiply(closing, order.fillPrice), order.feeRate)
    : ZERO
  const openingFee = subtract(order.fee, closingFee)
  const releasedIsolatedMargin = closesIsolated
    ? subtract(current.isolatedMargin, retainedIsolatedMargin)
    : ZERO
  const rawClosingCash = subtract(
    add(grossDelta, isolatedFundingSettlement),
    closingFee,
  )
  const appliedClosingCash =
    closesIsolated
    && compare(rawClosingCash, subtract(ZERO, releasedIsolatedMargin)) < 0
      ? subtract(ZERO, releasedIsolatedMargin)
      : rawClosingCash
  const bankruptcyShortfall = closesIsolated
    ? subtract(appliedClosingCash, rawClosingCash)
    : ZERO
  const cashDelta = closesIsolated
    ? subtract(appliedClosingCash, openingFee)
    : subtract(
        add(grossDelta, isolatedFundingSettlement),
        order.fee,
      )
  let isolatedMargin = ZERO
  if (order.marginMode === 'ISOLATED' && compare(quantity, ZERO) > 0) {
    if (compare(current.quantity, ZERO) === 0) {
      isolatedMargin = initialMargin
    } else if (sameDirection) {
      const manualAdjustment = subtract(
        current.isolatedMargin,
        current.initialMargin,
      )
      isolatedMargin = add(initialMargin, manualAdjustment)
    } else if (direction === current.direction) {
      isolatedMargin = retainedIsolatedMargin
    } else {
      isolatedMargin = initialMargin
    }
  }
  const next: PerpetualPositionState = {
    ...current,
    direction,
    marginMode: order.marginMode,
    status: compare(quantity, ZERO) > 0 ? 'OPEN' : 'CLOSED',
    quantity,
    entryPrice,
    markPrice,
    leverage: order.leverage,
    initialMargin,
    isolatedMargin,
    maintenanceMargin: maintenanceMargin(
      action,
      quantity,
      markPrice,
      current.instrument,
    ),
    unrealizedGrossPnl: compare(quantity, ZERO) === 0
      ? ZERO
      : unrealized(direction, quantity, entryPrice, markPrice),
    realizedGrossPnl: add(current.realizedGrossPnl, grossDelta),
    tradingFeeUsdt: add(current.tradingFeeUsdt, order.fee),
    isolatedFundingPendingUsdt: order.marginMode === 'ISOLATED'
      ? remainingIsolatedFunding
      : ZERO,
  }
  next.realizedNetPnl = realizedNet(next)
  return {
    position: next,
    cashDelta,
    bankruptcyShortfall,
    increasesExposure:
      sameDirection || direction !== current.direction,
    closesExposure,
    rawClosingCash,
    openingFee,
  }
}

function appendExecution(
  state: MutableOracleState,
  action: TimelineAction,
  order: ReturnType<typeof parseOrder>,
  cashDelta: Decimal,
  bankruptcyShortfall: Decimal,
  wallet: Decimal,
): void {
  const orderId = `order:${action.id}`
  state.orders.push({
    id: orderId,
    actionId: action.id,
    symbol: action.symbol,
    productType: 'LINEAR_PERP',
    side: order.side,
    status: 'FILLED',
    quantity: toDecimalString(order.quantity),
    price: toDecimalString(order.fillPrice),
    feeUsdt: toDecimalString(order.fee),
  })
  state.trades.push({
    id: `trade:${action.id}`,
    orderId,
    actionId: action.id,
    symbol: action.symbol,
    productType: 'LINEAR_PERP',
    side: order.side,
    quantity: toDecimalString(order.quantity),
    price: toDecimalString(order.fillPrice),
    notionalUsdt: toDecimalString(order.notional),
    feeUsdt: toDecimalString(order.fee),
  })
  state.ledger.push({
    actionId: action.id,
    asset: 'USDT',
    type: 'PERPETUAL_TRADE_CASH',
    amount: toDecimalString(cashDelta),
    balanceAfter: toDecimalString(wallet),
  })
  if (compare(bankruptcyShortfall, ZERO) > 0) {
    state.ledger.push({
      actionId: action.id,
      asset: 'USDT',
      type: 'BANKRUPTCY_SHORTFALL',
      amount: toDecimalString(bankruptcyShortfall),
      balanceAfter: toDecimalString(wallet),
    })
  }
}

export function executePerpetualAction(
  state: MutableOracleState,
  action: TimelineAction,
  tick: PerpetualInstrumentTick,
  fundingRates: readonly FundingRateTick[],
  instrument: TradingLabInstrumentConfig,
  policy: TradingLabExecutionPolicy,
): void {
  validateBoundary(action, tick, instrument, policy)
  if (applySettingsAction(state, action, instrument, policy)) {
    return
  }
  if (applyMarginAction(state, action, instrument, policy)) {
    return
  }
  if (applyFunding(state, action, tick, fundingRates, instrument)) {
    return
  }
  if (action.type !== 'PLACE_ORDER') {
    fail(action, 'ORACLE_ACTION_UNSUPPORTED', `Perpetual Oracle 不支持 ${action.type}`)
  }

  const order = parseOrder(state, action, tick, instrument, policy)
  const currentSettings = settings(state, action, instrument)
  if (
    openPositions(state, instrument).length > 0
    && (
      currentSettings.marginMode !== order.marginMode
      || currentSettings.leverage !== order.leverage
    )
  ) {
    fail(action, 'ORACLE_POSITION_SETTINGS_MISMATCH', '存在持仓时 order 必须沿用 instrument 的 marginMode/leverage')
  }
  const key = positionKey(instrument, order.positionSide)
  const current = state.perpetualPositions.get(key) ?? emptyPosition(
    instrument,
    order.positionSide,
    order.positionSide === 'SHORT' ? 'SHORT' : order.direction,
    order.marginMode,
    order.leverage,
  )
  if (
    compare(current.quantity, ZERO) === 0
    && order.positionSide !== 'BOTH'
    && (
      (order.positionSide === 'LONG' && order.side !== 'BUY')
      || (order.positionSide === 'SHORT' && order.side !== 'SELL')
    )
  ) {
    fail(action, 'ORACLE_HEDGE_CLOSE_WITHOUT_POSITION', 'HEDGE 空仓位不得执行平仓方向订单')
  }
  if (
    compare(current.quantity, ZERO) > 0
    && (
      current.marginMode !== order.marginMode
      || current.leverage !== order.leverage
    )
  ) {
    fail(action, 'ORACLE_POSITION_SETTINGS_MISMATCH', '现有持仓的 marginMode/leverage 必须先显式调整')
  }
  const mark = positive(action, parseDecimal(action, tick.mark, 'tick.mark'), 'tick.mark')
  const calculated = calculateOrderPosition(action, current, order, mark)
  if (
    calculated.position.marginMode === 'ISOLATED'
    && compare(calculated.position.quantity, ZERO) > 0
    && calculated.increasesExposure
    && isolatedRiskUnsafe(calculated.position, policy)
  ) {
    fail(
      action,
      'ORACLE_POSITION_RISK_UNSAFE',
      'Perpetual order 会使 isolated position 立即进入强平状态',
    )
  }
  const wallet = state.wallets.get('USDT') ?? ZERO
  let cashDelta = calculated.cashDelta
  let bankruptcyShortfall = calculated.bankruptcyShortfall
  if (current.marginMode === 'CROSS' && calculated.closesExposure) {
    const protectedFloor = isolatedPrincipalUsdt(state)
    const crossPool = compare(wallet, protectedFloor) > 0
      ? subtract(wallet, protectedFloor)
      : ZERO
    const minimumClosingCash = subtract(ZERO, crossPool)
    const appliedClosingCash =
      compare(calculated.rawClosingCash, minimumClosingCash) < 0
        ? minimumClosingCash
        : calculated.rawClosingCash
    cashDelta = subtract(appliedClosingCash, calculated.openingFee)
    bankruptcyShortfall = subtract(
      appliedClosingCash,
      calculated.rawClosingCash,
    )
  }
  const nextWallet = add(wallet, cashDelta)
  const replacements = new Map([[key, calculated.position]])
  if (
    compare(nextWallet, ZERO) < 0
    || (
      calculated.increasesExposure
      && compare(
        availableMarginUsdt(state, { replacements, wallet: nextWallet }),
        ZERO,
      ) < 0
    )
  ) {
    fail(action, 'ORACLE_INSUFFICIENT_MARGIN', 'Perpetual order 的 USDT 保证金或手续费不足')
  }

  state.wallets.set('USDT', nextWallet)
  state.perpetualPositions.set(key, calculated.position)
  state.settings.set(settingsKey(instrument), {
    ...currentSettings,
    marginMode: order.marginMode,
    leverage: order.leverage,
  })
  appendExecution(
    state,
    action,
    order,
    cashDelta,
    bankruptcyShortfall,
    nextWallet,
  )
}
