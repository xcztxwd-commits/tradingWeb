import { formatMarketPrice } from '@fx-platform/frontend-core'

import { isMarketTradingEnabled } from '../../../routes/markets/marketTradingTarget'
import { AssetMark } from '../../../shared-widgets/asset/AssetMark'
import type { MarketCollectionProps } from '../../../shared-widgets/market/marketCollection.types'
import {
  formatMarketCap,
  formatMarketVolume,
  formatSignedPercent
} from '../../../shared-widgets/market/marketFormatters'
import { MarketFavoriteButton } from '../../../shared-widgets/market/MarketFavoriteButton'
import styles from './MobileMarketList.module.css'

export function MobileMarketList({
  markets,
  favorites,
  onFavorite,
  onOpen
}: MarketCollectionProps) {
  return (
    <div className={styles.list} data-market-collection="mobile-list" aria-label="移动端行情列表">
      {markets.map((market) => (
        <article key={market.symbol} className={styles.row}>
          <button type="button" className={styles.body} disabled={!isMarketTradingEnabled(market)} onClick={() => onOpen(market)}>
            <AssetMark symbol={market.symbol} category={market.category} iconUrl={market.iconUrl} size="md" />
            <span>
              <strong>{market.symbol}</strong>
              <small>
                市值 {formatMarketCap(market)} · 成交量 {formatMarketVolume(market)}
              </small>
            </span>
            <span>
              <strong>{formatMarketPrice(market.symbol, market.last)}</strong>
              <em className={`${styles.change} ${market.changePercent >= 0 ? styles.changeUp : styles.changeDown}`}>
                {formatSignedPercent(market.changePercent)}
              </em>
            </span>
          </button>
          <MarketFavoriteButton active={favorites.has(market.symbol) || market.favorite} symbol={market.symbol} onFavorite={onFavorite} />
        </article>
      ))}
    </div>
  )
}
