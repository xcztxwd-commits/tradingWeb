import type { DeviceClass } from '../../app/device/deviceClass'
import type { HomeViewModel } from './homeView.types'
import { HomeHeroGuest } from './HomeHeroGuest'
import { HomeHeroUnverified } from './HomeHeroUnverified'
import { HomeHeroVerified } from './HomeHeroVerified'
import { HomeSupportSections } from './HomeSupportSections'
import { MarketPreviewPanel } from './MarketPreviewPanel'
import { NewsPreviewPanel } from './NewsPreviewPanel'
import { TrustAwardsStrip } from './TrustAwardsStrip'
import styles from './HomeContent.module.css'

type HomeContentProps = {
  model: HomeViewModel
  platform: DeviceClass
}

export function HomeContent({ model, platform }: HomeContentProps) {
  return (
    <div
      className={`${styles.page}${platform === 'mobile' ? ` ${styles.mobile}` : ''}`}
      data-home-status={model.status}
    >
      <section className={styles.heroGrid}>
        {model.authVariant === 'guest' ? (
          <HomeHeroGuest users={model.counters.users} metricCards={model.counters.metricCards} />
        ) : null}
        {model.authVariant === 'authenticated_unverified' ? <HomeHeroUnverified /> : null}
        {model.authVariant === 'authenticated_verified' ? (
          <HomeHeroVerified
            activeTraders={model.counters.activeTraders}
            dailyTrades={model.counters.dailyTrades}
          />
        ) : null}

        <aside className={styles.previewStack} aria-label="行情和新闻">
          <MarketPreviewPanel markets={model.markets} />
          <NewsPreviewPanel news={model.news} />
        </aside>
      </section>

      {model.status === 'error' ? (
        <p className={styles.dataNotice} role="status">实时统计暂不可用，当前显示最近快照。</p>
      ) : null}
      <TrustAwardsStrip />
      <HomeSupportSections />
    </div>
  )
}
