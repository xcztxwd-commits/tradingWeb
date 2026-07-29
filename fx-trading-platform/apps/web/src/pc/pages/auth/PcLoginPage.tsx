import type { AuthViewProps } from '../../../routes/auth/authRoute.types'
import { AuthPageContent } from '../../../shared-widgets/auth/AuthPageContent'
import styles from './PcAuthPages.module.css'

export function PcLoginPage({ model }: AuthViewProps) {
  return <div className={styles.root} data-platform-view="pc"><AuthPageContent expectedMode="login" model={model} platform="pc" /></div>
}
