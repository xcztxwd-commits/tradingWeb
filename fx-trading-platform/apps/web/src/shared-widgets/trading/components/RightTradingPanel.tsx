import { MarketSidePanel } from '../market-data/MarketSidePanel'
import styles from './RightTradingPanel.module.css'

type Props = {
  symbol: string
  token?: string | null
  loading?: boolean
  onSelectPrice?: (price: number) => void
}

export function RightTradingPanel({ symbol, token = null, loading = false, onSelectPrice }: Props) {
  return (
    <aside className={styles.panel}>
      <MarketSidePanel symbol={symbol} token={token} loading={loading} onSelectPrice={onSelectPrice} />
    </aside>
  )
}
