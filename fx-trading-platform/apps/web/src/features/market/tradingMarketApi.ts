import { apiGet } from '../../services/apiClient'
import {
  buildMarketCandlesPath,
  buildMarketOrderBookPath,
  buildMarketQuotePath,
  buildMarketRecentTradesPath,
  buildMarketSymbolsPath,
  mapCandleToTradingCandle,
  mapOrderBookToMarketData,
  mapQuoteToTradingQuote,
  mapRecentTradesToMarketData,
  mapSymbolToTradingMarket,
  tradingMarketEndpoints
} from './tradingMarketAdapters'
import type { BackendCandle, BackendOrderBook, BackendQuote, BackendRecentTrade, BackendSymbol } from './tradingMarketAdapters'
import type { TradingPeriod, TradingQuote } from './tradingModels'

export {
  buildMarketCandlesPath,
  buildMarketOrderBookPath,
  buildMarketQuotePath,
  buildMarketRecentTradesPath,
  buildMarketSymbolsPath,
  tradingMarketEndpoints
}

export function fetchMarketSymbols(limit?: number) {
  return apiGet<BackendSymbol[]>(buildMarketSymbolsPath(limit)).then((symbols) =>
    symbols.filter((symbol) => symbol.enabled).map(mapSymbolToTradingMarket)
  )
}

export function fetchMarketQuote(symbol: string, previous?: TradingQuote) {
  return apiGet<BackendQuote>(buildMarketQuotePath(symbol)).then((quote) => mapQuoteToTradingQuote(quote, previous))
}

export function fetchMarketCandles(symbol: string, period: TradingPeriod) {
  return apiGet<BackendCandle[]>(buildMarketCandlesPath(symbol, period)).then((candles) =>
    candles.map(mapCandleToTradingCandle)
  )
}

export function fetchMarketOrderBook(symbol: string) {
  return apiGet<BackendOrderBook>(buildMarketOrderBookPath(symbol)).then(mapOrderBookToMarketData)
}

export function fetchMarketRecentTrades(symbol: string, limit = 40) {
  return apiGet<BackendRecentTrade[]>(buildMarketRecentTradesPath(symbol, limit)).then(mapRecentTradesToMarketData)
}
