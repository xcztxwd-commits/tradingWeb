import type { TradingMarket } from '@fx-platform/frontend-core'

import type {
  MarketSortDirection,
  MarketSortKey
} from '../../routes/markets/marketsRoute.types'

export type MarketCollectionProps = {
  markets: TradingMarket[]
  favorites: Set<string>
  sortKey: MarketSortKey
  sortDirection: MarketSortDirection
  onFavorite(symbol: string): void
  onOpen(market: TradingMarket): void
  onSort(key: MarketSortKey): void
}
