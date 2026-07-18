import type { OrderResponse, PositionResponse } from '@fx-platform/frontend-core'
import type { TradingPeriod } from '../../features/market/tradingModels'

export type ChartTradeMarkerKind = 'positionEntry' | 'takeProfit' | 'stopLoss' | 'liquidation' | 'order'
export type ChartTradeMarkerTone = 'buy' | 'sell' | 'profit' | 'loss' | 'risk' | 'neutral'

export type ChartTradeMarker = {
  id: string
  kind: ChartTradeMarkerKind
  label: string
  price: number
  timestamp?: number
  tone: ChartTradeMarkerTone
}

export type ChartTradeMarkerOverlay = {
  name: 'simpleTag' | 'simpleAnnotation'
  groupId: typeof tradeMarkerOverlayGroupId
  points: Array<{ timestamp?: number; value: number }>
  extendData: string
  lock: true
  visible: true
  styles: Record<string, unknown>
}

export const tradeMarkerOverlayGroupId = 'trading-page-trade-markers'

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

export function buildChartTradeMarkers(
  symbol: string,
  orders: OrderResponse[],
  positions: PositionResponse[]
): ChartTradeMarker[] {
  const normalizedSymbol = normalizeMarkerSymbol(symbol)
  const markers: ChartTradeMarker[] = []

  positions
    .filter((position) => normalizeMarkerSymbol(position.symbol) === normalizedSymbol)
    .forEach((position) => {
      const side = normalizeSide(position.side)
      addPriceMarker(markers, {
        id: `position:${position.id}:entry`,
        kind: 'positionEntry',
        label: `${side} Entry`,
        price: toPositiveNumber(position.openPrice),
        timestamp: parseTimestamp(position.openedAt),
        tone: side === 'SELL' ? 'sell' : 'buy'
      })
      addPriceMarker(markers, {
        id: `position:${position.id}:takeProfit`,
        kind: 'takeProfit',
        label: 'Take profit',
        price: toPositiveNumber(position.takeProfit),
        tone: 'profit'
      })
      addPriceMarker(markers, {
        id: `position:${position.id}:stopLoss`,
        kind: 'stopLoss',
        label: 'Stop loss',
        price: toPositiveNumber(position.stopLoss),
        tone: 'loss'
      })
      addPriceMarker(markers, {
        id: `position:${position.id}:liquidation`,
        kind: 'liquidation',
        label: 'Liquidation',
        price: toPositiveNumber(position.liquidationPrice),
        tone: 'risk'
      })
    })

  orders
    .filter((order) => normalizeMarkerSymbol(order.symbol) === normalizedSymbol)
    .forEach((order) => {
      const price = toPositiveNumber(order.price) ?? toPositiveNumber(order.avgFillPrice) ?? toPositiveNumber(order.executionPrice)
      addPriceMarker(markers, {
        id: `order:${order.id}`,
        kind: 'order',
        label: `${normalizeSide(order.side)} ${order.orderType}`,
        price,
        timestamp: parseTimestamp(order.filledAt) ?? parseTimestamp(order.createdAt),
        tone: normalizeSide(order.side) === 'SELL' ? 'sell' : 'buy'
      })
    })

  return markers
}

export function buildTradeMarkerOverlays(
  markers: ChartTradeMarker[],
  period: TradingPeriod
): ChartTradeMarkerOverlay[] {
  const span = periodMilliseconds[period] ?? 60_000
  return markers.flatMap((marker) => {
    const points = marker.timestamp
      ? [
          { timestamp: marker.timestamp, value: marker.price },
          { timestamp: marker.timestamp + span, value: marker.price }
        ]
      : [{ value: marker.price }, { value: marker.price }]
    const baseOverlay: ChartTradeMarkerOverlay = {
      name: 'simpleTag',
      groupId: tradeMarkerOverlayGroupId,
      points,
      extendData: marker.label,
      lock: true,
      visible: true,
      styles: createTradeMarkerStyle(marker.tone)
    }

    if (!marker.timestamp || (marker.kind !== 'positionEntry' && marker.kind !== 'order')) {
      return [baseOverlay]
    }

    return [
      baseOverlay,
      {
        ...baseOverlay,
        name: 'simpleAnnotation',
        points,
        extendData: marker.label
      }
    ]
  })
}

function addPriceMarker(markers: ChartTradeMarker[], marker: Omit<ChartTradeMarker, 'price'> & { price: number | null }) {
  if (marker.price === null) return
  markers.push({ ...marker, price: marker.price })
}

function normalizeMarkerSymbol(symbol: string) {
  return symbol.replace(/[^a-z0-9]/gi, '').toUpperCase()
}

function normalizeSide(side: string) {
  return side.toUpperCase() === 'SELL' ? 'SELL' : 'BUY'
}

function parseTimestamp(value: string | null | undefined) {
  if (!value) return undefined
  const timestamp = Date.parse(value)
  return Number.isFinite(timestamp) ? timestamp : undefined
}

function toPositiveNumber(value: unknown) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) && numberValue > 0 ? numberValue : null
}

function createTradeMarkerStyle(tone: ChartTradeMarkerTone) {
  const color = markerColor(tone)
  return {
    line: {
      color,
      size: 1,
      style: tone === 'risk' ? 'dashed' : 'solid'
    },
    text: {
      color: '#050505',
      backgroundColor: color,
      borderColor: color
    }
  }
}

function markerColor(tone: ChartTradeMarkerTone) {
  if (tone === 'sell' || tone === 'loss' || tone === 'risk') return '#f6465d'
  if (tone === 'profit' || tone === 'buy') return '#2ebd85'
  return '#fcd535'
}
