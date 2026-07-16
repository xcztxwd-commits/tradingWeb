import { Navigate } from 'react-router-dom'

import { resolveTradingPath } from './hooks/useLastTradingSymbol'

export function LegacyTradingRedirect() {
  return <Navigate to={resolveTradingPath('spot')} replace />
}
