import { parseSymbolAssets, type TradeMarket } from '@fx-platform/frontend-core'

type Snapshot = {
  symbol?: string
  bids: Array<{ price: number }>
  asks: Array<{ price: number }>
  lastPrice: number
  updatedAt?: number
  tradable?: boolean
}

type MarketProfile = {
  category?: 'fx' | 'crypto' | 'metals' | 'indices'
  leverage?: number
  productType?: TradeMarket['productType']
  rules?: TradeMarket['rules']
}

export function createPanelMarket(symbol: string, snapshot: Snapshot, profile: MarketProfile = {}): TradeMarket {
  const { baseAsset, quoteAsset } = parseSymbolAssets(symbol)
  const normalizedSymbol = normalizePlatformSymbol(symbol)

  const marketDataReady = snapshot.tradable === true && normalizePlatformSymbol(snapshot.symbol ?? '') === normalizedSymbol
  const bestBid = marketDataReady ? snapshot.bids[0]?.price ?? 0 : 0
  const bestAsk = marketDataReady ? snapshot.asks[0]?.price ?? 0 : 0
  const lastPrice = marketDataReady ? snapshot.lastPrice || (bestBid > 0 && bestAsk > 0 ? (bestBid + bestAsk) / 2 : 0) : 0
  const leverage = resolveMarketLeverage(profile.leverage)
  const productType = profile.productType

  return {
    symbol: normalizedSymbol,
    lastPrice,
    bestBid,
    bestAsk,
    baseAsset,
    quoteAsset,
    unitSize: resolveUnitSize(normalizedSymbol, profile.category, productType, profile.rules),
    quantityMode: resolveQuantityMode(productType, profile.category),
    leverage,
    productType,
    quoteTimestamp: snapshot.updatedAt,
    tradable: marketDataReady,
    rules: profile.rules
  }
}

export function resolveUnitSize(
  symbol: string,
  category?: MarketProfile['category'],
  productType?: TradeMarket['productType'],
  rules?: TradeMarket['rules']
) {
  if (rules?.contractSize && rules.contractSize > 0) {
    return rules.contractSize * (rules.contractMultiplier && rules.contractMultiplier > 0 ? rules.contractMultiplier : 1)
  }
  if (productType === 'FX_MARGIN' || category === 'fx' || isForexSymbol(symbol)) return 100_000
  return 1
}

export function resolveQuantityMode(
  productType?: TradeMarket['productType'],
  category?: MarketProfile['category']
): TradeMarket['quantityMode'] {
  if (productType === 'CRYPTO_SPOT') return 'quote-budget'
  if (productType === 'INVERSE_PERP') return 'contracts'
  if (productType === 'FX_MARGIN' || productType === 'LINEAR_PERP') return 'quantity'
  return category === 'crypto' ? 'quote-budget' : 'quantity'
}

function resolveMarketLeverage(leverage?: number) {
  if (leverage === undefined || !Number.isFinite(leverage) || leverage <= 0) return undefined
  return Math.round(leverage)
}

function isForexSymbol(symbol: string) {
  return /^[A-Z]{6}$/.test(symbol) && !symbol.endsWith('USDT')
}

function normalizePlatformSymbol(symbol: string) {
  const normalized = symbol.trim().toUpperCase()
  if (normalized.endsWith('-PERP')) return normalized
  return normalized.replace(/[-_/]/g, '')
}
