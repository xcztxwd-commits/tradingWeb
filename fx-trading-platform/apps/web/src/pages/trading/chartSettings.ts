import type { TradingPeriod } from '../../features/market/tradingModels'

export type ChartType = 'candle' | 'bar' | 'hlc' | 'line'
export type LineType = 'solid' | 'dashed'
export type GridLineMode = 'none' | 'horizontal' | 'vertical' | 'both'
export type DrawingMagnetMode = 'none' | 'weak' | 'strong'
export type PriceScaleMode = 'normal' | 'percentage' | 'logarithm'
export type ChartTooltipStyle = 'standard' | 'compact' | 'hidden'
export type ChartTimezone =
  | 'local'
  | 'UTC'
  | 'Asia/Shanghai'
  | 'Asia/Singapore'
  | 'Asia/Tokyo'
  | 'Europe/London'
  | 'America/New_York'
export type DrawingTool =
  | 'cursor'
  | 'segment'
  | 'arrowLine'
  | 'trendLine'
  | 'rayLine'
  | 'straightLine'
  | 'horizontalSegment'
  | 'horizontalRayLine'
  | 'horizontalLine'
  | 'priceLine'
  | 'verticalLine'
  | 'verticalSegment'
  | 'verticalRayLine'
  | 'fibonacciExtension'
  | 'fibonacciFan'
  | 'fibonacciLine'
  | 'rectangle'
  | 'brush'
  | 'circle'
  | 'triangle'
  | 'priceChannel'
  | 'parallelLine'
  | 'parallelRayLine'
  | 'priceTag'
  | 'text'
export type DrawingOverlayName =
  | 'segment'
  | 'tradingArrowLine'
  | 'rayLine'
  | 'straightLine'
  | 'horizontalSegment'
  | 'horizontalRayLine'
  | 'horizontalStraightLine'
  | 'priceLine'
  | 'verticalStraightLine'
  | 'verticalSegment'
  | 'verticalRayLine'
  | 'tradingFibonacciExtension'
  | 'tradingFibonacciFan'
  | 'parallelStraightLine'
  | 'priceChannelLine'
  | 'tradingParallelRayLine'
  | 'fibonacciLine'
  | 'tradingRectangle'
  | 'brush'
  | 'tradingCircle'
  | 'tradingTriangle'
  | 'simpleTag'
  | 'simpleAnnotation'
export type DrawingToolOption = {
  value: DrawingTool
  label: string
  overlayName: DrawingOverlayName | null
  shortcut?: string
}
export type DrawingToolCommand = {
  value: 'hideDrawings' | 'hideIndicators' | 'hideAll' | 'weakMagnet' | 'strongMagnet' | 'clearDrawings'
  label: string
  shortcut?: string
}
export type DrawingShortcutAction =
  | { type: 'tool'; tool: DrawingTool }
  | { type: 'command'; command: DrawingToolCommand['value'] }
export type DrawingToolGroup = {
  value: 'line' | 'fibonacci' | 'shape' | 'channel' | 'text' | 'visibility' | 'magnet' | 'clear'
  label: string
  items: Array<DrawingToolOption | DrawingToolCommand>
}
export type IndicatorConfigGroup = 'trading' | 'main' | 'secondary'
export type IndicatorSource = 'klinecharts' | 'generated'
export type IndicatorPane = 'main' | 'secondary'
export type IndicatorVisualStyle = 'line' | 'bar' | 'mixed'
export type IndicatorConfigName = string
export type IndicatorConfigOption = {
  value: IndicatorConfigName
  label: string
  group: IndicatorConfigGroup
  pane: IndicatorPane
  source: IndicatorSource
  calcParams: number[]
  paramLabels: string[]
  visualStyle: IndicatorVisualStyle
  lineCount: number
  paneHeight?: number
}

type ChartSettingsStorage = Pick<Storage, 'getItem' | 'setItem'>
type ShortcutTarget = {
  tagName?: string
  isContentEditable?: boolean
  closest?: (selector: string) => unknown
}
type KeyboardShortcutEvent = {
  key: string
  altKey?: boolean
  ctrlKey?: boolean
  metaKey?: boolean
}

export type ChartIntervalOption = {
  value: TradingPeriod
  label: string
}

export type MovingAverageSettings = {
  lines: MovingAverageLineSettings[]
}

export type MovingAverageLineSettings = {
  enabled: boolean
  period: number
  lineType: LineType
  color: string
  width: number
}

export type VolumeIndicatorSettings = {
  enabled: boolean
  barUpColor: string
  barDownColor: string
  ma1: { enabled: boolean; period: number; color: string }
  ma2: { enabled: boolean; period: number; color: string }
  opacity: number
}

export type IndicatorParameterSettings = {
  calcParams: number[]
  lineType: LineType
  color: string
  secondaryColor: string
  width: number
  barUpColor: string
  barDownColor: string
  opacity: number
}

export type IndicatorSettings = {
  enabled: string[]
  indicators: Record<string, IndicatorParameterSettings>
  movingAverage: MovingAverageSettings
  exponentialMovingAverage: MovingAverageSettings
  weightedMovingAverage: MovingAverageSettings
  volume: VolumeIndicatorSettings
}

export type IndicatorApplyDescriptor = {
  name: string
  paneId: string
  stackOnCandle: boolean
  paneHeight?: number
  calcParams?: number[]
  styles?: Record<string, unknown>
}

export type IndicatorApplyPlan = {
  volume: IndicatorApplyDescriptor | null
  mainIndicators: IndicatorApplyDescriptor[]
  secondaryIndicators: IndicatorApplyDescriptor[]
}

export type ChartSettings = {
  interval: TradingPeriod
  favoriteIntervals: TradingPeriod[]
  timezone: ChartTimezone
  chartType: ChartType
  candleStyle: {
    useCustomColors: boolean
    upColor: string
    downColor: string
    upBorderColor: string
    downBorderColor: string
    upWickColor: string
    downWickColor: string
  }
  axisSettings: {
    priceScaleMode: PriceScaleMode
    countdown: boolean
    depth: boolean
    priceChangePercent: boolean
    latestPrice: boolean
    highPriceMark: boolean
    lowPriceMark: boolean
    highLowPriceMarks: boolean
    tooltipStyle: ChartTooltipStyle
    indicatorLastValue: boolean
    invertedCoordinate: boolean
    barSpace: number
    latestPriceLineType: LineType
    latestPriceColor: string
  }
  layoutSettings: {
    clickToEnableInteraction: boolean
    background: {
      type: 'solid'
      color: string
    }
    gridLines: GridLineMode
    crosshair: {
      enabled: boolean
      color: string
      lineType: LineType
    }
  }
  indicatorSettings: IndicatorSettings
  drawingToolSettings: {
    activeTool: DrawingTool
    magnetMode: DrawingMagnetMode
  }
  shortcutSettings: {
    intervalShortcuts: boolean
    drawingShortcuts: boolean
    fullscreenShortcut: boolean
  }
}

const chartSettingsStoragePrefix = 'fx-trading-platform:chart-settings:v1:'
const defaultFavoriteIntervals: TradingPeriod[] = ['15m', '1h', '1d']
const p0ChartSymbolKeys = new Set([
  'BTCUSDT', 'ETHUSDT', 'BNBUSDT', 'SOLUSDT', 'XRPUSDT',
  'BTCUSDTPERP', 'ETHUSDTPERP', 'BNBUSDTPERP', 'SOLUSDTPERP', 'XRPUSDTPERP'
])
const p0ChartIntervalValues = new Set<TradingPeriod>(['time', '1s', '1m', '5m', '15m', '1h', '4h', '1d'])
const movingAveragePeriods = [5, 10, 20, 30, 60, 120]
const movingAverageColors = ['#ffab2e', '#e83e78', '#4dd0e1', '#f4511e', '#ab47bc', '#66bb6a']

export const allChartIntervals: ChartIntervalOption[] = [
  { value: 'time', label: 'chart.intervals.time' },
  { value: '1s', label: 'chart.intervals.1s' },
  { value: '1m', label: 'chart.intervals.1m' },
  { value: '3m', label: 'chart.intervals.3m' },
  { value: '5m', label: 'chart.intervals.5m' },
  { value: '15m', label: 'chart.intervals.15m' },
  { value: '30m', label: 'chart.intervals.30m' },
  { value: '1h', label: 'chart.intervals.1h' },
  { value: '2h', label: 'chart.intervals.2h' },
  { value: '4h', label: 'chart.intervals.4h' },
  { value: '6h', label: 'chart.intervals.6h' },
  { value: '12h', label: 'chart.intervals.12h' },
  { value: '1d', label: 'chart.intervals.1d' },
  { value: '2d', label: 'chart.intervals.2d' },
  { value: '3d', label: 'chart.intervals.3d' },
  { value: '5d', label: 'chart.intervals.5d' },
  { value: '1w', label: 'chart.intervals.1w' },
  { value: '1M', label: 'chart.intervals.1M' },
  { value: '3M', label: 'chart.intervals.3M' }
]

const p0ChartIntervals = allChartIntervals.filter((item) => p0ChartIntervalValues.has(item.value))

export function isP0ChartSymbol(symbol: string) {
  return p0ChartSymbolKeys.has(normalizeChartSymbol(symbol))
}

export function getChartIntervalOptions(symbol: string): ChartIntervalOption[] {
  return isP0ChartSymbol(symbol) ? p0ChartIntervals : allChartIntervals
}

export function normalizeChartInterval(symbol: string, interval: TradingPeriod): TradingPeriod {
  if (!isP0ChartSymbol(symbol) || p0ChartIntervalValues.has(interval)) return interval
  return '1m'
}

export function quickChartIntervals(
  settings?: Pick<ChartSettings, 'favoriteIntervals'>,
  options: ChartIntervalOption[] = allChartIntervals
): ChartIntervalOption[] {
  const favorites = normalizeFavoriteIntervals(settings?.favoriteIntervals, defaultFavoriteIntervals)
  return favorites
    .map((interval) => options.find((item) => item.value === interval))
    .filter((item): item is ChartIntervalOption => Boolean(item))
}

export function toggleFavoriteInterval(favoriteIntervals: TradingPeriod[], interval: TradingPeriod): TradingPeriod[] {
  const favorites = normalizeFavoriteIntervals(favoriteIntervals, defaultFavoriteIntervals)
  if (!favorites.includes(interval)) return [...favorites, interval]
  if (favorites.length <= 1) return favorites
  return favorites.filter((item) => item !== interval)
}

export const chartTypeOptions: Array<{ value: ChartType; label: string }> = [
  { value: 'candle', label: 'chart.types.candle' },
  { value: 'bar', label: 'chart.types.bar' },
  { value: 'hlc', label: 'chart.types.hlc' },
  { value: 'line', label: 'chart.types.line' }
]

export const priceScaleModeOptions: Array<{ value: PriceScaleMode; label: string }> = [
  { value: 'normal', label: 'chart.priceScaleModes.normal' },
  { value: 'percentage', label: 'chart.priceScaleModes.percentage' },
  { value: 'logarithm', label: 'chart.priceScaleModes.logarithm' }
]

export const chartTimezoneOptions: Array<{ value: ChartTimezone; label: string }> = [
  { value: 'local', label: 'chart.timezones.local' },
  { value: 'UTC', label: 'chart.timezones.utc' },
  { value: 'Asia/Shanghai', label: 'chart.timezones.shanghai' },
  { value: 'Asia/Singapore', label: 'chart.timezones.singapore' },
  { value: 'Asia/Tokyo', label: 'chart.timezones.tokyo' },
  { value: 'Europe/London', label: 'chart.timezones.london' },
  { value: 'America/New_York', label: 'chart.timezones.newYork' }
]

export const drawingToolOptions: DrawingToolOption[] = [
  { value: 'cursor', label: 'chart.drawing.cursor', overlayName: null },
  { value: 'segment', label: 'chart.drawing.segment', overlayName: 'segment', shortcut: 'Alt + T' },
  { value: 'arrowLine', label: 'chart.drawing.arrowLine', overlayName: 'tradingArrowLine' },
  { value: 'trendLine', label: 'chart.drawing.trendLine', overlayName: 'segment' },
  { value: 'rayLine', label: 'chart.drawing.rayLine', overlayName: 'rayLine' },
  { value: 'straightLine', label: 'chart.drawing.straightLine', overlayName: 'straightLine' },
  { value: 'horizontalSegment', label: 'chart.drawing.horizontalSegment', overlayName: 'horizontalSegment' },
  { value: 'horizontalRayLine', label: 'chart.drawing.horizontalRayLine', overlayName: 'horizontalRayLine', shortcut: 'Alt + J' },
  { value: 'horizontalLine', label: 'chart.drawing.horizontalLine', overlayName: 'horizontalStraightLine', shortcut: 'Alt + H' },
  { value: 'priceLine', label: 'chart.drawing.priceLine', overlayName: 'priceLine' },
  { value: 'verticalLine', label: 'chart.drawing.verticalLine', overlayName: 'verticalStraightLine', shortcut: 'Alt + V' },
  { value: 'verticalSegment', label: 'chart.drawing.verticalSegment', overlayName: 'verticalSegment' },
  { value: 'verticalRayLine', label: 'chart.drawing.verticalRayLine', overlayName: 'verticalRayLine' },
  { value: 'fibonacciExtension', label: 'chart.drawing.fibonacciExtension', overlayName: 'tradingFibonacciExtension' },
  { value: 'fibonacciFan', label: 'chart.drawing.fibonacciFan', overlayName: 'tradingFibonacciFan' },
  { value: 'fibonacciLine', label: 'chart.drawing.fibonacciLine', overlayName: 'fibonacciLine', shortcut: 'Ctrl + Alt + F' },
  { value: 'rectangle', label: 'chart.drawing.rectangle', overlayName: 'tradingRectangle' },
  { value: 'brush', label: 'chart.drawing.brush', overlayName: 'brush' },
  { value: 'circle', label: 'chart.drawing.circle', overlayName: 'tradingCircle' },
  { value: 'triangle', label: 'chart.drawing.triangle', overlayName: 'tradingTriangle' },
  { value: 'priceChannel', label: 'chart.drawing.priceChannel', overlayName: 'priceChannelLine' },
  { value: 'parallelLine', label: 'chart.drawing.parallelLine', overlayName: 'parallelStraightLine' },
  { value: 'parallelRayLine', label: 'chart.drawing.parallelRayLine', overlayName: 'tradingParallelRayLine' },
  { value: 'priceTag', label: 'chart.drawing.priceTag', overlayName: 'simpleTag' },
  { value: 'text', label: 'chart.drawing.text', overlayName: 'simpleAnnotation' }
]

export const drawingToolGroups: DrawingToolGroup[] = [
  {
    value: 'line',
    label: 'chart.drawingGroups.line',
    items: getDrawingToolGroupItems([
      'segment',
      'arrowLine',
      'trendLine',
      'rayLine',
      'straightLine',
      'horizontalSegment',
      'horizontalRayLine',
      'horizontalLine',
      'priceLine',
      'verticalLine',
      'verticalSegment',
      'verticalRayLine'
    ])
  },
  {
    value: 'fibonacci',
    label: 'chart.drawingGroups.fibonacci',
    items: getDrawingToolGroupItems(['fibonacciLine', 'fibonacciExtension', 'fibonacciFan'])
  },
  {
    value: 'shape',
    label: 'chart.drawingGroups.shape',
    items: getDrawingToolGroupItems(['rectangle', 'brush', 'circle', 'triangle', 'priceTag'])
  },
  {
    value: 'channel',
    label: 'chart.drawingGroups.channel',
    items: getDrawingToolGroupItems(['priceChannel', 'parallelLine', 'parallelRayLine'])
  },
  {
    value: 'text',
    label: 'chart.drawingGroups.text',
    items: getDrawingToolGroupItems(['text'])
  },
  {
    value: 'visibility',
    label: 'chart.drawingGroups.visibility',
    items: [
      { value: 'hideDrawings', label: 'chart.commands.hideDrawings', shortcut: 'Ctrl + Alt + H' },
      { value: 'hideIndicators', label: 'chart.commands.hideIndicators' },
      { value: 'hideAll', label: 'chart.commands.hideAll' }
    ]
  },
  {
    value: 'magnet',
    label: 'chart.drawingGroups.magnet',
    items: [
      { value: 'weakMagnet', label: 'chart.commands.weakMagnet' },
      { value: 'strongMagnet', label: 'chart.commands.strongMagnet' }
    ]
  },
  {
    value: 'clear',
    label: 'chart.drawingGroups.clear',
    items: [{ value: 'clearDrawings', label: 'chart.commands.clearDrawings' }]
  }
]

export const indicatorConfigOptions: IndicatorConfigOption[] = [
  createIndicatorOption('OI', 'OI', 'trading', 'secondary', 'generated', [14], ['chart.params.period'], 'line', 1),
  createIndicatorOption('TOP_ACC_LS', 'Top Acc. L/S', 'trading', 'secondary', 'generated', [14], ['chart.params.period'], 'line', 1),
  createIndicatorOption('TOP_POS_LS', 'Top Pos. L/S', 'trading', 'secondary', 'generated', [14], ['chart.params.period'], 'line', 1),
  createIndicatorOption('ACC_LS', 'Acc. L/S', 'trading', 'secondary', 'generated', [14], ['chart.params.period'], 'line', 1),
  createIndicatorOption('TAKER_BS', 'Taker B/S', 'trading', 'secondary', 'generated', [14], ['chart.params.period'], 'bar', 0),

  createIndicatorOption('MA', 'MA', 'main', 'main', 'klinecharts', [5, 10, 20, 30, 60, 120], ['chart.params.period'], 'line', 6),
  createIndicatorOption('EMA', 'EMA', 'main', 'main', 'klinecharts', [5, 10, 20, 30, 60, 120], ['chart.params.period'], 'line', 6),
  createIndicatorOption('WMA', 'WMA', 'main', 'main', 'generated', [5, 10, 20, 30, 60, 120], ['chart.params.period'], 'line', 6),
  createIndicatorOption('SMA', 'SMA', 'main', 'main', 'klinecharts', [12, 2], ['chart.params.period', 'chart.params.weight'], 'line', 1),
  createIndicatorOption('BOLL', 'BOLL', 'main', 'main', 'klinecharts', [20, 2], ['chart.params.period', 'chart.params.multiplier'], 'line', 3),
  createIndicatorOption('SAR', 'SAR', 'main', 'main', 'klinecharts', [2, 2, 20], ['chart.params.step', 'chart.params.increment', 'chart.params.max'], 'line', 1),
  createIndicatorOption('BBI', 'BBI', 'main', 'main', 'klinecharts', [3, 6, 12, 24], ['chart.params.period1', 'chart.params.period2', 'chart.params.period3', 'chart.params.period4'], 'line', 1),
  createIndicatorOption('AVP', 'AVP', 'main', 'main', 'klinecharts', [], [], 'line', 1),
  createIndicatorOption('AVL', 'AVL', 'main', 'main', 'generated', [20], ['chart.params.period'], 'line', 1),
  createIndicatorOption('VWAP', 'VWAP', 'main', 'main', 'generated', [20], ['chart.params.period'], 'line', 1),
  createIndicatorOption('SUPER_TREND', 'chart.indicatorNames.superTrend', 'main', 'main', 'generated', [10, 3], ['chart.params.atrPeriod', 'chart.params.multiplier'], 'line', 1),
  createIndicatorOption('SUPPORT_RESISTANCE', 'chart.indicatorNames.supportResistance', 'main', 'main', 'generated', [20], ['chart.params.period'], 'line', 2),

  createIndicatorOption('VOL', 'VOLUME', 'secondary', 'secondary', 'klinecharts', [5, 10, 20], ['MA1', 'MA2', 'MA3'], 'bar', 3, 96),
  createIndicatorOption('MACD', 'MACD', 'secondary', 'secondary', 'klinecharts', [12, 26, 9], ['chart.params.fastLine', 'chart.params.slowLine', 'chart.params.signal'], 'mixed', 2),
  createIndicatorOption('KDJ', 'KDJ', 'secondary', 'secondary', 'klinecharts', [9, 3, 3], ['chart.params.period', 'K', 'D'], 'line', 3),
  createIndicatorOption('SKDJ', 'SKDJ', 'secondary', 'secondary', 'generated', [9, 3, 3], ['chart.params.period', 'K', 'D'], 'line', 2),
  createIndicatorOption('RSI', 'RSI', 'secondary', 'secondary', 'klinecharts', [6, 12, 24], ['chart.params.period1', 'chart.params.period2', 'chart.params.period3'], 'line', 3),
  createIndicatorOption('BIAS', 'BIAS', 'secondary', 'secondary', 'klinecharts', [6, 12, 24], ['chart.params.period1', 'chart.params.period2', 'chart.params.period3'], 'line', 3),
  createIndicatorOption('BRAR', 'BRAR', 'secondary', 'secondary', 'klinecharts', [26], ['chart.params.period'], 'line', 2),
  createIndicatorOption('CCI', 'CCI', 'secondary', 'secondary', 'klinecharts', [20], ['chart.params.period'], 'line', 1),
  createIndicatorOption('CR', 'CR', 'secondary', 'secondary', 'klinecharts', [26, 10, 20, 40, 60], ['chart.params.period', 'MA1', 'MA2', 'MA3', 'MA4'], 'line', 5),
  createIndicatorOption('DMA', 'DMA', 'secondary', 'secondary', 'klinecharts', [10, 50, 10], ['chart.params.shortPeriod', 'chart.params.longPeriod', 'chart.params.ma'], 'line', 2),
  createIndicatorOption('DMI', 'DMI', 'secondary', 'secondary', 'klinecharts', [14, 6], ['chart.params.period', 'chart.params.ma'], 'line', 4),
  createIndicatorOption('EMV', 'EMV', 'secondary', 'secondary', 'klinecharts', [14, 9], ['chart.params.period', 'chart.params.ma'], 'line', 2),
  createIndicatorOption('MTM', 'MTM', 'secondary', 'secondary', 'klinecharts', [12, 6], ['chart.params.period', 'chart.params.ma'], 'line', 2),
  createIndicatorOption('OBV', 'OBV', 'secondary', 'secondary', 'klinecharts', [30], ['chart.params.ma'], 'line', 2),
  createIndicatorOption('PVT', 'PVT', 'secondary', 'secondary', 'klinecharts', [], [], 'line', 1),
  createIndicatorOption('PSY', 'PSY', 'secondary', 'secondary', 'klinecharts', [12, 6], ['chart.params.period', 'chart.params.ma'], 'line', 2),
  createIndicatorOption('ROC', 'ROC', 'secondary', 'secondary', 'klinecharts', [12, 6], ['chart.params.period', 'chart.params.ma'], 'line', 2),
  createIndicatorOption('TRIX', 'TRIX', 'secondary', 'secondary', 'klinecharts', [12, 9], ['chart.params.period', 'chart.params.ma'], 'line', 2),
  createIndicatorOption('VR', 'VR', 'secondary', 'secondary', 'klinecharts', [26, 6], ['chart.params.period', 'chart.params.ma'], 'line', 2),
  createIndicatorOption('WR', 'WR', 'secondary', 'secondary', 'klinecharts', [6, 10, 14], ['chart.params.period1', 'chart.params.period2', 'chart.params.period3'], 'line', 3),
  createIndicatorOption('AO', 'AO', 'secondary', 'secondary', 'klinecharts', [5, 34], ['chart.params.shortPeriod', 'chart.params.longPeriod'], 'bar', 0)
]

export const defaultIndicatorSettings: IndicatorSettings = {
  enabled: ['MA'],
  indicators: createDefaultIndicatorParameterSettings(),
  movingAverage: { lines: createDefaultMovingAverageLines() },
  exponentialMovingAverage: { lines: createDefaultMovingAverageLines() },
  weightedMovingAverage: { lines: createDefaultMovingAverageLines() },
  volume: {
    enabled: true,
    barUpColor: '#16c784',
    barDownColor: '#ea3943',
    ma1: { enabled: false, period: 5, color: '#ffab2e' },
    ma2: { enabled: false, period: 10, color: '#4dd0e1' },
    opacity: 50
  }
}

export const defaultChartSettings: ChartSettings = {
  interval: '1m',
  favoriteIntervals: [...defaultFavoriteIntervals],
  timezone: 'local',
  chartType: 'candle',
  candleStyle: {
    useCustomColors: false,
    upColor: '#16c784',
    downColor: '#ea3943',
    upBorderColor: '#16c784',
    downBorderColor: '#ea3943',
    upWickColor: '#16c784',
    downWickColor: '#ea3943'
  },
  axisSettings: {
    priceScaleMode: 'normal',
    countdown: true,
    depth: true,
    priceChangePercent: true,
    latestPrice: true,
    highPriceMark: true,
    lowPriceMark: true,
    highLowPriceMarks: true,
    tooltipStyle: 'standard',
    indicatorLastValue: false,
    invertedCoordinate: false,
    barSpace: 8,
    latestPriceLineType: 'dashed',
    latestPriceColor: '#16c784'
  },
  layoutSettings: {
    clickToEnableInteraction: false,
    background: {
      type: 'solid',
      color: '#050505'
    },
    gridLines: 'both',
    crosshair: {
      enabled: true,
      color: '#8b93a1',
      lineType: 'dashed'
    }
  },
  indicatorSettings: cloneIndicatorSettings(defaultIndicatorSettings),
  drawingToolSettings: {
    activeTool: 'cursor',
    magnetMode: 'none'
  },
  shortcutSettings: {
    intervalShortcuts: true,
    drawingShortcuts: true,
    fullscreenShortcut: true
  }
}

export function getChartSettingsStorageKey(symbol: string) {
  const normalizedSymbol = symbol.trim().toUpperCase() || 'DEFAULT'
  return `${chartSettingsStoragePrefix}${normalizedSymbol}`
}

export function loadChartSettings(symbol: string, storage = getBrowserStorage()): ChartSettings {
  if (!storage) return cloneSettings(defaultChartSettings)

  try {
    const raw = storage.getItem(getChartSettingsStorageKey(symbol))
    if (!raw) return cloneSettings(defaultChartSettings)
    return normalizeChartSettings(JSON.parse(raw))
  } catch {
    return cloneSettings(defaultChartSettings)
  }
}

export type OwnedChartSettingsState = {
  ownerSymbol: string
  settings: ChartSettings
}

export function loadChartSettingsForSymbol(symbol: string, storage = getBrowserStorage()): ChartSettings {
  const settings = loadChartSettings(symbol, storage)
  const interval = normalizeChartInterval(symbol, settings.interval)
  if (interval === settings.interval) return settings

  const normalizedSettings = { ...settings, interval }
  try {
    saveChartSettings(symbol, normalizedSettings, storage)
  } catch {
    // Storage persistence is best-effort; rendering must still use the safe interval.
  }
  return normalizedSettings
}

export function selectChartSettingsForSymbol(
  state: OwnedChartSettingsState,
  selectedSymbol: string,
  loadSettings: (symbol: string) => ChartSettings = loadChartSettingsForSymbol
): OwnedChartSettingsState {
  if (state.ownerSymbol === selectedSymbol) return state
  return {
    ownerSymbol: selectedSymbol,
    settings: loadSettings(selectedSymbol)
  }
}

export function saveChartSettings(symbol: string, settings: ChartSettings, storage = getBrowserStorage()) {
  storage?.setItem(getChartSettingsStorageKey(symbol), JSON.stringify(normalizeChartSettings(settings)))
}

export function chartTypeToCandleType(chartType: ChartType) {
  if (chartType === 'line') return 'area'
  if (chartType === 'bar' || chartType === 'hlc') return 'ohlc'
  return 'candle_solid'
}

export function getChartTypeStyles(chartType: ChartType) {
  return {
    candle: {
      type: chartTypeToCandleType(chartType),
      area: {
        lineColor: '#f2b84b',
        backgroundColor: [
          { offset: 0, color: 'rgba(242, 184, 75, 0.02)' },
          { offset: 1, color: 'rgba(242, 184, 75, 0.16)' }
        ]
      }
    }
  }
}

export function getChartVisualStyles(settings: Pick<ChartSettings, 'chartType' | 'candleStyle' | 'axisSettings' | 'layoutSettings'>) {
  const gridLines = settings.layoutSettings.gridLines
  const gridVisible = gridLines !== 'none'
  const chartTypeStyles = getChartTypeStyles(settings.chartType)
  const customCandleColors = settings.candleStyle.useCustomColors
    ? {
        upColor: settings.candleStyle.upColor,
        downColor: settings.candleStyle.downColor,
        upBorderColor: settings.candleStyle.upBorderColor,
        downBorderColor: settings.candleStyle.downBorderColor,
        upWickColor: settings.candleStyle.upWickColor,
        downWickColor: settings.candleStyle.downWickColor
      }
    : {}

  return {
    grid: {
      show: gridVisible,
      horizontal: {
        show: gridLines === 'horizontal' || gridLines === 'both'
      },
      vertical: {
        show: gridLines === 'vertical' || gridLines === 'both'
      }
    },
    candle: {
      ...chartTypeStyles.candle,
      bar: customCandleColors,
      priceMark: {
        show: settings.axisSettings.latestPrice || settings.axisSettings.highPriceMark || settings.axisSettings.lowPriceMark,
        high: {
          show: settings.axisSettings.highPriceMark
        },
        low: {
          show: settings.axisSettings.lowPriceMark
        },
        last: {
          show: settings.axisSettings.latestPrice,
          upColor: settings.axisSettings.latestPriceColor,
          downColor: settings.axisSettings.latestPriceColor,
          noChangeColor: settings.axisSettings.latestPriceColor,
          line: {
            show: settings.axisSettings.latestPrice,
            style: settings.axisSettings.latestPriceLineType
          }
        }
      },
      tooltip: getChartTooltipStyles(settings.axisSettings.tooltipStyle)
    },
    crosshair: {
      show: settings.layoutSettings.crosshair.enabled,
      horizontal: {
        line: {
          show: settings.layoutSettings.crosshair.enabled,
          color: settings.layoutSettings.crosshair.color,
          style: settings.layoutSettings.crosshair.lineType
        },
        text: {
          show: settings.layoutSettings.crosshair.enabled
        }
      },
      vertical: {
        line: {
          show: settings.layoutSettings.crosshair.enabled,
          color: settings.layoutSettings.crosshair.color,
          style: settings.layoutSettings.crosshair.lineType
        },
        text: {
          show: settings.layoutSettings.crosshair.enabled
        }
      }
    }
  }
}

function getChartTooltipStyles(style: ChartTooltipStyle) {
  if (style === 'hidden') {
    return {
      showRule: 'none'
    }
  }

  if (style === 'compact') {
    return {
      showRule: 'follow_cross',
      showType: 'rect',
      title: {
        show: false
      }
    }
  }

  return {
    showRule: 'follow_cross',
    showType: 'standard',
    title: {
      show: true
    }
  }
}

export function drawingToolToOverlayName(tool: DrawingTool): DrawingOverlayName | null {
  return drawingToolOptions.find((item) => item.value === tool)?.overlayName ?? null
}

export function cloneIndicatorSettings(settings: IndicatorSettings): IndicatorSettings {
  return JSON.parse(JSON.stringify(settings)) as IndicatorSettings
}

export function updateIndicatorEnabled(
  settings: IndicatorSettings,
  indicator: IndicatorConfigName | string,
  enabled: boolean
): IndicatorSettings {
  const next = cloneIndicatorSettings(settings)

  if (indicator === 'VOLUME' || indicator === 'VOL') {
    next.volume.enabled = enabled
    return next
  }

  const existing = next.enabled.filter((item) => item !== indicator)
  next.enabled = enabled ? [...existing, indicator] : existing
  return next
}

export function getEnabledIndicatorNames(settings: IndicatorSettings): string[] {
  const names = settings.volume.enabled ? ['VOL', ...settings.enabled] : [...settings.enabled]
  return Array.from(new Set(names.filter((name) => hasIndicatorOption(name))))
}

export function buildIndicatorApplyPlan(settings: IndicatorSettings): IndicatorApplyPlan {
  const enabled = new Set(settings.enabled)
  const mainIndicators = indicatorConfigOptions
    .filter((option) => option.pane === 'main' && enabled.has(option.value))
    .map((option) => buildIndicatorDescriptor(option, settings))
    .filter((item): item is IndicatorApplyDescriptor => Boolean(item))
  const secondaryIndicators = indicatorConfigOptions
    .filter((option) => option.pane === 'secondary' && option.value !== 'VOL' && enabled.has(option.value))
    .map((option) => buildIndicatorDescriptor(option, settings))
    .filter((item): item is IndicatorApplyDescriptor => Boolean(item))

  return {
    volume: settings.volume.enabled ? buildVolumeDescriptor(settings.volume) : null,
    mainIndicators,
    secondaryIndicators
  }
}

export function getIntervalByShortcutKey(
  key: string,
  intervalsOrSettings: ChartIntervalOption[] | Pick<ChartSettings, 'favoriteIntervals'> = defaultChartSettings
): TradingPeriod | null {
  if (!/^[1-9]$/.test(key)) return null
  const intervals = Array.isArray(intervalsOrSettings)
    ? intervalsOrSettings
    : quickChartIntervals(intervalsOrSettings)
  return intervals[Number(key) - 1]?.value ?? null
}

export function getDrawingShortcutAction(event: KeyboardShortcutEvent): DrawingShortcutAction | null {
  const key = event.key.toLowerCase()
  const alt = event.altKey === true
  const ctrl = event.ctrlKey === true || event.metaKey === true

  if (!alt) return null
  if (ctrl && key === 'h') return { type: 'command', command: 'hideDrawings' }
  if (ctrl && key === 'f') return { type: 'tool', tool: 'fibonacciLine' }
  if (ctrl) return null

  if (key === 't') return { type: 'tool', tool: 'segment' }
  if (key === 'j') return { type: 'tool', tool: 'horizontalRayLine' }
  if (key === 'h') return { type: 'tool', tool: 'horizontalLine' }
  if (key === 'v') return { type: 'tool', tool: 'verticalLine' }

  return null
}

export function shouldIgnoreIntervalShortcut(
  target: ShortcutTarget | null | undefined,
  popupOpen: boolean
) {
  if (popupOpen || !target) return popupOpen
  const tagName = target.tagName?.toUpperCase()
  if (tagName === 'INPUT' || tagName === 'TEXTAREA' || tagName === 'SELECT') return true
  if (target.isContentEditable) return true

  return Boolean(target.closest?.('form, [role="dialog"], [data-shortcut-disabled="true"]'))
}

export function shouldIgnoreDrawingShortcut(target: ShortcutTarget | null | undefined) {
  if (!target) return false
  const tagName = target.tagName?.toUpperCase()
  if (tagName === 'INPUT' || tagName === 'TEXTAREA' || tagName === 'SELECT') return true
  if (target.isContentEditable) return true

  return Boolean(target.closest?.('form, [role="dialog"], [data-shortcut-disabled="true"]'))
}

function normalizeChartSettings(value: unknown): ChartSettings {
  if (!isRecord(value)) return cloneSettings(defaultChartSettings)

  const settings = cloneSettings(defaultChartSettings)
  settings.interval = isTradingPeriod(value.interval) ? value.interval : settings.interval
  settings.favoriteIntervals = normalizeFavoriteIntervals(value.favoriteIntervals, settings.favoriteIntervals)
  settings.timezone = isChartTimezone(value.timezone) ? value.timezone : settings.timezone
  settings.chartType = isChartType(value.chartType) ? value.chartType : settings.chartType

  if (isRecord(value.candleStyle)) {
    settings.candleStyle = { ...settings.candleStyle, ...value.candleStyle }
  }
  if (isRecord(value.axisSettings)) {
    const highLowPriceMarks = typeof value.axisSettings.highLowPriceMarks === 'boolean'
      ? value.axisSettings.highLowPriceMarks
      : undefined
    const highPriceMark = typeof value.axisSettings.highPriceMark === 'boolean'
      ? value.axisSettings.highPriceMark
      : highLowPriceMarks ?? settings.axisSettings.highPriceMark
    const lowPriceMark = typeof value.axisSettings.lowPriceMark === 'boolean'
      ? value.axisSettings.lowPriceMark
      : highLowPriceMarks ?? settings.axisSettings.lowPriceMark

    settings.axisSettings = {
      ...settings.axisSettings,
      ...value.axisSettings,
      countdown: typeof value.axisSettings.countdown === 'boolean'
        ? value.axisSettings.countdown
        : settings.axisSettings.countdown,
      latestPrice: typeof value.axisSettings.latestPrice === 'boolean'
        ? value.axisSettings.latestPrice
        : settings.axisSettings.latestPrice,
      priceScaleMode: isPriceScaleMode(value.axisSettings.priceScaleMode)
        ? value.axisSettings.priceScaleMode
        : settings.axisSettings.priceScaleMode,
      highPriceMark,
      lowPriceMark,
      highLowPriceMarks: highLowPriceMarks ?? (highPriceMark && lowPriceMark),
      tooltipStyle: isChartTooltipStyle(value.axisSettings.tooltipStyle)
        ? value.axisSettings.tooltipStyle
        : settings.axisSettings.tooltipStyle,
      indicatorLastValue: typeof value.axisSettings.indicatorLastValue === 'boolean'
        ? value.axisSettings.indicatorLastValue
        : settings.axisSettings.indicatorLastValue,
      invertedCoordinate: typeof value.axisSettings.invertedCoordinate === 'boolean'
        ? value.axisSettings.invertedCoordinate
        : settings.axisSettings.invertedCoordinate,
      barSpace: normalizeBarSpace(value.axisSettings.barSpace, settings.axisSettings.barSpace)
    }
  }
  if (isRecord(value.layoutSettings)) {
    settings.layoutSettings = {
      ...settings.layoutSettings,
      ...value.layoutSettings,
      gridLines: isGridLineMode(value.layoutSettings.gridLines)
        ? value.layoutSettings.gridLines
        : settings.layoutSettings.gridLines,
      background: isRecord(value.layoutSettings.background)
        ? { ...settings.layoutSettings.background, ...value.layoutSettings.background }
        : settings.layoutSettings.background,
      crosshair: isRecord(value.layoutSettings.crosshair)
        ? { ...settings.layoutSettings.crosshair, ...value.layoutSettings.crosshair }
        : settings.layoutSettings.crosshair
    }
  }
  if (isRecord(value.indicatorSettings)) settings.indicatorSettings = normalizeIndicatorSettings(value.indicatorSettings)
  if (isRecord(value.drawingToolSettings)) {
    settings.drawingToolSettings = {
      ...settings.drawingToolSettings,
      activeTool: isDrawingTool(value.drawingToolSettings.activeTool)
        ? value.drawingToolSettings.activeTool
        : settings.drawingToolSettings.activeTool,
      magnetMode: normalizeMagnetMode(value.drawingToolSettings)
    }
  }
  if (isRecord(value.shortcutSettings)) {
    settings.shortcutSettings = {
      intervalShortcuts: typeof value.shortcutSettings.intervalShortcuts === 'boolean'
        ? value.shortcutSettings.intervalShortcuts
        : settings.shortcutSettings.intervalShortcuts,
      drawingShortcuts: typeof value.shortcutSettings.drawingShortcuts === 'boolean'
        ? value.shortcutSettings.drawingShortcuts
        : settings.shortcutSettings.drawingShortcuts,
      fullscreenShortcut: typeof value.shortcutSettings.fullscreenShortcut === 'boolean'
        ? value.shortcutSettings.fullscreenShortcut
        : settings.shortcutSettings.fullscreenShortcut
    }
  }

  return settings
}

function createDefaultMovingAverageLines(): MovingAverageLineSettings[] {
  return movingAveragePeriods.map((period, index) => ({
    enabled: index < 3,
    period,
    lineType: 'solid',
    color: movingAverageColors[index],
    width: 1
  }))
}

function createIndicatorOption(
  value: IndicatorConfigName,
  label: string,
  group: IndicatorConfigGroup,
  pane: IndicatorPane,
  source: IndicatorSource,
  calcParams: number[],
  paramLabels: string[],
  visualStyle: IndicatorVisualStyle,
  lineCount: number,
  paneHeight?: number
): IndicatorConfigOption {
  return {
    value,
    label,
    group,
    pane,
    source,
    calcParams,
    paramLabels,
    visualStyle,
    lineCount,
    paneHeight
  }
}

function createDefaultIndicatorParameterSettings(): Record<string, IndicatorParameterSettings> {
  return Object.fromEntries(
    indicatorConfigOptions.map((option, index) => [option.value, createDefaultIndicatorParameterSetting(option, index)])
  )
}

function createDefaultIndicatorParameterSetting(option: IndicatorConfigOption, index: number): IndicatorParameterSettings {
  return {
    calcParams: [...option.calcParams],
    lineType: 'solid',
    color: movingAverageColors[index % movingAverageColors.length],
    secondaryColor: movingAverageColors[(index + 2) % movingAverageColors.length],
    width: 1,
    barUpColor: '#16c784',
    barDownColor: '#ea3943',
    opacity: option.visualStyle === 'bar' ? 64 : 78
  }
}

function buildMovingAverageDescriptor(name: 'MA' | 'EMA' | 'WMA', settings: MovingAverageSettings) {
  const enabledLines = settings.lines.filter((line) => line.enabled && Number.isFinite(line.period) && line.period > 0)
  if (enabledLines.length === 0) return null

  return {
    name,
    paneId: 'candle_pane',
    stackOnCandle: true,
    calcParams: enabledLines.map((line) => Math.round(line.period)),
    styles: { lines: createLineStyles(enabledLines) }
  }
}

function buildVolumeDescriptor(settings: VolumeIndicatorSettings): IndicatorApplyDescriptor {
  const movingAverageLines: Array<Pick<MovingAverageLineSettings, 'color' | 'lineType' | 'width'>> = []
  if (settings.ma1.enabled) movingAverageLines.push({ color: settings.ma1.color, lineType: 'solid', width: 1 })
  if (settings.ma2.enabled) movingAverageLines.push({ color: settings.ma2.color, lineType: 'solid', width: 1 })

  return {
    name: 'VOL',
    paneId: 'volume_pane',
    stackOnCandle: false,
    paneHeight: 96,
    calcParams: [settings.ma1, settings.ma2]
      .filter((item) => item.enabled && Number.isFinite(item.period) && item.period > 0)
      .map((item) => Math.round(item.period)),
    styles: {
      bars: [
        {
          style: 'fill',
          borderStyle: 'solid',
          borderSize: 1,
          borderDashedValue: [2, 2],
          upColor: colorWithOpacity(settings.barUpColor, settings.opacity),
          downColor: colorWithOpacity(settings.barDownColor, settings.opacity),
          noChangeColor: colorWithOpacity('#999999', settings.opacity)
        }
      ],
      lines: createLineStyles(movingAverageLines)
    }
  }
}

function buildIndicatorDescriptor(option: IndicatorConfigOption, settings: IndicatorSettings): IndicatorApplyDescriptor | null {
  if (option.value === 'MA') return buildMovingAverageDescriptor('MA', settings.movingAverage)
  if (option.value === 'EMA') return buildMovingAverageDescriptor('EMA', settings.exponentialMovingAverage)
  if (option.value === 'WMA') return buildMovingAverageDescriptor('WMA', settings.weightedMovingAverage)

  const parameterSettings = settings.indicators[option.value] ?? createDefaultIndicatorParameterSetting(option, 0)
  const calcParams = normalizeCalcParams(parameterSettings.calcParams, option.calcParams)

  return {
    name: option.value,
    paneId: option.pane === 'main' ? 'candle_pane' : `${option.value.toLowerCase()}_pane`,
    stackOnCandle: option.pane === 'main',
    paneHeight: option.pane === 'secondary' ? option.paneHeight ?? 108 : undefined,
    calcParams: calcParams.length > 0 ? calcParams : undefined,
    styles: createGenericIndicatorStyles(option, parameterSettings)
  }
}

function createLineStyles(lines: Array<Pick<MovingAverageLineSettings, 'color' | 'lineType' | 'width'>>) {
  return lines.map((line) => ({
    style: line.lineType,
    smooth: false,
    size: Math.max(1, Math.round(line.width)),
    dashedValue: [4, 4],
    color: line.color
  }))
}

function createGenericIndicatorStyles(option: IndicatorConfigOption, settings: IndicatorParameterSettings) {
  const styles: Record<string, unknown> = {}
  if (option.visualStyle === 'line' || option.visualStyle === 'mixed') {
    styles.lines = createGenericLineStyles(option, settings)
  }
  if (option.visualStyle === 'bar' || option.visualStyle === 'mixed') {
    styles.bars = [
      {
        style: 'fill',
        borderStyle: 'solid',
        borderSize: 1,
        borderDashedValue: [2, 2],
        upColor: colorWithOpacity(settings.barUpColor, settings.opacity),
        downColor: colorWithOpacity(settings.barDownColor, settings.opacity),
        noChangeColor: colorWithOpacity(settings.secondaryColor, settings.opacity)
      }
    ]
  }
  return styles
}

function createGenericLineStyles(option: IndicatorConfigOption, settings: IndicatorParameterSettings) {
  const lineCount = Math.max(1, option.lineCount)
  const colors = [settings.color, settings.secondaryColor, '#4dd0e1', '#ab47bc', '#66bb6a', '#ff7043']
  return Array.from({ length: lineCount }, (_, index) => ({
    style: settings.lineType,
    smooth: false,
    size: Math.max(1, Math.round(settings.width)),
    dashedValue: [4, 4],
    color: colors[index % colors.length]
  }))
}

function normalizeCalcParams(value: unknown, fallback: number[]) {
  if (!Array.isArray(value)) return [...fallback]
  const calcParams = value
    .map((item) => Number(item))
    .filter((item) => Number.isFinite(item) && item > 0)
    .map((item) => Math.round(item))
  return calcParams.length > 0 || fallback.length === 0 ? calcParams : [...fallback]
}

function normalizeIndicatorSettings(value: Record<string, unknown>): IndicatorSettings {
  const settings = cloneIndicatorSettings(defaultIndicatorSettings)

  if (Array.isArray(value.enabled)) {
    settings.enabled = value.enabled.filter(
      (item): item is string => typeof item === 'string' && item !== 'VOLUME' && item !== 'VOL' && hasIndicatorOption(item)
    )
  }
  if (isRecord(value.indicators)) settings.indicators = normalizeIndicatorParameterSettings(value.indicators, settings.indicators)
  if (isRecord(value.movingAverage)) settings.movingAverage = normalizeMovingAverageSettings(value.movingAverage, settings.movingAverage)
  if (isRecord(value.exponentialMovingAverage)) {
    settings.exponentialMovingAverage = normalizeMovingAverageSettings(
      value.exponentialMovingAverage,
      settings.exponentialMovingAverage
    )
  }
  if (isRecord(value.weightedMovingAverage)) {
    settings.weightedMovingAverage = normalizeMovingAverageSettings(value.weightedMovingAverage, settings.weightedMovingAverage)
  }
  if (isRecord(value.volume)) settings.volume = normalizeVolumeSettings(value.volume, settings.volume)

  return settings
}

function normalizeIndicatorParameterSettings(
  value: Record<string, unknown>,
  fallback: Record<string, IndicatorParameterSettings>
): Record<string, IndicatorParameterSettings> {
  return Object.fromEntries(
    indicatorConfigOptions.map((option) => {
      const stored = value[option.value]
      return [
        option.value,
        isRecord(stored)
          ? {
              ...fallback[option.value],
              ...stored,
              calcParams: normalizeCalcParams(stored.calcParams, fallback[option.value].calcParams),
              lineType: stored.lineType === 'dashed' ? 'dashed' : 'solid',
              width: normalizePositiveNumber(stored.width, fallback[option.value].width),
              opacity: normalizePercent(stored.opacity, fallback[option.value].opacity)
            }
          : fallback[option.value]
      ]
    })
  )
}

function normalizeMovingAverageSettings(value: Record<string, unknown>, fallback: MovingAverageSettings): MovingAverageSettings {
  const lines = value.lines
  if (!Array.isArray(lines)) return fallback

  return {
    lines: fallback.lines.map((line, index) => {
      const nextLine = lines[index]
      return isRecord(nextLine) ? { ...line, ...nextLine } : line
    })
  }
}

function normalizeVolumeSettings(value: Record<string, unknown>, fallback: VolumeIndicatorSettings): VolumeIndicatorSettings {
  return {
    ...fallback,
    ...value,
    ma1: isRecord(value.ma1) ? { ...fallback.ma1, ...value.ma1 } : fallback.ma1,
    ma2: isRecord(value.ma2) ? { ...fallback.ma2, ...value.ma2 } : fallback.ma2
  }
}

function hasIndicatorOption(value: string) {
  return indicatorConfigOptions.some((option) => option.value === value)
}

function normalizePositiveNumber(value: unknown, fallback: number) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) && numberValue > 0 ? numberValue : fallback
}

function normalizePercent(value: unknown, fallback: number) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) ? Math.max(0, Math.min(100, numberValue)) : fallback
}

function normalizeBarSpace(value: unknown, fallback: number) {
  const numberValue = Number(value)
  if (!Number.isFinite(numberValue) || numberValue <= 0) return fallback
  return Math.max(3, Math.min(30, Math.round(numberValue)))
}

function normalizeFavoriteIntervals(value: unknown, fallback: TradingPeriod[]): TradingPeriod[] {
  if (!Array.isArray(value)) return [...fallback]

  const favorites = value.filter((item): item is TradingPeriod => isTradingPeriod(item))
  return Array.from(new Set(favorites))
}

function getDrawingToolGroupItems(values: DrawingTool[]): DrawingToolOption[] {
  return values
    .map((value) => drawingToolOptions.find((item) => item.value === value))
    .filter((item): item is DrawingToolOption => Boolean(item))
}

function normalizeMagnetMode(value: Record<string, unknown>): DrawingMagnetMode {
  if (isDrawingMagnetMode(value.magnetMode)) return value.magnetMode
  if (typeof value.magnet === 'boolean') return value.magnet ? 'weak' : 'none'
  return defaultChartSettings.drawingToolSettings.magnetMode
}

function colorWithOpacity(color: string, opacity: number) {
  const alpha = Math.max(0, Math.min(1, opacity / 100))
  const normalized = color.replace('#', '')
  if (!/^[0-9a-f]{6}$/i.test(normalized)) return color

  const red = Number.parseInt(normalized.slice(0, 2), 16)
  const green = Number.parseInt(normalized.slice(2, 4), 16)
  const blue = Number.parseInt(normalized.slice(4, 6), 16)
  return `rgba(${red}, ${green}, ${blue}, ${alpha})`
}

function getBrowserStorage(): ChartSettingsStorage | undefined {
  if (typeof window === 'undefined') return undefined
  return window.localStorage
}

function cloneSettings(settings: ChartSettings): ChartSettings {
  return JSON.parse(JSON.stringify(settings)) as ChartSettings
}

function isTradingPeriod(value: unknown): value is TradingPeriod {
  return typeof value === 'string' && allChartIntervals.some((item) => item.value === value)
}

function normalizeChartSymbol(symbol: string) {
  return symbol.trim().replace(/[-_/]/g, '').toUpperCase()
}

function isChartType(value: unknown): value is ChartType {
  return value === 'candle' || value === 'bar' || value === 'hlc' || value === 'line'
}

function isPriceScaleMode(value: unknown): value is PriceScaleMode {
  return value === 'normal' || value === 'percentage' || value === 'logarithm'
}

function isGridLineMode(value: unknown): value is GridLineMode {
  return value === 'none' || value === 'horizontal' || value === 'vertical' || value === 'both'
}

function isChartTooltipStyle(value: unknown): value is ChartTooltipStyle {
  return value === 'standard' || value === 'compact' || value === 'hidden'
}

function isChartTimezone(value: unknown): value is ChartTimezone {
  return chartTimezoneOptions.some((item) => item.value === value)
}

function isDrawingTool(value: unknown): value is DrawingTool {
  return typeof value === 'string' && drawingToolOptions.some((item) => item.value === value)
}

function isDrawingMagnetMode(value: unknown): value is DrawingMagnetMode {
  return value === 'none' || value === 'weak' || value === 'strong'
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
