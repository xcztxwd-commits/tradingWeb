import { useTranslation } from 'react-i18next'

import styles from './ExchangeLoading.module.css'

const bookRows = [
  { side: 'sell', price: '68,412.4', amount: '1.842' },
  { side: 'mid', price: '68,410.8', amount: 'BTC/USDT' },
  { side: 'buy', price: '68,409.9', amount: '2.316' }
] as const

export function ExchangeLoading() {
  const { t } = useTranslation()

  return (
    <section className={styles.shell} role="status" aria-live="polite" aria-label={t('loading.terminalAria')}>
      <div className={styles.panel}>
        <div className={styles.header}>
          <span className={styles.brand}>FX Trader</span>
          <span className={styles.badge}>Live</span>
        </div>

        <div className={styles.copy}>
          <h1>{t('loading.terminalTitle')}</h1>
          <p>{t('loading.terminalSubtitle')}</p>
        </div>

        <div className={styles.market} aria-hidden="true">
          <div className={styles.marketTop}>
            <span>BTC/USDT</span>
            <strong>68,410.8</strong>
          </div>
          <div className={styles.scanLine} />
          <div className={styles.bookRows}>
            {bookRows.map((row) => (
              <div key={row.side} className={`${styles.bookRow} ${styles[row.side]}`}>
                <span>{row.price}</span>
                <span>{row.amount}</span>
              </div>
            ))}
          </div>
        </div>
      </div>
    </section>
  )
}
