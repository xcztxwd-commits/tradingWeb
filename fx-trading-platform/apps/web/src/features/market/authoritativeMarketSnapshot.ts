import type { MarketDataSnapshot, MarketOrderBook, MarketTradeBatch } from './marketDataTypes.ts'
import type { MarketSourceMetadata, TradingQuote } from './tradingModels.ts'

export function createAuthoritativeMarketSnapshot(
  quote: TradingQuote | undefined,
  orderBook: MarketOrderBook | undefined,
  trades: MarketTradeBatch | undefined,
  now = Date.now()
): MarketDataSnapshot {
  const quoteSource = quote?.marketSource
  const orderBookSource = orderBook?.source
  const tradeSource = trades?.source
  if (!quote || !orderBook || !trades || !quoteSource || !orderBookSource || !tradeSource
    || !sameProviderSource(quoteSource, orderBookSource) || !sameProviderSource(quoteSource, tradeSource)
    || !hasCompleteMarketData(quote, orderBook, trades)) {
    return unavailableSnapshot('unavailable')
  }

  const componentSources = { quote: quoteSource, orderBook: orderBookSource, trades: tradeSource }
  const source = combineSourceMetadata([quoteSource, orderBookSource, tradeSource])
  if (![quoteSource, orderBookSource, tradeSource].every((componentSource) => isFreshSource(componentSource, now))) {
    return { ...unavailableSnapshot('stale', source), componentSources }
  }

  return {
    symbol: quote.symbol,
    asks: orderBook.asks,
    bids: orderBook.bids,
    lastPrice: quote.mid,
    lastPriceDirection: 'flat',
    recentTrades: trades.recentTrades,
    updatedAt: quote.timestamp,
    source,
    componentSources,
    status: 'ready',
    tradable: true
  }
}

export function unavailableSnapshot(
  status: 'loading' | 'stale' | 'source-changing' | 'unavailable' = 'unavailable',
  source?: MarketSourceMetadata
): MarketDataSnapshot {
  return {
    asks: [],
    bids: [],
    lastPrice: 0,
    lastPriceDirection: 'flat',
    recentTrades: [],
    updatedAt: undefined,
    source,
    status,
    tradable: false
  }
}

export function matchesExpectedMarketSource(
  source: MarketSourceMetadata,
  expected: Pick<MarketSourceMetadata, 'providerCode' | 'sourceMode'>
) {
  return source.providerCode === expected.providerCode && source.sourceMode === expected.sourceMode
}

export function isFreshSource(source: MarketSourceMetadata, now = Date.now()) {
  const asOf = Date.parse(source.asOf)
  const expiresAt = Date.parse(source.expiresAt)
  return !source.stale
    && Boolean(source.providerCode.trim() && source.providerSymbol.trim() && source.sourceMode)
    && Number.isFinite(asOf)
    && Number.isFinite(expiresAt)
    && expiresAt >= asOf
    && now < expiresAt
}

function sameProviderSource(left: MarketSourceMetadata, right: MarketSourceMetadata) {
  return left.providerCode === right.providerCode
    && left.providerSymbol === right.providerSymbol
    && left.sourceMode === right.sourceMode
}

function combineSourceMetadata(sources: MarketSourceMetadata[]): MarketSourceMetadata {
  const first = sources[0]
  const asOf = sources.reduce(
    (oldest, source) => Date.parse(source.asOf) < Date.parse(oldest) ? source.asOf : oldest,
    first.asOf
  )
  const expiresAt = sources.reduce(
    (earliest, source) => Date.parse(source.expiresAt) < Date.parse(earliest) ? source.expiresAt : earliest,
    first.expiresAt
  )
  return {
    providerCode: first.providerCode,
    providerSymbol: first.providerSymbol,
    sourceMode: first.sourceMode,
    asOf,
    expiresAt,
    stale: sources.some((source) => source.stale)
  }
}

function hasCompleteMarketData(quote: TradingQuote, orderBook: MarketOrderBook, trades: MarketTradeBatch) {
  return quote.symbol === orderBook.symbol
    && quote.symbol === trades.symbol
    && isPositive(quote.bid)
    && isPositive(quote.ask)
    && isPositive(quote.mid)
    && quote.bid <= quote.ask
    && orderBook.bids.length > 0
    && orderBook.asks.length > 0
    && trades.recentTrades.length > 0
    && orderBook.bids.every((level) => isPositive(level.price) && isPositive(level.amount))
    && orderBook.asks.every((level) => isPositive(level.price) && isPositive(level.amount))
    && trades.recentTrades.every((trade) => isPositive(trade.price) && isPositive(trade.amount))
}

function isPositive(value: number) {
  return Number.isFinite(value) && value > 0
}
