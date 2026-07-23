import type { ChartSettings, ChartType, DrawingMagnetMode, DrawingTool, IndicatorSettings } from '../../shared-widgets/trading/chartSettings'
import type { AccountSummary, LedgerEntry, OcoOrderPayload, OrderPayload, OrderResponse, PositionResponse } from '@fx-platform/frontend-core'
import type { TradingMarket, TradingPeriod, TradingQuote } from '@fx-platform/frontend-core'
import type { TradingBalances, TradingSessionMode } from '@fx-platform/frontend-core'
import type { AccountTransferResponse, BatchActionResponse, FundingSettlement, OcoOrderGroupResponse, Trade } from '@fx-platform/shared-types'
import type { TradingMarketDataStatusView } from '../../shared-widgets/trading/tradingPageMarketDataStatus'
import type { PerpetualTradingControlsModel } from '../../shared-widgets/trading/usePerpetualTradingControls'
import type { TradePanelControllerModel } from '../../shared-widgets/trading/order-form/useTradePanelController'
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

export type TradingRouteModel = {
  accountPanel: TradingAccountPanelData
  accountId?: string
  balances: TradingBalances
  chartCallbacks: TradingChartCallbacks
  chartSettings: ChartSettings
  chartThemeMode: ChartThemeMode
  controllerSentinel: string
  indicators: string[]
  loginRequired: boolean
  marketDrawerOpen: boolean
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
  closeMarketDrawer: () => void
  closeOrderSheet: () => void
  closeQuoteDrawer: () => void
  orderSheetOpen: boolean
  product: TradingProduct
  quote: TradingQuote
  quotes: Record<string, TradingQuote>
  quoteDrawerOpen: boolean
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
  tradePanel: TradePanelControllerModel
  tradePanelSessionMode: TradingSessionMode
}
