import { Loader2 } from 'lucide-react'
import { useTranslation } from 'react-i18next'

import type { TradeSide } from '../types/order'

type Props = {
  side: TradeSide
  baseAsset: string
  canTrade: boolean
  loginRequired?: boolean
  submitting: boolean
  onClick: () => void
}

export function OrderSubmitButton({ side, baseAsset, canTrade, loginRequired = false, submitting, onClick }: Props) {
  const { t } = useTranslation()
  const sideLabel = side === 'buy' ? t('trading.openLong') : t('trading.openShort')
  const label = loginRequired ? t('auth.loginAccount') : canTrade ? sideLabel : t('trading.sessionNotReady')

  return (
    <button
      type="button"
      className={`trade-panel__submit trade-panel__submit--${side} ${loginRequired ? 'trade-panel__submit--login' : ''}`}
      disabled={submitting}
      aria-label={loginRequired ? t('auth.loginAccount') : t('trading.sideOrder', { side: sideLabel, asset: baseAsset })}
      onClick={onClick}
    >
      {submitting ? <Loader2 size={15} className="trade-panel__spin" aria-hidden="true" /> : null}
      {label}
    </button>
  )
}
