import type { OrdersRouteModel } from '../../../routes/orders/ordersRoute.types'
import { OrdersRouteContent } from '../../../shared-widgets/orders/OrdersRouteContent'
import { PcDataCollection } from '../../components/PcDataCollection'
import styles from './PcOrdersPage.module.css'

export function PcOrdersPage({ model }: { model: OrdersRouteModel }) {
  return <div className={styles.root} data-platform-view="pc"><OrdersRouteContent model={model} renderDataCollection={PcDataCollection} /></div>
}
