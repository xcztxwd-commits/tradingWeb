import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'

import { OrderConfirmationDialog } from './OrderConfirmationDialog'
import { OrderFormSide } from './OrderFormSide'
import { OrderTypeTabs } from './OrderTypeTabs'
import { TradePanelAccountStrip } from './TradePanelAccountStrip'
import { TradePanelLeverageControls, TradePanelLeverageToggle } from './TradePanelLeverageControls'
import { TradePanelSessionStatus, getTradePanelSessionState } from './TradePanelSessionStatus'
import type { TradePanelSessionMode } from './TradePanelSessionStatus'
import { TradeTabs } from './TradeTabs'
import { createPanelMarket } from './tradePanelMarket'
import { useMockBalances } from '../hooks/useMockBalances'
import { useTradePanelSubmit } from '../hooks/useTradePanelSubmit'
import { useTradeForm } from '../hooks/useTradeForm'
import type { OrderValidationResult, TradeBalances, TradeFormState, TradeSide } from '../types/order'
import { useMarketDataSnapshot } from '../../market/marketDataStore'
import type { OrderResponse } from '../../../components/tables/types'
import type { OrderPayload } from '../../../types/trading'
import '../styles/trade-panel.css'

const skipConfirmStorageKey = 'fx-trade-confirm-skip'

type Props = {
  symbol: string
  compact?: boolean
  accountId?: string
  balances?: TradeBalances
  minOrderAmount?: number
  pricePrecision?: number
  quantityPrecision?: number
  pricePrefill?: { id: number; price: number | string } | null
  sessionReady?: boolean
  sessionMode?: TradePanelSessionMode
  sessionError?: string | null
  loginRequired?: boolean
  onSubmitOrder?: (payload: OrderPayload) => Promise<OrderResponse | void>
  onLoginRequired?: () => void
  onRetrySession?: () => Promise<void> | void
}

const emptyBalances: TradeBalances = {}

export function TradePanel({
  symbol,
  compact = false,
  accountId,
  balances: externalBalances = emptyBalances,
  minOrderAmount = 0.0001,
  pricePrecision = 2,
  quantityPrecision = 6,
  pricePrefill = null,
  sessionReady = false,
  sessionMode = 'loading',
  sessionError = null,
  loginRequired = false,
  onSubmitOrder,
  onLoginRequired,
  onRetrySession
}: Props) {
  const { t } = useTranslation()
  const snapshot = useMarketDataSnapshot()
  const market = useMemo(() => createPanelMarket(symbol, snapshot), [snapshot, symbol])
  const mockBalances = useMockBalances()
  const balances = useMemo(() => ({ ...mockBalances, ...externalBalances }), [externalBalances, mockBalances])
  const buyForm = useTradeForm('buy', market, balances, 5, minOrderAmount)
  const sellForm = useTradeForm('sell', market, balances, 5, minOrderAmount)
  const [mobileSide, setMobileSide] = useState<TradeSide>('buy')
  const [leverage, setLeverage] = useState(100)
  const [leverageOpen, setLeverageOpen] = useState(false)
  const [skipConfirm, setSkipConfirm] = useState(readSkipConfirmPreference)
  const [confirmation, setConfirmation] = useState<{
    form: TradeFormState
    validation: OrderValidationResult
    reset: () => void
  } | null>(null)
  const backendReady = !loginRequired && Boolean(accountId && sessionReady && onSubmitOrder)
  const sessionState = getTradePanelSessionState({ backendReady, loginRequired, sessionError, sessionMode, t })
  const canTrade = backendReady
  const { attempted, handleSubmit, notice, setNotice, submittingSide } = useTradePanelSubmit({
    accountId,
    backendReady,
    canTrade,
    leverage,
    loginRequired,
    market,
    onLoginRequired,
    onSubmitOrder,
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

  const updateBothOrderTypes = (orderType: TradeFormState['orderType']) => {
    buyForm.setOrderType(orderType)
    sellForm.setOrderType(orderType)
  }

  const updateBothStrategies = (strategyType: TradeFormState['strategyType']) => {
    buyForm.setStrategyType(strategyType)
    sellForm.setStrategyType(strategyType)
  }

  const updateLeverage = (value: number) => {
    const nextLeverage = Math.min(100, Math.max(1, Math.round(value)))
    setLeverage(nextLeverage)
    setNotice(t('trading.leverageChanged', { leverage: nextLeverage }))
  }

  const updateSkipConfirm = (value: boolean) => {
    setSkipConfirm(value)
    writeSkipConfirmPreference(value)
  }

  const requestSubmit = (form: TradeFormState, validation: OrderValidationResult, reset: () => void) => {
    void handleSubmit(form, validation, reset, {
      confirmed: skipConfirm,
      onConfirmRequired: () => setConfirmation({ form, validation, reset })
    })
  }

  const confirmSubmit = () => {
    if (!confirmation) return
    const pending = confirmation
    setConfirmation(null)
    void handleSubmit(pending.form, pending.validation, pending.reset, { confirmed: true })
  }

  return (
    <section
      className={`trade-panel ${compact ? 'trade-panel--compact' : ''} trade-panel--mobile-${mobileSide}`}
      aria-label={t('trading.panelForSymbol', { symbol: market.symbol })}
    >
      <header className="trade-panel__header">
        <TradeTabs onToolsUnavailable={() => setNotice(t('trading.toolUnavailable'))} />
        <TradePanelLeverageToggle open={leverageOpen} onToggle={() => setLeverageOpen((current) => !current)} />
      </header>

      <TradePanelSessionStatus onRetrySession={onRetrySession} state={sessionState} />

      <TradePanelLeverageControls
        leverage={leverage}
        open={leverageOpen}
        onClose={() => setLeverageOpen(false)}
        onOpen={() => setLeverageOpen(true)}
        onUpdate={updateLeverage}
      />

      <OrderTypeTabs
        orderType={activeOrderType}
        strategyType={activeStrategyType}
        onOrderTypeChange={updateBothOrderTypes}
        onStrategyChange={updateBothStrategies}
        onUnavailable={(label) => setNotice(t('trading.featureUnavailable', { label }))}
      />

      <TradePanelAccountStrip accountStatus={sessionState.accountStatus} />

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
          minOrderAmount={minOrderAmount}
          pricePrecision={pricePrecision}
          quantityPrecision={quantityPrecision}
          validation={buyForm.validation}
          showErrors={attempted.buy}
          canTrade={canTrade}
          loginRequired={loginRequired}
          leverage={leverage}
          submitting={submittingSide === 'buy'}
          onFieldChange={buyForm.updateField}
          onPriceFocusChange={buyForm.setPriceFocused}
          onPercentChange={buyForm.setPercent}
          onBestPrice={buyForm.fillBestPrice}
          onSubmit={() => requestSubmit(buyForm.form, buyForm.validation, buyForm.reset)}
        />
        <OrderFormSide
          form={sellForm.form}
          market={market}
          balances={balances}
          minOrderAmount={minOrderAmount}
          pricePrecision={pricePrecision}
          quantityPrecision={quantityPrecision}
          validation={sellForm.validation}
          showErrors={attempted.sell}
          canTrade={canTrade}
          loginRequired={loginRequired}
          leverage={leverage}
          submitting={submittingSide === 'sell'}
          onFieldChange={sellForm.updateField}
          onPriceFocusChange={sellForm.setPriceFocused}
          onPercentChange={sellForm.setPercent}
          onBestPrice={sellForm.fillBestPrice}
          onSubmit={() => requestSubmit(sellForm.form, sellForm.validation, sellForm.reset)}
        />
      </div>

      <footer className="trade-panel__notice" role="status">
        <span>{notice}</span>
      </footer>

      {confirmation ? (
        <OrderConfirmationDialog
          form={confirmation.form}
          market={market}
          leverage={leverage}
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
