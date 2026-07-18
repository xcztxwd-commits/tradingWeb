import { Apple, ArrowRight, QrCode, Smartphone } from 'lucide-react'
import { Link } from 'react-router-dom'

import type { HomeMetricCard } from '@fx-platform/frontend-core'
import styles from '../HomePage.module.css'
import { AnimatedCounter } from './AnimatedCounter'

type HomeHeroGuestProps = {
  users: number
  metricCards: HomeMetricCard[]
}

function LaurelDecoration({ className }: { className: string }) {
  return <img className={className} src="/home-laurel.svg" alt="" aria-hidden="true" draggable={false} />
}

export function HomeHeroGuest({ users, metricCards }: HomeHeroGuestProps) {
  return (
    <section className={styles.heroCopy} aria-labelledby="home-hero-title">
      <h1 id="home-hero-title" className={styles.heroTitle}>
        <span className={styles.heroNumber}>
          <AnimatedCounter value={users} />
        </span>
        <span>用户的共同选择</span>
      </h1>

      <div className={styles.metricGrid} aria-label="平台指标">
        {metricCards.map((card) => (
          <article key={card.slot} className={styles.promoCard} tabIndex={0}>
            <div className={styles.promoCardSurface}>
              <LaurelDecoration className={styles.laurelLeft} />
              <div className={styles.promoTextFlip}>
                <div className={styles.promoTextInner}>
                  <div className={`${styles.promoTextFace} ${styles.promoTextFront}`}>
                    <strong className={styles.promoRank}>{card.frontRank}</strong>
                    <span className={styles.promoLabel}>{card.frontLabel}</span>
                  </div>
                  <div className={`${styles.promoTextFace} ${styles.promoTextBack}`}>
                    <strong className={styles.promoBackTitle}>{card.backTitle}</strong>
                    <small className={styles.promoValue}>{card.backValue}</small>
                  </div>
                </div>
              </div>
              <LaurelDecoration className={styles.laurelRight} />
            </div>
          </article>
        ))}
      </div>

      <form className={styles.signupPanel} aria-label="注册入口">
        <label className={styles.inputLabel} htmlFor="home-signup">
          邮箱/手机号码
        </label>
        <div className={styles.actionRow}>
          <input id="home-signup" type="text" inputMode="email" autoComplete="email" placeholder="邮箱/手机号码" />
          <Link className={`${styles.primaryAction} ${styles.primaryCta}`} to="/register">
            注册
            <ArrowRight size={17} aria-hidden="true" />
          </Link>
        </div>
      </form>

      <div className={styles.appDownloadRow} aria-label="快捷登录和下载">
        <Link className={styles.secondaryAction} to="/markets">
          <Smartphone size={16} aria-hidden="true" />
          下载 App
        </Link>
        <Link className={styles.ghostAction} to="/login">
          <Apple size={16} aria-hidden="true" />
          Apple
        </Link>
        <Link className={styles.ghostAction} to="/register">
          <QrCode size={16} aria-hidden="true" />
          QR
        </Link>
      </div>

      <div className={styles.heroActions}>
        <Link className={styles.ghostAction} to="/markets">
          查看行情
        </Link>
        <Link className={styles.ghostAction} to="/trading">
          进入交易
        </Link>
      </div>

      <div className={styles.mobileCounter} aria-hidden="true">
        <AnimatedCounter value={users} label="累计用户" />
      </div>
    </section>
  )
}
