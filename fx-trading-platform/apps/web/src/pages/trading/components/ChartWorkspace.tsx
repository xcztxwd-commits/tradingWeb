import { Suspense, lazy, useCallback, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'

import { ChartTopToolbar } from './ChartTopToolbar'
import { isChartFullscreenShortcut, shouldIgnoreChartFullscreenShortcut } from './chartFullscreen'
import { KLineChartPanel } from './KLineChartPanel'
import { getDrawingShortcutAction, shouldIgnoreDrawingShortcut } from '../chartSettings'
import type { ChartSettings, ChartType, DrawingMagnetMode, DrawingTool, IndicatorSettings } from '../chartSettings'
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
  themeMode: 'dark' | 'light'
  allowMockFallback?: boolean
  settings: ChartSettings
  indicators: string[]
  onChartTypeChange: (chartType: ChartType) => void
  onDrawingToolChange: (tool: DrawingTool) => void
  onDrawingMagnetModeChange: (magnetMode: DrawingMagnetMode) => void
  onPeriodChange: (period: TradingPeriod) => void
  onIndicatorToggle: (indicator: string) => void
  onIndicatorSettingsChange: (settings: IndicatorSettings) => void
}

export function ChartWorkspace({
  symbol,
  token,
  themeMode,
  allowMockFallback = true,
  settings,
  indicators,
  onChartTypeChange,
  onDrawingToolChange,
  onDrawingMagnetModeChange,
  onPeriodChange,
  onIndicatorToggle,
  onIndicatorSettingsChange
}: Props) {
  const { t } = useTranslation()
  const workspaceRef = useRef<HTMLElement | null>(null)
  const [indicatorModalOpen, setIndicatorModalOpen] = useState(false)
  const [drawingClearRequest, setDrawingClearRequest] = useState(0)
  const [drawingsHidden, setDrawingsHidden] = useState(false)
  const [indicatorsHidden, setIndicatorsHidden] = useState(false)
  const [fullscreen, setFullscreen] = useState(false)
  const [fallbackFullscreen, setFallbackFullscreen] = useState(false)
  const workspaceClassName = fallbackFullscreen ? `${styles.workspace} ${styles.workspaceExpanded}` : styles.workspace

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
      if (!isChartFullscreenShortcut(event)) return

      event.preventDefault()
      handleToggleFullscreen()
    }

    window.addEventListener('keydown', handleKeyDown)
    return () => {
      window.removeEventListener('keydown', handleKeyDown)
    }
  }, [handleToggleFullscreen])

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.defaultPrevented) return
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
  }, [handleDrawingToolChange])

  return (
    <section ref={workspaceRef} className={workspaceClassName}>
      <ChartTopToolbar
        indicators={indicators}
        settings={settings}
        fullscreenActive={fullscreen || fallbackFullscreen}
        onChartTypeChange={onChartTypeChange}
        onIndicatorToggle={onIndicatorToggle}
        onOpenIndicatorSettings={() => setIndicatorModalOpen(true)}
        onIntervalChange={onPeriodChange}
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
          drawingClearRequest={drawingClearRequest}
          drawingsVisible={!drawingsHidden}
          drawingToolSettings={settings.drawingToolSettings}
          indicatorsVisible={!indicatorsHidden}
          indicatorSettings={settings.indicatorSettings}
          allowMockFallback={allowMockFallback}
          onDrawingComplete={handleDrawingComplete}
          fullscreenActive={fullscreen || fallbackFullscreen}
          period={settings.interval}
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
