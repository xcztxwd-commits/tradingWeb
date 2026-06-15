import {
  Activity,
  BarChart3,
  ChevronDown,
  Maximize2,
  Minimize2,
  SlidersHorizontal,
} from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'

import {
  chartTypeOptions,
  getIntervalByShortcutKey,
  indicatorConfigOptions,
  quickChartIntervals,
  shouldIgnoreIntervalShortcut
} from '../chartSettings'
import type { ChartSettings, ChartType } from '../chartSettings'
import type { TradingPeriod } from '../../../features/market/tradingModels'
import { IntervalDropdown } from './IntervalDropdown'
import styles from './ChartTopToolbar.module.css'

type Props = {
  settings: ChartSettings
  indicators: string[]
  onChartTypeChange: (chartType: ChartType) => void
  onIndicatorToggle: (indicator: string) => void
  onOpenIndicatorSettings: () => void
  onIntervalChange: (period: TradingPeriod) => void
  fullscreenActive: boolean
  onToggleFullscreen: () => void
}

export function ChartTopToolbar({
  settings,
  indicators,
  onChartTypeChange,
  onIndicatorToggle,
  onOpenIndicatorSettings,
  onIntervalChange,
  fullscreenActive,
  onToggleFullscreen
}: Props) {
  const { t } = useTranslation()
  const [intervalDropdownOpen, setIntervalDropdownOpen] = useState(false)
  const [indicatorMenuOpen, setIndicatorMenuOpen] = useState(false)
  const indicatorMenuRef = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey) return
      if (shouldIgnoreIntervalShortcut(event.target as { tagName?: string; isContentEditable?: boolean }, intervalDropdownOpen)) {
        return
      }

      const interval = getIntervalByShortcutKey(event.key)
      if (!interval) return

      event.preventDefault()
      onIntervalChange(interval)
    }

    window.addEventListener('keydown', handleKeyDown)
    return () => {
      window.removeEventListener('keydown', handleKeyDown)
    }
  }, [intervalDropdownOpen, onIntervalChange])

  useEffect(() => {
    if (!indicatorMenuOpen) return

    const handlePointerDown = (event: PointerEvent) => {
      if (!(event.target instanceof Node)) return
      if (indicatorMenuRef.current === null || indicatorMenuRef.current.contains(event.target as Node)) return
      setIndicatorMenuOpen(false)
    }

    document.addEventListener('pointerdown', handlePointerDown)
    return () => {
      document.removeEventListener('pointerdown', handlePointerDown)
    }
  }, [indicatorMenuOpen])

  return (
    <div className={styles.toolbar}>
      <div className={styles.toolbarScroller}>
        <div className={styles.group} aria-label="K line periods">
          {quickChartIntervals.map((item) => (
            <button
              key={item.value}
              type="button"
              className={item.value === settings.interval ? styles.active : ''}
              onClick={() => onIntervalChange(item.value)}
            >
              {t(item.label)}
            </button>
          ))}
          <div className={styles.dropdownWrap}>
            <button
              type="button"
              className={quickChartIntervals.some((item) => item.value === settings.interval) ? '' : styles.active}
              aria-expanded={intervalDropdownOpen}
              onClick={() => setIntervalDropdownOpen((open) => !open)}
            >
              {t('common.more')}
              <ChevronDown size={14} />
            </button>
            <IntervalDropdown
              activeInterval={settings.interval}
              open={intervalDropdownOpen}
              onClose={() => setIntervalDropdownOpen(false)}
              onSelect={onIntervalChange}
            />
          </div>
        </div>

        <label className={styles.selectLabel}>
          <BarChart3 size={15} />
          <select value={settings.chartType} onChange={(event) => onChartTypeChange(event.target.value as ChartType)}>
            {chartTypeOptions.map((item) => (
              <option key={item.value} value={item.value}>
                {t(item.label)}
              </option>
            ))}
          </select>
        </label>

        <div className={styles.group} aria-label="Indicators">
          <div className={styles.dropdownWrap} ref={indicatorMenuRef}>
            <button
              type="button"
              className={indicators.length > 0 ? styles.active : ''}
              aria-label={t('chart.technicalIndicators')}
              aria-expanded={indicatorMenuOpen}
              onClick={() => setIndicatorMenuOpen((open) => !open)}
            >
              <Activity size={14} />
              {t('chart.indicators')}
              {indicators.length > 0 ? <span className={styles.countBadge}>{indicators.length}</span> : null}
              <ChevronDown size={14} />
            </button>
            {indicatorMenuOpen ? (
              <div className={styles.indicatorDropdown} data-shortcut-disabled="true">
                <IndicatorMenuGroup
                  labelKey="chart.indicatorGroups.trading"
                  indicators={indicators}
                  group="trading"
                  onIndicatorToggle={onIndicatorToggle}
                />
                <IndicatorMenuGroup
                  labelKey="chart.indicatorGroups.main"
                  indicators={indicators}
                  group="main"
                  onIndicatorToggle={onIndicatorToggle}
                />
                <IndicatorMenuGroup
                  labelKey="chart.indicatorGroups.secondary"
                  indicators={indicators}
                  group="secondary"
                  onIndicatorToggle={onIndicatorToggle}
                />
              </div>
            ) : null}
          </div>
          <button type="button" className={styles.iconTextButton} onClick={onOpenIndicatorSettings}>
            <SlidersHorizontal size={14} />
            {t('settings.title')}
          </button>
        </div>
      </div>
      <div className={styles.toolbarActions}>
        <button
          type="button"
          className={styles.fullscreenButton}
          title={fullscreenActive ? t('chart.exitFullscreenWithShortcut') : t('chart.fullscreenWithShortcut')}
          aria-label={fullscreenActive ? t('chart.exitFullscreen') : t('chart.fullscreen')}
          aria-pressed={fullscreenActive}
          onClick={onToggleFullscreen}
        >
          {fullscreenActive ? <Minimize2 size={15} /> : <Maximize2 size={15} />}
        </button>
      </div>
    </div>
  )
}

function IndicatorMenuGroup({
  labelKey,
  group,
  indicators,
  onIndicatorToggle
}: {
  labelKey: string
  group: 'trading' | 'main' | 'secondary'
  indicators: string[]
  onIndicatorToggle: (indicator: string) => void
}) {
  const { t } = useTranslation()
  const options = indicatorConfigOptions.filter((item) => item.group === group)

  return (
    <div className={styles.indicatorMenuGroup}>
      <span>{t(labelKey)}</span>
      {options.map((item) => (
        <label key={item.value} className={styles.indicatorMenuItem}>
          <input
            type="checkbox"
            checked={indicators.includes(item.value)}
            onChange={() => onIndicatorToggle(item.value)}
          />
          {t(item.label)}
        </label>
      ))}
    </div>
  )
}
