import type { TradingMarket } from './tradingModels'

type NumberLike = string | number | null | undefined

export type BinanceProduct = {
  s: string
  st?: string
  b: string
  q: string
  an?: string
  qn?: string
  o?: NumberLike
  h?: NumberLike
  l?: NumberLike
  c?: NumberLike
  v?: NumberLike
  qv?: NumberLike
  cs?: NumberLike
  tags?: string[]
  etf?: boolean
}

export type BinanceFearGreed = {
  value: number
  label: string
  updatedAt?: number
  source: string
}

export type BinanceMarketOverview = {
  markets: TradingMarket[]
  hotTokens: TradingMarket[]
  metrics: {
    marketCap: number
    volume24h: number
    fearGreed?: BinanceFearGreed
    updatedAt: number
    source: string
  }
}

export type BinanceFuturesPeriod = '5m' | '15m' | '30m' | '1h' | '2h' | '4h' | '6h' | '12h' | '1d'

export type BinanceFuturesTicker = {
  symbol: string
  highPrice?: NumberLike
  lastPrice?: NumberLike
  lowPrice?: NumberLike
  priceChangePercent?: NumberLike
  quoteVolume?: NumberLike
  volume?: NumberLike
  closeTime?: NumberLike
}

export type BinanceOpenInterestPoint = {
  sumOpenInterest?: NumberLike
  sumOpenInterestValue?: NumberLike
  CMCCirculatingSupply?: NumberLike
  timestamp?: NumberLike
}

export type BinanceLongShortPoint = {
  longShortRatio?: NumberLike
  longAccount?: NumberLike
  shortAccount?: NumberLike
  timestamp?: NumberLike
}

export type BinanceTakerBuySellPoint = {
  buySellRatio?: NumberLike
  buyVol?: NumberLike
  sellVol?: NumberLike
  timestamp?: NumberLike
}

export type BinanceBasisPoint = {
  futuresPrice?: NumberLike
  indexPrice?: NumberLike
  basis?: NumberLike
  basisRate?: NumberLike
  timestamp?: NumberLike
}

export type BinanceFundingRatePoint = {
  fundingRate?: NumberLike
  fundingTime?: NumberLike
}

export type BinanceFuturesDashboardPayload = {
  ticker: BinanceFuturesTicker
  openInterest: BinanceOpenInterestPoint[]
  topAccountRatio: BinanceLongShortPoint[]
  topPositionRatio: BinanceLongShortPoint[]
  globalLongShortRatio: BinanceLongShortPoint[]
  takerBuySell: BinanceTakerBuySellPoint[]
  basis: BinanceBasisPoint[]
  fundingRates: BinanceFundingRatePoint[]
}

export type BinanceFuturesChartPanelModel = {
  title: string
  subtitle?: string
  switchLabels?: string[]
  labels: string[]
  series: BinanceFuturesChartSeries[]
}

export type BinanceFuturesChartSeries = {
  label: string
  color: string
  values: number[]
  type: 'line' | 'area' | 'bar'
  suffix?: string
  prefix?: string
  compact?: boolean
  precision?: number
}

export type BinanceFuturesDashboard = {
  referenceMarket: TradingMarket
  panels: BinanceFuturesChartPanelModel[]
  updatedAt: number
}

type BinanceProductResponse = {
  code?: string
  data?: BinanceProduct[]
}

type AlternativeFearGreedResponse = {
  data?: Array<{
    value?: string
    value_classification?: string
    timestamp?: string
  }>
}

const binanceProductsUrl = 'https://www.binance.com/bapi/asset/v2/public/asset-service/product/get-products?includeEtf=true'
const fearGreedUrl = 'https://api.alternative.me/fng/?limit=1&format=json'
const futuresRestBase = 'https://fapi.binance.com'
const futuresDataBase = `${futuresRestBase}/futures/data`
const dashboardPointLimit = 14
const leveragedTokenPattern = /(UP|DOWN|BULL|BEAR)USDT$/u

export async function fetchBinanceMarketOverview(): Promise<BinanceMarketOverview> {
  const [products, fearGreed] = await Promise.all([
    fetchBinanceProducts(),
    fetchBinanceFearGreed().catch(() => undefined)
  ])
  return mapBinanceProductOverview(products, fearGreed)
}

export async function fetchBinanceFuturesDashboard(symbol = 'BTCUSDT', period: BinanceFuturesPeriod = '5m'): Promise<BinanceFuturesDashboard> {
  const normalizedSymbol = normalizeSymbol(symbol)
  const safePeriod = normalizeFuturesPeriod(period)
  const ticker = await fetchJson<BinanceFuturesTicker>(`${futuresRestBase}/fapi/v1/ticker/24hr?symbol=${encodeURIComponent(normalizedSymbol)}`)
  const [openInterest, topAccountRatio, topPositionRatio, globalLongShortRatio, takerBuySell, basis, fundingRates] = await Promise.all([
    fetchJsonArray<BinanceOpenInterestPoint>(`${futuresDataBase}/openInterestHist?symbol=${encodeURIComponent(normalizedSymbol)}&period=${safePeriod}&limit=${dashboardPointLimit}`),
    fetchJsonArray<BinanceLongShortPoint>(`${futuresDataBase}/topLongShortAccountRatio?symbol=${encodeURIComponent(normalizedSymbol)}&period=${safePeriod}&limit=${dashboardPointLimit}`),
    fetchJsonArray<BinanceLongShortPoint>(`${futuresDataBase}/topLongShortPositionRatio?symbol=${encodeURIComponent(normalizedSymbol)}&period=${safePeriod}&limit=${dashboardPointLimit}`),
    fetchJsonArray<BinanceLongShortPoint>(`${futuresDataBase}/globalLongShortAccountRatio?symbol=${encodeURIComponent(normalizedSymbol)}&period=${safePeriod}&limit=${dashboardPointLimit}`),
    fetchJsonArray<BinanceTakerBuySellPoint>(`${futuresDataBase}/takerlongshortRatio?symbol=${encodeURIComponent(normalizedSymbol)}&period=${safePeriod}&limit=${dashboardPointLimit}`),
    fetchJsonArray<BinanceBasisPoint>(`${futuresDataBase}/basis?pair=${encodeURIComponent(normalizedSymbol)}&contractType=PERPETUAL&period=${safePeriod}&limit=${dashboardPointLimit}`),
    fetchJsonArray<BinanceFundingRatePoint>(`${futuresRestBase}/fapi/v1/fundingRate?symbol=${encodeURIComponent(normalizedSymbol)}&limit=${dashboardPointLimit}`)
  ])

  return buildBinanceFuturesDashboard({
    ticker,
    openInterest,
    topAccountRatio,
    topPositionRatio,
    globalLongShortRatio,
    takerBuySell,
    basis,
    fundingRates
  })
}

export function mapBinanceProductOverview(products: BinanceProduct[], fearGreed?: BinanceFearGreed): BinanceMarketOverview {
  const rows = products.map(toProductMarketRow).filter((row): row is ProductMarketRow => row !== null)
  const markets = rows
    .map((row) => row.market)
    .sort((left, right) => (right.marketCap ?? 0) - (left.marketCap ?? 0))
  const hotTokens = [...rows]
    .sort((left, right) => right.quoteVolume - left.quoteVolume || Math.abs(right.market.changePercent) - Math.abs(left.market.changePercent))
    .slice(0, 10)
    .map((row) => row.market)

  return {
    markets,
    hotTokens,
    metrics: {
      marketCap: roundNumber(rows.reduce((sum, row) => sum + (row.market.marketCap ?? 0), 0)),
      volume24h: roundNumber(rows.reduce((sum, row) => sum + row.quoteVolume, 0)),
      fearGreed,
      updatedAt: Date.now(),
      source: 'binance-market-overview'
    }
  }
}

export function buildBinanceFuturesDashboard(payload: BinanceFuturesDashboardPayload): BinanceFuturesDashboard {
  const symbol = normalizeSymbol(payload.ticker.symbol || 'BTCUSDT')
  const base = symbol.endsWith('USDT') ? symbol.slice(0, -4) : symbol
  const lastPrice = numberValue(payload.ticker.lastPrice)
  const latestOpenInterest = lastItem(payload.openInterest)
  const circulatingSupply = numberValue(latestOpenInterest?.CMCCirculatingSupply)
  const marketCap = lastPrice > 0 && circulatingSupply > 0 ? roundNumber(lastPrice * circulatingSupply) : undefined
  const latestFundingRate = numberValue(lastItem(payload.fundingRates)?.fundingRate)

  return {
    referenceMarket: {
      symbol,
      base,
      quote: 'USDT',
      name: `${base} / Tether Perpetual`,
      category: 'crypto',
      favorite: false,
      last: lastPrice,
      changePercent: numberValue(payload.ticker.priceChangePercent),
      volume: formatCompactNumber(numberValue(payload.ticker.quoteVolume)),
      high24h: numberValue(payload.ticker.highPrice) || lastPrice,
      low24h: numberValue(payload.ticker.lowPrice) || lastPrice,
      spread: 0,
      source: 'binance-usds-m-futures',
      provider: 'binance',
      providerSymbol: symbol,
      tradable: true,
      chartEnabled: true,
      orderBookEnabled: true,
      marketCap,
      quoteTimestamp: numberValue(payload.ticker.closeTime) || Date.now()
    },
    panels: [
      openInterestPanel(payload.openInterest),
      longShortPanel('大户账户数多空比', '多空账户数比值', payload.topAccountRatio),
      longShortPanel('大户持仓量多空比', '多空持仓量比值', payload.topPositionRatio),
      longShortPanel('多空账户数比', '多空账户比值', payload.globalLongShortRatio),
      takerBuySellPanel(payload.takerBuySell),
      basisPanel(payload.basis),
      fundingRatePanel(payload.fundingRates, latestFundingRate),
      openInterestMarketCapRatioPanel(payload.openInterest, lastPrice)
    ],
    updatedAt: Date.now()
  }
}

async function fetchBinanceProducts() {
  const payload = await fetchJson<BinanceProductResponse>(binanceProductsUrl)
  return Array.isArray(payload.data) ? payload.data : []
}

async function fetchBinanceFearGreed(): Promise<BinanceFearGreed | undefined> {
  const payload = await fetchJson<AlternativeFearGreedResponse>(fearGreedUrl)
  const latest = payload.data?.[0]
  if (!latest) return undefined
  const value = numberValue(latest.value)
  if (value <= 0) return undefined
  return {
    value,
    label: latest.value_classification || classifyFearGreed(value),
    updatedAt: numberValue(latest.timestamp) * 1000 || undefined,
    source: 'alternative.me'
  }
}

async function fetchJsonArray<T>(url: string): Promise<T[]> {
  return fetchJson<T[]>(url).catch(() => [])
}

async function fetchJson<T>(url: string): Promise<T> {
  const response = await fetch(url, { headers: { Accept: 'application/json' } })
  if (!response.ok) {
    throw new Error(`Binance request failed: ${response.status}`)
  }
  return (await response.json()) as T
}

type ProductMarketRow = {
  market: TradingMarket
  quoteVolume: number
}

function toProductMarketRow(product: BinanceProduct): ProductMarketRow | null {
  const symbol = normalizeSymbol(product.s)
  if (!symbol || product.q !== 'USDT' || product.st !== 'TRADING' || product.etf === true || leveragedTokenPattern.test(symbol)) {
    return null
  }

  const last = numberValue(product.c)
  const open = numberValue(product.o)
  const high = numberValue(product.h) || last
  const low = numberValue(product.l) || last
  const baseVolume = numberValue(product.v)
  const quoteVolume = numberValue(product.qv) || baseVolume * last
  const circulatingSupply = numberValue(product.cs)
  if (last <= 0 || quoteVolume <= 0) return null

  const marketCap = circulatingSupply > 0 ? roundNumber(circulatingSupply * last) : undefined
  const changePercent = open > 0 ? roundNumber(((last - open) / open) * 100) : 0
  return {
    market: {
      symbol,
      base: normalizeSymbol(product.b),
      quote: 'USDT',
      name: `${product.an || product.b} / ${product.qn || 'Tether'}`,
      category: 'crypto',
      favorite: false,
      last,
      changePercent,
      volume: formatCompactNumber(quoteVolume),
      high24h: high,
      low24h: low,
      spread: 0,
      source: 'binance-market-overview',
      provider: 'binance',
      providerSymbol: symbol,
      tradable: true,
      quoteEnabled: true,
      chartEnabled: true,
      orderBookEnabled: true,
      marketCap
    },
    quoteVolume
  }
}

function openInterestPanel(rows: BinanceOpenInterestPoint[]): BinanceFuturesChartPanelModel {
  return {
    title: '合约持仓量',
    switchLabels: ['单边', '双边'],
    labels: labelsFrom(rows, (row) => row.timestamp),
    series: [
      { label: '持仓总数量(BTC)', color: '#fcd535', values: valuesFrom(rows, (row) => numberValue(row.sumOpenInterest)), type: 'bar' },
      {
        label: '持仓总价值(USDT)',
        color: '#eaecef',
        values: valuesFrom(rows, (row) => numberValue(row.sumOpenInterestValue) / 1_000_000),
        type: 'line',
        suffix: 'M',
        precision: 0
      }
    ]
  }
}

function longShortPanel(title: string, subtitle: string, rows: BinanceLongShortPoint[]): BinanceFuturesChartPanelModel {
  return {
    title,
    subtitle,
    labels: labelsFrom(rows, (row) => row.timestamp),
    series: [{ label: subtitle, color: '#fcd535', values: valuesFrom(rows, (row) => numberValue(row.longShortRatio)), type: 'line', precision: 3 }]
  }
}

function takerBuySellPanel(rows: BinanceTakerBuySellPoint[]): BinanceFuturesChartPanelModel {
  return {
    title: '合约主动买卖量',
    labels: labelsFrom(rows, (row) => row.timestamp),
    series: [
      { label: '主动卖出量(BTC)', color: '#f6465d', values: valuesFrom(rows, (row) => numberValue(row.sellVol)), type: 'bar', precision: 0 },
      { label: '主动买入量(BTC)', color: '#0ecb81', values: valuesFrom(rows, (row) => numberValue(row.buyVol)), type: 'bar', precision: 0 }
    ]
  }
}

function basisPanel(rows: BinanceBasisPoint[]): BinanceFuturesChartPanelModel {
  return {
    title: '基差',
    labels: labelsFrom(rows, (row) => row.timestamp),
    series: [
      { label: '合约价格', color: '#fcd535', values: valuesFrom(rows, (row) => numberValue(row.futuresPrice)), type: 'line', prefix: '$', compact: true },
      { label: '价格指数', color: '#2ebd85', values: valuesFrom(rows, (row) => numberValue(row.indexPrice)), type: 'line', prefix: '$', compact: true },
      { label: '基差', color: '#f6465d', values: valuesFrom(rows, (row) => numberValue(row.basis)), type: 'line', precision: 2 }
    ]
  }
}

function fundingRatePanel(rows: BinanceFundingRatePoint[], latestRate: number): BinanceFuturesChartPanelModel {
  return {
    title: `资金费率: ${formatFundingPercent(latestRate)}%`,
    subtitle: `最近 ${Math.max(rows.length, 1)} 次`,
    labels: labelsFrom(rows, (row) => row.fundingTime),
    series: [
      {
        label: '资金费率',
        color: '#fcd535',
        values: valuesFrom(rows, (row) => numberValue(row.fundingRate) * 100),
        type: 'bar',
        suffix: '%',
        precision: 6
      }
    ]
  }
}

function openInterestMarketCapRatioPanel(rows: BinanceOpenInterestPoint[], lastPrice: number): BinanceFuturesChartPanelModel {
  return {
    title: '未平仓量与市值比率',
    labels: labelsFrom(rows, (row) => row.timestamp),
    series: [
      {
        label: '委托价格',
        color: '#fcd535',
        values: valuesFrom(rows, () => lastPrice),
        type: 'line',
        prefix: '$',
        compact: true
      },
      {
        label: '比率',
        color: '#2ebd85',
        values: valuesFrom(rows, (row) => {
          const openInterestValue = numberValue(row.sumOpenInterestValue)
          const circulatingSupply = numberValue(row.CMCCirculatingSupply)
          const marketCap = circulatingSupply * lastPrice
          return marketCap > 0 ? (openInterestValue / marketCap) * 100 : 0
        }),
        type: 'line',
        suffix: '%',
        precision: 3
      }
    ]
  }
}

function labelsFrom<T>(rows: T[], timestamp: (row: T) => NumberLike) {
  const labels = rows.map((row) => formatTimestampLabel(numberValue(timestamp(row)))).filter(Boolean)
  return labels.length > 0 ? labels : ['--']
}

function valuesFrom<T>(rows: T[], value: (row: T) => number) {
  const values = rows.map(value).filter(Number.isFinite)
  return values.length > 0 ? values : [0]
}

function formatTimestampLabel(timestamp: number) {
  if (timestamp <= 0) return ''
  return new Intl.DateTimeFormat('en-US', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  }).format(new Date(timestamp))
}

function classifyFearGreed(value: number) {
  if (value <= 24) return 'Extreme Fear'
  if (value <= 49) return 'Fear'
  if (value <= 50) return 'Neutral'
  if (value <= 74) return 'Greed'
  return 'Extreme Greed'
}

function formatFundingPercent(value: number) {
  return (value * 100).toFixed(6)
}

function formatCompactNumber(value: number) {
  if (!Number.isFinite(value) || value <= 0) return '0'
  return new Intl.NumberFormat('en-US', {
    maximumFractionDigits: 2,
    notation: 'compact'
  }).format(value)
}

function lastItem<T>(items: T[]) {
  return items.at(-1)
}

function numberValue(value: NumberLike) {
  if (value === null || value === undefined || value === '') return 0
  const number = Number(value)
  return Number.isFinite(number) ? number : 0
}

function normalizeFuturesPeriod(period: BinanceFuturesPeriod) {
  return period
}

function normalizeSymbol(symbol: NumberLike) {
  return String(symbol ?? '').trim().toUpperCase()
}

function roundNumber(value: number) {
  return Number(value.toFixed(10))
}
