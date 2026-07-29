import type {
  MarginMode,
  PositionMode,
  ProductType,
  ScenarioValidationIssue,
  TimelineAction,
  TradingLabInstrumentConfig,
} from '../model/types.ts'
import type { Decimal } from './decimal.ts'

export type SpotInstrumentTick = Readonly<{
  productType: 'CRYPTO_SPOT'
  symbol: string
  bid: string
  ask: string
  last: string
}>

export type PerpetualInstrumentTick = Readonly<{
  productType: 'LINEAR_PERP'
  symbol: string
  bid: string
  ask: string
  last: string
  mark: string
  index: string
}>

export type InstrumentTick = SpotInstrumentTick | PerpetualInstrumentTick

export type FundingRateTick = Readonly<{
  symbol: string
  rate: string
}>

export type MarketTick = Readonly<{
  sequence: number
  virtualTime: string
  instruments: readonly InstrumentTick[]
  fundingRates: readonly FundingRateTick[]
}>

export type OracleMarketInput = Readonly<{
  virtualStart: string
  ticks: readonly MarketTick[]
}>

export type LocalOracleIssue = Readonly<{
  path: string
  code: string
  message: string
}>

export type LocalOracleWarning = Readonly<{
  code: string
  message: string
}>

export type LocalOrderResult = Readonly<{
  id: string
  actionId: string
  symbol: string
  productType: ProductType
  side: 'BUY' | 'SELL'
  status: 'FILLED' | 'REJECTED'
  quantity: string
  price: string
  feeUsdt: string
}>

export type LocalTradeResult = Readonly<{
  id: string
  orderId: string
  actionId: string
  symbol: string
  productType: ProductType
  side: 'BUY' | 'SELL'
  quantity: string
  price: string
  notionalUsdt: string
  feeUsdt: string
}>

export type SpotPositionResult = Readonly<{
  symbol: string
  baseAsset: string
  quoteAsset: string
  status: 'OPEN' | 'CLOSED'
  quantity: string
  averageCost: string | null
  grossQuoteCost: string
  feeCostUsdt: string
  netInvestedUsdt: string
  breakEvenPrice: string | null
  realizedGrossPnl: string
  realizedNetPnl: string
}>

export type PerpetualPositionResult = Readonly<{
  symbol: string
  positionSide: 'BOTH' | 'LONG' | 'SHORT'
  direction: 'LONG' | 'SHORT'
  marginMode: MarginMode
  status: 'OPEN' | 'CLOSED' | 'LIQUIDATED'
  quantity: string
  entryPrice: string
  markPrice: string
  leverage: number
  initialMargin: string
  isolatedMargin: string
  maintenanceMargin: string
  unrealizedGrossPnl: string
  realizedGrossPnl: string
  tradingFeeUsdt: string
  fundingPnlUsdt: string
  liquidationFeeUsdt: string
  realizedNetPnl: string
  estimatedLiquidationPrice: string | null
}>

export type WalletResult = Readonly<{
  asset: string
  available: string
  locked: string
  total: string
}>

export type AccountSummaryResult = Readonly<{
  totalWalletBalanceUsdt: string
  availableBalanceUsdt: string
  totalUnrealizedPnlUsdt: string
  equityUsdt: string
  totalMaintenanceMarginUsdt: string
}>

export type LedgerProjectionResult = Readonly<{
  actionId: string
  asset: string
  type: string
  amount: string
  balanceAfter: string
}>

export type RiskResult = Readonly<{
  equityUsdt: string
  availableBalanceUsdt: string
  maintenanceMarginUsdt: string
  marginRatio: string | null
  liquidationTriggered: boolean
}>

export type LocalActionSnapshot = Readonly<{
  actionId: string
  tickSequence: number
  virtualTime: string
  orders: readonly LocalOrderResult[]
  trades: readonly LocalTradeResult[]
  spotPositions: readonly SpotPositionResult[]
  perpetualPositions: readonly PerpetualPositionResult[]
  wallets: readonly WalletResult[]
  accountSummary: AccountSummaryResult
  ledgerProjection: readonly LedgerProjectionResult[]
  risk: RiskResult
  warnings: readonly LocalOracleWarning[]
}>

export type LocalCalculationResult = Readonly<{
  modelVersion: string
  configSnapshotHash: string
  assumptions: readonly string[]
  snapshots: readonly LocalActionSnapshot[]
}>

export type OracleCalculationOutcome =
  | Readonly<{
      status: 'CALCULATED'
      result: LocalCalculationResult
      runnerIssues: readonly ScenarioValidationIssue[]
    }>
  | Readonly<{
      status: 'BLOCKED'
      issues: readonly LocalOracleIssue[]
      runnerIssues: readonly ScenarioValidationIssue[]
    }>

export type CompiledAction = Readonly<{
  action: TimelineAction
  tickSequence: number
}>

export type SpotPositionState = {
  instrument: TradingLabInstrumentConfig
  quantity: Decimal
  grossQuoteCost: Decimal
  feeCostUsdt: Decimal
  netInvestedUsdt: Decimal
  realizedGrossPnl: Decimal
  realizedNetPnl: Decimal
}

export type PerpetualPositionState = {
  instrument: TradingLabInstrumentConfig
  positionSide: 'BOTH' | 'LONG' | 'SHORT'
  direction: 'LONG' | 'SHORT'
  marginMode: MarginMode
  status: 'OPEN' | 'CLOSED' | 'LIQUIDATED'
  quantity: Decimal
  entryPrice: Decimal
  markPrice: Decimal
  leverage: number
  initialMargin: Decimal
  isolatedMargin: Decimal
  maintenanceMargin: Decimal
  unrealizedGrossPnl: Decimal
  realizedGrossPnl: Decimal
  tradingFeeUsdt: Decimal
  fundingPnlUsdt: Decimal
  isolatedFundingPendingUsdt: Decimal
  liquidationFeeUsdt: Decimal
  realizedNetPnl: Decimal
  estimatedLiquidationPrice: Decimal | null
}

export type InstrumentSettingsState = {
  positionMode: PositionMode
  marginMode: MarginMode
  leverage: number
}

export type MutableOracleState = {
  wallets: Map<string, Decimal>
  spotPositions: Map<string, SpotPositionState>
  perpetualPositions: Map<string, PerpetualPositionState>
  settings: Map<string, InstrumentSettingsState>
  orders: LocalOrderResult[]
  trades: LocalTradeResult[]
  ledger: LedgerProjectionResult[]
  warnings: LocalOracleWarning[]
  assumptions: Set<string>
  liquidationTriggered: boolean
}

export class OracleExecutionError extends Error {
  readonly issue: LocalOracleIssue

  constructor(issue: LocalOracleIssue) {
    super(issue.message)
    this.name = 'OracleExecutionError'
    this.issue = issue
  }
}
