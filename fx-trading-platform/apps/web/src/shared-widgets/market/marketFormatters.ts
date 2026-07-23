import type { TradingMarket } from '@fx-platform/frontend-core'

import {
  marketCap,
  parseCompactNumber
} from '../../routes/markets/marketsRouteModel'
import type { MarketSortDirection } from '../../routes/markets/marketsRoute.types'

export function formatSignedPercent(value: number) {
  const sign = value > 0 ? '+' : ''
  return `${sign}${value.toFixed(2)}%`
}

export function formatMarketVolume(market: TradingMarket) {
  return parseCompactNumber(market.volume) > 0 ? `${market.volume}` : '--'
}

export function formatMarketCap(market: TradingMarket) {
  const value = marketCap(market)
  return value > 0 ? `$${compactMarketNumber(value)}` : '--'
}

export function compactMarketNumber(value: number) {
  if (value >= 1_000_000_000_000) return `${(value / 1_000_000_000_000).toFixed(2)}T`
  if (value >= 1_000_000_000) return `${(value / 1_000_000_000).toFixed(2)}B`
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(2)}M`
  if (value >= 1_000) return `${(value / 1_000).toFixed(2)}K`
  return value.toFixed(2)
}

export function toMarketAriaSort(direction: MarketSortDirection) {
  return direction === 'asc' ? 'ascending' : 'descending'
}
