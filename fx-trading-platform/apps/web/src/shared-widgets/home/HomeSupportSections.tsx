import { LockKeyhole, ShieldCheck } from 'lucide-react'
import { Link } from 'react-router-dom'

import styles from './HomeContent.module.css'

const faqs = [
  '为什么 FX Trader 适合加密货币交易者？',
  '如何购买比特币和其他加密货币？',
  '如何追踪加密货币价格？'
] as const

export function HomeSupportSections() {
  return (
    <section className={styles.supportGrid} aria-label="安全保护和常见问题">
      <article className={styles.safuPanel}>
        <ShieldCheck size={24} aria-hidden="true" />
        <div>
          <h2>资金受 SAFU 保护</h2>
          <p>
            参考 Binance 首屏的安全叙事，但不复制品牌资产。页面强调
            <Link to="/account/security/kyc">KYC</Link>
            、账户隔离、风险提示和资金操作可追溯。
          </p>
        </div>
      </article>
      <article className={styles.faqPanel}>
        <LockKeyhole size={22} aria-hidden="true" />
        <div>
          <h2>常见问题</h2>
          <ul>
            {faqs.map((item) => (
              <li key={item}>{item}</li>
            ))}
          </ul>
        </div>
      </article>
    </section>
  )
}
