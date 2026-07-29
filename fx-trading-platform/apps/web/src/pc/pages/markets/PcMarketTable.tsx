import { formatMarketPrice } from '@fx-platform/frontend-core'

import type { MarketSortKey } from '../../../routes/markets/marketsRoute.types'
import { isMarketTradingEnabled } from '../../../routes/markets/marketTradingTarget'
import { AssetMark } from '../../../shared-widgets/asset/AssetMark'
import type { MarketCollectionProps } from '../../../shared-widgets/market/marketCollection.types'
import {
  formatMarketCap,
  formatMarketVolume,
  formatSignedPercent,
  toMarketAriaSort
} from '../../../shared-widgets/market/marketFormatters'
import { MarketFavoriteButton } from '../../../shared-widgets/market/MarketFavoriteButton'
import styles from './PcMarketTable.module.css'

const marketColumns: Array<{ key: MarketSortKey | 'action'; label: string; sortable?: boolean }> = [
  { key: 'symbol', label: '名称', sortable: true },
  { key: 'price', label: '价格', sortable: true },
  { key: 'change', label: '24h涨跌', sortable: true },
  { key: 'volume', label: '24h成交量', sortable: true },
  { key: 'marketCap', label: '市值', sortable: true },
  { key: 'action', label: '操作' }
]

export function PcMarketTable({
  markets,
  favorites,
  sortKey,
  sortDirection,
  onFavorite,
  onOpen,
  onSort
}: MarketCollectionProps) {
  return (
    <div className={styles.table} data-market-collection="pc-table" aria-label="行情表格">
      <table>
        <thead>
          <tr>
            {marketColumns.map((column) => (
              <th key={column.key} aria-sort={column.key === sortKey ? toMarketAriaSort(sortDirection) : undefined}>
                {column.sortable ? (
                  <button type="button" onClick={() => onSort(column.key as MarketSortKey)}>
                    {column.label}
                    <span>{column.key === sortKey ? (sortDirection === 'asc' ? '↑' : '↓') : '↕'}</span>
                  </button>
                ) : (
                  column.label
                )}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {markets.map((market) => (
            <tr key={market.symbol} onClick={() => isMarketTradingEnabled(market) && onOpen(market)}>
              <td>
                <MarketFavoriteButton active={favorites.has(market.symbol) || market.favorite} symbol={market.symbol} onFavorite={onFavorite} />
                <AssetMark symbol={market.symbol} category={market.category} iconUrl={market.iconUrl} size="sm" />
                <span>
                  <strong>{market.symbol}</strong>
                  <small>{market.name}</small>
                </span>
              </td>
              <td>{formatMarketPrice(market.symbol, market.last)}</td>
              <td className={market.changePercent >= 0 ? styles.changeUp : styles.changeDown}>
                {formatSignedPercent(market.changePercent)}
              </td>
              <td>{formatMarketVolume(market)}</td>
              <td>{formatMarketCap(market)}</td>
              <td>
                <button
                  type="button"
                  className={styles.tradeAction}
                  disabled={!isMarketTradingEnabled(market)}
                  onClick={(event) => {
                    event.stopPropagation()
                    onOpen(market)
                  }}
                >
                  交易
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
