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
const { applyRealtimeQuoteToChart } = await import('./KLineChartPanel.tsx')
const source = readFileSync(join(currentDir, 'KLineChartPanel.tsx'), 'utf8')
const chartWorkspaceSource = readFileSync(join(currentDir, 'ChartWorkspace.tsx'), 'utf8')
const tradingPageSource = readFileSync(join(currentDir, '..', 'TradingPage.tsx'), 'utf8')
const desktopViewSource = readFileSync(join(currentDir, 'TradingDesktopView.tsx'), 'utf8')
const mobileViewSource = readFileSync(join(currentDir, 'TradingMobileView.tsx'), 'utf8')
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
    assert.match(source, /chartRef\.current\?\.removeOverlay\(\{\s*id:\s*selectedDrawing\.id\s*\}\)/)
  })

  it('positions the overlay edit toolbar above the selected chart drawing controls', () => {
    assert.match(styles, /\.overlayEditToolbar\s*{[^}]*position:\s*absolute[^}]*top:\s*8px[^}]*left:\s*50%[^}]*z-index:\s*13[^}]*}/s)
    assert.match(styles, /\.lineWidthButtonActive\s*{[^}]*border-color:\s*#f2b84b[^}]*}/s)
    assert.match(styles, /\.lockButtonActive\s*{[^}]*color:\s*#f2b84b[^}]*}/s)
  })
})

describe('KLineChartPanel realtime candle updates', () => {
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
    assert.match(source, /subscribeQuote\(symbol,\s*token,/)
    assert.match(source, /\},\s*\[period,\s*symbol,\s*token\]\)/)
    assert.match(chartWorkspaceSource, /token:\s*string\s*\|\s*null/)
    assert.match(chartWorkspaceSource, /<KLineChartPanel[\s\S]*token=\{token\}/)
    assert.match(tradingPageSource, /token,/)
    assert.match(desktopViewSource, /<ChartWorkspace[\s\S]*token=\{token\}/)
    assert.match(mobileViewSource, /<ChartWorkspace[\s\S]*token=\{token\}/)
  })
})

describe('KLineChartPanel empty candle state', () => {
  it('clears stale chart data and shows an empty state when a provider period has no candles', () => {
    assert.match(source, /const hasHistoricalCandlesRef = useRef\(false\)/)
    assert.match(source, /const \[hasNoCandles,\s*setHasNoCandles\]/)
    assert.match(source, /if \(candles\.length === 0\)\s*{[\s\S]*hasHistoricalCandlesRef\.current = false[\s\S]*setHasNoCandles\(true\)[\s\S]*callback\(\[\]\)[\s\S]*return/s)
    assert.match(source, /hasHistoricalCandlesRef\.current = true[\s\S]*setHasNoCandles\(false\)[\s\S]*callback\(candles\)/s)
    assert.match(source, /<div className=\{styles\.emptyState\} role="status">/)
    assert.match(source, /t\('common\.empty'\)/)
    assert.match(styles, /\.emptyState\s*{[^}]*position:\s*absolute[^}]*inset:\s*0[^}]*pointer-events:\s*none[^}]*}/s)
  })

  it('does not let a realtime quote hide an empty historical candle state', () => {
    assert.match(source, /if \(hasHistoricalCandlesRef\.current\)\s*{\s*setHasNoCandles\(false\)\s*}/)
    assert.doesNotMatch(source, /callback\(nextBar\)\s*setHasNoCandles\(false\)/)
  })
})
