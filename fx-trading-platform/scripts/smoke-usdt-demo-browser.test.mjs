import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { existsSync, mkdtempSync, readFileSync, readdirSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const scriptsDir = dirname(fileURLToPath(import.meta.url))
const projectRoot = join(scriptsDir, '..')
const packageJson = JSON.parse(readFileSync(join(projectRoot, 'package.json'), 'utf8'))
const scriptPath = join(scriptsDir, 'smoke-usdt-demo-browser.mjs')
const legacyScriptPath = join(scriptsDir, 'smoke-btcusdt-perp-50x.mjs')

function source() {
  assert.equal(existsSync(scriptPath), true, 'USDT demo browser smoke script must exist')
  return readFileSync(scriptPath, 'utf8')
}

describe('real USDT demo browser smoke contract', () => {
  it('publishes the new command and retires the destructive legacy fixture', () => {
    assert.equal(packageJson.scripts['smoke:usdt-demo-browser'], 'node scripts/smoke-usdt-demo-browser.mjs')
    assert.equal(packageJson.scripts['smoke:btcusdt-perp-50x'], 'node scripts/smoke-usdt-demo-browser.mjs')
    assert.equal(
      packageJson.scripts['acceptance:p0-user-trading'],
      'node scripts/smoke-usdt-demo-browser.mjs --suite=p0'
    )
    assert.equal(existsSync(legacyScriptPath), true)

    const legacy = readFileSync(legacyScriptPath, 'utf8')
    assert.doesNotMatch(legacy, /UPDATE\s+market\.symbols/i)
    assert.doesNotMatch(legacy, /INSERT\s+INTO\s+market\.symbols/i)
  })

  it('starts real infrastructure, backend, Web, and Admin services', () => {
    const text = source()

    for (const fragment of [
      'docker compose',
      'infra/docker-compose.yml',
      'spring-boot:run',
      '--workspace apps/web run dev',
      '--workspace apps/admin run dev',
      '/actuator/health'
    ]) {
      assert.match(text, new RegExp(escapeRegExp(fragment)))
    }
    assert.match(text, /MARKET_TEST_CONTROL_ENABLED["']?:?\s*["']?true/i)
    assert.match(text, /'real backend \/actuator\/health', 180000/)
  })

  it('retains enough canonical process output to preserve the first server exception', () => {
    const text = source()
    const boundedProcessLogs = text.match(
      /appendTail\(log\.output, chunk, CANONICAL_PROCESS_LOG_TAIL_BYTES\)/g
    ) ?? []

    assert.match(text, /const CANONICAL_PROCESS_LOG_TAIL_BYTES = 200_000/)
    assert.equal(boundedProcessLogs.length, 6)
  })

  it('uses real HTTP and WebSocket traffic without browser API interception or mocks', () => {
    const text = source()

    for (const forbidden of [
      /Fetch\.enable/,
      /Fetch\.fulfillRequest/,
      /Fetch\.requestPaused/,
      /installApiMocks/,
      /mock(?:Trading|Account|Market)Api/i,
      /page\.route\s*\(/
    ]) {
      assert.doesNotMatch(text, forbidden)
    }
    assert.match(text, /new WebSocket/)
    assert.match(text, new RegExp(escapeRegExp('/api/trading')))
    assert.match(text, new RegExp(escapeRegExp('/api/accounts')))
  })

  it('disables the Chromium sandbox only when the smoke runs as root', () => {
    assert.match(source(), /\.\.\.\(process\.getuid\?\.\(\) === 0 \? \['--no-sandbox'\] : \[\]\)/)
  })

  it('covers public primary, OKX failover, local fallback, and recovery source jumps', () => {
    const text = source()

    for (const mode of ['BINANCE_PUBLIC', 'BINANCE_TO_OKX', 'LOCAL_SIMULATED', 'RECOVERY']) {
      assert.match(text, new RegExp(escapeRegExp(mode)))
    }
    for (const provider of ['binance', 'okx', 'local-spot', 'binance-usdm', 'okx-swap', 'local-perp']) {
      assert.match(text, new RegExp(`['\"]${escapeRegExp(provider)}['\"]`))
    }
    for (const metadata of ['providerCode', 'providerSymbol', 'sourceMode', 'asOf', 'expiresAt', 'stale']) {
      assert.match(text, new RegExp(escapeRegExp(metadata)))
    }
    assert.match(text, /MARKET_SOURCE_CHANGED/)
    assert.match(text, /source jump/i)
    assert.match(text, new RegExp(escapeRegExp('/api/admin/market/data-providers')))
    assert.match(text, new RegExp(escapeRegExp('/provider-bindings/')))
  })

  it('captures Web and Admin desktop/mobile evidence for every mode', () => {
    const text = source()

    for (const viewport of [
      "web-desktop', width: 1440, height: 900",
      "web-mobile', width: 390, height: 844",
      "admin-desktop', width: 1440, height: 900",
      "admin-mobile', width: 390, height: 844"
    ]) {
      assert.match(text, new RegExp(escapeRegExp(viewport)))
    }
    for (const fragment of ['Page.captureScreenshot', 'screenshots', 'logs', '/trading', '/market/status', '/accounts']) {
      assert.match(text, new RegExp(escapeRegExp(fragment)))
    }
  })

  it('covers the complete Spot, transfer, and Perpetual P0 journey', () => {
    const text = source()
    const fragments = [
      'INITIAL_SPOT_USDT',
      'INITIAL_PERP_USDT',
      '50000',
      'CRYPTO_SPOT',
      'LINEAR_PERP',
      'INVERSE_PERP',
      'OPTION',
      'FOREX',
      'MARKET BUY QUOTE',
      'MARKET SELL BASE',
      'LIMIT immediate',
      'LIMIT pending cancel',
      'STOP_MARKET LAST_PRICE',
      '/api/trading/oco',
      'BUY OCO',
      'SELL OCO',
      'SPOT_TO_PERP',
      'PERP_TO_SPOT',
      '/transfers',
      'ONE_WAY',
      'HEDGE',
      'CROSS',
      'ISOLATED',
      'USDT_NOTIONAL',
      'CONTRACTS',
      '/position-mode',
      '/settings',
      '/margin',
      'unsafe reduction',
      'partial close',
      'reduce-only over-close',
      '/protections',
      'TAKE_PROFIT',
      'STOP_LOSS',
      'newest-first',
      '/orders/cancel-all',
      '/positions/close-all',
      'funding positive',
      'funding negative',
      'Isolated liquidation',
      'Cross liquidation',
      'shortfall',
      '/force-cleanup',
      '/demo-reset'
    ]

    for (const fragment of fragments) {
      assert.match(text, new RegExp(escapeRegExp(fragment), 'i'), `missing journey contract: ${fragment}`)
    }
  })

  it('accepts the demo guard error for forbidden non-whitelisted symbols', () => {
    assert.match(
      source(),
      /\['PRODUCT_NOT_ALLOWED', 'SYMBOL_NOT_ALLOWED', 'SYMBOL_NOT_FOUND', 'SYMBOL_NOT_TRADABLE'\]/
    )
  })

  it('asserts REST and PostgreSQL state rather than relying only on visible text', () => {
    const text = source()

    for (const endpoint of [
      '/api/market/symbols',
      '/api/market/quotes/',
      '/api/market/order-book/',
      '/api/market/trades/',
      '/api/market/perpetuals/',
      '/api/accounts',
      '/summary',
      '/wallet-balances',
      '/asset-ledger',
      '/api/trading/orders',
      '/api/trading/trades',
      '/api/trading/positions',
      '/api/trading/funding/settlements'
    ]) {
      assert.match(text, new RegExp(escapeRegExp(endpoint)))
    }
    for (const table of [
      'core.wallet_balances',
      'ledger.asset_ledger_entries',
      'trading.orders',
      'trading.trades',
      'trading.positions',
      'trading.funding_settlements'
    ]) {
      assert.match(text, new RegExp(escapeRegExp(table)))
    }
    assert.match(text, /docker["']?,\s*\[[^\]]*exec[^\]]*psql/s)
  })

  it('keeps deterministic funding and liquidation fixtures isolated and reversible', () => {
    const text = source()

    assert.match(text, /assertNoForeignOpenPositions/)
    assert.match(text, /smokeDatabase/)
    assert.match(text, /waitForRealFundingRate/)
    assert.match(text, /DELETE FROM trading\.funding_rates/)
    assert.doesNotMatch(text, /INSERT INTO trading\.funding_rates/)
    assert.doesNotMatch(text, /UPDATE market\.symbols\s+SET maintenance_margin_rate/is)
    assert.doesNotMatch(text, /installLiquidationRiskFixture/)
    assert.match(text, /CREATE DATABASE/)
    assert.match(text, /DATABASE_URL/)
    assert.match(text, /dropSmokeDatabase/)
    assert.match(text, /UPDATE core\.trading_accounts/)
    assert.match(text, /account-scoped Cross shortfall fixture/i)
    assert.match(text, /sanitizedBackendEnvironment/)
    assert.match(text, /SPRING_DATASOURCE_URL/)
    assert.match(text, /backend connection to dedicated smoke database before any user write/)
    assert.match(text, /BANKRUPTCY_SHORTFALL/)
    assert.doesNotMatch(text, /CROSS_LIQUIDATION_SHORTFALL/)
    assert.match(text, /CROSS_LIQUIDATION_SETTLEMENT/)
    assert.match(text, /trading\.cross_liquidation_charges/)
    assert.match(text, /for \(const positionId of positionIdSet\)/)
  })

  it('retries stale position versions without accepting them as margin safety evidence', () => {
    const text = source()

    assert.match(text, /async function updatePositionMarginWithFreshVersion/)
    assert.match(text, /if \(error\.code !== 'POSITION_VERSION_CONFLICT'\) throw error/)
    assert.match(text, /await updatePositionMarginWithFreshVersion\(isolated\.id, 'ADD', '10'\)/)
    assert.match(text, /await updatePositionMarginWithFreshVersion\(isolated\.id, 'REDUCE', '1'\)/)
    assert.match(text, /async function expectUnsafeMarginReduction/)
    assert.match(text, /for \(let attempt = 0; attempt < 3; attempt \+= 1\)/)
    assert.match(text, /if \(error\.code !== 'POSITION_VERSION_CONFLICT'\) return error/)
    assert.match(text, /unsafe margin reduction safety could not be verified after 3 fresh position versions/)
    assert.match(text, /await expectUnsafeMarginReduction\(isolated\.id\)/)
  })

  it('binds the isolated canonical admin to the exact funding configuration authority', () => {
    const text = source()

    assert.match(text, /function grantCanonicalAdminAuthority/)
    for (const table of ['admin.roles', 'admin.menus', 'admin.role_menu_permissions', 'admin.user_roles']) {
      assert.match(text, new RegExp(escapeRegExp(table)))
    }
    assert.match(text, /'canonical admin bootstrap user', 10000\)\s+grantCanonicalAdminAuthority/)
    assert.match(text, /grantCanonicalAdminAuthority\('market:symbol:update'\)\s+adminToken = await login/)
    assert.match(text, /adminAuthorities\.includes\('market:symbol:update'\)/)
    assert.match(text, /SELECT count\(\*\) FROM user_role_upsert/)
    assert.doesNotMatch(text, /grantCanonicalAdminAuthority\(['"]\*['"]\)/)
  })

  it('cannot report PASS until provider state is restored and browser targets are closed', () => {
    const text = source()

    assert.match(text, /return await captureWebCriticalPaths/)
    assert.match(text, /return await captureAdminCriticalPaths/)
    assert.match(text, /json\/close/)
    assert.match(text, /await terminateProcessTree/)
    assert.match(text, /waitForProcessExit/)
    assert.match(text, /\['SIGINT', 'SIGTERM'\]/)
    assert.match(text, /throwIfInterrupted/)
    assert.match(text, /verifyProviderBindingsRestored/)
    assert.ok(
      text.indexOf('restoreProviderBindings') < text.indexOf("writeReport('PASS')"),
      'provider restore must happen before the PASS report is written'
    )
  })

  it('proves recovery notices are on-screen and ties liquidation evidence to liquidation orders', () => {
    const text = source()

    for (const fragment of [
      'getComputedStyle',
      'data-previous-provider',
      'data-current-provider',
      'data-previous-source',
      'data-current-source',
      'getBoundingClientRect',
      'assertRecoveryUnavailableFromProviderHealth',
      "String(provider.healthStatus).toUpperCase() === 'DOWN'",
      'lastSuccessAtMs <= lastFailureAtMs',
      'real STOMP subscription',
      'liquidationOrderIds',
      'parentPositionId'
    ]) {
      assert.match(text, new RegExp(escapeRegExp(fragment)))
    }
  })

  it('checks both quote and base-asset OCO holds and a contiguous newest-first protection suffix', () => {
    const text = source()

    assert.match(text, /OCO peer cancellation must release shared USDT hold/)
    assert.match(text, /OCO peer cancellation must release shared BTC hold/)
    assert.match(text, /changedFlags/)
    assert.match(text, /slice\(firstChanged\)/)
    assert.match(text, /expectedReduction/)
    assert.match(text, /remainingProtectionCapacity/)
    assert.match(text, /triggeredCommitment/)
  })

  it('repeats real UI and state-linked Spot/Perpetual loops in the three executable source modes', () => {
    const text = source()

    assert.match(text, /for\s*\([^)]*SOURCE_MODES/)
    assert.match(text, /mode\.id !== 'RECOVERY'/)
    assert.match(text, /runMinimalSpotLoop/)
    assert.match(text, /runMinimalPerpetualLoop/)
    assert.match(text, /runBrowserMinimalTradingLoop/)
    assert.match(text, /submitBrowserOrder/)
    assert.match(text, /cancelBrowserOrder/)
    assert.match(text, /assertTradeUsesMode/)
    assert.match(text, /MARKET.*LIMIT.*cancel/is)
    assert.match(text, /MARKET.*STOP_MARKET.*close/is)
  })

  it('keeps browser orders valid and proves the mobile order sheet is actually visible', () => {
    const text = source()

    assert.match(text, /LIMIT pending cancel[\s\S]*?quantity: '0\.001'/)
    assert.match(text, /values: \[aligned\(spotLast \* 0\.5, spotTick, 'floor'\), '0\.001'\]/)
    assert.match(text, /closest\('\[aria-hidden\]'\)/)
    assert.match(text, /getAttribute\('aria-hidden'\) === 'false'/)
    assert.match(text, /mobile real order sheet[\s\S]*getBoundingClientRect|getBoundingClientRect[\s\S]*mobile real order sheet/)
  })

  it('refreshes LAST_PRICE for each backend OCO matrix and keeps exact ledger, funding, and batch invariants', () => {
    const text = source()

    assert.match(text, /const buyOcoQuote = await api\(`\/api\/market\/quotes\/\$\{SPOT_SYMBOL\}`\)[\s\S]*const buyOcoLast = number\(buyOcoQuote\.mid \?\? buyOcoQuote\.last \?\? buyOcoQuote\.ask\)[\s\S]*BUY OCO[\s\S]*limitPrice: aligned\(buyOcoLast \* 0\.9998[\s\S]*stopTriggerPrice: aligned\(buyOcoLast \* 1\.0002/)
    assert.match(text, /const sellOcoQuote = await api\(`\/api\/market\/quotes\/\$\{SPOT_SYMBOL\}`\)[\s\S]*const sellOcoLast = number\(sellOcoQuote\.mid \?\? sellOcoQuote\.last \?\? sellOcoQuote\.ask\)[\s\S]*SELL OCO[\s\S]*limitPrice: aligned\(sellOcoLast \* 1\.0002[\s\S]*stopTriggerPrice: aligned\(sellOcoLast \* 0\.9998/)
    assert.match(text, /for \(const transfer of \[spotToPerp, perpToSpot\]\)/)
    assert.match(text, /pending Perp order must reduce free margin/)
    assert.match(text, /Isolated margin after \+10/)
    assert.match(text, /Isolated margin after -1/)
    assert.match(text, /assertBatchItemsSucceeded/)
    assert.match(text, /waitForDirectionalPerpMark/)
  })

  it('binds scheduler funding to fresh provider rows and exactly one target-position settlement', () => {
    const text = source()

    assert.match(text, /\['binance-usdm', 'okx-swap'\]/)
    assert.match(text, /created_at >= to_timestamp/)
    assert.match(text, /actualSource === expectedActualSource/)
    assert.match(text, /sourceMode === 'PUBLIC_EXTERNAL'/)
    assert.match(text, /candidate\.positionId === position\.id/)
    assert.match(text, /candidate\.symbol === position\.symbol/)
    assert.match(text, /matchingDelta\.length === 1/)
    assert.match(text, /exact account balance cashflow/)
    assert.match(text, /next_funding_time/)
    assert.match(text, /minimumNextFundingLeadMs/)
    assert.match(text, /isolatePreparedFundingRate/)
    assert.match(text, /fixedFundingIntervalMinutes: 525600/)
    assert.match(text, /DELETE FROM trading\.funding_rates[\s\S]*id <>/)
    assert.ok(
      text.indexOf('const selectedFunding = await settleRealFundingRateForLong')
        < text.indexOf('for (let index = 0; index < 10; index += 1)'),
      'selected funding must settle immediately after opening, before the protection workflow can cross another due cycle'
    )
    assert.ok(
      text.indexOf('const fallbackRate = await waitForRealFundingRate')
        < text.indexOf("await createOrder('funding fallback LONG position'"),
      'fallback rate ingestion must complete while no fallback position can settle early'
    )
  })

  it('fails fast on interrupted network/CDP work and accepts evidenced offline recovery fallback', () => {
    const text = source()

    assert.match(text, /const shutdownController = new AbortController\(\)/)
    assert.match(text, /shutdownController\.abort/)
    assert.match(text, /AbortSignal\.timeout/)
    assert.match(text, /rejectPending/)
    assert.match(text, /CDP command .* timed out/)
    assert.doesNotMatch(text, /throw new Error\('RECOVERY requires a visible LOCAL_SIMULATED to PUBLIC_EXTERNAL source jump/)
    assert.match(text, /return \{ unavailable: true/)
  })

  it('observes account and Admin source truth in real browser sessions', () => {
    const text = source()

    assert.match(text, /startAccountEventObserver/)
    assert.match(text, /\/user\/queue\/trading-events/)
    assert.match(text, /Network\.webSocketFrameReceived/)
    assert.match(text, /authenticated WebSocket LIQUIDATION notifications/)
    assert.match(text, /assertBrowserObservedJourneyEvents/)
    assert.match(text, /assertAdminBindingMode/)
    assert.match(text, /\/products\/symbol-bindings/)
    assert.match(text, /assertHigherPriorityProvidersUnavailable/)
    assert.match(text, /quote\.providerCode/)
    assert.match(text, /quote\.sourceMode/)
    assert.match(text, /sourceChangedBetweenQuoteAndTrade/)
    assert.match(text, /await assertHigherPriorityProvidersUnavailable\(\s*mode,\s*product,\s*trade/)
    assert.match(
      text,
      /if \(trade\.providerCode === quote\.providerCode && trade\.sourceMode === quote\.sourceMode\) return/,
    )

    const restPerpLoop = text.slice(
      text.indexOf('async function runMinimalPerpetualLoop'),
      text.indexOf('async function assertTradeUsesMode'),
    )
    const restMarketEvidence = restPerpLoop.indexOf("await assertOrderTradeUsesMode(market, mode, 'perp', quote")
    assert.notEqual(restMarketEvidence, -1, 'REST Perpetual MARKET must capture source evidence')
    assert.ok(
      restMarketEvidence < restPerpLoop.indexOf('const stopQuoteStartedAt'),
      'REST Perpetual MARKET source evidence must be captured before resolving the STOP quote',
    )

    const browserLoop = text.slice(
      text.indexOf('async function runBrowserMinimalTradingLoop'),
      text.indexOf('async function openBrowserTradeRoute'),
    )
    const browserMarketEvidence = browserLoop.indexOf(
      "await assertOrderTradeUsesMode(perpMarket, mode, 'perp', perpExecutionQuote",
    )
    assert.notEqual(browserMarketEvidence, -1, 'browser Perpetual MARKET must capture source evidence')
    assert.ok(
      browserMarketEvidence < browserLoop.indexOf('const perpStopStartedAt'),
      'browser Perpetual MARKET source evidence must be captured before resolving the STOP quote',
    )
  })

  it('canonical smoke module import is quiet and side-effect free', (t) => {
    const root = mkdtempSync(join(tmpdir(), 'p0-smoke-import-safe-'))
    t.after(() => rmSync(root, { recursive: true, force: true }))
    const artifacts = join(root, 'artifacts')
    const emptyPath = join(root, 'empty-path')
    const environment = Object.fromEntries(
      Object.entries(process.env).filter(([key]) => key.toUpperCase() !== 'PATH')
    )
    environment.PATH = emptyPath
    environment.USDT_DEMO_SMOKE_ARTIFACTS = artifacts
    const moduleUrl = new URL('./smoke-usdt-demo-browser.mjs', import.meta.url).href

    const execution = spawnSync(process.execPath, [
      '--input-type=module',
      '--eval',
      `await import(${JSON.stringify(moduleUrl)})`
    ], {
      encoding: 'utf8',
      env: environment,
      timeout: 10_000
    })

    assert.equal(execution.status, 0, execution.stderr)
    assert.equal(execution.stdout, '')
    assert.equal(execution.stderr, '')
    assert.deepEqual(readdirSync(root), [])
  })
})

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}
