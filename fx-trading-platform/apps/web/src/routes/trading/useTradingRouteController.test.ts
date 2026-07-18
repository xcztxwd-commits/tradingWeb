import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const webSrc = join(currentDir, '..', '..')
const routeTypesSource = readFileSync(join(currentDir, 'tradingRoute.types.ts'), 'utf8')
const controllerSource = readFileSync(join(currentDir, 'useTradingRouteController.ts'), 'utf8')
const routeSource = readFileSync(join(currentDir, 'TradingRoute.tsx'), 'utf8')
const pcSource = readFileSync(join(webSrc, 'pc', 'pages', 'trading', 'PcTradingTerminal.tsx'), 'utf8')
const mobileSource = readFileSync(join(webSrc, 'mobile', 'pages', 'trading', 'MobileTradingTerminal.tsx'), 'utf8')
const tradePanelControllerSource = readFileSync(
  join(webSrc, 'shared-widgets', 'trading', 'order-form', 'useTradePanelController.ts'),
  'utf8'
)

describe('trading route controller contract', () => {
  it('keeps the shared route model independent from PC workspace layout state', () => {
    for (const field of [
      'accountPanel', 'accountId', 'balances', 'chartCallbacks', 'chartSettings', 'chartThemeMode',
      'chartTitle', 'controllerSentinel', 'indicators', 'loginRequired', 'market', 'markets', 'favorites',
      'marketDataStatusView', 'onLoginRequired', 'onOpenMarkets', 'onOpenQuote', 'onOpenTrade',
      'onSelectPrice', 'onRetrySession', 'onSelectSymbol', 'onFavorite', 'product', 'quote', 'quotes',
      'sessionError', 'sessionReady', 'sessionStatusLabel', 'sessionStatusText', 'submitOrder',
      'submitOco', 'perpetualControls', 'symbol', 'tradePanel', 'tradeMinOrderAmount',
      'tradePricePrecision', 'tradeQuantityPrecision', 'tradePricePrefill', 'terminalLoading',
      'token', 'tradePanelSessionMode'
    ]) {
      assert.match(routeTypesSource, new RegExp(`\\b${field}[?]?:`), `missing TradingRouteModel.${field}`)
    }
    assert.doesNotMatch(routeTypesSource, /workspaceLayoutControls|TradingTerminalViewProps|layoutStore/)
    assert.match(pcSource, /useResizableLayout\(\)/)
    assert.doesNotMatch(mobileSource, /useResizableLayout|TradingWorkspace/)
  })

  it('owns one trading session, market runtime and trade form above both views', () => {
    assert.equal((controllerSource.match(/useTradingSession\(\)/g) ?? []).length, 1)
    assert.equal((controllerSource.match(/useTradingQuoteMap\(quoteMarkets,\s*token,\s*handleQuoteStatus\)/g) ?? []).length, 1)
    assert.equal((controllerSource.match(/startQuoteMarketDataAdapter\(selectedSymbol, token\)/g) ?? []).length, 1)
    assert.equal((controllerSource.match(/useTradePanelController\(/g) ?? []).length, 1)
    assert.match(tradePanelControllerSource, /const buyForm = useTradeForm/)
    assert.match(tradePanelControllerSource, /const sellForm = useTradeForm/)
    assert.match(tradePanelControllerSource, /const \[confirmation, setConfirmation\]/)
    assert.match(tradePanelControllerSource, /useTradeSubmit\(/)
    assert.doesNotMatch(`${pcSource}\n${mobileSource}`, /useTradingSession|useTradingQuoteMap|useTradePanelController/)
  })

  it('adapts the exact same model through PlatformView without rebuilding controllers', () => {
    assert.match(routeSource, /const controller = useTradingRouteController\(\{ product \}\)/)
    assert.match(routeSource, /<PlatformView[\s\S]*model=\{controller\.model\}/)
    assert.match(pcSource, /\{ model \}: \{ model: TradingRouteModel \}/)
    assert.match(mobileSource, /\{ model \}: \{ model: TradingRouteModel \}/)
    assert.match(routeSource, /data-controller-sentinel=\{controller\.model\.controllerSentinel\}/)
  })
})
