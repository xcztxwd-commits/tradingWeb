import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { reconcileQuoteMap } from './tradingQuoteMap.ts'
import type { TradingMarket, TradingQuote } from '../../features/market/tradingModels.ts'

describe('trading quote map reconciliation', () => {
  it('keeps an existing provider quote when the market list refreshes', () => {
    const existingQuote: TradingQuote = {
      symbol: 'EURUSD',
      bid: 1.15653,
      ask: 1.15657,
      mid: 1.15655,
      spread: 0.00004,
      changePercent: -0.110552,
      high24h: 1.15895,
      low24h: 1.1556,
      volume: '250.15K',
      source: 'massive-aggregate',
      timestamp: 1781297940000
    }
    const markets: TradingMarket[] = [
      market('EURUSD'),
      market('GBPUSD')
    ]

    const nextQuotes = reconcileQuoteMap(markets, { EURUSD: existingQuote })

    assert.equal(nextQuotes.EURUSD, existingQuote)
    assert.equal(nextQuotes.GBPUSD.symbol, 'GBPUSD')
    assert.equal(nextQuotes.GBPUSD.mid, 0)
  })
})

function market(symbol: string): TradingMarket {
  return {
    symbol,
    base: symbol.slice(0, 3),
    quote: symbol.slice(3),
    name: symbol,
    category: 'fx',
    favorite: false,
    last: 0,
    changePercent: 0,
    volume: 'Live',
    high24h: 0,
    low24h: 0,
    spread: 0,
    source: 'massive',
    provider: 'massive',
    providerSymbol: `C:${symbol}`,
    tradable: false
  }
}
