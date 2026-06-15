import type {
  AggregatedOrderBookRow,
  AggregationStep,
  BuiltOrderBookRows,
  OrderBookDisplayMode,
  OrderBookLevel,
  OrderBookSettings,
  OrderBookSide,
  OrderBookState
} from './types'

export const orderBookSettingsKey = 'market-side-panel:order-book-settings'

export const defaultOrderBookSettings: OrderBookSettings = {
  showAverageAndTotal: true,
  showBidAskRatio: true,
  showDepthBars: true,
  layoutMode: 'split'
}

type StorageLike = {
  getItem: (key: string) => string | null
  setItem: (key: string, value: string) => void
}

export function buildOrderBookRows(
  state: OrderBookState,
  step: AggregationStep,
  mode: OrderBookDisplayMode
): BuiltOrderBookRows {
  const bids = mode === 'asks' ? [] : aggregateLevels(state.bids, 'bid', step)
  const asksForCumulative = mode === 'bids' ? [] : aggregateLevels(state.asks, 'ask', step)
  const asks = [...asksForCumulative].reverse()
  const maxCumulativeTotal = Math.max(
    0,
    ...bids.map((row) => row.cumulativeTotal),
    ...asks.map((row) => row.cumulativeTotal)
  )

  return { asks, bids, maxCumulativeTotal }
}

export function aggregateLevels(
  levels: OrderBookLevel[],
  side: OrderBookSide,
  step: AggregationStep
): AggregatedOrderBookRow[] {
  const bucketMap = new Map<number, number>()

  levels.forEach((level) => {
    if (level.amount <= 0) return
    const bucketPrice = getBucketPrice(level.price, step, side)
    bucketMap.set(bucketPrice, roundAmount((bucketMap.get(bucketPrice) ?? 0) + level.amount))
  })

  const sortedLevels = Array.from(bucketMap, ([price, amount]) => ({ price, amount })).sort((left, right) =>
    side === 'bid' ? right.price - left.price : left.price - right.price
  )

  let cumulativeTotal = 0
  return sortedLevels.map((level) => {
    cumulativeTotal = roundAmount(cumulativeTotal + level.amount)
    return { ...level, side, cumulativeTotal }
  })
}

export function getBucketPrice(price: number, step: AggregationStep, side: OrderBookSide) {
  const normalized = side === 'bid' ? Math.floor(price / step) * step : Math.ceil(price / step) * step
  return Number(normalized.toFixed(getStepPrecision(step)))
}

export function getDepthBarWidth(cumulativeTotal: number, maxCumulativeTotal: number) {
  if (maxCumulativeTotal <= 0) return 0
  return Math.max(0, Math.min(100, (cumulativeTotal / maxCumulativeTotal) * 100))
}

export function formatPrice(price: number, step: AggregationStep) {
  return price.toLocaleString('en-US', {
    minimumFractionDigits: getStepPrecision(step),
    maximumFractionDigits: getStepPrecision(step)
  })
}

export function formatLastPrice(price: number) {
  const precision = price >= 100 ? 1 : price >= 10 ? 2 : 5
  return price.toLocaleString('en-US', {
    minimumFractionDigits: precision,
    maximumFractionDigits: precision
  })
}

export function formatAmount(amount: number) {
  return amount.toLocaleString('en-US', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 8
  })
}

export function formatTradeTime(time: number) {
  return new Intl.DateTimeFormat('en-US', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false
  }).format(time)
}

export function getBaseAsset(symbol: string) {
  if (symbol.endsWith('USDT')) return symbol.slice(0, -4)
  if (symbol.endsWith('USD')) return symbol.slice(0, -3)
  return symbol.slice(0, 3)
}

export function getQuoteAsset(symbol: string) {
  if (symbol.endsWith('USDT')) return 'USDT'
  if (symbol.endsWith('USD')) return 'USD'
  if (symbol.endsWith('JPY')) return 'JPY'
  return 'USDT'
}

export function loadOrderBookSettings(storage = getBrowserStorage()): OrderBookSettings {
  if (!storage) return defaultOrderBookSettings

  try {
    const rawValue = storage.getItem(orderBookSettingsKey)
    if (!rawValue) return defaultOrderBookSettings
    const parsed = JSON.parse(rawValue) as Partial<OrderBookSettings>

    return {
      showAverageAndTotal:
        typeof parsed.showAverageAndTotal === 'boolean'
          ? parsed.showAverageAndTotal
          : defaultOrderBookSettings.showAverageAndTotal,
      showBidAskRatio:
        typeof parsed.showBidAskRatio === 'boolean' ? parsed.showBidAskRatio : defaultOrderBookSettings.showBidAskRatio,
      showDepthBars:
        typeof parsed.showDepthBars === 'boolean' ? parsed.showDepthBars : defaultOrderBookSettings.showDepthBars,
      layoutMode:
        parsed.layoutMode === 'orderbook' || parsed.layoutMode === 'trades' || parsed.layoutMode === 'split'
          ? parsed.layoutMode
          : defaultOrderBookSettings.layoutMode
    }
  } catch {
    return defaultOrderBookSettings
  }
}

export function saveOrderBookSettings(settings: OrderBookSettings, storage = getBrowserStorage()) {
  storage?.setItem(orderBookSettingsKey, JSON.stringify(settings))
}

function getBrowserStorage(): StorageLike | undefined {
  if (typeof window === 'undefined') return undefined
  return window.localStorage
}

function getStepPrecision(step: AggregationStep) {
  const value = String(step)
  return value.includes('.') ? value.split('.')[1].length : 0
}

function roundAmount(value: number) {
  return Number(value.toFixed(8))
}
