import {
  dispose as disposeKLineChart,
  getSupportedIndicators,
  init as initKLineChart,
  registerIndicator,
  type Chart,
  type IndicatorCreate,
  type IndicatorTemplate,
  type KLineData,
  type OverlayCreate,
} from 'klinecharts'

import {
  buildTradingLabCandleTooltipLegends,
  buildTradingLabPriceLineTooltipLegends,
  type TradingLabChartDataLoader,
  type TradingLabChartRuntimeAdapter,
  type TradingLabChartRuntimeBar,
  type TradingLabChartRuntimeChart,
  type TradingLabChartRuntimeOverlay,
  type TradingLabChartRuntimePeriod,
  type TradingLabChartRuntimePriceLine,
} from './chartRuntime.ts'

const PRICE_LINES_INDICATOR = 'TRADING_LAB_PRICE_LINES'
const PRICE_LINES_TEMPLATE: IndicatorTemplate = {
  name: PRICE_LINES_INDICATOR,
  shortName: '价格线',
  series: 'price',
  figures: [],
  calc: (dataList) => dataList.map(() => ({})),
}

if (!getSupportedIndicators().includes(PRICE_LINES_INDICATOR)) {
  registerIndicator(PRICE_LINES_TEMPLATE)
}

function kLineData(
  bar: TradingLabChartRuntimeBar,
): KLineData {
  return { ...bar }
}

function overlayCreate(
  overlay: TradingLabChartRuntimeOverlay,
): OverlayCreate {
  return {
    id: overlay.id,
    groupId: overlay.groupId,
    name: overlay.name,
    points: overlay.points.map((point) => ({ ...point })),
    extendData: overlay.extendData,
    lock: true,
    visible: true,
  }
}

class KLineChartRuntimeChart implements TradingLabChartRuntimeChart {
  readonly raw: Chart

  constructor(chart: Chart) {
    this.raw = chart
  }

  setSymbol(symbol: Record<string, unknown>): void {
    const {
      ticker,
      name,
      productType,
      pricePrecision,
      volumePrecision,
    } = symbol
    if (
      typeof ticker !== 'string'
      || typeof name !== 'string'
      || typeof productType !== 'string'
      || !Number.isSafeInteger(pricePrecision)
      || !Number.isSafeInteger(volumePrecision)
    ) {
      throw new Error('KLineCharts symbol projection is invalid')
    }
    this.raw.setSymbol({
      ticker,
      name,
      productType,
      pricePrecision: pricePrecision as number,
      volumePrecision: volumePrecision as number,
    })
  }

  setPeriod(period: TradingLabChartRuntimePeriod): void {
    this.raw.setPeriod({
      type: period.type,
      span: period.span,
    })
  }

  setStyles(styles: Record<string, unknown>): void {
    const candle = styles.candle
    const type = (
      candle !== null
      && typeof candle === 'object'
      && 'type' in candle
    )
      ? candle.type
      : null
    if (type !== 'area' && type !== 'candle_solid') {
      throw new Error('KLineCharts candle style projection is invalid')
    }
    this.raw.setStyles({
      candle: {
        type,
        tooltip: {
          legend: {
            template: ({
              current,
            }: { current: KLineData | null }) => (
              buildTradingLabCandleTooltipLegends(current)
            ),
          },
        },
      },
    })
  }

  setPriceLines(
    lines: readonly TradingLabChartRuntimePriceLine[],
  ): void {
    this.raw.removeIndicator({ name: PRICE_LINES_INDICATOR })
    if (lines.length === 0) {
      return
    }
    const indicator: IndicatorCreate = {
      name: PRICE_LINES_INDICATOR,
      shortName: '价格线',
      series: 'price',
      figures: lines.map((line) => ({
        key: line.key,
        title: line.label,
        type: 'line',
        styles: () => ({
          color: line.color,
          size: 1,
        }),
      })),
      calc: (dataList) => dataList.map((data) => (
        Object.fromEntries(lines.map((line) => [
          line.key,
          typeof data[line.key] === 'number'
            ? data[line.key] as number
            : null,
        ]))
      )),
      createTooltipDataSource: ({ chart, crosshair }) => {
        const dataIndex = crosshair.dataIndex
        const current = (
          typeof dataIndex === 'number'
          && Number.isSafeInteger(dataIndex)
          && dataIndex >= 0
        )
          ? chart.getDataList()[dataIndex] ?? null
          : null
        return {
          name: '价格线',
          calcParamsText: '',
          features: [],
          legends: buildTradingLabPriceLineTooltipLegends(
            current,
            lines,
          ),
        }
      },
    }
    const indicatorId = this.raw.createIndicator(indicator, {
      pane: { id: 'candle_pane' },
      isStack: true,
    })
    if (indicatorId === null) {
      throw new Error('KLineCharts price-line indicator creation failed')
    }
  }

  setDataLoader(loader: TradingLabChartDataLoader): void {
    let runtimeSubscription:
      | ((bar: TradingLabChartRuntimeBar) => void)
      | null = null
    this.raw.setDataLoader({
      getBars: ({ type, timestamp, callback }) => loader.getBars({
        type,
        timestamp,
        callback(data, more) {
          callback(data.map(kLineData), more)
        },
      }),
      subscribeBar: ({ callback }) => {
        runtimeSubscription = (bar): void => {
          callback(kLineData(bar))
        }
        loader.subscribeBar?.({ callback: runtimeSubscription })
      },
      unsubscribeBar: () => {
        if (runtimeSubscription !== null) {
          loader.unsubscribeBar?.({ callback: runtimeSubscription })
          runtimeSubscription = null
        }
      },
    })
  }

  resetData(): void {
    this.raw.resetData()
  }

  removeOverlay(filter: { groupId: string }): boolean {
    return this.raw.removeOverlay(filter)
  }

  createOverlay(
    overlays: readonly TradingLabChartRuntimeOverlay[],
  ): unknown {
    return this.raw.createOverlay(overlays.map(overlayCreate))
  }

  scrollToTimestamp(timestamp: number): void {
    this.raw.scrollToTimestamp(timestamp)
  }

  resize(): void {
    this.raw.resize()
  }
}

export const klineChartAdapter: TradingLabChartRuntimeAdapter = {
  init(container) {
    const chart = initKLineChart(container)
    return chart === null ? null : new KLineChartRuntimeChart(chart)
  },
  dispose(chart) {
    if (!(chart instanceof KLineChartRuntimeChart)) {
      throw new Error('KLineCharts dispose target is invalid')
    }
    disposeKLineChart(chart.raw)
  },
}
