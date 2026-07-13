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
import type { OcoOrderPayload } from '../../types/trading'
import type { AccountTransferResponse, BatchActionResponse, FundingSettlement, OcoOrderGroupResponse, Trade } from '@fx-platform/shared-types'
import type { TradingMarketDataStatusView } from './tradingPageMarketDataStatus'
import type { PerpetualTradingControlsModel } from './usePerpetualTradingControls'
import type { TradingProduct } from '../../app/tradingRoutes'

export type ChartThemeMode = 'dark' | 'light'

export type TradingChartCallbacks = {
  onChartSettingsChange: (settings: ChartSettings) => void
  onResetChartSettings: () => void
  onChartTypeChange: (chartType: ChartType) => void
  onHighLowPriceMarksChange: (enabled: boolean) => void
  onPriceScaleModeChange: (priceScaleMode: ChartSettings['axisSettings']['priceScaleMode']) => void
  onDrawingMagnetModeChange: (magnetMode: DrawingMagnetMode) => void
  onDrawingToolChange: (activeTool: DrawingTool) => void
  onIndicatorSettingsChange: (indicatorSettings: IndicatorSettings) => void
  onIndicatorToggle: (indicator: string) => void
  onFavoriteIntervalToggle: (interval: TradingPeriod) => void
  onPeriodChange: (interval: TradingPeriod) => void
}

export type TradingAccountPanelData = {
  account?: AccountSummary
  ledgerEntries: LedgerEntry[]
  loading: boolean
  orders: OrderResponse[]
  trades: Trade[]
  positions: PositionResponse[]
  positionHistory: PositionResponse[]
  fundingSettlements: FundingSettlement[]
  transfers: AccountTransferResponse[]
  sessionReady: boolean
  onClosePosition: (position: PositionResponse) => Promise<unknown> | void
  onCancelAllOrders: () => Promise<BatchActionResponse>
  onCloseAllPositions: () => Promise<BatchActionResponse>
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
  favorites: Set<string>
  marketDataStatusView: TradingMarketDataStatusView
  onLoginRequired: () => void
  onOpenMarkets: () => void
  onOpenQuote: () => void
  onOpenTrade: () => void
  onSelectPrice: (price: number) => void
  onRetrySession: () => Promise<void> | void
  onSelectSymbol: (symbol: string) => void
  onFavorite: (symbol: string) => void
  product: TradingProduct
  quote: TradingQuote
  quotes: Record<string, TradingQuote>
  sessionError?: string | null
  sessionReady: boolean
  sessionStatusLabel: string
  sessionStatusText: string
  submitOrder: (payload: OrderPayload) => Promise<OrderResponse | void>
  submitOco: (payload: OcoOrderPayload) => Promise<OcoOrderGroupResponse | void>
  perpetualControls: PerpetualTradingControlsModel
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
