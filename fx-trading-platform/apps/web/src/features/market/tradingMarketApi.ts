import { apiGet, apiPut } from '../../services/apiClient'
import { favoriteSymbolList } from './marketFavorites'
import {
  buildMarketCandlesPath,
  buildMarketFavoritePath,
  buildMarketFavoritesPath,
  buildMarketOrderBookPath,
  buildMarketQuotePath,
  buildMarketRecentTradesPath,
  buildMarketStatusPath,
  buildMarketSymbolsPath,
  mapCandleToTradingCandle,
  mapOrderBookToMarketData,
  mapQuoteToTradingQuote,
  mapRecentTradesToMarketData,
  mapSymbolToTradingMarket,
  tradingMarketEndpoints
} from './tradingMarketAdapters'
import type { BackendCandle, BackendOrderBook, BackendQuote, BackendRecentTrade, BackendSymbol } from './tradingMarketAdapters'
import type { BackendMarketStatus } from './tradingMarketAdapters'
import type { TradingPeriod, TradingQuote } from './tradingModels'

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
  buildMarketRecentTradesPath,
  buildMarketStatusPath,
  buildMarketSymbolsPath,
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

export function fetchMarketQuote(symbol: string, previous?: TradingQuote) {
  return apiGet<BackendQuote>(buildMarketQuotePath(symbol)).then((quote) => mapQuoteToTradingQuote(quote, previous))
}

export function fetchMarketCandles(symbol: string, period: TradingPeriod, options: FetchMarketCandlesOptions = {}) {
  return apiGet<BackendCandle[]>(buildMarketCandlesPath(symbol, period, options.endTime, options.count)).then((candles) =>
    candles.map(mapCandleToTradingCandle)
  )
}

export function fetchMarketOrderBook(symbol: string) {
  return apiGet<BackendOrderBook>(buildMarketOrderBookPath(symbol)).then(mapOrderBookToMarketData)
}

export function fetchMarketRecentTrades(symbol: string, limit = 40) {
  return apiGet<BackendRecentTrade[]>(buildMarketRecentTradesPath(symbol, limit)).then(mapRecentTradesToMarketData)
}
