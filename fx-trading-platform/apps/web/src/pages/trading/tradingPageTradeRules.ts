import { getPricePrecision, type TradingInstrumentRules, type TradingMarket } from '@fx-platform/frontend-core'

export function mergeMarketRules(market: TradingMarket, rules: TradingInstrumentRules | null): TradingMarket {
  if (!rules || rules.symbol !== market.symbol) return market
  return {
    ...market,
    productType: rules.productType ?? market.productType,
    tradable: rules.tradable,
    quoteEnabled: rules.quoteEnabled,
    chartEnabled: rules.chartEnabled,
    orderBookEnabled: rules.orderBookEnabled,
    minLot: rules.minQty ? String(rules.minQty) : market.minLot,
    leverage: rules.maxLeverage ?? market.leverage,
    rules
  }
}

export function getTradeMinOrderAmount(market: TradingMarket) {
  const ruleValue = market.rules?.minQty ?? market.rules?.minLot
  if (ruleValue && Number.isFinite(ruleValue) && ruleValue > 0) return ruleValue
  const value = Number(market.minLot)
  if (Number.isFinite(value) && value > 0) return value
  if (market.symbol.includes('BTC') || market.symbol.includes('ETH')) return 0.0001
  return 0.01
}

export function getTradePricePrecision(market: TradingMarket) {
  return getStepPrecision(market.rules?.tickSize) ?? market.pricePrecision ?? getPricePrecision(market.symbol)
}

export function getTradeQuantityPrecision(market: TradingMarket, minOrderAmount: number) {
  return getStepPrecision(market.rules?.stepSize) ?? market.quantityPrecision ?? getQuantityPrecision(minOrderAmount, market.symbol)
}

function getQuantityPrecision(minOrderAmount: number, symbol: string) {
  const text = String(minOrderAmount)
  if (text.includes('.')) return text.split('.')[1]?.replace(/0+$/, '').length ?? 0
  return symbol.includes('BTC') || symbol.includes('ETH') ? 6 : 2
}

function getStepPrecision(step: number | undefined) {
  if (!step || !Number.isFinite(step) || step <= 0) return undefined
  const text = String(step)
  return text.includes('.') ? text.split('.')[1]?.replace(/0+$/, '').length ?? 0 : 0
}
