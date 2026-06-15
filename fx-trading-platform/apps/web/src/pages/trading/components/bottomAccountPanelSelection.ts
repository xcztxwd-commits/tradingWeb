import type { OrderResponse, PositionResponse } from '../../../components/tables/types'
import type { BottomAccountTabView } from './bottomAccountPanelData'

type Translate = (key: string, options?: Record<string, unknown>) => string

const defaultTranslate: Translate = (key) => key

export type AccountPanelSelection = {
  canFilterCurrentSymbol: boolean
  orders: OrderResponse[]
  orderEmptyLabel: string
  positions: PositionResponse[]
  positionEmptyLabel: string
}

export function resolveAccountPanelSelection(
  activeView: BottomAccountTabView,
  onlyCurrentSymbol: boolean,
  currentSymbol?: string,
  t: Translate = defaultTranslate
): AccountPanelSelection {
  const canFilterCurrentSymbol = (activeView.kind === 'orders' || activeView.kind === 'positions') && Boolean(currentSymbol)

  return {
    canFilterCurrentSymbol,
    orders: resolveOrders(activeView, onlyCurrentSymbol, currentSymbol),
    orderEmptyLabel: resolveOrderEmptyLabel(activeView, onlyCurrentSymbol, currentSymbol, t),
    positions: resolvePositions(activeView, onlyCurrentSymbol, currentSymbol),
    positionEmptyLabel: resolvePositionEmptyLabel(activeView, onlyCurrentSymbol, currentSymbol, t)
  }
}

export function mergePositions(current?: PositionResponse[], history?: PositionResponse[]) {
  if (current === undefined && history === undefined) return undefined

  const seen = new Set<string>()
  return [...(current ?? []), ...(history ?? [])].filter((position) => {
    if (seen.has(position.id)) return false
    seen.add(position.id)
    return true
  })
}

function resolveOrders(activeView: BottomAccountTabView, onlyCurrentSymbol: boolean, currentSymbol?: string) {
  if (activeView.kind !== 'orders') return []
  if (!onlyCurrentSymbol || !currentSymbol) return activeView.orders
  return activeView.orders.filter((order) => normalizeSymbol(order.symbol) === normalizeSymbol(currentSymbol))
}

function resolveOrderEmptyLabel(
  activeView: BottomAccountTabView,
  onlyCurrentSymbol: boolean,
  currentSymbol: string | undefined,
  t: Translate
) {
  if (activeView.kind !== 'orders') return ''
  return onlyCurrentSymbol && currentSymbol ? t('trading.emptyCurrentSymbolOrders', { symbol: currentSymbol }) : activeView.emptyLabel
}

function resolvePositions(activeView: BottomAccountTabView, onlyCurrentSymbol: boolean, currentSymbol?: string) {
  if (activeView.kind !== 'positions') return []
  if (!onlyCurrentSymbol || !currentSymbol) return activeView.positions
  return activeView.positions.filter((position) => normalizeSymbol(position.symbol) === normalizeSymbol(currentSymbol))
}

function resolvePositionEmptyLabel(
  activeView: BottomAccountTabView,
  onlyCurrentSymbol: boolean,
  currentSymbol: string | undefined,
  t: Translate
) {
  if (activeView.kind !== 'positions') return ''
  return onlyCurrentSymbol && currentSymbol ? t('trading.emptyCurrentSymbolPositions', { symbol: currentSymbol }) : activeView.emptyLabel
}

function normalizeSymbol(symbol: string) {
  return symbol.replace(/[-_/]/g, '').toUpperCase()
}
