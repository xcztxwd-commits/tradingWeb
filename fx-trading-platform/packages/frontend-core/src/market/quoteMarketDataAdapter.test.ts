import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

import { createMarketDataStore } from './marketDataStore.ts'
import {
  startQuoteSession,
  type QuoteMarketDataSessionDependencies
} from './quoteMarketDataAdapter.ts'
import type { MarketOrderBook, MarketTradeBatch } from './marketDataTypes.ts'
import type { MarketSourceChangedEvent } from './marketStream.ts'
import type { MarketSourceMetadata, TradingQuote } from './tradingModels.ts'

const currentDir = dirname(fileURLToPath(import.meta.url))
const adapterSource = readFileSync(join(currentDir, 'quoteMarketDataAdapter.ts'), 'utf8')

describe('quote market data adapter', () => {
  it('keeps the market data store, authoritative snapshot and adapter in one core boundary', () => {
    assert.equal(existsSync(join(currentDir, 'marketDataStore.ts')), true)
    assert.equal(existsSync(join(currentDir, 'quoteMarketDataAdapter.ts')), true)
    assert.equal(existsSync(join(currentDir, 'authoritativeMarketSnapshot.ts')), true)
    assert.equal(existsSync(join(currentDir, 'quoteMarketDataSnapshot.ts')), false)
  })

  it('depends only on package-level market modules', () => {
    assert.doesNotMatch(adapterSource, /apps\/web|pages\/trading|components\/market-side-panel/)
    assert.equal(existsSync(join(currentDir, 'tradingMarketApi.ts')), true)
    assert.equal(existsSync(join(currentDir, 'tradingMarketAdapters.ts')), true)
    assert.equal(existsSync(join(currentDir, 'tradingModels.ts')), true)
    assert.equal(existsSync(join(currentDir, 'mockTradingData.ts')), false)
  })

  it('publishes only a complete authoritative bundle and never synthesizes quote depth or trades', () => {
    assert.match(adapterSource, /Promise\.all\(\[/)
    assert.match(adapterSource, /createAuthoritativeMarketSnapshot\(latestQuote, latestOrderBook, latestTrades\)/)
    assert.doesNotMatch(adapterSource, /createQuoteMarketDataSnapshot|createFallbackMarketDataSnapshot/)
    assert.doesNotMatch(adapterSource, /latestQuote\?\.mid \?\? orderBook\.lastPrice/)
    assert.match(adapterSource, /subscribeQuote\(symbol, token, scheduleBundleRefresh\)/)
    assert.match(adapterSource, /subscribeOrderBook\(symbol, token, scheduleBundleRefresh\)/)
    assert.match(adapterSource, /subscribeRecentTrades\(symbol, token, scheduleBundleRefresh\)/)
    assert.doesNotMatch(adapterSource, /as BackendQuote|as BackendOrderBook|as BackendRecentTrade/)
  })

  it('invalidates the old bundle immediately while a provider source is changing', () => {
    assert.match(adapterSource, /subscribeMarketSourceChanges/)
    assert.match(adapterSource, /unavailableSnapshot\('source-changing'\)/)
    assert.match(adapterSource, /sourceChanging = true/)
    assert.match(adapterSource, /controller\.signal\.aborted/)
  })

  it('aborts stale and disposed bundles without blocking the latest source', async () => {
    const store = createMarketDataStore({ flushMs: 0 })
    const quoteSignals: AbortSignal[] = []
    const orderBookSignals: AbortSignal[] = []
    const tradeSignals: AbortSignal[] = []
    let sourceChange: ((event: MarketSourceChangedEvent) => void) | undefined
    const dependencies = {
      fetchQuote: (_symbol, signal) => {
        quoteSignals.push(signal)
        return pendingUntilAbort(signal)
      },
      fetchOrderBook: (_symbol, signal) => {
        orderBookSignals.push(signal)
        return pendingUntilAbort(signal)
      },
      fetchTrades: (_symbol, signal) => {
        tradeSignals.push(signal)
        return pendingUntilAbort(signal)
      },
      subscribeQuote: () => () => {},
      subscribeOrderBook: () => () => {},
      subscribeRecentTrades: () => () => {},
      subscribeSourceChanges: (_symbol, _token, handler) => {
        sourceChange = handler
        return () => {}
      }
    } satisfies QuoteMarketDataSessionDependencies
    const stop = startQuoteSession('BTCUSDT-PERP', null, store, dependencies)

    await waitFor(() => quoteSignals.length === 1 && orderBookSignals.length === 1 && tradeSignals.length === 1)
    assert.ok(sourceChange)
    sourceChange({
      type: 'MARKET_SOURCE_CHANGED',
      symbol: 'BTCUSDT-PERP',
      providerCode: 'local-perp',
      sourceMode: 'LOCAL_SIMULATED'
    })

    assert.equal(quoteSignals[0]?.aborted, true)
    assert.equal(orderBookSignals[0]?.aborted, true)
    assert.equal(tradeSignals[0]?.aborted, true)
    await waitFor(() => quoteSignals.length === 2 && orderBookSignals.length === 2 && tradeSignals.length === 2)
    assert.equal([quoteSignals[1], orderBookSignals[1], tradeSignals[1]].every((signal) => !signal?.aborted), true)
    stop()

    assert.equal([...quoteSignals, ...orderBookSignals, ...tradeSignals].every((signal) => signal.aborted), true)
  })

  it('accepts a complete fresh fallback bundle after the advertised source becomes unavailable', async () => {
    const store = createMarketDataStore({ flushMs: 0 })
    const quoteSignals: AbortSignal[] = []
    const orderBookSignals: AbortSignal[] = []
    const tradeSignals: AbortSignal[] = []
    const fallbackSource = source(Date.now() + 10_000)
    const advertisedSource = {
      ...fallbackSource,
      providerCode: 'binance-usdm',
      sourceMode: 'PUBLIC_EXTERNAL' as const
    }
    const oldQuote = deferred<TradingQuote>()
    const oldOrderBook = deferred<MarketOrderBook>()
    const oldTrades = deferred<MarketTradeBatch>()
    const publishedSources: Array<string | undefined> = []
    let sourceChange: ((event: MarketSourceChangedEvent) => void) | undefined
    const dependencies = {
      fetchQuote: (_symbol, signal) => {
        quoteSignals.push(signal)
        return quoteSignals.length === 1
          ? oldQuote.promise
          : Promise.resolve(quote(fallbackSource))
      },
      fetchOrderBook: (_symbol, signal) => {
        orderBookSignals.push(signal)
        return orderBookSignals.length === 1
          ? oldOrderBook.promise
          : Promise.resolve(orderBook(fallbackSource))
      },
      fetchTrades: (_symbol, signal) => {
        tradeSignals.push(signal)
        return tradeSignals.length === 1
          ? oldTrades.promise
          : Promise.resolve(tradeBatch(fallbackSource))
      },
      subscribeQuote: () => () => {},
      subscribeOrderBook: () => () => {},
      subscribeRecentTrades: () => () => {},
      subscribeSourceChanges: (_symbol, _token, handler) => {
        sourceChange = handler
        return () => {}
      }
    } satisfies QuoteMarketDataSessionDependencies
    const stop = startQuoteSession('BTCUSDT-PERP', null, store, dependencies)
    const unsubscribe = store.subscribe(() => {
      if (store.getSnapshot().status === 'ready') publishedSources.push(store.getSnapshot().source?.providerCode)
    })

    try {
      await waitFor(() => quoteSignals.length === 1 && orderBookSignals.length === 1 && tradeSignals.length === 1)
      assert.ok(sourceChange)
      sourceChange({
        type: 'MARKET_SOURCE_CHANGED',
        symbol: 'BTCUSDT-PERP',
        providerCode: 'binance-usdm',
        sourceMode: 'PUBLIC_EXTERNAL'
      })

      assert.equal(quoteSignals[0]?.aborted, true)
      assert.equal(orderBookSignals[0]?.aborted, true)
      assert.equal(tradeSignals[0]?.aborted, true)
      oldQuote.resolve(quote(advertisedSource))
      oldOrderBook.resolve(orderBook(advertisedSource))
      oldTrades.resolve(tradeBatch(advertisedSource))

      await waitFor(() => store.getSnapshot().status === 'ready', 1_000)
      assert.deepEqual(publishedSources, ['local-perp'])
      assert.equal(store.getSnapshot().source?.providerCode, 'local-perp')
      assert.equal(store.getSnapshot().tradable, true)
    } finally {
      unsubscribe()
      stop()
    }
  })

  it('aborts a refresh that outlives the published bundle and starts a fresh one', async () => {
    const store = createMarketDataStore({ flushMs: 0 })
    const quoteSignals: AbortSignal[] = []
    const orderBookSignals: AbortSignal[] = []
    const tradeSignals: AbortSignal[] = []
    const firstSource = source(Date.now() + 700)
    const recoveredSource = source(Date.now() + 10_000)
    let refresh: (() => void) | undefined
    const dependencies = {
      fetchQuote: (_symbol, signal) => fetchRound(
        quoteSignals,
        signal,
        () => quote(firstSource),
        () => quote(recoveredSource)
      ),
      fetchOrderBook: (_symbol, signal) => fetchRound(
        orderBookSignals,
        signal,
        () => orderBook(firstSource),
        () => orderBook(recoveredSource)
      ),
      fetchTrades: (_symbol, signal) => fetchRound(
        tradeSignals,
        signal,
        () => tradeBatch(firstSource),
        () => tradeBatch(recoveredSource)
      ),
      subscribeQuote: (_symbol, _token, handler) => {
        refresh = handler
        return () => {}
      },
      subscribeOrderBook: () => () => {},
      subscribeRecentTrades: () => () => {},
      subscribeSourceChanges: () => () => {}
    } satisfies QuoteMarketDataSessionDependencies
    const stop = startQuoteSession('BTCUSDT-PERP', null, store, dependencies)

    await waitFor(() => store.getSnapshot().status === 'ready')
    assert.ok(refresh)
    refresh()
    await waitFor(() => quoteSignals.length === 2 && orderBookSignals.length === 2 && tradeSignals.length === 2)
    await waitFor(
      () => quoteSignals[1]?.aborted === true
        && orderBookSignals[1]?.aborted === true
        && tradeSignals[1]?.aborted === true,
      2_000
    )
    await waitFor(() => quoteSignals.length === 3 && orderBookSignals.length === 3 && tradeSignals.length === 3)
    await waitFor(() => store.getSnapshot().status === 'ready')
    stop()
  })

  it('retries unavailable and failed initial bundles without realtime events', async () => {
    for (const firstFailure of ['source-mismatch', 'request-error'] as const) {
      const store = createMarketDataStore({ flushMs: 0 })
      const quoteSignals: AbortSignal[] = []
      const orderBookSignals: AbortSignal[] = []
      const tradeSignals: AbortSignal[] = []
      const recoveredSource = source(Date.now() + 10_000)
      const mismatchedSource = { ...recoveredSource, providerCode: 'transient-perp' }
      const dependencies = {
        fetchQuote: (_symbol, signal) => {
          quoteSignals.push(signal)
          return Promise.resolve(quote(recoveredSource))
        },
        fetchOrderBook: (_symbol, signal) => {
          orderBookSignals.push(signal)
          if (orderBookSignals.length === 1 && firstFailure === 'request-error') {
            return Promise.reject(new Error('temporary order-book failure'))
          }
          return Promise.resolve(orderBook(orderBookSignals.length === 1 ? mismatchedSource : recoveredSource))
        },
        fetchTrades: (_symbol, signal) => {
          tradeSignals.push(signal)
          return Promise.resolve(tradeBatch(recoveredSource))
        },
        subscribeQuote: () => () => {},
        subscribeOrderBook: () => () => {},
        subscribeRecentTrades: () => () => {},
        subscribeSourceChanges: () => () => {}
      } satisfies QuoteMarketDataSessionDependencies
      const stop = startQuoteSession('BTCUSDT-PERP', null, store, dependencies)

      try {
        await waitFor(() => quoteSignals.length === 2, 1_000)
        await waitFor(() => store.getSnapshot().status === 'ready')
        assert.equal(orderBookSignals.length, 2, firstFailure)
        assert.equal(tradeSignals.length, 2, firstFailure)
        assert.equal(store.getSnapshot().tradable, true, firstFailure)
      } finally {
        stop()
      }
    }
  })

  it('retries an incomplete bundle while the provider source is changing', () => {
    assert.match(
      adapterSource,
      /if \(sourceChanging && \(snapshot\.status !== 'ready' \|\| !snapshot\.source\)\) \{\s*store\.reset\(unavailableSnapshot\('source-changing'\)\)\s*scheduleBundleRefresh\(\)\s*return/
    )
  })

  it('refreshes an expired bundle when realtime events are unavailable', () => {
    assert.match(
      adapterSource,
      /expiryTimer = globalThis\.setTimeout\(\(\) => \{\s*if \(!disposed\) \{\s*store\.reset\(unavailableSnapshot\('stale', snapshot\.source\)\)\s*refreshController\?\.abort\(\)\s*void refreshBundle\(\)\s*\}/
    )
  })

  it('retries when a slow refresh returns an already expired bundle', () => {
    assert.match(
      adapterSource,
      /store\.reset\(snapshot\)\s*if \(snapshot\.status !== 'ready' \|\| !snapshot\.source\) \{\s*scheduleBundleRefresh\(\)\s*return\s*\}/
    )
  })
})

function pendingUntilAbort<T>(signal: AbortSignal): Promise<T> {
  return new Promise<T>((_resolve, reject) => {
    const abort = () => reject(new DOMException('Aborted', 'AbortError'))
    if (signal.aborted) {
      abort()
      return
    }
    signal.addEventListener('abort', abort, { once: true })
  })
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}

function fetchRound<T>(
  signals: AbortSignal[],
  signal: AbortSignal,
  first: () => T,
  recovered: () => T
): Promise<T> {
  signals.push(signal)
  if (signals.length === 1) return Promise.resolve(first())
  if (signals.length === 2) return pendingUntilAbort(signal)
  return Promise.resolve(recovered())
}

function source(expiresAt: number): MarketSourceMetadata {
  return {
    providerCode: 'local-perp',
    providerSymbol: 'BTCUSDT',
    sourceMode: 'LOCAL_SIMULATED',
    asOf: new Date(Date.now()).toISOString(),
    expiresAt: new Date(expiresAt).toISOString(),
    stale: false
  }
}

function quote(marketSource: MarketSourceMetadata): TradingQuote {
  return {
    symbol: 'BTCUSDT-PERP',
    bid: 59_999,
    ask: 60_001,
    mid: 60_000,
    spread: 2,
    changePercent: 1,
    high24h: 61_000,
    low24h: 58_000,
    volume: '10K',
    source: marketSource.providerCode,
    timestamp: Date.now(),
    marketSource,
    tradable: true
  }
}

function orderBook(source: MarketSourceMetadata): MarketOrderBook {
  return {
    symbol: 'BTCUSDT-PERP',
    bids: [{ price: 59_999, amount: 2 }],
    asks: [{ price: 60_001, amount: 3 }],
    lastPrice: 60_000,
    lastPriceDirection: 'flat',
    source
  }
}

function tradeBatch(source: MarketSourceMetadata): MarketTradeBatch {
  return {
    symbol: 'BTCUSDT-PERP',
    recentTrades: [{ id: 't-1', price: 60_000, amount: 0.1, side: 'buy', time: Date.now() }],
    source
  }
}

async function waitFor(predicate: () => boolean, timeoutMs = 250) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (predicate()) return
    await new Promise((resolve) => globalThis.setTimeout(resolve, 5))
  }
  assert.fail('Timed out waiting for adapter state')
}
