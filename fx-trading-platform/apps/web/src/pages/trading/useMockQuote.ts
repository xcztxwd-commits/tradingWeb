import { useEffect, useMemo, useState } from 'react'

import { getInitialMockQuote, subscribeMockQuote } from './tradingMarketStream'
import type { TradingQuote } from '../../features/market/tradingModels'

export function useMockQuote(symbol: string, throttleMs = 320) {
  const [quote, setQuote] = useState(() => getInitialMockQuote(symbol))

  useEffect(() => {
    setQuote(getInitialMockQuote(symbol))

    let lastCommit = 0
    let timeoutId: number | undefined
    let latestQuote: TradingQuote | undefined

    const flush = () => {
      if (!latestQuote) return
      lastCommit = window.performance.now()
      setQuote(latestQuote)
      latestQuote = undefined
    }

    const unsubscribe = subscribeMockQuote(symbol, (nextQuote) => {
      latestQuote = nextQuote
      const now = window.performance.now()
      const elapsed = now - lastCommit

      if (elapsed >= throttleMs) {
        if (timeoutId) {
          window.clearTimeout(timeoutId)
          timeoutId = undefined
        }
        flush()
        return
      }

      if (!timeoutId) {
        timeoutId = window.setTimeout(() => {
          timeoutId = undefined
          flush()
        }, throttleMs - elapsed)
      }
    })

    return () => {
      unsubscribe()
      if (timeoutId) {
        window.clearTimeout(timeoutId)
      }
    }
  }, [symbol, throttleMs])

  return quote
}

export function useMockQuoteMap(symbols: string[], throttleMs = 420) {
  const symbolKey = symbols.join('|')
  const stableSymbols = useMemo(() => symbolKey.split('|').filter(Boolean), [symbolKey])
  const [quotes, setQuotes] = useState<Record<string, TradingQuote>>(() =>
    Object.fromEntries(stableSymbols.map((symbol) => [symbol, getInitialMockQuote(symbol)]))
  )

  useEffect(() => {
    setQuotes(Object.fromEntries(stableSymbols.map((symbol) => [symbol, getInitialMockQuote(symbol)])))

    let frameId: number | undefined
    const pending = new Map<string, TradingQuote>()
    const flush = () => {
      frameId = undefined
      setQuotes((current) => {
        const next = { ...current }
        pending.forEach((quote, symbol) => {
          next[symbol] = quote
        })
        pending.clear()
        return next
      })
    }

    const unsubscribers = stableSymbols.map((symbol) =>
      subscribeMockQuote(symbol, (quote) => {
        pending.set(symbol, quote)
        if (!frameId) {
          frameId = window.setTimeout(flush, throttleMs)
        }
      })
    )

    return () => {
      unsubscribers.forEach((unsubscribe) => unsubscribe())
      if (frameId) {
        window.clearTimeout(frameId)
      }
    }
  }, [stableSymbols, throttleMs])

  return quotes
}
