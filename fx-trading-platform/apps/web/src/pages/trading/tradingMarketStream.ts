import { mockTradingMarkets } from '../../features/market/mockTradingData'
import type { TradingQuote } from '../../features/market/tradingModels'

type QuoteCallback = (quote: TradingQuote) => void

const quoteSubscribers = new Map<string, Set<QuoteCallback>>()
let timer: number | undefined
let tick = 0

export function subscribeMockQuote(symbol: string, callback: QuoteCallback) {
  const callbacks = quoteSubscribers.get(symbol) ?? new Set<QuoteCallback>()
  callbacks.add(callback)
  quoteSubscribers.set(symbol, callbacks)
  callback(createMockQuote(symbol))
  ensureTimer()

  return () => {
    callbacks.delete(callback)
    if (callbacks.size === 0) {
      quoteSubscribers.delete(symbol)
    }
    stopTimerWhenIdle()
  }
}

export function getInitialMockQuote(symbol: string) {
  return createMockQuote(symbol)
}

function ensureTimer() {
  if (timer) return
  timer = window.setInterval(() => {
    tick += 1
    quoteSubscribers.forEach((callbacks, symbol) => {
      const quote = createMockQuote(symbol)
      callbacks.forEach((callback) => callback(quote))
    })
  }, 160)
}

function stopTimerWhenIdle() {
  if (quoteSubscribers.size > 0 || !timer) return
  window.clearInterval(timer)
  timer = undefined
}

function createMockQuote(symbol: string): TradingQuote {
  const market = mockTradingMarkets.find((item) => item.symbol === symbol) ?? mockTradingMarkets[0]
  const precision = getPrecision(symbol)
  const wave = Math.sin((tick + symbol.length) / 7) * market.spread * 11
  const drift = Math.cos((tick + symbol.charCodeAt(0)) / 13) * market.spread * 4
  const mid = round(market.last + wave + drift, precision)
  const spread = round(Math.max(market.spread, Math.abs(Math.sin(tick / 9)) * market.spread * 1.25), precision)
  const halfSpread = spread / 2

  return {
    symbol,
    bid: round(mid - halfSpread, precision),
    ask: round(mid + halfSpread, precision),
    mid,
    spread,
    changePercent: round(market.changePercent + Math.sin(tick / 18) * 0.08, 2),
    high24h: market.high24h,
    low24h: market.low24h,
    volume: market.volume,
    source: market.source,
    timestamp: Date.now()
  }
}

function getPrecision(symbol: string) {
  if (symbol.includes('BTC') || symbol.includes('ETH') || symbol === 'US100') return 2
  if (symbol.includes('XAU')) return 2
  if (symbol.endsWith('JPY')) return 3
  return 5
}

function round(value: number, precision: number) {
  return Number(value.toFixed(precision))
}
