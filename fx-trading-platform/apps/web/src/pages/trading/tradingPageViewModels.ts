import type { ChartSettings, ChartType, DrawingMagnetMode, DrawingTool, IndicatorSettings } from './chartSettings'
import type { OrderResponse, PositionResponse } from '../../components/tables/types'
import type { TradingMarket, TradingPeriod, TradingQuote } from '../../features/market/tradingModels'
import type { TradingBalances } from '../../features/trading-session/tradingSession'
import type { TradingSessionMode } from '../../features/trading-session/useTradingSession'
import type {
  TradingDropSide,
  TradingLayout,
  TradingLayoutPreset,
  TradingPanelId,
  TradingSplitDirection
} from '../../stores/layoutStore'
import type { AccountSummary, LedgerEntry, OrderPayload } from '../../types/trading'

export type ChartThemeMode = 'dark' | 'light'

export type TradingChartCallbacks = {
  onChartTypeChange: (chartType: ChartType) => void
  onDrawingMagnetModeChange: (magnetMode: DrawingMagnetMode) => void
  onDrawingToolChange: (activeTool: DrawingTool) => void
  onIndicatorSettingsChange: (indicatorSettings: IndicatorSettings) => void
  onIndicatorToggle: (indicator: string) => void
  onPeriodChange: (interval: TradingPeriod) => void
}

export type TradingAccountPanelData = {
  account?: AccountSummary
  ledgerEntries: LedgerEntry[]
  loading: boolean
  orders: OrderResponse[]
  positions: PositionResponse[]
  positionHistory: PositionResponse[]
  sessionReady: boolean
  onClosePosition: (position: PositionResponse) => Promise<unknown> | void
}

export type TradingWorkspaceLayoutControls = {
  layout: TradingLayout
  activePreset: TradingLayoutPreset | null
  applyPreset: (preset: TradingLayoutPreset) => void
  beginSplitResize: (path: string, direction: TradingSplitDirection, containerSize: number) => void
  resizeByDelta: (delta: { deltaX: number; deltaY: number }) => void
  endResize: () => void
  movePanel: (sourceId: TradingPanelId, targetId: TradingPanelId, side: TradingDropSide) => void
  resetLayout: () => void
  resetSignal: number
}

export type TradingTerminalViewProps = {
  accountPanel: TradingAccountPanelData
  accountId?: string
  balances: TradingBalances
  chartCallbacks: TradingChartCallbacks
  chartSettings: ChartSettings
  chartThemeMode: ChartThemeMode
  chartTitle: string
  indicators: string[]
  loginRequired: boolean
  market: TradingMarket
  markets: TradingMarket[]
  onLoginRequired: () => void
  onOpenMarkets: () => void
  onOpenQuote: () => void
  onOpenSettings: () => void
  onOpenTrade: () => void
  onSelectPrice: (price: number) => void
  onRetrySession: () => Promise<void> | void
  onSelectSymbol: (symbol: string) => void
  quote: TradingQuote
  quotes: Record<string, TradingQuote>
  sessionError?: string | null
  sessionReady: boolean
  sessionStatusLabel: string
  sessionStatusText: string
  submitOrder: (payload: OrderPayload) => Promise<OrderResponse | void>
  symbol: string
  tradeMinOrderAmount: number
  tradePricePrecision: number
  tradeQuantityPrecision: number
  tradePricePrefill: { id: number; price: number } | null
  terminalLoading: boolean
  token: string | null
  tradePanelSessionMode: TradingSessionMode
  workspaceLayoutControls: TradingWorkspaceLayoutControls
}

export type TradingSettingsDialogProps = {
  layoutControls: TradingWorkspaceLayoutControls
  open: boolean
  onClose: () => void
}

export function shouldAllowChartMockFallback(market: TradingMarket) {
  return !market.provider && market.category !== 'fx'
}
