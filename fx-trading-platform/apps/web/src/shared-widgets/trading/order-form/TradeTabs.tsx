import { useTranslation } from 'react-i18next'

import styles from './TradePanel.module.css'

type Props = {
  productType?: 'CRYPTO_SPOT' | 'LINEAR_PERP' | 'FX_MARGIN' | 'INVERSE_PERP'
}

export function TradeTabs({ productType }: Props) {
  const { t } = useTranslation()

  return (
    <div className={styles['trade-panel__top-tabs']} aria-label={t('trading.panel')}>
      <button type="button" className={`${styles['trade-panel__top-tab']} ${styles['trade-panel__top-tab--active']}`} role="tab" aria-selected="true">
        {productType === 'LINEAR_PERP' ? t('trading.perpetual') : t('trading.spot')}
      </button>
    </div>
  )
}
