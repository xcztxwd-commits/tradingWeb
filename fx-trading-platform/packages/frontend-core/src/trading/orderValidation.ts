import type { CoreMessage } from '../coreMessage.ts'
import type {
  OrderValidationErrorKey,
  OrderValidationResult,
  TradeBalances,
  TradeFormState,
  TradeMarket
} from './orderTypes.ts'

export type ValidationOptions = {
  balances: TradeBalances
  market: TradeMarket
  minAmount: number
  minNotional: number
  now?: number
}

const validationMessageKeys: Record<OrderValidationErrorKey, string> = {
  price: 'validation.pricePositive',
  amount: 'validation.amountPositive',
  minAmount: 'validation.minAmount',
  minNotional: 'validation.minNotional',
  quoteBalance: 'validation.quoteBalance',
  baseBalance: 'validation.baseBalance',
  marketStale: 'validation.marketStale',
  takeProfitTriggerPrice: 'validation.takeProfitTriggerPrice',
  stopLossTriggerPrice: 'validation.stopLossTriggerPrice',
  trailingCallbackRatio: 'validation.trailingCallbackRatio',
  triggerPrice: 'validation.triggerPrice',
  attachedProtections: 'validation.triggerPrice'
}

export function validateOrder(form: TradeFormState, options: ValidationOptions): OrderValidationResult {
  const errors: OrderValidationErrorKey[] = []
  const price = toNumber(form.price)
  const amount = toNumber(form.amount)
  const total = getOrderNotional(form, options.market)
  const requiredMargin = getRequiredMargin(form, options.market)
  const quoteBalance = options.balances[options.market.quoteAsset] ?? 0
  const baseBalance = options.balances[options.market.baseAsset] ?? 0

  if (form.orderType !== 'market' && price <= 0) errors.push('price')
  if (amount <= 0) {
    errors.push('amount')
    return buildValidationResult(errors)
  }
  if (form.orderType === 'market' && isMarketQuoteStale(options.market, options.now)) errors.push('marketStale')
  if (options.minAmount > 0 && amount < options.minAmount) errors.push('minAmount')
  if (total < options.minNotional) errors.push('minNotional')
  if (form.side === 'buy' && requiredMargin > quoteBalance) errors.push('quoteBalance')
  if (form.side === 'sell' && isMarginQuantityMarket(options.market) && requiredMargin > quoteBalance) {
    errors.push('quoteBalance')
  }
  if (form.side === 'sell' && !isMarginQuantityMarket(options.market) && amount > baseBalance) {
    errors.push('baseBalance')
  }

  if (form.tpSlEnabled || form.strategyType === 'tp_sl') {
    if (form.takeProfitEnabled && toNumber(form.takeProfitTriggerPrice) <= 0) errors.push('takeProfitTriggerPrice')
    if (form.stopLossEnabled && toNumber(form.stopLossTriggerPrice) <= 0) errors.push('stopLossTriggerPrice')
  }
  if (form.strategyType === 'trailing_tp_sl' && toNumber(form.trailingCallbackRatio) <= 0) {
    errors.push('trailingCallbackRatio')
  }
  if ((form.strategyType === 'trigger' || form.strategyType === 'oco') && toNumber(form.triggerPrice) <= 0) {
    errors.push('triggerPrice')
  }

  const parentProtectionQuantity = form.quantityUnit === 'QUOTE' ? toNumber(form.total) : amount
  const protectionTotals = { TAKE_PROFIT: 0, STOP_LOSS: 0 }
  const invalidProtection = form.attachedProtections.some((protection) => {
    const quantity = toNumber(protection.quantity)
    protectionTotals[protection.protectionType] += quantity
    return toNumber(protection.triggerPrice) <= 0
      || (protection.triggerExecutionType === 'LIMIT' && toNumber(protection.price) <= 0)
      || quantity <= 0
      || protection.quantityUnit !== form.quantityUnit
      || protectionTotals[protection.protectionType] > parentProtectionQuantity
  })
  if (form.attachedProtections.length > 10 || invalidProtection) errors.push('attachedProtections')

  return buildValidationResult(errors)
}

export function getOrderNotional(form: TradeFormState, market: TradeMarket) {
  const total = toNumber(form.total)
  if ((usesQuoteBudgetMarketBuy(form, market) || usesQuoteQuantity(form, market)) && total > 0) return total
  const amount = toNumber(form.amount)
  const price = form.orderType === 'market' ? market.lastPrice : toNumber(form.price)
  return amount * price * getMarketUnitSize(market)
}

export function getRequiredMargin(form: TradeFormState, market: TradeMarket) {
  const notional = getOrderNotional(form, market)
  return isMarginQuantityMarket(market) ? notional / getMarginLeverage(market) : notional
}

export function usesQuoteBudgetMarketBuy(
  form: Pick<TradeFormState, 'side' | 'orderType' | 'strategyType'>,
  market?: TradeMarket
) {
  return form.side === 'buy'
    && form.orderType === 'market'
    && form.strategyType !== 'trigger'
    && market?.quantityMode === 'quote-budget'
}

export function usesQuoteQuantity(form: Pick<TradeFormState, 'quantityUnit'>, market?: TradeMarket) {
  return isMarginQuantityMarket(market) && form.quantityUnit === 'QUOTE'
}

export function isMarginQuantityMarket(market?: TradeMarket) {
  return market?.quantityMode === 'quantity' || market?.quantityMode === 'contracts'
}

export function isMarketQuoteStale(market: TradeMarket, now = Date.now()) {
  if (market.lastPrice <= 0) return true
  if (market.quoteTimestamp === undefined) return false
  return now - market.quoteTimestamp > 15_000
}

export function getMarketUnitSize(market?: TradeMarket) {
  return market?.unitSize && market.unitSize > 0 ? market.unitSize : 1
}

export function getMarginLeverage(market?: TradeMarket) {
  return market?.leverage && market.leverage > 0 ? market.leverage : 1
}

export function toNumber(value: string | number | undefined) {
  if (typeof value === 'number') return Number.isFinite(value) ? value : 0
  if (!value) return 0
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : 0
}

function buildValidationResult(errors: OrderValidationErrorKey[]): OrderValidationResult {
  const fieldErrors = Object.fromEntries(
    errors.map((error) => [error, { key: validationMessageKeys[error] } satisfies CoreMessage])
  )
  return { errors, fieldErrors, canSubmit: errors.length === 0 }
}
