import { useTranslation } from 'react-i18next'

import type { MarketSourceChangedEvent } from '@fx-platform/frontend-core'
import styles from './MarketSourceChangeNotice.module.css'

type MarketSourceChangeNoticeProps = {
  notice: MarketSourceChangedEvent | null
}

export function MarketSourceChangeNotice({ notice }: MarketSourceChangeNoticeProps) {
  const { t } = useTranslation()
  if (!notice) return null

  const previousProvider = notice.previousProviderCode?.trim() || 'unknown'
  const previousSource = notice.previousSourceMode ?? 'UNKNOWN'

  return (
    <aside
      className={styles.marketSourceNotice}
      role="status"
      aria-live="polite"
      aria-atomic="true"
      data-testid="market-source-change-notice"
      data-market-source-change="true"
      data-symbol={notice.symbol}
      data-previous-provider={previousProvider}
      data-current-provider={notice.providerCode}
      data-previous-source={previousSource}
      data-current-source={notice.sourceMode}
    >
      <strong>{t('trading.marketSource')} · {notice.symbol}</strong>
      <span>{previousProvider} / {previousSource} → {notice.providerCode} / {notice.sourceMode}</span>
    </aside>
  )
}
