type ChartDrawingStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>

export type PersistedDrawingPoint = {
  timestamp?: number
  dataIndex?: number
  value?: number
}

export type PersistedChartDrawing = {
  id?: string
  name: string
  groupId?: string
  points?: PersistedDrawingPoint[]
  styles?: Record<string, unknown> | null
  lock?: boolean
  visible?: boolean
  extendData?: unknown
}

const chartDrawingStoragePrefix = 'fx-trading-platform:chart-drawings:v1:'
const drawingOverlayGroupId = 'trading-page-drawings'

export function getChartDrawingStorageKey(symbol: string) {
  const normalizedSymbol = symbol.trim().toUpperCase() || 'DEFAULT'
  return `${chartDrawingStoragePrefix}${normalizedSymbol}`
}

export function loadPersistedChartDrawings(
  symbol: string,
  storage = getBrowserStorage()
): PersistedChartDrawing[] {
  if (!storage) return []
  try {
    const raw = storage.getItem(getChartDrawingStorageKey(symbol))
    if (!raw) return []
    const parsed = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed.map(toPersistedChartDrawing).filter((item): item is PersistedChartDrawing => Boolean(item))
  } catch {
    return []
  }
}

export function savePersistedChartDrawings(
  symbol: string,
  overlays: unknown[],
  storage = getBrowserStorage()
) {
  if (!storage) return
  const drawings = overlays.map(toPersistedChartDrawing).filter((item): item is PersistedChartDrawing => Boolean(item))
  const key = getChartDrawingStorageKey(symbol)
  if (drawings.length === 0) {
    storage.removeItem(key)
    return
  }
  storage.setItem(key, JSON.stringify(drawings))
}

export function clonePersistedDrawing(drawing: PersistedChartDrawing): PersistedChartDrawing {
  return {
    ...drawing,
    id: undefined,
    points: drawing.points?.map((point) => ({ ...point })),
    styles: cloneRecord(drawing.styles)
  }
}

export function buildRiskTemplateDrawings(lastPrice: number): PersistedChartDrawing[] {
  if (!Number.isFinite(lastPrice) || lastPrice <= 0) return []
  return [
    createRiskTemplateDrawing('Entry', lastPrice, '#fcd535'),
    createRiskTemplateDrawing('Take profit', roundPrice(lastPrice * 1.02), '#2ebd85'),
    createRiskTemplateDrawing('Stop loss', roundPrice(lastPrice * 0.99), '#f6465d')
  ]
}

function createRiskTemplateDrawing(label: string, price: number, color: string): PersistedChartDrawing {
  return {
    name: 'simpleTag',
    groupId: drawingOverlayGroupId,
    points: [{ value: price }, { value: price }],
    extendData: label,
    styles: {
      line: { color, size: 1, style: 'solid' },
      text: {
        color: '#050505',
        backgroundColor: color,
        borderColor: color
      }
    },
    lock: false,
    visible: true
  }
}

function toPersistedChartDrawing(value: unknown): PersistedChartDrawing | null {
  if (!isRecord(value) || typeof value.name !== 'string') return null
  const drawing: PersistedChartDrawing = {
    id: typeof value.id === 'string' ? value.id : undefined,
    name: value.name,
    groupId: typeof value.groupId === 'string' ? value.groupId : drawingOverlayGroupId,
    points: normalizeDrawingPoints(value.points),
    styles: cloneRecord(value.styles),
    lock: value.lock === true,
    visible: value.visible !== false
  }
  if (value.extendData !== undefined && isSerializableExtendData(value.extendData)) {
    drawing.extendData = value.extendData
  }
  return drawing
}

function normalizeDrawingPoints(value: unknown) {
  if (!Array.isArray(value)) return undefined
  const points = value.map((point) => {
    if (!isRecord(point)) return null
    const nextPoint: PersistedDrawingPoint = {}
    if (isFiniteNumber(point.timestamp)) nextPoint.timestamp = point.timestamp
    if (isFiniteNumber(point.dataIndex)) nextPoint.dataIndex = point.dataIndex
    if (isFiniteNumber(point.value)) nextPoint.value = point.value
    return Object.keys(nextPoint).length > 0 ? nextPoint : null
  }).filter((point): point is PersistedDrawingPoint => Boolean(point))
  return points.length > 0 ? points : undefined
}

function cloneRecord(value: unknown): Record<string, unknown> | null | undefined {
  if (!isRecord(value)) return undefined
  return JSON.parse(JSON.stringify(value)) as Record<string, unknown>
}

function isSerializableExtendData(value: unknown) {
  const valueType = typeof value
  return value == null || valueType === 'string' || valueType === 'number' || valueType === 'boolean'
}

function roundPrice(value: number) {
  return Math.round(value * 100_000_000) / 100_000_000
}

function isFiniteNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value)
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function getBrowserStorage(): ChartDrawingStorage | undefined {
  if (typeof window === 'undefined') return undefined
  return window.localStorage
}
