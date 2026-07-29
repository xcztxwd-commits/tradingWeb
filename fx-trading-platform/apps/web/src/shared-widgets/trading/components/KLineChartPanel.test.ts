import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { register } from 'node:module'
import { pathToFileURL } from 'node:url'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

import { isChartFullscreenShortcut, shouldIgnoreChartFullscreenShortcut } from './chartFullscreen.ts'

const currentDir = dirname(fileURLToPath(import.meta.url))
const typescriptUrl = pathToFileURL(join(currentDir, '../../../../../../node_modules/typescript/lib/typescript.js')).href
register(
  `data:text/javascript,${encodeURIComponent(`
    import { access, readFile } from 'node:fs/promises'
    import ts from ${JSON.stringify(typescriptUrl)}

    const klinechartsStub = 'data:text/javascript,' + encodeURIComponent(\`
      export function init() { return null }
      export function dispose() {}
      export function registerIndicator() {}
      export function getSupportedIndicators() { return [] }
      export function registerOverlay() {}
    \`)

    export async function resolve(specifier, context, nextResolve) {
      if (specifier === 'klinecharts') {
        return { url: klinechartsStub, shortCircuit: true }
      }

      if ((specifier.startsWith('./') || specifier.startsWith('../')) && !/\\.[cm]?[jt]sx?$|\\.css$/.test(specifier)) {
        for (const extension of ['.ts', '.tsx']) {
          const url = new URL(specifier + extension, context.parentURL)
          try {
            await access(url)
            return { url: url.href, shortCircuit: true }
          } catch {
          }
        }
      }

      return nextResolve(specifier, context)
    }

    export async function load(url, context, nextLoad) {
      if (url.endsWith('.module.css')) {
        return {
          format: 'module',
          shortCircuit: true,
          source: 'export default new Proxy({}, { get: (_, key) => String(key) })'
        }
      }

      if (url.endsWith('.ts') || url.endsWith('.tsx')) {
        const source = await readFile(new URL(url), 'utf8')
        const result = ts.transpileModule(source, {
          compilerOptions: {
            jsx: ts.JsxEmit.ReactJSX,
            module: ts.ModuleKind.ESNext,
            target: ts.ScriptTarget.ES2022,
            verbatimModuleSyntax: true
          }
        })
        return { format: 'module', shortCircuit: true, source: result.outputText }
      }

      return nextLoad(url, context)
    }
  `)}`,
  pathToFileURL(`${currentDir}/`)
)
const { applyAuthoritativeRealtimeQuoteToChart, applyRealtimeQuoteToChart } = await import('./KLineChartPanel.tsx')
const source = readFileSync(join(currentDir, 'KLineChartPanel.tsx'), 'utf8')
const chartWorkspaceSource = readFileSync(join(currentDir, 'ChartWorkspace.tsx'), 'utf8')
const chartThemeSource = readFileSync(join(currentDir, '..', 'chartTheme.ts'), 'utf8')
const tradingPageSource = [
  readFileSync(join(currentDir, '..', '..', '..', 'routes', 'trading', 'TradingRoute.tsx'), 'utf8'),
  readFileSync(join(currentDir, '..', '..', '..', 'routes', 'trading', 'useTradingRouteController.ts'), 'utf8')
].join('\n')
const desktopViewSource = readFileSync(join(currentDir, '..', '..', '..', 'pc', 'pages', 'trading', 'PcTradingTerminal.tsx'), 'utf8')
const mobileViewSource = readFileSync(join(currentDir, '..', '..', '..', 'mobile', 'pages', 'trading', 'MobileTradingTerminal.tsx'), 'utf8')
const styles = readFileSync(join(currentDir, 'KLineChartPanel.module.css'), 'utf8')

describe('KLineChartPanel fullscreen control', () => {
  it('uses Shift+F as the fullscreen shortcut without capturing plain F', () => {
    assert.equal(isChartFullscreenShortcut({ key: 'F', shiftKey: true, ctrlKey: false, metaKey: false, altKey: false }), true)
    assert.equal(isChartFullscreenShortcut({ key: 'f', shiftKey: false, ctrlKey: false, metaKey: false, altKey: false }), false)
    assert.equal(isChartFullscreenShortcut({ key: 'F', shiftKey: true, ctrlKey: true, metaKey: false, altKey: false }), false)
  })

  it('ignores the fullscreen shortcut inside text entry and disabled shortcut zones', () => {
    assert.equal(shouldIgnoreChartFullscreenShortcut({ tagName: 'INPUT' }), true)
    assert.equal(shouldIgnoreChartFullscreenShortcut({ isContentEditable: true }), true)
    assert.equal(shouldIgnoreChartFullscreenShortcut({ closest: (selector: string) => selector.includes('data-shortcut-disabled') }), true)
    assert.equal(shouldIgnoreChartFullscreenShortcut({ tagName: 'DIV', closest: () => null }), false)
  })

  it('keeps the fullscreen control out of the chart canvas overlay layer', () => {
    assert.match(styles, /\.canvasWrap\s*{[^}]*position:\s*relative[^}]*}/s)
    assert.doesNotMatch(source, /styles\.fullscreenButton/)
    assert.doesNotMatch(source, /onToggleFullscreen/)
    assert.doesNotMatch(styles, /\.fullscreenButton\s*{/)
  })

  it('leaves fullscreen expansion to the outer chart workspace', () => {
    assert.doesNotMatch(styles, /chartPanelExpanded/)
  })

  it('reserves enough mobile chart height for a 300px candle pane', () => {
    assert.match(styles, /\[data-platform-view=['"]mobile['"]\]\s+\.klineCanvas\s*\{[^}]*min-height:\s*423px/s)
  })
})

describe('KLineChartPanel overlay edit toolbar', () => {
  it('wires selected drawing overlays to a floating edit toolbar', () => {
    assert.match(source, /const \[selectedDrawing,\s*setSelectedDrawing\]/)
    assert.match(source, /onSelected:\s*handleOverlaySelected/)
    assert.match(source, /onClick:\s*handleOverlaySelected/)
    assert.match(source, /onDeselected:\s*\(\)\s*=>\s*setSelectedDrawing\(null\)/)
    assert.match(source, /selectedDrawing\s*&&\s*drawingsVisible\s*\?\s*\(/)
    assert.match(source, /<ChartOverlayEditToolbar/)
  })

  it('lets the floating toolbar update style, lock state, and delete the selected overlay', () => {
    assert.match(source, /trading\.drawingEdit/)
    assert.match(source, /data-shortcut-disabled="true"/)
    assert.match(source, /onMouseDown=\{\(event\) => event\.stopPropagation\(\)\}/)
    assert.match(source, /chartRef\.current\?\.overrideOverlay\(\{\s*id:\s*selectedDrawing\.id,\s*styles:\s*buildDrawingOverlayStyles/)
    assert.match(source, /chartRef\.current\?\.overrideOverlay\(\{\s*id:\s*selectedDrawing\.id,\s*lock:\s*nextLocked\s*\}\)/)
    assert.match(source, /handleRemoveDrawing\(selectedDrawing\.id\)/)
  })

  it('positions the overlay edit toolbar above the selected chart drawing controls', () => {
    assert.match(styles, /\.overlayEditToolbar\s*{[^}]*position:\s*absolute[^}]*top:\s*8px[^}]*left:\s*50%[^}]*z-index:\s*13[^}]*}/s)
    assert.match(styles, /\.lineWidthButtonActive\s*{[^}]*border-color:\s*var\(--trading-accent-border\)[^}]*}/s)
    assert.match(styles, /\.lockButtonActive\s*{[^}]*color:\s*var\(--trading-accent\)[^}]*}/s)
  })
})

describe('KLineChartPanel chart instance controls', () => {
  it('applies persisted visual settings and price scale mode to KLineCharts', () => {
    assert.match(source, /getChartVisualStyles/)
    assert.match(source, /candleStyle:\s*ChartSettings\['candleStyle'\]/)
    assert.match(source, /axisSettings:\s*ChartSettings\['axisSettings'\]/)
    assert.match(source, /layoutSettings:\s*ChartSettings\['layoutSettings'\]/)
    assert.match(source, /chart\.setStyles\(getChartVisualStyles\(\{[\s\S]*chartType,[\s\S]*candleStyle,[\s\S]*axisSettings,[\s\S]*layoutSettings[\s\S]*\}\)\)/)
    assert.match(source, /chart\.overrideYAxis\(\{\s*name:\s*axisSettings\.priceScaleMode\s*\}\)/)
    assert.match(chartWorkspaceSource, /candleStyle=\{settings\.candleStyle\}/)
    assert.match(chartWorkspaceSource, /axisSettings=\{settings\.axisSettings\}/)
    assert.match(chartWorkspaceSource, /layoutSettings=\{settings\.layoutSettings\}/)
  })

  it('handles toolbar requests for realtime scroll and camera image export', () => {
    assert.match(source, /chartActionRequest:\s*ChartActionRequest/)
    assert.match(source, /chart\.scrollToRealTime\(160\)/)
    assert.match(source, /const imageBackgroundColor = exportBackgroundColor === defaultChartSettings\.layoutSettings\.background\.color/)
    assert.match(source, /chartTheme\.terminalImageBackground\[exportThemeMode\]/)
    assert.match(source, /chart\.getConvertPictureUrl\(true,\s*'png',\s*imageBackgroundColor\)/)
    assert.match(chartThemeSource, /terminalImageBackground:\s*\{[\s\S]*dark:\s*'#050505',[\s\S]*light:\s*'#ffffff'[\s\S]*\}/)
    assert.match(source, /downloadChartImage\(/)
    assert.match(source, /const \[chartImagePreview,\s*setChartImagePreview\]/)
    assert.match(source, /setChartImagePreview\(\{\s*imageUrl,\s*symbol:\s*exportSymbol\s*\}\)/)
    assert.doesNotMatch(source, /downloadChartImage\(imageUrl,\s*symbol\)/)
    assert.match(source, /role="dialog"[\s\S]*chart\.imagePreviewTitle/)
    assert.match(source, /<img[\s\S]*src=\{chartImagePreview\.imageUrl\}[\s\S]*alt=\{t\('chart\.imagePreviewAlt',\s*\{\s*symbol:\s*chartImagePreview\.symbol\s*\}\)\}/)
    assert.match(source, /onClick=\{\(\) => downloadChartImage\(chartImagePreview\.imageUrl,\s*chartImagePreview\.symbol\)\}/)
    assert.match(styles, /\.imagePreviewDialog\s*{/)
    assert.match(styles, /\.imagePreviewFrame\s*{[\s\S]*max-height/)
    assert.match(chartWorkspaceSource, /const \[chartActionRequest,\s*setChartActionRequest\]/)
    assert.match(chartWorkspaceSource, /handleScrollToRealtime/)
    assert.match(chartWorkspaceSource, /handleExportChart/)
    assert.match(chartWorkspaceSource, /chartActionRequest=\{chartActionRequest\}/)
    assert.match(chartWorkspaceSource, /onExportChart=\{handleExportChart\}/)
  })

  it('does not reopen the camera preview when only the selected symbol changes', () => {
    assert.match(source, /const chartExportContextRef = useRef/)
    assert.match(source, /chartExportContextRef\.current = \{[\s\S]*symbol,[\s\S]*themeMode,[\s\S]*backgroundColor:\s*layoutSettings\.background\.color[\s\S]*\}/)
    assert.match(source, /const \{\s*symbol:\s*exportSymbol,[\s\S]*themeMode:\s*exportThemeMode,[\s\S]*backgroundColor:\s*exportBackgroundColor[\s\S]*\} = chartExportContextRef\.current/)
    assert.match(source, /setChartImagePreview\(\{\s*imageUrl,\s*symbol:\s*exportSymbol\s*\}\)/)

    const exportImageEffect = source.match(/useEffect\(\(\) => \{[\s\S]*?chartActionRequest\.exportImage[\s\S]*?setChartImagePreview\(\{[\s\S]*?\}\)[\s\S]*?\}, \[([^\]]*)\]\)/)
    assert.ok(exportImageEffect)
    assert.equal(exportImageEffect[1].replace(/\s/g, ''), 'chartActionRequest.exportImage')
  })

  it('handles KLineCharts jump and persisted bar-space settings without toolbar zoom actions', () => {
    assert.match(source, /chart\.setBarSpace\(axisSettings\.barSpace\)/)
    assert.match(source, /chart\.scrollToTimestamp\(chartActionRequest\.scrollToTimestamp\.timestamp,\s*160\)/)
    assert.match(chartWorkspaceSource, /handleJumpToTimestamp/)
    assert.doesNotMatch(source, /chartActionRequest\.resetZoom/)
    assert.doesNotMatch(source, /chart\.zoomAtTimestamp/)
    assert.doesNotMatch(source, /zoomAtTimestamp/)
    assert.doesNotMatch(chartWorkspaceSource, /handleResetZoom/)
    assert.doesNotMatch(chartWorkspaceSource, /handleZoomAtTimestamp/)
  })

  it('applies chart locale, timezone, separators, decimal folding, and formatter hooks', () => {
    assert.match(source, /i18n\.language/)
    assert.match(source, /timezone:\s*ChartSettings\['timezone'\]/)
    assert.match(source, /chart\.setLocale\(normalizeChartLocale\(i18n\.language\)\)/)
    assert.match(source, /chart\.setTimezone\(resolveChartTimezone\(timezone\)\)/)
    assert.match(source, /chart\.setThousandsSeparator\(\{[\s\S]*sign:\s*','/)
    assert.match(source, /chart\.setDecimalFold\(\{[\s\S]*threshold:\s*1_000_000/)
    assert.match(source, /chart\.setFormatter\(\{[\s\S]*formatDate:[\s\S]*formatBigNumber:/)
    assert.match(chartWorkspaceSource, /timezone=\{settings\.timezone\}/)
  })

  it('subscribes to KLineCharts actions for crosshair, candle clicks, and visible range changes', () => {
    assert.match(source, /chart\.subscribeAction\('onCrosshairChange',\s*handleCrosshairChange\)/)
    assert.match(source, /chart\.subscribeAction\('onCandleBarClick',\s*handleCandleBarClick\)/)
    assert.match(source, /chart\.subscribeAction\('onVisibleRangeChange',\s*handleVisibleRangeChange\)/)
    assert.match(source, /chart\.unsubscribeAction\('onCrosshairChange',\s*handleCrosshairChange\)/)
    assert.match(source, /onCandlePriceSelect\?\.\(price\)/)
    assert.match(source, /setCrosshairCandle\(createCrosshairCandle\(payload\)\)/)
    assert.match(source, /visibleRangeRef\.current = chart\.getVisibleRange\(\)/)
    assert.match(source, /styles\.ohlcPanel/)
  })

  it('creates automatic trading marker overlays from orders and positions', () => {
    assert.match(source, /tradeMarkers:\s*ChartTradeMarker\[\]/)
    assert.match(source, /const \[tradeMarkerOverlayCount,\s*setTradeMarkerOverlayCount\] = useState\(0\)/)
    assert.match(source, /buildTradeMarkerOverlays\(tradeMarkers,\s*period\)/)
    assert.match(source, /chart\.removeOverlay\(\{\s*groupId:\s*tradeMarkerOverlayGroupId\s*\}\)/)
    assert.match(source, /setTradeMarkerOverlayCount\(overlays\.length\)/)
    assert.match(source, /chart\.createOverlay\(overlays\)/)
    assert.match(source, /data-trade-marker-overlay-count=\{tradeMarkerOverlayCount\}/)
    assert.match(chartWorkspaceSource, /buildChartTradeMarkers\(symbol,\s*orders,\s*positions\)/)
    assert.match(desktopViewSource, /orders=\{model\.accountPanel\.orders\}/)
    assert.match(desktopViewSource, /positions=\{model\.accountPanel\.positions\}/)
    assert.match(mobileViewSource, /orders=\{model\.accountPanel\.orders\}/)
    assert.match(mobileViewSource, /positions=\{model\.accountPanel\.positions\}/)
  })

  it('persists drawings by symbol and exposes drawing manager actions', () => {
    assert.match(source, /loadPersistedChartDrawings\(symbol\)/)
    assert.match(source, /savePersistedChartDrawings\(symbol,\s*chart\.getOverlays\(\{\s*groupId:\s*drawingOverlayGroupId\s*\}\)\)/)
    assert.match(source, /handleCopyDrawing/)
    assert.match(source, /handleRemoveDrawing/)
    assert.match(source, /handleLockAllDrawings/)
    assert.match(source, /handleApplyRiskTemplate/)
    assert.match(source, /<ChartDrawingManager/)
    assert.match(styles, /\.drawingManager\s*{/)
  })

  it('uses KLineCharts data loader load-more metadata for older history', () => {
    assert.match(source, /getBars:\s*\(\{\s*type,\s*timestamp,\s*callback\s*\}\)\s*=>/)
    assert.match(source, /resolveHistoricalCandleEndTime\(type,\s*timestamp\)/)
    assert.match(source, /count:\s*historicalCandleBatchSize/)
    assert.match(source, /callback\(candles,\s*\{\s*forward:\s*hasMoreHistoricalCandles\(candles\),\s*backward:\s*false\s*\}\)/)
    assert.match(source, /if \(type === 'backward'\)[\s\S]*callback\(\[\],\s*\{\s*forward:\s*false,\s*backward:\s*false\s*\}\)/)
  })
})

describe('KLineChartPanel realtime candle updates', () => {
  it('rejects realtime websocket quotes without complete fresh source metadata', () => {
    const now = 1_700_000_001_000
    const quote = {
      type: 'quote' as const,
      symbol: 'BTCUSDT',
      bid: '59999',
      ask: '60001',
      mid: '60000',
      spread: '2',
      source: 'binance',
      timestamp: 1_700_000_001_234
    }

    assert.equal(applyAuthoritativeRealtimeQuoteToChart(null, '1m', quote, now), null)
    assert.equal(applyAuthoritativeRealtimeQuoteToChart(null, '1m', {
      ...quote,
      providerCode: 'binance',
      providerSymbol: 'BTCUSDT',
      sourceMode: 'PUBLIC_EXTERNAL',
      asOf: '2023-11-14T22:13:20.000Z',
      expiresAt: '2023-11-14T22:13:21.000Z',
      stale: true
    }, now), null)
    assert.deepEqual(applyAuthoritativeRealtimeQuoteToChart(null, '1m', {
      ...quote,
      providerCode: 'binance',
      providerSymbol: 'BTCUSDT',
      sourceMode: 'PUBLIC_EXTERNAL',
      asOf: '2023-11-14T22:13:20.000Z',
      expiresAt: '2023-11-14T22:13:30.000Z',
      stale: false
    }, now), {
      timestamp: 1_699_999_980_000,
      open: 60_000,
      high: 60_000,
      low: 60_000,
      close: 60_000,
      volume: 0,
      turnover: 0
    })
    assert.match(source, /const nextBar = applyAuthoritativeRealtimeQuoteToChart\(/)
    assert.match(source, /subscribeQuote<BackendQuote>/)
    assert.doesNotMatch(source, /quote as BackendQuote/)
  })

  it('returns null for invalid or non-positive quote prices', () => {
    assert.equal(applyRealtimeQuoteToChart(null, '1m', 1_700_000_001_234, Number.NaN), null)
    assert.equal(applyRealtimeQuoteToChart(null, '1m', 1_700_000_001_234, 0), null)
    assert.equal(applyRealtimeQuoteToChart(null, '1m', 1_700_000_001_234, -1), null)
  })

  it('updates high, low, and close for a quote in the same candle bucket', () => {
    const previous = {
      timestamp: 1_700_000_000_000,
      open: 1.1,
      high: 1.2,
      low: 1.05,
      close: 1.15,
      volume: 42
    }

    assert.deepEqual(applyRealtimeQuoteToChart(previous, '1m', 1_700_000_030_000, 1.25), {
      timestamp: 1_699_999_980_000,
      open: 1.1,
      high: 1.25,
      low: 1.05,
      close: 1.25,
      volume: 42,
      turnover: 52.5
    })
  })

  it('creates a new minute-bucketed time candle from the previous close with zero volume', () => {
    const previous = {
      timestamp: 1_700_000_000_000,
      open: 1.1,
      high: 1.2,
      low: 1.05,
      close: 1.15,
      volume: 42
    }

    assert.deepEqual(applyRealtimeQuoteToChart(previous, 'time', 1_700_000_041_234, 1.18), {
      timestamp: 1_700_000_040_000,
      open: 1.15,
      high: 1.18,
      low: 1.15,
      close: 1.18,
      volume: 0,
      turnover: 0
    })
  })

  it('subscribes to quote updates to keep the current chart bar moving', () => {
    assert.match(source, /subscribeQuote/)
    assert.match(source, /applyRealtimeQuoteToChart/)
    assert.match(source, /realtimeBarCallbackRef/)
    assert.doesNotMatch(source, /subscribeBar:\s*\(\)\s*=>\s*\{\}/)
    assert.doesNotMatch(source, /updateData\(/)
    assert.match(source, /chart\.resetData\(\)/)
  })

  it('uses the page session token for realtime quote subscriptions', () => {
    assert.match(source, /token:\s*string\s*\|\s*null/)
    assert.match(source, /subscribeQuote<BackendQuote>\(symbol,\s*token,/)
    assert.match(source, /\},\s*\[period,\s*symbol,\s*token\]\)/)
    assert.match(chartWorkspaceSource, /token:\s*string\s*\|\s*null/)
    assert.match(chartWorkspaceSource, /<KLineChartPanel[\s\S]*token=\{token\}/)
    assert.match(tradingPageSource, /token,/)
    assert.match(desktopViewSource, /<ChartWorkspace[\s\S]*token=\{model\.token\}/)
    assert.match(mobileViewSource, /<ChartWorkspace[\s\S]*token=\{model\.token\}/)
  })
})

describe('KLineChartPanel empty candle state', () => {
  it('clears stale chart data and shows an empty state when a provider period has no candles', () => {
    assert.match(source, /const hasHistoricalCandlesRef = useRef\(false\)/)
    assert.match(source, /const \[hasNoCandles,\s*setHasNoCandles\]/)
    assert.match(source, /if \(candles\.length === 0\)\s*{[\s\S]*hasHistoricalCandlesRef\.current = false[\s\S]*setHasNoCandles\(true\)[\s\S]*callback\(\[\](?:,\s*\{[^}]*\})?\)[\s\S]*return/s)
    assert.match(source, /hasHistoricalCandlesRef\.current = true[\s\S]*setHasNoCandles\(false\)[\s\S]*callback\(candles(?:,\s*\{[^}]*\})?\)/s)
    assert.match(source, /<div className=\{styles\.emptyState\} role="status">/)
    assert.match(source, /t\('common\.empty'\)/)
    assert.match(styles, /\.emptyState\s*{[^}]*position:\s*absolute[^}]*inset:\s*0[^}]*pointer-events:\s*none[^}]*}/s)
  })

  it('does not let a realtime quote hide an empty historical candle state', () => {
    assert.match(source, /if \(hasHistoricalCandlesRef\.current\)\s*{\s*setHasNoCandles\(false\)\s*}/)
    assert.doesNotMatch(source, /callback\(nextBar\)\s*setHasNoCandles\(false\)/)
  })
})
