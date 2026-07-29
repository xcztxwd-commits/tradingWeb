import { apiGet, apiPut } from '../api/apiClient.ts'
import { favoriteSymbolList } from './marketFavorites.ts'
import {
  buildMarketCandlesPath,
  buildMarketFavoritePath,
  buildMarketFavoritesPath,
  buildMarketOrderBookPath,
  buildMarketQuotePath,
  buildMarketQuotesPath,
  buildMarketRecentTradesPath,
  buildMarketSymbolRulesBatchPath,
  buildMarketSymbolRulesPath,
  buildMarketStatusPath,
  buildMarketSymbolsPath,
  mapCandleToTradingCandle,
  mapInstrumentRulesToTradingRules,
  mapOrderBookToMarketData,
  mapQuoteToTradingQuote,
  mapRecentTradeBatchToMarketData,
  mapRecentTradesToMarketData,
  mapSymbolToTradingMarket,
  tradingMarketEndpoints
} from './tradingMarketAdapters.ts'
import type { BackendCandle, BackendOrderBook, BackendQuote, BackendRecentTrade, BackendSymbol } from './tradingMarketAdapters.ts'
import type { BackendInstrumentRules, BackendMarketStatus } from './tradingMarketAdapters.ts'
import type { TradingInstrumentRules, TradingPeriod, TradingQuote } from './tradingModels.ts'

type FetchMarketCandlesOptions = {
  endTime?: Date | number
  count?: number
}

export {
  buildMarketCandlesPath,
  buildMarketFavoritePath,
  buildMarketFavoritesPath,
  buildMarketOrderBookPath,
  buildMarketQuotePath,
  buildMarketQuotesPath,
  buildMarketRecentTradesPath,
  buildMarketSymbolRulesBatchPath,
  buildMarketSymbolRulesPath,
  buildMarketStatusPath,
  buildMarketSymbolsPath,
  mapInstrumentRulesToTradingRules,
  tradingMarketEndpoints
}

export function fetchMarketSymbols(limit?: number) {
  return apiGet<BackendSymbol[]>(buildMarketSymbolsPath(limit)).then((symbols) =>
    symbols.filter((symbol) => symbol.enabled).map(mapSymbolToTradingMarket)
  )
}

export function fetchMarketFavorites(token?: string | null) {
  return apiGet<string[]>(buildMarketFavoritesPath(), token ?? undefined).then(favoriteSymbolList)
}

export function setMarketFavorite(symbol: string, favorite: boolean, token?: string | null) {
  return apiPut<string[]>(buildMarketFavoritePath(symbol), { favorite }, token ?? undefined).then(favoriteSymbolList)
}

export function fetchMarketStatus() {
  return apiGet<BackendMarketStatus>(buildMarketStatusPath())
}

export function fetchMarketSymbolRules(symbol: string): Promise<TradingInstrumentRules> {
  return apiGet<BackendInstrumentRules>(buildMarketSymbolRulesPath(symbol)).then(mapInstrumentRulesToTradingRules)
}

export function fetchMarketSymbolRulesBatch(symbols: string[]): Promise<TradingInstrumentRules[]> {
  return apiGet<BackendInstrumentRules[]>(buildMarketSymbolRulesBatchPath(symbols)).then((rules) =>
    rules.map(mapInstrumentRulesToTradingRules)
  )
}

export function fetchMarketQuote(symbol: string, previous?: TradingQuote, signal?: AbortSignal) {
  return apiGet<BackendQuote>(buildMarketQuotePath(symbol), undefined, { signal }).then((quote) =>
    mapQuoteToTradingQuote(quote, previous)
  )
}

export function fetchMarketQuotes(symbols: string[], previousBySymbol: Record<string, TradingQuote> = {}) {
  return apiGet<Record<string, BackendQuote>>(buildMarketQuotesPath(symbols)).then((quotes) => {
    const mappedQuotes: Record<string, TradingQuote> = {}
    Object.entries(quotes).forEach(([symbol, quote]) => {
      mappedQuotes[symbol] = mapQuoteToTradingQuote(quote, previousBySymbol[symbol])
    })
    return mappedQuotes
  })
}

export function fetchMarketCandles(symbol: string, period: TradingPeriod, options: FetchMarketCandlesOptions = {}) {
  return apiGet<BackendCandle[]>(buildMarketCandlesPath(symbol, period, options.endTime, options.count)).then((candles) =>
    candles.map(mapCandleToTradingCandle)
  )
}

export function fetchMarketOrderBook(symbol: string, signal?: AbortSignal) {
  return apiGet<BackendOrderBook>(buildMarketOrderBookPath(symbol), undefined, { signal }).then(mapOrderBookToMarketData)
}

export function fetchMarketRecentTrades(symbol: string, limit = 40) {
  return apiGet<BackendRecentTrade[]>(buildMarketRecentTradesPath(symbol, limit)).then(mapRecentTradesToMarketData)
}

export function fetchMarketRecentTradeBatch(symbol: string, limit = 40, signal?: AbortSignal) {
  return apiGet<BackendRecentTrade[]>(buildMarketRecentTradesPath(symbol, limit), undefined, { signal }).then((trades) =>
    mapRecentTradeBatchToMarketData(trades, symbol)
  )
}
