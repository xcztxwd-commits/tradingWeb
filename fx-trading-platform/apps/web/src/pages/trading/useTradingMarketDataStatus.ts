import { useCallback, useEffect, useState } from 'react'

import type { BackendMarketStatus } from '../../features/market/tradingMarketAdapters'
import { fetchMarketStatus } from '../../features/market/tradingMarketApi'
import {
  getTradingMarketDataStatusView,
  toTradingMarketDataError,
  type TradingMarketDataError
} from './tradingPageMarketDataStatus'

export function useTradingMarketDataStatus() {
  const [marketDataStatus, setMarketDataStatus] = useState<BackendMarketStatus | null>(null)
  const [marketDataStatusError, setMarketDataStatusError] = useState<TradingMarketDataError | null>(null)
  const [quoteErrors, setQuoteErrors] = useState<Record<string, TradingMarketDataError>>({})

  const handleQuoteStatus = useCallback((symbol: string, error: TradingMarketDataError | null) => {
    setQuoteErrors((current) => {
      const next = { ...current }
      if (error) next[symbol] = error
      else delete next[symbol]
      return next
    })
  }, [])

  const getStatusView = useCallback(
    (symbol: string, quoteSource: string) =>
      getTradingMarketDataStatusView(marketDataStatus, quoteSource, quoteErrors[symbol] ?? marketDataStatusError),
    [marketDataStatus, marketDataStatusError, quoteErrors]
  )

  useEffect(() => {
    let active = true

    void fetchMarketStatus()
      .then((status) => {
        if (!active) return
        setMarketDataStatus(status)
        setMarketDataStatusError(null)
      })
      .catch((error: unknown) => {
        if (active) setMarketDataStatusError(toTradingMarketDataError(error))
      })

    return () => {
      active = false
    }
  }, [])

  return { getStatusView, handleQuoteStatus }
}
