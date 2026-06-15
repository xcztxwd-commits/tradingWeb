import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const leverageControlsPath = join(currentDir, 'TradePanelLeverageControls.tsx')
const accountStripPath = join(currentDir, 'TradePanelAccountStrip.tsx')
const sessionStatusPath = join(currentDir, 'TradePanelSessionStatus.tsx')
const submitHookPath = join(currentDir, '..', 'hooks', 'useTradePanelSubmit.ts')
const tradePanelSource = readFileSync(join(currentDir, 'TradePanel.tsx'), 'utf8')
const tradePanelLines = tradePanelSource.split(/\r?\n/)
const leverageControlsSource = existsSync(leverageControlsPath) ? readFileSync(leverageControlsPath, 'utf8') : ''
const accountStripSource = existsSync(accountStripPath) ? readFileSync(accountStripPath, 'utf8') : ''
const sessionStatusSource = existsSync(sessionStatusPath) ? readFileSync(sessionStatusPath, 'utf8') : ''
const submitHookSource = existsSync(submitHookPath) ? readFileSync(submitHookPath, 'utf8') : ''
const confirmDialogSource = readFileSync(join(currentDir, 'OrderConfirmationDialog.tsx'), 'utf8')
const orderSideSource = readFileSync(join(currentDir, 'OrderFormSide.tsx'), 'utf8')
const orderSubmitButtonSource = readFileSync(join(currentDir, 'OrderSubmitButton.tsx'), 'utf8')
const tpSlPanelSource = readFileSync(join(currentDir, 'TpSlPanel.tsx'), 'utf8')
const styles = readFileSync(join(currentDir, '..', 'styles', 'trade-panel.css'), 'utf8')

const userFacingSources = [
  tradePanelSource,
  leverageControlsSource,
  accountStripSource,
  sessionStatusSource,
  confirmDialogSource,
  orderSideSource,
  orderSubmitButtonSource,
  tpSlPanelSource
]

describe('OKX-style trade panel density', () => {
  it('keeps TradePanel as a compact composition shell with extracted session, leverage, and submit logic', () => {
    assert.equal(existsSync(leverageControlsPath), true)
    assert.equal(existsSync(accountStripPath), true)
    assert.equal(existsSync(sessionStatusPath), true)
    assert.equal(existsSync(submitHookPath), true)
    assert.ok(tradePanelLines.length <= 280, `TradePanel.tsx has ${tradePanelLines.length} lines`)
    assert.match(tradePanelSource, /<TradePanelSessionStatus/)
    assert.match(tradePanelSource, /<TradePanelAccountStrip/)
    assert.match(tradePanelSource, /<TradePanelLeverageToggle/)
    assert.match(tradePanelSource, /<TradePanelLeverageControls/)
    assert.match(tradePanelSource, /<OrderConfirmationDialog/)
    assert.match(tradePanelSource, /useTradePanelSubmit/)
    assert.doesNotMatch(tradePanelSource, /function LeverageCell/)
    assert.doesNotMatch(tradePanelSource, /function formatOrderError/)
  })

  it('uses i18n keys instead of hard-coded Chinese in visible trade panel components', () => {
    for (const source of userFacingSources) {
      assert.doesNotMatch(source, /[\p{Script=Han}]/u)
    }
  })

  it('exposes leverage controls and a leverage adjustment popover', () => {
    assert.match(leverageControlsSource, /trade-panel__leverage-row/)
    assert.match(leverageControlsSource, /trade-panel__leverage-popover/)
    assert.match(leverageControlsSource, /t\('trading\.adjustLeverage'\)/)
    assert.match(leverageControlsSource, /const leverageOptions = \[5, 10, 20, 30, 50, 75, 100\]/)
    assert.match(leverageControlsSource, /\$\{option\}x/)
    assert.match(leverageControlsSource, /Number\.isFinite\(nextValue\)/)
    assert.match(leverageControlsSource, /onUpdate\(nextValue\)/)
  })

  it('keeps the Binance-style dual form shell for wide layouts', () => {
    assert.match(tradePanelSource, /trade-panel__forms--dual/)
    assert.match(styles, /\.trade-panel__forms--dual\s*{[\s\S]*grid-template-columns:\s*minmax\(0,\s*1fr\)\s*minmax\(0,\s*1fr\)/)
    assert.match(styles, /\.trade-panel--compact\s+\.trade-panel__forms--dual\s*{[\s\S]*grid-template-columns:\s*1fr/)
  })

  it('shows advanced TP\\/SL labels and contract sizing inside each side form', () => {
    assert.match(tpSlPanelSource, /t\('trading\.takeProfitStopLoss'\)/)
    assert.match(tpSlPanelSource, /t\('trading\.advanced'\)/)
    assert.match(orderSideSource, /t\('trading\.singleContractValue'/)
    assert.match(orderSideSource, /t\('trading\.contractsUnit'\)/)
  })

  it('adds account mode and fee context above the order forms', () => {
    assert.match(accountStripSource, /trade-panel__account-strip/)
    assert.match(accountStripSource, /t\('trading\.spot'\)/)
    assert.match(accountStripSource, /t\('trading\.feeRate'/)
    assert.match(accountStripSource, /Cash/)
  })

  it('uses the real order callback when a backend trading session is ready', () => {
    assert.match(submitHookSource, /onSubmitOrder/)
    assert.match(submitHookSource, /toOrderPayload\(accountId,\s*form,\s*market,\s*leverage\)/)
    assert.match(submitHookSource, /await onSubmitOrder\(payload\)/)
    assert.match(tradePanelSource, /useTradePanelSubmit\(\{[\s\S]*leverage,/)
    assert.match(tradePanelSource, /backendReady/)
    assert.match(submitHookSource, /\.\.\/services\/orderAdapter/)
  })

  it('shows compact order metrics inside each side form', () => {
    assert.match(orderSideSource, /trade-panel__order-metrics/)
    assert.match(orderSideSource, /t\('trading\.estimatedCost'\)/)
    assert.match(orderSideSource, /t\('trading\.estimatedLiquidationPrice'\)/)
    assert.match(orderSideSource, /formatFiatForcePrice/)
  })

  it('keeps trading limits and precision props available to the order form', () => {
    assert.match(tradePanelSource, /minOrderAmount/)
    assert.match(tradePanelSource, /pricePrecision/)
    assert.match(tradePanelSource, /quantityPrecision/)
    assert.match(orderSideSource, /t\('trading\.availableBalance'\)/)
    assert.match(orderSideSource, /minAmount/)
  })

  it('requires a confirmation dialog before real order submission', () => {
    assert.match(submitHookSource, /onConfirmRequired/)
    assert.match(submitHookSource, /t\('trading\.submitConfirmFirst'\)/)
    assert.match(confirmDialogSource, /role="dialog"/)
    assert.match(confirmDialogSource, /t\('trading\.orderConfirmTitle'\)/)
    assert.match(confirmDialogSource, /t\('trading\.skipConfirm'\)/)
    assert.match(confirmDialogSource, /t\('trading\.crossMarketOrder'\)/)
    assert.match(confirmDialogSource, /t\('trading\.crossLimitOrder'\)/)
    assert.match(confirmDialogSource, /t\('trading\.orderConfirmRisk'\)/)
    assert.match(confirmDialogSource, /onSkipConfirmChange/)
    assert.match(tradePanelSource, /skipConfirm/)
    assert.match(tradePanelSource, /setSkipConfirm/)
    assert.match(tradePanelSource, /confirmed:\s*skipConfirm/)
  })

  it('renders market orders without a price field while keeping OKX quantity and TP/SL structure', () => {
    assert.match(orderSideSource, /form\.orderType === 'limit' \?/)
    assert.match(orderSideSource, /caption=\{t\('trading\.singleContractValue'/)
    assert.match(orderSideSource, /trade-panel__side--\$\{form\.orderType\}/)
    assert.match(tpSlPanelSource, /trade-panel__tpsl-order-row/)
    assert.match(tpSlPanelSource, /trade-panel__tpsl-trigger-note/)
    assert.match(tpSlPanelSource, /t\('trading\.triggerMarketNote'/)
    assert.match(tpSlPanelSource, /t\('trading\.triggerMarketPnlNote'/)
    assert.match(tpSlPanelSource, /t\('trading\.latestPrice'\)/)
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

  it('uses OKX-like compact surfaces instead of oversized form gaps', () => {
    assert.match(styles, /\.trade-panel__account-strip\s*{[\s\S]*display:\s*grid/)
    assert.match(styles, /\.trade-panel__order-metrics\s*{[\s\S]*display:\s*grid/)
    assert.match(styles, /\.trade-panel__forms\s*{[\s\S]*gap:\s*18px/)
  })

  it('does not expose mock login as a normal trading path', () => {
    assert.doesNotMatch(tradePanelSource, /mock login/i)
    assert.doesNotMatch(tradePanelSource, /setIsLoggedIn/)
    assert.match(orderSubmitButtonSource, /loginRequired/)
    assert.match(orderSubmitButtonSource, /t\('auth\.loginAccount'\)/)
  })

  it('keeps unavailable backend sessions in loading or error states without engineering preview copy', () => {
    assert.match(submitHookSource, /t\('trading\.simulatedMode'\)/)
    assert.match(sessionStatusSource, /t\('trading\.sessionConnected'\)/)
    assert.match(tradePanelSource, /sessionMode/)
    assert.doesNotMatch(tradePanelSource, /offline-preview/)
    assert.doesNotMatch(tradePanelSource, /previewReady/)
    assert.match(tradePanelSource, /const canTrade = backendReady/)
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
    assert.match(submitHookSource, /Request ID/)
  })

  it('shows login navigation instead of order submission when authentication is required', () => {
    assert.match(tradePanelSource, /loginRequired/)
    assert.match(tradePanelSource, /onLoginRequired/)
    assert.match(submitHookSource, /if \(loginRequired\)[\s\S]*onLoginRequired\?\.\(\)/)
    assert.match(orderSideSource, /loginRequired=\{loginRequired\}/)
    assert.match(orderSubmitButtonSource, /t\('auth\.loginAccount'\)/)
  })

  it('keeps provided local balances ahead of mock fallback balances', () => {
    assert.match(tradePanelSource, /\{\s*\.\.\.mockBalances,\s*\.\.\.externalBalances\s*\}/)
    assert.doesNotMatch(tradePanelSource, /\{\s*\.\.\.externalBalances,\s*\.\.\.mockBalances\s*\}/)
  })

  it('keeps order controls polished with focus rings, press feedback, and dropdown entrance motion', () => {
    assert.match(styles, /\.trade-panel__top-tab:focus-visible,\s*\.trade-panel__order-tab:focus-visible/)
    assert.match(styles, /\.trade-panel__submit:active:not\(:disabled\)/)
    assert.match(styles, /\.trade-panel__strategy-menu\s*{[\s\S]*animation:\s*tradePanelDropdownIn/)
    assert.match(styles, /@keyframes tradePanelDropdownIn/)
    assert.match(styles, /@media\s*\(prefers-reduced-motion:\s*reduce\)\s*{[\s\S]*\.trade-panel__strategy-menu\s*{[\s\S]*animation:\s*none/)
  })
})
