import {
  Activity,
  BarChart3,
  Camera,
  ChevronDown,
  ChevronsRight,
  Clock3,
  Eye,
  LocateFixed,
  Maximize2,
  Minimize2,
  RotateCcw,
  Scale,
  Settings,
  SlidersHorizontal,
  X,
} from 'lucide-react'
import { type FormEvent, useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'

import {
  chartTimezoneOptions,
  chartTypeOptions,
  getIntervalByShortcutKey,
  indicatorConfigOptions,
  priceScaleModeOptions,
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
  onChartSettingsChange: (settings: ChartSettings) => void
  onResetChartSettings: () => void
  onChartTypeChange: (chartType: ChartType) => void
  onHighLowPriceMarksChange: (enabled: boolean) => void
  onPriceScaleModeChange: (mode: ChartSettings['axisSettings']['priceScaleMode']) => void
  onTooltipStyleChange: (style: ChartSettings['axisSettings']['tooltipStyle']) => void
  onIndicatorToggle: (indicator: string) => void
  onFavoriteIntervalToggle: (interval: TradingPeriod) => void
  onOpenIndicatorSettings: () => void
  onIntervalChange: (period: TradingPeriod) => void
  onScrollToRealtime: () => void
  onJumpToTimestamp: (timestamp: number) => void
  onExportChart: () => void
  fullscreenActive: boolean
  onToggleFullscreen: () => void
}

export function ChartTopToolbar({
  settings,
  indicators,
  onChartSettingsChange,
  onResetChartSettings,
  onChartTypeChange,
  onHighLowPriceMarksChange,
  onPriceScaleModeChange,
  onTooltipStyleChange,
  onIndicatorToggle,
  onFavoriteIntervalToggle,
  onOpenIndicatorSettings,
  onIntervalChange,
  onScrollToRealtime,
  onJumpToTimestamp,
  onExportChart,
  fullscreenActive,
  onToggleFullscreen
}: Props) {
  const { t } = useTranslation()
  const [intervalDropdownOpen, setIntervalDropdownOpen] = useState(false)
  const [chartTypeMenuOpen, setChartTypeMenuOpen] = useState(false)
  const [indicatorMenuOpen, setIndicatorMenuOpen] = useState(false)
  const [chartSettingsOpen, setChartSettingsOpen] = useState(false)
  const [jumpTimestampValue, setJumpTimestampValue] = useState('')
  const chartTypeMenuRef = useRef<HTMLDivElement | null>(null)
  const indicatorMenuRef = useRef<HTMLDivElement | null>(null)
  const favoriteIntervals = useMemo(() => quickChartIntervals(settings), [settings.favoriteIntervals])
  const activeChartType = chartTypeOptions.find((item) => item.value === settings.chartType) ?? chartTypeOptions[0]
  const toolbarPopoverOpen = intervalDropdownOpen || chartTypeMenuOpen || indicatorMenuOpen || chartSettingsOpen

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey) return
      if (!settings.shortcutSettings.intervalShortcuts) return
      if (shouldIgnoreIntervalShortcut(event.target as { tagName?: string; isContentEditable?: boolean }, toolbarPopoverOpen)) {
        return
      }

      const interval = getIntervalByShortcutKey(event.key, favoriteIntervals)
      if (!interval) return

      event.preventDefault()
      onIntervalChange(interval)
    }

    window.addEventListener('keydown', handleKeyDown)
    return () => {
      window.removeEventListener('keydown', handleKeyDown)
    }
  }, [
    favoriteIntervals,
    intervalDropdownOpen,
    chartTypeMenuOpen,
    indicatorMenuOpen,
    chartSettingsOpen,
    onIntervalChange,
    settings.shortcutSettings.intervalShortcuts,
    toolbarPopoverOpen
  ])

  useEffect(() => {
    if (!chartTypeMenuOpen && !indicatorMenuOpen) return

    const handlePointerDown = (event: PointerEvent) => {
      if (!(event.target instanceof Node)) return
      if (chartTypeMenuRef.current !== null && chartTypeMenuRef.current.contains(event.target)) return
      if (indicatorMenuRef.current !== null && indicatorMenuRef.current.contains(event.target)) return
      setChartTypeMenuOpen(false)
      setIndicatorMenuOpen(false)
    }

    document.addEventListener('pointerdown', handlePointerDown)
    return () => {
      document.removeEventListener('pointerdown', handlePointerDown)
    }
  }, [chartTypeMenuOpen, indicatorMenuOpen])

  const handleJumpSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const timestamp = parseLocalDateTimeValue(jumpTimestampValue)
    if (timestamp !== null) onJumpToTimestamp(timestamp)
  }

  const updateAxisSettings = (patch: Partial<ChartSettings['axisSettings']>) => {
    const axisSettings = { ...settings.axisSettings, ...patch }
    onChartSettingsChange({
      ...settings,
      axisSettings: {
        ...axisSettings,
        highLowPriceMarks: axisSettings.highPriceMark && axisSettings.lowPriceMark
      }
    })
  }

  const updateLayoutSettings = (patch: Partial<ChartSettings['layoutSettings']>) => {
    onChartSettingsChange({
      ...settings,
      layoutSettings: {
        ...settings.layoutSettings,
        ...patch
      }
    })
  }

  return (
    <div className={styles.toolbar}>
      <div className={styles.toolbarScroller}>
        <div className={styles.group} aria-label="K line periods">
          {favoriteIntervals.map((item) => (
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
              className={favoriteIntervals.some((item) => item.value === settings.interval) ? '' : styles.active}
              aria-expanded={intervalDropdownOpen}
              onClick={() => setIntervalDropdownOpen((open) => !open)}
            >
              {t('common.more')}
              <ChevronDown size={14} />
            </button>
            <IntervalDropdown
              activeInterval={settings.interval}
              favoriteIntervals={settings.favoriteIntervals}
              open={intervalDropdownOpen}
              onClose={() => setIntervalDropdownOpen(false)}
              onFavoriteIntervalToggle={onFavoriteIntervalToggle}
              onSelect={onIntervalChange}
            />
          </div>
        </div>

        <div className={styles.chartTypeWrap} ref={chartTypeMenuRef}>
          <button
            type="button"
            className={styles.chartTypeButton}
            aria-label={t('chart.candleChartType')}
            aria-haspopup="menu"
            aria-expanded={chartTypeMenuOpen}
            onClick={() => setChartTypeMenuOpen((open) => !open)}
          >
            <BarChart3 size={15} />
            <span>{t(activeChartType.label)}</span>
            <ChevronDown size={13} />
          </button>
          {chartTypeMenuOpen ? (
            <div role="menu" className={styles.chartTypeDropdown} data-shortcut-disabled="true">
              {chartTypeOptions.map((item) => (
                <button
                  key={item.value}
                  type="button"
                  className={styles.chartTypeOption}
                  role="menuitemradio"
                  aria-checked={item.value === settings.chartType}
                  onClick={() => {
                    onChartTypeChange(item.value)
                    setChartTypeMenuOpen(false)
                  }}
                >
                  {t(item.label)}
                </button>
              ))}
            </div>
          ) : null}
        </div>

        <label className={styles.selectLabel}>
          <Scale size={15} />
          <select
            aria-label={t('chart.priceScaleMode')}
            value={settings.axisSettings.priceScaleMode}
            onChange={(event) => onPriceScaleModeChange(event.target.value as ChartSettings['axisSettings']['priceScaleMode'])}
          >
            {priceScaleModeOptions.map((item) => (
              <option key={item.value} value={item.value}>
                {t(item.label)}
              </option>
            ))}
          </select>
        </label>

        <label className={styles.toggleLabel}>
          <Eye size={15} />
          <input
            type="checkbox"
            checked={settings.axisSettings.highLowPriceMarks}
            onChange={(event) => onHighLowPriceMarksChange(event.target.checked)}
          />
          {t('chart.highLowMarks')}
        </label>

        <label className={styles.selectLabel}>
          <Clock3 size={15} />
          <select
            aria-label={t('chart.tooltipStyle')}
            value={settings.axisSettings.tooltipStyle}
            onChange={(event) => onTooltipStyleChange(event.target.value as ChartSettings['axisSettings']['tooltipStyle'])}
          >
            <option value="standard">{t('chart.tooltipStyles.standard')}</option>
            <option value="compact">{t('chart.tooltipStyles.compact')}</option>
            <option value="hidden">{t('chart.tooltipStyles.hidden')}</option>
          </select>
        </label>

        <form className={styles.jumpForm} data-shortcut-disabled="true" onSubmit={handleJumpSubmit}>
          <input
            type="datetime-local"
            value={jumpTimestampValue}
            aria-label={t('chart.jumpTimestamp')}
            onChange={(event) => setJumpTimestampValue(event.target.value)}
          />
          <button type="submit" title={t('chart.jumpToTimestamp')} aria-label={t('chart.jumpToTimestamp')}>
            <LocateFixed size={14} />
          </button>
        </form>

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
          className={styles.actionButton}
          title={t('chart.chartSettings')}
          aria-label={t('chart.chartSettings')}
          aria-expanded={chartSettingsOpen}
          onClick={() => setChartSettingsOpen((open) => !open)}
        >
          <Settings size={15} />
        </button>
        <button
          type="button"
          className={styles.actionButton}
          title={t('chart.scrollToRealtime')}
          aria-label={t('chart.scrollToRealtime')}
          onClick={onScrollToRealtime}
        >
          <ChevronsRight size={15} />
        </button>
        <button
          type="button"
          className={styles.actionButton}
          title={t('chart.exportImage')}
          aria-label={t('chart.exportImage')}
          onClick={onExportChart}
        >
          <Camera size={15} />
        </button>
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
      {chartSettingsOpen ? (
        <div
          className={styles.settingsLayer}
          data-shortcut-disabled="true"
          onMouseDown={(event) => {
            if (event.target === event.currentTarget) setChartSettingsOpen(false)
          }}
        >
          <section className={styles.settingsDialog} role="dialog" aria-modal="true" aria-label={t('chart.chartSettings')}>
            <header className={styles.settingsHeader}>
              <h2>{t('chart.chartSettings')}</h2>
              <button type="button" aria-label={t('chart.closeChartSettings')} onClick={() => setChartSettingsOpen(false)}>
                <X size={20} />
              </button>
            </header>
            <div className={styles.settingsGrid}>
              <label className={styles.settingField}>
                <span>{t('chart.candleChartType')}</span>
                <select
                  aria-label={t('chart.candleChartType')}
                  value={settings.chartType}
                  onChange={(event) => onChartTypeChange(event.target.value as ChartType)}
                >
                  {chartTypeOptions.map((item) => (
                    <option key={item.value} value={item.value}>
                      {t(item.label)}
                    </option>
                  ))}
                </select>
              </label>
              <label className={styles.settingSwitch}>
                <span>{t('chart.latestPriceMark')}</span>
                <input
                  className={styles.switchInput}
                  type="checkbox"
                  checked={settings.axisSettings.latestPrice}
                  onChange={(event) => updateAxisSettings({ latestPrice: event.target.checked })}
                />
              </label>
              <label className={styles.settingSwitch}>
                <span>{t('chart.highestPriceMark')}</span>
                <input
                  className={styles.switchInput}
                  type="checkbox"
                  checked={settings.axisSettings.highPriceMark}
                  onChange={(event) => updateAxisSettings({ highPriceMark: event.target.checked })}
                />
              </label>
              <label className={styles.settingSwitch}>
                <span>{t('chart.lowestPriceMark')}</span>
                <input
                  className={styles.switchInput}
                  type="checkbox"
                  checked={settings.axisSettings.lowPriceMark}
                  onChange={(event) => updateAxisSettings({ lowPriceMark: event.target.checked })}
                />
              </label>
              <label className={styles.settingSwitch}>
                <span>{t('chart.indicatorLastValue')}</span>
                <input
                  className={styles.switchInput}
                  type="checkbox"
                  checked={settings.axisSettings.indicatorLastValue}
                  onChange={(event) => updateAxisSettings({ indicatorLastValue: event.target.checked })}
                />
              </label>
              <label className={styles.settingField}>
                <span>{t('chart.priceScaleMode')}</span>
                <select
                  aria-label={t('chart.priceScaleMode')}
                  value={settings.axisSettings.priceScaleMode}
                  onChange={(event) => onPriceScaleModeChange(event.target.value as ChartSettings['axisSettings']['priceScaleMode'])}
                >
                  {priceScaleModeOptions.map((item) => (
                    <option key={item.value} value={item.value}>
                      {t(item.label)}
                    </option>
                  ))}
                </select>
              </label>
              <label className={styles.settingSwitch}>
                <span>{t('chart.invertedCoordinate')}</span>
                <input
                  className={styles.switchInput}
                  type="checkbox"
                  checked={settings.axisSettings.invertedCoordinate}
                  onChange={(event) => updateAxisSettings({ invertedCoordinate: event.target.checked })}
                />
              </label>
              <label className={styles.settingSwitch}>
                <span>{t('chart.countdown')}</span>
                <input
                  className={styles.switchInput}
                  type="checkbox"
                  checked={settings.axisSettings.countdown}
                  onChange={(event) => updateAxisSettings({ countdown: event.target.checked })}
                />
              </label>
              <label className={styles.settingField}>
                <span>{t('chart.gridLines')}</span>
                <select
                  aria-label={t('chart.gridLines')}
                  value={settings.layoutSettings.gridLines}
                  onChange={(event) => updateLayoutSettings({ gridLines: event.target.value as ChartSettings['layoutSettings']['gridLines'] })}
                >
                  <option value="both">{t('chart.gridLineModes.both')}</option>
                  <option value="horizontal">{t('chart.gridLineModes.horizontal')}</option>
                  <option value="vertical">{t('chart.gridLineModes.vertical')}</option>
                  <option value="none">{t('chart.gridLineModes.none')}</option>
                </select>
              </label>
              <label className={styles.settingField}>
                <span>{t('chart.timezone')}</span>
                <select
                  aria-label={t('chart.timezone')}
                  value={settings.timezone}
                  onChange={(event) => onChartSettingsChange({ ...settings, timezone: event.target.value as ChartSettings['timezone'] })}
                >
                  {chartTimezoneOptions.map((item) => (
                    <option key={item.value} value={item.value}>
                      {t(item.label)}
                    </option>
                  ))}
                </select>
              </label>
            </div>
            <footer className={styles.settingsFooter}>
              <button type="button" className={styles.resetButton} onClick={onResetChartSettings}>
                <RotateCcw size={14} />
                {t('chart.resetChartSettings')}
              </button>
            </footer>
          </section>
        </div>
      ) : null}
    </div>
  )
}

function parseLocalDateTimeValue(value: string) {
  if (!value) return null
  const timestamp = new Date(value).getTime()
  return Number.isFinite(timestamp) ? timestamp : null
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
