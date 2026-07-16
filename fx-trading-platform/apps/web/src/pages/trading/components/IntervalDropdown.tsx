import { Star } from 'lucide-react'
import { useEffect, useRef } from 'react'
import { useTranslation } from 'react-i18next'

import type { ChartIntervalOption } from '../chartSettings'
import type { TradingPeriod } from '../../../features/market/tradingModels'
import styles from './ChartTopToolbar.module.css'

type Props = {
  activeInterval: TradingPeriod
  favoriteIntervals: TradingPeriod[]
  options: ChartIntervalOption[]
  open: boolean
  onClose: () => void
  onFavoriteIntervalToggle: (interval: TradingPeriod) => void
  onSelect: (interval: TradingPeriod) => void
}

export function IntervalDropdown({
  activeInterval,
  favoriteIntervals,
  options,
  open,
  onClose,
  onFavoriteIntervalToggle,
  onSelect
}: Props) {
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
      <div className={styles.dropdownHeader}>
        <span>{t('trading.intervalSelect')}</span>
      </div>

      <div className={styles.intervalGrid}>
        {options.map((item) => {
          const favorite = favoriteIntervals.includes(item.value)
          const lockedFavorite = favorite && favoriteIntervals.length <= 1
          return (
            <div key={item.value} className={styles.intervalOption}>
              <button
                type="button"
                className={item.value === activeInterval ? styles.active : ''}
                onClick={() => selectInterval(item)}
              >
                {t(item.label)}
              </button>
              <button
                type="button"
                className={`${styles.intervalStar} ${favorite ? styles.intervalStarActive : ''}`}
                aria-label={t(favorite ? 'trading.removeFavoriteInterval' : 'trading.addFavoriteInterval', {
                  interval: t(item.label)
                })}
                aria-pressed={favorite}
                disabled={lockedFavorite}
                onClick={() => {
                  if (!lockedFavorite) onFavoriteIntervalToggle(item.value)
                }}
              >
                <Star size={13} fill={favorite ? 'currentColor' : 'none'} />
              </button>
            </div>
          )
        })}
      </div>
    </div>
  )
}
