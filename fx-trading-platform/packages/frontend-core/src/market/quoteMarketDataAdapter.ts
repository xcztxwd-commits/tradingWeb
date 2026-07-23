import { subscribeMarketSourceChanges, subscribeOrderBook, subscribeQuote, subscribeRecentTrades } from './marketStream.ts'
import type { MarketSourceChangedEvent } from './marketStream.ts'
import { createAuthoritativeMarketSnapshot, matchesExpectedMarketSource, unavailableSnapshot } from './authoritativeMarketSnapshot.ts'
import { fetchMarketOrderBook, fetchMarketQuote, fetchMarketRecentTradeBatch } from './tradingMarketApi.ts'
import type { MarketOrderBook, MarketTradeBatch } from './marketDataTypes.ts'
import type { MarketSourceMode, TradingQuote } from './tradingModels.ts'
import { marketDataStore } from './marketDataStore.ts'

type AdapterStore = typeof marketDataStore

type RefreshSubscription = (symbol: string, token: string | null, onRefresh: () => void) => () => void

export type QuoteMarketDataSessionDependencies = {
  fetchQuote: (symbol: string, signal: AbortSignal) => Promise<TradingQuote>
  fetchOrderBook: (symbol: string, signal: AbortSignal) => Promise<MarketOrderBook>
  fetchTrades: (symbol: string, signal: AbortSignal) => Promise<MarketTradeBatch>
  subscribeQuote: RefreshSubscription
  subscribeOrderBook: RefreshSubscription
  subscribeRecentTrades: RefreshSubscription
  subscribeSourceChanges: (
    symbol: string,
    token: string | null,
    onSourceChange: (event: MarketSourceChangedEvent) => void
  ) => () => void
}

const defaultSessionDependencies: QuoteMarketDataSessionDependencies = {
  fetchQuote: (symbol, signal) => fetchMarketQuote(symbol, undefined, signal),
  fetchOrderBook: (symbol, signal) => fetchMarketOrderBook(symbol, signal),
  fetchTrades: (symbol, signal) => fetchMarketRecentTradeBatch(symbol, 40, signal),
  subscribeQuote: (symbol, token, onRefresh) => subscribeQuote(symbol, token, onRefresh),
  subscribeOrderBook: (symbol, token, onRefresh) => subscribeOrderBook(symbol, token, onRefresh),
  subscribeRecentTrades: (symbol, token, onRefresh) => subscribeRecentTrades(symbol, token, onRefresh),
  subscribeSourceChanges: (symbol, token, onSourceChange) =>
    subscribeMarketSourceChanges(symbol, token, onSourceChange)
}

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

export function startQuoteSession(
  symbol: string,
  token: string | null,
  store: AdapterStore,
  dependencies: QuoteMarketDataSessionDependencies = defaultSessionDependencies
) {
  let latestQuote: TradingQuote | undefined
  let latestOrderBook: MarketOrderBook | undefined
  let latestTrades: MarketTradeBatch | undefined
  let expectedSource: { providerCode: string; sourceMode: MarketSourceMode } | undefined
  let disposed = false
  let expiryTimer: ReturnType<typeof globalThis.setTimeout> | undefined
  let refreshTimer: ReturnType<typeof globalThis.setTimeout> | undefined
  let refreshController: AbortController | undefined
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
        scheduleBundleRefresh()
        return
      }
    }
    store.reset(snapshot)
    if (snapshot.status !== 'ready' || !snapshot.source) {
      if (snapshot.status === 'stale') scheduleBundleRefresh()
      return
    }

    const expiresIn = Date.parse(snapshot.source.expiresAt) - Date.now()
    expiryTimer = globalThis.setTimeout(() => {
      if (!disposed) {
        store.reset(unavailableSnapshot('stale', snapshot.source))
        refreshController?.abort()
        void refreshBundle()
      }
    }, Math.max(0, expiresIn))
  }

  const refreshBundle = async () => {
    if (disposed) return
    if (refreshRunning) {
      refreshTrailing = true
      return
    }
    refreshRunning = true
    const controller = new AbortController()
    refreshController = controller
    try {
      const [quote, orderBook, trades] = await Promise.all([
        dependencies.fetchQuote(symbol, controller.signal),
        dependencies.fetchOrderBook(symbol, controller.signal),
        dependencies.fetchTrades(symbol, controller.signal)
      ])
      if (disposed) return
      latestQuote = quote
      latestOrderBook = orderBook
      latestTrades = trades
      publish()
    } catch {
      if (!disposed) store.reset(unavailableSnapshot(expectedSource ? 'source-changing' : 'unavailable'))
    } finally {
      if (refreshController === controller) refreshController = undefined
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

  const unsubscribeQuote = dependencies.subscribeQuote(symbol, token, scheduleBundleRefresh)
  const unsubscribeOrderBook = dependencies.subscribeOrderBook(symbol, token, scheduleBundleRefresh)
  const unsubscribeRecentTrades = dependencies.subscribeRecentTrades(symbol, token, scheduleBundleRefresh)
  const unsubscribeSourceChanges = dependencies.subscribeSourceChanges(symbol, token, (event: MarketSourceChangedEvent) => {
    expectedSource = { providerCode: event.providerCode, sourceMode: event.sourceMode }
    latestQuote = undefined
    latestOrderBook = undefined
    latestTrades = undefined
    if (expiryTimer) globalThis.clearTimeout(expiryTimer)
    expiryTimer = undefined
    store.reset(unavailableSnapshot('source-changing'))
    refreshController?.abort()
    void refreshBundle()
  })

  return () => {
    disposed = true
    refreshController?.abort()
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
