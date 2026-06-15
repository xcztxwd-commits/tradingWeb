import { useEffect, useMemo, useState } from 'react'

import { subscribeQuote } from '../../services/marketStream'
import {
  mapQuoteToTradingQuote
} from '../../features/market/tradingMarketAdapters'
import type { BackendQuote } from '../../features/market/tradingMarketAdapters'
import { fetchMarketQuote } from '../../features/market/tradingMarketApi'
import type { TradingMarket, TradingQuote } from '../../features/market/tradingModels'
import { toTradingMarketDataError, type TradingMarketDataError } from './tradingPageMarketDataStatus'
import { reconcileQuoteMap } from './tradingQuoteMap'

export function useTradingQuoteMap(
  markets: TradingMarket[],
  token: string | null,
  onQuoteStatus?: (symbol: string, error: TradingMarketDataError | null) => void
) {
  const symbolKey = useMemo(() => markets.map((market) => market.symbol).join('|'), [markets])
  const [quotes, setQuotes] = useState<Record<string, TradingQuote>>(() => reconcileQuoteMap(markets))

  useEffect(() => {
    setQuotes((current) => reconcileQuoteMap(markets, current))
  }, [symbolKey, markets])

  useEffect(() => {
    if (markets.length === 0) return

    let active = true
    const releases: Array<() => void> = []

    const applyQuote = (quote: TradingQuote) => {
      if (!active) return
      onQuoteStatus?.(quote.symbol, null)
      setQuotes((current) => ({ ...current, [quote.symbol]: quote }))
    }

    markets.forEach((market) => {
      void fetchMarketQuote(market.symbol).then(applyQuote).catch((error: unknown) => {
        if (active) onQuoteStatus?.(market.symbol, toTradingMarketDataError(error))
      })
      releases.push(
        subscribeQuote(market.symbol, token, (quote) => {
          onQuoteStatus?.((quote as BackendQuote).symbol, null)
          setQuotes((current) => {
            const nextQuote = mapQuoteToTradingQuote(quote as BackendQuote, current[market.symbol])
            return { ...current, [market.symbol]: nextQuote }
          })
        })
      )
    })

    return () => {
      active = false
      releases.forEach((release) => release())
    }
  }, [onQuoteStatus, symbolKey, markets, token])

  return quotes
}
