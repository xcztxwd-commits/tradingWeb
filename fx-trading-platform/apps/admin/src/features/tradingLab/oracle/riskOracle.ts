import type { TradingLabExecutionPolicy } from '../model/types.ts'
import {
  add,
  compare,
  decimal,
  divide,
  multiply,
  subtract,
  toDecimalString,
  type Decimal,
} from './decimal.ts'
import type {
  AccountSummaryResult,
  MarketTick,
  MutableOracleState,
  PerpetualInstrumentTick,
  PerpetualPositionState,
  RiskResult,
} from './types.ts'

const ZERO = decimal(0n)
const ONE = decimal(1n)
const CALCULATION_SCALE = 18
const CROSS_ESTIMATE_ASSUMPTION =
  'Cross liquidation estimate assumes every non-target instrument mark remains unchanged.'

function identity(symbol: string): string {
  return `LINEAR_PERP\u0000${symbol}`
}

function leverageDecimal(leverage: number): Decimal {
  return decimal(BigInt(leverage))
}

function canonicalBaseQuantity(position: PerpetualPositionState): Decimal {
  return position.quantity
}

function notional(
  position: PerpetualPositionState,
  price: Decimal,
): Decimal {
  return multiply(canonicalBaseQuantity(position), price)
}

function unrealized(
  position: PerpetualPositionState,
  mark: Decimal,
): Decimal {
  const difference = position.direction === 'LONG'
    ? subtract(mark, position.entryPrice)
    : subtract(position.entryPrice, mark)
  return multiply(difference, canonicalBaseQuantity(position))
}

function positiveOrZero(value: Decimal): Decimal {
  return compare(value, ZERO) < 0 ? ZERO : value
}

function estimatedCloseFee(
  position: PerpetualPositionState,
  policy: TradingLabExecutionPolicy,
): Decimal {
  return multiply(
    notional(position, position.markPrice),
    decimal(policy.takerFeeRate),
  )
}

function liquidationThreshold(
  position: PerpetualPositionState,
  policy: TradingLabExecutionPolicy,
): Decimal {
  return add(position.maintenanceMargin, estimatedCloseFee(position, policy))
}

export function isolatedPrincipalUsdt(
  state: MutableOracleState,
): Decimal {
  let total = ZERO
  for (const position of state.perpetualPositions.values()) {
    if (
      position.marginMode === 'ISOLATED'
      && compare(position.quantity, ZERO) > 0
    ) {
      total = add(total, position.isolatedMargin)
    }
  }
  return total
}

function isolatedFundingPnl(state: MutableOracleState): Decimal {
  let total = ZERO
  for (const position of state.perpetualPositions.values()) {
    if (
      position.marginMode === 'ISOLATED'
      && compare(position.quantity, ZERO) > 0
    ) {
      total = add(total, position.isolatedFundingPendingUsdt)
    }
  }
  return total
}

function recalculatePosition(
  state: MutableOracleState,
  position: PerpetualPositionState,
  mark: Decimal,
): void {
  const settings = state.settings.get(identity(position.instrument.symbol))
  if (settings !== undefined) {
    position.leverage = settings.leverage
  }

  position.markPrice = mark
  if (compare(position.quantity, ZERO) <= 0) {
    position.initialMargin = ZERO
    position.isolatedMargin = ZERO
    position.maintenanceMargin = ZERO
    position.unrealizedGrossPnl = ZERO
    position.estimatedLiquidationPrice = null
    return
  }

  position.initialMargin = divide(
    notional(position, position.entryPrice),
    leverageDecimal(position.leverage),
    CALCULATION_SCALE,
    'HALF_UP',
  )
  if (position.marginMode !== 'ISOLATED') {
    position.isolatedMargin = ZERO
  }
  position.maintenanceMargin = multiply(
    notional(position, mark),
    decimal(position.instrument.maintenanceMarginRate),
  )
  position.unrealizedGrossPnl = unrealized(position, mark)
}

export function revaluePerpetualPositions(
  state: MutableOracleState,
  tick: MarketTick,
): void {
  for (const position of state.perpetualPositions.values()) {
    const instrumentTick = tick.instruments.find((candidate) =>
      candidate.productType === 'LINEAR_PERP'
      && candidate.symbol === position.instrument.symbol)
    if (
      instrumentTick === undefined
      || instrumentTick.productType !== 'LINEAR_PERP'
    ) {
      continue
    }
    recalculatePosition(state, position, decimal(instrumentTick.mark))
  }
}

function isolatedEstimate(
  position: PerpetualPositionState,
  policy: TradingLabExecutionPolicy,
): Decimal | null {
  const quantity = canonicalBaseQuantity(position)
  if (compare(quantity, ZERO) <= 0) {
    return null
  }
  const riskRate = add(
    decimal(position.instrument.maintenanceMarginRate),
    decimal(policy.takerFeeRate),
  )
  const entryNotional = multiply(position.entryPrice, quantity)
  const effectiveMargin = add(
    position.isolatedMargin,
    position.isolatedFundingPendingUsdt,
  )
  if (position.direction === 'LONG') {
    const numerator = subtract(entryNotional, effectiveMargin)
    const denominator = multiply(quantity, subtract(ONE, riskRate))
    return compare(numerator, ZERO) <= 0
      ? ZERO
      : divide(numerator, denominator, CALCULATION_SCALE, 'HALF_UP')
  }
  return divide(
    add(entryNotional, effectiveMargin),
    multiply(quantity, add(ONE, riskRate)),
    CALCULATION_SCALE,
    'HALF_UP',
  )
}

function crossEstimateForInstrument(
  state: MutableOracleState,
  symbol: string,
  policy: TradingLabExecutionPolicy,
): Decimal | null {
  const targetPositions = Array.from(state.perpetualPositions.values())
    .filter((position) =>
      position.marginMode === 'CROSS'
      && position.instrument.symbol === symbol
      && compare(position.quantity, ZERO) > 0)
  if (targetPositions.length === 0) {
    return null
  }
  let equityAtZero = subtract(
    state.wallets.get('USDT') ?? ZERO,
    isolatedPrincipalUsdt(state),
  )
  let equitySlope = ZERO
  let fixedThreshold = ZERO
  let thresholdSlope = ZERO
  for (const position of state.perpetualPositions.values()) {
    if (
      position.marginMode !== 'CROSS'
      || compare(position.quantity, ZERO) <= 0
    ) {
      continue
    }
    if (position.instrument.symbol !== symbol) {
      equityAtZero = add(equityAtZero, position.unrealizedGrossPnl)
      fixedThreshold = add(
        fixedThreshold,
        liquidationThreshold(position, policy),
      )
      continue
    }
    const quantity = canonicalBaseQuantity(position)
    const entryNotional = multiply(quantity, position.entryPrice)
    if (position.direction === 'LONG') {
      equityAtZero = subtract(equityAtZero, entryNotional)
      equitySlope = add(equitySlope, quantity)
    } else {
      equityAtZero = add(equityAtZero, entryNotional)
      equitySlope = subtract(equitySlope, quantity)
    }
    thresholdSlope = add(
      thresholdSlope,
      multiply(
        quantity,
        add(
          decimal(position.instrument.maintenanceMarginRate),
          decimal(policy.takerFeeRate),
        ),
      ),
    )
  }
  const gapAtZero = subtract(equityAtZero, fixedThreshold)
  const gapSlope = subtract(equitySlope, thresholdSlope)
  if (compare(gapSlope, ZERO) === 0) {
    return null
  }
  return positiveOrZero(
    divide(
      subtract(ZERO, gapAtZero),
      gapSlope,
      CALCULATION_SCALE,
      'HALF_UP',
    ),
  )
}

function totalMaintenance(
  state: MutableOracleState,
  marginMode?: 'CROSS' | 'ISOLATED',
): Decimal {
  let total = ZERO
  for (const position of state.perpetualPositions.values()) {
    if (
      compare(position.quantity, ZERO) > 0
      && (marginMode === undefined || position.marginMode === marginMode)
    ) {
      total = add(total, position.maintenanceMargin)
    }
  }
  return total
}

function totalUnrealized(
  state: MutableOracleState,
  marginMode?: 'CROSS' | 'ISOLATED',
): Decimal {
  let total = ZERO
  for (const position of state.perpetualPositions.values()) {
    if (
      compare(position.quantity, ZERO) > 0
      && (marginMode === undefined || position.marginMode === marginMode)
    ) {
      total = add(total, position.unrealizedGrossPnl)
    }
  }
  return total
}

function totalCloseFees(
  state: MutableOracleState,
  policy: TradingLabExecutionPolicy,
  marginMode?: 'CROSS' | 'ISOLATED',
): Decimal {
  let total = ZERO
  for (const position of state.perpetualPositions.values()) {
    if (
      compare(position.quantity, ZERO) > 0
      && (marginMode === undefined || position.marginMode === marginMode)
    ) {
      total = add(total, estimatedCloseFee(position, policy))
    }
  }
  return total
}

export function availableMarginUsdt(
  state: MutableOracleState,
  options: Readonly<{
    replacements?: ReadonlyMap<string, PerpetualPositionState>
    wallet?: Decimal
  }> = {},
): Decimal {
  let available = options.wallet ?? state.wallets.get('USDT') ?? ZERO
  const seen = new Set<string>()
  const accountFor = (position: PerpetualPositionState): void => {
    if (compare(position.quantity, ZERO) <= 0) {
      return
    }
    if (position.marginMode === 'ISOLATED') {
      available = subtract(available, position.isolatedMargin)
      return
    }
    available = add(available, position.unrealizedGrossPnl)
    available = subtract(available, position.initialMargin)
  }
  for (const [key, current] of state.perpetualPositions) {
    seen.add(key)
    accountFor(options.replacements?.get(key) ?? current)
  }
  if (options.replacements !== undefined) {
    for (const [key, replacement] of options.replacements) {
      if (!seen.has(key)) {
        accountFor(replacement)
      }
    }
  }
  return available
}

function liquidationFillPrice(
  position: PerpetualPositionState,
  tick: MarketTick,
  policy: TradingLabExecutionPolicy,
): Decimal {
  const instrumentTick = tick.instruments.find((candidate) =>
    candidate.productType === 'LINEAR_PERP'
    && candidate.symbol === position.instrument.symbol) as
      | PerpetualInstrumentTick
      | undefined
  if (instrumentTick === undefined) {
    throw new Error('liquidation tick missing instrument')
  }
  const slippage = decimal(policy.slippageRate)
  return position.direction === 'LONG'
    ? multiply(decimal(instrumentTick.bid), subtract(ONE, slippage))
    : multiply(decimal(instrumentTick.ask), add(ONE, slippage))
}

function liquidatePositions(
  state: MutableOracleState,
  positions: readonly PerpetualPositionState[],
  tick: MarketTick,
  policy: TradingLabExecutionPolicy,
  actionId: string,
): void {
  const openPositions = positions.filter((position) =>
    compare(position.quantity, ZERO) > 0)
  if (openPositions.length === 0) {
    return
  }

  let cashFlow = ZERO
  let bankruptcyShortfall = ZERO
  const wallet = state.wallets.get('USDT') ?? ZERO
  for (const position of openPositions) {
    const quantity = position.quantity
    const fillPrice = liquidationFillPrice(position, tick, policy)
    const closeNotional = notional(position, fillPrice)
    const closeFee = multiply(closeNotional, decimal(policy.takerFeeRate))
    const liquidationFee = multiply(
      closeNotional,
      decimal(position.instrument.liquidationFeeRate),
    )
    const grossPnl = unrealized(position, fillPrice)
    const rawCashFlow = add(
      position.isolatedFundingPendingUsdt,
      subtract(subtract(grossPnl, closeFee), liquidationFee),
    )
    const appliedCashFlow =
      position.marginMode === 'ISOLATED'
      && compare(rawCashFlow, subtract(ZERO, position.isolatedMargin)) < 0
        ? subtract(ZERO, position.isolatedMargin)
        : rawCashFlow
    if (compare(appliedCashFlow, rawCashFlow) > 0) {
      bankruptcyShortfall = add(
        bankruptcyShortfall,
        subtract(appliedCashFlow, rawCashFlow),
      )
    }
    cashFlow = add(cashFlow, appliedCashFlow)
    const closeSide =
      position.direction === 'LONG' ? 'SELL' as const : 'BUY' as const
    const suffix =
      `${actionId}:${position.instrument.symbol}:${position.positionSide}`
    const orderId = `liquidation-order:${suffix}`

    state.orders.push({
      id: orderId,
      actionId,
      symbol: position.instrument.symbol,
      productType: 'LINEAR_PERP',
      side: closeSide,
      status: 'FILLED',
      quantity: toDecimalString(quantity),
      price: toDecimalString(fillPrice),
      feeUsdt: toDecimalString(closeFee),
    })
    state.trades.push({
      id: `liquidation-trade:${suffix}`,
      orderId,
      actionId,
      symbol: position.instrument.symbol,
      productType: 'LINEAR_PERP',
      side: closeSide,
      quantity: toDecimalString(quantity),
      price: toDecimalString(fillPrice),
      notionalUsdt: toDecimalString(closeNotional),
      feeUsdt: toDecimalString(closeFee),
    })

    position.realizedGrossPnl = add(position.realizedGrossPnl, grossPnl)
    position.tradingFeeUsdt = add(position.tradingFeeUsdt, closeFee)
    position.liquidationFeeUsdt = add(
      position.liquidationFeeUsdt,
      liquidationFee,
    )
    position.realizedNetPnl = subtract(
      add(position.realizedGrossPnl, position.fundingPnlUsdt),
      add(position.tradingFeeUsdt, position.liquidationFeeUsdt),
    )
    position.status = 'LIQUIDATED'
    position.quantity = ZERO
    position.initialMargin = ZERO
    position.isolatedMargin = ZERO
    position.isolatedFundingPendingUsdt = ZERO
    position.maintenanceMargin = ZERO
    position.unrealizedGrossPnl = ZERO
    position.estimatedLiquidationPrice = null
  }

  const floor = openPositions.every((position) =>
    position.marginMode === 'CROSS')
    ? isolatedPrincipalUsdt(state)
    : ZERO
  const uncappedWallet = add(wallet, cashFlow)
  const nextWallet = compare(uncappedWallet, floor) < 0
    ? floor
    : uncappedWallet
  if (compare(nextWallet, uncappedWallet) > 0) {
    bankruptcyShortfall = add(
      bankruptcyShortfall,
      subtract(nextWallet, uncappedWallet),
    )
  }
  const chargedCashFlow = subtract(nextWallet, wallet)
  state.wallets.set('USDT', nextWallet)
  state.ledger.push({
    actionId,
    asset: 'USDT',
    type: 'PERPETUAL_LIQUIDATION_CASH',
    amount: toDecimalString(chargedCashFlow),
    balanceAfter: toDecimalString(nextWallet),
  })
  if (compare(bankruptcyShortfall, ZERO) > 0) {
    state.ledger.push({
      actionId,
      asset: 'USDT',
      type: 'BANKRUPTCY_SHORTFALL',
      amount: toDecimalString(bankruptcyShortfall),
      balanceAfter: toDecimalString(nextWallet),
    })
  }
  state.liquidationTriggered = true
}

export function projectPerpetualRisk(
  state: MutableOracleState,
  tick: MarketTick,
  policy: TradingLabExecutionPolicy,
): void {
  state.liquidationTriggered = false
  revaluePerpetualPositions(state, tick)

  const crossEstimates = new Map<string, Decimal | null>()
  for (const position of state.perpetualPositions.values()) {
    position.estimatedLiquidationPrice =
      compare(position.quantity, ZERO) <= 0
        ? null
        : position.marginMode === 'ISOLATED'
      ? isolatedEstimate(position, policy)
      : (() => {
          const symbol = position.instrument.symbol
          if (!crossEstimates.has(symbol)) {
            crossEstimates.set(
              symbol,
              crossEstimateForInstrument(state, symbol, policy),
            )
          }
          return crossEstimates.get(symbol) ?? null
        })()
    if (
      position.marginMode === 'CROSS'
      && compare(position.quantity, ZERO) > 0
    ) {
      state.assumptions.add(CROSS_ESTIMATE_ASSUMPTION)
    }
  }
}

export function refreshPerpetualRisk(
  state: MutableOracleState,
  tick: MarketTick,
  policy: TradingLabExecutionPolicy,
  actionId: string,
): void {
  projectPerpetualRisk(state, tick, policy)

  const isolatedToLiquidate = Array.from(state.perpetualPositions.values())
    .filter((position) =>
      position.marginMode === 'ISOLATED'
      && compare(position.quantity, ZERO) > 0
      && compare(
        add(
          add(position.isolatedMargin, position.isolatedFundingPendingUsdt),
          position.unrealizedGrossPnl,
        ),
        liquidationThreshold(position, policy),
      ) <= 0)
  if (isolatedToLiquidate.length > 0) {
    liquidatePositions(state, isolatedToLiquidate, tick, policy, actionId)
  }

  const crossPositions = Array.from(state.perpetualPositions.values())
    .filter((position) =>
      position.marginMode === 'CROSS'
      && compare(position.quantity, ZERO) > 0)
  if (crossPositions.length > 0) {
    const crossEquity = add(
      subtract(
        state.wallets.get('USDT') ?? ZERO,
        isolatedPrincipalUsdt(state),
      ),
      totalUnrealized(state, 'CROSS'),
    )
    const crossThreshold = add(
      totalMaintenance(state, 'CROSS'),
      totalCloseFees(state, policy, 'CROSS'),
    )
    if (compare(crossEquity, crossThreshold) <= 0) {
      liquidatePositions(state, crossPositions, tick, policy, actionId)
    }
  }
}

export function calculateRiskSnapshot(state: MutableOracleState): Readonly<{
  accountSummary: AccountSummaryResult
  risk: RiskResult
}> {
  const wallet = state.wallets.get('USDT') ?? ZERO
  const unrealizedPnl = totalUnrealized(state)
  const maintenanceMargin = totalMaintenance(state)
  const equity = add(
    add(wallet, unrealizedPnl),
    isolatedFundingPnl(state),
  )
  const available = positiveOrZero(availableMarginUsdt(state))
  const marginRatio = compare(equity, ZERO) > 0
    ? divide(
        maintenanceMargin,
        equity,
        CALCULATION_SCALE,
        'HALF_UP',
      )
    : null

  const accountSummary = {
    totalWalletBalanceUsdt: wallet,
    availableBalanceUsdt: available,
    totalUnrealizedPnlUsdt: unrealizedPnl,
    equityUsdt: equity,
    totalMaintenanceMarginUsdt: maintenanceMargin,
  }
  return {
    accountSummary: {
      totalWalletBalanceUsdt:
        toDecimalString(accountSummary.totalWalletBalanceUsdt),
      availableBalanceUsdt:
        toDecimalString(accountSummary.availableBalanceUsdt),
      totalUnrealizedPnlUsdt:
        toDecimalString(accountSummary.totalUnrealizedPnlUsdt),
      equityUsdt: toDecimalString(accountSummary.equityUsdt),
      totalMaintenanceMarginUsdt:
        toDecimalString(accountSummary.totalMaintenanceMarginUsdt),
    },
    risk: {
      equityUsdt: toDecimalString(equity),
      availableBalanceUsdt: toDecimalString(available),
      maintenanceMarginUsdt: toDecimalString(maintenanceMargin),
      marginRatio: marginRatio === null ? null : toDecimalString(marginRatio),
      liquidationTriggered: state.liquidationTriggered,
    },
  }
}
