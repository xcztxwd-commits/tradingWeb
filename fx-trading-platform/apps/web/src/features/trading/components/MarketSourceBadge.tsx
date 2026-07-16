import styles from './TradingControls.module.css'

export type MarketSource = 'LIVE' | 'PUBLIC_EXTERNAL' | 'BINANCE' | 'OKX' | 'LOCAL' | 'LOCAL_SIMULATED' | 'FIXED'

type Props = {
  source: MarketSource
  providerCode?: string | null
  stale?: boolean
  className?: string
}

function sourceLabel(source: MarketSource, providerCode?: string | null) {
  if (providerCode?.trim()) return providerCode.trim().toUpperCase()
  if (source === 'PUBLIC_EXTERNAL') return 'PUBLIC'
  if (source === 'LOCAL_SIMULATED') return 'LOCAL'
  return source
}

export function MarketSourceBadge({ source, providerCode, stale = false, className = '' }: Props) {
  const label = sourceLabel(source, providerCode)
  const classes = [styles.sourceBadge, stale ? styles.sourceBadgeStale : '', className].filter(Boolean).join(' ')

  return (
    <span className={classes} data-source={source} data-stale={stale} aria-label={`Market source ${label}${stale ? ', stale' : ''}`}>
      <span className={styles.sourceDot} aria-hidden="true" />
      {label}
      {stale ? <span className={styles.sourceState}>STALE</span> : null}
    </span>
  )
}
