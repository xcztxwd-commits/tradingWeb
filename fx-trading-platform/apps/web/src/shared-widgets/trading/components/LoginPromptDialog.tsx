import { Activity, LogIn, ShieldCheck, X } from 'lucide-react'
import { useTranslation } from 'react-i18next'

import type { TradingSessionMode } from '@fx-platform/frontend-core'
import styles from './LoginPromptDialog.module.css'

type Props = {
  open: boolean
  sessionMode: TradingSessionMode
  sessionError?: string | null
  onClose: () => void
  onLogin: () => void
  onRetry?: () => void
}

export function LoginPromptDialog({ open, sessionMode, sessionError, onClose, onLogin, onRetry }: Props) {
  const { t } = useTranslation()

  if (!open) return null

  const showRetry = sessionMode === 'error' && Boolean(onRetry)

  return (
    <div
      className={styles.layer}
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose()
      }}
    >
      <section className={styles.dialog} role="dialog" aria-modal="true" aria-labelledby="trading-login-title">
        <button className={styles.closeButton} type="button" aria-label={t('trading.closeLoginPrompt')} data-trading-action="close-login-prompt" onClick={onClose}>
          <X size={19} aria-hidden="true" />
        </button>

        <div className={styles.icon} aria-hidden="true">
          <LogIn size={26} />
        </div>

        <p className={styles.eyebrow}>FX Trader Pro</p>
        <h2 id="trading-login-title">{t('trading.loginPromptTitle')}</h2>
        <p className={styles.summary}>
          {t('trading.loginPromptDescription')}
        </p>

        <div className={styles.featureGrid}>
          <span>
            <Activity size={16} aria-hidden="true" />
            {t('trading.marketCanView')}
          </span>
          <span>
            <ShieldCheck size={16} aria-hidden="true" />
            {t('trading.orderNeedsLogin')}
          </span>
        </div>

        {sessionError ? (
          <p className={styles.error} role="alert">
            {sessionError}
          </p>
        ) : null}

        <div className={styles.actions}>
          <button className={styles.primaryButton} type="button" data-trading-action="go-to-login" onClick={onLogin}>
            {t('trading.goLogin')}
          </button>
          <button className={styles.secondaryButton} type="button" onClick={showRetry ? onRetry : onClose}>
            {showRetry ? t('trading.retrySession') : t('trading.continueWatching')}
          </button>
        </div>
      </section>
    </div>
  )
}
