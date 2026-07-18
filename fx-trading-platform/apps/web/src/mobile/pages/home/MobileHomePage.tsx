import type { HomeRouteModel } from '../../../routes/home/homeRoute.types'
import { HomeContent } from '../../../shared-widgets/home/HomeContent'
import styles from './MobileHomePage.module.css'

export function MobileHomePage({ model }: { model: HomeRouteModel }) {
  return (
    <div className={styles.root} data-platform-view="mobile">
      <HomeContent model={model} platform="mobile" />
    </div>
  )
}
