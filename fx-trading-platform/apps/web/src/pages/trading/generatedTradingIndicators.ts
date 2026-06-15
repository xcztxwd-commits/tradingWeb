import { getSupportedIndicators, registerIndicator } from 'klinecharts'

type KLineData = {
  open: number
  high: number
  low: number
  close: number
  volume?: number
  turnover?: number
}

type IndicatorRuntime = {
  calcParams: number[]
}

type IndicatorResult = Record<string, number | undefined>

let registered = false

export function registerTradingGeneratedIndicators() {
  if (registered) return

  registerLineIndicator('AVL', 'AVL', 'price', [20], [{ key: 'avl', title: 'AVL: ', type: 'line' }], (dataList, params) =>
    rollingTypicalAverage(dataList, getPeriod(params, 0, 20), 'avl')
  )
  registerLineIndicator('VWAP', 'VWAP', 'price', [20], [{ key: 'vwap', title: 'VWAP: ', type: 'line' }], (dataList, params) =>
    rollingVwap(dataList, getPeriod(params, 0, 20))
  )
  registerLineIndicator(
    'SUPER_TREND',
    'ST',
    'price',
    [10, 3],
    [{ key: 'superTrend', title: 'ST: ', type: 'line' }],
    (dataList, params) => superTrend(dataList, getPeriod(params, 0, 10), getPeriod(params, 1, 3))
  )
  registerLineIndicator(
    'SUPPORT_RESISTANCE',
    'S/R',
    'price',
    [20],
    [
      { key: 'resistance', title: 'R: ', type: 'line' },
      { key: 'support', title: 'S: ', type: 'line' }
    ],
    (dataList, params) => supportResistance(dataList, getPeriod(params, 0, 20))
  )
  registerLineIndicator('OI', 'OI', 'normal', [14], [{ key: 'oi', title: 'OI: ', type: 'line' }], (dataList, params) =>
    syntheticOpenInterest(dataList, getPeriod(params, 0, 14))
  )
  registerLineIndicator(
    'TOP_ACC_LS',
    'Top Acc. L/S',
    'normal',
    [14],
    [{ key: 'topAccLs', title: 'Top Acc L/S: ', type: 'line' }],
    (dataList, params) => syntheticLongShortRatio(dataList, getPeriod(params, 0, 14), 'topAccLs', 1.16)
  )
  registerLineIndicator(
    'TOP_POS_LS',
    'Top Pos. L/S',
    'normal',
    [14],
    [{ key: 'topPosLs', title: 'Top Pos L/S: ', type: 'line' }],
    (dataList, params) => syntheticLongShortRatio(dataList, getPeriod(params, 0, 14), 'topPosLs', 1.08)
  )
  registerLineIndicator(
    'ACC_LS',
    'Acc. L/S',
    'normal',
    [14],
    [{ key: 'accLs', title: 'Acc L/S: ', type: 'line' }],
    (dataList, params) => syntheticLongShortRatio(dataList, getPeriod(params, 0, 14), 'accLs', 1)
  )
  registerLineIndicator(
    'TAKER_BS',
    'Taker B/S',
    'normal',
    [14],
    [{ key: 'takerBs', title: 'Taker B/S: ', type: 'bar', baseValue: 0 }],
    (dataList, params) => syntheticTakerBalance(dataList, getPeriod(params, 0, 14))
  )
  registerLineIndicator(
    'SKDJ',
    'SKDJ',
    'normal',
    [9, 3, 3],
    [
      { key: 'k', title: 'K: ', type: 'line' },
      { key: 'd', title: 'D: ', type: 'line' }
    ],
    (dataList, params) => smoothKdj(dataList, getPeriod(params, 0, 9), getPeriod(params, 1, 3), getPeriod(params, 2, 3))
  )

  registered = true
}

function registerLineIndicator(
  name: string,
  shortName: string,
  series: 'normal' | 'price',
  calcParams: number[],
  figures: Array<{ key: string; title: string; type: 'line' | 'bar'; baseValue?: number }>,
  calc: (dataList: KLineData[], params: number[]) => IndicatorResult[]
) {
  if (getSupportedIndicators().includes(name)) return

  registerIndicator<IndicatorResult, number>({
    name,
    shortName,
    series,
    calcParams,
    precision: series === 'price' ? 2 : 4,
    shouldOhlc: series === 'price',
    figures,
    calc: (dataList: KLineData[], indicator: IndicatorRuntime) => calc(dataList, indicator.calcParams)
  })
}

function rollingTypicalAverage(dataList: KLineData[], period: number, key: string): IndicatorResult[] {
  return dataList.map((_, index) => {
    if (index < period - 1) return {}
    const window = dataList.slice(index - period + 1, index + 1)
    return {
      [key]: window.reduce((sum, item) => sum + typicalPrice(item), 0) / period
    }
  })
}

function rollingVwap(dataList: KLineData[], period: number): IndicatorResult[] {
  return dataList.map((_, index) => {
    const window = dataList.slice(Math.max(0, index - period + 1), index + 1)
    const sums = window.reduce(
      (total, item) => {
        const volume = item.volume ?? 0
        total.turnover += (item.turnover ?? typicalPrice(item) * volume)
        total.volume += volume
        return total
      },
      { turnover: 0, volume: 0 }
    )
    return sums.volume > 0 ? { vwap: sums.turnover / sums.volume } : {}
  })
}

function superTrend(dataList: KLineData[], period: number, multiplier: number): IndicatorResult[] {
  let previousTrend = 0
  return dataList.map((item, index) => {
    if (index < period) return {}
    const atr = averageTrueRange(dataList, index, period)
    const median = (item.high + item.low) / 2
    const upper = median + multiplier * atr
    const lower = median - multiplier * atr
    const nextTrend = previousTrend === 0 ? lower : item.close >= previousTrend ? Math.max(lower, previousTrend) : Math.min(upper, previousTrend)
    previousTrend = nextTrend
    return { superTrend: nextTrend }
  })
}

function supportResistance(dataList: KLineData[], period: number): IndicatorResult[] {
  return dataList.map((_, index) => {
    if (index < period - 1) return {}
    const window = dataList.slice(index - period + 1, index + 1)
    return {
      resistance: Math.max(...window.map((item) => item.high)),
      support: Math.min(...window.map((item) => item.low))
    }
  })
}

function syntheticOpenInterest(dataList: KLineData[], period: number): IndicatorResult[] {
  let running = 0
  return dataList.map((item, index) => {
    const range = Math.max(item.high - item.low, Math.abs(item.close) * 0.0001)
    const pressure = Math.abs(item.close - item.open) / range
    running += (item.volume ?? 0) * (1 + pressure)
    if (index >= period) running -= (dataList[index - period].volume ?? 0) * 0.72
    return { oi: Math.max(0, running) }
  })
}

function syntheticLongShortRatio(dataList: KLineData[], period: number, key: string, bias: number): IndicatorResult[] {
  return dataList.map((_, index) => {
    const window = dataList.slice(Math.max(0, index - period + 1), index + 1)
    const score = window.reduce((sum, item) => sum + candlePressure(item), 0) / Math.max(1, window.length)
    return { [key]: Math.max(0.2, bias + score * 0.24) }
  })
}

function syntheticTakerBalance(dataList: KLineData[], period: number): IndicatorResult[] {
  return dataList.map((_, index) => {
    const window = dataList.slice(Math.max(0, index - period + 1), index + 1)
    const balance = window.reduce((sum, item) => sum + candlePressure(item) * (item.volume ?? 0), 0)
    const volume = window.reduce((sum, item) => sum + (item.volume ?? 0), 0)
    return { takerBs: volume > 0 ? (balance / volume) * 100 : 0 }
  })
}

function smoothKdj(dataList: KLineData[], period: number, kPeriod: number, dPeriod: number): IndicatorResult[] {
  let previousK = 50
  let previousD = 50
  return dataList.map((item, index) => {
    if (index < period - 1) return {}
    const window = dataList.slice(index - period + 1, index + 1)
    const high = Math.max(...window.map((data) => data.high))
    const low = Math.min(...window.map((data) => data.low))
    const rsv = ((item.close - low) / Math.max(high - low, 1)) * 100
    const k = ((kPeriod - 1) * previousK + rsv) / kPeriod
    const d = ((dPeriod - 1) * previousD + k) / dPeriod
    previousK = k
    previousD = d
    return { k, d }
  })
}

function averageTrueRange(dataList: KLineData[], index: number, period: number) {
  let total = 0
  for (let offset = 0; offset < period; offset += 1) {
    const current = dataList[index - offset]
    const previous = dataList[index - offset - 1]
    total += Math.max(
      current.high - current.low,
      Math.abs(current.high - (previous?.close ?? current.close)),
      Math.abs(current.low - (previous?.close ?? current.close))
    )
  }
  return total / period
}

function candlePressure(item: KLineData) {
  const range = Math.max(item.high - item.low, Math.abs(item.close) * 0.0001)
  return (item.close - item.open) / range
}

function typicalPrice(item: KLineData) {
  return (item.high + item.low + item.close) / 3
}

function getPeriod(params: number[], index: number, fallback: number) {
  const value = params[index]
  return Number.isFinite(value) && value > 0 ? Math.round(value) : fallback
}
