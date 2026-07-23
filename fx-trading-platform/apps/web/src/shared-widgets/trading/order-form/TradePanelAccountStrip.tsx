import { useTranslation } from 'react-i18next'

import styles from './TradePanel.module.css'

type Props = {
  accountStatus: string
}

export function TradePanelAccountStrip({ accountStatus }: Props) {
  const { t } = useTranslation()

  return (
    <div className={styles['trade-panel__account-strip']} aria-label={t('trading.tradingMode')}>
      <span>
        <strong>{t('trading.spot')}</strong>
        <small>Cash</small>
      </span>
      <span>
        <strong>{t('trading.feeRate', { rate: '0.10%' })}</strong>
        <small>Maker/Taker</small>
      </span>
      <span>
        <strong>{accountStatus}</strong>
        <small>{t('trading.simulatedAccount')}</small>
      </span>
    </div>
  )
}
