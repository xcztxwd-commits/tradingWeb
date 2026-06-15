import type { TFunction } from 'i18next'
import { useTranslation } from 'react-i18next'

export type TradePanelSessionMode = 'loading' | 'ready' | 'login-required' | 'error'

type SessionStatusArgs = {
  backendReady: boolean
  loginRequired: boolean
  sessionMode: TradePanelSessionMode
  sessionError?: string | null
  t: TFunction
}

export type TradePanelSessionState = {
  accountStatus: string
  sessionBadge: string
  sessionErrorText: string
  sessionHasError: boolean
}

export function getTradePanelSessionState({
  backendReady,
  loginRequired,
  sessionMode,
  sessionError,
  t
}: SessionStatusArgs): TradePanelSessionState {
  const sessionHasError = sessionMode === 'error'
  return {
    accountStatus: backendReady
      ? t('trading.accountReady')
      : loginRequired
        ? t('trading.accountLoginRequired')
        : sessionHasError
          ? t('trading.connectionError')
          : t('trading.notReady'),
    sessionBadge: backendReady
      ? t('trading.sessionConnected')
      : loginRequired
        ? t('trading.loginFirst')
        : sessionHasError
          ? t('trading.sessionDisconnected')
          : t('trading.connecting'),
    sessionErrorText: sessionError ? t('trading.sessionError', { error: sessionError }) : t('trading.sessionErrorFallback'),
    sessionHasError
  }
}

type Props = {
  onRetrySession?: () => Promise<void> | void
  state: TradePanelSessionState
}

export function TradePanelSessionStatus({ onRetrySession, state }: Props) {
  const { t } = useTranslation()

  return (
    <>
      <div className="trade-panel__session-status" aria-live="polite">
        <span>{state.sessionBadge}</span>
        <small>{state.accountStatus}</small>
      </div>

      {state.sessionHasError ? (
        <div className="trade-panel__session-error" role="alert">
          <span>{state.sessionErrorText}</span>
          {onRetrySession ? (
            <button type="button" onClick={() => void onRetrySession()}>
              {t('common.retryConnection')}
            </button>
          ) : null}
        </div>
      ) : null}
    </>
  )
}
