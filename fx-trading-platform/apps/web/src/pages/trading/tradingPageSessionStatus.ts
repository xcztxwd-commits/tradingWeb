import type { SessionAuthStatus } from '@fx-platform/frontend-core'
import type { TradingSessionMode } from '../../features/trading-session/useTradingSession'

type Translate = (key: string, options?: Record<string, unknown>) => string

const defaultTranslate: Translate = (key) => key

export function getTradingSessionStatusLabel(
  mode: TradingSessionMode,
  sessionAuthStatus: SessionAuthStatus,
  t: Translate = defaultTranslate
) {
  if (mode === 'error') return t('trading.sessionLinkError')

  switch (sessionAuthStatus) {
    case 'guest':
      return mode === 'loading' ? t('trading.checkingLoginStatus') : t('trading.publicMarketMode')
    case 'valid_token':
      return t('trading.accountLinkConnected')
    case 'invalid_token':
      return t('trading.loginExpired')
  }
}

export function getTradingSessionStatusText({
  sessionMode,
  sessionAuthStatus,
  sessionError,
  loginRequired,
  t = defaultTranslate
}: {
  sessionMode: TradingSessionMode
  sessionAuthStatus: SessionAuthStatus
  sessionError?: string | null
  loginRequired: boolean
  t?: Translate
}) {
  if (sessionAuthStatus === 'invalid_token') return t('trading.loginExpiredRetry')
  if (sessionMode === 'error') return sessionError ?? t('trading.backendSessionFailedRetry')
  if (sessionAuthStatus === 'valid_token') return t('trading.tokenValidConnected')
  if (loginRequired) return t('trading.guestTradingNeedsLogin')

  switch (sessionMode) {
    case 'loading':
      return t('trading.checkingLoginStatus')
    case 'ready':
      return t('trading.marketAndChartAvailable')
    case 'login-required':
    default:
      return t('trading.guestTradingNeedsLogin')
  }
}
