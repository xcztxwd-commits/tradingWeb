import type {
  CreateOrderRequest,
  MarginMode,
  PositionMode,
  PositionSide,
  QuantityUnit
} from '@fx-platform/shared-types'

import type { OcoOrderPayload, OrderPayload } from '@fx-platform/frontend-core'
import type { TradeFormState, TradeMarket } from '../types/order'

export const CANONICAL_TIME_IN_FORCE = 'GTC' as const

export type OrderAdapterSettings = {
  leverage?: number
  positionMode?: PositionMode
  positionSide?: PositionSide
  marginMode?: MarginMode
  quantityUnit?: QuantityUnit
  reduceOnly?: boolean
  attachedProtections?: NonNullable<CreateOrderRequest['attachedProtections']>
}

export function toOrderPayload(
  accountId: string,
  form: TradeFormState,
  market: TradeMarket,
  leverageOrSettings?: number | OrderAdapterSettings
): OrderPayload {
  const settings = typeof leverageOrSettings === 'number'
    ? { leverage: leverageOrSettings }
    : leverageOrSettings ?? {}
  const perpetual = isPerpetual(market)
  const orderType = form.strategyType === 'trigger'
    ? 'STOP_MARKET'
    : form.orderType === 'market'
      ? 'MARKET'
      : 'LIMIT'
  const quantityUnit = resolveQuantityUnit(form, market, settings, perpetual)
  const clientOrderId = form.clientOrderId || crypto.randomUUID()
  const price = orderType === 'LIMIT' ? positiveDecimal(form.price, 'price') : undefined
  const triggerPrice = orderType === 'STOP_MARKET'
    ? positiveDecimal(form.triggerPrice, 'triggerPrice')
    : undefined

  return {
    accountId,
    symbol: canonicalSymbol(form.symbol),
    side: form.side === 'buy' ? 'BUY' : 'SELL',
    orderType,
    quantity: positiveDecimal(resolveQuantityValue(form, quantityUnit), 'quantity'),
    price,
    idempotencyKey: clientOrderId,
    clientOrderId,
    leverage: perpetual ? normalizeLeverage(settings.leverage ?? market.leverage) : undefined,
    positionSide: perpetual ? resolvePositionSide(form, settings) : 'BOTH',
    quantityUnit,
    marginMode: perpetual ? resolvePerpetualMarginMode(form, settings) : 'CASH',
    triggerPrice,
    triggerPriceType: triggerPrice === undefined
      ? undefined
      : perpetual ? 'MARK_PRICE' : 'LAST_PRICE',
    reduceOnly: perpetual ? settings.reduceOnly ?? form.reduceOnly : false,
    attachedProtections: perpetual
      ? settings.attachedProtections ?? form.attachedProtections
      : []
  } satisfies OrderPayload
}

export function toOcoOrderPayload(
  accountId: string,
  form: TradeFormState,
  market: TradeMarket
): OcoOrderPayload {
  if (isPerpetual(market)) {
    throw new RangeError('OCO is only supported for Spot markets')
  }
  const clientOrderId = form.clientOrderId || crypto.randomUUID()
  return {
    accountId,
    symbol: canonicalSymbol(form.symbol),
    side: form.side === 'buy' ? 'BUY' : 'SELL',
    quantity: positiveDecimal(form.amount, 'quantity'),
    quantityUnit: 'BASE',
    limitPrice: positiveDecimal(form.price, 'limitPrice'),
    stopTriggerPrice: positiveDecimal(form.triggerPrice, 'stopTriggerPrice'),
    triggerPriceType: 'LAST_PRICE',
    idempotencyKey: clientOrderId,
    clientOrderId
  } satisfies OcoOrderPayload
}

function resolveQuantityUnit(
  form: TradeFormState,
  market: TradeMarket,
  settings: OrderAdapterSettings,
  perpetual: boolean
): QuantityUnit {
  if (!perpetual) {
    return form.side === 'buy' && form.orderType === 'market' && form.strategyType !== 'trigger'
      ? 'QUOTE'
      : 'BASE'
  }
  return settings.quantityUnit ?? form.quantityUnit ?? (market.quantityMode === 'contracts' ? 'CONTRACTS' : 'BASE')
}

function resolveQuantityValue(form: TradeFormState, quantityUnit: QuantityUnit) {
  return quantityUnit === 'QUOTE' ? form.total : form.amount
}

function resolvePositionSide(form: TradeFormState, settings: OrderAdapterSettings): PositionSide {
  if (settings.positionMode !== 'HEDGE') return 'BOTH'
  const positionSide = settings.positionSide ?? form.positionSide
  return positionSide === 'LONG' || positionSide === 'SHORT' ? positionSide : form.side === 'buy' ? 'LONG' : 'SHORT'
}

function resolvePerpetualMarginMode(form: TradeFormState, settings: OrderAdapterSettings): MarginMode {
  const marginMode = settings.marginMode ?? form.marginMode
  return marginMode === 'ISOLATED' ? 'ISOLATED' : 'CROSS'
}

function normalizeLeverage(leverage: number | undefined) {
  if (leverage === undefined || !Number.isFinite(leverage) || leverage <= 0) return undefined
  return Math.round(leverage)
}

function positiveDecimal(value: string | number | undefined, field: string) {
  const parsed = typeof value === 'number' ? value : Number(value)
  if (!Number.isFinite(parsed) || parsed <= 0) {
    throw new RangeError(`${field} must be a finite positive number`)
  }
  return parsed
}

function isPerpetual(market: TradeMarket) {
  return market.productType === 'LINEAR_PERP' || market.symbol.toUpperCase().endsWith('-PERP')
}

function canonicalSymbol(symbol: string) {
  const normalized = symbol.trim().toUpperCase()
  if (normalized.endsWith('-PERP')) return normalized
  return normalized.replace(/[-_/]/g, '')
}
