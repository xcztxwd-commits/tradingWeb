import { subscribeMarketSourceChanges, subscribeOrderBook, subscribeQuote, subscribeRecentTrades } from './marketStream.ts'
import type { MarketSourceChangedEvent } from './marketStream.ts'
import { createAuthoritativeMarketSnapshot, matchesExpectedMarketSource, unavailableSnapshot } from './authoritativeMarketSnapshot.ts'
import { fetchMarketOrderBook, fetchMarketQuote, fetchMarketRecentTradeBatch } from './tradingMarketApi.ts'
import type { MarketOrderBook, MarketTradeBatch } from './marketDataTypes.ts'
import type { MarketSourceMode, TradingQuote } from './tradingModels.ts'
import { marketDataStore } from './marketDataStore.ts'

type AdapterStore = typeof marketDataStore

type ActiveAdapterSession = {
  symbol: string
  token: string | null
  store: AdapterStore
  refs: number
  stop: () => void
}

let activeSession: ActiveAdapterSession | undefined

export function startQuoteMarketDataAdapter(symbol: string, token: string | null, store: AdapterStore = marketDataStore) {
  if (activeSession?.symbol === symbol && activeSession.token === token && activeSession.store === store) {
    activeSession.refs += 1
    return createRelease(activeSession)
  }

  if (activeSession) {
    activeSession.stop()
    activeSession = undefined
  }

  const session: ActiveAdapterSession = {
    symbol,
    token,
    store,
    refs: 1,
    stop: startQuoteSession(symbol, token, store)
  }
  activeSession = session

  return createRelease(session)
}

function startQuoteSession(symbol: string, token: string | null, store: AdapterStore) {
  let latestQuote: TradingQuote | undefined
  let latestOrderBook: MarketOrderBook | undefined
  let latestTrades: MarketTradeBatch | undefined
  let expectedSource: { providerCode: string; sourceMode: MarketSourceMode } | undefined
  let disposed = false
  let expiryTimer: ReturnType<typeof globalThis.setTimeout> | undefined
  let refreshTimer: ReturnType<typeof globalThis.setTimeout> | undefined
  let refreshRunning = false
  let refreshTrailing = false

  store.reset(unavailableSnapshot('loading'))

  const publish = () => {
    if (disposed) return
    if (expiryTimer) globalThis.clearTimeout(expiryTimer)
    expiryTimer = undefined

    const snapshot = createAuthoritativeMarketSnapshot(latestQuote, latestOrderBook, latestTrades)
    if (expectedSource) {
      if (snapshot.status !== 'ready' || !snapshot.source || !matchesExpectedMarketSource(snapshot.source, expectedSource)) {
        store.reset(unavailableSnapshot('source-changing'))
        return
      }
    }
    store.reset(snapshot)
    if (snapshot.status !== 'ready' || !snapshot.source) return

    const expiresIn = Date.parse(snapshot.source.expiresAt) - Date.now()
    expiryTimer = globalThis.setTimeout(() => {
      if (!disposed) store.reset(unavailableSnapshot('stale', snapshot.source))
    }, Math.max(0, expiresIn))
  }

  const refreshBundle = async () => {
    if (disposed) return
    if (refreshRunning) {
      refreshTrailing = true
      return
    }
    refreshRunning = true
    try {
      const [quote, orderBook, trades] = await Promise.all([
        fetchMarketQuote(symbol),
        fetchMarketOrderBook(symbol),
        fetchMarketRecentTradeBatch(symbol)
      ])
      if (disposed) return
      latestQuote = quote
      latestOrderBook = orderBook
      latestTrades = trades
      publish()
    } catch {
      if (!disposed) store.reset(unavailableSnapshot(expectedSource ? 'source-changing' : 'unavailable'))
    } finally {
      refreshRunning = false
      if (!disposed && refreshTrailing) {
        refreshTrailing = false
        void refreshBundle()
      }
    }
  }

  const scheduleBundleRefresh = () => {
    if (disposed || refreshTimer) return
    refreshTimer = globalThis.setTimeout(() => {
      refreshTimer = undefined
      void refreshBundle()
    }, 200)
  }

  void refreshBundle()

  const unsubscribeQuote = subscribeQuote(symbol, token, scheduleBundleRefresh)
  const unsubscribeOrderBook = subscribeOrderBook(symbol, token, scheduleBundleRefresh)
  const unsubscribeRecentTrades = subscribeRecentTrades(symbol, token, scheduleBundleRefresh)
  const unsubscribeSourceChanges = subscribeMarketSourceChanges(symbol, token, (event: MarketSourceChangedEvent) => {
    expectedSource = { providerCode: event.providerCode, sourceMode: event.sourceMode }
    latestQuote = undefined
    latestOrderBook = undefined
    latestTrades = undefined
    if (expiryTimer) globalThis.clearTimeout(expiryTimer)
    expiryTimer = undefined
    store.reset(unavailableSnapshot('source-changing'))
    void refreshBundle()
  })

  return () => {
    disposed = true
    if (expiryTimer) globalThis.clearTimeout(expiryTimer)
    if (refreshTimer) globalThis.clearTimeout(refreshTimer)
    refreshTrailing = false
    unsubscribeQuote()
    unsubscribeOrderBook()
    unsubscribeRecentTrades()
    unsubscribeSourceChanges()
  }
}

function createRelease(session: ActiveAdapterSession) {
  let released = false

  return () => {
    if (released) return
    released = true
    session.refs -= 1

    if (session.refs > 0 || activeSession !== session) return
    session.stop()
    activeSession = undefined
  }
}
