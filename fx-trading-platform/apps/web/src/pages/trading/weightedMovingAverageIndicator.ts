import { getSupportedIndicators, registerIndicator } from 'klinecharts'

type KLineData = {
  close: number
}

type WeightedMovingAverageResult = Record<string, number | undefined>

let registered = false

export function registerWeightedMovingAverageIndicator() {
  if (registered) return
  if (getSupportedIndicators().includes('WMA')) {
    registered = true
    return
  }

  registerIndicator<WeightedMovingAverageResult, number>({
    name: 'WMA',
    shortName: 'WMA',
    series: 'price',
    calcParams: [5, 10, 20, 30, 60, 120],
    precision: 2,
    shouldOhlc: true,
    figures: [
      { key: 'wma1', title: 'WMA5: ', type: 'line' },
      { key: 'wma2', title: 'WMA10: ', type: 'line' },
      { key: 'wma3', title: 'WMA20: ', type: 'line' },
      { key: 'wma4', title: 'WMA30: ', type: 'line' },
      { key: 'wma5', title: 'WMA60: ', type: 'line' },
      { key: 'wma6', title: 'WMA120: ', type: 'line' }
    ],
    regenerateFigures: (params) => params.map((period, index) => ({
      key: `wma${index + 1}`,
      title: `WMA${period}: `,
      type: 'line'
    })),
    calc: (dataList: KLineData[], indicator) => {
      const { calcParams, figures } = indicator
      return dataList.map((_, index) => {
        const result: WeightedMovingAverageResult = {}
        calcParams.forEach((period, paramIndex) => {
          if (index < period - 1) return

          let weightedSum = 0
          let weightSum = 0
          for (let offset = 0; offset < period; offset += 1) {
            const weight = period - offset
            weightedSum += dataList[index - offset].close * weight
            weightSum += weight
          }
          result[figures[paramIndex].key] = weightedSum / weightSum
        })
        return result
      })
    }
  })

  registered = true
}
