import { dispose, init } from 'klinecharts'
import { Copy, Download, GripVertical, Layers, ListTree, Lock, MoreHorizontal, Paintbrush, Trash2, Unlock, X } from 'lucide-react'
import { memo, useCallback, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'

import { buildIndicatorApplyPlan, defaultChartSettings, drawingToolToOverlayName, getChartVisualStyles } from '../chartSettings'
import type { ChartSettings, ChartType, IndicatorApplyDescriptor, IndicatorSettings } from '../chartSettings'
import {
  hasMoreHistoricalCandles,
  historicalCandleBatchSize,
  loadChartCandles,
  resolveHistoricalCandleEndTime
} from '../chartCandleData'
import {
  buildRiskTemplateDrawings,
  clonePersistedDrawing,
  loadPersistedChartDrawings,
  savePersistedChartDrawings
} from '../chartDrawingPersistence'
import type { PersistedChartDrawing } from '../chartDrawingPersistence'
import { buildTradeMarkerOverlays, tradeMarkerOverlayGroupId } from '../chartTradeMarkers'
import type { ChartTradeMarker } from '../chartTradeMarkers'
import {
  formatMarketPrice,
  getPricePrecision,
  mapQuoteToTradingQuote,
  subscribeQuote,
  toKLinePeriod,
  type BackendQuote,
  type TradingCandle,
  type TradingPeriod
} from '@fx-platform/frontend-core'
import { registerTradingGeneratedIndicators } from '../generatedTradingIndicators'
import { registerTradingDrawingOverlays } from '../tradingDrawingOverlays'
import { registerWeightedMovingAverageIndicator } from '../weightedMovingAverageIndicator'
import { chartColorWithAlpha, chartTheme } from '../chartTheme.ts'
import { useResizeObserver } from './useResizeObserver'
import styles from './KLineChartPanel.module.css'

type Props = {
  symbol: string
  token: string | null
  themeMode: 'dark' | 'light'
  period: TradingPeriod
  timezone: ChartSettings['timezone']
  chartType: ChartType
  candleStyle: ChartSettings['candleStyle']
  axisSettings: ChartSettings['axisSettings']
  layoutSettings: ChartSettings['layoutSettings']
  chartActionRequest: ChartActionRequest
  indicatorSettings: IndicatorSettings
  indicatorsVisible: boolean
  drawingToolSettings: ChartSettings['drawingToolSettings']
  drawingClearRequest: number
  drawingsVisible: boolean
  tradeMarkers: ChartTradeMarker[]
  onDrawingComplete: () => void
  onCandlePriceSelect?: (price: number) => void
  fullscreenActive: boolean
}

export type ChartActionRequest = {
  exportImage: number
  scrollToRealtime: number
  scrollToTimestamp: { id: number; timestamp: number } | null
}

type KLineChart = NonNullable<ReturnType<typeof init>>
type DrawingOverlayEvent = Parameters<Parameters<KLineChart['createOverlay']>[0] extends infer Overlay
  ? Overlay extends { onSelected?: infer Callback }
    ? NonNullable<Callback>
    : never
  : never>[0]
type DrawingOverlayRecord = {
  id?: string
  name?: string
  groupId?: string
  points?: Array<{ timestamp?: number; dataIndex?: number; value?: number }>
  lock?: boolean
  styles?: Record<string, unknown> | null
  extendData?: unknown
  visible?: boolean
}
type SelectedDrawingOverlay = {
  id: string
  color: string
  lineSize: number
  locked: boolean
}
type CrosshairCandle = {
  timestamp: number
  open: number
  high: number
  low: number
  close: number
}
type ChartImagePreview = {
  imageUrl: string
  symbol: string
}
type ChartExportContext = {
  symbol: string
  themeMode: 'dark' | 'light'
  backgroundColor: string
}

const drawingOverlayGroupId = 'trading-page-drawings'
const drawingOverlayDefaultColor = chartTheme.palette.accent
const drawingOverlayColors = chartTheme.drawingOverlayColors
const drawingOverlayLineSizes = [1, 2, 3] as const

export const KLineChartPanel = memo(function KLineChartPanel({
  symbol,
  token,
  themeMode,
  period,
  timezone,
  chartType,
  candleStyle,
  axisSettings,
  layoutSettings,
  chartActionRequest,
  indicatorSettings,
  indicatorsVisible,
  drawingToolSettings,
  drawingClearRequest,
  drawingsVisible,
  tradeMarkers,
  onDrawingComplete,
  onCandlePriceSelect,
  fullscreenActive
}: Props) {
  const { t, i18n } = useTranslation()
  const containerRef = useRef<HTMLDivElement | null>(null)
  const chartRef = useRef<ReturnType<typeof init>>(null)
  const initialThemeModeRef = useRef(themeMode)
  const activeIndicatorsRef = useRef<string[]>([])
  const realtimeBarCallbackRef = useRef<((data: TradingCandle) => void) | null>(null)
  const latestRealtimeBarRef = useRef<TradingCandle | null>(null)
  const hasHistoricalCandlesRef = useRef(false)
  const visibleRangeRef = useRef<ReturnType<KLineChart['getVisibleRange']> | null>(null)
  const [lastClose, setLastClose] = useState<number | null>(null)
  const [hasNoCandles, setHasNoCandles] = useState(false)
  const [tradeMarkerOverlayCount, setTradeMarkerOverlayCount] = useState(0)
  const [selectedDrawing, setSelectedDrawing] = useState<SelectedDrawingOverlay | null>(null)
  const [drawingManagerOpen, setDrawingManagerOpen] = useState(false)
  const [drawingRecords, setDrawingRecords] = useState<PersistedChartDrawing[]>([])
  const [crosshairCandle, setCrosshairCandle] = useState<CrosshairCandle | null>(null)
  const [chartImagePreview, setChartImagePreview] = useState<ChartImagePreview | null>(null)
  const chartExportContextRef = useRef<ChartExportContext>({
    symbol,
    themeMode,
    backgroundColor: layoutSettings.background.color
  })
  chartExportContextRef.current = {
    symbol,
    themeMode,
    backgroundColor: layoutSettings.background.color
  }

  const syncPersistedDrawings = useCallback(() => {
    const chart = chartRef.current
    if (!chart) return
    savePersistedChartDrawings(symbol, chart.getOverlays({ groupId: drawingOverlayGroupId }))
    setDrawingRecords(loadPersistedChartDrawings(symbol))
  }, [symbol])

  const handleOverlaySelected = useCallback((event: DrawingOverlayEvent) => {
    const selected = createSelectedDrawingOverlay(event.overlay)
    if (selected) setSelectedDrawing(selected)
  }, [])

  const handleOverlayColorChange = useCallback(
    (color: string) => {
      if (!selectedDrawing) return
      chartRef.current?.overrideOverlay({ id: selectedDrawing.id, styles: buildDrawingOverlayStyles(color, selectedDrawing.lineSize) })
      setSelectedDrawing({ ...selectedDrawing, color })
      syncPersistedDrawings()
    },
    [selectedDrawing, syncPersistedDrawings]
  )

  const handleOverlayLineSizeChange = useCallback(
    (lineSize: number) => {
      if (!selectedDrawing) return
      chartRef.current?.overrideOverlay({ id: selectedDrawing.id, styles: buildDrawingOverlayStyles(selectedDrawing.color, lineSize) })
      setSelectedDrawing({ ...selectedDrawing, lineSize })
      syncPersistedDrawings()
    },
    [selectedDrawing, syncPersistedDrawings]
  )

  const handleOverlayLockToggle = useCallback(() => {
    if (!selectedDrawing) return
    const nextLocked = !selectedDrawing.locked
    chartRef.current?.overrideOverlay({ id: selectedDrawing.id, lock: nextLocked })
    setSelectedDrawing({ ...selectedDrawing, locked: nextLocked })
    syncPersistedDrawings()
  }, [selectedDrawing, syncPersistedDrawings])

  const handleRemoveDrawing = useCallback((id: string) => {
    chartRef.current?.removeOverlay({ id })
    setSelectedDrawing(null)
    syncPersistedDrawings()
  }, [syncPersistedDrawings])

  const handleOverlayRemove = useCallback(() => {
    if (!selectedDrawing) return
    handleRemoveDrawing(selectedDrawing.id)
  }, [handleRemoveDrawing, selectedDrawing])

  const handleCopyDrawing = useCallback((drawing: PersistedChartDrawing) => {
    const chart = chartRef.current
    if (!chart) return
    chart.createOverlay(createDrawingOverlay(clonePersistedDrawing(drawing), drawingsVisible, handleOverlaySelected, syncPersistedDrawings))
    syncPersistedDrawings()
  }, [drawingsVisible, handleOverlaySelected, syncPersistedDrawings])

  const handleLockAllDrawings = useCallback(() => {
    chartRef.current?.overrideOverlay({ groupId: drawingOverlayGroupId, lock: true })
    setSelectedDrawing((drawing) => drawing ? { ...drawing, locked: true } : drawing)
    syncPersistedDrawings()
  }, [syncPersistedDrawings])

  const handleApplyRiskTemplate = useCallback(() => {
    const chart = chartRef.current
    const price = lastClose ?? latestRealtimeBarRef.current?.close ?? null
    if (!chart || price === null) return
    const overlays = buildRiskTemplateDrawings(price).map((drawing) =>
      createDrawingOverlay(drawing, drawingsVisible, handleOverlaySelected, syncPersistedDrawings)
    )
    if (overlays.length > 0) chart.createOverlay(overlays)
    syncPersistedDrawings()
  }, [drawingsVisible, handleOverlaySelected, lastClose, syncPersistedDrawings])

  const handleDrawingOverlayDrawEnd = useCallback(() => {
    syncPersistedDrawings()
    onDrawingComplete()
  }, [onDrawingComplete, syncPersistedDrawings])

  useEffect(() => {
    const container = containerRef.current
    if (!container) return

    registerWeightedMovingAverageIndicator()
    registerTradingGeneratedIndicators()
    registerTradingDrawingOverlays()
    const chart = init(container)
    chartRef.current = chart
    chart?.setStyles(initialThemeModeRef.current)
    chart?.setStyles(chartTheme.terminal[initialThemeModeRef.current])

    return () => {
      dispose(container)
      chartRef.current = null
    }
  }, [])

  useEffect(() => {
    chartRef.current?.resize()
  }, [fullscreenActive])

  useEffect(() => {
    const chart = chartRef.current
    if (!chart) return
    chart.setLocale(normalizeChartLocale(i18n.language))
    chart.setTimezone(resolveChartTimezone(timezone))
    chart.setThousandsSeparator({
      sign: ',',
      format: formatThousandsSeparated
    })
    chart.setDecimalFold({
      threshold: 1_000_000,
      format: formatDecimalFold
    })
    chart.setFormatter({
      formatDate: formatChartDate,
      formatBigNumber: formatChartBigNumber
    })
  }, [i18n.language, timezone])

  useEffect(() => {
    const chart = chartRef.current
    if (!chart) return
    chart.setStyles(themeMode)
    chart.setStyles(chartTheme.terminal[themeMode])
    chart.setStyles(getChartVisualStyles({
      chartType,
      candleStyle,
      axisSettings,
      layoutSettings
    }))
    chart.setBarSpace(axisSettings.barSpace)
    chart.overrideYAxis({ name: axisSettings.priceScaleMode })
  }, [axisSettings, candleStyle, chartType, layoutSettings, themeMode])

  useEffect(() => {
    if (chartActionRequest.scrollToRealtime <= 0) return
    const chart = chartRef.current
    if (!chart) return
    chart.scrollToRealTime(160)
  }, [chartActionRequest.scrollToRealtime])

  useEffect(() => {
    if (!chartActionRequest.scrollToTimestamp) return
    const chart = chartRef.current
    if (!chart) return
    chart.scrollToTimestamp(chartActionRequest.scrollToTimestamp.timestamp, 160)
  }, [chartActionRequest.scrollToTimestamp])

  useEffect(() => {
    if (chartActionRequest.exportImage <= 0) return
    const chart = chartRef.current
    if (!chart) return
    const {
      symbol: exportSymbol,
      themeMode: exportThemeMode,
      backgroundColor: exportBackgroundColor
    } = chartExportContextRef.current
    const imageBackgroundColor = exportBackgroundColor === defaultChartSettings.layoutSettings.background.color
      ? chartTheme.terminalImageBackground[exportThemeMode]
      : exportBackgroundColor
    const imageUrl = chart.getConvertPictureUrl(true, 'png', imageBackgroundColor)
    setChartImagePreview({ imageUrl, symbol: exportSymbol })
  }, [chartActionRequest.exportImage])

  useEffect(() => {
    const chart = chartRef.current
    if (!chart) return
    let disposed = false

    chart.setSymbol({
      ticker: symbol,
      name: symbol,
      pricePrecision: getPricePrecision(symbol),
      volumePrecision: 2
    })
    chart.setPeriod(toKLinePeriod(period))
    latestRealtimeBarRef.current = null
    hasHistoricalCandlesRef.current = false
    setLastClose(null)
    setHasNoCandles(false)
    chart.setDataLoader({
      getBars: ({ type, timestamp, callback }) => {
        if (type === 'backward') {
          callback([], { forward: false, backward: false })
          return
        }

        void loadChartCandles(symbol, period, undefined, resolveHistoricalCandleEndTime(type, timestamp), {
          count: historicalCandleBatchSize
        })
          .then((candles) => {
            if (disposed) return
            if (candles.length === 0) {
              if (type === 'init') {
                latestRealtimeBarRef.current = null
                hasHistoricalCandlesRef.current = false
                setLastClose(null)
                setHasNoCandles(true)
              }
              callback([], { forward: false, backward: false })
              return
            }
            if (type === 'init') {
              latestRealtimeBarRef.current = candles.at(-1) ?? null
              hasHistoricalCandlesRef.current = true
              setLastClose(latestRealtimeBarRef.current?.close ?? null)
              setHasNoCandles(false)
            }
            callback(candles, { forward: hasMoreHistoricalCandles(candles), backward: false })
          })
          .catch(() => {
            if (disposed) return
            if (type === 'init') {
              latestRealtimeBarRef.current = null
              hasHistoricalCandlesRef.current = false
              setLastClose(null)
              setHasNoCandles(true)
            }
            callback([], { forward: false, backward: false })
          })
      },
      subscribeBar: ({ callback }) => {
        realtimeBarCallbackRef.current = callback
      },
      unsubscribeBar: () => {
        realtimeBarCallbackRef.current = null
      }
    })
    chart.resetData()

    return () => {
      disposed = true
    }
  }, [period, symbol])

  useEffect(() => {
    const chart = chartRef.current
    if (!chart) return

    const drawings = loadPersistedChartDrawings(symbol)
    setDrawingRecords(drawings)
    chart.removeOverlay({ groupId: drawingOverlayGroupId })
    if (drawings.length > 0) {
      chart.createOverlay(
        drawings.map((drawing) => createDrawingOverlay(drawing, drawingsVisible, handleOverlaySelected, syncPersistedDrawings))
      )
    }
  }, [drawingsVisible, handleOverlaySelected, symbol, syncPersistedDrawings])

  useEffect(() => {
    const chart = chartRef.current
    if (!chart) return

    const handleCrosshairChange = (payload?: unknown) => {
      setCrosshairCandle(createCrosshairCandle(payload))
    }
    const handleCandleBarClick = (payload?: unknown) => {
      const price = getActionClosePrice(payload)
      if (price !== null) onCandlePriceSelect?.(price)
    }
    const handleVisibleRangeChange = () => {
      visibleRangeRef.current = chart.getVisibleRange()
    }

    chart.subscribeAction('onCrosshairChange', handleCrosshairChange)
    chart.subscribeAction('onCandleBarClick', handleCandleBarClick)
    chart.subscribeAction('onVisibleRangeChange', handleVisibleRangeChange)

    return () => {
      chart.unsubscribeAction('onCrosshairChange', handleCrosshairChange)
      chart.unsubscribeAction('onCandleBarClick', handleCandleBarClick)
      chart.unsubscribeAction('onVisibleRangeChange', handleVisibleRangeChange)
    }
  }, [onCandlePriceSelect])

  useEffect(() => {
    const chart = chartRef.current
    if (!chart) {
      setTradeMarkerOverlayCount(0)
      return
    }

    chart.removeOverlay({ groupId: tradeMarkerOverlayGroupId })
    const overlays = buildTradeMarkerOverlays(tradeMarkers, period)
    setTradeMarkerOverlayCount(overlays.length)
    if (overlays.length > 0) chart.createOverlay(overlays)
  }, [period, tradeMarkers])

  useEffect(() => {
    return subscribeQuote<BackendQuote>(symbol, token, (quote) => {
      const callback = realtimeBarCallbackRef.current
      const nextBar = applyAuthoritativeRealtimeQuoteToChart(
        latestRealtimeBarRef.current,
        period,
        quote
      )
      if (!callback || !nextBar) return
      latestRealtimeBarRef.current = nextBar
      callback(nextBar)
      if (hasHistoricalCandlesRef.current) {
        setHasNoCandles(false)
      }
      setLastClose(nextBar.close)
    })
  }, [period, symbol, token])

  useEffect(() => {
    const chart = chartRef.current
    if (!chart) return

    activeIndicatorsRef.current.forEach((name) => chart.removeIndicator({ name }))
    activeIndicatorsRef.current = []
    if (!indicatorsVisible) return

    const plan = buildIndicatorApplyPlan(indicatorSettings)
    const descriptors = [
      ...(plan.volume ? [plan.volume] : []),
      ...plan.mainIndicators,
      ...plan.secondaryIndicators
    ]
    descriptors.forEach((descriptor) => createChartIndicator(chart, descriptor))
    activeIndicatorsRef.current = descriptors.map((descriptor) => descriptor.name)
  }, [indicatorSettings, indicatorsVisible])

  useEffect(() => {
    const chart = chartRef.current
    if (!chart) return

    const overlayName = drawingToolToOverlayName(drawingToolSettings.activeTool)
    if (!overlayName) return

    const id = chart.createOverlay({
      name: overlayName,
      groupId: drawingOverlayGroupId,
      mode: drawingMagnetModeToOverlayMode(drawingToolSettings.magnetMode),
      modeSensitivity: 8,
      visible: drawingsVisible,
      extendData: drawingToolSettings.activeTool === 'text' ? t('trading.chartAnnotation') : undefined,
      onDrawEnd: handleDrawingOverlayDrawEnd,
      onSelected: handleOverlaySelected,
      onClick: handleOverlaySelected,
      onDeselected: () => setSelectedDrawing(null)
    })
    if (id === null) onDrawingComplete()
  }, [drawingToolSettings.activeTool, drawingToolSettings.magnetMode, drawingsVisible, handleDrawingOverlayDrawEnd, handleOverlaySelected, onDrawingComplete, t])

  useEffect(() => {
    if (drawingClearRequest <= 0) return
    chartRef.current?.removeOverlay({ groupId: drawingOverlayGroupId })
    savePersistedChartDrawings(symbol, [])
    setDrawingRecords([])
    setSelectedDrawing(null)
  }, [drawingClearRequest, symbol])

  useEffect(() => {
    chartRef.current?.overrideOverlay({ groupId: drawingOverlayGroupId, visible: drawingsVisible })
    if (!drawingsVisible) setSelectedDrawing(null)
  }, [drawingsVisible])

  useResizeObserver(containerRef, () => chartRef.current?.resize())

  return (
    <div
      className={styles.chartPanel}
      data-trade-marker-count={tradeMarkers.length}
      data-trade-marker-overlay-count={tradeMarkerOverlayCount}
    >
      <div className={styles.canvasHeader}>
        <span>{symbol}</span>
        {crosshairCandle ? (
          <div className={styles.ohlcPanel}>
            <span>O {formatMarketPrice(symbol, crosshairCandle.open)}</span>
            <span>H {formatMarketPrice(symbol, crosshairCandle.high)}</span>
            <span>L {formatMarketPrice(symbol, crosshairCandle.low)}</span>
            <span>C {formatMarketPrice(symbol, crosshairCandle.close)}</span>
          </div>
        ) : null}
        <strong>{lastClose === null ? '--' : formatMarketPrice(symbol, lastClose)}</strong>
        <button
          type="button"
          className={styles.headerIconButton}
          title={t('chart.drawingManager')}
          aria-label={t('chart.drawingManager')}
          aria-expanded={drawingManagerOpen}
          onClick={() => setDrawingManagerOpen((open) => !open)}
        >
          <ListTree size={15} />
        </button>
      </div>
      <div className={styles.canvasWrap}>
        <div ref={containerRef} className={styles.klineCanvas} />
        {hasNoCandles ? (
          <div className={styles.emptyState} role="status">
            {t('common.empty')}
          </div>
        ) : null}
        {selectedDrawing && drawingsVisible ? (
          <ChartOverlayEditToolbar
            selectedDrawing={selectedDrawing}
            onColorChange={handleOverlayColorChange}
            onLineSizeChange={handleOverlayLineSizeChange}
            onRemove={handleOverlayRemove}
            onToggleLock={handleOverlayLockToggle}
            onOpenManager={() => setDrawingManagerOpen((open) => !open)}
          />
        ) : null}
        {drawingManagerOpen && drawingsVisible ? (
          <ChartDrawingManager
            drawings={drawingRecords}
            onApplyRiskTemplate={handleApplyRiskTemplate}
            onCopyDrawing={handleCopyDrawing}
            onLockAll={handleLockAllDrawings}
            onRemoveDrawing={handleRemoveDrawing}
          />
        ) : null}
      </div>
      {chartImagePreview ? (
        <div
          className={styles.imagePreviewBackdrop}
          onMouseDown={(event) => {
            if (event.target === event.currentTarget) setChartImagePreview(null)
          }}
        >
          <section
            className={styles.imagePreviewDialog}
            role="dialog"
            aria-modal="true"
            aria-labelledby="chart-image-preview-title"
            data-shortcut-disabled="true"
          >
            <div className={styles.imagePreviewHeader}>
              <div>
                <strong id="chart-image-preview-title">{t('chart.imagePreviewTitle')}</strong>
                <span>{chartImagePreview.symbol}</span>
              </div>
              <button
                type="button"
                className={styles.imagePreviewCloseButton}
                title={t('chart.closeImagePreview')}
                aria-label={t('chart.closeImagePreview')}
                onClick={() => setChartImagePreview(null)}
              >
                <X size={16} />
              </button>
            </div>
            <div className={styles.imagePreviewFrame}>
              <img
                src={chartImagePreview.imageUrl}
                alt={t('chart.imagePreviewAlt', { symbol: chartImagePreview.symbol })}
              />
            </div>
            <div className={styles.imagePreviewActions}>
              <button type="button" className={styles.imagePreviewSecondaryButton} onClick={() => setChartImagePreview(null)}>
                {t('common.cancel')}
              </button>
              <button
                type="button"
                className={styles.imagePreviewPrimaryButton}
                onClick={() => downloadChartImage(chartImagePreview.imageUrl, chartImagePreview.symbol)}
              >
                <Download size={15} />
                {t('common.save')}
              </button>
            </div>
          </section>
        </div>
      ) : null}
    </div>
  )
})

type ChartOverlayEditToolbarProps = {
  selectedDrawing: SelectedDrawingOverlay
  onColorChange: (color: string) => void
  onLineSizeChange: (lineSize: number) => void
  onRemove: () => void
  onToggleLock: () => void
  onOpenManager: () => void
}

function ChartOverlayEditToolbar({
  selectedDrawing,
  onColorChange,
  onLineSizeChange,
  onRemove,
  onToggleLock,
  onOpenManager
}: ChartOverlayEditToolbarProps) {
  const { t } = useTranslation()

  return (
    <div
      className={styles.overlayEditToolbar}
      aria-label={t('trading.drawingEdit')}
      data-shortcut-disabled="true"
      onMouseDown={(event) => event.stopPropagation()}
      role="toolbar"
    >
      <GripVertical className={styles.toolbarGrip} size={15} aria-hidden="true" />
      <span className={styles.toolbarDivider} />
      <Paintbrush size={15} aria-hidden="true" />
      <div className={styles.colorSwatches} aria-label={t('trading.color')}>
        {drawingOverlayColors.map((color) => (
          <button
            key={color}
            type="button"
            className={color === selectedDrawing.color ? `${styles.colorSwatch} ${styles.colorSwatchActive}` : styles.colorSwatch}
            style={{ backgroundColor: color }}
            title={color}
            aria-label={t('trading.colorValue', { color })}
            aria-pressed={color === selectedDrawing.color}
            onClick={() => onColorChange(color)}
          />
        ))}
      </div>
      <span className={styles.toolbarDivider} />
      <div className={styles.lineWidthGroup} aria-label={t('trading.lineWidth')}>
        {drawingOverlayLineSizes.map((lineSize) => (
          <button
            key={lineSize}
            type="button"
            className={lineSize === selectedDrawing.lineSize ? `${styles.lineWidthButton} ${styles.lineWidthButtonActive}` : styles.lineWidthButton}
            title={`${lineSize}px`}
            aria-label={`${lineSize}px`}
            aria-pressed={lineSize === selectedDrawing.lineSize}
            onClick={() => onLineSizeChange(lineSize)}
          >
            <span className={styles.lineWidthPreview} style={{ height: lineSize }} />
          </button>
        ))}
        <span className={styles.lineSizeLabel}>{selectedDrawing.lineSize}px</span>
      </div>
      <span className={styles.toolbarDivider} />
      <button
        type="button"
        className={selectedDrawing.locked ? `${styles.iconButton} ${styles.lockButtonActive}` : styles.iconButton}
        title={selectedDrawing.locked ? t('trading.unlock') : t('trading.lock')}
        aria-label={selectedDrawing.locked ? t('trading.unlock') : t('trading.lock')}
        aria-pressed={selectedDrawing.locked}
        onClick={onToggleLock}
      >
        {selectedDrawing.locked ? <Lock size={15} /> : <Unlock size={15} />}
      </button>
      <button type="button" className={styles.iconButton} title={t('common.delete')} aria-label={t('common.delete')} onClick={onRemove}>
        <Trash2 size={15} />
      </button>
      <button type="button" className={styles.iconButton} title={t('common.more')} aria-label={t('common.more')} onClick={onOpenManager}>
        <MoreHorizontal size={16} />
      </button>
    </div>
  )
}

type ChartDrawingManagerProps = {
  drawings: PersistedChartDrawing[]
  onApplyRiskTemplate: () => void
  onCopyDrawing: (drawing: PersistedChartDrawing) => void
  onLockAll: () => void
  onRemoveDrawing: (id: string) => void
}

function ChartDrawingManager({
  drawings,
  onApplyRiskTemplate,
  onCopyDrawing,
  onLockAll,
  onRemoveDrawing
}: ChartDrawingManagerProps) {
  const { t } = useTranslation()

  return (
    <div className={styles.drawingManager} data-shortcut-disabled="true">
      <div className={styles.drawingManagerHeader}>
        <strong>{t('chart.drawingManager')}</strong>
        <div>
          <button type="button" title={t('chart.lockAllDrawings')} aria-label={t('chart.lockAllDrawings')} onClick={onLockAll}>
            <Lock size={14} />
          </button>
          <button type="button" title={t('chart.applyRiskTemplate')} aria-label={t('chart.applyRiskTemplate')} onClick={onApplyRiskTemplate}>
            <Layers size={14} />
          </button>
        </div>
      </div>
      {drawings.length === 0 ? (
        <p>{t('common.empty')}</p>
      ) : (
        <ul>
          {drawings.map((drawing, index) => (
            <li key={drawing.id ?? `${drawing.name}-${index}`}>
              <span>{drawing.extendData == null ? drawing.name : String(drawing.extendData)}</span>
              <button type="button" title={t('common.copy')} aria-label={t('common.copy')} onClick={() => onCopyDrawing(drawing)}>
                <Copy size={14} />
              </button>
              <button
                type="button"
                title={t('common.delete')}
                aria-label={t('common.delete')}
                disabled={!drawing.id}
                onClick={() => drawing.id && onRemoveDrawing(drawing.id)}
              >
                <Trash2 size={14} />
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

function drawingMagnetModeToOverlayMode(mode: ChartSettings['drawingToolSettings']['magnetMode']) {
  if (mode === 'weak') return 'weak_magnet'
  if (mode === 'strong') return 'strong_magnet'
  return 'normal'
}

function createDrawingOverlay(
  drawing: PersistedChartDrawing,
  visible: boolean,
  onSelected: (event: DrawingOverlayEvent) => void,
  onChanged: () => void
) {
  const overlay = {
    ...drawing,
    styles: drawing.styles ?? undefined,
    groupId: drawingOverlayGroupId,
    visible,
    onDrawEnd: onChanged,
    onSelected,
    onClick: onSelected,
    onDeselected: () => undefined
  }
  return overlay
}

function createSelectedDrawingOverlay(overlay: DrawingOverlayRecord): SelectedDrawingOverlay | null {
  if (!overlay.id) return null
  return {
    id: overlay.id,
    color: getOverlayStyleColor(overlay.styles),
    lineSize: getOverlayLineSize(overlay.styles),
    locked: overlay.lock === true
  }
}

function createCrosshairCandle(payload: unknown): CrosshairCandle | null {
  const kLineData = getActionKLineData(payload)
  if (!kLineData) return null
  return {
    timestamp: kLineData.timestamp,
    open: kLineData.open,
    high: kLineData.high,
    low: kLineData.low,
    close: kLineData.close
  }
}

function getActionClosePrice(payload: unknown) {
  const kLineData = getActionKLineData(payload)
  return typeof kLineData?.close === 'number' && Number.isFinite(kLineData.close) && kLineData.close > 0
    ? kLineData.close
    : null
}

function getActionKLineData(payload: unknown): TradingCandle | null {
  if (!isRecord(payload)) return null
  const directData = payload.kLineData
  if (isTradingCandle(directData)) return directData
  const data = payload.data
  if (isRecord(data) && isTradingCandle(data.current)) return data.current
  return null
}

function isTradingCandle(value: unknown): value is TradingCandle {
  if (!isRecord(value)) return false
  return isFiniteNumber(value.timestamp) &&
    isFiniteNumber(value.open) &&
    isFiniteNumber(value.high) &&
    isFiniteNumber(value.low) &&
    isFiniteNumber(value.close)
}

function buildDrawingOverlayStyles(color: string, lineSize: number) {
  const fillColor = chartColorWithAlpha(color, 0.14)
  return {
    point: {
      color,
      borderColor: chartColorWithAlpha(color, 0.36),
      activeColor: color,
      activeBorderColor: chartColorWithAlpha(color, 0.46)
    },
    line: {
      color,
      size: lineSize
    },
    rect: {
      style: 'stroke_fill',
      color: fillColor,
      borderColor: color,
      borderSize: lineSize
    },
    polygon: {
      style: 'stroke_fill',
      color: fillColor,
      borderColor: color,
      borderSize: lineSize
    },
    circle: {
      style: 'stroke_fill',
      color: fillColor,
      borderColor: color,
      borderSize: lineSize
    },
    arc: {
      color,
      size: lineSize
    },
    text: {
      color: chartTheme.palette.ink,
      backgroundColor: color,
      borderColor: color,
      borderSize: lineSize
    }
  }
}

function getOverlayStyleColor(styles: Record<string, unknown> | null | undefined) {
  const lineColor = getNestedStyleValue(styles, 'line', 'color')
  const rectBorderColor = getNestedStyleValue(styles, 'rect', 'borderColor')
  const polygonBorderColor = getNestedStyleValue(styles, 'polygon', 'borderColor')
  const circleBorderColor = getNestedStyleValue(styles, 'circle', 'borderColor')
  const textBackgroundColor = getNestedStyleValue(styles, 'text', 'backgroundColor')
  return [lineColor, rectBorderColor, polygonBorderColor, circleBorderColor, textBackgroundColor].find(isHexColor) ?? drawingOverlayDefaultColor
}

function getOverlayLineSize(styles: Record<string, unknown> | null | undefined) {
  const lineSize = getNestedStyleValue(styles, 'line', 'size')
  const rectBorderSize = getNestedStyleValue(styles, 'rect', 'borderSize')
  const polygonBorderSize = getNestedStyleValue(styles, 'polygon', 'borderSize')
  const circleBorderSize = getNestedStyleValue(styles, 'circle', 'borderSize')
  const size = [lineSize, rectBorderSize, polygonBorderSize, circleBorderSize].find(isValidLineSize)
  return size ?? 1
}

function getNestedStyleValue(styles: Record<string, unknown> | null | undefined, group: string, key: string) {
  const value = styles?.[group]
  return isRecord(value) ? value[key] : undefined
}

function isHexColor(value: unknown): value is string {
  return typeof value === 'string' && /^#[\da-f]{6}$/i.test(value)
}

function isValidLineSize(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0
}

function isFiniteNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value)
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function normalizeChartLocale(language: string) {
  if (language.toLowerCase().startsWith('zh')) return 'zh-CN'
  if (language.toLowerCase().startsWith('ja')) return 'ja-JP'
  return 'en-US'
}

function getLocalTimezone() {
  return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
}

function resolveChartTimezone(timezone: ChartSettings['timezone']) {
  return timezone === 'local' ? getLocalTimezone() : timezone
}

function formatChartDate({ dateTimeFormat, timestamp }: { dateTimeFormat: Intl.DateTimeFormat; timestamp: number }) {
  return dateTimeFormat.format(new Date(timestamp))
}

function formatChartBigNumber(value: string | number) {
  const numberValue = Number(value)
  if (!Number.isFinite(numberValue)) return String(value)
  if (Math.abs(numberValue) >= 1_000_000_000) return `${formatCompactNumber(numberValue / 1_000_000_000)}B`
  if (Math.abs(numberValue) >= 1_000_000) return `${formatCompactNumber(numberValue / 1_000_000)}M`
  if (Math.abs(numberValue) >= 1_000) return `${formatCompactNumber(numberValue / 1_000)}K`
  return formatThousandsSeparated(value)
}

function formatThousandsSeparated(value: string | number) {
  const [integerPart, decimalPart] = String(value).split('.')
  const formattedInteger = integerPart.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  return decimalPart === undefined ? formattedInteger : `${formattedInteger}.${decimalPart}`
}

function formatDecimalFold(value: string | number) {
  const numberValue = Number(value)
  if (!Number.isFinite(numberValue) || Math.abs(numberValue) < 1_000_000) return String(value)
  return formatChartBigNumber(numberValue)
}

function formatCompactNumber(value: number) {
  return Number.isInteger(value) ? String(value) : value.toFixed(2).replace(/\.?0+$/, '')
}

function downloadChartImage(imageUrl: string, symbol: string) {
  if (typeof document === 'undefined') return
  const link = document.createElement('a')
  link.href = imageUrl
  link.download = `${symbol.toLowerCase()}-chart.png`
  document.body.appendChild(link)
  link.click()
  link.remove()
}

function createChartIndicator(chart: KLineChart, descriptor: IndicatorApplyDescriptor) {
  const value =
    descriptor.calcParams || descriptor.styles
      ? {
          name: descriptor.name,
          calcParams: descriptor.calcParams,
          styles: descriptor.styles
        }
      : descriptor.name

  chart.createIndicator(value, {
    isStack: descriptor.stackOnCandle,
    pane: {
      id: descriptor.paneId,
      height: descriptor.paneHeight,
      minHeight: descriptor.paneHeight ? Math.min(82, descriptor.paneHeight) : undefined
    }
  })
}

const periodMilliseconds: Record<TradingPeriod, number> = {
  time: 60_000,
  '1s': 1_000,
  '1m': 60_000,
  '3m': 180_000,
  '5m': 300_000,
  '15m': 900_000,
  '30m': 1_800_000,
  '1h': 3_600_000,
  '2h': 7_200_000,
  '4h': 14_400_000,
  '6h': 21_600_000,
  '12h': 43_200_000,
  '1d': 86_400_000,
  '2d': 172_800_000,
  '3d': 259_200_000,
  '5d': 432_000_000,
  '1w': 604_800_000,
  '1M': 2_592_000_000,
  '3M': 7_776_000_000
}

export function applyRealtimeQuoteToChart(
  previous: TradingCandle | null,
  period: TradingPeriod,
  timestamp: number,
  price: number
): TradingCandle | null {
  if (!Number.isFinite(price) || price <= 0) return null

  const interval = periodMilliseconds[period]
  const bucketTimestamp = Math.floor(timestamp / interval) * interval
  const previousBucketTimestamp = previous ? Math.floor(previous.timestamp / interval) * interval : null

  if (previous && previousBucketTimestamp === bucketTimestamp) {
    return {
      ...previous,
      timestamp: bucketTimestamp,
      high: Math.max(previous.high, price),
      low: Math.min(previous.low, price),
      close: price,
      turnover: price * previous.volume
    }
  }

  const open = previous?.close ?? price
  return {
    timestamp: bucketTimestamp,
    open,
    high: Math.max(open, price),
    low: Math.min(open, price),
    close: price,
    volume: 0,
    turnover: 0
  }
}

export function applyAuthoritativeRealtimeQuoteToChart(
  previous: TradingCandle | null,
  period: TradingPeriod,
  quote: BackendQuote,
  now = Date.now()
): TradingCandle | null {
  const tradingQuote = mapQuoteToTradingQuote(quote, undefined, now)
  if (!tradingQuote.marketSource || tradingQuote.tradable !== true) return null
  return applyRealtimeQuoteToChart(previous, period, tradingQuote.timestamp, tradingQuote.mid)
}
