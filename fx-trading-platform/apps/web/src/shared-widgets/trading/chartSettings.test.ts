import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import {
  buildIndicatorApplyPlan,
  chartTypeToCandleType,
  cloneIndicatorSettings,
  defaultChartSettings,
  defaultIndicatorSettings,
  drawingToolGroups,
  drawingToolOptions,
  drawingToolToOverlayName,
  chartTimezoneOptions,
  indicatorConfigOptions,
  getDrawingShortcutAction,
  getChartSettingsStorageKey,
  getChartVisualStyles,
  getIntervalByShortcutKey,
  loadChartSettings,
  quickChartIntervals,
  priceScaleModeOptions,
  saveChartSettings,
  shouldIgnoreDrawingShortcut,
  shouldIgnoreIntervalShortcut,
  toggleFavoriteInterval,
  updateIndicatorEnabled
} from './chartSettings.ts'

class MemoryStorage {
  private readonly values = new Map<string, string>()

  getItem(key: string) {
    return this.values.get(key) ?? null
  }

  setItem(key: string, value: string) {
    this.values.set(key, value)
  }
}

describe('chart settings', () => {
  it('loads a fresh copy of defaults when storage is empty', () => {
    const storage = new MemoryStorage()
    const first = loadChartSettings('EURUSD', storage)
    const second = loadChartSettings('EURUSD', storage)

    assert.deepEqual(first, defaultChartSettings)
    assert.notEqual(first, defaultChartSettings)
    first.interval = '15m'
    assert.equal(second.interval, '1m')
  })

  it('persists settings per symbol and keeps missing fields on default values', () => {
    const storage = new MemoryStorage()
    const nextSettings = {
      ...defaultChartSettings,
      interval: '4h' as const,
      chartType: 'line' as const,
      candleStyle: {
        ...defaultChartSettings.candleStyle,
        useCustomColors: true
      }
    }

    saveChartSettings('BTCUSDT', nextSettings, storage)

    const loaded = loadChartSettings('BTCUSDT', storage)
    assert.equal(loaded.interval, '4h')
    assert.equal(loaded.chartType, 'line')
    assert.equal(loaded.candleStyle.useCustomColors, true)
    assert.equal(loaded.axisSettings.latestPriceLineType, defaultChartSettings.axisSettings.latestPriceLineType)
    assert.equal(loadChartSettings('EURUSD', storage).interval, '1m')
  })

  it('persists and validates KLineCharts price scale modes', () => {
    const storage = new MemoryStorage()
    storage.setItem(
      getChartSettingsStorageKey('BTCUSDT'),
      JSON.stringify({
        axisSettings: {
          priceScaleMode: 'percentage'
        }
      })
    )
    storage.setItem(
      getChartSettingsStorageKey('ETHUSDT'),
      JSON.stringify({
        axisSettings: {
          priceScaleMode: 'invalid'
        }
      })
    )

    assert.deepEqual(priceScaleModeOptions.map((item) => item.value), ['normal', 'percentage', 'logarithm'])
    assert.equal(loadChartSettings('BTCUSDT', storage).axisSettings.priceScaleMode, 'percentage')
    assert.equal(loadChartSettings('ETHUSDT', storage).axisSettings.priceScaleMode, 'normal')
  })

  it('persists chart dialog display toggles and validates timezones', () => {
    const storage = new MemoryStorage()
    storage.setItem(
      getChartSettingsStorageKey('BTCUSDT'),
      JSON.stringify({
        timezone: 'Asia/Tokyo',
        axisSettings: {
          latestPrice: false,
          highPriceMark: false,
          lowPriceMark: true,
          countdown: false
        },
        layoutSettings: {
          gridLines: 'none'
        }
      })
    )
    storage.setItem(
      getChartSettingsStorageKey('ETHUSDT'),
      JSON.stringify({
        timezone: 'Mars/Base',
        layoutSettings: {
          gridLines: 'diagonal'
        }
      })
    )

    assert.deepEqual(chartTimezoneOptions.map((item) => item.value), [
      'local',
      'UTC',
      'Asia/Shanghai',
      'Asia/Singapore',
      'Asia/Tokyo',
      'Europe/London',
      'America/New_York'
    ])

    const loaded = loadChartSettings('BTCUSDT', storage)
    assert.equal(loaded.timezone, 'Asia/Tokyo')
    assert.equal(loaded.axisSettings.latestPrice, false)
    assert.equal(loaded.axisSettings.highPriceMark, false)
    assert.equal(loaded.axisSettings.lowPriceMark, true)
    assert.equal(loaded.axisSettings.countdown, false)
    assert.equal(loaded.layoutSettings.gridLines, 'none')

    const fallback = loadChartSettings('ETHUSDT', storage)
    assert.equal(fallback.timezone, defaultChartSettings.timezone)
    assert.equal(fallback.layoutSettings.gridLines, defaultChartSettings.layoutSettings.gridLines)
  })

  it('persists chart visual controls for marks, tooltip, zoom, and shortcuts', () => {
    const storage = new MemoryStorage()
    storage.setItem(
      getChartSettingsStorageKey('BTCUSDT'),
      JSON.stringify({
        axisSettings: {
          highPriceMark: false,
          lowPriceMark: false,
          tooltipStyle: 'compact',
          barSpace: 14
        },
        shortcutSettings: {
          intervalShortcuts: false,
          drawingShortcuts: true,
          fullscreenShortcut: false
        }
      })
    )
    storage.setItem(
      getChartSettingsStorageKey('ETHUSDT'),
      JSON.stringify({
        axisSettings: {
          tooltipStyle: 'unknown',
          barSpace: -4
        },
        shortcutSettings: {
          intervalShortcuts: 'nope'
        }
      })
    )

    const loaded = loadChartSettings('BTCUSDT', storage)
    assert.equal(loaded.axisSettings.highPriceMark, false)
    assert.equal(loaded.axisSettings.lowPriceMark, false)
    assert.equal(loaded.axisSettings.tooltipStyle, 'compact')
    assert.equal(loaded.axisSettings.barSpace, 14)
    assert.equal(loaded.shortcutSettings.intervalShortcuts, false)
    assert.equal(loaded.shortcutSettings.drawingShortcuts, true)
    assert.equal(loaded.shortcutSettings.fullscreenShortcut, false)

    const fallback = loadChartSettings('ETHUSDT', storage)
    assert.equal(fallback.axisSettings.tooltipStyle, defaultChartSettings.axisSettings.tooltipStyle)
    assert.equal(fallback.axisSettings.barSpace, defaultChartSettings.axisSettings.barSpace)
    assert.equal(fallback.shortcutSettings.intervalShortcuts, defaultChartSettings.shortcutSettings.intervalShortcuts)
  })

  it('defaults interval shortcuts to starred 15m, 1h, and 1d periods', () => {
    assert.deepEqual(defaultChartSettings.favoriteIntervals, ['15m', '1h', '1d'])
    assert.deepEqual(quickChartIntervals(defaultChartSettings).map((item) => item.value), ['15m', '1h', '1d'])
    assert.equal(getIntervalByShortcutKey('1', defaultChartSettings), '15m')
    assert.equal(getIntervalByShortcutKey('2', defaultChartSettings), '1h')
    assert.equal(getIntervalByShortcutKey('3', defaultChartSettings), '1d')
    assert.equal(getIntervalByShortcutKey('4', defaultChartSettings), null)
  })

  it('persists starred interval shortcuts and removes invalid stored periods', () => {
    const storage = new MemoryStorage()
    storage.setItem(
      getChartSettingsStorageKey('BTCUSDT'),
      JSON.stringify({
        favoriteIntervals: ['1s', 'bad', '30m', '1s', '3M']
      })
    )

    const loaded = loadChartSettings('BTCUSDT', storage)

    assert.deepEqual(loaded.favoriteIntervals, ['1s', '30m', '3M'])
    assert.deepEqual(quickChartIntervals(loaded).map((item) => item.value), ['1s', '30m', '3M'])
  })

  it('keeps at least one starred interval selected', () => {
    assert.deepEqual(toggleFavoriteInterval(['15m'], '15m'), ['15m'])
    assert.deepEqual(toggleFavoriteInterval(['15m', '1h'], '15m'), ['1h'])
    assert.deepEqual(toggleFavoriteInterval(['15m'], '30m'), ['15m', '30m'])
  })

  it('falls back to defaults for invalid stored JSON', () => {
    const storage = new MemoryStorage()
    storage.setItem(getChartSettingsStorageKey('EURUSD'), '{bad-json')

    assert.deepEqual(loadChartSettings('EURUSD', storage), defaultChartSettings)
  })

  it('maps supported chart types to KLineCharts candle types without recreating the chart', () => {
    assert.equal(chartTypeToCandleType('candle'), 'candle_solid')
    assert.equal(chartTypeToCandleType('bar'), 'ohlc')
    assert.equal(chartTypeToCandleType('hlc'), 'ohlc')
    assert.equal(chartTypeToCandleType('line'), 'area')
  })

  it('builds KLineCharts visual styles from persisted chart settings', () => {
    const styles = getChartVisualStyles({
      ...defaultChartSettings,
      chartType: 'bar',
      candleStyle: {
        ...defaultChartSettings.candleStyle,
        useCustomColors: true,
        upColor: '#00ff88',
        downColor: '#ff3355',
        upBorderColor: '#00cc66',
        downBorderColor: '#cc2244',
        upWickColor: '#33ffaa',
        downWickColor: '#ff6680'
      },
      axisSettings: {
        ...defaultChartSettings.axisSettings,
        latestPrice: false,
        highPriceMark: true,
        lowPriceMark: true,
        latestPriceColor: '#fcd535',
        latestPriceLineType: 'solid'
      },
      layoutSettings: {
        ...defaultChartSettings.layoutSettings,
        gridLines: 'horizontal',
        crosshair: {
          enabled: false,
          color: '#f0b90b',
          lineType: 'solid'
        }
      }
    })

    assert.equal(styles.candle.type, 'ohlc')
    assert.equal(styles.candle.bar.upColor, '#00ff88')
    assert.equal(styles.candle.bar.downBorderColor, '#cc2244')
    assert.equal(styles.candle.priceMark.last.show, false)
    assert.equal(styles.candle.priceMark.show, true)
    assert.equal(styles.candle.priceMark.high.show, true)
    assert.equal(styles.candle.priceMark.low.show, true)
    assert.equal(styles.candle.priceMark.last.line.style, 'solid')
    assert.equal(styles.candle.priceMark.last.upColor, '#fcd535')
    assert.equal(styles.candle.tooltip.showRule, 'follow_cross')
    assert.equal(styles.candle.tooltip.showType, 'standard')
    assert.equal(styles.grid.show, true)
    assert.equal(styles.grid.horizontal.show, true)
    assert.equal(styles.grid.vertical.show, false)
    assert.equal(styles.crosshair.show, false)
    assert.equal(styles.crosshair.horizontal.line.color, '#f0b90b')
  })

  it('maps high-low marks and tooltip style settings into KLineCharts visual styles', () => {
    const compact = getChartVisualStyles({
      ...defaultChartSettings,
      axisSettings: {
        ...defaultChartSettings.axisSettings,
        latestPrice: false,
        highPriceMark: false,
        lowPriceMark: false,
        tooltipStyle: 'compact'
      }
    })

    assert.equal(compact.candle.priceMark.show, false)
    assert.equal(compact.candle.priceMark.high.show, false)
    assert.equal(compact.candle.priceMark.low.show, false)
    assert.equal(compact.candle.tooltip.showRule, 'follow_cross')
    assert.equal(compact.candle.tooltip.showType, 'rect')
    assert.equal(compact.candle.tooltip.title.show, false)

    const hidden = getChartVisualStyles({
      ...defaultChartSettings,
      axisSettings: {
        ...defaultChartSettings.axisSettings,
        tooltipStyle: 'hidden'
      }
    })

    assert.equal(hidden.candle.tooltip.showRule, 'none')
  })

  it('exposes only KLineCharts-backed drawing tools', () => {
    assert.deepEqual(
      drawingToolOptions.map((item) => item.value),
      [
        'cursor',
        'segment',
        'arrowLine',
        'trendLine',
        'rayLine',
        'straightLine',
        'horizontalSegment',
        'horizontalRayLine',
        'horizontalLine',
        'priceLine',
        'verticalLine',
        'verticalSegment',
        'verticalRayLine',
        'fibonacciExtension',
        'fibonacciFan',
        'fibonacciLine',
        'rectangle',
        'brush',
        'circle',
        'triangle',
        'priceChannel',
        'parallelLine',
        'parallelRayLine',
        'priceTag',
        'text'
      ]
    )
    assert.equal(drawingToolToOverlayName('cursor'), null)
    assert.equal(drawingToolToOverlayName('segment'), 'segment')
    assert.equal(drawingToolToOverlayName('arrowLine'), 'tradingArrowLine')
    assert.equal(drawingToolToOverlayName('trendLine'), 'segment')
    assert.equal(drawingToolToOverlayName('straightLine'), 'straightLine')
    assert.equal(drawingToolToOverlayName('horizontalSegment'), 'horizontalSegment')
    assert.equal(drawingToolToOverlayName('horizontalRayLine'), 'horizontalRayLine')
    assert.equal(drawingToolToOverlayName('horizontalLine'), 'horizontalStraightLine')
    assert.equal(drawingToolToOverlayName('priceLine'), 'priceLine')
    assert.equal(drawingToolToOverlayName('verticalLine'), 'verticalStraightLine')
    assert.equal(drawingToolToOverlayName('verticalSegment'), 'verticalSegment')
    assert.equal(drawingToolToOverlayName('verticalRayLine'), 'verticalRayLine')
    assert.equal(drawingToolToOverlayName('fibonacciExtension'), 'tradingFibonacciExtension')
    assert.equal(drawingToolToOverlayName('fibonacciFan'), 'tradingFibonacciFan')
    assert.equal(drawingToolToOverlayName('parallelLine'), 'parallelStraightLine')
    assert.equal(drawingToolToOverlayName('priceChannel'), 'priceChannelLine')
    assert.equal(drawingToolToOverlayName('parallelRayLine'), 'tradingParallelRayLine')
    assert.equal(drawingToolToOverlayName('fibonacciLine'), 'fibonacciLine')
    assert.equal(drawingToolToOverlayName('rectangle'), 'tradingRectangle')
    assert.equal(drawingToolToOverlayName('brush'), 'brush')
    assert.equal(drawingToolToOverlayName('circle'), 'tradingCircle')
    assert.equal(drawingToolToOverlayName('triangle'), 'tradingTriangle')
    assert.equal(drawingToolToOverlayName('priceTag'), 'simpleTag')
    assert.equal(drawingToolToOverlayName('text'), 'simpleAnnotation')
  })

  it('groups professional drawing tools for the left rail flyout menus', () => {
    assert.deepEqual(
      drawingToolGroups.map((group) => group.value),
      ['line', 'fibonacci', 'shape', 'channel', 'text', 'visibility', 'magnet', 'clear']
    )
    assert.deepEqual(
      drawingToolGroups.find((group) => group.value === 'line')?.items.map((item) => item.label),
      [
        'chart.drawing.segment',
        'chart.drawing.arrowLine',
        'chart.drawing.trendLine',
        'chart.drawing.rayLine',
        'chart.drawing.straightLine',
        'chart.drawing.horizontalSegment',
        'chart.drawing.horizontalRayLine',
        'chart.drawing.horizontalLine',
        'chart.drawing.priceLine',
        'chart.drawing.verticalLine',
        'chart.drawing.verticalSegment',
        'chart.drawing.verticalRayLine'
      ]
    )
    assert.deepEqual(
      drawingToolGroups.find((group) => group.value === 'fibonacci')?.items.map((item) => item.label),
      ['chart.drawing.fibonacciLine', 'chart.drawing.fibonacciExtension', 'chart.drawing.fibonacciFan']
    )
    assert.equal(
      drawingToolGroups.find((group) => group.value === 'fibonacci')?.items[0].shortcut,
      'Ctrl + Alt + F'
    )
    assert.deepEqual(
      drawingToolGroups.find((group) => group.value === 'channel')?.items.map((item) => item.label),
      ['chart.drawing.priceChannel', 'chart.drawing.parallelLine', 'chart.drawing.parallelRayLine']
    )
    assert.deepEqual(
      drawingToolGroups.find((group) => group.value === 'shape')?.items.map((item) => item.label),
      ['chart.drawing.rectangle', 'chart.drawing.brush', 'chart.drawing.circle', 'chart.drawing.triangle', 'chart.drawing.priceTag']
    )
    assert.deepEqual(
      drawingToolGroups.find((group) => group.value === 'magnet')?.items.map((item) => item.label),
      ['chart.commands.weakMagnet', 'chart.commands.strongMagnet']
    )
  })

  it('drops unsupported persisted drawing tools back to the cursor', () => {
    const storage = new MemoryStorage()
    storage.setItem(
      getChartSettingsStorageKey('EURUSD'),
      JSON.stringify({
        drawingToolSettings: {
          activeTool: 'rect',
          magnetMode: 'strong',
          magnet: true
        }
      })
    )

    const loaded = loadChartSettings('EURUSD', storage)

    assert.equal(loaded.drawingToolSettings.activeTool, 'cursor')
    assert.equal(loaded.drawingToolSettings.magnetMode, 'strong')
  })

  it('keeps old boolean magnet settings compatible with the new magnet mode', () => {
    const storage = new MemoryStorage()
    storage.setItem(
      getChartSettingsStorageKey('EURUSD'),
      JSON.stringify({
        drawingToolSettings: {
          activeTool: 'segment',
          magnet: true
        }
      })
    )

    const loaded = loadChartSettings('EURUSD', storage)

    assert.equal(loaded.drawingToolSettings.activeTool, 'segment')
    assert.equal(loaded.drawingToolSettings.magnetMode, 'weak')
  })

  it('maps numeric shortcuts to the visible quick intervals', () => {
    assert.equal(getIntervalByShortcutKey('1'), '15m')
    assert.equal(getIntervalByShortcutKey('2'), '1h')
    assert.equal(getIntervalByShortcutKey('3'), '1d')
    assert.equal(getIntervalByShortcutKey('4'), null)
  })

  it('ignores numeric shortcuts while text entry or a popup is active', () => {
    assert.equal(shouldIgnoreIntervalShortcut({ tagName: 'INPUT' }, false), true)
    assert.equal(shouldIgnoreIntervalShortcut({ tagName: 'DIV', isContentEditable: true }, false), true)
    assert.equal(shouldIgnoreIntervalShortcut({ tagName: 'BUTTON' }, true), true)
    assert.equal(shouldIgnoreIntervalShortcut({ tagName: 'BUTTON' }, false), false)
  })

  it('maps drawing keyboard shortcuts to tools and commands', () => {
    assert.deepEqual(getDrawingShortcutAction({ key: 't', altKey: true }), { type: 'tool', tool: 'segment' })
    assert.deepEqual(getDrawingShortcutAction({ key: 'J', altKey: true }), { type: 'tool', tool: 'horizontalRayLine' })
    assert.deepEqual(getDrawingShortcutAction({ key: 'h', altKey: true }), { type: 'tool', tool: 'horizontalLine' })
    assert.deepEqual(getDrawingShortcutAction({ key: 'v', altKey: true }), { type: 'tool', tool: 'verticalLine' })
    assert.deepEqual(getDrawingShortcutAction({ key: 'f', altKey: true, ctrlKey: true }), { type: 'tool', tool: 'fibonacciLine' })
    assert.deepEqual(getDrawingShortcutAction({ key: 'h', altKey: true, ctrlKey: true }), {
      type: 'command',
      command: 'hideDrawings'
    })
    assert.equal(getDrawingShortcutAction({ key: 't' }), null)
    assert.equal(getDrawingShortcutAction({ key: '1', altKey: true }), null)
  })

  it('ignores drawing shortcuts while text entry or shortcut-disabled UI is active', () => {
    assert.equal(shouldIgnoreDrawingShortcut({ tagName: 'INPUT' }), true)
    assert.equal(shouldIgnoreDrawingShortcut({ tagName: 'TEXTAREA' }), true)
    assert.equal(shouldIgnoreDrawingShortcut({ tagName: 'DIV', isContentEditable: true }), true)
    assert.equal(shouldIgnoreDrawingShortcut({ tagName: 'BUTTON', closest: () => ({}) }), true)
    assert.equal(shouldIgnoreDrawingShortcut({ tagName: 'BUTTON', closest: () => null }), false)
  })

  it('provides independent indicator setting drafts with OKX-style defaults', () => {
    const draft = cloneIndicatorSettings(defaultIndicatorSettings)

    assert.deepEqual(
      draft.movingAverage.lines.map((line) => line.period),
      [5, 10, 20, 30, 60, 120]
    )
    assert.deepEqual(
      draft.weightedMovingAverage.lines.map((line) => line.period),
      [5, 10, 20, 30, 60, 120]
    )

    draft.movingAverage.lines[0].period = 7
    assert.equal(defaultIndicatorSettings.movingAverage.lines[0].period, 5)
  })

  it('updates indicator enabled state without mutating the previous settings', () => {
    const next = updateIndicatorEnabled(defaultIndicatorSettings, 'EMA', true)

    assert.equal(defaultIndicatorSettings.enabled.includes('EMA'), false)
    assert.equal(next.enabled.includes('EMA'), true)

    const withoutVolume = updateIndicatorEnabled(next, 'VOLUME', false)
    assert.equal(next.volume.enabled, true)
    assert.equal(withoutVolume.volume.enabled, false)
  })

  it('builds a chart indicator application plan from persisted settings', () => {
    const settings = updateIndicatorEnabled(defaultIndicatorSettings, 'EMA', true)
    settings.exponentialMovingAverage.lines[3].enabled = true
    settings.volume.ma1.enabled = true
    settings.volume.ma2.enabled = true

    const plan = buildIndicatorApplyPlan(settings)

    assert.equal(plan.volume?.name, 'VOL')
    assert.deepEqual(plan.volume?.calcParams, [5, 10])
    assert.deepEqual(
      plan.mainIndicators.map((item) => item.name),
      ['MA', 'EMA']
    )
    assert.deepEqual(plan.mainIndicators[0].calcParams, [5, 10, 20])
    assert.deepEqual(plan.mainIndicators[1].calcParams, [5, 10, 20, 30])
  })

  it('exposes KLineCharts built-in indicators plus trading-page generated indicators', () => {
    const values = indicatorConfigOptions.map((item) => item.value)
    const builtInIndicators = [
      'AVP',
      'AO',
      'BIAS',
      'BOLL',
      'BRAR',
      'BBI',
      'CCI',
      'CR',
      'DMA',
      'DMI',
      'EMV',
      'EMA',
      'MA',
      'MACD',
      'MTM',
      'OBV',
      'PVT',
      'PSY',
      'ROC',
      'RSI',
      'SMA',
      'KDJ',
      'SAR',
      'TRIX',
      'VOL',
      'VR',
      'WR'
    ]
    const generatedIndicators = [
      'WMA',
      'AVL',
      'VWAP',
      'SUPER_TREND',
      'SUPPORT_RESISTANCE',
      'OI',
      'TOP_ACC_LS',
      'TOP_POS_LS',
      'ACC_LS',
      'TAKER_BS',
      'SKDJ'
    ]

    assert.deepEqual(values.filter((value, index) => values.indexOf(value) !== index), [])
    assert.deepEqual(builtInIndicators.filter((indicator) => !values.includes(indicator)), [])
    assert.deepEqual(generatedIndicators.filter((indicator) => !values.includes(indicator)), [])
    assert.equal(indicatorConfigOptions.some((item) => item.group === 'trading'), true)
    assert.equal(indicatorConfigOptions.some((item) => item.group === 'main'), true)
    assert.equal(indicatorConfigOptions.some((item) => item.group === 'secondary'), true)
  })

  it('builds descriptors for every enabled catalog indicator with configurable params', () => {
    let settings = cloneIndicatorSettings(defaultIndicatorSettings)
    ;['VWAP', 'SUPPORT_RESISTANCE', 'MACD', 'KDJ', 'OI', 'TOP_ACC_LS'].forEach((indicator) => {
      settings = updateIndicatorEnabled(settings, indicator, true)
    })
    settings.indicators.MACD.calcParams = [8, 21, 5]
    settings.indicators.OI.calcParams = [18]
    settings.indicators.VWAP.calcParams = [34]

    const plan = buildIndicatorApplyPlan(settings)

    assert.deepEqual(
      plan.mainIndicators.map((item) => item.name),
      ['MA', 'VWAP', 'SUPPORT_RESISTANCE']
    )
    assert.deepEqual(
      plan.secondaryIndicators.map((item) => item.name),
      ['OI', 'TOP_ACC_LS', 'MACD', 'KDJ']
    )
    assert.deepEqual(plan.mainIndicators.find((item) => item.name === 'VWAP')?.calcParams, [34])
    assert.deepEqual(plan.secondaryIndicators.find((item) => item.name === 'MACD')?.calcParams, [8, 21, 5])
    assert.deepEqual(plan.secondaryIndicators.find((item) => item.name === 'OI')?.calcParams, [18])
  })
})
