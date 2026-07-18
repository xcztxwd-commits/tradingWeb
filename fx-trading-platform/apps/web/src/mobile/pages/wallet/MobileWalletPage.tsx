import type { WalletRouteModel } from '../../../routes/wallet/walletRoute.types'
import { WalletRouteContent } from '../../../shared-widgets/wallet/WalletRouteContent'
import { MobileDataCollection } from '../../components/MobileDataCollection'
import styles from './MobileWalletPage.module.css'

export function MobileWalletPage({ model }: { model: WalletRouteModel }) {
  return <div className={styles.root} data-platform-view="mobile"><WalletRouteContent model={model} renderDataCollection={MobileDataCollection} /></div>
}
