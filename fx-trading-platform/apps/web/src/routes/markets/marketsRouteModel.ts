import {
  mergeTradingQuoteIntoMarket,
  type Quote,
  type TradingMarket,
  type TradingQuote
} from '@fx-platform/frontend-core'

import type {
  MarketSortDirection,
  MarketSortKey,
  MarketUniverseTab,
  MarketZoneTab
} from './marketsRoute.types'

export function filterMarketRows(
  markets: TradingMarket[],
  query: string,
  universeTab: MarketUniverseTab,
  zoneTab: MarketZoneTab
) {
  const normalizedQuery = query.trim().toLowerCase()
  return markets.filter((market) => {
    const universe = getMarketUniverse(market)
    const universeMatches =
      universeTab === 'favorites'
        ? market.favorite
        : universeTab === 'forex'
          ? market.category === 'fx'
          : universeTab === 'crypto'
            ? universe === 'spot' || universe === 'contract'
            : universe === universeTab
    const queryMatches =
      normalizedQuery.length === 0 ||
      market.symbol.toLowerCase().includes(normalizedQuery) ||
      market.name.toLowerCase().includes(normalizedQuery)
    return universeMatches && matchesZone(market, zoneTab) && queryMatches
  })
}

export function compareMarkets(
  left: TradingMarket,
  right: TradingMarket,
  sortKey: MarketSortKey,
  direction: MarketSortDirection
) {
  const multiplier = direction === 'asc' ? 1 : -1
  if (sortKey === 'symbol') return left.symbol.localeCompare(right.symbol) * multiplier
  if (sortKey === 'price') return (left.last - right.last) * multiplier
  if (sortKey === 'change') return (left.changePercent - right.changePercent) * multiplier
  if (sortKey === 'marketCap') return (marketCap(left) - marketCap(right)) * multiplier
  return (marketTurnover(left) - marketTurnover(right)) * multiplier
}

export function mergeBinanceOverviewMarkets(providerMarkets: TradingMarket[], binanceMarkets: TradingMarket[]) {
  const merged = new Map<string, TradingMarket>()
  binanceMarkets.forEach((market) => merged.set(market.symbol, market))
  providerMarkets.forEach((market) => {
    const liveMarket = merged.get(market.symbol)
    if (!liveMarket) {
      merged.set(market.symbol, market)
      return
    }
    merged.set(market.symbol, {
      ...liveMarket,
      favorite: market.favorite,
      tradable: market.tradable,
      quoteEnabled: market.quoteEnabled,
      chartEnabled: market.chartEnabled,
      orderBookEnabled: market.orderBookEnabled,
      minLot: market.minLot,
      pricePrecision: market.pricePrecision,
      quantityPrecision: market.quantityPrecision
    })
  })
  return Array.from(merged.values())
}

export function canHydrateMarketQuote(market: TradingMarket) {
  return market.source !== 'binance-market-overview' &&
    market.tradable !== false &&
    market.quoteEnabled !== false &&
    (market.category === 'fx' || market.provider === 'massive' || market.provider === 'binance')
}

export function applyMarketQuote(market: TradingMarket, quote: TradingQuote): TradingMarket {
  return mergeTradingQuoteIntoMarket(market, quote)
}

export function mergeQuotedMarkets(markets: TradingMarket[], quotedMarkets: TradingMarket[]) {
  const quotedBySymbol = new Map(quotedMarkets.map((market) => [market.symbol, market]))
  return markets.map((market) => quotedBySymbol.get(market.symbol) ?? market)
}

export function applyRealtimeQuote(market: TradingMarket, quote: Quote): TradingMarket {
  const mid = Number(quote.mid)
  const spread = Number(quote.spread)
  return {
    ...market,
    last: Number.isFinite(mid) ? mid : market.last,
    spread: Number.isFinite(spread) ? spread : market.spread
  }
}

export function marketCap(market: TradingMarket) {
  if (typeof market.marketCap === 'number' && market.marketCap > 0) return market.marketCap
  return 0
}

export function marketTurnover(market: TradingMarket) {
  return parseCompactNumber(market.volume) * market.last
}

export function parseCompactNumber(value: string) {
  const normalized = value.trim().toUpperCase()
  const number = Number(normalized.replace(/[^\d.]/g, ''))
  if (!Number.isFinite(number)) return 0
  if (normalized.endsWith('T')) return number * 1_000_000_000_000
  if (normalized.endsWith('B')) return number * 1_000_000_000
  if (normalized.endsWith('M')) return number * 1_000_000
  if (normalized.endsWith('K')) return number * 1_000
  return number
}

function getMarketUniverse(market: TradingMarket): Exclude<MarketUniverseTab, 'favorites' | 'crypto'> {
  if (market.category === 'fx') return 'forex'
  if (market.category === 'crypto') return market.symbol === 'ETHUSDT' ? 'contract' : 'spot'
  return 'contract'
}

function matchesZone(market: TradingMarket, zone: MarketZoneTab) {
  if (zone === 'all') return true
  if (zone === 'solana') return market.symbol === 'SOLUSDT'
  if (zone === 'payments') return market.symbol === 'XRPUSDT'
  if (zone === 'metals') return market.category === 'metals'
  if (zone === 'indices') return market.category === 'indices'
  return true
}
