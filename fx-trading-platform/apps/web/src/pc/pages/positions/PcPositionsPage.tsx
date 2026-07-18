import type { PositionsRouteModel } from '../../../routes/positions/positionsRoute.types'
import { PositionsRouteContent } from '../../../shared-widgets/positions/PositionsRouteContent'
import { PcDataCollection } from '../../components/PcDataCollection'
import styles from './PcPositionsPage.module.css'

export function PcPositionsPage({ model }: { model: PositionsRouteModel }) {
  return <div className={styles.root} data-platform-view="pc"><PositionsRouteContent model={model} renderDataCollection={PcDataCollection} /></div>
}
