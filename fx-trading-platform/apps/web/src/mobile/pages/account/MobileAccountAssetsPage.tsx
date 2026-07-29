import type { AccountRouteModel } from '../../../routes/account/accountRoute.types'
import { MobileAccountPageFrame } from './MobileAccountPageFrame'
import styles from './MobileAccountPages.module.css'

export function MobileAccountAssetsPage({ model }: { model: AccountRouteModel }) {
  return <div className={styles.root} data-platform-view="mobile"><MobileAccountPageFrame model={model} expectedMode="assets" /></div>
}
