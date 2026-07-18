import type { MarketsRouteModel } from '../../../routes/markets/marketsRoute.types'
import { MarketTable, MarketsContent } from '../../../shared-widgets/market/MarketsContent'
import styles from './PcMarketsPage.module.css'

export function PcMarketsPage({ model }: { model: MarketsRouteModel }) {
  return (
    <div className={styles.root} data-platform-view="pc">
      <MarketsContent model={model} MarketCollection={MarketTable} />
    </div>
  )
}
