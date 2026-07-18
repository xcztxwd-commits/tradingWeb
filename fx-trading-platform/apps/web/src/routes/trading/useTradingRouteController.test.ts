import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const routeTypesSource = readFileSync(join(currentDir, 'tradingRoute.types.ts'), 'utf8')
const controllerSource = readFileSync(join(currentDir, 'useTradingRouteController.ts'), 'utf8')
const pageSource = readFileSync(join(currentDir, '..', '..', 'pages', 'trading', 'TradingPage.tsx'), 'utf8')

describe('trading route controller contract', () => {
  it('keeps the shared route model independent from PC workspace layout state', () => {
    const sharedModel = sourceBetween(routeTypesSource, 'export type TradingRouteModel = {', '/** Temporary name')
    const compatibilityModel = sourceBetween(routeTypesSource, 'export type TradingTerminalViewProps', '\n}')

    for (const field of [
      'accountPanel', 'accountId', 'balances', 'chartCallbacks', 'chartSettings', 'chartThemeMode',
      'chartTitle', 'indicators', 'loginRequired', 'market', 'markets', 'favorites', 'marketDataStatusView',
      'onLoginRequired', 'onOpenMarkets', 'onOpenQuote', 'onOpenTrade', 'onSelectPrice', 'onRetrySession',
      'onSelectSymbol', 'onFavorite', 'product', 'quote', 'quotes', 'sessionError', 'sessionReady',
      'sessionStatusLabel', 'sessionStatusText', 'submitOrder', 'submitOco', 'perpetualControls', 'symbol',
      'tradeMinOrderAmount', 'tradePricePrecision', 'tradeQuantityPrecision', 'tradePricePrefill',
      'terminalLoading', 'token', 'tradePanelSessionMode'
    ]) {
      assert.match(sharedModel, new RegExp(`\\b${field}[?]?:`), `missing TradingRouteModel.${field}`)
    }
    assert.doesNotMatch(sharedModel, /workspaceLayoutControls/)
    assert.match(compatibilityModel, /TradingRouteModel/)
    assert.match(compatibilityModel, /workspaceLayoutControls: TradingWorkspaceLayoutControls/)
  })

  it('owns one trading session and market runtime above both current views', () => {
    assert.match(controllerSource, /useTradingSession\(\)/)
    assert.match(controllerSource, /useTradingQuoteMap\(quoteMarkets,\s*token,\s*handleQuoteStatus\)/)
    assert.match(controllerSource, /startQuoteMarketDataAdapter\(selectedSymbol, token\)/)
    assert.match(controllerSource, /return \{[\s\S]*model:[\s\S]*sourceNotice/)
    assert.match(pageSource, /useTradingRouteController\(\{\s*product\s*\}\)/)
    assert.doesNotMatch(pageSource, /useTradingSession\(\)/)
    assert.doesNotMatch(pageSource, /useTradingQuoteMap\(/)
    assert.doesNotMatch(pageSource, /startQuoteMarketDataAdapter\(/)
  })

  it('adapts the same route model to desktop and mobile without rebuilding controllers', () => {
    assert.match(pageSource, /const viewProps: TradingTerminalViewProps = \{[\s\S]*\.\.\.model,[\s\S]*workspaceLayoutControls/)
    assert.match(pageSource, /<TradingDesktopView \{\.\.\.viewProps\} \/>/)
    assert.match(pageSource, /<TradingMobileView \{\.\.\.viewProps\} \/>/)
  })
})

function sourceBetween(source: string, start: string, end: string) {
  const startIndex = source.indexOf(start)
  assert.notEqual(startIndex, -1, `Missing source marker: ${start}`)
  const endIndex = source.indexOf(end, startIndex + start.length)
  assert.notEqual(endIndex, -1, `Missing source marker: ${end}`)
  return source.slice(startIndex, endIndex)
}
