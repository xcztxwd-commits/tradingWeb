import { useEffect, useMemo, useRef, useState } from 'react'

import { subscribeMarketSourceChanges, subscribeQuote, type MarketSourceChangedEvent } from '../../services/marketStream'
import {
  mapQuoteToTradingQuote
} from '../../features/market/tradingMarketAdapters'
import type { BackendQuote } from '../../features/market/tradingMarketAdapters'
import { isFreshSource, matchesExpectedMarketSource } from '../../features/market/authoritativeMarketSnapshot'
import { fetchMarketQuote } from '../../features/market/tradingMarketApi'
import type { MarketSourceMetadata, TradingMarket, TradingQuote } from '../../features/market/tradingModels'
import { normalizePlatformMarketSymbol } from '../../utils/marketSymbol.ts'
import { toTradingMarketDataError, type TradingMarketDataError } from './tradingPageMarketDataStatus'
import { createUnavailableTradingQuote, expireTradingQuote, reconcileQuoteMap } from './tradingQuoteMap'

const MARKET_SOURCE_NOTICE_DURATION_MS = 8_000

export function useTradingQuoteMap(
  markets: TradingMarket[],
  token: string | null,
  onQuoteStatus?: (symbol: string, error: TradingMarketDataError | null) => void
) {
  const symbolKey = useMemo(() => markets.map((market) => market.symbol).join('|'), [markets])
  const [quotes, setQuotes] = useState<Record<string, TradingQuote>>(() => reconcileQuoteMap(markets))
  const [sourceNotice, setSourceNotice] = useState<MarketSourceChangedEvent | null>(null)
  const expectedSourcesRef = useRef(new Map<
    string,
    Pick<MarketSourceMetadata, 'providerCode' | 'sourceMode'>
  >())

  useEffect(() => {
    setQuotes((current) => reconcileQuoteMap(markets, current))
  }, [symbolKey, markets])

  useEffect(() => {
    const activeSymbols = new Set(markets.map((market) => normalizePlatformMarketSymbol(market.symbol)))
    expectedSourcesRef.current.forEach((_source, symbol) => {
      if (!activeSymbols.has(symbol)) expectedSourcesRef.current.delete(symbol)
    })
  }, [symbolKey])

  useEffect(() => {
    setSourceNotice(null)
    if (markets.length === 0) return

    let active = true
    let sourceNoticeTimer: ReturnType<typeof globalThis.setTimeout> | undefined
    const releases: Array<() => void> = []
    const expiryTimers = new Map<string, ReturnType<typeof globalThis.setTimeout>>()
    const refreshTimers = new Map<string, ReturnType<typeof globalThis.setTimeout>>()
    const latestQuotes = new Map<string, TradingQuote>()
    const expectedSources = expectedSourcesRef.current
    const refreshRunning = new Set<string>()
    const refreshTrailing = new Map<string, TradingMarket>()
    const sourceGenerations = new Map<string, number>()

    const clearExpiry = (symbol: string) => {
      const timer = expiryTimers.get(symbol)
      if (timer) globalThis.clearTimeout(timer)
      expiryTimers.delete(symbol)
    }

    const clearRefresh = (symbol: string) => {
      const timer = refreshTimers.get(symbol)
      if (timer) globalThis.clearTimeout(timer)
      refreshTimers.delete(symbol)
    }

    const scheduleExpiry = (quote: TradingQuote) => {
      clearExpiry(quote.symbol)
      if (!quote.marketSource || quote.tradable !== true) return
      const expiresIn = Date.parse(quote.marketSource.expiresAt) - Date.now()
      const timer = globalThis.setTimeout(() => {
        if (!active) return
        setQuotes((current) => {
          if (!current[quote.symbol]) return current
          return { ...current, [quote.symbol]: expireTradingQuote(current[quote.symbol]) }
        })
        onQuoteStatus?.(quote.symbol, { code: 'MARKET_DATA_STALE', message: 'Market quote expired' })
      }, Math.max(0, expiresIn))
      expiryTimers.set(quote.symbol, timer)
    }

    const applyQuote = (quote: TradingQuote, targetSymbol = quote.symbol) => {
      if (!active) return
      const symbolKey = normalizePlatformMarketSymbol(targetSymbol)
      const expectedSource = expectedSources.get(symbolKey)
      const sourceMatches = Boolean(
        quote.marketSource && (!expectedSource || matchesExpectedMarketSource(quote.marketSource, expectedSource))
      )
      const normalizedQuote = quote.symbol === targetSymbol ? quote : { ...quote, symbol: targetSymbol }
      const nextQuote = sourceMatches && normalizedQuote.marketSource
        && normalizedQuote.tradable === true && isFreshSource(normalizedQuote.marketSource)
        ? normalizedQuote
        : expireTradingQuote(normalizedQuote)
      latestQuotes.set(symbolKey, nextQuote)
      onQuoteStatus?.(
        targetSymbol,
        nextQuote.tradable === false ? { code: 'MARKET_DATA_STALE', message: 'Market quote is not fresh' } : null
      )
      setQuotes((current) => ({ ...current, [targetSymbol]: nextQuote }))
      scheduleExpiry(nextQuote)
    }

    const refreshMarketQuote = (market: TradingMarket) => {
      const symbolKey = normalizePlatformMarketSymbol(market.symbol)
      if (refreshRunning.has(symbolKey)) {
        refreshTrailing.set(symbolKey, market)
        return
      }
      refreshRunning.add(symbolKey)
      const sourceGeneration = sourceGenerations.get(symbolKey) ?? 0
      void fetchMarketQuote(market.symbol, latestQuotes.get(symbolKey)).then((quote) => {
        if (!active || (sourceGenerations.get(symbolKey) ?? 0) !== sourceGeneration) return
        applyQuote(quote, market.symbol)
      }).catch((error: unknown) => {
        if (!active || (sourceGenerations.get(symbolKey) ?? 0) !== sourceGeneration) return
        clearExpiry(market.symbol)
        const unavailableQuote = createUnavailableTradingQuote(market.symbol)
        latestQuotes.set(symbolKey, unavailableQuote)
        setQuotes((current) => ({ ...current, [market.symbol]: unavailableQuote }))
        onQuoteStatus?.(market.symbol, toTradingMarketDataError(error))
      }).finally(() => {
        refreshRunning.delete(symbolKey)
        const trailingMarket = refreshTrailing.get(symbolKey)
        refreshTrailing.delete(symbolKey)
        if (active && trailingMarket) refreshMarketQuote(trailingMarket)
      })
    }

    const scheduleQuoteRefresh = (market: TradingMarket) => {
      if (refreshTimers.has(market.symbol)) return
      refreshTimers.set(market.symbol, globalThis.setTimeout(() => {
        refreshTimers.delete(market.symbol)
        refreshMarketQuote(market)
      }, 200))
    }

    markets.forEach((market) => {
      const symbolKey = normalizePlatformMarketSymbol(market.symbol)
      refreshMarketQuote(market)
      releases.push(
        subscribeQuote<BackendQuote>(market.symbol, token, (quote) => {
          const mappedQuote = mapQuoteToTradingQuote(quote, latestQuotes.get(symbolKey))
          if (!mappedQuote.marketSource) {
            scheduleQuoteRefresh(market)
            return
          }
          applyQuote(mappedQuote, market.symbol)
        }),
        subscribeMarketSourceChanges(market.symbol, token, (event) => {
          if (normalizePlatformMarketSymbol(event.symbol) !== symbolKey) return
          if (sourceNoticeTimer) globalThis.clearTimeout(sourceNoticeTimer)
          setSourceNotice(event)
          sourceNoticeTimer = globalThis.setTimeout(() => {
            sourceNoticeTimer = undefined
            setSourceNotice(null)
          }, MARKET_SOURCE_NOTICE_DURATION_MS)
          expectedSources.set(symbolKey, { providerCode: event.providerCode, sourceMode: event.sourceMode })
          sourceGenerations.set(symbolKey, (sourceGenerations.get(symbolKey) ?? 0) + 1)
          clearExpiry(market.symbol)
          clearRefresh(market.symbol)
          const unavailableQuote = createUnavailableTradingQuote(market.symbol)
          latestQuotes.set(symbolKey, unavailableQuote)
          setQuotes((current) => ({ ...current, [market.symbol]: unavailableQuote }))
          onQuoteStatus?.(market.symbol, {
            code: 'MARKET_SOURCE_CHANGING',
            message: 'Market source changed; refreshing authoritative quote'
          })
          refreshMarketQuote(market)
        })
      )
    })

    return () => {
      active = false
      expiryTimers.forEach((timer) => globalThis.clearTimeout(timer))
      refreshTimers.forEach((timer) => globalThis.clearTimeout(timer))
      if (sourceNoticeTimer) globalThis.clearTimeout(sourceNoticeTimer)
      releases.forEach((release) => release())
    }
  }, [onQuoteStatus, symbolKey, markets, token])

  return { quotes, sourceNotice }
}
