import { ApiClientError } from '@fx-platform/frontend-core'
import type { BackendMarketStatus } from '@fx-platform/frontend-core'

export type TradingMarketDataError = {
  code: string
  message: string
  requestId?: string
}

export type TradingMarketDataStatusView = {
  label: string
  detail: string
  tone: 'live' | 'demo' | 'error' | 'unknown'
}

export function toTradingMarketDataError(error: unknown): TradingMarketDataError {
  if (error instanceof ApiClientError) {
    return {
      code: error.code || 'QUOTE_PROVIDER_UNAVAILABLE',
      message: error.message,
      requestId: error.requestId
    }
  }
  if (error instanceof Error) {
    return { code: 'QUOTE_PROVIDER_UNAVAILABLE', message: error.message }
  }
  return { code: 'QUOTE_PROVIDER_UNAVAILABLE', message: 'Quote provider unavailable' }
}

export function getTradingMarketDataStatusView(
  status: BackendMarketStatus | null,
  quoteSource: string,
  error?: TradingMarketDataError | null
): TradingMarketDataStatusView {
  const label = marketDataSourceLabel(quoteSource, status)
  const statusCode = status?.status ?? 'MARKET_STATUS_UNKNOWN'
  const providerStatus = status?.providerStatus ?? 'unknown'
  const reason = error
    ? `${error.code}: ${error.message}`
    : status?.failureCode
      ? `${status.failureCode}: ${status.failureReason ?? 'Provider is not available'}`
      : status?.failureReason ?? 'Market status has not loaded yet'

  return {
    label,
    detail: `${statusCode} | provider=${providerStatus} | ${reason}`,
    tone: error || status?.failureCode ? 'error' : sourceTone(label)
  }
}

function marketDataSourceLabel(quoteSource: string, status: BackendMarketStatus | null) {
  if (quoteSource.startsWith('massive') || status?.sourceMode === 'massive') return 'Massive data'
  if (quoteSource.startsWith('demo') || quoteSource === 'markets.sources.mock' || status?.sourceMode === 'demo') {
    return 'Demo quote'
  }
  if (status?.sourceMode === 'required') return 'Provider required'
  return quoteSource || 'Unknown source'
}

function sourceTone(label: string): TradingMarketDataStatusView['tone'] {
  if (label === 'Massive data') return 'live'
  if (label === 'Demo quote') return 'demo'
  if (label === 'Provider required') return 'error'
  return 'unknown'
}
