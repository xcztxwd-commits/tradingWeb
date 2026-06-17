import type { ProductType, TradingCandle, TradingInstrumentRules, TradingMarket, TradingPeriod, TradingQuote } from './tradingModels'
import type { MarketDataSnapshot, TradeItem } from './marketDataTypes'

export type BackendSymbol = {
  symbol: string
  displayName: string
  assetClass: string
  productType?: ProductType | string | null
  baseCurrency: string
  quoteCurrency: string
  minLot?: string | number | null
  maxLot?: string | number | null
  leverage?: string | number | null
  tickSize?: string | number | null
  pricePrecision?: number | null
  quantityPrecision?: number | null
  enabled: boolean
  provider?: string | null
  providerSymbol?: string | null
  tradable?: boolean | null
  quoteEnabled?: boolean | null
  chartEnabled?: boolean | null
  orderBookEnabled?: boolean | null
  iconUrl?: string | null
  lastPrice?: string | number | null
  changePercent?: string | number | null
  high24h?: string | number | null
  low24h?: string | number | null
  volume24h?: string | number | null
  marketCap?: string | number | null
  spread?: string | number | null
  quoteTimestamp?: string | number | null
  quoteSource?: string | null
  rules?: BackendInstrumentRules | null
}

export type BackendInstrumentRules = {
  symbol: string
  exists: boolean
  enabled: boolean
  tradable: boolean
  quoteEnabled: boolean
  chartEnabled: boolean
  orderBookEnabled: boolean
  orderEnabled: boolean
  productType?: ProductType | string | null
  tickSize?: string | number | null
  stepSize?: string | number | null
  minQty?: string | number | null
  maxQty?: string | number | null
  minNotional?: string | number | null
  maxNotional?: string | number | null
  minLot?: string | number | null
  maxLot?: string | number | null
  maxLeverage?: string | number | null
  defaultLeverage?: string | number | null
  marginAsset?: string | null
  settlementAsset?: string | null
  contractSize?: string | number | null
  riskTier?: string | null
  tradingSession?: string | null
  kycRequirement?: string | null
  userRiskLevelRestriction?: string | null
}

export type BackendQuote = {
  type: 'quote'
  symbol: string
  bid: string
  ask: string
  mid: string
  spread: string
  source: string
  timestamp: number
  changePercent?: string | number | null
  high24h?: string | number | null
  low24h?: string | number | null
  volume24h?: string | number | null
}

export type BackendMarketStatus = {
  massiveConfigured: boolean
  redisCacheEnabled: boolean
  quoteStaleMs: number
  status: string
  demoQuotesEnabled?: boolean
  sourceMode?: string
  providerStatus?: string
  failureCode?: string | null
  failureReason?: string | null
}

export type BackendCandle = {
  timestamp: number
  open: string
  high: string
  low: string
  close: string
  volume: string
}

export type BackendOrderBookLevel = {
  price: string
  amount: string
}

export type BackendOrderBook = {
  symbol: string
  timestamp: number
  bids: BackendOrderBookLevel[]
  asks: BackendOrderBookLevel[]
}

export type BackendRecentTrade = {
  id: string
  symbol: string
  price: string
  amount: string
  side: 'buy' | 'sell'
  timestamp: number
}

export const tradingMarketEndpoints = {
  symbols: '/api/market/symbols',
  favorites: '/api/market/favorites',
  status: '/api/market/status',
  symbolRules: '/api/market/symbol-rules',
  candles: '/api/chart/candles',
  orderBook: '/api/market/order-book',
  recentTrades: '/api/market/trades'
} as const

const defaultMarketSymbolLimit = 2000

const periodIntervals: Record<TradingPeriod, number> = {
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

export function buildMarketQuotePath(symbol: string) {
  return `/api/market/quotes/${encodeURIComponent(symbol)}`
}

export function buildMarketQuotesPath(symbols: string[]) {
  const normalizedSymbols = Array.from(new Set(symbols.map(normalizeMarketSymbol).filter(Boolean)))
  const params = new URLSearchParams({ symbols: normalizedSymbols.join(',') })
  return `/api/market/quotes?${params.toString()}`
}

export function buildMarketSymbolsPath(limit = defaultMarketSymbolLimit) {
  const params = new URLSearchParams({ limit: String(limit) })
  return `${tradingMarketEndpoints.symbols}?${params.toString()}`
}

export function buildMarketSymbolRulesPath(symbol: string) {
  return `${tradingMarketEndpoints.symbols}/${encodeURIComponent(normalizeMarketSymbol(symbol))}/rules`
}

export function buildMarketSymbolRulesBatchPath(symbols: string[]) {
  const params = new URLSearchParams({ symbols: symbols.map(normalizeMarketSymbol).join(',') })
  return `${tradingMarketEndpoints.symbolRules}?${params.toString()}`
}

export function buildMarketFavoritesPath() {
  return tradingMarketEndpoints.favorites
}

export function buildMarketFavoritePath(symbol: string) {
  return `${tradingMarketEndpoints.favorites}/${encodeURIComponent(symbol.trim().toUpperCase())}`
}

export function buildMarketStatusPath() {
  return tradingMarketEndpoints.status
}

export function buildMarketOrderBookPath(symbol: string) {
  return `${tradingMarketEndpoints.orderBook}/${encodeURIComponent(symbol)}`
}

export function buildMarketRecentTradesPath(symbol: string, limit = 40) {
  const params = new URLSearchParams({ limit: String(limit) })
  return `${tradingMarketEndpoints.recentTrades}/${encodeURIComponent(symbol)}?${params.toString()}`
}

export function buildMarketCandlesPath(symbol: string, period: TradingPeriod, now: Date | number = new Date(), count = 220) {
  const interval = periodIntervals[period]
  const to = typeof now === 'number' ? new Date(now) : now
  const from = new Date(to.getTime() - interval * count)
  const params = new URLSearchParams({
    symbol,
    timeframe: period === 'time' ? '1m' : period,
    from: from.toISOString(),
    to: to.toISOString()
  })
  return `${tradingMarketEndpoints.candles}?${params.toString()}`
}

export function mapSymbolToTradingMarket(symbol: BackendSymbol): TradingMarket {
  const lastPrice = optionalNumber(symbol.lastPrice)
  const changePercent = optionalNumber(symbol.changePercent)
  const high24h = optionalNumber(symbol.high24h)
  const low24h = optionalNumber(symbol.low24h)
  const volume24h = optionalNumber(symbol.volume24h)
  const marketCap = optionalNumber(symbol.marketCap)
  const spread = optionalNumber(symbol.spread)
  const quoteTimestamp = optionalNumber(symbol.quoteTimestamp)

  return {
    symbol: symbol.symbol,
    base: symbol.baseCurrency,
    quote: symbol.quoteCurrency,
    name: symbol.displayName,
    category: mapAssetClass(symbol.assetClass),
    favorite: false,
    iconUrl: optionalText(symbol.iconUrl),
    last: lastPrice ?? 0,
    changePercent: changePercent ?? 0,
    volume: volume24h === undefined ? 'Live' : formatCompactVolume(volume24h),
    high24h: high24h ?? lastPrice ?? 0,
    low24h: low24h ?? lastPrice ?? 0,
    spread: spread ?? 0,
    source: symbol.quoteSource ?? symbol.provider ?? 'backend',
    productType: optionalProductType(symbol.productType),
    provider: symbol.provider ?? undefined,
    providerSymbol: symbol.providerSymbol ?? undefined,
    tradable: symbol.tradable ?? true,
    quoteEnabled: symbol.quoteEnabled ?? true,
    chartEnabled: symbol.chartEnabled ?? true,
    orderBookEnabled: symbol.orderBookEnabled ?? true,
    minLot: symbol.minLot == null ? undefined : String(symbol.minLot),
    leverage: optionalPositiveInteger(symbol.leverage),
    pricePrecision: getSymbolPricePrecision(symbol),
    quantityPrecision: getSymbolQuantityPrecision(symbol),
    marketCap,
    quoteTimestamp,
    rules: symbol.rules ? mapInstrumentRulesToTradingRules(symbol.rules) : undefined
  }
}

export function mapInstrumentRulesToTradingRules(rules: BackendInstrumentRules): TradingInstrumentRules {
  return {
    symbol: rules.symbol,
    exists: rules.exists,
    enabled: rules.enabled,
    tradable: rules.tradable,
    quoteEnabled: rules.quoteEnabled,
    chartEnabled: rules.chartEnabled,
    orderBookEnabled: rules.orderBookEnabled,
    orderEnabled: rules.orderEnabled,
    productType: optionalProductType(rules.productType),
    tickSize: optionalNumber(rules.tickSize),
    stepSize: optionalNumber(rules.stepSize),
    minQty: optionalNumber(rules.minQty),
    maxQty: optionalNumber(rules.maxQty),
    minNotional: optionalNumber(rules.minNotional),
    maxNotional: optionalNumber(rules.maxNotional),
    minLot: optionalNumber(rules.minLot),
    maxLot: optionalNumber(rules.maxLot),
    maxLeverage: optionalPositiveInteger(rules.maxLeverage),
    defaultLeverage: optionalPositiveInteger(rules.defaultLeverage),
    marginAsset: optionalText(rules.marginAsset),
    settlementAsset: optionalText(rules.settlementAsset),
    contractSize: optionalNumber(rules.contractSize),
    riskTier: optionalText(rules.riskTier),
    tradingSession: optionalText(rules.tradingSession),
    kycRequirement: optionalText(rules.kycRequirement),
    userRiskLevelRestriction: optionalText(rules.userRiskLevelRestriction)
  }
}

export function mapQuoteToTradingQuote(quote: BackendQuote, previous?: TradingQuote): TradingQuote {
  const mid = toNumber(quote.mid)
  const previousMid = previous?.mid ?? 0
  const providerHigh24h = optionalNumber(quote.high24h)
  const providerLow24h = optionalNumber(quote.low24h)
  const providerChangePercent = optionalNumber(quote.changePercent)
  const providerVolume24h = optionalNumber(quote.volume24h)
  const high24h = providerHigh24h ?? (previous ? Math.max(previous.high24h, mid) : mid)
  const low24h = providerLow24h ?? (previous && previous.low24h > 0 ? Math.min(previous.low24h, mid) : mid)

  return {
    symbol: quote.symbol,
    bid: toNumber(quote.bid),
    ask: toNumber(quote.ask),
    mid,
    spread: toNumber(quote.spread),
    changePercent: providerChangePercent ?? (previousMid > 0 ? ((mid - previousMid) / previousMid) * 100 : 0),
    high24h,
    low24h,
    volume: providerVolume24h === undefined ? previous?.volume ?? 'Live' : formatCompactVolume(providerVolume24h),
    source: quote.source,
    timestamp: quote.timestamp
  }
}

export function mapCandleToTradingCandle(candle: BackendCandle): TradingCandle {
  const close = toNumber(candle.close)
  const volume = toNumber(candle.volume)
  return {
    timestamp: candle.timestamp,
    open: toNumber(candle.open),
    high: toNumber(candle.high),
    low: toNumber(candle.low),
    close,
    volume,
    turnover: close * volume
  }
}

export function mapOrderBookToMarketData(
  orderBook: BackendOrderBook
): Pick<MarketDataSnapshot, 'bids' | 'asks' | 'lastPrice' | 'lastPriceDirection'> {
  const bids = orderBook.bids.map((level) => ({
    price: toNumber(level.price),
    amount: toNumber(level.amount)
  }))
  const asks = orderBook.asks.map((level) => ({
    price: toNumber(level.price),
    amount: toNumber(level.amount)
  }))
  const bestBid = bids[0]?.price ?? 0
  const bestAsk = asks[0]?.price ?? 0
  const lastPrice = roundNumber(bestBid > 0 && bestAsk > 0 ? (bestBid + bestAsk) / 2 : Math.max(bestBid, bestAsk))

  return {
    bids,
    asks,
    lastPrice,
    lastPriceDirection: 'flat'
  }
}

export function mapRecentTradesToMarketData(trades: BackendRecentTrade[]): TradeItem[] {
  return trades.map((trade) => ({
    id: trade.id,
    price: toNumber(trade.price),
    amount: toNumber(trade.amount),
    side: trade.side === 'buy' ? 'buy' : 'sell',
    time: trade.timestamp
  }))
}

export function createTradingMarketPlaceholder(symbol: string): TradingMarket {
  const quote = symbol.endsWith('JPY') ? 'JPY' : symbol.endsWith('USDT') ? 'USDT' : 'USD'
  const base = symbol.endsWith(quote) ? symbol.slice(0, -quote.length) : symbol
  const realtimeEnabled = symbol.endsWith('USDT')

  return {
    symbol,
    base,
    quote,
    name: symbol,
    category: symbol.endsWith('USDT') ? 'crypto' : 'fx',
    favorite: false,
    last: 0,
    changePercent: 0,
    volume: 'Live',
    high24h: 0,
    low24h: 0,
    spread: 0,
    source: 'backend',
    tradable: false,
    quoteEnabled: realtimeEnabled,
    chartEnabled: realtimeEnabled,
    orderBookEnabled: realtimeEnabled
  }
}

export function createTradingQuoteFromMarket(market: TradingMarket): TradingQuote {
  return {
    symbol: market.symbol,
    bid: market.last,
    ask: market.last,
    mid: market.last,
    spread: market.spread,
    changePercent: market.changePercent,
    high24h: market.high24h || market.last,
    low24h: market.low24h || market.last,
    volume: market.volume,
    source: market.source,
    timestamp: Date.now()
  }
}

export function mergeTradingQuoteIntoMarket(market: TradingMarket, quote: TradingQuote): TradingMarket {
  const nextMarketCap = quoteMarketCap(quote)
  return {
    ...market,
    last: quote.mid,
    changePercent: quote.changePercent,
    spread: quote.spread,
    high24h: quote.high24h,
    low24h: quote.low24h,
    volume: quote.volume,
    source: quote.source,
    marketCap: nextMarketCap ?? market.marketCap
  }
}

function mapAssetClass(assetClass: string): TradingMarket['category'] {
  const normalized = assetClass.toLowerCase()
  if (normalized.includes('crypto')) return 'crypto'
  if (normalized.includes('metal') || normalized.includes('commodity')) return 'metals'
  if (normalized.includes('index') || normalized.includes('indice')) return 'indices'
  return 'fx'
}

function getSymbolPricePrecision(symbol: BackendSymbol) {
  if (typeof symbol.pricePrecision === 'number') return symbol.pricePrecision
  return getDecimalPlaces(symbol.tickSize)
}

function getSymbolQuantityPrecision(symbol: BackendSymbol) {
  if (typeof symbol.quantityPrecision === 'number') return symbol.quantityPrecision
  return getDecimalPlaces(symbol.minLot)
}

function getDecimalPlaces(value: string | number | null | undefined) {
  if (value === null || value === undefined || value === '') return undefined
  const text = String(value)
  const decimals = text.includes('.') ? text.split('.')[1]?.replace(/0+$/, '').length ?? 0 : 0
  return decimals
}

function toNumber(value: string | number) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) ? numberValue : 0
}

function optionalNumber(value: string | number | null | undefined) {
  if (value === null || value === undefined || value === '') return undefined
  const numberValue = Number(value)
  return Number.isFinite(numberValue) ? numberValue : undefined
}

function optionalPositiveInteger(value: string | number | null | undefined) {
  const numberValue = optionalNumber(value)
  if (numberValue === undefined || numberValue <= 0) return undefined
  return Math.round(numberValue)
}

function optionalText(value: string | null | undefined) {
  const text = value?.trim()
  return text ? text : undefined
}

function optionalProductType(value: string | null | undefined): ProductType | undefined {
  if (
    value === 'FX_MARGIN' ||
    value === 'CRYPTO_SPOT' ||
    value === 'LINEAR_PERP' ||
    value === 'INVERSE_PERP'
  ) {
    return value
  }
  return undefined
}

function normalizeMarketSymbol(symbol: string) {
  return symbol.trim().toUpperCase().replace(/[-_/]/g, '')
}

function formatCompactVolume(value: number) {
  return new Intl.NumberFormat('en-US', {
    maximumFractionDigits: 2,
    notation: 'compact'
  }).format(value)
}

function quoteMarketCap(quote: TradingQuote) {
  const volume = parseCompactNumber(quote.volume)
  return quote.mid > 0 && volume > 0 ? quote.mid * volume * 8 : undefined
}

function parseCompactNumber(value: string) {
  const normalized = value.trim().toUpperCase()
  const number = Number(normalized.replace(/[^\d.]/g, ''))
  if (!Number.isFinite(number)) return 0
  if (normalized.endsWith('T')) return number * 1_000_000_000_000
  if (normalized.endsWith('B')) return number * 1_000_000_000
  if (normalized.endsWith('M')) return number * 1_000_000
  if (normalized.endsWith('K')) return number * 1_000
  return number
}

function roundNumber(value: number) {
  return Number(value.toFixed(10))
}
