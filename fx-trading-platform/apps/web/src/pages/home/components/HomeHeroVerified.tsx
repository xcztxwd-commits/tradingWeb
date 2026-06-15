import { Link } from 'react-router-dom'

import styles from '../HomePage.module.css'
import { AnimatedCounter } from './AnimatedCounter'

type HomeHeroVerifiedProps = {
  activeTraders: number
  dailyTrades: number
}

export function HomeHeroVerified({ activeTraders, dailyTrades }: HomeHeroVerifiedProps) {
  return (
    <section className={styles.heroCopy} aria-labelledby="home-hero-title">
      <p className={styles.eyebrow}>账户已就绪</p>
      <h1 id="home-hero-title" className={styles.heroTitle}>
        <span>安全、快速</span>
        <span>开始交易</span>
      </h1>
      <p className={styles.summary}>实时行情、交易入口、资产总览和资金流水已整理到最短路径。每次操作都给出明确反馈。</p>
      <div className={styles.metricGrid} aria-label="实时交易状态">
        <AnimatedCounter value={activeTraders} label="当前交易用户" />
        <AnimatedCounter value={dailyTrades} label="今日成交" />
      </div>
      <div className={styles.heroActions}>
        <Link className={`${styles.primaryAction} ${styles.primaryCta}`} to="/trading">
          立即交易
        </Link>
        <Link className={styles.secondaryAction} to="/account/assets">
          充值资金
        </Link>
      </div>
    </section>
  )
}
