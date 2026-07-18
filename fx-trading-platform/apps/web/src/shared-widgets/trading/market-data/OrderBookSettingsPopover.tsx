import { Columns2, List, Rows3, X } from 'lucide-react'
import { useTranslation } from 'react-i18next'

import type { OrderBookLayoutMode, OrderBookSettings } from './types'
import styles from './MarketSidePanel.module.css'

type Props = {
  settings: OrderBookSettings
  onChange: (settings: OrderBookSettings) => void
  onClose: () => void
}

const layoutModes: Array<{ mode: OrderBookLayoutMode; labelKey: string; icon: typeof Rows3 }> = [
  { mode: 'orderbook', labelKey: 'trading.layoutOrderBook', icon: Rows3 },
  { mode: 'trades', labelKey: 'trading.layoutTrades', icon: List },
  { mode: 'split', labelKey: 'trading.layoutSplit', icon: Columns2 }
]

export function OrderBookSettingsPopover({ settings, onChange, onClose }: Props) {
  const { t } = useTranslation()

  const update = (nextSettings: Partial<OrderBookSettings>) => {
    onChange({ ...settings, ...nextSettings })
  }

  return (
    <div className={styles.popover} role="dialog" aria-label={t('trading.orderBookSettings')}>
      <div className={styles.popoverHeader}>
        <strong>{t('trading.layoutMode')}</strong>
        <button type="button" aria-label={t('trading.closeOrderBookSettings')} onClick={onClose}>
          <X size={14} />
        </button>
      </div>
      <div className={styles.layoutChoices}>
        {layoutModes.map((item) => (
          <button
            key={item.mode}
            type="button"
            className={settings.layoutMode === item.mode ? styles.activeLayoutChoice : undefined}
            aria-label={t(item.labelKey)}
            title={t(item.labelKey)}
            onClick={() => update({ layoutMode: item.mode })}
          >
            <item.icon size={22} />
            <span>{t(item.labelKey)}</span>
          </button>
        ))}
      </div>
      <label className={styles.settingLine}>
        <span>{t('trading.showAverageAndTotal')}</span>
        <input
          type="checkbox"
          checked={settings.showAverageAndTotal}
          onChange={(event) => update({ showAverageAndTotal: event.target.checked })}
        />
      </label>
      <label className={styles.settingLine}>
        <span>{t('trading.showBidAskRatio')}</span>
        <input
          type="checkbox"
          checked={settings.showBidAskRatio}
          onChange={(event) => update({ showBidAskRatio: event.target.checked })}
        />
      </label>
      <label className={styles.settingLine}>
        <span>{t('trading.showDepthBars')}</span>
        <input
          type="checkbox"
          checked={settings.showDepthBars}
          onChange={(event) => update({ showDepthBars: event.target.checked })}
        />
      </label>
    </div>
  )
}
