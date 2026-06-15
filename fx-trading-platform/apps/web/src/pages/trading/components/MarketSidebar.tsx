import { Search, Star } from 'lucide-react'
import { useMemo, useState } from 'react'

import { AssetMark } from '../../../components/asset/AssetMark'
import { filterMarkets, formatMarketPrice, marketCategories } from '../../../features/market/tradingModels'
import type { MarketCategory, TradingMarket, TradingQuote } from '../../../features/market/tradingModels'
import styles from './MarketSidebar.module.css'

type Props = {
  markets: TradingMarket[]
  quotes: Record<string, TradingQuote>
  selectedSymbol: string
  onSelect: (symbol: string) => void
}

export function MarketSidebar({ markets, quotes, selectedSymbol, onSelect }: Props) {
  const [query, setQuery] = useState('')
  const [category, setCategory] = useState<MarketCategory>('all')
  const [favorites, setFavorites] = useState(() => new Set(markets.filter((item) => item.favorite).map((item) => item.symbol)))

  const visibleMarkets = useMemo(
    () =>
      filterMarkets(
        markets.map((market) => ({ ...market, favorite: favorites.has(market.symbol) })),
        query,
        category
      ),
    [category, favorites, markets, query]
  )

  const toggleFavorite = (symbol: string) => {
    setFavorites((current) => {
      const next = new Set(current)
      if (next.has(symbol)) {
        next.delete(symbol)
      } else {
        next.add(symbol)
      }
      return next
    })
  }

  return (
    <aside className={styles.sidebar}>
      <header className={styles.header}>
        <h2>Markets</h2>
        <span>{visibleMarkets.length}</span>
      </header>

      <label className={styles.search}>
        <Search size={15} />
        <input value={query} placeholder="Search symbol" onChange={(event) => setQuery(event.target.value)} />
      </label>

      <div className={styles.tabs}>
        {marketCategories.map((item) => (
          <button
            key={item.value}
            type="button"
            className={item.value === category ? styles.activeTab : ''}
            onClick={() => setCategory(item.value)}
          >
            {item.label}
          </button>
        ))}
      </div>

      <div className={styles.list} data-virtual-ready="true">
        {visibleMarkets.map((market) => {
          const quote = quotes[market.symbol]
          const changePercent = quote?.changePercent ?? market.changePercent
          const last = quote?.mid ?? market.last
          const directionClass = changePercent >= 0 ? styles.positive : styles.negative

          return (
            <div key={market.symbol} className={`${styles.row} ${market.symbol === selectedSymbol ? styles.selected : ''}`}>
              <button type="button" className={styles.rowMain} onClick={() => onSelect(market.symbol)}>
                <AssetMark symbol={market.symbol} category={market.category} size="sm" />
                <span className={styles.symbolCell}>
                  <span className={styles.symbolLine}>{market.symbol}</span>
                  <small>{market.name}</small>
                </span>
                <span className={styles.priceCell}>
                  <strong>{formatMarketPrice(market.symbol, last)}</strong>
                  <small className={directionClass}>{changePercent.toFixed(2)}%</small>
                </span>
              </button>
              <button
                type="button"
                aria-label={favorites.has(market.symbol) ? `Remove ${market.symbol} from favorites` : `Add ${market.symbol} to favorites`}
                className={`${styles.star} ${favorites.has(market.symbol) ? styles.starActive : ''}`}
                onClick={() => toggleFavorite(market.symbol)}
              >
                <Star size={13} fill="currentColor" />
              </button>
            </div>
          )
        })}
      </div>
    </aside>
  )
}
