import type { HomeRouteModel } from '../../../routes/home/homeRoute.types'
import { HomeContent } from '../../../shared-widgets/home/HomeContent'
import styles from './PcHomePage.module.css'

export function PcHomePage({ model }: { model: HomeRouteModel }) {
  return (
    <div className={styles.root} data-platform-view="pc">
      <HomeContent model={model} platform="pc" />
    </div>
  )
}
