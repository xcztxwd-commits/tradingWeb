import type { OrdersRouteModel } from '../../../routes/orders/ordersRoute.types'
import { OrdersRouteContent } from '../../../shared-widgets/orders/OrdersRouteContent'
import { MobileDataCollection } from '../../components/MobileDataCollection'
import styles from './MobileOrdersPage.module.css'

export function MobileOrdersPage({ model }: { model: OrdersRouteModel }) {
  return <div className={styles.root} data-platform-view="mobile"><OrdersRouteContent model={model} renderDataCollection={MobileDataCollection} /></div>
}
