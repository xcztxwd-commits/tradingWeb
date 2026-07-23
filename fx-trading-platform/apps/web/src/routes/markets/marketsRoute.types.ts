import type {
  BinanceFuturesDashboard,
  BinanceFuturesPeriod,
  BinanceMarketOverview,
  TradingMarket
} from '@fx-platform/frontend-core'

import type { ApiErrorView } from '../../shared-widgets/data/userPageModels'

export type MarketPageTab = 'overview' | 'trading-data' | 'ai-picks' | 'token-unlocks'
export type TradingDataTab = 'rankings' | 'usdt-contracts' | 'coin-contracts' | 'options'
export type FuturesViewMode = 'list' | 'card'
export type MarketUniverseTab = 'favorites' | 'forex' | 'crypto' | 'spot' | 'contract'
export type MarketZoneTab =
  | 'all'
  | 'bnb-chain'
  | 'solana'
  | 'rwa'
  | 'meme'
  | 'payments'
  | 'ai'
  | 'layer'
  | 'metals'
  | 'indices'
  | 'launchpool'
  | 'defi'
export type MarketSortKey = 'symbol' | 'price' | 'change' | 'volume' | 'marketCap'
export type MarketSortDirection = 'asc' | 'desc'

export type MarketsRouteModel = {
  markets: TradingMarket[]
  hydratedMarkets: TradingMarket[]
  visibleMarkets: TradingMarket[]
  pagedMarkets: {
    items: TradingMarket[]
    page: number
    pageSize: number
    total: number
    totalPages: number
  }
  binanceOverview: BinanceMarketOverview | null
  favorites: Set<string>
  query: string
  pageTab: MarketPageTab
  universeTab: MarketUniverseTab
  zoneTab: MarketZoneTab
  sortKey: MarketSortKey
  sortDirection: MarketSortDirection
  loading: boolean
  apiError: ApiErrorView | null
  reloadKey: number
  futures: {
    activeTab: TradingDataTab
    viewMode: FuturesViewMode
    period: BinanceFuturesPeriod
    dashboard: BinanceFuturesDashboard | null
    loading: boolean
    error: string | null
    setActiveTab(value: TradingDataTab): void
    setViewMode(value: FuturesViewMode): void
    setPeriod(value: BinanceFuturesPeriod): void
  }
  setQuery(value: string): void
  setPageTab(value: MarketPageTab): void
  setUniverseTab(value: MarketUniverseTab): void
  setZoneTab(value: MarketZoneTab): void
  selectSort(value: MarketSortKey): void
  toggleTableSort(value: MarketSortKey): void
  changePage(delta: -1 | 1): void
  toggleFavorite(symbol: string): void
  openMarket(market: TradingMarket): void
  retry(): void
}
