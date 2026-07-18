import { Link } from 'react-router-dom'

import styles from './HomeContent.module.css'

export function NewsPreviewPanel({ news }: { news: readonly string[] }) {
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
