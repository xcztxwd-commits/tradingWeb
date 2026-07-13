import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'

import { OrderConfirmationDialog } from './OrderConfirmationDialog'
import { OrderFormSide } from './OrderFormSide'
import { OrderTypeTabs } from './OrderTypeTabs'
import { getTradePanelSessionState } from './TradePanelSessionStatus'
import type { TradePanelSessionMode } from './TradePanelSessionStatus'
import { TradeTabs } from './TradeTabs'
import { createPanelMarket } from './tradePanelMarket'
import { useTradePanelSubmit } from '../hooks/useTradePanelSubmit'
import type { CanonicalSubmitPayload } from '../hooks/useTradePanelSubmit'
import type { OrderAdapterSettings } from '../services/orderAdapter'
import { useTradeForm } from '../hooks/useTradeForm'
import type { OrderValidationResult, TradeBalances, TradeFormState, TradeMarket, TradeSide } from '../types/order'
import { useMarketDataSnapshot } from '../../market/marketDataStore'
import type { OrderResponse } from '../../../components/tables/types'
import type { OrderPayload } from '../../../types/trading'
import type { OcoOrderGroupResponse } from '@fx-platform/shared-types'
import type { OcoOrderPayload } from '../../../types/trading'
import '../styles/trade-panel.css'
const skipConfirmStorageKey = 'fx-trade-confirm-skip'

type Props = {
  symbol: string
  category?: 'fx' | 'crypto' | 'metals' | 'indices'
  compact?: boolean
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

const emptyBalances: TradeBalances = {}

export function TradePanel({
  symbol,
  category,
  compact = false,
  accountId,
  balances: externalBalances = emptyBalances,
  leverage: symbolLeverage, adapterSettings = {}, settingsReady = true, productType, rules,
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
}: Props) {
  const { t } = useTranslation()
  const snapshot = useMarketDataSnapshot()
  const leverage = resolveTradePanelLeverage(resolveRuleLeverage(adapterSettings.leverage ?? symbolLeverage, rules))
  const market = useMemo(() => createPanelMarket(symbol, snapshot, { category, leverage, productType, rules }), [category, leverage, productType, rules, snapshot, symbol])
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
  const { attempted, handleSubmit, notice, setNotice, submittingSide } = useTradePanelSubmit({
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

  const activeOrderType = mobileSide === 'buy' ? buyForm.form.orderType : sellForm.form.orderType
  const activeStrategyType = mobileSide === 'buy' ? buyForm.form.strategyType : sellForm.form.strategyType

  useEffect(() => {
    if (!pricePrefill) return
    const results = [buyForm.fillLimitPrice(pricePrefill.price), sellForm.fillLimitPrice(pricePrefill.price)]
    if (results.includes('updated')) {
      setNotice(
        results.includes('skipped-focused')
          ? t('trading.priceFilledSkipFocused', { price: pricePrefill.price })
          : t('trading.priceFilled', { price: pricePrefill.price })
      )
    } else if (results.includes('skipped-focused')) {
      setNotice(t('trading.editingPrice'))
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

  return (
    <section
      className={`trade-panel ${compact ? 'trade-panel--compact' : ''} trade-panel--mobile-${mobileSide}`}
      aria-label={t('trading.panelForSymbol', { symbol: market.symbol })}
    >
      <header className="trade-panel__header">
        <TradeTabs productType={market.productType} />
      </header>

      <OrderTypeTabs
        orderType={activeOrderType}
        strategyType={activeStrategyType}
        allowOco={market.productType === 'CRYPTO_SPOT'}
        onOrderTypeChange={updateBothOrderTypes}
        onStrategyTypeChange={updateBothStrategyTypes}
      />

      <div className="trade-panel__mobile-sides" role="tablist" aria-label={t('trading.sideTabs')}>
        <button
          type="button"
          className={mobileSide === 'buy' ? 'trade-panel__mobile-side--active' : ''}
          onClick={() => setMobileSide('buy')}
        >
          {t('common.buy')}
        </button>
        <button
          type="button"
          className={mobileSide === 'sell' ? 'trade-panel__mobile-side--active' : ''}
          onClick={() => setMobileSide('sell')}
        >
          {t('common.sell')}
        </button>
      </div>

      <div className="trade-panel__forms trade-panel__forms--dual">
        <OrderFormSide
          form={buyForm.form}
          market={market}
          balances={balances}
          minOrderAmount={effectiveMinOrderAmount}
          pricePrecision={effectivePricePrecision}
          quantityPrecision={effectiveQuantityPrecision}
          validation={buyForm.validation}
          showErrors={attempted.buy}
          canTrade={canTrade}
          loginRequired={loginRequired}
          submitting={submittingSide === 'buy'}
          onFieldChange={buyForm.updateField}
          onPriceFocusChange={buyForm.setPriceFocused}
          onPercentChange={buyForm.setPercent}
          onBestPrice={buyForm.fillBestPrice}
          positionMode={adapterSettings.positionMode}
          onProtectionsChange={buyForm.setAttachedProtections}
          onSubmit={() => requestSubmit(buyForm.form, buyForm.validation, buyForm.reset)}
        />
        <OrderFormSide
          form={sellForm.form}
          market={market}
          balances={balances}
          minOrderAmount={effectiveMinOrderAmount}
          pricePrecision={effectivePricePrecision}
          quantityPrecision={effectiveQuantityPrecision}
          validation={sellForm.validation}
          showErrors={attempted.sell}
          canTrade={canTrade}
          loginRequired={loginRequired}
          submitting={submittingSide === 'sell'}
          onFieldChange={sellForm.updateField}
          onPriceFocusChange={sellForm.setPriceFocused}
          onPercentChange={sellForm.setPercent}
          onBestPrice={sellForm.fillBestPrice}
          positionMode={adapterSettings.positionMode}
          onProtectionsChange={sellForm.setAttachedProtections}
          onSubmit={() => requestSubmit(sellForm.form, sellForm.validation, sellForm.reset)}
        />
      </div>

      {notice ? (
        <footer className="trade-panel__notice" role="status">
          <span>{notice}</span>
        </footer>
      ) : null}

      {confirmation ? (
        <OrderConfirmationDialog
          payload={confirmation.payload}
          skipConfirm={skipConfirm}
          submitting={submittingSide === confirmation.form.side}
          onCancel={() => setConfirmation(null)}
          onConfirm={confirmSubmit}
          onSkipConfirmChange={updateSkipConfirm}
        />
      ) : null}
    </section>
  )
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
    // Ignore storage failures; the checkbox state still works for this session.
  }
}

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
