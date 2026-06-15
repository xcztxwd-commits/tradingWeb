import { PanelBottom, PanelTop, Rows3, SlidersHorizontal } from 'lucide-react'
import { useTranslation } from 'react-i18next'

import type { AggregationStep, OrderBookDisplayMode } from './types'
import { aggregationSteps } from './types'
import styles from './MarketSidePanel.module.css'

type Props = {
  aggregationStep: AggregationStep
  displayMode: OrderBookDisplayMode
  settingsOpen: boolean
  onAggregationStepChange: (step: AggregationStep) => void
  onDisplayModeChange: (mode: OrderBookDisplayMode) => void
  onSettingsToggle: () => void
}

const displayModes: Array<{ mode: OrderBookDisplayMode; labelKey: string; icon: typeof Rows3 }> = [
  { mode: 'both', labelKey: 'trading.displayBothSides', icon: Rows3 },
  { mode: 'bids', labelKey: 'trading.displayBidsOnly', icon: PanelBottom },
  { mode: 'asks', labelKey: 'trading.displayAsksOnly', icon: PanelTop }
]

export function OrderBookToolbar({
  aggregationStep,
  displayMode,
  settingsOpen,
  onAggregationStepChange,
  onDisplayModeChange,
  onSettingsToggle
}: Props) {
  const { t } = useTranslation()

  return (
    <div className={styles.toolbar}>
      <div className={styles.modeButtons} aria-label={t('trading.orderBookDisplayMode')}>
        {displayModes.map((item) => (
          <button
            key={item.mode}
            type="button"
            className={displayMode === item.mode ? styles.activeIconButton : undefined}
            aria-label={t(item.labelKey)}
            title={t(item.labelKey)}
            onClick={() => onDisplayModeChange(item.mode)}
          >
            <item.icon size={15} />
          </button>
        ))}
      </div>
      <select
        aria-label={t('trading.priceAggregationStep')}
        className={styles.stepSelect}
        value={aggregationStep}
        onChange={(event) => onAggregationStepChange(Number(event.target.value) as AggregationStep)}
      >
        {aggregationSteps.map((step) => (
          <option key={step} value={step}>
            {step}
          </option>
        ))}
      </select>
      <button
        type="button"
        className={settingsOpen ? styles.activeIconButton : undefined}
        aria-label={t('trading.orderBookSettings')}
        title={t('trading.orderBookSettings')}
        onClick={onSettingsToggle}
      >
        <SlidersHorizontal size={15} />
      </button>
    </div>
  )
}
