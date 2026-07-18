import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const webSrc = resolve(currentDir, '..', '..')
const projectRoot = resolve(webSrc, '..', '..', '..')
const routeSource = readFileSync(join(webSrc, 'routes', 'trading', 'TradingRoute.tsx'), 'utf8')
const controllerSource = readFileSync(join(webSrc, 'routes', 'trading', 'useTradingRouteController.ts'), 'utf8')
const routeTypesSource = readFileSync(join(webSrc, 'routes', 'trading', 'tradingRoute.types.ts'), 'utf8')
const routeStyles = readFileSync(join(webSrc, 'routes', 'trading', 'TradingRoute.module.css'), 'utf8')
const pcSource = readFileSync(join(webSrc, 'pc', 'pages', 'trading', 'PcTradingTerminal.tsx'), 'utf8')
const pcStyles = readFileSync(join(webSrc, 'pc', 'pages', 'trading', 'PcTradingTerminal.module.css'), 'utf8')
const mobileSource = readFileSync(join(webSrc, 'mobile', 'pages', 'trading', 'MobileTradingTerminal.tsx'), 'utf8')
const orderSheetSource = readFileSync(join(webSrc, 'mobile', 'pages', 'trading', 'TradingOrderSheet.tsx'), 'utf8')
const tradePanelControllerSource = readFileSync(join(currentDir, 'order-form', 'useTradePanelController.ts'), 'utf8')
const marketSidebarSource = readFileSync(join(currentDir, 'components', 'MarketSidebar.tsx'), 'utf8')
const marketSelectionSource = readFileSync(join(currentDir, 'tradingPageMarketSelection.ts'), 'utf8')
const marketStatusSource = readFileSync(join(currentDir, 'tradingPageMarketDataStatus.ts'), 'utf8')
const marketStatusHookSource = readFileSync(join(currentDir, 'useTradingMarketDataStatus.ts'), 'utf8')
const sessionStatusSource = readFileSync(join(currentDir, 'tradingPageSessionStatus.ts'), 'utf8')
const tradeRulesSource = readFileSync(join(currentDir, 'tradingPageTradeRules.ts'), 'utf8')
const chartSettingsSource = readFileSync(join(currentDir, 'useTradingChartSettings.ts'), 'utf8')
const packageJson = JSON.parse(readFileSync(join(projectRoot, 'package.json'), 'utf8'))
const tradingSmokeSource = readFileSync(join(projectRoot, 'scripts', 'smoke-trading-login-gate.mjs'), 'utf8')
const appSource = readFileSync(join(webSrc, 'app', 'App.tsx'), 'utf8')
const noticeSource = readFileSync(join(currentDir, 'components', 'MarketSourceChangeNotice.tsx'), 'utf8')
const submitHookSource = readFileSync(join(projectRoot, 'packages', 'frontend-core', 'src', 'trading', 'useTradeSubmit.ts'), 'utf8')
const source = `${routeSource}\n${controllerSource}\n${tradePanelControllerSource}`

describe('trading terminal route and business continuity', () => {
  it('uses a long-page PC terminal without a CSS breakpoint platform switch', () => {
    assert.match(routeStyles, /\.page\s*\{[\s\S]*min-height:\s*100vh[\s\S]*overflow-x:\s*hidden[\s\S]*overflow-y:\s*auto/)
    assert.match(routeStyles, /--trading-page-bg:\s*var\(--theme-background\)/)
    assert.match(pcStyles, /\.layout\s*\{[\s\S]*min-height:\s*calc\(100vh - 16px\)/)
    assert.doesNotMatch(pcStyles, /(?:^|\n)\s*height:\s*calc\(100vh - 16px\)/)
    assert.match(pcStyles, /\.ticker/)
    assert.doesNotMatch(`${routeStyles}\n${pcStyles}`, /max-width:\s*768px|display:\s*none/)
  })

  it('keeps the watchlist, symbol header and trading panels in the PC-only workspace', () => {
    assert.match(pcSource, /watchlist=\{[\s\S]*<MarketSidebar/)
    assert.match(pcSource, /header=\{[\s\S]*<SymbolHeader/)
    assert.match(pcSource, /<TradingWorkspace[\s\S]*watchlist=\{[\s\S]*header=\{[\s\S]*chart=\{/)
    assert.match(pcSource, /useResizableLayout\(\)/)
    assert.doesNotMatch(mobileSource, /TradingWorkspace|useResizableLayout|layoutStore/)
  })

  it('passes the canonical chart title and theme to both active platform views', () => {
    assert.match(controllerSource, /chartTitle:\s*formatTradingChartTitle\(selectedMarketWithRules,\s*t\)/)
    assert.match(pcSource, /chartTitle=\{model\.chartTitle\}/)
    assert.match(marketSelectionSource, /\$\{market\.base\}\/\$\{market\.quote\} \$\{t\('chart\.titleSuffix'\)\}/)
    assert.match(controllerSource, /useTheme/)
    assert.match(controllerSource, /useTradingChartSettings\(\s*selectedSymbol,\s*currentTheme\.colorScheme\s*\)/)
    assert.match(chartSettingsSource, /chartThemeMode:\s*colorScheme === 'light' \? 'light' : 'dark'/)
    assert.match(pcSource, /themeMode=\{model\.chartThemeMode\}/)
    assert.match(mobileSource, /themeMode=\{model\.chartThemeMode\}/)
  })

  it('owns one controlled TradePanel model across PC, Mobile and confirmation resize', () => {
    assert.equal((controllerSource.match(/useTradePanelController\(/g) ?? []).length, 1)
    assert.match(pcSource, /<TradePanel model=\{model\.tradePanel\} \/>/)
    assert.match(mobileSource, /tradePanel=\{model\.tradePanel\}/)
    assert.match(orderSheetSource, /<TradePanel compact model=\{tradePanel\} \/>/)
    assert.match(tradePanelControllerSource, /const buyForm = useTradeForm/)
    assert.match(tradePanelControllerSource, /const sellForm = useTradeForm/)
    assert.match(tradePanelControllerSource, /const \[confirmation, setConfirmation\]/)
    assert.match(tradePanelControllerSource, /useTradeSubmit\(/)
    assert.match(tradePanelControllerSource, /onSubmitOrder/)
    assert.match(tradePanelControllerSource, /onSubmitOco/)
  })

  it('wires clicked depth prices into the same persistent limit-order form', () => {
    assert.match(controllerSource, /tradePricePrefill/)
    assert.match(controllerSource, /setTradePricePrefill\(\{ id: Date\.now\(\), price \}\)/)
    assert.match(pcSource, /onSelectPrice=\{model\.onSelectPrice\}/)
    assert.match(mobileSource, /onSelectPrice=\{model\.onSelectPrice\}/)
    assert.match(controllerSource, /pricePrefill:\s*tradePricePrefill/)
  })

  it('wires real account collections and mutations into both terminals', () => {
    for (const view of [pcSource, mobileSource]) {
      assert.match(view, /<BottomAccountPanel/)
      assert.match(view, /orders=\{model\.accountPanel\.orders\}/)
      assert.match(view, /positions=\{model\.accountPanel\.positions\}/)
      assert.match(view, /ledgerEntries=\{model\.accountPanel\.ledgerEntries\}/)
      assert.match(view, /onClosePosition=\{model\.accountPanel\.onClosePosition\}/)
    }
  })

  it('preserves canonical product routing, rules, capabilities and bounded quote subscriptions', () => {
    assert.match(controllerSource, /normalizeTradingProductSymbol\(product, routeSymbol\) \?\? defaultTradingSymbols\[product\]/)
    assert.match(controllerSource, /getTradingMarketsForProduct\(mergedMarkets, product\)/)
    assert.match(controllerSource, /fetchMarketSymbolRules/)
    assert.match(controllerSource, /mergeMarketRules\(selectedMarket,\s*selectedRules\)/)
    assert.match(tradeRulesSource, /rules/)
    assert.match(controllerSource, /getRealtimeQuoteMarkets/)
    assert.match(controllerSource, /useTradingQuoteMap\(quoteMarkets,\s*token,\s*handleQuoteStatus\)/)
    assert.doesNotMatch(controllerSource, /useTradingQuoteMap\(visibleMarkets/)
  })

  it('shares favorites and keeps first paint in an explicit loading state', () => {
    assert.match(controllerSource, /useMarketFavorites\(token\)/)
    assert.match(controllerSource, /favorites:\s*favoriteSymbols/)
    assert.match(controllerSource, /onFavorite:\s*toggleFavorite/)
    assert.match(marketSidebarSource, /favorites: Set<string>/)
    assert.match(controllerSource, /const terminalLoading = sessionMode === 'loading'/)
    assert.match(pcSource, /loading=\{model\.terminalLoading\}/)
    assert.match(mobileSource, /loading=\{model\.terminalLoading\}/)
  })

  it('keeps guest watch mode non-blocking until a trade action asks for login', () => {
    assert.match(controllerSource, /loginPromptRequested/)
    assert.match(controllerSource, /pendingTradeOpen/)
    assert.match(controllerSource, /sessionMode === 'loading'[\s\S]*setPendingTradeOpen\(true\)/)
    assert.match(controllerSource, /loginPromptOpen:\s*loginRequired && loginPromptRequested/)
    assert.match(routeSource, /<LoginPromptDialog/)
    assert.match(controllerSource, /navigate\(\`\/login\?redirect=\$\{encodeURIComponent\(resolveTradingPath\(product, selectedSymbol\)\)\}\`\)/)
    assert.doesNotMatch(source, /offline-preview|mock login/i)
  })

  it('keeps status, source transition and provider failure surfaces explicit', () => {
    assert.match(controllerSource, /getTradingSessionStatusLabel\(sessionMode, sessionAuthStatus, t\)/)
    assert.match(sessionStatusSource, /case 'guest':[\s\S]*trading\.publicMarketMode/)
    assert.match(sessionStatusSource, /case 'invalid_token':[\s\S]*trading\.loginExpired/)
    assert.match(controllerSource, /useTradingMarketDataStatus\(\)/)
    assert.match(marketStatusHookSource, /fetchMarketStatus/)
    assert.match(marketStatusSource, /Massive|Demo quote|QUOTE_PROVIDER_UNAVAILABLE/)
    assert.match(controllerSource, /startQuoteMarketDataAdapter\(selectedSymbol, token\)/)
    assert.match(routeSource, /<MarketSourceChangeNotice notice=\{controller\.sourceNotice\} \/>/)
    assert.match(routeSource, /data-controller-sentinel=\{controller\.model\.controllerSentinel\}/)
  })

  it('does not retain removed settings or generated chart fallback contracts', () => {
    assert.equal(existsSync(join(currentDir, 'components', 'TradingSettingsDialog.tsx')), false)
    assert.doesNotMatch(source, /settingsOpen|<TradingSettingsDialog|onOpenSettings|offline-preview/)
    assert.doesNotMatch(routeTypesSource, /onOpenSettings|TradingSettingsDialogProps|workspaceLayoutControls/)
    assert.doesNotMatch(`${pcSource}\n${mobileSource}`, /allowMockFallback/)
  })

  it('exposes the existing mobile trading smoke command', () => {
    assert.equal(packageJson.scripts['web:smoke:trading'], 'node scripts/smoke-trading-login-gate.mjs')
    assert.match(tradingSmokeSource, /await verifyMobileTradeAction\(/)
    assert.match(tradingSmokeSource, /windowSize: '390,844'/)
    assert.match(tradingSmokeSource, /mobile Trade action opens login prompt/)
    assert.match(tradingSmokeSource, /mobile Trade action opens order sheet/)
  })

  it('keeps migrated orchestration files within the strengthened size limits', () => {
    assert.ok(controllerSource.split(/\r?\n/).length <= 330)
    assert.ok(pcSource.split(/\r?\n/).length <= 330)
    assert.ok(mobileSource.split(/\r?\n/).length <= 230)
  })

  it('loads the trading route separately before either platform entry', () => {
    assert.match(appSource, /const TradingRoute = lazy\(/)
    assert.match(appSource, /import\('\.\.\/routes\/trading\/TradingRoute'\)/)
    assert.doesNotMatch(appSource, /PcTradingTerminal|MobileTradingTerminal/)
  })

  it('persists canonical symbol selection without provider-symbol rewriting', () => {
    assert.match(controllerSource, /writeLastTradingSymbol\(product, routedSymbol\)/)
    assert.match(controllerSource, /writeLastTradingSymbol\(product, normalized\)/)
    assert.match(controllerSource, /navigate\(resolveTradingPath\(product, normalized\)\)/)
    assert.match(controllerSource, /normalizeTradingProductSymbol\(product, symbol\)/)
  })

  it('keeps provider capability failures on the exact local P0 allowlist', () => {
    assert.match(controllerSource, /fetchMarketSymbols\(\)/)
    assert.match(controllerSource, /mergeWithLocalTradingMarkets\(nextMarkets\)/)
    assert.match(controllerSource, /setMarkets\(getTradingMarketsForProduct\(mergeWithLocalTradingMarkets\(\[\]\), product\)\)/)
    assert.doesNotMatch(controllerSource, /mockMarket|generatedMarket/i)
  })

  it('routes batch and position mutations through the one account session', () => {
    assert.match(controllerSource, /onCancelAllOrders:\s*cancelAllOrders/)
    assert.match(controllerSource, /onCloseAllPositions:\s*closeAllPositions/)
    assert.match(controllerSource, /onClosePosition:\s*closePosition/)
    assert.doesNotMatch(`${pcSource}\n${mobileSource}`, /cancelAllOrders\(|closeAllPositions\(|closePosition\(/)
  })

  it('keeps Mobile overlay state and close commands in the route model', () => {
    assert.match(controllerSource, /const \[marketDrawerOpen, setMarketDrawerOpen\]/)
    assert.match(controllerSource, /const \[quoteDrawerOpen, setQuoteDrawerOpen\]/)
    assert.match(controllerSource, /const \[orderSheetOpen, setOrderSheetOpen\]/)
    assert.match(controllerSource, /closeMarketDrawer:\s*\(\) => setMarketDrawerOpen\(false\)/)
    assert.match(mobileSource, /open=\{model\.marketDrawerOpen\}/)
    assert.match(mobileSource, /open=\{model\.orderSheetOpen\}/)
  })

  it('keeps submit single-flight state above a platform remount', () => {
    assert.match(tradePanelControllerSource, /useTradeSubmit\(/)
    assert.match(submitHookSource, /const submitInFlight = useRef\(false\)/)
    assert.match(submitHookSource, /if \(submitInFlight\.current\) return/)
    assert.match(submitHookSource, /submitInFlight\.current = true/)
    assert.match(submitHookSource, /finally \{[\s\S]*submitInFlight\.current = false/)
  })

  it('preserves form drafts across resize and resets them only for a symbol change or success', () => {
    assert.match(tradePanelControllerSource, /const buyForm = useTradeForm\('buy'/)
    assert.match(tradePanelControllerSource, /const sellForm = useTradeForm\('sell'/)
    assert.match(tradePanelControllerSource, /useEffect\(\(\) => \{[\s\S]*setConfirmation\(null\)[\s\S]*\}, \[market\.symbol\]\)/)
    assert.doesNotMatch(pcSource, /useTradeForm/)
    assert.doesNotMatch(mobileSource, /useTradeForm/)
  })

  it('keeps safe login redirection bound to the selected canonical terminal', () => {
    assert.match(controllerSource, /resolveTradingPath\(product, selectedSymbol\)/)
    assert.match(controllerSource, /encodeURIComponent/)
    assert.match(controllerSource, /setOrderSheetOpen\(false\)[\s\S]*setLoginPromptRequested\(true\)/)
    assert.doesNotMatch(routeSource, /mockLogin|demoLogin/i)
  })

  it('keeps source-transition automation metadata stable across both views', () => {
    assert.match(noticeSource, /data-testid="market-source-change-notice"/)
    assert.match(noticeSource, /data-market-source-change="true"/)
    assert.match(noticeSource, /data-previous-provider=\{previousProvider\}/)
    assert.match(noticeSource, /data-current-provider=\{notice\.providerCode\}/)
    assert.match(noticeSource, /role="status"/)
    assert.match(noticeSource, /aria-live="polite"/)
  })

  it('keeps selected-market status explicit on PC and Mobile', () => {
    assert.match(pcSource, /data-tone=\{model\.marketDataStatusView\.tone\}/)
    assert.match(mobileSource, /marketDataStatusView=\{model\.marketDataStatusView\}/)
    assert.match(marketStatusHookSource, /getTradingMarketDataStatusView/)
    assert.doesNotMatch(`${pcSource}\n${mobileSource}`, /status:\s*'live'/)
  })

  it('keeps PC and Mobile imports mutually isolated', () => {
    assert.doesNotMatch(pcSource, /mobile\//)
    assert.doesNotMatch(mobileSource, /pc\//)
    assert.doesNotMatch(mobileSource, /layout\/|layoutStore|useResizableLayout/)
  })

  it('keeps the Route Controller free of platform layout ownership', () => {
    assert.doesNotMatch(controllerSource, /TradingWorkspace|layoutStore|useResizableLayout/)
    assert.doesNotMatch(routeTypesSource, /TradingLayout|TradingPanelId|TradingWorkspaceLayoutControls/)
    assert.match(pcSource, /workspaceLayoutControls/)
  })

  it('removes every superseded trading page and viewport owner', () => {
    assert.equal(existsSync(join(webSrc, 'pages', 'trading', 'TradingPage.tsx')), false)
    assert.equal(existsSync(join(webSrc, 'pages', 'trading', 'useMobileTerminalViewport.ts')), false)
    assert.equal(existsSync(join(webSrc, 'features', 'trading', 'styles', 'trade-panel.css')), false)
    assert.doesNotMatch(source, /max-width:\s*768px/)
  })
})
