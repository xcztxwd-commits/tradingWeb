import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const sessionStatusPath = join(currentDir, 'TradePanelSessionStatus.tsx')
const submitHookPath = join(currentDir, '..', 'hooks', 'useTradePanelSubmit.ts')
const tradePanelSource = readFileSync(join(currentDir, 'TradePanel.tsx'), 'utf8')
const tradePanelLines = tradePanelSource.split(/\r?\n/)
const sessionStatusSource = existsSync(sessionStatusPath) ? readFileSync(sessionStatusPath, 'utf8') : ''
const submitHookSource = existsSync(submitHookPath) ? readFileSync(submitHookPath, 'utf8') : ''
const confirmDialogSource = readFileSync(join(currentDir, 'OrderConfirmationDialog.tsx'), 'utf8')
const orderSideSource = readFileSync(join(currentDir, 'OrderFormSide.tsx'), 'utf8')
const orderSubmitButtonSource = readFileSync(join(currentDir, 'OrderSubmitButton.tsx'), 'utf8')
const orderTypeTabsSource = readFileSync(join(currentDir, 'OrderTypeTabs.tsx'), 'utf8')
const tradeTabsSource = readFileSync(join(currentDir, 'TradeTabs.tsx'), 'utf8')
const tpSlPanelSource = readFileSync(join(currentDir, 'TpSlPanel.tsx'), 'utf8')
const styles = readFileSync(join(currentDir, '..', 'styles', 'trade-panel.css'), 'utf8')

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
    assert.ok(tradePanelLines.length <= 320, `TradePanel.tsx has ${tradePanelLines.length} lines`)
    assert.match(tradePanelSource, /<OrderConfirmationDialog/)
    assert.match(tradePanelSource, /useTradePanelSubmit/)
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
    assert.match(tradePanelSource, /useTradePanelSubmit\(\{[\s\S]*adapterSettings:/)
    assert.match(tradePanelSource, /resolveTradePanelLeverage\(resolveRuleLeverage\(adapterSettings\.leverage \?\? symbolLeverage,\s*rules\)\)/)
    assert.match(tradePanelSource, /createPanelMarket\(symbol,\s*snapshot,\s*\{ category,\s*leverage,\s*productType,\s*rules \}\)/)
    assert.doesNotMatch(tradePanelSource, /const leverage = 1/)
    assert.match(tradePanelSource, /backendReady/)
    assert.match(submitHookSource, /\.\.\/services\/orderAdapter/)
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
    assert.match(submitHookSource, /t\('trading\.submitConfirmFirst'\)/)
    assert.match(confirmDialogSource, /role="dialog"/)
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

  it('renders market orders with a disabled market price field and slippage controls, not TP\\/SL', () => {
    assert.match(orderSideSource, /form\.orderType === 'limit' && form\.strategyType !== 'trigger'/)
    assert.match(orderSideSource, /<StaticOrderField/)
    assert.match(orderSideSource, /<SlippageTolerance/)
    assert.match(orderSideSource, /trade-panel__side--\$\{form\.orderType\}/)
    assert.match(orderSideSource, /form\.orderType === 'market' \?/)
    assert.doesNotMatch(orderSideSource, /form\.orderType === 'market' \?[\s\S]*<TpSlPanel/)
  })

  it('accepts clicked quote prices as limit price prefill signals', () => {
    assert.match(tradePanelSource, /pricePrefill/)
    assert.match(tradePanelSource, /fillLimitPrice/)
    assert.match(tradePanelSource, /t\('trading\.priceFilled'/)
  })

  it('does not overwrite a price input that is currently being edited by quote prefill', () => {
    const tradeFormSource = readFileSync(join(currentDir, '..', 'hooks', 'useTradeForm.ts'), 'utf8')

    assert.match(tradeFormSource, /export type LimitPriceFillResult = 'updated' \| 'skipped-focused' \| 'invalid'/)
    assert.match(tradeFormSource, /if \(priceFocused\) return 'skipped-focused'/)
    assert.match(tradePanelSource, /buyForm\.fillLimitPrice\(pricePrefill\.price\)/)
    assert.match(tradePanelSource, /sellForm\.fillLimitPrice\(pricePrefill\.price\)/)
    assert.match(tradePanelSource, /t\('trading\.editingPrice'\)/)
  })

  it('uses cohesive compact light surfaces instead of mixed order-form layers', () => {
    assert.match(styles, /--tp-bg:\s*#ffffff/)
    assert.match(styles, /--tp-surface-2:\s*#f5f7fa/)
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
    assert.match(submitHookSource, /useState\(''\)/)
    assert.doesNotMatch(submitHookSource, /useState\(\(\) => t\('trading\.simulatedMode'\)\)/)
    assert.match(sessionStatusSource, /t\('trading\.sessionConnected'\)/)
    assert.match(tradePanelSource, /sessionMode/)
    assert.doesNotMatch(tradePanelSource, /offline-preview/)
    assert.doesNotMatch(tradePanelSource, /previewReady/)
    assert.match(tradePanelSource, /const canTrade = backendReady && rulesTradable/)
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
    assert.match(submitHookSource, /formatOrderError\(error/)
    assert.doesNotMatch(submitHookSource, /formatOrderError\(orderError \?\? error\)/)
  })

  it('formats backend API order failures with code, status, and request id', () => {
    assert.match(submitHookSource, /ApiClientError/)
    assert.match(submitHookSource, /t\('errors\.apiCode'/)
    assert.match(submitHookSource, /t\('errors\.httpStatus'/)
    assert.match(submitHookSource, /t\('errors\.requestId'/)
    assert.doesNotMatch(submitHookSource, /Request ID锛\?/)
  })

  it('blocks stale market orders and surfaces balance or margin shortfalls', () => {
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
    assert.doesNotMatch(tradePanelSource, /useMockBalances|mockBalances/)
  })

  it('keeps order controls polished with focus rings, press feedback, and dropdown entrance motion', () => {
    assert.match(styles, /\.trade-panel__top-tab:focus-visible,\s*\.trade-panel__order-tab:focus-visible/)
    assert.match(styles, /\.trade-panel__submit:active:not\(:disabled\)/)
    assert.match(styles, /\.trade-panel__strategy-menu\s*{[\s\S]*animation:\s*tradePanelDropdownIn/)
    assert.match(styles, /@keyframes tradePanelDropdownIn/)
    assert.match(styles, /@media\s*\(prefers-reduced-motion:\s*reduce\)\s*{[\s\S]*\.trade-panel__strategy-menu\s*{[\s\S]*animation:\s*none/)
  })
})
