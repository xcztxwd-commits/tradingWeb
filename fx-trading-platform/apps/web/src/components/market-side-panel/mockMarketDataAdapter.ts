import { marketDataStore } from './marketDataStore'
import type { MarketDataSnapshot, OrderBookLevel, OrderBookSide, TradeItem } from './types'

type AdapterStore = typeof marketDataStore

type MarketProfile = {
  lastPrice: number
  spread: number
  tickSize: number
  amountBase: number
}

type ActiveAdapterSession = {
  symbol: string
  store: AdapterStore
  refs: number
  stop: () => void
}

const symbolProfiles: Record<string, MarketProfile> = {
  BTCUSDT: { lastPrice: 60865.7, spread: 1.1, tickSize: 0.1, amountBase: 0.24 },
  ETHUSDT: { lastPrice: 3420.6, spread: 0.4, tickSize: 0.1, amountBase: 2.8 },
  SOLUSDT: { lastPrice: 152.12, spread: 0.01, tickSize: 0.01, amountBase: 42 },
  XRPUSDT: { lastPrice: 2.481, spread: 0.002, tickSize: 0.0001, amountBase: 1200 },
  XAUUSD: { lastPrice: 2348.4, spread: 0.2, tickSize: 0.1, amountBase: 12 },
  US100: { lastPrice: 18924.6, spread: 0.7, tickSize: 0.1, amountBase: 4.4 },
  USDJPY: { lastPrice: 156.42, spread: 0.02, tickSize: 0.1, amountBase: 28 }
}

let activeSession: ActiveAdapterSession | undefined
let adapterRunId = 0

export function startMockMarketDataAdapter(symbol: string, store: AdapterStore = marketDataStore) {
  if (activeSession?.symbol === symbol && activeSession.store === store) {
    activeSession.refs += 1
    return createRelease(activeSession)
  }

  if (activeSession) {
    activeSession.stop()
    activeSession = undefined
  }

  const session: ActiveAdapterSession = {
    symbol,
    store,
    refs: 1,
    stop: startMockSession(symbol, store)
  }
  activeSession = session

  return createRelease(session)
}

function startMockSession(symbol: string, store: AdapterStore) {
  const profile = getProfile(symbol)
  let lastPrice = profile.lastPrice
  let tradeIndex = 0
  const sessionId = adapterRunId

  store.reset(createInitialSnapshot(profile))
  adapterRunId += 1

  const orderBookTimer = globalThis.setInterval(() => {
    const direction = Math.random() > 0.48 ? 1 : -1
    lastPrice = roundPrice(lastPrice + direction * profile.tickSize * Math.random() * 5, profile.tickSize)
    store.setLastPrice(lastPrice)

    for (let index = 0; index < 8; index += 1) {
      const side: OrderBookSide = Math.random() > 0.5 ? 'bid' : 'ask'
      const distance = 1 + Math.floor(Math.random() * 28)
      const price = getLevelPrice(lastPrice, profile, side, distance)
      const amount = Math.max(0.00001, profile.amountBase * (0.2 + Math.random() * 4))
      store.setOrderBookLevel(side, price, roundAmount(amount))
    }
  }, 100)

  const tradesTimer = globalThis.setInterval(() => {
    const side = Math.random() > 0.5 ? 'buy' : 'sell'
    const priceOffset = (Math.random() - 0.5) * profile.spread * 3
    const trade: TradeItem = {
      id: `${symbol}-${sessionId}-${Date.now()}-${tradeIndex}`,
      price: roundPrice(lastPrice + priceOffset, profile.tickSize),
      amount: roundAmount(profile.amountBase * (0.05 + Math.random() * 2)),
      side,
      time: Date.now()
    }
    tradeIndex += 1
    store.addTrade(trade)
  }, 300)

  return () => {
    globalThis.clearInterval(orderBookTimer)
    globalThis.clearInterval(tradesTimer)
  }
}

function createRelease(session: ActiveAdapterSession) {
  let released = false

  return () => {
    if (released) return
    released = true
    session.refs -= 1

    if (session.refs > 0 || activeSession !== session) return
    session.stop()
    activeSession = undefined
  }
}

function createInitialSnapshot(profile: MarketProfile): MarketDataSnapshot {
  return {
    asks: createLevels(profile, 'ask'),
    bids: createLevels(profile, 'bid'),
    lastPrice: profile.lastPrice,
    lastPriceDirection: 'flat',
    recentTrades: []
  }
}

function createLevels(profile: MarketProfile, side: OrderBookSide): OrderBookLevel[] {
  return Array.from({ length: 42 }, (_, index) => {
    const distance = index + 1
    const price = getLevelPrice(profile.lastPrice, profile, side, distance)
    const wave = 0.4 + Math.abs(Math.sin(distance / 3)) * 2.6
    return {
      price,
      amount: roundAmount(profile.amountBase * wave)
    }
  })
}

function getLevelPrice(lastPrice: number, profile: MarketProfile, side: OrderBookSide, distance: number) {
  const offset = profile.spread + distance * profile.tickSize
  const price = side === 'bid' ? lastPrice - offset : lastPrice + offset
  return roundPrice(price, profile.tickSize)
}

function getProfile(symbol: string): MarketProfile {
  if (symbolProfiles[symbol]) return symbolProfiles[symbol]
  if (symbol.endsWith('JPY')) return { lastPrice: 156.42, spread: 0.02, tickSize: 0.1, amountBase: 28 }
  if (symbol.endsWith('USDT')) return { lastPrice: 152.12, spread: 0.01, tickSize: 0.01, amountBase: 42 }
  if (symbol.includes('GBP')) return { lastPrice: 1.2712, spread: 0.01, tickSize: 0.1, amountBase: 140 }
  if (symbol.includes('AUD')) return { lastPrice: 0.6642, spread: 0.01, tickSize: 0.1, amountBase: 180 }
  return { lastPrice: 1.0832, spread: 0.01, tickSize: 0.1, amountBase: 160 }
}

function roundPrice(price: number, tickSize: number) {
  const precision = String(tickSize).includes('.') ? String(tickSize).split('.')[1].length : 0
  return Number(price.toFixed(precision))
}

function roundAmount(amount: number) {
  return Number(amount.toFixed(8))
}
