import { Link } from 'react-router-dom'

import styles from '../HomePage.module.css'

const news = [
  '瑞士夺世界杯B组概率降至47%',
  '开源权重模型或加大交易模型竞争压力',
  '加密 ETF 申请进入新一轮审查窗口',
  '永续合约部署权限进入更开放的产品周期'
] as const

export function NewsPreviewPanel() {
  return (
    <section className={`${styles.panelCard} ${styles.newsPanel}`} aria-labelledby="home-news-title">
      <div className={styles.panelHeader}>
        <h2 id="home-news-title">新闻</h2>
        <Link to="/markets">查看全部新闻</Link>
      </div>
      <ul>
        {news.map((item) => (
          <li key={item}>{item}</li>
        ))}
      </ul>
    </section>
  )
}
