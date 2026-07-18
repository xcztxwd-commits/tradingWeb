import type { PositionsRouteModel } from '../../../routes/positions/positionsRoute.types'
import { PositionsRouteContent } from '../../../shared-widgets/positions/PositionsRouteContent'
import { MobileDataCollection } from '../../components/MobileDataCollection'
import styles from './MobilePositionsPage.module.css'

export function MobilePositionsPage({ model }: { model: PositionsRouteModel }) {
  return <div className={styles.root} data-platform-view="mobile"><PositionsRouteContent model={model} renderDataCollection={MobileDataCollection} /></div>
}
