export type ProductType = 'CRYPTO_SPOT' | 'LINEAR_PERP'

export type PriceType = 'BID' | 'ASK' | 'LAST' | 'MARK' | 'INDEX'

export type PositionMode = 'ONE_WAY' | 'HEDGE'

export type MarginMode = 'CROSS' | 'ISOLATED'

export type MatchingMode = 'SIMPLE' | 'DEPTH'

export type InitialBalances = Record<string, string>

export type ScenarioDefaults = {
  positionMode: PositionMode
  marginMode: MarginMode
  leverage: number
}

export type ScenarioSymbol = {
  symbol: string
  productType: ProductType
}

export type TradingLabExecutionPolicy = {
  matchingMode: MatchingMode
  makerFeeRate: string
  takerFeeRate: string
  liquidationFeeRate: string
  slippageRate: string
  maxFillQuantityPerTick: string | null
}

export type TradingLabInstrumentConfig = {
  symbol: string
  productType: ProductType
  baseAsset: string
  quoteAsset: string
  tickSize: string
  stepSize: string
  pricePrecision: number
  quantityPrecision: number
  minQty: string
  maxQty: string
  minNotional: string | null
  maxNotional: string | null
  initialMarginRate: string
  maintenanceMarginRate: string
  liquidationFeeRate: string
  fixedFundingRate: string
  fixedFundingIntervalMinutes: number
  markPriceSource: string
  contractSize: string
  maxLeverage: number
  defaultLeverage: number
  marginAsset: string
  settlementAsset: string
  riskTier: string
}

export type TradingLabConfigSnapshot = {
  modelVersion: string
  symbolConfigVersion: string
  codeVersion: string
  executionPolicy: TradingLabExecutionPolicy
  instruments: TradingLabInstrumentConfig[]
}

export type PricePathSegment = Readonly<{
  target: string
  durationSeconds: number
  offsetRangeSteps: number
  volatilitySteps: number
  maxStepPerSecond: number
}>

export type ScalarPricePath = Readonly<{
  start: string
  segments: readonly PricePathSegment[]
}>

export type SimpleInstrumentPath = Readonly<{
  mode: 'SIMPLE'
  productType: ProductType
  symbol: string
  seed: string
  last: ScalarPricePath
  spreadSteps: number
  indexOffsetSteps: number
  basisSteps: number
  fundingRate?: string
}>

export type AdvancedSpotPath = Readonly<{
  mode: 'ADVANCED'
  productType: 'CRYPTO_SPOT'
  symbol: string
  seed: string
  prices: Readonly<{
    bid: ScalarPricePath
    ask: ScalarPricePath
    last: ScalarPricePath
  }>
}>

export type AdvancedPerpetualPath = Readonly<{
  mode: 'ADVANCED'
  productType: 'LINEAR_PERP'
  symbol: string
  seed: string
  fundingRate: string
  prices: Readonly<{
    bid: ScalarPricePath
    ask: ScalarPricePath
    last: ScalarPricePath
    mark: ScalarPricePath
    index: ScalarPricePath
  }>
}>

export type MarketPathDefinition = Readonly<{
  virtualStart: string
  realistic: boolean
  instruments: readonly (
    | SimpleInstrumentPath
    | AdvancedSpotPath
    | AdvancedPerpetualPath
  )[]
}>

export type ExpectedActionError = {
  status: number
  code: string
}

export type ValidationPublicActionType =
  | 'PLACE_ORDER'
  | 'CANCEL_ORDER'
  | 'CANCEL_ALL'
  | 'SET_POSITION_MODE'
  | 'SET_MARGIN_MODE'
  | 'SET_LEVERAGE'

export type LocalOracleActionType =
  | 'ADD_MARGIN'
  | 'REMOVE_MARGIN'
  | 'APPLY_FUNDING'
  | 'PLACE_OCO'

export type TimelineActionType =
  | ValidationPublicActionType
  | LocalOracleActionType

export type TimelineTrigger =
  | { type: 'VIRTUAL_TIME'; atSecond: number }
  | {
      type: 'PRICE'
      priceType: PriceType
      operator: 'GTE' | 'LTE'
      value: string
    }
  | {
      type: 'AFTER_ACTION'
      actionId: string
      delaySeconds: number
    }
  | {
      type: 'GROUP'
      operator: 'ALL' | 'ANY'
      items: TimelineTrigger[]
    }

export type TimelineAction = {
  id: string
  sequence: number
  type: TimelineActionType
  symbol: string
  productType: ProductType
  trigger: TimelineTrigger
  parameters: Record<string, unknown>
  overrides?: Record<string, unknown>
  expectedError?: ExpectedActionError
}

export type TradingLabScenario = {
  id: string
  name: string
  description: string
  negativeMode: boolean
  seed: string
  modelVersion: string
  configSnapshot: TradingLabConfigSnapshot
  configSnapshotHash: string
  executionPolicy: TradingLabExecutionPolicy
  marketPath: MarketPathDefinition
  initialBalances: InitialBalances
  defaults: ScenarioDefaults
  symbols: ScenarioSymbol[]
  timeline: TimelineAction[]
}

export type ScenarioValidationIssue = {
  path: string
  code: string
  message: string
  severity: 'ERROR' | 'WARNING'
}
