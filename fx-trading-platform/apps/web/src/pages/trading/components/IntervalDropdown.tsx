import { Keyboard, Pencil, Plus, X } from 'lucide-react'
import { useEffect, useRef } from 'react'
import { useTranslation } from 'react-i18next'

import { allChartIntervals } from '../chartSettings'
import type { ChartIntervalOption } from '../chartSettings'
import type { TradingPeriod } from '../../../features/market/tradingModels'
import styles from './ChartTopToolbar.module.css'

type Props = {
  activeInterval: TradingPeriod
  open: boolean
  onClose: () => void
  onSelect: (interval: TradingPeriod) => void
}

export function IntervalDropdown({ activeInterval, open, onClose, onSelect }: Props) {
  const { t } = useTranslation()
  const rootRef = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    if (!open) return

    const handlePointerDown = (event: PointerEvent) => {
      if (rootRef.current?.contains(event.target as Node)) return
      onClose()
    }
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }

    document.addEventListener('pointerdown', handlePointerDown)
    document.addEventListener('keydown', handleKeyDown)
    return () => {
      document.removeEventListener('pointerdown', handlePointerDown)
      document.removeEventListener('keydown', handleKeyDown)
    }
  }, [onClose, open])

  if (!open) return null

  const selectInterval = (item: ChartIntervalOption) => {
    onSelect(item.value)
    onClose()
  }

  return (
    <div
      ref={rootRef}
      className={styles.intervalDropdown}
      data-shortcut-disabled="true"
      role="dialog"
      aria-label={t('trading.intervalSelect')}
    >
      <div className={styles.shortcutTip}>
        <Keyboard size={15} />
        <div>
          <strong>{t('trading.intervalHelpTitle')}</strong>
          <span>{t('trading.intervalHelpDescription')}</span>
        </div>
        <button type="button" aria-label={t('trading.closeIntervalHelp')} onClick={onClose}>
          <X size={15} />
        </button>
      </div>

      <div className={styles.dropdownHeader}>
        <span>{t('trading.intervalSelect')}</span>
        <div className={styles.dropdownActions}>
          <button type="button" aria-disabled="true">
            <Plus size={14} />
            {t('trading.customInterval')}
          </button>
          <button type="button" aria-disabled="true">
            <Pencil size={14} />
            {t('common.edit')}
          </button>
        </div>
      </div>

      <div className={styles.intervalGrid}>
        {allChartIntervals.map((item) => (
          <button
            key={item.value}
            type="button"
            className={item.value === activeInterval ? styles.active : ''}
            onClick={() => selectInterval(item)}
          >
            {t(item.label)}
          </button>
        ))}
      </div>
    </div>
  )
}
