import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const pagesDir = dirname(fileURLToPath(import.meta.url))
const projectRoot = resolve(pagesDir, '../../../..')
const marketsContent = readFileSync(join(pagesDir, '..', 'shared-widgets', 'market', 'MarketsContent.tsx'), 'utf8')
const marketsController = readFileSync(join(pagesDir, '..', 'routes', 'markets', 'useMarketsRouteController.ts'), 'utf8')
const marketsModel = readFileSync(join(pagesDir, '..', 'routes', 'markets', 'marketsRouteModel.ts'), 'utf8')
const pcMarketTablePath = join(pagesDir, '..', 'pc', 'pages', 'markets', 'PcMarketTable.tsx')
const mobileMarketListPath = join(pagesDir, '..', 'mobile', 'pages', 'markets', 'MobileMarketList.tsx')
const pcMarketTable = existsSync(pcMarketTablePath) ? readFileSync(pcMarketTablePath, 'utf8') : ''
const mobileMarketList = existsSync(mobileMarketListPath) ? readFileSync(mobileMarketListPath, 'utf8') : ''
const marketsViews = [
  readFileSync(join(pagesDir, '..', 'pc', 'pages', 'markets', 'PcMarketsPage.tsx'), 'utf8'),
  readFileSync(join(pagesDir, '..', 'mobile', 'pages', 'markets', 'MobileMarketsPage.tsx'), 'utf8'),
  pcMarketTable,
  mobileMarketList
].join('\n')
const markets = `${marketsContent}\n${marketsController}\n${marketsModel}\n${marketsViews}`
const binanceMarketData = readFileSync(
  join(projectRoot, 'packages', 'frontend-core', 'src', 'market', 'binanceMarketData.ts'),
  'utf8'
)
const marketSources = `${markets}\n${binanceMarketData}`
const orders = [
  readFileSync(join(pagesDir, '..', 'shared-widgets', 'orders', 'OrdersRouteContent.tsx'), 'utf8'),
  readFileSync(join(pagesDir, '..', 'routes', 'orders', 'useOrdersRouteController.ts'), 'utf8'),
  readFileSync(join(pagesDir, '..', 'routes', 'orders', 'orderActionPolicy.ts'), 'utf8'),
  readFileSync(join(pagesDir, '..', 'routes', 'orders', 'orderActionPayloads.ts'), 'utf8')
].join('\n')
const positions = [
  readFileSync(join(pagesDir, '..', 'shared-widgets', 'positions', 'PositionsRouteContent.tsx'), 'utf8'),
  readFileSync(join(pagesDir, '..', 'routes', 'positions', 'usePositionsRouteController.ts'), 'utf8'),
  readFileSync(join(pagesDir, '..', 'routes', 'positions', 'positionProtectionPolicy.ts'), 'utf8')
].join('\n')
const accountPagesPath = join(pagesDir, '..', 'shared-widgets', 'account', 'AccountPagesContent.tsx')
const accountControllerPath = join(pagesDir, '..', 'routes', 'account', 'useAccountRouteController.ts')
const accountRoutePath = join(pagesDir, '..', 'routes', 'account', 'AccountRoutes.tsx')
const accountViewPaths = [
  join(pagesDir, '..', 'pc', 'pages', 'account', 'PcAccountDataCollection.tsx'),
  join(pagesDir, '..', 'mobile', 'pages', 'account', 'MobileAccountDataCollection.tsx')
]
const accountContent = existsSync(accountPagesPath) ? readFileSync(accountPagesPath, 'utf8') : ''
const accountController = readFileSync(accountControllerPath, 'utf8')
const accountRoute = readFileSync(accountRoutePath, 'utf8')
const accountViews = accountViewPaths.map((path) => readFileSync(path, 'utf8')).join('\n')
const accountPages = `${accountContent}\n${accountController}\n${accountRoute}\n${accountViews}`
const wallet = [
  readFileSync(join(pagesDir, '..', 'shared-widgets', 'wallet', 'WalletRouteContent.tsx'), 'utf8'),
  readFileSync(join(pagesDir, '..', 'routes', 'wallet', 'useWalletRouteController.ts'), 'utf8'),
  readFileSync(join(pagesDir, '..', 'routes', 'wallet', 'walletRouteModel.ts'), 'utf8'),
  readFileSync(join(pagesDir, '..', 'pc', 'components', 'PcDataCollection.tsx'), 'utf8'),
  readFileSync(join(pagesDir, '..', 'mobile', 'components', 'MobileDataCollection.tsx'), 'utf8')
].join('\n')
const accountApi = readFileSync(join(projectRoot, 'packages', 'frontend-core', 'src', 'api', 'accountApi.ts'), 'utf8')
const tradingTypes = readFileSync(join(projectRoot, 'packages', 'frontend-core', 'src', 'models', 'trading.ts'), 'utf8')
const authController = readFileSync(join(pagesDir, '..', 'routes', 'auth', 'useAuthRouteController.ts'), 'utf8')
const authModel = readFileSync(join(pagesDir, '..', 'routes', 'auth', 'authRouteModel.ts'), 'utf8')
const authContent = readFileSync(join(pagesDir, '..', 'shared-widgets', 'auth', 'AuthPageContent.tsx'), 'utf8')
const authSupport = `${authController}\n${authModel}\n${authContent}`
const login = authSupport
const authStyles = readFileSync(join(pagesDir, '..', 'shared-widgets', 'auth', 'AuthPageContent.module.css'), 'utf8')
const baseStyles = readFileSync(join(pagesDir, '..', 'styles.css'), 'utf8')
const userPageStyles = readFileSync(join(pagesDir, '..', 'shared-widgets', 'data', 'UserPageSurface.module.css'), 'utf8')
const accountStyles = readFileSync(join(pagesDir, '..', 'shared-widgets', 'account', 'AccountPagesContent.module.css'), 'utf8')
const walletStyles = readFileSync(join(pagesDir, '..', 'shared-widgets', 'wallet', 'WalletRouteContent.module.css'), 'utf8')
const styles = `${baseStyles}\n${userPageStyles}\n${accountStyles}\n${walletStyles}`
const marketStyles = readFileSync(join(pagesDir, '..', 'shared-widgets', 'market', 'MarketsContent.module.css'), 'utf8')
const pcMarketsStyles = readFileSync(join(pagesDir, '..', 'pc', 'pages', 'markets', 'PcMarketsPage.module.css'), 'utf8')
const mobileMarketsStyles = readFileSync(join(pagesDir, '..', 'mobile', 'pages', 'markets', 'MobileMarketsPage.module.css'), 'utf8')
const pcMarketTableStylesPath = join(pagesDir, '..', 'pc', 'pages', 'markets', 'PcMarketTable.module.css')
const mobileMarketListStylesPath = join(pagesDir, '..', 'mobile', 'pages', 'markets', 'MobileMarketList.module.css')
const pcMarketTableStyles = existsSync(pcMarketTableStylesPath) ? readFileSync(pcMarketTableStylesPath, 'utf8') : ''
const mobileMarketListStyles = existsSync(mobileMarketListStylesPath) ? readFileSync(mobileMarketListStylesPath, 'utf8') : ''
const selectFieldPath = join(projectRoot, 'packages', 'ui', 'src', 'select-field', 'SelectField.tsx')
const selectField = existsSync(selectFieldPath) ? readFileSync(selectFieldPath, 'utf8') : ''
const selectFieldCss = readFileSync(join(projectRoot, 'packages', 'ui', 'src', 'select-field', 'SelectField.module.css'), 'utf8')
const marketSortSelectStart = marketStyles.indexOf('.market-sort-field__select {')
const marketSortSelectCss = marketStyles.slice(
  marketSortSelectStart,
  marketStyles.indexOf('.market-universe-tabs,', marketSortSelectStart),
)

describe('prototype markets page', () => {
  it('keeps the market API and realtime layer while presenting a Binance-style overview model', () => {
    assert.match(markets, /useMarketFavorites/)
    assert.match(markets, /fetchMarketSymbols/)
    assert.match(markets, /fetchMarketQuote/)
    assert.match(markets, /fetchBinanceMarketOverview/)
    assert.match(markets, /fetchBinanceFuturesDashboard/)
    assert.match(markets, /mergeBinanceOverviewMarkets/)
    assert.match(markets, /subscribeQuote/)
    assert.match(markets, /LoadingState/)
    assert.match(markets, /ApiErrorState/)
    assert.match(markets, /formatApiError/)
    assert.match(markets, /useState<TradingMarket\[\]>\(\[\]\)/)
    assert.match(markets, /const nextMarkets = mergeBinanceOverviewMarkets/)
    assert.doesNotMatch(markets, /mockTradingData/)
    assert.match(markets, /marketPageTabs/)
    assert.match(markets, /overview/)
    assert.match(markets, /trading-data/)
    assert.match(markets, /ai-picks/)
    assert.match(markets, /token-unlocks/)
    assert.match(markets, /marketUniverseTabs/)
    assert.match(markets, /marketZoneTabs/)
    assert.match(markets, /favorites/)
    assert.doesNotMatch(markets, /favoriteStorageKey/)
    assert.match(markets, /crypto/)
    assert.match(markets, /forex/)
    assert.match(markets, /contract/)
    assert.match(markets, /spot/)
    assert.match(markets, /all/)
    assert.match(markets, /MobileMarketList/)
    assert.match(markets, /marketColumns/)
    assert.doesNotMatch(markets, /market-shell__hero/)
    assert.match(markets, /market-shell__titleSrOnly/)
    assert.match(markets, /24h成交量/)
    assert.match(markets, /市值/)
    assert.doesNotMatch(markets, /Bid\/Ask|high24h|low24h|sourceStatus/)
  })

  it('shows the market loading state before tab-specific content renders', () => {
    const tabsIndex = markets.indexOf("<div className={styles['market-shell__tabs']}")
    const overviewIndex = markets.indexOf("{pageTab === 'overview'")
    const loadingIndex = markets.indexOf('{loading ? <LoadingState', tabsIndex)

    assert.ok(tabsIndex > 0)
    assert.ok(overviewIndex > tabsIndex)
    assert.ok(loadingIndex > tabsIndex && loadingIndex < overviewIndex)
  })

  it('shows the market API error state before tab-specific content renders', () => {
    const tabsIndex = markets.indexOf("<div className={styles['market-shell__tabs']}")
    const overviewIndex = markets.indexOf("{pageTab === 'overview'")
    const errorIndex = markets.indexOf('{apiError ? (', tabsIndex)

    assert.ok(tabsIndex > 0)
    assert.ok(overviewIndex > tabsIndex)
    assert.ok(errorIndex > tabsIndex && errorIndex < overviewIndex)
  })

  it('uses readable Binance-like market labels for the visible market surface', () => {
    assert.match(markets, /加密货币市场/)
    assert.match(markets, /总览/)
    assert.match(markets, /交易数据/)
    assert.match(marketSources, /合约持仓量/)
    assert.match(marketSources, /多空账户数比/)
    assert.match(markets, /去合约交易/)
    assert.match(markets, /Fear & Greed/)
    assert.match(markets, /Hot Tokens/)
    assert.match(markets, /24h成交量/)
    assert.match(markets, /市值/)
    assert.doesNotMatch(markets, />[^<]*鎬昏/)
  })

  it('uses the requested overview universe tabs and routes the forex tab to provider-backed forex rows', () => {
    const universeTabs = markets.slice(markets.indexOf('const marketUniverseTabs'), markets.indexOf('const marketZoneTabs'))
    assert.match(markets, /const marketUniverseTabs: Array<\{ value: MarketUniverseTab; label: string \}> = \[\s*\{ value: 'favorites', label: '自选' \},\s*\{ value: 'forex', label: '外汇' \},\s*\{ value: 'crypto', label: '币种' \},\s*\{ value: 'spot', label: '现货' \},\s*\{ value: 'contract', label: '合约' \}\s*\]/)
    assert.doesNotMatch(universeTabs, /\{ value: 'all', label:/)
    assert.doesNotMatch(universeTabs, /label: 'TradFi'/)
    assert.match(markets, /universeTab === 'forex'[\s\S]*\? market\.category === 'fx'/)
    assert.match(markets, /fetchMarketSymbols\(\)/)
  })

  it('limits forex market zone tabs to the all category', () => {
    assert.match(markets, /const forexMarketZoneTabs = marketZoneTabs\.filter\(\(tab\) => tab\.value === 'all'\)/)
    assert.match(markets, /const activeMarketZoneTabs = universeTab === 'forex' \? forexMarketZoneTabs : marketZoneTabs/)
    assert.match(markets, /zoneTabs=\{activeMarketZoneTabs\}/)
    assert.match(markets, /zoneTabs\.map\(\(tab\) =>/)
    assert.match(markets, /if \(nextTab === 'forex'\) setZoneTab\('all'\)/)
  })

  it('paginates the overview market list at 20 rows by default', () => {
    assert.match(markets, /const marketOverviewPageSize = 20/)
    assert.match(markets, /paginateRows\(visibleMarkets, marketPage, marketOverviewPageSize\)/)
    assert.match(markets, /<MarketCollection[\s\S]*markets=\{pagedMarkets\.items\}/)
    assert.match(marketsViews, /PcMarketTable/)
    assert.match(marketsViews, /MobileMarketList/)
    assert.match(markets, /pagedMarkets\.pageSize/)
  })

  it('renders provider symbol lists before quote hydration so forex data is not blocked by every pair quote', () => {
    assert.match(markets, /const marketQuoteHydrationLimit = 40/)
    assert.match(markets, /Promise\.allSettled\(\[[\s\S]*fetchMarketSymbols\(\)[\s\S]*fetchBinanceMarketOverview\(\)[\s\S]*\]\)/)
    assert.match(markets, /const nextMarkets = mergeBinanceOverviewMarkets\(symbols, nextOverview\?\.markets \?\? \[\]\)/)
    assert.match(markets, /setMarkets\(nextMarkets\)/)
    assert.match(markets, /loadQuotedMarkets\([\s\S]*nextMarkets\.filter\(canHydrateMarketQuote\)\.slice\(0, marketQuoteHydrationLimit\)[\s\S]*\)/)
    assert.match(markets, /fetchMarketQuotes/)
    assert.doesNotMatch(markets, /Promise\.all\([\s\S]*loadQuotedMarket/)
    assert.match(markets, /visibleMarkets[\s\S]*\.filter\(canHydrateMarketQuote\)[\s\S]*\.slice\(0, marketQuoteHydrationLimit\)/)
    assert.match(markets, /function canHydrateMarketQuote\(market: TradingMarket\)/)
    assert.match(markets, /market\.source !== 'binance-market-overview'/)
    assert.match(markets, /market\.tradable !== false/)
    assert.match(markets, /market\.category === 'fx'/)
    assert.match(markets, /market\.provider === 'massive'/)
    assert.match(markets, /market\.provider === 'binance'/)
  })

  it('maps mainstream crypto additions into concrete market tabs', () => {
    assert.match(markets, /zone === 'solana'[\s\S]*market\.symbol === 'SOLUSDT'/)
    assert.match(markets, /zone === 'payments'[\s\S]*market\.symbol === 'XRPUSDT'/)
  })

  it('connects only canonical P0 market rows to product-aware trading routes', () => {
    assert.match(markets, /resolveMarketTradingTarget/)
    assert.match(markets, /isMarketTradingEnabled/)
    assert.match(markets, /const openMarket/)
    assert.match(markets, /navigate\(target\)/)
  })

  it('recreates the Binance futures trading-data chart surface without copying page assets', () => {
    assert.match(markets, /SummaryCard/)
    assert.match(markets, /MarketSummaryDeck/)
    assert.match(markets, /RankingPreviewCard/)
    assert.match(markets, /MarketInsightPlaceholder/)
    assert.match(markets, /TradingDataDashboard/)
    assert.match(markets, /FuturesChartPanel/)
    assert.match(markets, /futuresChartPanels/)
    assert.match(markets, /dashboard\?\.panels/)
    assert.match(binanceMarketData, /buildBinanceFuturesDashboard/)
    assert.match(markets, /futures-view-toggle/)
    assert.match(markets, /futures-period-tabs/)
    assert.match(markets, /futures-chart-tooltip/)
    assert.match(markets, /futures-chart-crosshair/)
    assert.match(binanceMarketData, /合约持仓量/)
    assert.match(binanceMarketData, /大户账户数多空比/)
    assert.match(binanceMarketData, /大户持仓量多空比/)
    assert.match(binanceMarketData, /多空账户数比/)
    assert.match(binanceMarketData, /合约主动买卖量/)
    assert.match(binanceMarketData, /资金费率/)
    assert.match(markets, /U本位合约/)
    assert.match(markets, /币本位合约/)
    assert.doesNotMatch(markets, /Alpha|DEX|C2C/)
    assert.doesNotMatch(markets, /highcharts|bnbstatic|common-widget/)
  })

  it('keeps the rankings tab on ranking cards while leaving U-margined futures charts separate', () => {
    assert.match(markets, /const rankings = useMemo\(\(\) => buildRankings\(hydratedMarkets\), \[hydratedMarkets\]\)/)
    assert.match(markets, /<TradingDataDashboard rankings=\{rankings\} onOpen=\{model\.openMarket\} futures=\{model\.futures\}/)
    assert.match(markets, /function TradingDataDashboard\(\{[\s\S]*futures[\s\S]*MarketsRouteModel\['futures'\]/)
    assert.match(markets, /useState<TradingDataTab>\('rankings'\)/)
    assert.match(markets, /activeTab === 'rankings' \? \(/)
    assert.match(markets, /styles\['market-data-dashboard'\][\s\S]*styles\['market-ranking-preview-grid'\]/)
    assert.match(markets, /rankings\.map\(\(group\) =>/)
    assert.match(markets, /activeTab === 'usdt-contracts' \? \(/)
    assert.match(markets, /<section className=\{styles\['futures-data-pane'\]\}/)
    assert.match(markets, /function RankingCard/)
    assert.match(markets, /function buildRankings/)
    assert.match(markets, /热门币种/)
    assert.match(markets, /涨幅榜/)
    assert.match(markets, /跌幅榜/)
    assert.match(markets, /成交榜/)
  })

  it('uses the reusable theme-aware listbox select for market sorting', () => {
    assert.equal(existsSync(selectFieldPath), true)
    assert.match(markets, /SelectField/)
    assert.doesNotMatch(markets, /<select value=\{sortKey\}/)
    assert.match(selectField, /role="listbox"/)
    assert.match(selectField, /role="option"/)
    assert.match(selectField, /aria-selected/)
    assert.match(selectFieldCss, /\.menu\s*{/)
    assert.match(selectFieldCss, /\.option\[aria-selected='true'\]/)
    assert.match(marketSortSelectCss, /--select-menu-bottom:\s*calc\(100% \+ 6px\)/)
    assert.match(marketSortSelectCss, /--select-menu-min-width:\s*144px/)
    assert.match(selectFieldCss, /@media \(prefers-reduced-motion:\s*reduce\)[\s\S]*\.menu/)
    assert.match(selectFieldCss, /\.root\[data-open='true'\]\s+\.chevron\s*{[\s\S]*transform:\s*rotate\(180deg\)/)
    assert.match(selectFieldCss, /\.button:hover\s*{[\s\S]*color-mix\(in srgb,\s*var\(--theme-primary\) 42%,\s*transparent\)/)
    assert.match(selectFieldCss, /\.menu\s*{[\s\S]*border:\s*1px solid var\(--select-menu-border, color-mix\(in srgb, var\(--theme-border\) 74%, transparent\)\)/)
    assert.doesNotMatch(selectFieldCss, /:global/)
    assert.doesNotMatch(selectFieldCss, /linear-gradient|box-shadow|animation:\s*dropdownIn/)
    assert.doesNotMatch(marketSortSelectCss, /box-shadow/)
  })
})

describe('prototype auth and account center', () => {
  it('moves successful login and registration to account overview', () => {
    assert.match(login, /return mode === 'login' \? loginRedirect : '\/account\/overview'/)
    assert.match(authSupport, /navigate\(successPath, \{ replace: true \}\)/)
    assert.match(authSupport, /AuthPageContent/)
  })

  it('registers email or phone identifiers directly through the backend', () => {
    assert.match(authSupport, /channel/)
    assert.match(authSupport, /countryCode/)
    assert.match(authSupport, /identifier/)
    assert.match(authSupport, /password/)
    assert.match(authSupport, /await register\(/)
    assert.match(authSupport, /normalizeRegistrationIdentifier\(channel, countryCode, identifier\)/)
    assert.doesNotMatch(authSupport, /verification/)
    assert.doesNotMatch(authSupport, /checkAuthIdentity/)
    assert.doesNotMatch(authSupport, /sendAuthVerificationCode/)
    assert.doesNotMatch(authSupport, /local-user|local-\$\{Date\.now\(\)\}/)
    assert.doesNotMatch(authSupport, /不做真实|真实注册|不会创建账户/)
  })

  it('uses a Binance-style authentication shell while preserving login and registration APIs', () => {
    assert.match(login, /await login\(email\.trim\(\), password\)/)
    assert.match(login, /type="text"/)
    assert.match(login, /writeStoredAuthTokens\(auth\.accessToken,\s*auth\.refreshToken\)/)
    assert.match(authSupport, /writeStoredAuthTokens\(auth\.accessToken,\s*auth\.refreshToken\)/)
    assert.match(authSupport, /await register\(/)
    assert.match(authStyles, /\.page\s*{[\s\S]*background:\s*var\(--auth-bg\)/)
    assert.match(authStyles, /\.shell\s*{[\s\S]*grid-template-columns:\s*minmax\(0,\s*520px\) 425px/)
    assert.match(authStyles, /\.form\s*{[\s\S]*width:\s*425px[\s\S]*padding:\s*40px[\s\S]*border-radius:\s*24px[\s\S]*border:\s*1px solid var\(--auth-border\)/)
    assert.match(authStyles, /\.submit\s*{[\s\S]*background:\s*var\(--auth-primary-hover\)/)
    assert.match(authStyles, /\.socialButton\s*{[\s\S]*min-height:\s*48px[\s\S]*border:\s*1px solid color-mix\(in srgb,\s*var\(--auth-border\) 76%,\s*var\(--auth-muted\)\)[\s\S]*border-radius:\s*12px/)
    assert.match(authStyles, /\.segmentedControl button\[aria-pressed="true"\]\s*{[\s\S]*background:\s*var\(--auth-primary-hover\)/)
    assert.doesNotMatch(authStyles, /radial-gradient|backdrop-filter|gridDrift/)
  })

  it('creates the account shell and only the live account pages', () => {
    assert.equal(existsSync(accountPagesPath), true)
    assert.match(accountPages, /AccountShell/)
    assert.match(accountPages, /AccountOverviewContent/)
    assert.match(accountPages, /AccountAssetsContent/)
    assert.match(accountPages, /FundingRecordsContent/)
    assert.match(accountPages, /TradeOrdersContent/)
    assert.match(accountPages, /KycContent/)
    assert.match(accountPages, /AccountSettingsContent/)
    assert.match(accountPages, /\/account\/overview/)
    assert.match(accountPages, /\/account\/assets/)
    assert.match(accountPages, /\/account\/orders\/funding/)
    assert.match(accountPages, /\/account\/orders\/trades/)
    assert.match(accountPages, /\/account\/security\/kyc/)
    assert.match(accountPages, /\/account\/settings/)
    assert.doesNotMatch(accountPages, /C2C|期权|邀请好友|活动中心|API 管理|返佣|子账户/)
  })

  it('connects account pages to the authenticated trading session instead of static placeholders', () => {
    assert.match(accountPages, /useTranslatedAccountData/)
    assert.match(accountPages, /DataTable/)
    assert.match(accountPages, /DataCardList/)
    assert.match(accountPages, /LoadingState/)
    assert.match(accountPages, /LoginRequiredState/)
    assert.match(accountPages, /ApiErrorState/)
    assert.match(accountPages, /formatApiError/)
    assert.match(accountPages, /getFundOrders/)
    assert.match(accountPages, /createFundOrder/)
    assert.match(accountPages, /walletBalances/)
    assert.match(accountPages, /ledgerEntries/)
    assert.match(accountPages, /orders/)
    assert.match(accountPages, /positions/)
    assert.doesNotMatch(accountPages, /const ledgers =/)
    assert.doesNotMatch(accountPages, /const tradeOrders =/)
    assert.doesNotMatch(accountPages, /rows=\{ledgers\.map/)
    assert.doesNotMatch(accountPages, /rows=\{tradeOrders\.map/)
  })

  it('routes account asset operations to the shared Demo Spot and Perpetual controls', () => {
    assert.match(accountPages, /\/wallet#wallet-assets/)
    assert.match(accountPages, /Spot and Perpetual/)
    assert.doesNotMatch(accountPages, /AssetConversionPanel/)
    assert.doesNotMatch(accountPages, /convertAsset/)
    assert.doesNotMatch(accountPages, /USDT_PERP/)
  })

  it('keeps account pages scoped to assets, ledgers, trade orders, KYC and preferences', () => {
    assert.match(accountPages, /createAssetColumns/)
    assert.match(accountPages, /createLedgerColumns/)
    assert.match(accountPages, /createTradeOrderColumns/)
    assert.match(accountPages, /DemoWalletOperationsLink/)
    assert.match(accountPages, /RecentLedgerPanel/)
    assert.match(accountPages, /AssetAccountCards/)
    assert.match(accountPages, /Funding records/)
    assert.match(accountPages, /Trade orders/)
    assert.match(accountPages, /Open orders/)
    assert.match(accountPages, /Order history/)
    assert.match(accountPages, /Fill history/)
    assert.match(accountPages, /Identity verification/)
    assert.match(accountPages, /Notification language/)
  })

  it('marks KYC as coming soon for internal testing instead of exposing a live-looking entry', () => {
    const kycPage = accountPages.slice(accountPages.indexOf('export function KycContent'), accountPages.indexOf('export function AccountSettingsContent'))

    assert.match(kycPage, /Coming soon/)
    assert.match(kycPage, /Internal test/)
    assert.match(kycPage, /disabled/)
    assert.doesNotMatch(kycPage, /Start verification/)
  })

  it('remodels account overview into a Binance-style dashboard flow', () => {
    assert.match(accountPages, /AccountProfileSummary/)
    assert.match(accountPages, /AccountOnboardingSteps/)
    assert.match(accountPages, /AccountAssetActionPanel/)
    assert.match(accountPages, /AccountDashboardInsights/)
    assert.match(accountPages, /account-dashboard-layout/)
    assert.match(accountPages, /account-onboarding-grid/)
    assert.match(accountPages, /account-action-strip/)
    assert.match(accountPages, /account-insight-grid/)
    assert.match(styles, /\.account-dashboard-layout/)
    assert.match(styles, /\.account-onboarding-grid/)
    assert.match(styles, /\.account-step-card--active/)
    assert.match(styles, /\.account-action-strip/)
    assert.match(styles, /@media \(max-width:\s*720px\)[\s\S]*\.account-onboarding-grid\s*{[\s\S]*grid-template-columns:\s*1fr/)
  })

  it('skins user pages with theme-aware tokens and compact motion', () => {
    assert.match(marketStyles, /--user-page-bg:\s*var\(--bn-bg\)/)
    assert.match(marketStyles, /--user-surface:\s*var\(--bn-surface\)/)
    assert.match(marketStyles, /--user-primary:\s*var\(--theme-primary\)/)
    assert.match(styles, /\.account-shell/)
    assert.match(marketStyles, /\.user-page/)
    assert.match(marketStyles, /\.table-pagination/)
    assert.match(marketStyles, /\.table-action--secondary/)
    assert.match(marketStyles, /\.market-shell/)
    assert.match(marketStyles, /\.market-shell__titleSrOnly/)
    assert.match(marketStyles, /\.market-zone-tabs/)
    assert.match(marketStyles, /\.market-ranking-preview-grid/)
    assert.doesNotMatch(marketsContent, /export function (?:MarketTable|MarketMobileList)/)
    assert.doesNotMatch(marketStyles, /(?:^|[\n,])\s*\.market-table(?:\s|,|\{|:)/m)
    assert.doesNotMatch(marketStyles, /\.market-mobile-(?:list|row)/)
    assert.match(pcMarketTableStyles, /\.table table\s*\{[^}]*border-collapse:\s*collapse/su)
    assert.match(mobileMarketListStyles, /\.list\s*\{[^}]*display:\s*grid/su)
    assert.match(marketStyles, /@media \(prefers-reduced-motion:\s*reduce\)/)
    assert.doesNotMatch(marketsContent, /className="/)
    assert.doesNotMatch(`${marketStyles}\n${pcMarketsStyles}\n${mobileMarketsStyles}\n${pcMarketTableStyles}\n${mobileMarketListStyles}`, /:global/)
  })

  it('remodels wallet into a Binance-style asset dashboard without changing the funding data flow', () => {
    assert.match(wallet, /useWalletController/)
    assert.match(wallet, /getFundOrders/)
    assert.match(wallet, /createFundOrder/)
    assert.match(wallet, /walletBalances/)
    assert.match(wallet, /assetLedgerEntries/)
    assert.match(wallet, /getAssetRows\(wallet\.account,\s*wallet\.walletBalances,\s*wallet\.assetLedgerEntries,\s*frozenAmount\)/)
    assert.match(wallet, /balances:\s*WalletBalance\[\]/)
    assert.match(wallet, /entries:\s*AssetLedgerEntry\[\]/)
    assert.match(wallet, /walletType/)
    assert.match(wallet, /walletKey\(balance\.walletType,\s*balance\.asset\)/)
    assert.match(wallet, /ledgerWalletTypeFilter/)
    assert.match(wallet, /entry\.walletType === ledgerWalletTypeFilter/)
    assert.match(wallet, /DataTable/)
    assert.match(wallet, /wallet-page/)
    assert.match(wallet, /wallet-hero-grid/)
    assert.match(wallet, /wallet-balance-card/)
    assert.match(wallet, /wallet-account-grid/)
    assert.match(wallet, /wallet-risk-card/)
    assert.match(wallet, /action-card-grid/)
    assert.match(wallet, /wallet-asset-detail/)
    assert.match(wallet, /wallet-workbench/)
    assert.match(wallet, /wallet-sidebar/)
    assert.match(wallet, /wallet-login-gate/)
    assert.match(styles, /\.wallet-page__hero/)
    assert.match(styles, /\.wallet-hero-grid/)
    assert.match(styles, /\.wallet-balance-card/)
    assert.match(styles, /\.wallet-account-grid/)
    assert.match(styles, /\.wallet-risk-card/)
    assert.match(styles, /\.wallet-workbench/)
    assert.match(styles, /\.wallet-sidebar/)
    assert.match(styles, /\.wallet-login-gate/)
    assert.match(styles, /@media \(max-width:\s*720px\)[\s\S]*\.wallet-hero-grid\s*{[\s\S]*grid-template-columns:\s*1fr/)
    assert.match(
      styles,
      /@media \(min-width:\s*901px\) and \(max-width:\s*1199px\)[\s\S]*\[data-platform-view='pc'\] \.wallet-hero-grid\s*{[^}]*grid-template-columns:\s*repeat\(2,\s*minmax\(0,\s*1fr\)\)/
    )
    assert.match(walletStyles, /\[data-platform-view='pc'\] \.wallet-risk-card\s*{[^}]*grid-column:\s*1\s*\/\s*-1/)
    assert.match(walletStyles, /\[data-platform-view='pc'\] \.wallet-simulation-note\s*{[^}]*flex-direction:\s*column/)
    assert.match(
      walletStyles,
      /\.wallet-workbench__main\s*\{[^}]*grid-template-columns:\s*minmax\(0,\s*1fr\)/
    )
    assert.match(userPageStyles, /@media \(max-width:\s*1024px\)[\s\S]*\.user-page__metrics,[\s\S]*grid-template-columns:\s*var\(--user-form-columns,\s*repeat\(2,\s*minmax\(0,\s*1fr\)\)\)/)
  })

  it('exposes backend account snapshot and asset-ledger contracts to the frontend', () => {
    assert.match(accountApi, /AssetLedgerFilters/)
    assert.match(accountApi, /getAssetLedger/)
    assert.match(accountApi, /convertAsset/)
    assert.match(accountApi, /\/api\/accounts\/\$\{accountId\}\/asset-ledger/)
    assert.match(accountApi, /\/api\/accounts\/\$\{accountId\}\/asset-conversions/)
    assert.match(tradingTypes, /export type AssetLedgerEntry/)
    assert.match(tradingTypes, /export type AssetConversionPayload/)
    assert.match(tradingTypes, /export type AssetConversionResponse/)
    assert.match(tradingTypes, /walletType/)
    assert.match(tradingTypes, /openFloatingPnl/)
    assert.match(tradingTypes, /maintenanceMargin/)
    assert.match(tradingTypes, /positionValue/)
    assert.match(tradingTypes, /marginAvailable/)
    assert.match(tradingTypes, /lastSnapshotAt/)
    assert.match(tradingTypes, /warning/)
  })
})

describe('user order page automation hooks', () => {
  it('keeps order actions addressable by backend order identity', () => {
    assert.match(orders, /data-order-id=\{order\.id\}/)
    assert.match(orders, /data-order-status=\{order\.status\}/)
  })

  it('dispatches normal and protective order actions without legacy TP or SL fields', () => {
    assert.match(orders, /policy\.cancelVia === 'PROTECTION'/)
    assert.match(orders, /policy\.modifyVia === 'PROTECTION'/)
    assert.match(orders, /cancelProtection\(order\.id, token\)/)
    assert.match(orders, /updateProtection\(editingOrder\.id, payload, token\)/)
    assert.match(orders, /buildNormalOrderUpdatePayload/)
    assert.doesNotMatch(orders, /form\.get\('stopLoss'\)/)
    assert.doesNotMatch(orders, /form\.get\('takeProfit'\)/)
    assert.doesNotMatch(orders, /<input name="stopLoss"/)
    assert.doesNotMatch(orders, /<input name="takeProfit"/)
  })

  it('keeps position actions addressable by backend position identity', () => {
    assert.match(positions, /data-position-id=\{position\.id\}/)
    assert.match(positions, /data-position-status=\{position\.status\}/)
  })
})
