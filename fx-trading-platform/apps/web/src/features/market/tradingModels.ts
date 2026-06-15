export type TradingPeriod =
  | 'time'
  | '1s'
  | '1m'
  | '3m'
  | '5m'
  | '15m'
  | '30m'
  | '1h'
  | '2h'
  | '4h'
  | '6h'
  | '12h'
  | '1d'
  | '2d'
  | '3d'
  | '5d'
  | '1w'
  | '1M'
  | '3M'

export type KLinePeriod = {
  type: 'second' | 'minute' | 'hour' | 'day' | 'week' | 'month' | 'year'
  span: number
}

export type TradingCandle = {
  timestamp: number
  open: number
  high: number
  low: number
  close: number
  volume: number
  turnover?: number
}

export type MarketCategory = 'all' | 'favorites' | 'fx' | 'crypto' | 'metals' | 'indices'

export type MarketListItem = {
  symbol: string
  base: string
  quote: string
  name: string
  category: Exclude<MarketCategory, 'all' | 'favorites'>
  favorite: boolean
  iconUrl?: string
  quoteEnabled?: boolean
}

export type TradingMarket = MarketListItem & {
  last: number
  changePercent: number
  volume: string
  high24h: number
  low24h: number
  spread: number
  source: string
  provider?: string
  providerSymbol?: string
  tradable?: boolean
  chartEnabled?: boolean
  orderBookEnabled?: boolean
  minLot?: string
  pricePrecision?: number
  quantityPrecision?: number
  marketCap?: number
  quoteTimestamp?: number
}

export type TradingQuote = {
  symbol: string
  bid: number
  ask: number
  mid: number
  spread: number
  changePercent: number
  high24h: number
  low24h: number
  volume: string
  source: string
  timestamp: number
}

export type IndicatorName = 'MA' | 'EMA' | 'BOLL' | 'MACD' | 'RSI'

export const tradingPeriods: TradingPeriod[] = ['1s', '1m', '5m', '15m', '1h', '4h', '1d']

export const indicatorNames: IndicatorName[] = ['MA', 'EMA', 'BOLL', 'MACD', 'RSI']

export const marketCategories: Array<{ value: MarketCategory; label: string }> = [
  { value: 'all', label: 'All' },
  { value: 'favorites', label: 'Favorites' },
  { value: 'fx', label: 'FX' },
  { value: 'crypto', label: 'Crypto' },
  { value: 'metals', label: 'Metals' },
  { value: 'indices', label: 'Indices' }
]

const periodIntervals: Record<TradingPeriod, number> = {
  time: 60_000,
  '1s': 1_000,
  '1m': 60_000,
  '3m': 180_000,
  '5m': 300_000,
  '15m': 900_000,
  '30m': 1_800_000,
  '1h': 3_600_000,
  '2h': 7_200_000,
  '4h': 14_400_000,
  '6h': 21_600_000,
  '12h': 43_200_000,
  '1d': 86_400_000,
  '2d': 172_800_000,
  '3d': 259_200_000,
  '5d': 432_000_000,
  '1w': 604_800_000,
  '1M': 2_592_000_000,
  '3M': 7_776_000_000
}

export const percentageSteps = [0, 25, 50, 75, 100] as const

export function toKLinePeriod(period: TradingPeriod): KLinePeriod {
  if (period === 'time') return { type: 'minute', span: 1 }

  const unit = period.at(-1)
  const span = Number(period.slice(0, -1))

  if (unit === 's') return { type: 'second', span }
  if (unit === 'm') return { type: 'minute', span }
  if (unit === 'h') return { type: 'hour', span }
  if (unit === 'w') return { type: 'week', span }
  if (unit === 'M') return { type: 'month', span }
  return { type: 'day', span }
}

export function getPeriodInterval(period: TradingPeriod) {
  return periodIntervals[period]
}

export function makeMockCandles(symbol: string, period: TradingPeriod, count = 180, endTime = Date.now()): TradingCandle[] {
  const interval = getPeriodInterval(period)
  const base = getSymbolBasePrice(symbol)
  const { precision, step, wick, shock } = getMockCandleProfile(symbol, base)
  const volumeBase = symbol.includes('BTC') ? 280 : 920
  const random = createSeededRandom(`${symbol}:${period}:${count}:${endTime}`)
  const shockMap = createShockMap(count, shock, random)
  const baseVolatility = Math.max(step / base, 0.00008)
  let open = base * (1 + randomBetween(random, -baseVolatility * 6, baseVolatility * 6))
  let momentum = randomBetween(random, -baseVolatility, baseVolatility)
  let volatility = baseVolatility * randomBetween(random, 1.4, 2.4)

  return Array.from({ length: count }, (_, index) => {
    const timestamp = endTime - (count - 1 - index) * interval
    const shockMove = shockMap.get(index) ?? 0
    momentum = clamp(momentum * 0.68 + randomBetween(random, -volatility, volatility) * 0.42, -baseVolatility * 5, baseVolatility * 5)
    volatility = clamp(volatility * 0.9 + Math.abs(randomBetween(random, -baseVolatility, baseVolatility)) * 1.15, baseVolatility * 0.8, baseVolatility * 7)

    const vectorMove = momentum + randomBetween(random, -volatility, volatility) + shockMove
    const close = Math.max(base * 0.05, open * (1 + vectorMove))
    const bodyHigh = Math.max(open, close)
    const bodyLow = Math.min(open, close)
    const shockWick = Math.abs(shockMove) * open * randomBetween(random, 0.08, 0.22)
    const high = bodyHigh + wick * randomBetween(random, 0.7, 2.9) + shockWick
    const low = Math.max(base * 0.05, bodyLow - wick * randomBetween(random, 0.7, 2.9) - shockWick)
    const volumeShock = shockMove === 0 ? 1 : randomBetween(random, 3.2, 6.4)
    const volume = Math.round(volumeBase * randomBetween(random, 0.55, 1.75) * (1 + Math.abs(vectorMove) * 180) * volumeShock)
    const roundedClose = roundPrice(close, precision)
    const candle = {
      timestamp,
      open: roundPrice(open, precision),
      high: roundPrice(high, precision),
      low: roundPrice(low, precision),
      close: roundedClose,
      volume,
      turnover: roundedClose * volume
    }

    open = close
    return candle
  })
}

export function filterMarkets<T extends MarketListItem>(markets: T[], query: string, category: MarketCategory): T[] {
  const normalizedQuery = query.trim().toLowerCase()

  return markets.filter((market) => {
    const matchesCategory =
      category === 'all' || (category === 'favorites' ? market.favorite : market.category === category)
    const matchesQuery =
      normalizedQuery.length === 0 ||
      market.symbol.toLowerCase().includes(normalizedQuery) ||
      market.name.toLowerCase().includes(normalizedQuery) ||
      market.base.toLowerCase().includes(normalizedQuery) ||
      market.quote.toLowerCase().includes(normalizedQuery)

    return matchesCategory && matchesQuery
  })
}

export function getRealtimeQuoteMarkets<T extends MarketListItem>(
  markets: T[],
  selectedSymbol: string,
  firstScreenLimit = 6
): T[] {
  const picked = new Map<string, T>()
  const visibleLimit = Math.max(1, firstScreenLimit)

  markets.slice(0, visibleLimit).forEach((market) => {
    if (!canRequestRealtimeQuote(market)) return
    picked.set(market.symbol, market)
  })
  markets.forEach((market) => {
    if (market.favorite && canRequestRealtimeQuote(market)) picked.set(market.symbol, market)
  })

  const selectedMarket = markets.find((market) => market.symbol === selectedSymbol)
  if (selectedMarket && canRequestRealtimeQuote(selectedMarket)) picked.set(selectedMarket.symbol, selectedMarket)

  return Array.from(picked.values())
}

function canRequestRealtimeQuote(market: MarketListItem) {
  return market.quoteEnabled !== false
}

export function getPercentageStep(value: number): (typeof percentageSteps)[number] {
  const clampedValue = Math.min(100, Math.max(0, value))
  return percentageSteps.reduce((closest, step) =>
    Math.abs(step - clampedValue) < Math.abs(closest - clampedValue) ? step : closest
  )
}

export function getNextPriceValue(currentValue: string, nextDefaultPrice: string, userTouched: boolean, focused: boolean) {
  if (userTouched || focused) return currentValue
  return nextDefaultPrice
}

export function getPricePrecision(symbol: string) {
  if (symbol.includes('BTC') || symbol.includes('ETH') || symbol.includes('SOL') || symbol === 'US100') return 2
  if (symbol.includes('XRP')) return 4
  if (symbol.includes('XAU')) return 2
  if (symbol.endsWith('JPY')) return 3
  return 5
}

export function formatMarketPrice(symbol: string, value: number) {
  return value.toFixed(getPricePrecision(symbol))
}

function getSymbolBasePrice(symbol: string) {
  if (symbol.includes('BTC')) return 67_240
  if (symbol.includes('ETH')) return 3_420
  if (symbol.includes('SOL')) return 152.12
  if (symbol.includes('XRP')) return 2.481
  if (symbol.includes('XAU')) return 2_348
  if (symbol.endsWith('JPY')) return 156.42
  if (symbol === 'GBPUSD') return 1.2712
  if (symbol === 'AUDUSD') return 0.6642
  return 1.0832
}

function getMockCandleProfile(symbol: string, base: number) {
  if (symbol.endsWith('USDT')) {
    return {
      precision: symbol.includes('XRP') ? 4 : 2,
      step: base * 0.0007,
      wick: base * 0.0011,
      shock: 0.034
    }
  }

  if (symbol.includes('XAU')) {
    return { precision: 2, step: 0.78, wick: 1.7, shock: 0.018 }
  }

  if (symbol === 'US100') {
    return { precision: 2, step: 5.2, wick: 11.4, shock: 0.02 }
  }

  if (symbol.endsWith('JPY')) {
    return { precision: 3, step: 0.022, wick: 0.05, shock: 0.012 }
  }

  return { precision: 5, step: 0.00011, wick: 0.00026, shock: 0.007 }
}

function roundPrice(value: number, precision: number) {
  return Number(value.toFixed(precision))
}

function createShockMap(count: number, shock: number, random: () => number) {
  const shocks = new Map<number, number>()
  if (count < 40) return shocks

  const firstDirection = random() > 0.5 ? 1 : -1
  const anchors = count >= 120 ? [0.23, 0.58, 0.82] : [0.34, 0.72]
  anchors.forEach((anchor, index) => {
    const jitter = Math.round(randomBetween(random, -count * 0.025, count * 0.025))
    const shockIndex = Math.min(count - 2, Math.max(1, Math.round(count * anchor) + jitter))
    const direction = index % 2 === 0 ? firstDirection : -firstDirection
    shocks.set(shockIndex, direction * shock * randomBetween(random, 0.9, 1.35))
  })
  return shocks
}

function createSeededRandom(seedText: string) {
  let seed = 2166136261
  for (let index = 0; index < seedText.length; index += 1) {
    seed ^= seedText.charCodeAt(index)
    seed = Math.imul(seed, 16777619)
  }

  return () => {
    seed += 0x6D2B79F5
    let value = seed
    value = Math.imul(value ^ (value >>> 15), value | 1)
    value ^= value + Math.imul(value ^ (value >>> 7), value | 61)
    return ((value ^ (value >>> 14)) >>> 0) / 4294967296
  }
}

function randomBetween(random: () => number, min: number, max: number) {
  return min + random() * (max - min)
}

function clamp(value: number, min: number, max: number) {
  return Math.min(Math.max(value, min), max)
}
