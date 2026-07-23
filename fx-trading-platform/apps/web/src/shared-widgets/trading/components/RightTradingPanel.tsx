import { MarketSidePanel } from '../market-data/MarketSidePanel'
import styles from './RightTradingPanel.module.css'

type Props = {
  symbol: string
  loading?: boolean
  onSelectPrice?: (price: number) => void
}

export function RightTradingPanel({ symbol, loading = false, onSelectPrice }: Props) {
  return (
    <aside className={styles.panel}>
      <MarketSidePanel symbol={symbol} loading={loading} onSelectPrice={onSelectPrice} />
    </aside>
  )
}
