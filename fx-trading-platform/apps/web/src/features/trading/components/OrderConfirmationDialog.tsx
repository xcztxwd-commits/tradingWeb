import { Bitcoin, X } from 'lucide-react'
import { useEffect } from 'react'
import { useTranslation } from 'react-i18next'

import { getOrderNotional } from '../hooks/useTradeForm'
import type { TradeFormState, TradeMarket } from '../types/order'
import { formatDecimal } from '../utils/format'

type Props = {
  form: TradeFormState
  market: TradeMarket
  leverage: number
  skipConfirm: boolean
  submitting: boolean
  onCancel: () => void
  onConfirm: () => void
  onSkipConfirmChange: (value: boolean) => void
}

export function OrderConfirmationDialog({
  form,
  market,
  leverage,
  skipConfirm,
  submitting,
  onCancel,
  onConfirm,
  onSkipConfirmChange
}: Props) {
  const { t } = useTranslation()
  const sideLabel = form.side === 'buy' ? t('trading.openLong') : t('trading.openShort')
  const notional = getOrderNotional(form, market)
  const margin = leverage > 0 ? notional / leverage : notional

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onCancel()
    }

    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [onCancel])

  return (
    <div className="trade-panel__confirm-layer" role="presentation">
      <button type="button" className="trade-panel__confirm-backdrop" aria-label={t('trading.closeOrderConfirm')} onClick={onCancel} />
      <section
        className="trade-panel__confirm"
        role="dialog"
        aria-modal="true"
        aria-label={t('trading.orderConfirmAria', { side: sideLabel, symbol: market.symbol })}
      >
        <header className="trade-panel__confirm-head">
          <strong>{t('trading.orderConfirmTitle')}</strong>
          <button type="button" aria-label={t('trading.closeOrderConfirm')} onClick={onCancel}>
            <X size={22} aria-hidden="true" />
          </button>
        </header>

        <div className="trade-panel__confirm-symbol">
          <span className="trade-panel__confirm-coin" aria-hidden="true">
            <Bitcoin size={14} />
          </span>
          <strong>{market.symbol.replace('-', '')} {t('trading.perpetual')}</strong>
          <span className={`trade-panel__confirm-side trade-panel__confirm-side--${form.side}`}>{sideLabel}</span>
        </div>

        <dl className="trade-panel__confirm-grid trade-panel__confirm-grid--primary">
          <ConfirmItem label={t('common.price')} value={formatPrice(form, market, t('trading.marketOrder'))} />
          <ConfirmItem label={t('common.quantity')} value={formatAmount(form, market, t('trading.contractsUnit'))} />
          <ConfirmItem label={t('positions.margin')} value={formatMargin(margin)} />
          <ConfirmItem
            label={t('trading.orderType')}
            value={form.orderType === 'market' ? t('trading.crossMarketOrder') : t('trading.crossLimitOrder')}
          />
        </dl>

        <dl className="trade-panel__confirm-grid trade-panel__confirm-grid--tpsl">
          <ConfirmItem label={t('trading.takeProfitTriggerPrice')} value={formatTriggerPrice(form.takeProfitTriggerPrice, market.quoteAsset)} />
          <ConfirmItem label={t('trading.takeProfitOrderPrice')} value={formatOrderPrice(form.takeProfitOrderPrice, form.takeProfitTriggerPrice, form.orderType, t('trading.marketOrder'))} />
          <ConfirmItem label={t('trading.stopLossTriggerPrice')} value={formatTriggerPrice(form.stopLossTriggerPrice, market.quoteAsset)} />
          <ConfirmItem label={t('trading.stopLossOrderPrice')} value={formatOrderPrice(form.stopLossOrderPrice, form.stopLossTriggerPrice, form.orderType, t('trading.marketOrder'))} />
        </dl>

        <p className="trade-panel__confirm-risk">{t('trading.orderConfirmRisk')}</p>

        <label className="trade-panel__confirm-skip">
          <input type="checkbox" checked={skipConfirm} onChange={(event) => onSkipConfirmChange(event.target.checked)} />
          <span>{t('trading.skipConfirm')}</span>
        </label>

        <footer className="trade-panel__confirm-actions">
          <button type="button" className="trade-panel__confirm-cancel" onClick={onCancel}>
            {t('common.cancel')}
          </button>
          <button type="button" className="trade-panel__confirm-submit" disabled={submitting} onClick={onConfirm}>
            {submitting ? t('trading.submitting') : t('common.confirm')}
          </button>
        </footer>
      </section>
    </div>
  )
}

function ConfirmItem({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  )
}

function formatPrice(form: TradeFormState, market: TradeMarket, marketOrderLabel: string) {
  if (form.orderType === 'market') return marketOrderLabel
  return `${form.price || '--'} ${market.quoteAsset}`
}

function formatAmount(form: TradeFormState, market: TradeMarket, unit: string) {
  const notional = getOrderNotional(form, market)
  const amount = Number(form.amount) > 0 ? Number(form.amount) : notional > 0 && market.lastPrice > 0 ? notional / market.lastPrice : 0
  if (!Number.isFinite(amount) || amount <= 0) return `-- ${unit}`
  return `${formatDecimal(amount) || amount.toFixed(4)} ${unit}`
}

function formatMargin(value: number) {
  if (!Number.isFinite(value) || value <= 0) return '--'
  return formatDecimal(value) || value.toFixed(2)
}

function formatTriggerPrice(value: string, unit: string) {
  if (!value) return '--'
  return `${value} ${unit}`
}

function formatOrderPrice(orderPrice: string, triggerPrice: string, orderType: TradeFormState['orderType'], marketOrderLabel: string) {
  if (orderPrice) return orderPrice
  if (triggerPrice && orderType === 'market') return marketOrderLabel
  return '--'
}
