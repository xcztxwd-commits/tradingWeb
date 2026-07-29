import type { AuthViewProps } from '../../../routes/auth/authRoute.types'
import { AuthPageContent } from '../../../shared-widgets/auth/AuthPageContent'
import styles from './MobileAuthPages.module.css'

export function MobileTwoFactorHelpPage({ model }: AuthViewProps) {
  return <div className={styles.root} data-platform-view="mobile"><AuthPageContent expectedMode="two-factor-help" model={model} platform="mobile" /></div>
}
