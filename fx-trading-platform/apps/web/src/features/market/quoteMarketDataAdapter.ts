import { subscribeOrderBook, subscribeQuote, subscribeRecentTrades } from '../../services/marketStream'
import { fetchMarketOrderBook, fetchMarketQuote, fetchMarketRecentTrades } from './tradingMarketApi'
import { mapOrderBookToMarketData, mapQuoteToTradingQuote, mapRecentTradesToMarketData } from './tradingMarketAdapters'
import type { BackendOrderBook, BackendQuote, BackendRecentTrade } from './tradingMarketAdapters'
import type { TradingQuote } from './tradingModels'
import { marketDataStore } from './marketDataStore'
import { createFallbackMarketDataSnapshot, createQuoteMarketDataSnapshot } from './quoteMarketDataSnapshot'

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
  let disposed = false
  let backendDepthReady = false

  const applyQuote = (quote: TradingQuote) => {
    latestQuote = quote
    if (backendDepthReady) {
      store.setLastPrice(quote.mid)
    } else {
      store.reset(createQuoteMarketDataSnapshot(quote))
    }
  }

  const applyOrderBook = (orderBook: ReturnType<typeof mapOrderBookToMarketData>) => {
    backendDepthReady = true
    store.setOrderBook([
      ...orderBook.bids.map((level) => ({ ...level, side: 'bid' as const })),
      ...orderBook.asks.map((level) => ({ ...level, side: 'ask' as const }))
    ])
    store.setLastPrice(latestQuote?.mid ?? orderBook.lastPrice)
  }

  void fetchMarketQuote(symbol).then(applyQuote).catch(() => {
    if (!disposed) store.reset(createFallbackMarketDataSnapshot(symbol))
  })
  void fetchMarketOrderBook(symbol).then(applyOrderBook).catch(() => undefined)
  void fetchMarketRecentTrades(symbol).then((trades) => store.setRecentTrades(trades)).catch(() => undefined)

  const unsubscribeQuote = subscribeQuote(symbol, token, (quote) => {
    applyQuote(mapQuoteToTradingQuote(quote as BackendQuote, latestQuote))
  })
  const unsubscribeOrderBook = subscribeOrderBook<BackendOrderBook>(symbol, token, (orderBook) => {
    applyOrderBook(mapOrderBookToMarketData(orderBook))
  })
  const unsubscribeRecentTrades = subscribeRecentTrades<BackendRecentTrade[]>(symbol, token, (trades) => {
    store.setRecentTrades(mapRecentTradesToMarketData(trades))
  })

  return () => {
    disposed = true
    unsubscribeQuote()
    unsubscribeOrderBook()
    unsubscribeRecentTrades()
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
