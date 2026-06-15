import { HomeHeroGuest } from './components/HomeHeroGuest'
import { HomeHeroUnverified } from './components/HomeHeroUnverified'
import { HomeHeroVerified } from './components/HomeHeroVerified'
import { HomeSupportSections } from './components/HomeSupportSections'
import { MarketPreviewPanel } from './components/MarketPreviewPanel'
import { NewsPreviewPanel } from './components/NewsPreviewPanel'
import { TrustAwardsStrip } from './components/TrustAwardsStrip'
import { useHomeAuthVariant } from './hooks/useHomeAuthVariant'
import { useHomeCounters } from './hooks/useHomeCounters'
import styles from './HomePage.module.css'

export function HomePage() {
  const authVariant = useHomeAuthVariant()
  const counters = useHomeCounters()

  return (
    <div className={styles.page}>
      <section className={styles.heroGrid}>
        {authVariant === 'guest' ? <HomeHeroGuest users={counters.users} /> : null}
        {authVariant === 'authenticated_unverified' ? <HomeHeroUnverified /> : null}
        {authVariant === 'authenticated_verified' ? (
          <HomeHeroVerified activeTraders={counters.activeTraders} dailyTrades={counters.dailyTrades} />
        ) : null}

        <aside className={styles.previewStack} aria-label="行情和新闻">
          <MarketPreviewPanel />
          <NewsPreviewPanel />
        </aside>
      </section>

      <TrustAwardsStrip />
      <HomeSupportSections />
    </div>
  )
}
