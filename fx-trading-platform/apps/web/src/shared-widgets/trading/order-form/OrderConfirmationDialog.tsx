import { Dialog } from '@fx-platform/ui'
import { Bitcoin, X } from 'lucide-react'
import { createPortal } from 'react-dom'
import { useTranslation } from 'react-i18next'

import type { CanonicalSubmitPayload, OcoOrderPayload } from '@fx-platform/frontend-core'
import styles from './TradePanel.module.css'

type Props = {
  payload: CanonicalSubmitPayload
  skipConfirm: boolean
  submitting: boolean
  onCancel: () => void
  onConfirm: () => void
  onSkipConfirmChange: (value: boolean) => void
}

export function OrderConfirmationDialog({
  payload,
  skipConfirm,
  submitting,
  onCancel,
  onConfirm,
  onSkipConfirmChange
}: Props) {
  const { t } = useTranslation()
  const sideLabel = payload.side === 'BUY' ? t('common.buy') : t('common.sell')
  const rows = buildConfirmationRows(payload, t('trading.marketOrder'))

  const dialog = (
    <Dialog
      open
      onClose={onCancel}
      ariaLabel={t('trading.orderConfirmAria', { side: sideLabel, symbol: payload.symbol })}
      closeLabel={t('trading.closeOrderConfirm')}
      pending={submitting}
      priority="critical"
      className={styles['trade-panel__confirm-layer']}
      backdropClassName={styles['trade-panel__confirm-backdrop']}
      panelClassName={styles['trade-panel__confirm']}
    >
      <header className={styles['trade-panel__confirm-head']}>
        <strong>{t('trading.orderConfirmTitle')}</strong>
        <button type="button" aria-label={t('trading.closeOrderConfirm')} disabled={submitting} onClick={onCancel}>
          <X size={22} aria-hidden="true" />
        </button>
      </header>

      <div className={styles['trade-panel__confirm-symbol']}>
        <span className={styles['trade-panel__confirm-coin']} aria-hidden="true"><Bitcoin size={14} /></span>
        <strong>{payload.symbol}</strong>
        <span className={`${styles['trade-panel__confirm-side']} ${styles[`trade-panel__confirm-side--${payload.side.toLowerCase()}`]}`}>{sideLabel}</span>
      </div>

      <dl className={`${styles['trade-panel__confirm-grid']} ${styles['trade-panel__confirm-grid--primary']}`}>
        {rows.map((row) => <ConfirmItem key={row.label} label={row.label} value={row.value} />)}
      </dl>

      <p className={styles['trade-panel__confirm-risk']}>{t('trading.orderConfirmRisk')}</p>
      <label className={styles['trade-panel__confirm-skip']}>
        <input type="checkbox" checked={skipConfirm} onChange={(event) => onSkipConfirmChange(event.target.checked)} />
        <span>{t('trading.skipConfirm')}</span>
      </label>
      <footer className={styles['trade-panel__confirm-actions']}>
        <button type="button" className={styles['trade-panel__confirm-cancel']} disabled={submitting} onClick={onCancel}>{t('common.cancel')}</button>
        <button type="button" className={styles['trade-panel__confirm-submit']} disabled={submitting} onClick={onConfirm}>
          {submitting ? t('trading.submitting') : t('common.confirm')}
        </button>
      </footer>
    </Dialog>
  )
  if (typeof document === 'undefined') return dialog
  return createPortal(dialog, document.body)
}

export function buildConfirmationRows(payload: CanonicalSubmitPayload, marketOrderLabel: string) {
  if (isOcoPayload(payload)) {
    return [
      { label: 'Order type', value: 'OCO / GTC' },
      { label: 'Quantity', value: `${payload.quantity} ${payload.quantityUnit}` },
      { label: 'Limit price', value: String(payload.limitPrice) },
      { label: 'Stop trigger', value: `${payload.stopTriggerPrice} ${payload.triggerPriceType ?? 'LAST_PRICE'}` }
    ]
  }

  const rows = [
    { label: 'Order type', value: `${payload.orderType}${payload.orderType === 'LIMIT' ? ' / GTC' : ''}` },
    { label: 'Quantity', value: `${payload.quantity} ${payload.quantityUnit}` },
    { label: 'Price', value: payload.orderType === 'LIMIT' ? String(payload.price) : marketOrderLabel }
  ]
  if (payload.triggerPrice !== undefined) rows.push({ label: 'Trigger', value: `${payload.triggerPrice} ${payload.triggerPriceType ?? ''}`.trim() })
  if (payload.marginMode !== 'CASH') {
    rows.push({ label: 'Position side', value: payload.positionSide })
    rows.push({ label: 'Margin / leverage', value: `${payload.marginMode} / ${payload.leverage ?? '--'}x` })
    rows.push({ label: 'Reduce only', value: payload.reduceOnly ? 'Yes' : 'No' })
    rows.push({ label: 'Attached TP / SL', value: String(payload.attachedProtections?.length ?? 0) })
  }
  return rows
}

function isOcoPayload(payload: CanonicalSubmitPayload): payload is OcoOrderPayload {
  return 'limitPrice' in payload
}

function ConfirmItem({ label, value }: { label: string; value: string }) {
  return <div><dt>{label}</dt><dd>{value}</dd></div>
}
