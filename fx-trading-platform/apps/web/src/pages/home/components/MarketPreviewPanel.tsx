import { ArrowRight } from 'lucide-react'
import { Link } from 'react-router-dom'

import { AssetMark } from '../../../components/asset/AssetMark'
import styles from '../HomePage.module.css'

const markets = [
  { symbol: 'BTC', name: 'Bitcoin', price: '$64,483.88', change: '+1.27%', tone: 'positive' },
  { symbol: 'ETH', name: 'Ethereum', price: '$1,682.29', change: '+0.83%', tone: 'positive' },
  { symbol: 'BNB', name: 'BNB', price: '$609.30', change: '+0.89%', tone: 'positive' },
  { symbol: 'XRP', name: 'XRP', price: '$1.15', change: '+1.46%', tone: 'positive' },
  { symbol: 'ASTER', name: 'Aster', price: '$0.636', change: '+0.32%', tone: 'positive' }
] as const

export function MarketPreviewPanel() {
  return (
    <section className={`${styles.panelCard} ${styles.marketPanel}`} aria-labelledby="home-market-title">
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
