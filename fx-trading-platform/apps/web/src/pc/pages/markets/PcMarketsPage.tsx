import type { MarketsRouteModel } from '../../../routes/markets/marketsRoute.types'
import { MarketsContent } from '../../../shared-widgets/market/MarketsContent'
import { PcMarketTable } from './PcMarketTable'
import styles from './PcMarketsPage.module.css'

export function PcMarketsPage({ model }: { model: MarketsRouteModel }) {
  return (
    <div className={styles.root} data-platform-view="pc">
      <MarketsContent model={model} MarketCollection={PcMarketTable} />
    </div>
  )
}
