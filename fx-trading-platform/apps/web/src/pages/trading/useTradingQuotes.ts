import { useEffect, useMemo, useState } from 'react'

import { subscribeQuote } from '../../services/marketStream'
import {
  mapQuoteToTradingQuote
} from '../../features/market/tradingMarketAdapters'
import type { BackendQuote } from '../../features/market/tradingMarketAdapters'
import { fetchMarketQuote } from '../../features/market/tradingMarketApi'
import type { TradingMarket, TradingQuote } from '../../features/market/tradingModels'
import { reconcileQuoteMap } from './tradingQuoteMap'

export function useTradingQuoteMap(markets: TradingMarket[], token: string | null) {
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
      setQuotes((current) => ({ ...current, [quote.symbol]: quote }))
    }

    markets.forEach((market) => {
      void fetchMarketQuote(market.symbol).then(applyQuote).catch(() => undefined)
      releases.push(
        subscribeQuote(market.symbol, token, (quote) => {
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
  }, [symbolKey, markets, token])

  return quotes
}
