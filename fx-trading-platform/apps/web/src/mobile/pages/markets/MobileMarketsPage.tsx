import type { MarketsRouteModel } from '../../../routes/markets/marketsRoute.types'
import { MarketsContent } from '../../../shared-widgets/market/MarketsContent'
import { MobileMarketList } from './MobileMarketList'
import styles from './MobileMarketsPage.module.css'

export function MobileMarketsPage({ model }: { model: MarketsRouteModel }) {
  return (
    <div className={styles.root} data-platform-view="mobile">
      <MarketsContent model={model} MarketCollection={MobileMarketList} />
    </div>
  )
}
