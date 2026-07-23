import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'

import {
  useMarketDataSnapshot,
  useTradeForm,
  useTradeSubmit,
  type CanonicalSubmitPayload,
  type OcoOrderPayload,
  type OrderAdapterSettings,
  type OrderPayload,
  type OrderResponse,
  type OrderValidationResult,
  type TradeBalances,
  type TradeFormState,
  type TradeMarket,
  type TradeSide
} from '@fx-platform/frontend-core'
import type { OcoOrderGroupResponse } from '@fx-platform/shared-types'
import { translateCoreMessage } from '../../../routes/shared/translateCoreMessage'
import { getTradePanelSessionState, type TradePanelSessionMode } from './TradePanelSessionStatus'
import { createPanelMarket } from './tradePanelMarket'

const skipConfirmStorageKey = 'fx-trade-confirm-skip'
const emptyBalances: TradeBalances = {}

export type TradePanelControllerOptions = {
  symbol: string
  category?: 'fx' | 'crypto' | 'metals' | 'indices'
  accountId?: string
  balances?: TradeBalances
  leverage?: number
  adapterSettings?: OrderAdapterSettings
  settingsReady?: boolean
  productType?: TradeMarket['productType']
  rules?: TradeMarket['rules']
  minOrderAmount?: number
  pricePrecision?: number
  quantityPrecision?: number
  pricePrefill?: { id: number; price: number | string } | null
  sessionReady?: boolean
  sessionMode?: TradePanelSessionMode
  sessionError?: string | null
  loginRequired?: boolean
  onSubmitOrder?: (payload: OrderPayload) => Promise<OrderResponse | void>
  onSubmitOco?: (payload: OcoOrderPayload) => Promise<OcoOrderGroupResponse | void>
  onLoginRequired?: () => void
  onRetrySession?: () => Promise<void> | void
}

export function useTradePanelController({
  symbol,
  category,
  accountId,
  balances: externalBalances = emptyBalances,
  leverage: symbolLeverage,
  adapterSettings = {},
  settingsReady = true,
  productType,
  rules,
  minOrderAmount = 0.0001,
  pricePrecision = 2,
  quantityPrecision = 6,
  pricePrefill = null,
  sessionReady = false,
  sessionMode = 'loading',
  sessionError = null,
  loginRequired = false,
  onSubmitOrder,
  onSubmitOco,
  onLoginRequired,
  onRetrySession
}: TradePanelControllerOptions) {
  const { t } = useTranslation()
  const snapshot = useMarketDataSnapshot()
  const leverage = resolveTradePanelLeverage(resolveRuleLeverage(adapterSettings.leverage ?? symbolLeverage, rules))
  const market = useMemo(
    () => createPanelMarket(symbol, snapshot, { category, leverage, productType, rules }),
    [category, leverage, productType, rules, snapshot, symbol]
  )
  const balances = externalBalances
  const effectiveMinOrderAmount = resolveRuleNumber(rules?.minQty ?? rules?.minLot, minOrderAmount)
  const minNotional = resolveRuleNumber(rules?.minNotional, 5)
  const effectivePricePrecision = resolvePrecision(rules?.tickSize, pricePrecision)
  const effectiveQuantityPrecision = resolvePrecision(rules?.stepSize, quantityPrecision)
  const buyForm = useTradeForm('buy', market, balances, minNotional, effectiveMinOrderAmount)
  const sellForm = useTradeForm('sell', market, balances, minNotional, effectiveMinOrderAmount)
  const [mobileSide, setMobileSide] = useState<TradeSide>('buy')
  const [skipConfirm, setSkipConfirm] = useState(readSkipConfirmPreference)
  const [confirmation, setConfirmation] = useState<{
    form: TradeFormState
    payload: CanonicalSubmitPayload
    validation: OrderValidationResult
    reset: () => void
  } | null>(null)
  const backendReady = !loginRequired && Boolean(accountId && sessionReady && onSubmitOrder)
  const sessionState = getTradePanelSessionState({ backendReady, loginRequired, sessionError, sessionMode, t })
  const rulesTradable = Boolean(rules?.enabled && rules.tradable && rules.orderEnabled)
  const marketDataReady = market.tradable === true
  const perpetual = market.productType === 'LINEAR_PERP'
  const canTrade = marketDataReady && backendReady && rulesTradable && (!perpetual || settingsReady)
  const { attempted, handleSubmit, notice: noticeMessage, setNotice, submittingSide } = useTradeSubmit({
    accountId,
    backendReady,
    canTrade,
    adapterSettings: { ...adapterSettings, leverage },
    loginRequired,
    market,
    onLoginRequired,
    onSubmitOrder,
    onSubmitOco,
    sessionHasError: sessionState.sessionHasError
  })
  const notice = translateCoreMessage(noticeMessage, t)
  const activeOrderType = mobileSide === 'buy' ? buyForm.form.orderType : sellForm.form.orderType
  const activeStrategyType = mobileSide === 'buy' ? buyForm.form.strategyType : sellForm.form.strategyType

  useEffect(() => {
    if (!pricePrefill) return
    const results = [buyForm.fillLimitPrice(pricePrefill.price), sellForm.fillLimitPrice(pricePrefill.price)]
    if (results.includes('updated')) {
      setNotice(
        results.includes('skipped-focused')
          ? { key: 'trading.priceFilledSkipFocused', values: { price: pricePrefill.price } }
          : { key: 'trading.priceFilled', values: { price: pricePrefill.price } }
      )
    } else if (results.includes('skipped-focused')) {
      setNotice({ key: 'trading.editingPrice' })
    }
  }, [pricePrefill?.id])

  useEffect(() => {
    setConfirmation(null)
  }, [market.symbol])

  useEffect(() => {
    if (!perpetual) return
    buyForm.updateField('quantityUnit', adapterSettings.quantityUnit ?? 'BASE')
    sellForm.updateField('quantityUnit', adapterSettings.quantityUnit ?? 'BASE')
    buyForm.updateField('marginMode', adapterSettings.marginMode ?? 'CROSS')
    sellForm.updateField('marginMode', adapterSettings.marginMode ?? 'CROSS')
  }, [adapterSettings.marginMode, adapterSettings.quantityUnit, perpetual])

  const updateBothOrderTypes = (orderType: TradeFormState['orderType']) => {
    buyForm.setOrderType(orderType)
    sellForm.setOrderType(orderType)
    buyForm.setStrategyType('none')
    sellForm.setStrategyType('none')
  }
  const updateBothStrategyTypes = (strategyType: 'trigger' | 'oco') => {
    buyForm.setOrderType(strategyType === 'oco' ? 'limit' : 'market')
    sellForm.setOrderType(strategyType === 'oco' ? 'limit' : 'market')
    buyForm.setStrategyType(strategyType)
    sellForm.setStrategyType(strategyType)
  }
  const updateSkipConfirm = (value: boolean) => {
    setSkipConfirm(value)
    writeSkipConfirmPreference(value)
  }
  const requestSubmit = (form: TradeFormState, validation: OrderValidationResult, reset: () => void) => {
    void handleSubmit(form, validation, reset, {
      confirmed: skipConfirm,
      onConfirmRequired: (payload) => setConfirmation({ form, payload, validation, reset })
    })
  }
  const confirmSubmit = () => {
    if (!confirmation) return
    const pending = confirmation
    setConfirmation(null)
    void handleSubmit(pending.form, pending.validation, pending.reset, { confirmed: true, payload: pending.payload })
  }

  return {
    activeOrderType,
    activeStrategyType,
    attempted,
    balances,
    buyForm,
    canTrade,
    confirmation,
    confirmSubmit,
    effectiveMinOrderAmount,
    effectivePricePrecision,
    effectiveQuantityPrecision,
    loginRequired,
    market,
    mobileSide,
    notice,
    onRetrySession,
    positionMode: adapterSettings.positionMode,
    readiness: { backendReady, marketDataReady, rulesTradable, settingsReady },
    requestSubmit,
    sellForm,
    sessionState,
    setConfirmation,
    setMobileSide,
    skipConfirm,
    submittingSide,
    updateBothOrderTypes,
    updateBothStrategyTypes,
    updateSkipConfirm
  }
}

export type TradePanelControllerModel = ReturnType<typeof useTradePanelController>

export function resolveTradePanelLeverage(leverage?: number) {
  if (leverage === undefined || !Number.isFinite(leverage) || leverage <= 0) return 1
  return Math.round(leverage)
}

function resolveRuleLeverage(leverage?: number, rules?: TradeMarket['rules']) {
  const requested = leverage ?? rules?.defaultLeverage
  if (!rules?.maxLeverage || !requested) return requested
  return Math.min(requested, rules.maxLeverage)
}

function resolveRuleNumber(value: number | undefined, fallback: number) {
  return value !== undefined && Number.isFinite(value) && value > 0 ? value : fallback
}

function resolvePrecision(step: number | undefined, fallback: number) {
  if (!step || !Number.isFinite(step) || step <= 0) return fallback
  const text = String(step)
  return text.includes('.') ? text.split('.')[1]?.replace(/0+$/, '').length ?? fallback : 0
}

function readSkipConfirmPreference() {
  if (typeof window === 'undefined') return false
  try {
    return window.localStorage.getItem(skipConfirmStorageKey) === 'true'
  } catch {
    return false
  }
}

function writeSkipConfirmPreference(value: boolean) {
  if (typeof window === 'undefined') return
  try {
    if (value) window.localStorage.setItem(skipConfirmStorageKey, 'true')
    else window.localStorage.removeItem(skipConfirmStorageKey)
  } catch {
    // The in-memory preference still works when browser storage is unavailable.
  }
}
