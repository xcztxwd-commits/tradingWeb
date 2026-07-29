import type { WalletRouteModel } from '../../../routes/wallet/walletRoute.types'
import { WalletRouteContent } from '../../../shared-widgets/wallet/WalletRouteContent'
import { PcDataCollection } from '../../components/PcDataCollection'
import styles from './PcWalletPage.module.css'

export function PcWalletPage({ model }: { model: WalletRouteModel }) {
  return <div className={styles.root} data-platform-view="pc"><WalletRouteContent model={model} renderDataCollection={PcDataCollection} /></div>
}
