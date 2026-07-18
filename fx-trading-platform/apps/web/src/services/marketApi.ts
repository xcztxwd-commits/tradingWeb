import { apiGet, type Candle, type Quote, type SymbolItem } from '@fx-platform/frontend-core'
import type { PerpetualReferenceResponse } from '@fx-platform/shared-types'

export function getSymbols() {
  return apiGet<SymbolItem[]>('/api/market/symbols')
}

export function getQuote(symbol: string) {
  return apiGet<Quote>(`/api/market/quotes/${symbol}`)
}

export function getPerpetualReference(symbol: string) {
  return apiGet<PerpetualReferenceResponse>(
    `/api/market/perpetuals/${encodeURIComponent(symbol.trim().toUpperCase())}/reference`
  )
}

export function getCandles(symbol: string, timeframe: string) {
  const to = new Date()
  const from = new Date(to.getTime() - 60 * 60 * 1000)
  return apiGet<Candle[]>(
    `/api/chart/candles?symbol=${symbol}&timeframe=${timeframe}&from=${from.toISOString()}&to=${to.toISOString()}`
  )
}

// Massive 只能由 Java backend 调用；前端只请求平台标准化后的 /api/market 与 /api/chart。
