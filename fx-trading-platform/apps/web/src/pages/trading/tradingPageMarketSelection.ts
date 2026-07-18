import { defaultTradingSymbols, tradingProductSymbols, type TradingProduct } from '../../app/tradingRoutes.ts'
import type { TradingMarket } from '@fx-platform/frontend-core'

type Translate = (key: string, options?: Record<string, unknown>) => string

const defaultTranslate: Translate = (key) => {
  if (key === 'chart.titleSuffix') return 'K-line chart'
  return key
}

export const initialTradingSymbol = defaultTradingSymbols.spot

export function mergeWithLocalTradingMarkets(markets: TradingMarket[]) {
  const localMarkets = createLocalTradingMarkets()
  const merged = new Map(localMarkets.map((market) => [market.symbol, market]))
  markets.forEach((market) => {
    if (isP0TradingMarket(market)) merged.set(market.symbol, market)
  })
  return [...tradingProductSymbols.spot, ...tradingProductSymbols.perpetual]
    .map((symbol) => merged.get(symbol))
    .filter((market): market is TradingMarket => Boolean(market))
}

export function getTradingMarketsForProduct(markets: TradingMarket[], product: TradingProduct) {
  const marketBySymbol = new Map(markets.filter(isP0TradingMarket).map((market) => [market.symbol, market]))
  return tradingProductSymbols[product]
    .map((symbol) => marketBySymbol.get(symbol))
    .filter((market): market is TradingMarket => Boolean(market))
}

export function normalizeTradingSymbol(symbol: string | null) {
  const normalized = symbol?.trim().toUpperCase()
  return normalized || null
}

export function formatTradingChartTitle(market: TradingMarket, t: Translate = defaultTranslate) {
  return `${market.base}/${market.quote} ${t('chart.titleSuffix')}`
}

function createLocalTradingMarkets() {
  const spotMarkets = tradingProductSymbols.spot.map(createLocalSpotMarket)
  const perpetualMarkets = spotMarkets.map((market) => ({
    ...market,
    symbol: `${market.symbol}-PERP`,
    name: `${market.name} Perpetual`,
    productType: 'LINEAR_PERP' as const,
    leverage: 10,
    tradable: false
  }))
  return [...spotMarkets, ...perpetualMarkets]
}

function createLocalSpotMarket(symbol: string): TradingMarket {
  const base = symbol.slice(0, -'USDT'.length)
  return {
    symbol,
    base,
    quote: 'USDT',
    name: `${assetNames[base] ?? base} / Tether`,
    category: 'crypto',
    favorite: false,
    last: 0,
    changePercent: 0,
    volume: '0',
    high24h: 0,
    low24h: 0,
    spread: 0,
    source: 'metadata-only',
    productType: 'CRYPTO_SPOT',
    tradable: false,
    quoteEnabled: true,
    chartEnabled: true,
    orderBookEnabled: true
  }
}

const assetNames: Record<string, string> = {
  BTC: 'Bitcoin',
  ETH: 'Ethereum',
  BNB: 'BNB',
  SOL: 'Solana',
  XRP: 'XRP'
}

function isP0TradingMarket(market: TradingMarket) {
  if (tradingProductSymbols.spot.some((symbol) => symbol === market.symbol)) {
    return market.productType === undefined || market.productType === 'CRYPTO_SPOT'
  }
  if (tradingProductSymbols.perpetual.some((symbol) => symbol === market.symbol)) {
    return market.productType === undefined || market.productType === 'LINEAR_PERP'
  }
  return false
}
