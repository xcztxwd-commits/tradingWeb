import { useCallback, useEffect, useMemo, useState } from 'react'
import type { TFunction } from 'i18next'
import { useTranslation } from 'react-i18next'

import type {
  MockOrderPayload,
  OrderValidationErrorKey,
  OrderValidationResult,
  PrimaryOrderType,
  StrategyType,
  TradeBalances,
  TradeField,
  TradeFormState,
  TradeMarket,
  TradeSide
} from '../types/order'
import { formatDecimal } from '../utils/format.ts'
import { parseSymbolAssets } from '../utils/symbols.ts'

type TradeFormHook = {
  form: TradeFormState
  validation: OrderValidationResult
  updateField: (field: TradeField, value: string | number | boolean) => void
  setPriceFocused: (focused: boolean) => void
  setOrderType: (orderType: PrimaryOrderType) => void
  setStrategyType: (strategyType: StrategyType) => void
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

type ValidationOptions = {
  balances: TradeBalances
  market: TradeMarket
  minAmount: number
  minNotional: number
}

const validationMessageKeys: Record<OrderValidationErrorKey, string> = {
  price: 'validation.pricePositive',
  amount: 'validation.amountPositive',
  minAmount: 'validation.minAmount',
  minNotional: 'validation.minNotional',
  quoteBalance: 'validation.quoteBalance',
  baseBalance: 'validation.baseBalance',
  takeProfitTriggerPrice: 'validation.takeProfitTriggerPrice',
  stopLossTriggerPrice: 'validation.stopLossTriggerPrice',
  trailingCallbackRatio: 'validation.trailingCallbackRatio',
  triggerPrice: 'validation.triggerPrice'
}

export function useTradeForm(
  side: TradeSide,
  market: TradeMarket,
  balances: TradeBalances,
  minNotional = 5,
  minAmount = 0
): TradeFormHook {
  const { t } = useTranslation()
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
    () => validateOrder(form, { balances, market, minAmount, minNotional }, t),
    [balances, form, market, minAmount, minNotional, t]
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
    setPercent,
    fillBestPrice,
    fillLimitPrice,
    reset
  }
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
    clientOrderId: createClientOrderId()
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
    if (usesQuoteBudgetMarketBuy(next, market) && sourceField === 'total' && total > 0 && marketPrice > 0) {
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

export function validateOrder(form: TradeFormState, options: ValidationOptions, t?: TFunction): OrderValidationResult {
  const errors: OrderValidationErrorKey[] = []
  const price = toNumber(form.price)
  const amount = toNumber(form.amount)
  const total = getOrderNotional(form, options.market)
  const requiredMargin = getRequiredMargin(form, options.market)
  const quoteBalance = options.balances[options.market.quoteAsset] ?? 0
  const baseBalance = options.balances[options.market.baseAsset] ?? 0

  if (form.orderType !== 'market' && price <= 0) {
    errors.push('price')
  }

  if (amount <= 0) {
    errors.push('amount')
    return buildValidationResult(errors, t)
  }

  if (options.minAmount > 0 && amount < options.minAmount) {
    errors.push('minAmount')
  }

  if (total < options.minNotional) {
    errors.push('minNotional')
  }

  if (form.side === 'buy' && requiredMargin > quoteBalance) {
    errors.push('quoteBalance')
  }

  if (form.side === 'sell' && isMarginQuantityMarket(options.market) && requiredMargin > quoteBalance) {
    errors.push('quoteBalance')
  }

  if (form.side === 'sell' && !isMarginQuantityMarket(options.market) && amount > baseBalance) {
    errors.push('baseBalance')
  }

  if (form.tpSlEnabled || form.strategyType === 'tp_sl') {
    if (form.takeProfitEnabled && toNumber(form.takeProfitTriggerPrice) <= 0) {
      errors.push('takeProfitTriggerPrice')
    }
    if (form.stopLossEnabled && toNumber(form.stopLossTriggerPrice) <= 0) {
      errors.push('stopLossTriggerPrice')
    }
  }

  if (form.strategyType === 'trailing_tp_sl' && toNumber(form.trailingCallbackRatio) <= 0) {
    errors.push('trailingCallbackRatio')
  }

  if (form.strategyType === 'trigger' && toNumber(form.triggerPrice) <= 0) {
    errors.push('triggerPrice')
  }

  return buildValidationResult(errors, t)
}

export function getOrderNotional(form: TradeFormState, market: TradeMarket) {
  const total = toNumber(form.total)
  if (usesQuoteBudgetMarketBuy(form, market) && total > 0) return total

  const amount = toNumber(form.amount)
  const price = form.orderType === 'market' ? market.lastPrice : toNumber(form.price)
  return amount * price * getMarketUnitSize(market)
}

export function getRequiredMargin(form: TradeFormState, market: TradeMarket) {
  const notional = getOrderNotional(form, market)
  return isMarginQuantityMarket(market) ? notional / getMarginLeverage(market) : notional
}

export function usesQuoteBudgetMarketBuy(form: Pick<TradeFormState, 'side' | 'orderType'>, market?: TradeMarket) {
  return form.side === 'buy' && form.orderType === 'market' && market?.quantityMode === 'quote-budget'
}

export function isMarginQuantityMarket(market?: TradeMarket) {
  return market?.quantityMode === 'quantity' || market?.quantityMode === 'contracts'
}

function getMarketUnitSize(market?: TradeMarket) {
  return market?.unitSize && market.unitSize > 0 ? market.unitSize : 1
}

function getMarginLeverage(market?: TradeMarket) {
  return market?.leverage && market.leverage > 0 ? market.leverage : 1
}

export function toNumber(value: string | number | undefined) {
  if (typeof value === 'number') return Number.isFinite(value) ? value : 0
  if (!value) return 0
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : 0
}

function formatPrice(value: number) {
  return String(value)
}

function normalizeNumericInput(value: string) {
  return value.replace(/[^\d.]/g, '').replace(/(\..*)\./g, '$1')
}

function createClientOrderId() {
  return `mock_${Date.now()}_${Math.random().toString(16).slice(2, 8)}`
}

function buildValidationResult(errors: OrderValidationErrorKey[], t?: TFunction): OrderValidationResult {
  return {
    errors,
    fieldErrors: Object.fromEntries(errors.map((error) => [error, t ? t(validationMessageKeys[error]) : validationMessageKeys[error]])),
    canSubmit: errors.length === 0
  }
}
