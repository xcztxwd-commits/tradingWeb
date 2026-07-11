import { Suspense, lazy, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'

import { ChartTopToolbar } from './ChartTopToolbar'
import { isChartFullscreenShortcut, shouldIgnoreChartFullscreenShortcut } from './chartFullscreen'
import { KLineChartPanel } from './KLineChartPanel'
import type { ChartActionRequest } from './KLineChartPanel'
import { buildChartTradeMarkers } from '../chartTradeMarkers'
import { getDrawingShortcutAction, normalizeChartInterval, shouldIgnoreDrawingShortcut } from '../chartSettings'
import type { ChartSettings, ChartType, DrawingMagnetMode, DrawingTool, IndicatorSettings } from '../chartSettings'
import type { OrderResponse, PositionResponse } from '../../../components/tables/types'
import type { TradingPeriod } from '../../../features/market/tradingModels'
import styles from './ChartWorkspace.module.css'

const IndicatorSettingsModal = lazy(() =>
  import('./IndicatorSettingsModal').then((module) => ({ default: module.IndicatorSettingsModal }))
)

const ChartDrawingToolbar = lazy(() =>
  import('./ChartDrawingToolbar').then((module) => ({ default: module.ChartDrawingToolbar }))
)

type Props = {
  symbol: string
  token: string | null
  orders: OrderResponse[]
  positions: PositionResponse[]
  themeMode: 'dark' | 'light'
  allowMockFallback?: boolean
  settings: ChartSettings
  indicators: string[]
  onChartSettingsChange: (settings: ChartSettings) => void
  onResetChartSettings: () => void
  onChartTypeChange: (chartType: ChartType) => void
  onHighLowPriceMarksChange: (enabled: boolean) => void
  onPriceScaleModeChange: (priceScaleMode: ChartSettings['axisSettings']['priceScaleMode']) => void
  onDrawingToolChange: (tool: DrawingTool) => void
  onDrawingMagnetModeChange: (magnetMode: DrawingMagnetMode) => void
  onFavoriteIntervalToggle: (interval: TradingPeriod) => void
  onPeriodChange: (period: TradingPeriod) => void
  onIndicatorToggle: (indicator: string) => void
  onIndicatorSettingsChange: (settings: IndicatorSettings) => void
  onSelectPrice?: (price: number) => void
}

export function ChartWorkspace({
  symbol,
  token,
  orders,
  positions,
  themeMode,
  allowMockFallback = false,
  settings,
  indicators,
  onChartSettingsChange,
  onResetChartSettings,
  onChartTypeChange,
  onHighLowPriceMarksChange,
  onPriceScaleModeChange,
  onDrawingToolChange,
  onDrawingMagnetModeChange,
  onFavoriteIntervalToggle,
  onPeriodChange,
  onIndicatorToggle,
  onIndicatorSettingsChange,
  onSelectPrice
}: Props) {
  const { t } = useTranslation()
  const workspaceRef = useRef<HTMLElement | null>(null)
  const [indicatorModalOpen, setIndicatorModalOpen] = useState(false)
  const [drawingClearRequest, setDrawingClearRequest] = useState(0)
  const [drawingsHidden, setDrawingsHidden] = useState(false)
  const [indicatorsHidden, setIndicatorsHidden] = useState(false)
  const [chartActionRequest, setChartActionRequest] = useState<ChartActionRequest>({
    exportImage: 0,
    scrollToRealtime: 0,
    scrollToTimestamp: null
  })
  const [fullscreen, setFullscreen] = useState(false)
  const [fallbackFullscreen, setFallbackFullscreen] = useState(false)
  const workspaceClassName = fallbackFullscreen ? `${styles.workspace} ${styles.workspaceExpanded}` : styles.workspace
  const tradeMarkers = useMemo(
    () => buildChartTradeMarkers(symbol, orders, positions),
    [orders, positions, symbol]
  )
  const activeInterval = normalizeChartInterval(symbol, settings.interval)
  const activeSettings = activeInterval === settings.interval ? settings : { ...settings, interval: activeInterval }

  const handleClearDrawings = useCallback(() => {
    setDrawingClearRequest((request) => request + 1)
  }, [])

  const handleDrawingToolChange = useCallback(
    (tool: DrawingTool) => {
      if (tool !== 'cursor') setDrawingsHidden(false)
      onDrawingToolChange(tool)
    },
    [onDrawingToolChange]
  )

  const handleDrawingComplete = useCallback(() => {
    onDrawingToolChange('cursor')
  }, [onDrawingToolChange])

  const handleToggleAllHidden = useCallback(() => {
    const shouldHide = !(drawingsHidden && indicatorsHidden)
    setDrawingsHidden(shouldHide)
    setIndicatorsHidden(shouldHide)
  }, [drawingsHidden, indicatorsHidden])

  const handleScrollToRealtime = useCallback(() => {
    setChartActionRequest((current) => ({
      ...current,
      scrollToRealtime: current.scrollToRealtime + 1
    }))
  }, [])

  const handleExportChart = useCallback(() => {
    setChartActionRequest((current) => ({
      ...current,
      exportImage: current.exportImage + 1
    }))
  }, [])

  const handleJumpToTimestamp = useCallback((timestamp: number) => {
    setChartActionRequest((current) => ({
      ...current,
      scrollToTimestamp: { id: Date.now(), timestamp }
    }))
  }, [])

  const handleToggleFullscreen = useCallback(() => {
    const workspace = workspaceRef.current
    if (!workspace) return

    if (!canUseNativeFullscreen(workspace)) {
      setFallbackFullscreen((active) => !active)
      return
    }

    if (document.fullscreenElement === workspace) {
      void document.exitFullscreen().catch(() => undefined)
      return
    }

    void workspace.requestFullscreen().catch(() => undefined)
  }, [])

  useEffect(() => {
    const handleFullscreenChange = () => {
      const active = document.fullscreenElement === workspaceRef.current
      setFullscreen(active)
      if (active) setFallbackFullscreen(false)
    }

    document.addEventListener('fullscreenchange', handleFullscreenChange)
    return () => {
      document.removeEventListener('fullscreenchange', handleFullscreenChange)
    }
  }, [])

  useEffect(() => {
    if (!fallbackFullscreen) return

    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.body.style.overflow = previousOverflow
    }
  }, [fallbackFullscreen])

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.defaultPrevented || shouldIgnoreChartFullscreenShortcut(event.target as { tagName?: string; isContentEditable?: boolean; closest?: (selector: string) => unknown })) {
        return
      }
      if (!settings.shortcutSettings.fullscreenShortcut) return
      if (!isChartFullscreenShortcut(event)) return

      event.preventDefault()
      handleToggleFullscreen()
    }

    window.addEventListener('keydown', handleKeyDown)
    return () => {
      window.removeEventListener('keydown', handleKeyDown)
    }
  }, [handleToggleFullscreen, settings.shortcutSettings.fullscreenShortcut])

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.defaultPrevented) return
      if (!settings.shortcutSettings.drawingShortcuts) return
      if (shouldIgnoreDrawingShortcut(event.target as { tagName?: string; isContentEditable?: boolean; closest?: (selector: string) => unknown })) {
        return
      }

      const action = getDrawingShortcutAction(event)
      if (!action) return

      event.preventDefault()
      if (action.type === 'tool') {
        handleDrawingToolChange(action.tool)
        return
      }
      if (action.command === 'hideDrawings') {
        setDrawingsHidden((hidden) => !hidden)
      }
    }

    window.addEventListener('keydown', handleKeyDown)
    return () => {
      window.removeEventListener('keydown', handleKeyDown)
    }
  }, [handleDrawingToolChange, settings.shortcutSettings.drawingShortcuts])

  return (
    <section ref={workspaceRef} className={workspaceClassName}>
      <ChartTopToolbar
        symbol={symbol}
        indicators={indicators}
        settings={activeSettings}
        fullscreenActive={fullscreen || fallbackFullscreen}
        onChartSettingsChange={onChartSettingsChange}
        onResetChartSettings={onResetChartSettings}
        onChartTypeChange={onChartTypeChange}
        onHighLowPriceMarksChange={onHighLowPriceMarksChange}
        onPriceScaleModeChange={onPriceScaleModeChange}
        onIndicatorToggle={onIndicatorToggle}
        onFavoriteIntervalToggle={onFavoriteIntervalToggle}
        onOpenIndicatorSettings={() => setIndicatorModalOpen(true)}
        onIntervalChange={onPeriodChange}
        onScrollToRealtime={handleScrollToRealtime}
        onJumpToTimestamp={handleJumpToTimestamp}
        onExportChart={handleExportChart}
        onToggleFullscreen={handleToggleFullscreen}
      />
      <div className={styles.chartBody}>
        <Suspense fallback={<DrawingToolbarFallback />}>
          <ChartDrawingToolbar
            drawingsHidden={drawingsHidden}
            indicatorsHidden={indicatorsHidden}
            settings={settings.drawingToolSettings}
            onDrawingToolChange={handleDrawingToolChange}
            onDrawingMagnetModeChange={onDrawingMagnetModeChange}
            onClearDrawings={handleClearDrawings}
            onToggleAllHidden={handleToggleAllHidden}
            onToggleDrawingsHidden={() => setDrawingsHidden((hidden) => !hidden)}
            onToggleIndicatorsHidden={() => setIndicatorsHidden((hidden) => !hidden)}
          />
        </Suspense>
        <KLineChartPanel
          chartType={settings.chartType}
          timezone={settings.timezone}
          candleStyle={settings.candleStyle}
          axisSettings={settings.axisSettings}
          layoutSettings={settings.layoutSettings}
          chartActionRequest={chartActionRequest}
          drawingClearRequest={drawingClearRequest}
          drawingsVisible={!drawingsHidden}
          drawingToolSettings={settings.drawingToolSettings}
          tradeMarkers={tradeMarkers}
          indicatorsVisible={!indicatorsHidden}
          indicatorSettings={settings.indicatorSettings}
          allowMockFallback={allowMockFallback}
          onCandlePriceSelect={onSelectPrice}
          onDrawingComplete={handleDrawingComplete}
          fullscreenActive={fullscreen || fallbackFullscreen}
          period={activeInterval}
          symbol={symbol}
          themeMode={themeMode}
          token={token}
        />
      </div>
      {indicatorModalOpen ? (
        <Suspense fallback={<div className={styles.modalLoading} role="status">{t('chart.loadingIndicatorSettings')}</div>}>
          <IndicatorSettingsModal
            open={indicatorModalOpen}
            settings={settings.indicatorSettings}
            onClose={() => setIndicatorModalOpen(false)}
            onConfirm={(nextSettings) => {
              onIndicatorSettingsChange(nextSettings)
              setIndicatorModalOpen(false)
            }}
          />
        </Suspense>
      ) : null}
    </section>
  )
}

function DrawingToolbarFallback() {
  const { t } = useTranslation()

  return (
    <div className={styles.drawingToolbarLoading} role="status" aria-label={t('chart.loadingDrawingTools')}>
      <span aria-hidden="true" />
      <span aria-hidden="true" />
      <span aria-hidden="true" />
      <span aria-hidden="true" />
    </div>
  )
}

function canUseNativeFullscreen(target: HTMLElement) {
  return document.fullscreenEnabled && typeof document.exitFullscreen === 'function' && typeof target.requestFullscreen === 'function'
}
