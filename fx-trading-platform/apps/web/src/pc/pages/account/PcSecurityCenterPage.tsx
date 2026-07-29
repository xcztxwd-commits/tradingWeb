import type { AccountRouteModel } from '../../../routes/account/accountRoute.types'
import { PcAccountPageFrame } from './PcAccountPageFrame'
import styles from './PcAccountPages.module.css'

export function PcSecurityCenterPage({ model }: { model: AccountRouteModel }) {
  return <div className={styles.root} data-platform-view="pc"><PcAccountPageFrame model={model} expectedMode="security" /></div>
}
