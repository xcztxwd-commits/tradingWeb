import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { describe, it } from 'node:test'

import { createUnavailableTradingQuote, expireTradingQuote, reconcileQuoteMap } from './tradingQuoteMap.ts'
import type { TradingMarket, TradingQuote } from '@fx-platform/frontend-core'

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

  it('does not turn P0 symbol-list prices into a tradable quote before the provider quote arrives', () => {
    const quotes = reconcileQuoteMap([{
      ...market('BTCUSDT'),
      category: 'crypto',
      base: 'BTC',
      quote: 'USDT',
      last: 60_000,
      high24h: 61_000,
      low24h: 59_000,
      tradable: true,
      quoteTimestamp: Date.now()
    }])

    assert.equal(quotes.BTCUSDT.mid, 0)
    assert.equal(quotes.BTCUSDT.timestamp, 0)
    assert.equal(quotes.BTCUSDT.tradable, false)
  })

  it('clears provider prices on failure or expiry while preserving source metadata for diagnosis', () => {
    const unavailable = createUnavailableTradingQuote('BTCUSDT')
    const expired = expireTradingQuote({
      ...unavailable,
      bid: 59_999,
      ask: 60_001,
      mid: 60_000,
      marketSource: {
        providerCode: 'binance',
        providerSymbol: 'BTCUSDT',
        sourceMode: 'PUBLIC_EXTERNAL',
        asOf: '2026-07-13T00:00:00.000Z',
        expiresAt: '2026-07-13T00:00:05.000Z',
        stale: false
      },
      tradable: true
    })

    assert.equal(unavailable.mid, 0)
    assert.equal(unavailable.tradable, false)
    assert.equal(expired.mid, 0)
    assert.equal(expired.tradable, false)
    assert.equal(expired.marketSource?.providerCode, 'binance')
  })

  it('invalidates the selected quote on provider failure and authoritative expiry', () => {
    const hookSource = readFileSync(new URL('./useTradingQuotes.ts', import.meta.url), 'utf8')

    assert.match(hookSource, /createUnavailableTradingQuote\(market\.symbol\)/)
    assert.match(hookSource, /expireTradingQuote\(current\[quote\.symbol\]\)/)
    assert.match(hookSource, /Date\.parse\(quote\.marketSource\.expiresAt\) - Date\.now\(\)/)
    assert.doesNotMatch(hookSource, /catch\(\(error:[\s\S]*current\[market\.symbol\]/)
  })

  it('does not apply a websocket quote without complete freshness metadata', () => {
    const hookSource = readFileSync(new URL('./useTradingQuotes.ts', import.meta.url), 'utf8')

    assert.match(hookSource, /const mappedQuote = mapQuoteToTradingQuote\(quote,[\s\S]*?latestQuotes\.get\(symbolKey\)\s*\)/)
    assert.match(hookSource, /subscribeQuote<BackendQuote>/)
    assert.match(hookSource, /if \(!mappedQuote\.marketSource\) \{[\s\S]*scheduleQuoteRefresh\(market\)[\s\S]*return/)
    assert.match(hookSource, /applyQuote\(mappedQuote, market\.symbol\)/)
    assert.match(hookSource, /isFreshSource\(normalizedQuote\.marketSource/)
  })

  it('reports trust status from the freshness-normalized quote', () => {
    const hookSource = readFileSync(new URL('./useTradingQuotes.ts', import.meta.url), 'utf8')

    assert.match(hookSource, /nextQuote\.tradable === false \? \{ code: 'MARKET_DATA_STALE'/)
    assert.doesNotMatch(hookSource, /quote\.tradable === false \? \{ code: 'MARKET_DATA_STALE'/)
  })

  it('invalidates quotes and refreshes authoritative REST data when the provider source changes', () => {
    const hookSource = readFileSync(new URL('./useTradingQuotes.ts', import.meta.url), 'utf8')

    assert.match(hookSource, /subscribeMarketSourceChanges/)
    assert.match(hookSource, /normalizePlatformMarketSymbol\(event\.symbol\)/)
    assert.match(hookSource, /expectedSources\.set\(symbolKey, \{ providerCode: event\.providerCode, sourceMode: event\.sourceMode \}\)/)
    assert.match(hookSource, /clearExpiry\(market\.symbol\)/)
    assert.match(hookSource, /latestQuotes\.set\(symbolKey, unavailableQuote\)/)
    assert.match(hookSource, /\[market\.symbol\]: unavailableQuote/)
    assert.match(hookSource, /refreshMarketQuote\(market\)/)
    assert.match(hookSource, /matchesExpectedMarketSource\(quote\.marketSource, expectedSource\)/)
    assert.match(hookSource, /refreshRunning\.has\(symbolKey\)/)
    assert.match(hookSource, /refreshTrailing\.set\(symbolKey, market\)/)
  })

  it('keeps a source-change pin across market metadata effect restarts', () => {
    const hookSource = readFileSync(new URL('./useTradingQuotes.ts', import.meta.url), 'utf8')

    assert.match(hookSource, /const expectedSourcesRef = useRef\(new Map/)
    assert.match(hookSource, /const expectedSources = expectedSourcesRef\.current/)
    assert.doesNotMatch(hookSource, /useEffect\([\s\S]*?const expectedSources = new Map/)
  })

  it('clears a source transition when the subscribed symbol or token changes', () => {
    const hookSource = readFileSync(new URL('./useTradingQuotes.ts', import.meta.url), 'utf8')

    assert.match(hookSource, /const \[sourceNotice, setSourceNotice\] = useState/)
    assert.match(hookSource, /useEffect\(\(\) => \{\s*setSourceNotice\(null\)\s*if \(markets\.length === 0\) return/)
    assert.match(hookSource, /\}, \[onQuoteStatus, symbolKey, markets, token\]\)/)
    assert.match(hookSource, /return \{ quotes, sourceNotice \}/)
  })

  it('restarts the eight-second notice timer for every source event and cancels it on cleanup', () => {
    const hookSource = readFileSync(new URL('./useTradingQuotes.ts', import.meta.url), 'utf8')

    assert.match(hookSource, /const MARKET_SOURCE_NOTICE_DURATION_MS = 8_000/)
    assert.match(hookSource, /let sourceNoticeTimer: ReturnType<typeof globalThis\.setTimeout> \| undefined/)
    assert.match(
      hookSource,
      /subscribeMarketSourceChanges[\s\S]*if \(sourceNoticeTimer\) globalThis\.clearTimeout\(sourceNoticeTimer\)[\s\S]*setSourceNotice\(event\)[\s\S]*sourceNoticeTimer = globalThis\.setTimeout/
    )
    assert.match(hookSource, /sourceNoticeTimer = undefined\s*setSourceNotice\(null\)/)
    assert.match(
      hookSource,
      /return \(\) => \{[\s\S]*if \(sourceNoticeTimer\) globalThis\.clearTimeout\(sourceNoticeTimer\)[\s\S]*releases\.forEach/
    )
    assert.doesNotMatch(hookSource, /useEffect\(\(\) => \{\s*if \(!sourceNotice\) return/)
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
