import { ArrowRight } from 'lucide-react'
import { Link } from 'react-router-dom'

import { AssetMark } from '../asset/AssetMark'
import type { HomeViewMarketPreview } from './homeView.types'
import styles from './HomeContent.module.css'

export function MarketPreviewPanel({ markets }: { markets: readonly HomeViewMarketPreview[] }) {
  return (
    <section className={styles.panelCard} aria-labelledby="home-market-title">
      <div className={styles.marketTabs} aria-label="行情分类">
        <span className={styles.activeTab}>热门</span>
        <span>新币</span>
      </div>
      <div className={styles.marketList}>
        {markets.map((market) => (
          <Link key={market.symbol} className={styles.marketRow} to="/markets">
            <AssetMark symbol={market.symbol} size="sm" />
            <span>
              <strong>{market.symbol}</strong>
              <small>{market.name}</small>
            </span>
            <strong>{market.price}</strong>
            <em className={styles[market.tone]}>{market.change}</em>
          </Link>
        ))}
      </div>
      <Link className={styles.panelMoreLink} to="/markets" id="home-market-title">
        查看全部350多个代币
        <ArrowRight size={15} aria-hidden="true" />
      </Link>
    </section>
  )
}
