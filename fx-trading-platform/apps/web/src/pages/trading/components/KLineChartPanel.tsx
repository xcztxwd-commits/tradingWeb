import { dispose, init } from 'klinecharts'
import { GripVertical, Lock, MoreHorizontal, Paintbrush, Trash2, Unlock } from 'lucide-react'
import { memo, useCallback, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'

import { buildIndicatorApplyPlan, drawingToolToOverlayName, getChartTypeStyles } from '../chartSettings'
import type { ChartSettings, ChartType, IndicatorApplyDescriptor, IndicatorSettings } from '../chartSettings'
import { loadChartCandles } from '../chartCandleData'
import { formatMarketPrice, getPricePrecision, toKLinePeriod } from '../../../features/market/tradingModels'
import { registerTradingGeneratedIndicators } from '../generatedTradingIndicators'
import { registerTradingDrawingOverlays } from '../tradingDrawingOverlays'
import type { TradingCandle, TradingPeriod } from '../../../features/market/tradingModels'
import { registerWeightedMovingAverageIndicator } from '../weightedMovingAverageIndicator'
import { useResizeObserver } from '../../../hooks/useResizeObserver'
import { subscribeQuote } from '../../../services/marketStream'
import styles from './KLineChartPanel.module.css'

type Props = {
  symbol: string
  token: string | null
  themeMode: 'dark' | 'light'
  period: TradingPeriod
  chartType: ChartType
  indicatorSettings: IndicatorSettings
  indicatorsVisible: boolean
  drawingToolSettings: ChartSettings['drawingToolSettings']
  drawingClearRequest: number
  drawingsVisible: boolean
  allowMockFallback?: boolean
  onDrawingComplete: () => void
  fullscreenActive: boolean
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
  lock?: boolean
  styles?: Record<string, unknown> | null
}
type SelectedDrawingOverlay = {
  id: string
  color: string
  lineSize: number
  locked: boolean
}

const drawingOverlayGroupId = 'trading-page-drawings'
const drawingOverlayDefaultColor = '#f2b84b'
const drawingOverlayColors = ['#f2b84b', '#ffffff', '#26a69a', '#ef5350', '#4dd0e1']
const drawingOverlayLineSizes = [1, 2, 3] as const

export const KLineChartPanel = memo(function KLineChartPanel({
  symbol,
  token,
  themeMode,
  period,
  chartType,
  indicatorSettings,
  indicatorsVisible,
  drawingToolSettings,
  drawingClearRequest,
  drawingsVisible,
  allowMockFallback = true,
  onDrawingComplete,
  fullscreenActive
}: Props) {
  const { t } = useTranslation()
  const containerRef = useRef<HTMLDivElement | null>(null)
  const chartRef = useRef<ReturnType<typeof init>>(null)
  const initialThemeModeRef = useRef(themeMode)
  const activeIndicatorsRef = useRef<string[]>([])
  const realtimeBarCallbackRef = useRef<((data: TradingCandle) => void) | null>(null)
  const latestRealtimeBarRef = useRef<TradingCandle | null>(null)
  const hasHistoricalCandlesRef = useRef(false)
  const [lastClose, setLastClose] = useState<number | null>(null)
  const [hasNoCandles, setHasNoCandles] = useState(false)
  const [selectedDrawing, setSelectedDrawing] = useState<SelectedDrawingOverlay | null>(null)

  const handleOverlaySelected = useCallback((event: DrawingOverlayEvent) => {
    const selected = createSelectedDrawingOverlay(event.overlay)
    if (selected) setSelectedDrawing(selected)
  }, [])

  const handleOverlayColorChange = useCallback(
    (color: string) => {
      if (!selectedDrawing) return
      chartRef.current?.overrideOverlay({ id: selectedDrawing.id, styles: buildDrawingOverlayStyles(color, selectedDrawing.lineSize) })
      setSelectedDrawing({ ...selectedDrawing, color })
    },
    [selectedDrawing]
  )

  const handleOverlayLineSizeChange = useCallback(
    (lineSize: number) => {
      if (!selectedDrawing) return
      chartRef.current?.overrideOverlay({ id: selectedDrawing.id, styles: buildDrawingOverlayStyles(selectedDrawing.color, lineSize) })
      setSelectedDrawing({ ...selectedDrawing, lineSize })
    },
    [selectedDrawing]
  )

  const handleOverlayLockToggle = useCallback(() => {
    if (!selectedDrawing) return
    const nextLocked = !selectedDrawing.locked
    chartRef.current?.overrideOverlay({ id: selectedDrawing.id, lock: nextLocked })
    setSelectedDrawing({ ...selectedDrawing, locked: nextLocked })
  }, [selectedDrawing])

  const handleOverlayRemove = useCallback(() => {
    if (!selectedDrawing) return
    chartRef.current?.removeOverlay({ id: selectedDrawing.id })
    setSelectedDrawing(null)
  }, [selectedDrawing])

  useEffect(() => {
    const container = containerRef.current
    if (!container) return

    registerWeightedMovingAverageIndicator()
    registerTradingGeneratedIndicators()
    registerTradingDrawingOverlays()
    const chart = init(container)
    chartRef.current = chart
    chart?.setStyles(initialThemeModeRef.current)
    chart?.setStyles(terminalChartStyles[initialThemeModeRef.current])

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
    chart.setStyles(themeMode)
    chart.setStyles(terminalChartStyles[themeMode])
  }, [themeMode])

  useEffect(() => {
    chartRef.current?.setStyles(getChartTypeStyles(chartType))
  }, [chartType])

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
      getBars: ({ callback }) => {
        void loadChartCandles(symbol, period, undefined, Date.now(), { allowMockFallback })
          .then((candles) => {
            if (disposed) return
            if (candles.length === 0) {
              latestRealtimeBarRef.current = null
              hasHistoricalCandlesRef.current = false
              setLastClose(null)
              setHasNoCandles(true)
              callback([])
              return
            }
            latestRealtimeBarRef.current = candles.at(-1) ?? null
            hasHistoricalCandlesRef.current = true
            setLastClose(latestRealtimeBarRef.current?.close ?? null)
            setHasNoCandles(false)
            callback(candles)
          })
          .catch(() => {
            if (disposed) return
            latestRealtimeBarRef.current = null
            hasHistoricalCandlesRef.current = false
            setLastClose(null)
            setHasNoCandles(true)
            callback([])
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
  }, [allowMockFallback, period, symbol])

  useEffect(() => {
    return subscribeQuote(symbol, token, (quote) => {
      const callback = realtimeBarCallbackRef.current
      const price = Number(quote.mid)
      const nextBar = applyRealtimeQuoteToChart(latestRealtimeBarRef.current, period, quote.timestamp, price)
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
      onDrawEnd: onDrawingComplete,
      onSelected: handleOverlaySelected,
      onClick: handleOverlaySelected,
      onDeselected: () => setSelectedDrawing(null)
    })
    if (id === null) onDrawingComplete()
  }, [drawingToolSettings.activeTool, drawingToolSettings.magnetMode, drawingsVisible, handleOverlaySelected, onDrawingComplete, t])

  useEffect(() => {
    if (drawingClearRequest <= 0) return
    chartRef.current?.removeOverlay({ groupId: drawingOverlayGroupId })
    setSelectedDrawing(null)
  }, [drawingClearRequest])

  useEffect(() => {
    chartRef.current?.overrideOverlay({ groupId: drawingOverlayGroupId, visible: drawingsVisible })
    if (!drawingsVisible) setSelectedDrawing(null)
  }, [drawingsVisible])

  useResizeObserver(containerRef, () => chartRef.current?.resize())

  return (
    <div className={styles.chartPanel}>
      <div className={styles.canvasHeader}>
        <span>{symbol}</span>
        <strong>{lastClose === null ? '--' : formatMarketPrice(symbol, lastClose)}</strong>
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
          />
        ) : null}
      </div>
    </div>
  )
})

type ChartOverlayEditToolbarProps = {
  selectedDrawing: SelectedDrawingOverlay
  onColorChange: (color: string) => void
  onLineSizeChange: (lineSize: number) => void
  onRemove: () => void
  onToggleLock: () => void
}

function ChartOverlayEditToolbar({
  selectedDrawing,
  onColorChange,
  onLineSizeChange,
  onRemove,
  onToggleLock
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
      <button type="button" className={styles.iconButton} title={t('common.more')} aria-label={t('common.more')}>
        <MoreHorizontal size={16} />
      </button>
    </div>
  )
}

function drawingMagnetModeToOverlayMode(mode: ChartSettings['drawingToolSettings']['magnetMode']) {
  if (mode === 'weak') return 'weak_magnet'
  if (mode === 'strong') return 'strong_magnet'
  return 'normal'
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

function buildDrawingOverlayStyles(color: string, lineSize: number) {
  const fillColor = colorWithAlpha(color, 0.14)
  return {
    point: {
      color,
      borderColor: colorWithAlpha(color, 0.36),
      activeColor: color,
      activeBorderColor: colorWithAlpha(color, 0.46)
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
      color: '#050505',
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

function colorWithAlpha(color: string, alpha: number) {
  if (!isHexColor(color)) return color
  const value = color.slice(1)
  const red = Number.parseInt(value.slice(0, 2), 16)
  const green = Number.parseInt(value.slice(2, 4), 16)
  const blue = Number.parseInt(value.slice(4, 6), 16)
  return `rgba(${red}, ${green}, ${blue}, ${alpha})`
}

function isHexColor(value: unknown): value is string {
  return typeof value === 'string' && /^#[\da-f]{6}$/i.test(value)
}

function isValidLineSize(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
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

const terminalChartStyles = {
  dark: {
    grid: {
      show: true,
      horizontal: { color: '#151d28', size: 1 },
      vertical: { color: '#111923', size: 1 }
    },
    candle: {
      bar: {
        upColor: '#26a69a',
        upBorderColor: '#26a69a',
        upWickColor: '#26a69a',
        downColor: '#ef5350',
        downBorderColor: '#ef5350',
        downWickColor: '#ef5350'
      },
      priceMark: {
        high: { color: '#8c97a6' },
        low: { color: '#8c97a6' },
        last: {
          upColor: '#26a69a',
          downColor: '#ef5350',
          noChangeColor: '#f2b84b'
        }
      }
    },
    xAxis: {
      axisLine: { color: '#1d2530' },
      tickText: { color: '#687483' }
    },
    yAxis: {
      axisLine: { color: '#1d2530' },
      tickText: { color: '#687483' }
    },
    separator: {
      color: '#1d2530',
      size: 1
    },
    crosshair: {
      horizontal: {
        line: { color: '#4d5968' },
        text: { backgroundColor: '#202a36', color: '#dce3ec' }
      },
      vertical: {
        line: { color: '#4d5968' },
        text: { backgroundColor: '#202a36', color: '#dce3ec' }
      }
    },
    overlay: {
      point: {
        color: '#f2b84b',
        borderColor: 'rgba(242, 184, 75, 0.36)',
        activeColor: '#f2b84b',
        activeBorderColor: 'rgba(242, 184, 75, 0.44)'
      },
      line: {
        color: '#f2b84b',
        size: 1,
        style: 'solid'
      },
      rect: {
        color: 'rgba(242, 184, 75, 0.14)',
        borderColor: '#f2b84b',
        borderSize: 1
      },
      polygon: {
        color: 'rgba(242, 184, 75, 0.12)',
        borderColor: '#f2b84b',
        borderSize: 1
      },
      text: {
        color: '#050505',
        backgroundColor: '#f2b84b',
        borderColor: '#f2b84b'
      }
    }
  },
  light: {
    grid: {
      show: true,
      horizontal: { color: '#e6ebf2', size: 1 },
      vertical: { color: '#eef2f6', size: 1 }
    },
    candle: {
      bar: {
        upColor: '#059669',
        upBorderColor: '#059669',
        upWickColor: '#059669',
        downColor: '#dc3f5f',
        downBorderColor: '#dc3f5f',
        downWickColor: '#dc3f5f'
      },
      priceMark: {
        high: { color: '#64748b' },
        low: { color: '#64748b' },
        last: {
          upColor: '#059669',
          downColor: '#dc3f5f',
          noChangeColor: '#0f172a'
        }
      }
    },
    xAxis: {
      axisLine: { color: '#d8dee8' },
      tickText: { color: '#64748b' }
    },
    yAxis: {
      axisLine: { color: '#d8dee8' },
      tickText: { color: '#64748b' }
    },
    separator: {
      color: '#d8dee8',
      size: 1
    },
    crosshair: {
      horizontal: {
        line: { color: '#94a3b8' },
        text: { backgroundColor: '#111827', color: '#ffffff' }
      },
      vertical: {
        line: { color: '#94a3b8' },
        text: { backgroundColor: '#111827', color: '#ffffff' }
      }
    },
    overlay: {
      point: {
        color: '#111827',
        borderColor: 'rgba(17, 24, 39, 0.28)',
        activeColor: '#111827',
        activeBorderColor: 'rgba(17, 24, 39, 0.42)'
      },
      line: {
        color: '#111827',
        size: 1,
        style: 'solid'
      },
      rect: {
        color: 'rgba(17, 24, 39, 0.09)',
        borderColor: '#111827',
        borderSize: 1
      },
      polygon: {
        color: 'rgba(17, 24, 39, 0.08)',
        borderColor: '#111827',
        borderSize: 1
      },
      text: {
        color: '#ffffff',
        backgroundColor: '#111827',
        borderColor: '#111827'
      }
    }
  }
}
