import { RotateCcw, X } from 'lucide-react'
import { useTranslation } from 'react-i18next'

import { ThemeSwitcher } from '../../../design-system/theme/ThemeSwitcher'
import styles from '../TradingPage.module.css'
import type { TradingSettingsDialogProps } from '../tradingPageViewModels'

export function TradingSettingsDialog({ layoutControls, open, onClose }: TradingSettingsDialogProps) {
  const { t } = useTranslation()

  if (!open) return null

  return (
    <div
      className={styles.settingsLayer}
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose()
      }}
    >
      <section className={styles.settingsDialog} role="dialog" aria-modal="true" aria-label={t('trading.tradingSettings')}>
        <header className={styles.settingsHeader}>
          <h2>{t('trading.tradingSettings')}</h2>
          <button type="button" aria-label={t('trading.closeTradingSettings')} onClick={onClose}>
            <X size={20} aria-hidden="true" />
          </button>
        </header>
        <div className={styles.settingsRow}>
          <ThemeSwitcher />
        </div>
        <div className={styles.settingsSection}>
          <strong>{t('trading.workspaceLayout')}</strong>
          <div className={styles.layoutPresetGroup} role="group" aria-label={t('trading.workspaceLayoutPreset')}>
            <button
              type="button"
              className={`${styles.layoutPresetButton} ${layoutControls.activePreset === 'default' ? styles.activeLayoutPresetButton : ''}`}
              aria-pressed={layoutControls.activePreset === 'default'}
              onClick={() => layoutControls.applyPreset('default')}
            >
              {t('trading.defaultLayout')}
            </button>
            <button
              type="button"
              className={`${styles.layoutPresetButton} ${layoutControls.activePreset === 'chart-focus' ? styles.activeLayoutPresetButton : ''}`}
              aria-pressed={layoutControls.activePreset === 'chart-focus'}
              onClick={() => layoutControls.applyPreset('chart-focus')}
            >
              {t('trading.chartLayout')}
            </button>
            <button
              type="button"
              className={`${styles.layoutPresetButton} ${layoutControls.activePreset === 'order-focus' ? styles.activeLayoutPresetButton : ''}`}
              aria-pressed={layoutControls.activePreset === 'order-focus'}
              onClick={() => layoutControls.applyPreset('order-focus')}
            >
              {t('trading.orderLayout')}
            </button>
          </div>
          <button type="button" className={styles.layoutResetButton} onClick={layoutControls.resetLayout}>
            <RotateCcw size={15} aria-hidden="true" />
            {t('trading.resetLayout')}
          </button>
        </div>
      </section>
    </div>
  )
}
