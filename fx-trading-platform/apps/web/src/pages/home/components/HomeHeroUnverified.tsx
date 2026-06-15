import { ShieldCheck } from 'lucide-react'
import { Link } from 'react-router-dom'

import styles from '../HomePage.module.css'

export function HomeHeroUnverified() {
  return (
    <section className={styles.heroCopy} aria-labelledby="home-hero-title">
      <p className={styles.eyebrow}>身份认证</p>
      <p className={styles.eyebrow}>KYC</p>
      <h1 id="home-hero-title" className={styles.heroTitle}>
        <span>完成身份认证</span>
        <span>开启交易旅程</span>
      </h1>
      <p className={styles.summary}>认证后即可使用充值、提现、交易订单和账户偏好。当前状态会在个人中心保持同步。</p>
      <div className={styles.statusCard}>
        <ShieldCheck size={22} aria-hidden="true" />
        <span>账户已创建，身份认证待完成</span>
      </div>
      <div className={styles.heroActions}>
        <Link className={`${styles.primaryAction} ${styles.primaryCta}`} to="/account/security/kyc">
          立即认证
        </Link>
        <Link className={styles.ghostAction} to="/markets">
          先看行情
        </Link>
      </div>
    </section>
  )
}
