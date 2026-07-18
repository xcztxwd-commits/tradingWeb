import { useCallback, useEffect, useMemo, useState } from 'react'

import type {
  MockOrderPayload,
  OrderValidationResult,
  PrimaryOrderType,
  StrategyType,
  TradeBalances,
  TradeField,
  TradeFormState,
  TradeMarket,
  TradeSide
} from './orderTypes.ts'
import { formatDecimal } from './format.ts'
import {
  getMarginLeverage,
  getMarketUnitSize,
  getOrderNotional,
  isMarginQuantityMarket,
  toNumber,
  usesQuoteBudgetMarketBuy,
  usesQuoteQuantity,
  validateOrder
} from './orderValidation.ts'
import { parseSymbolAssets } from './symbols.ts'

type TradeFormHook = {
  form: TradeFormState
  validation: OrderValidationResult
  updateField: (field: TradeField, value: string | number | boolean) => void
  setPriceFocused: (focused: boolean) => void
  setOrderType: (orderType: PrimaryOrderType) => void
  setStrategyType: (strategyType: StrategyType) => void
  setAttachedProtections: (protections: TradeFormState['attachedProtections']) => void
  setPercent: (percent: number) => void
  fillBestPrice: () => void
  fillLimitPrice: (price: number | string) => LimitPriceFillResult
  reset: () => void
}

export type LimitPriceFillResult = 'updated' | 'skipped-focused' | 'invalid'

const numericFields = new Set<TradeField>([
  'price',
  'amount',
  'total',
  'takeProfitTriggerPrice',
  'takeProfitOrderPrice',
  'stopLossTriggerPrice',
  'stopLossOrderPrice',
  'trailingCallbackRatio',
  'trailingActivationPrice',
  'triggerPrice'
])

export function useTradeForm(
  side: TradeSide,
  market: TradeMarket,
  balances: TradeBalances,
  minNotional = 5,
  minAmount = 0
): TradeFormHook {
  const [form, setForm] = useState(() => createInitialTradeForm(side, market))
  const [priceTouched, setPriceTouched] = useState(false)
  const [priceFocused, setPriceFocused] = useState(false)

  useEffect(() => {
    setForm(createInitialTradeForm(side, market))
    setPriceTouched(false)
    setPriceFocused(false)
  }, [market.symbol, side])

  useEffect(() => {
    setForm((current) => syncLimitPriceFromMarket(current, market, priceTouched, priceFocused))
  }, [market, priceFocused, priceTouched])

  const validation = useMemo(
    () => validateOrder(form, { balances, market, minAmount, minNotional }),
    [balances, form, market, minAmount, minNotional]
  )

  const updateField = useCallback((field: TradeField, value: string | number | boolean) => {
    if (field === 'price') setPriceTouched(true)
    setForm((current) => {
      const nextValue = numericFields.has(field) ? normalizeNumericInput(String(value)) : value
      return deriveTradeForm(current, { [field]: nextValue } as Partial<TradeFormState>, field, market)
    })
  }, [market])

  const setOrderType = useCallback((orderType: PrimaryOrderType) => {
    setForm((current) =>
      deriveTradeForm(
        {
          ...current,
          orderType,
          price: orderType === 'market' ? '' : current.price
        },
        {},
        'orderType',
        market
      )
    )
  }, [market])

  const setStrategyType = useCallback((strategyType: StrategyType) => {
    setForm((current) => ({
      ...current,
      strategyType,
      orderType: strategyType === 'advanced_limit' ? 'limit' : current.orderType,
      tpSlEnabled: strategyType === 'none' || strategyType === 'tp_sl' ? false : current.tpSlEnabled,
      takeProfitEnabled: strategyType === 'none' || strategyType === 'tp_sl' ? false : current.takeProfitEnabled,
      stopLossEnabled: strategyType === 'none' || strategyType === 'tp_sl' ? false : current.stopLossEnabled
    }))
  }, [])

  const setAttachedProtections = useCallback((protections: TradeFormState['attachedProtections']) => {
    setForm((current) => replaceAttachedProtections(current, protections))
  }, [])

  const setPercent = useCallback(
    (percent: number) => {
      setForm((current) => applyPercent(current, percent, balances, market))
    },
    [balances, market]
  )

  const fillBestPrice = useCallback(() => {
    setPriceTouched(false)
    setForm((current) => applyBestPrice(current, market))
  }, [market])

  const fillLimitPrice = useCallback((price: number | string): LimitPriceFillResult => {
    if (priceFocused) return 'skipped-focused'
    const nextPrice = normalizeNumericInput(String(price))
    if (!nextPrice) return 'invalid'
    setPriceTouched(true)
    setForm((current) =>
      deriveTradeForm(
        {
          ...current,
          orderType: 'limit',
          price: nextPrice
        },
        {},
        'price',
        market
      )
    )
    return 'updated'
  }, [market, priceFocused])

  const reset = useCallback(() => {
    setPriceTouched(false)
    setPriceFocused(false)
    setForm(createInitialTradeForm(side, market))
  }, [market, side])

  return {
    form,
    validation,
    updateField,
    setPriceFocused,
    setOrderType,
    setStrategyType,
    setAttachedProtections,
    setPercent,
    fillBestPrice,
    fillLimitPrice,
    reset
  }
}

export function replaceAttachedProtections(
  form: TradeFormState,
  protections: TradeFormState['attachedProtections']
): TradeFormState {
  return { ...form, attachedProtections: [...protections] }
}

export function createInitialTradeForm(side: TradeSide, market: TradeMarket): TradeFormState {
  const initialPrice = side === 'buy' ? market.bestAsk : market.bestBid

  return {
    symbol: market.symbol,
    side,
    orderType: 'limit',
    strategyType: 'none',
    price: initialPrice > 0 ? formatPrice(initialPrice) : '',
    amount: '',
    total: '',
    percent: 0,
    tpSlEnabled: false,
    tpSlMode: 'both',
    takeProfitEnabled: false,
    stopLossEnabled: false,
    takeProfitTriggerPrice: '',
    takeProfitOrderPrice: '',
    stopLossTriggerPrice: '',
    stopLossOrderPrice: '',
    trailingCallbackRatio: '',
    trailingActivationPrice: '',
    triggerPrice: '',
    advancedLimitMode: 'normal',
    timeInForce: 'gtc',
    clientOrderId: createClientOrderId(),
    positionSide: 'BOTH',
    marginMode: market.productType === 'CRYPTO_SPOT' ? 'CASH' : 'CROSS',
    quantityUnit: market.quantityMode === 'contracts' ? 'CONTRACTS' : 'BASE',
    reduceOnly: false,
    attachedProtections: []
  }
}

export function deriveTradeForm(
  form: TradeFormState,
  patch: Partial<TradeFormState>,
  sourceField: TradeField,
  market?: TradeMarket
): TradeFormState {
  const next = { ...form, ...patch }
  const price = toNumber(next.price)
  const amount = toNumber(next.amount)
  const total = toNumber(next.total)

  if (next.orderType === 'market') {
    const marketPrice = market?.lastPrice ?? 0
    const unitSize = getMarketUnitSize(market)
    if ((usesQuoteBudgetMarketBuy(next, market) || usesQuoteQuantity(next, market)) && sourceField === 'total' && total > 0 && marketPrice > 0) {
      next.amount = formatDecimal(total / (marketPrice * unitSize))
    }
    if (sourceField === 'amount' && amount > 0 && marketPrice > 0) {
      next.total = formatDecimal(amount * marketPrice * unitSize)
    }
    return next
  }

  if (next.orderType === 'limit' && price > 0) {
    const unitSize = getMarketUnitSize(market)
    if ((sourceField === 'price' || sourceField === 'amount') && amount > 0) {
      next.total = formatDecimal(price * amount * unitSize)
    }

    if (sourceField === 'total' && total > 0) {
      next.amount = formatDecimal(total / (price * unitSize))
    }
  }

  return next
}

export function applyBestPrice(form: TradeFormState, market: TradeMarket): TradeFormState {
  const price = form.side === 'buy' ? market.bestAsk : market.bestBid
  if (price <= 0) return form
  return deriveTradeForm(form, { price: formatPrice(price) }, 'price', market)
}

export function syncLimitPriceFromMarket(
  form: TradeFormState,
  market: TradeMarket,
  userTouched: boolean,
  focused: boolean
): TradeFormState {
  if (form.orderType !== 'limit') return form
  const price = form.side === 'buy' ? market.bestAsk : market.bestBid
  if (price <= 0) return form

  const nextPrice = formatPrice(price)
  if (userTouched || focused || form.price === nextPrice) return form
  return deriveTradeForm(form, { price: nextPrice }, 'price')
}

export function applyPercent(
  form: TradeFormState,
  percent: number,
  balances: TradeBalances,
  market?: TradeMarket
): TradeFormState {
  const boundedPercent = Math.min(100, Math.max(0, percent))
  const { baseAsset, quoteAsset } = parseSymbolAssets(form.symbol)
  const price = toNumber(form.price) || market?.lastPrice || 0

  if (form.side === 'buy') {
    const quoteBalance = balances[quoteAsset] ?? 0
    const leverage = getMarginLeverage(market)
    const unitSize = getMarketUnitSize(market)
    const total = ((quoteBalance * boundedPercent) / 100) * (isMarginQuantityMarket(market) ? leverage : 1)
    const amount = price > 0 ? total / (price * unitSize) : 0
    return {
      ...form,
      percent: boundedPercent,
      total: total > 0 ? formatDecimal(total) : '',
      amount: amount > 0 ? formatDecimal(amount) : ''
    }
  }

  const baseBalance = balances[baseAsset] ?? 0
  const leverage = getMarginLeverage(market)
  const unitSize = getMarketUnitSize(market)
  const quoteBalance = balances[quoteAsset] ?? 0
  const amount = isMarginQuantityMarket(market)
    ? price > 0 ? ((quoteBalance * boundedPercent) / 100) * leverage / (price * unitSize) : 0
    : (baseBalance * boundedPercent) / 100
  return deriveTradeForm(
    {
      ...form,
      percent: boundedPercent,
      amount: amount > 0 ? formatDecimal(amount) : ''
    },
    {},
    'amount',
    market
  )
}

export function buildOrderPayload(form: TradeFormState, market: TradeMarket): MockOrderPayload {
  return {
    ...form,
    clientOrderId: form.clientOrderId || createClientOrderId(),
    total: form.total || formatDecimal(getOrderNotional(form, market)),
    marketPrice: market.lastPrice,
    submittedAt: new Date().toISOString()
  }
}

function formatPrice(value: number) {
  return String(value)
}

function normalizeNumericInput(value: string) {
  return value.replace(/[^\d.]/g, '').replace(/(\..*)\./g, '$1')
}

function createClientOrderId() {
  return `web_${Date.now()}_${Math.random().toString(16).slice(2, 8)}`
}
