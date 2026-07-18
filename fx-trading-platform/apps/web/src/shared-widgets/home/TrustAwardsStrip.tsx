import { Award, BadgeCheck, Trophy } from 'lucide-react'

import styles from './HomeContent.module.css'

const trustSignals = [
  { icon: Trophy, title: '获评 2025 年最受信任交易平台', description: '以资产透明度、风控流程和用户保护作为首屏信任背书。' },
  { icon: Award, title: '亚洲金融科技创新者榜单第 1', description: '保持高密度行情、交易和账户入口，减少用户跳转成本。' },
  { icon: BadgeCheck, title: '入选全球顶级金融科技公司榜单', description: '只复刻布局与交互，不使用 Binance 品牌资产。' }
] as const

export function TrustAwardsStrip() {
  return (
    <section className={styles.trustStrip} aria-label="平台信任背书">
      {trustSignals.map((signal) => {
        const Icon = signal.icon
        return (
          <article key={signal.title}>
            <Icon size={18} aria-hidden="true" />
            <div>
              <strong>{signal.title}</strong>
              <p>{signal.description}</p>
            </div>
          </article>
        )
      })}
    </section>
  )
}
