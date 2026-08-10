import assert from 'node:assert/strict'
import { existsSync, readFileSync, readdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const sessionStatusPath = join(currentDir, 'TradePanelSessionStatus.tsx')
const coreTradingDir = join(currentDir, '../../../../../../packages/frontend-core/src/trading')
const submitHookPath = join(coreTradingDir, 'useTradeSubmit.ts')
const tradeFormPath = join(coreTradingDir, 'useTradeForm.ts')
const tradePanelComponentSource = readFileSync(join(currentDir, 'TradePanel.tsx'), 'utf8')
const tradePanelControllerSource = readFileSync(join(currentDir, 'useTradePanelController.ts'), 'utf8')
const tradePanelSource = `${tradePanelComponentSource}\n${tradePanelControllerSource}`
const tradePanelLines = tradePanelComponentSource.split(/\r?\n/)
const sessionStatusSource = existsSync(sessionStatusPath) ? readFileSync(sessionStatusPath, 'utf8') : ''
const submitHookSource = existsSync(submitHookPath) ? readFileSync(submitHookPath, 'utf8') : ''
const confirmDialogSource = readFileSync(join(currentDir, 'OrderConfirmationDialog.tsx'), 'utf8')
const leverageDialogSource = readFileSync(join(currentDir, 'TradePanelLeverageControls.tsx'), 'utf8')
const orderSideSource = readFileSync(join(currentDir, 'OrderFormSide.tsx'), 'utf8')
const orderSubmitButtonSource = readFileSync(join(currentDir, 'OrderSubmitButton.tsx'), 'utf8')
const orderTypeTabsSource = readFileSync(join(currentDir, 'OrderTypeTabs.tsx'), 'utf8')
const tradeTabsSource = readFileSync(join(currentDir, 'TradeTabs.tsx'), 'utf8')
const tpSlPanelSource = readFileSync(join(currentDir, 'TpSlPanel.tsx'), 'utf8')
const styles = readFileSync(join(currentDir, 'TradePanel.module.css'), 'utf8')
const tradingRouteStyles = readFileSync(join(currentDir, '../../../routes/trading/TradingRoute.module.css'), 'utf8')
const tradingWorkspaceStyles = readFileSync(join(currentDir, '../../../pc/pages/trading/layout/TradingWorkspace.module.css'), 'utf8')
const styledComponentSources = readdirSync(currentDir)
  .filter((fileName) => fileName.endsWith('.tsx'))
  .map((fileName) => ({ fileName, source: readFileSync(join(currentDir, fileName), 'utf8') }))
  .filter(({ source }) => source.includes('trade-panel'))

const userFacingSources = [
  tradePanelSource,
  sessionStatusSource,
  confirmDialogSource,
  orderSideSource,
  orderSubmitButtonSource,
  orderTypeTabsSource,
  tradeTabsSource,
  tpSlPanelSource
]

describe('OKX-style trade panel density', () => {
  it('keeps TradePanel as a compact composition shell for the spot order form', () => {
    assert.equal(existsSync(sessionStatusPath), true)
    assert.equal(existsSync(submitHookPath), true)
    assert.ok(tradePanelLines.length <= 230, `TradePanel.tsx has ${tradePanelLines.length} lines`)
    assert.match(tradePanelSource, /<OrderConfirmationDialog/)
    assert.match(tradePanelSource, /useTradeSubmit/)
    assert.doesNotMatch(tradePanelSource, /TradePanelAccountStrip/)
    assert.doesNotMatch(tradePanelSource, /TradePanelLeverageToggle/)
    assert.doesNotMatch(tradePanelSource, /TradePanelLeverageControls/)
    assert.doesNotMatch(tradePanelSource, /function LeverageCell/)
    assert.doesNotMatch(tradePanelSource, /function formatOrderError/)
  })

  it('uses i18n keys instead of hard-coded Chinese in visible trade panel components', () => {
    for (const source of userFacingSources) {
      assert.doesNotMatch(source, /[\p{Script=Han}]/u)
    }
  })

  it('exposes only P0 normal order types from the order tabs', () => {
    assert.match(tradeTabsSource, /t\('trading\.spot'\)/)
    assert.match(tradeTabsSource, /t\('trading\.perpetual'\)/)
    assert.doesNotMatch(tradeTabsSource, /crossMargin|isolatedMargin|gridTrading/)
    assert.match(orderTypeTabsSource, /strategyType === 'trigger'/)
    assert.match(orderTypeTabsSource, /strategyType === 'oco'/)
    assert.match(orderTypeTabsSource, /!strategyActive && orderType === 'limit'/)
    assert.match(orderTypeTabsSource, /!strategyActive && orderType === 'market'/)
    assert.doesNotMatch(orderTypeTabsSource, /StrategyDropdown|post_only|\bfok\b|\bioc\b|iceberg|twap|trailing/i)
  })

  it('keeps the Binance-style dual form shell for wide layouts', () => {
    assert.match(tradePanelSource, /trade-panel__forms--dual/)
    assert.match(styles, /\.trade-panel__forms--dual\s*{[\s\S]*grid-template-columns:\s*minmax\(0,\s*1fr\)\s*minmax\(0,\s*1fr\)/)
    assert.match(styles, /\.trade-panel--compact\s+\.trade-panel__forms--dual\s*{[\s\S]*grid-template-columns:\s*1fr/)
  })

  it('keeps interactive Mobile sheet inputs touch- and zoom-safe without changing PC density', () => {
    assert.match(styles, /\[data-platform-view='mobile'\]\s+\.trade-panel--compact\s+\.trade-panel__control input:not\(:disabled\)\s*\{[\s\S]*?min-height:\s*44px;[\s\S]*?font-size:\s*16px/)
    assert.match(styles, /\[data-platform-view='pc'\]\s+\.trade-panel__control,[\s\S]*?\.trade-panel__best-price\s*\{[\s\S]*?min-height:\s*34px/)
  })

  it('shows canonical quantity-unit sizing inside each side form', () => {
    assert.match(orderSideSource, /usesQuoteBudgetMarketBuy\(form,\s*market\)/)
    assert.match(orderSideSource, /form\.quantityUnit === 'QUOTE'/)
    assert.match(orderSideSource, /form\.quantityUnit === 'CONTRACTS'/)
    assert.doesNotMatch(orderSideSource, /<TpSlPanel/)
  })

  it('uses the real order callback when a backend trading session is ready', () => {
    assert.match(submitHookSource, /onSubmitOrder/)
    assert.match(submitHookSource, /toOrderPayload\(accountId,\s*form,\s*market,\s*adapterSettings\)/)
    assert.match(submitHookSource, /toOcoOrderPayload/)
    assert.match(submitHookSource, /createCanonicalPayload\(accountId,\s*form,\s*market,\s*adapterSettings\)/)
    assert.match(submitHookSource, /await onSubmitOrder\(payload\)/)
    assert.match(tradePanelSource, /useTradeSubmit\(\{[\s\S]*adapterSettings:/)
    assert.match(tradePanelSource, /resolveTradePanelLeverage\(resolveRuleLeverage\(adapterSettings\.leverage \?\? symbolLeverage,\s*rules\)\)/)
    assert.match(tradePanelSource, /createPanelMarket\(symbol,\s*snapshot,\s*\{ category,\s*leverage,\s*productType,\s*rules \}\)/)
    assert.doesNotMatch(tradePanelSource, /const leverage = 1/)
    assert.match(tradePanelSource, /backendReady/)
    assert.match(submitHookSource, /\.\/orderAdapter\.ts/)
  })

  it('keeps trading limits and precision props available to the order form', () => {
    assert.match(tradePanelSource, /rules\?: TradeMarket\['rules'\]/)
    assert.match(tradePanelSource, /resolveRuleNumber\(rules\?\.minNotional,\s*5\)/)
    assert.match(tradePanelSource, /minOrderAmount/)
    assert.match(tradePanelSource, /pricePrecision/)
    assert.match(tradePanelSource, /quantityPrecision/)
    assert.match(orderSideSource, /t\('trading\.available'\)/)
    assert.match(orderSideSource, /minAmount/)
  })

  it('requires a confirmation dialog before real order submission', () => {
    assert.match(submitHookSource, /onConfirmRequired/)
    assert.match(submitHookSource, /key: 'trading\.submitConfirmFirst'/)
    assert.match(confirmDialogSource, /import \{ Dialog \} from '@fx-platform\/ui'/)
    assert.match(confirmDialogSource, /<Dialog/)
    assert.match(confirmDialogSource, /priority="critical"/)
    assert.match(confirmDialogSource, /pending=\{submitting\}/)
    assert.match(confirmDialogSource, /t\('trading\.orderConfirmTitle'\)/)
    assert.match(confirmDialogSource, /t\('trading\.skipConfirm'\)/)
    assert.match(confirmDialogSource, /buildConfirmationRows/)
    assert.match(confirmDialogSource, /OCO \/ GTC/)
    assert.match(confirmDialogSource, /Position side/)
    assert.match(confirmDialogSource, /Reduce only/)
    assert.match(confirmDialogSource, /Attached TP \/ SL/)
    assert.match(confirmDialogSource, /t\('trading\.orderConfirmRisk'\)/)
    assert.match(confirmDialogSource, /onSkipConfirmChange/)
    assert.match(tradePanelSource, /skipConfirm/)
    assert.match(tradePanelSource, /setSkipConfirm/)
    assert.match(tradePanelSource, /confirmed:\s*skipConfirm/)
  })

  it('keeps a prepared confirmation submittable across transient market refreshes', () => {
    assert.match(submitHookSource, /const hasPreparedConfirmation = options\.confirmed === true && options\.payload !== undefined/)
    assert.match(submitHookSource, /if \(!canTrade && !hasPreparedConfirmation\)/)
    assert.match(submitHookSource, /if \(!validation\.canSubmit\)/)
    assert.match(submitHookSource, /if \(!backendReady \|\| !accountId \|\| !onSubmitOrder\)/)
  })

  it('portals the critical order confirmation outside the inert Mobile order sheet', () => {
    assert.match(confirmDialogSource, /import \{ createPortal \} from 'react-dom'/)
    assert.match(confirmDialogSource, /return createPortal\([\s\S]*document\.body\)/)
    assert.match(styles, /\.trade-panel,\s*\.trade-panel__confirm-layer\s*\{[\s\S]*?--tp-warning:/)
  })

  it('renders market orders with a disabled market price field and slippage controls, not TP\\/SL', () => {
    assert.match(orderSideSource, /form\.orderType === 'limit' && form\.strategyType !== 'trigger'/)
    assert.match(orderSideSource, /<StaticOrderField/)
    assert.match(orderSideSource, /<SlippageTolerance/)
    assert.doesNotMatch(orderSideSource, /trade-panel__side--\$\{form\.orderType\}/)
    assert.match(orderSideSource, /form\.orderType === 'market' \?/)
    assert.doesNotMatch(orderSideSource, /form\.orderType === 'market' \?[\s\S]*<TpSlPanel/)
  })

  it('exposes the controlled quantity unit on each scoped order form', () => {
    assert.match(orderSideSource, /data-quantity-unit=\{form\.quantityUnit\}/)
  })

  it('accepts clicked quote prices as limit price prefill signals', () => {
    assert.match(tradePanelSource, /pricePrefill/)
    assert.match(tradePanelSource, /fillLimitPrice/)
    assert.match(tradePanelSource, /key: 'trading\.priceFilled'/)
  })

  it('does not overwrite a price input that is currently being edited by quote prefill', () => {
    const tradeFormSource = readFileSync(tradeFormPath, 'utf8')

    assert.match(tradeFormSource, /export type LimitPriceFillResult = 'updated' \| 'skipped-focused' \| 'invalid'/)
    assert.match(tradeFormSource, /if \(priceFocused\) return 'skipped-focused'/)
    assert.match(tradePanelSource, /buyForm\.fillLimitPrice\(pricePrefill\.price\)/)
    assert.match(tradePanelSource, /sellForm\.fillLimitPrice\(pricePrefill\.price\)/)
    assert.match(tradePanelSource, /key: 'trading\.editingPrice'/)
  })

  it('uses cohesive compact light surfaces instead of mixed order-form layers', () => {
    const tradePanelBlocks = [...styles.matchAll(/(?:^|\n)\.trade-panel\s*\{([^}]*)\}/gu)]
    const effectiveTradePanelBlock = tradePanelBlocks.at(-1)?.[1] ?? ''

    assert.match(styles, /--tp-bg:\s*var\(--trading-surface-2/)
    assert.match(styles, /--tp-surface-2:\s*var\(--trading-field-bg/)
    assert.match(effectiveTradePanelBlock, /--tp-bg:\s*var\(--theme-surface-contrast\)/)
    assert.match(effectiveTradePanelBlock, /border:\s*0/)
    assert.match(effectiveTradePanelBlock, /background:\s*var\(--tp-bg\)/)
    assert.doesNotMatch(effectiveTradePanelBlock, /var\(--trading-surface\)/)
    assert.doesNotMatch(styles, /#[0-9a-f]{3,8}\b|\b(?:rgb|rgba|hsl|hsla)\s*\(/i)
    assert.match(styles, /\.trade-panel__price-row\s*{[\s\S]*grid-template-columns:\s*minmax\(0,\s*1fr\)\s*46px/)
    assert.match(styles, /\.trade-panel__submit\s*{[\s\S]*border-radius:\s*4px/)
    assert.doesNotMatch(orderSideSource, /trade-panel__order-metrics/)
  })

  it('does not expose mock login as a normal trading path', () => {
    assert.doesNotMatch(tradePanelSource, /mock login/i)
    assert.doesNotMatch(tradePanelSource, /setIsLoggedIn/)
    assert.match(orderSubmitButtonSource, /loginRequired/)
    assert.match(orderSubmitButtonSource, /t\('auth\.loginAccount'\)/)
  })

  it('keeps unavailable backend sessions in loading or error states without engineering preview copy', () => {
    assert.match(submitHookSource, /useState<CoreMessage \| null>\(null\)/)
    assert.doesNotMatch(submitHookSource, /useState\(\(\) => t\('trading\.simulatedMode'\)\)/)
    assert.match(sessionStatusSource, /t\('trading\.sessionConnected'\)/)
    assert.match(tradePanelSource, /sessionMode/)
    assert.doesNotMatch(tradePanelSource, /offline-preview/)
    assert.doesNotMatch(tradePanelSource, /previewReady/)
    assert.match(tradePanelSource, /const canTrade = marketDataReady && backendReady && rulesTradable/)
    assert.match(tradePanelSource, /const rulesTradable = Boolean\(rules\?\.enabled && rules\.tradable && rules\.orderEnabled\)/)
    assert.doesNotMatch(tradePanelSource, /buildOrderPayload/)
    assert.doesNotMatch(tradePanelSource, /submitOrder\(mockPayload\)/)
    assert.doesNotMatch(tradePanelSource, /mockResponse/)
  })

  it('announces backend session readiness in a compact terminal status row', () => {
    assert.match(sessionStatusSource, /trade-panel__session-status/)
    assert.match(sessionStatusSource, /aria-live="polite"/)
    assert.match(styles, /\.trade-panel__session-status\s*{[\s\S]*display:\s*grid/)
  })

  it('keeps backend session errors distinct from offline preview with a retry action', () => {
    assert.match(sessionStatusSource, /sessionMode === 'error'/)
    assert.match(sessionStatusSource, /t\('trading\.sessionDisconnected'\)/)
    assert.match(sessionStatusSource, /t\('trading\.sessionError'/)
    assert.match(sessionStatusSource, /t\('common\.retryConnection'\)/)
    assert.match(sessionStatusSource, /onRetrySession/)
    assert.doesNotMatch(tradePanelSource, /const previewReady = sessionMode === 'offline-preview' \|\| sessionMode === 'error'/)
  })

  it('formats the caught order submission error instead of stale parent error state', () => {
    assert.match(submitHookSource, /createOrderSubmitFailureMessage\(error\)/)
    assert.doesNotMatch(submitHookSource, /orderError \?\? error/)
  })

  it('keeps leverage adjustment above campaign popups', () => {
    assert.match(leverageDialogSource, /import \{ Dialog \} from '@fx-platform\/ui'/)
    assert.match(leverageDialogSource, /<Dialog[\s\S]*priority="critical"/)
    assert.doesNotMatch(leverageDialogSource, /role="dialog"/)
  })

  it('owns trade-panel styles through hashed module classes without cross-module global overrides', () => {
    assert.doesNotMatch(styles, /:global\(\.trade-panel/)
    assert.doesNotMatch(tradingRouteStyles, /:global\(\.trade-panel/)
    assert.doesNotMatch(tradingWorkspaceStyles, /:global\(\.trade-panel/)
    assert.match(styles, /\[data-platform-view=['"]pc['"]\]\s+\.trade-panel/)

    for (const { fileName, source } of styledComponentSources) {
      const styledSource = source.replace(/styles\[(?:'[^']+'|"[^"]+"|`[^`]+`)\]/gu, '')
      assert.match(source, /import styles from '\.\/TradePanel\.module\.css'/, `${fileName} must import the local trade-panel module`)
      assert.doesNotMatch(styledSource, /trade-panel__/, `${fileName} must not emit an unstyled global trade-panel token`)
      assert.doesNotMatch(styledSource, /(?:className|backdropClassName|panelClassName)="trade-panel/, `${fileName} must not emit a styled global trade-panel class`)
      assert.doesNotMatch(styledSource, /className=\{`trade-panel/, `${fileName} must start composed classes from the module map`)
      assert.doesNotMatch(styledSource, /\?\s*'trade-panel__/, `${fileName} must resolve conditional styled classes through the module map`)
    }
  })

  it('formats backend API order failures with code, status, and request id', () => {
    assert.match(submitHookSource, /ApiClientError/)
    assert.match(submitHookSource, /code: error\.code/)
    assert.match(submitHookSource, /status: error\.status/)
    assert.match(submitHookSource, /requestId: error\.requestId/)
    assert.match(submitHookSource, /key: 'trading\.backendOrderFailed'/)
    assert.doesNotMatch(submitHookSource, /react-i18next|TFunction|useTranslation/)
    assert.match(tradePanelSource, /translateCoreMessage\(noticeMessage,\s*t\)/)
    assert.doesNotMatch(submitHookSource, /Request ID锛\?/)
  })

  it('blocks stale market orders and surfaces balance or margin shortfalls', () => {
    assert.match(tradePanelSource, /const marketDataReady = market\.tradable === true/)
    assert.match(tradePanelSource, /marketDataReady && backendReady/)
    assert.match(orderSideSource, /validation\.errors\.includes\('marketStale'\)/)
    assert.match(orderSideSource, /disabledReason=\{marketStaleError\}/)
    assert.match(orderSideSource, /getRequiredMargin/)
    assert.match(orderSideSource, /quoteShortfall/)
    assert.match(orderSideSource, /baseShortfall/)
    assert.match(orderSideSource, /t\('trading\.balanceShortfall'/)
    assert.match(orderSideSource, /t\('trading\.marginRequirement'/)
  })

  it('shows login navigation instead of order submission when authentication is required', () => {
    assert.match(tradePanelSource, /loginRequired/)
    assert.match(tradePanelSource, /onLoginRequired/)
    assert.match(submitHookSource, /if \(loginRequired\)[\s\S]*onLoginRequired\?\.\(\)/)
    assert.match(orderSideSource, /loginRequired=\{loginRequired\}/)
    assert.match(orderSubmitButtonSource, /t\('auth\.loginAccount'\)/)
  })

  it('uses only account-derived balances on the ready trading path', () => {
    assert.match(tradePanelSource, /const balances = externalBalances/)
    assert.doesNotMatch(tradePanelSource, /tradeFormTestFixtures/)
  })

  it('keeps order controls polished with focus rings, press feedback, and dropdown entrance motion', () => {
    assert.match(styles, /\.trade-panel__top-tab:focus-visible,\s*\.trade-panel__order-tab:focus-visible/)
    assert.match(styles, /\.trade-panel__submit:active:not\(:disabled\)/)
    assert.match(styles, /\.trade-panel__strategy-menu\s*{[\s\S]*animation:\s*tradePanelDropdownIn/)
    assert.match(styles, /@keyframes tradePanelDropdownIn/)
    assert.match(styles, /@media\s*\(prefers-reduced-motion:\s*reduce\)\s*{[\s\S]*\.trade-panel__strategy-menu\s*{[\s\S]*animation:\s*none/)
  })
})
