import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const projectRoot = join(currentDir, '..', '..', '..', '..', '..')
const source = readFileSync(join(currentDir, 'TradingPage.tsx'), 'utf8')
const sourceLines = source.split(/\r?\n/)
const desktopSource = readFileSync(join(currentDir, 'components', 'TradingDesktopView.tsx'), 'utf8')
const mobileSource = readFileSync(join(currentDir, 'components', 'TradingMobileView.tsx'), 'utf8')
const orderSheetSource = readFileSync(join(currentDir, 'components', 'TradingOrderSheet.tsx'), 'utf8')
const marketSidebarSource = readFileSync(join(currentDir, 'components', 'MarketSidebar.tsx'), 'utf8')
const settingsDialogPath = join(currentDir, 'components', 'TradingSettingsDialog.tsx')
const marketSelectionSource = readFileSync(join(currentDir, 'tradingPageMarketSelection.ts'), 'utf8')
const marketStatusSource = readFileSync(join(currentDir, 'tradingPageMarketDataStatus.ts'), 'utf8')
const marketStatusHookSource = readFileSync(join(currentDir, 'useTradingMarketDataStatus.ts'), 'utf8')
const sessionStatusSource = readFileSync(join(currentDir, 'tradingPageSessionStatus.ts'), 'utf8')
const tradeRulesSource = readFileSync(join(currentDir, 'tradingPageTradeRules.ts'), 'utf8')
const viewModelsSource = readFileSync(join(currentDir, 'tradingPageViewModels.ts'), 'utf8')
const chartSettingsSource = readFileSync(join(currentDir, 'useTradingChartSettings.ts'), 'utf8')
const styles = readFileSync(join(currentDir, 'TradingPage.module.css'), 'utf8')
const layoutStyles = styles.slice(styles.indexOf('.layout {'), styles.indexOf('.desktopTerminal {'))
const packageJson = JSON.parse(readFileSync(join(projectRoot, 'package.json'), 'utf8'))
const tradingSmokeSource = readFileSync(join(projectRoot, 'scripts', 'smoke-trading-login-gate.mjs'), 'utf8')

describe('TradingPage terminal viewport', () => {
  it('uses a long-page terminal shell instead of pinning the desktop workspace to one viewport', () => {
    assert.match(styles, /\.page\s*{[\s\S]*min-height:\s*100vh[\s\S]*margin:\s*0[\s\S]*padding:\s*0[\s\S]*background:\s*var\(--trading-page-bg\)/)
    assert.doesNotMatch(layoutStyles, /\n\s+height:\s*calc\(100vh - 16px\)/)
    assert.match(layoutStyles, /min-height:\s*calc\(100vh - 16px\)/)
    assert.match(styles, /--trading-page-bg:\s*var\(--theme-background\)/)
    assert.match(styles, /\.terminalTicker/)
    assert.match(styles, /\.binanceGrid/)
  })

  it('allows the terminal page and desktop layout to extend vertically in the browser document', () => {
    assert.match(styles, /\.page\s*{[\s\S]*overflow-x:\s*hidden;[\s\S]*overflow-y:\s*auto;/)
    assert.match(styles, /\.desktopTerminal\s*{[\s\S]*align-items:\s*start;/)
    assert.doesNotMatch(styles, /\.leftRail\s*{[\s\S]*position:\s*sticky;/)
  })

  it('passes the watchlist and symbol header into the same draggable workspace as the trading panels', () => {
    assert.match(desktopSource, /watchlist=\{[\s\S]*<MarketSidebar/)
    assert.match(desktopSource, /header=\{[\s\S]*<SymbolHeader/)
    assert.match(desktopSource, /<TradingWorkspace[\s\S]*watchlist=\{[\s\S]*header=\{[\s\S]*chart=\{/)
    assert.doesNotMatch(desktopSource, /className=\{styles\.leftRail\}/)
  })

  it('passes a slash-separated mobile chart title to the workspace', () => {
    assert.match(source, /chartTitle:\s*formatTradingChartTitle\(selectedMarketWithRules,\s*t\)/)
    assert.match(desktopSource, /chartTitle=\{chartTitle\}/)
    assert.match(marketSelectionSource, /function formatTradingChartTitle\(market: TradingMarket,\s*t: Translate = defaultTranslate\)/)
    assert.match(marketSelectionSource, /\$\{market\.base\}\/\$\{market\.quote\} \$\{t\('chart\.titleSuffix'\)\}/)
  })

  it('wires the TradePanel to the real trading session submit path', () => {
    assert.match(desktopSource, /trade=\{[\s\S]*<TradePanel[\s\S]*symbol=\{symbol\}/)
    assert.match(source, /<TradingOrderSheet/)
    assert.match(orderSheetSource, /<MobileOrderSheet[\s\S]*<TradePanel[\s\S]*compact[\s\S]*symbol=\{view\.symbol\}/)
    assert.match(desktopSource, /category=\{market\.category\}/)
    assert.match(orderSheetSource, /category=\{view\.market\.category\}/)
    assert.match(desktopSource, /leverage=\{market\.leverage\}/)
    assert.match(orderSheetSource, /leverage=\{view\.market\.leverage\}/)
    assert.match(desktopSource, /rules=\{market\.rules\}/)
    assert.match(orderSheetSource, /rules=\{view\.market\.rules\}/)
    assert.match(orderSheetSource, /accountId=\{view\.accountId\}/)
    assert.match(orderSheetSource, /balances=\{view\.balances\}/)
    assert.match(orderSheetSource, /sessionReady=\{view\.sessionReady\}/)
    assert.match(orderSheetSource, /sessionMode=\{view\.tradePanelSessionMode\}/)
    assert.match(orderSheetSource, /sessionError=\{view\.sessionError\}/)
    assert.doesNotMatch(desktopSource, /orderError=\{lastOrderError\}/)
    assert.match(desktopSource, /onSubmitOrder=\{submitOrder\}/)
    assert.match(desktopSource, /onRetrySession=\{onRetrySession\}/)
  })

  it('wires clicked order book and recent trade prices into the limit order form', () => {
    assert.match(source, /tradePricePrefill/)
    assert.match(source, /handleSelectPrice/)
    assert.match(source, /setTradePricePrefill\(\{ id: Date\.now\(\), price \}\)/)
    assert.match(desktopSource, /onSelectPrice=\{onSelectPrice\}/)
    assert.match(desktopSource, /pricePrefill=\{tradePricePrefill\}/)
    assert.match(source, /<MobileDrawer[\s\S]*title="Quote"[\s\S]*onSelectPrice=\{handleSelectPrice\}/)
    assert.match(orderSheetSource, /pricePrefill=\{view\.tradePricePrefill\}/)
  })

  it('keeps TradingPage as an orchestration shell with extracted views', () => {
    assert.match(source, /<TradingDesktopView/)
    assert.match(source, /<TradingMobileView/)
    assert.doesNotMatch(source, /<TradingWorkspace[\s\S]*<ChartWorkspace[\s\S]*<TradePanel[\s\S]*<BottomAccountPanel/)
  })

  it('wires the bottom account panel to real trading session data', () => {
    assert.match(desktopSource, /bottom=\{[\s\S]*<BottomAccountPanel/)
    assert.match(mobileSource, /accountPanel=\{[\s\S]*<BottomAccountPanel/)
    assert.match(desktopSource, /account=\{accountPanel\.account\}/)
    assert.match(desktopSource, /orders=\{accountPanel\.orders\}/)
    assert.match(desktopSource, /positions=\{accountPanel\.positions\}/)
    assert.match(desktopSource, /ledgerEntries=\{accountPanel\.ledgerEntries\}/)
    assert.match(desktopSource, /currentSymbol=\{symbol\}/)
    assert.match(mobileSource, /currentSymbol=\{symbol\}/)
    assert.match(desktopSource, /onClosePosition=\{accountPanel\.onClosePosition\}/)
  })

  it('selects the initial trading symbol from the canonical product route', () => {
    assert.match(source, /useParams/)
    assert.match(source, /export function TradingPage\(\{ product \}: TradingPageProps\)/)
    assert.match(source, /normalizeTradingProductSymbol\(product, routeSymbol\) \?\? defaultTradingSymbols\[product\]/)
    assert.match(source, /getTradingMarketsForProduct\(mergedMarkets, product\)/)
  })

  it('keeps first-paint terminal regions in loading state until the session resolves', () => {
    assert.match(source, /const terminalLoading = sessionMode === 'loading'/)
    assert.doesNotMatch(source, /mobileTerminalFallback|正在连接交易终端|Connecting trading terminal/)
    assert.match(desktopSource, /market=\{[\s\S]*<RightTradingPanel[\s\S]*loading=\{terminalLoading\}/)
    assert.match(desktopSource, /bottom=\{[\s\S]*<BottomAccountPanel[\s\S]*loading=\{accountPanel\.loading\}/)
    assert.match(source, /<MobileDrawer[\s\S]*title="Quote"[\s\S]*<RightTradingPanel[\s\S]*loading=\{terminalLoading\}/)
  })

  it('limits realtime quote subscriptions instead of subscribing every visible market', () => {
    assert.match(source, /getRealtimeQuoteMarkets/)
    assert.match(source, /const quoteMarkets = useMemo/)
    assert.match(source, /useTradingQuoteMap\(quoteMarkets,\s*token,\s*handleQuoteStatus\)/)
    assert.doesNotMatch(source, /useTradingQuoteMap\(visibleMarkets,\s*token\)/)
  })

  it('fetches selected symbol rules and shares them with the trade panel market', () => {
    assert.match(source, /fetchMarketSymbolRules/)
    assert.match(source, /useState<TradingInstrumentRules \| null>\(null\)/)
    assert.match(source, /setSelectedRules\(rules\)/)
    assert.match(source, /const selectedMarketWithRules = useMemo/)
    assert.match(source, /mergeMarketRules\(selectedMarket,\s*selectedRules\)/)
    assert.match(tradeRulesSource, /rules/)
    assert.match(source, /market:\s*selectedMarketWithRules/)
  })

  it('waits for backend market capabilities before seeding the full mock watchlist', () => {
    assert.match(source, /useState<TradingMarket\[\]>\(\[\]\)/)
    assert.match(source, /catch\(\(\) => \{[\s\S]*setMarkets\(getTradingMarketsForProduct\(mergeWithLocalTradingMarkets\(\[\]\), product\)\)/)
  })

  it('shares market favorites between the trading watchlist and market self-selection', () => {
    assert.match(source, /useMarketFavorites\(token\)/)
    assert.match(source, /favorites:\s*favoriteSymbols/)
    assert.match(source, /onFavorite:\s*toggleFavorite/)
    assert.match(desktopSource, /<MarketSidebar[\s\S]*favorites=\{favorites\}[\s\S]*onFavorite=\{onFavorite\}/)
    assert.match(marketSidebarSource, /favorites: Set<string>/)
    assert.match(marketSidebarSource, /onFavorite: \(symbol: string\) => void/)
    assert.doesNotMatch(marketSidebarSource, /useState\(\(\) => new Set\(markets\.filter/)
  })

  it('does not expose the removed trading settings button or dialog', () => {
    assert.equal(existsSync(settingsDialogPath), false)
    assert.doesNotMatch(source, /settingsOpen|setSettingsOpen|<TradingSettingsDialog|onOpenSettings/)
    assert.doesNotMatch(desktopSource, /openTradingSettings|settingsButton|onOpenSettings/)
    assert.doesNotMatch(mobileSource, /onOpenSettings/)
    assert.doesNotMatch(viewModelsSource, /onOpenSettings|TradingSettingsDialogProps/)
    assert.doesNotMatch(styles, /settingsButton|settingsLayer|settingsDialog|layoutPresetButton/)
    assert.doesNotMatch(source, /saveTradingThemeMode\(nextMode\)/)
  })

  it('keeps workspace layout controls internal to the draggable workspace', () => {
    assert.match(source, /useResizableLayout\(\)/)
    assert.match(source, /workspaceLayoutControls/)
    assert.match(desktopSource, /layoutControls=\{workspaceLayoutControls\}/)
    assert.doesNotMatch(desktopSource, /sessionTelemetry/)
  })

  it('keeps guest watch mode non-blocking until a trade action asks for login', () => {
    assert.match(source, /LoginPromptDialog/)
    assert.match(source, /loginPromptRequested/)
    assert.match(source, /pendingTradeOpen/)
    assert.match(source, /sessionMode === 'loading'[\s\S]*setPendingTradeOpen\(true\)/)
    assert.match(source, /if \(!pendingTradeOpen \|\| sessionMode === 'loading'\) return/)
    assert.match(source, /setLoginPromptRequested\(true\)/)
    assert.match(source, /open=\{loginRequired && loginPromptRequested\}/)
    assert.match(source, /handleLoginRedirect/)
    assert.match(source, /navigate\(`\/login\?redirect=\$\{encodeURIComponent\(resolveTradingPath\(product, selectedSymbol\)\)\}`\)/)
    assert.match(source, /onClose=\{\(\) => setLoginPromptRequested\(false\)\}/)
    assert.match(orderSheetSource, /loginRequired=\{view\.loginRequired\}/)
    assert.match(orderSheetSource, /onLoginRequired=\{view\.onLoginRequired\}/)
    assert.doesNotMatch(source, /loginRequired && !loginPromptDismissed/)
    assert.doesNotMatch(source, /onLoginRequired=\{handleLoginRedirect\}/)
  })

  it('exposes a reusable trading smoke command with mobile Trade click coverage', () => {
    assert.equal(packageJson.scripts['web:smoke:trading'], 'node scripts/smoke-trading-login-gate.mjs')
    assert.match(tradingSmokeSource, /await verifyMobileTradeAction\(/)
    assert.match(tradingSmokeSource, /launchChrome\(\{ windowSize: '390,844' \}\)/)
    assert.match(tradingSmokeSource, /Emulation\.setDeviceMetricsOverride/)
    assert.match(tradingSmokeSource, /mobile Trade action opens login prompt/)
    assert.match(tradingSmokeSource, /mobile Trade action opens order sheet/)
    assert.match(tradingSmokeSource, /button\.textContent\?\.trim\(\) === 'Trade'/)
  })

  it('keeps session status models for compact panel and mobile surfaces without a desktop telemetry row', () => {
    assert.match(source, /sessionAuthStatus/)
    assert.match(source, /getTradingSessionStatusLabel\(sessionMode, sessionAuthStatus,\s*t\)/)
    assert.match(source, /getTradingSessionStatusText\(\{ sessionMode, sessionAuthStatus, sessionError, loginRequired,\s*t \}\)/)
    assert.doesNotMatch(source, /const showSessionRetry = sessionMode === 'error' \|\| sessionAuthStatus === 'invalid_token'/)
    assert.match(sessionStatusSource, /case 'guest':[\s\S]*trading\.publicMarketMode/)
    assert.match(sessionStatusSource, /case 'valid_token':[\s\S]*trading\.accountLinkConnected/)
    assert.match(sessionStatusSource, /case 'invalid_token':[\s\S]*trading\.loginExpired/)
    assert.match(sessionStatusSource, /trading\.loginExpiredRetry/)
    assert.doesNotMatch(desktopSource, /className=\{styles\.sessionTelemetry\}/)
    assert.doesNotMatch(styles, /\.sessionTelemetry/)
  })

  it('does not keep an offline-preview session mode in the terminal UI state text', () => {
    assert.doesNotMatch(source, /offline-preview/)
    assert.doesNotMatch(source, /本地预览模式可用/)
  })

  it('uses global design-system themes while keeping the chart API on dark or light mode', () => {
    assert.match(source, /useTheme/)
    assert.match(source, /useTradingChartSettings\(\s*selectedSymbol,\s*currentTheme\.colorScheme\s*\)/)
    assert.match(chartSettingsSource, /chartThemeMode:\s*colorScheme === 'light' \? 'light' : 'dark'/)
    assert.match(desktopSource, /themeMode=\{chartThemeMode\}/)
    assert.match(mobileSource, /themeMode=\{chartThemeMode\}/)
    assert.doesNotMatch(source, /data-theme=\{themeMode\}/)
    assert.doesNotMatch(styles, /\.page\[data-theme='light'\]\s*{[\s\S]*--trading-page-bg:/)
    assert.match(styles, /--trading-chart-grid-horizontal:/)
  })

  it('does not use generated chart history on any trading route', () => {
    assert.doesNotMatch(viewModelsSource, /shouldAllowChartMockFallback/)
    assert.doesNotMatch(desktopSource, /allowMockFallback/)
    assert.doesNotMatch(mobileSource, /allowMockFallback/)
  })

  it('shows explicit market data source and failure status on the trading page', () => {
    assert.match(source, /useTradingMarketDataStatus\(\)/)
    assert.match(marketStatusHookSource, /fetchMarketStatus/)
    assert.match(marketStatusHookSource, /marketDataStatus/)
    assert.match(source, /handleQuoteStatus/)
    assert.match(marketStatusHookSource, /getTradingMarketDataStatusView\(/)
    assert.match(source, /marketDataStatusView/)
    assert.match(source, /marketDataStatusView:\s*marketDataStatusView/)
    assert.match(desktopSource, /marketDataStatusView/)
    assert.match(desktopSource, /className=\{styles\.marketDataStatus\}/)
    assert.match(mobileSource, /marketDataStatusView=\{marketDataStatusView\}/)
    assert.match(marketStatusSource, /Massive/)
    assert.match(marketStatusSource, /Demo quote/)
    assert.match(marketStatusSource, /QUOTE_PROVIDER_UNAVAILABLE/)
  })

  it('starts the authoritative quote-depth-trades bundle before the mobile quote drawer opens', () => {
    assert.match(source, /startQuoteMarketDataAdapter/)
    assert.match(source, /useEffect\(\(\) => startQuoteMarketDataAdapter\(selectedSymbol, token\), \[selectedSymbol, token\]\)/)
  })

  it('keeps TradingPage below the orchestration size budget', () => {
    assert.ok(sourceLines.length <= 340, `TradingPage.tsx has ${sourceLines.length} lines`)
  })
})
