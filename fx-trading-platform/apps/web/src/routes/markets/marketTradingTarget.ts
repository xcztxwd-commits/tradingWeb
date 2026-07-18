import {
  buildTradingPath,
  normalizeTradingProductSymbol,
  type TradingProduct
} from '../../app/tradingRoutes.ts'
import type { ProductType } from '@fx-platform/shared-types'

type MarketTradingCandidate = {
  symbol: string
  productType?: ProductType | null
}

export function resolveMarketTradingTarget(market: MarketTradingCandidate) {
  const spotSymbol = normalizeTradingProductSymbol('spot', market.symbol)
  if (spotSymbol && (market.productType === undefined || market.productType === null || market.productType === 'CRYPTO_SPOT')) {
    return buildTradingPath('spot', spotSymbol)
  }

  const perpetualSymbol = normalizeTradingProductSymbol('perpetual', market.symbol)
  if (perpetualSymbol && (market.productType === undefined || market.productType === null || market.productType === 'LINEAR_PERP')) {
    return buildTradingPath('perpetual', perpetualSymbol)
  }

  return null
}

export function isMarketTradingEnabled(market: MarketTradingCandidate, product?: TradingProduct) {
  const target = resolveMarketTradingTarget(market)
  return target !== null && (product === undefined || target.startsWith(`/trade/${product}/`))
}
