import type { TradeFormState, TradeMarket } from '../types/order'
import { formatDecimal } from '../utils/format.ts'
import type { OrderPayload } from '../../../types/trading'

export function toOrderPayload(accountId: string, form: TradeFormState, market: TradeMarket, leverage?: number): OrderPayload {
  const orderType = form.strategyType === 'trigger' ? 'STOP' : form.orderType === 'market' ? 'MARKET' : 'LIMIT'
  const quantity = getLots(form, market)
  const price = orderType === 'MARKET' ? undefined : getRequestedPrice(form)
  const clientOrderId = form.clientOrderId || crypto.randomUUID()

  return {
    accountId,
    symbol: form.symbol.replace(/[-_/]/g, '').toUpperCase(),
    side: form.side === 'buy' ? 'BUY' : 'SELL',
    orderType,
    quantity,
    price,
    clientOrderId,
    lots: quantity,
    requestedPrice: price,
    stopLoss: form.stopLossEnabled ? firstValue(form.stopLossTriggerPrice, form.stopLossOrderPrice) : undefined,
    takeProfit: form.takeProfitEnabled ? firstValue(form.takeProfitTriggerPrice, form.takeProfitOrderPrice) : undefined,
    idempotencyKey: clientOrderId,
    leverage: normalizeLeverage(leverage)
  }
}

function normalizeLeverage(leverage: number | undefined) {
  if (leverage === undefined || !Number.isFinite(leverage) || leverage <= 0) return undefined
  return Math.round(leverage)
}

function getLots(form: TradeFormState, market: TradeMarket) {
  if (Number(form.amount) > 0) return form.amount
  if (form.side !== 'buy' || form.orderType !== 'market') return form.amount

  const marketPrice = market.lastPrice > 0 ? market.lastPrice : market.bestAsk
  const total = Number(form.total)
  if (!Number.isFinite(total) || total <= 0 || marketPrice <= 0) return form.amount
  return formatDecimal(total / marketPrice)
}

function getRequestedPrice(form: TradeFormState) {
  if (form.strategyType === 'trigger') return firstValue(form.triggerPrice, form.price)
  return firstValue(form.price, form.triggerPrice)
}

function firstValue(...values: string[]) {
  return values.find((value) => value.trim() !== '') || undefined
}
