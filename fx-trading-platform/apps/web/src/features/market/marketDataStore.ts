import { useSyncExternalStore } from 'react'

import { unavailableSnapshot } from './authoritativeMarketSnapshot.ts'
import type { MarketDataSnapshot, OrderBookLevel, OrderBookSide, TradeItem } from './marketDataTypes'

type Listener = () => void
type OrderBookLevelUpdate = OrderBookLevel & { side: OrderBookSide }

type MarketDataStoreOptions = {
  flushMs?: number
}

const emptySnapshot: MarketDataSnapshot = unavailableSnapshot('loading')

class MarketDataStore {
  private readonly flushMs: number
  private readonly listeners = new Set<Listener>()
  private readonly bidsMap = new Map<number, number>()
  private readonly asksMap = new Map<number, number>()
  private recentTrades: TradeItem[] = []
  private symbol: string | undefined
  private lastPrice = 0
  private lastPriceDirection: MarketDataSnapshot['lastPriceDirection'] = 'flat'
  private updatedAt: number | undefined
  private source: MarketDataSnapshot['source']
  private componentSources: MarketDataSnapshot['componentSources']
  private status: MarketDataSnapshot['status'] = 'loading'
  private tradable = false
  private snapshot = emptySnapshot
  private flushTimer: ReturnType<typeof globalThis.setTimeout> | undefined

  constructor(options: MarketDataStoreOptions = {}) {
    this.flushMs = options.flushMs ?? 120
  }

  subscribe = (listener: Listener) => {
    this.listeners.add(listener)
    return () => {
      this.listeners.delete(listener)
    }
  }

  getSnapshot = () => this.snapshot

  reset(state: Partial<MarketDataSnapshot> = {}) {
    this.clearTimer()
    this.bidsMap.clear()
    this.asksMap.clear()
    this.recentTrades = state.recentTrades?.slice(0, 100) ?? []
    this.symbol = state.symbol
    this.lastPrice = state.lastPrice ?? 0
    this.lastPriceDirection = state.lastPriceDirection ?? 'flat'
    this.updatedAt = state.updatedAt
    this.source = state.source
    this.componentSources = state.componentSources
    this.status = state.status ?? 'unavailable'
    this.tradable = state.tradable ?? false
    this.writeLevels('bid', state.bids ?? [])
    this.writeLevels('ask', state.asks ?? [])
    this.commitSnapshot()
  }

  setOrderBookLevel(side: OrderBookSide, price: number, amount: number) {
    const targetMap = side === 'bid' ? this.bidsMap : this.asksMap

    if (amount <= 0) {
      targetMap.delete(price)
    } else {
      targetMap.set(price, amount)
    }

    this.scheduleFlush()
  }

  setOrderBook(levels: OrderBookLevelUpdate[]) {
    this.bidsMap.clear()
    this.asksMap.clear()
    levels.forEach((level) => {
      this.setOrderBookLevel(level.side, level.price, level.amount)
    })
    if (levels.length === 0) {
      this.scheduleFlush()
    }
  }

  setLastPrice(price: number, updatedAt = Date.now()) {
    this.lastPriceDirection = price > this.lastPrice ? 'up' : price < this.lastPrice ? 'down' : 'flat'
    this.lastPrice = price
    this.updatedAt = updatedAt
    this.scheduleFlush()
  }

  setRecentTrades(trades: TradeItem[]) {
    this.recentTrades = trades.slice(0, 100)
    this.scheduleFlush()
  }

  addTrade(trade: TradeItem) {
    this.recentTrades = [trade, ...this.recentTrades].slice(0, 100)
    this.setLastPrice(trade.price, trade.time)
  }

  flushNow() {
    this.clearTimer()
    this.commitSnapshot()
  }

  private writeLevels(side: OrderBookSide, levels: OrderBookLevel[]) {
    const targetMap = side === 'bid' ? this.bidsMap : this.asksMap
    levels.forEach((level) => {
      if (level.amount > 0) {
        targetMap.set(level.price, level.amount)
      }
    })
  }

  private scheduleFlush() {
    if (this.flushTimer) return
    this.flushTimer = globalThis.setTimeout(() => {
      this.flushTimer = undefined
      this.commitSnapshot()
    }, this.flushMs)
  }

  private commitSnapshot() {
    this.snapshot = {
      symbol: this.symbol,
      asks: mapToLevels(this.asksMap, 'ask'),
      bids: mapToLevels(this.bidsMap, 'bid'),
      lastPrice: this.lastPrice,
      lastPriceDirection: this.lastPriceDirection,
      recentTrades: this.recentTrades,
      updatedAt: this.updatedAt,
      source: this.source,
      componentSources: this.componentSources,
      status: this.status,
      tradable: this.tradable
    }
    this.listeners.forEach((listener) => listener())
  }

  private clearTimer() {
    if (!this.flushTimer) return
    globalThis.clearTimeout(this.flushTimer)
    this.flushTimer = undefined
  }
}

export function createMarketDataStore(options?: MarketDataStoreOptions) {
  return new MarketDataStore(options)
}

export const marketDataStore = createMarketDataStore()

export function useMarketDataSnapshot(store = marketDataStore) {
  return useSyncExternalStore(store.subscribe, store.getSnapshot, store.getSnapshot)
}

function mapToLevels(map: Map<number, number>, side: OrderBookSide) {
  return Array.from(map, ([price, amount]) => ({ price, amount })).sort((left, right) =>
    side === 'bid' ? right.price - left.price : left.price - right.price
  )
}
