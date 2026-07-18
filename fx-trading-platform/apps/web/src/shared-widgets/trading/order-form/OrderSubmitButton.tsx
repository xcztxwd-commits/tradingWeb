import { Loader2 } from 'lucide-react'
import { useTranslation } from 'react-i18next'

import type { TradeSide } from '@fx-platform/frontend-core'

type Props = {
  side: TradeSide
  baseAsset: string
  canTrade: boolean
  disabledReason?: string
  loginRequired?: boolean
  submitting: boolean
  onClick: () => void
}

export function OrderSubmitButton({ side, baseAsset, canTrade, disabledReason, loginRequired = false, submitting, onClick }: Props) {
  const { t } = useTranslation()
  const sideLabel = side === 'buy' ? t('trading.openLong') : t('trading.openShort')
  const label = loginRequired || !canTrade ? t('auth.loginAccount') : sideLabel
  const disabled = submitting || Boolean(disabledReason)

  return (
    <button
      type="button"
      className={`trade-panel__submit trade-panel__submit--${side} ${loginRequired ? 'trade-panel__submit--login' : ''}`}
      disabled={disabled}
      title={disabledReason}
      aria-label={disabledReason ?? (loginRequired ? t('auth.loginAccount') : t('trading.sideOrder', { side: sideLabel, asset: baseAsset }))}
      onClick={onClick}
    >
      {submitting ? <Loader2 size={15} className="trade-panel__spin" aria-hidden="true" /> : null}
      {label}
    </button>
  )
}
