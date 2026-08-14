import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import test from 'node:test'

import * as coreContracts from './p0-user-trading-core-cases.mjs'

const source = readFileSync(
  fileURLToPath(new URL('./p0-user-trading-core-cases.mjs', import.meta.url)),
  'utf8'
)

function coreCaseContext(options = {}) {
  let persisted
  let closed = 0
  const captures = []
  const wallet = {
    walletType: 'PERPETUAL',
    asset: 'USDT',
    total: '100',
    available: '100',
    locked: '0'
  }
  const openPosition = options.openPosition
    ? {
        id: '00000000-0000-4000-8000-000000000002',
        symbol: 'BTCUSDT-PERP',
        status: 'OPEN',
        marginMode: 'CROSS',
        marginHeld: '1'
      }
    : null
  const snapshot = {
    account: { id: '00000000-0000-4000-8000-000000000001', accountType: 'DEMO', status: 'ACTIVE' },
    wallets: [wallet],
    positions: openPosition ? [openPosition] : [],
    orders: [],
    trades: [],
    summary: { balance: '100', equity: '100', usedMargin: '0', freeMargin: '100' },
    settings: {},
    fundingSettlements: []
  }
  const database = {
    database: 'p0_blocked_test',
    openPositions: openPosition ? 1 : 0,
    orderRows: [],
    positionRows: openPosition
      ? [{ id: openPosition.id, status: 'OPEN', margin_held: '1' }]
      : [],
    walletRows: [{ ...wallet }],
    accountRow: {
      balance: '100',
      equity: '100',
      used_margin: '0',
      free_margin: '100'
    }
  }
  return {
    context: {
      run: { commit: 'blocked-test', artifactRoot: 'C:\\p0-test' },
      userFactory: () => ({ email: 'blocked@example.test', password: 'Password123!' }),
      ui: {
        async launchBrowser() {
          return { async close() { closed += 1 } }
        },
        async createEvidencePage() {
          return {
            assertEvidenceClean() {},
            async close() {
              if (options.pageCloseError) throw new Error('test page close failed')
              closed += 1
            }
          }
        },
        async registerViaUi() { return { requestRef: 'register-request' } }
      },
      api: { async snapshotAccount() { return structuredClone(snapshot) } },
      db: {
        async assertDedicatedDatabase() { return 'p0_blocked_test' },
        async snapshotTradingRows() { return structuredClone(database) }
      },
      evidence: {
        async captureCheckpoint(_context, name, scope) {
          captures.push({ name, pages: scope.pages.length })
          return {
            name,
            uiEvidence: [],
            networkEvidence: [],
            eventEvidence: [],
            artifactHashes: {}
          }
        },
        writeCaseResultAtomic(_path, result) { persisted = result }
      }
    },
    persisted: () => persisted,
    closed: () => closed,
    captures: () => captures
  }
}

function section(start, end) {
  const from = source.indexOf(start)
  const to = source.indexOf(end, from + start.length)
  assert.notEqual(from, -1, start)
  assert.notEqual(to, -1, end)
  return source.slice(from, to)
}

test('Spot wallet delta treats a missing pre-mutation asset wallet as zero', () => {
  assert.equal(typeof coreContracts.findPreMutationWalletOrZero, 'function')
  assert.deepEqual(
    coreContracts.findPreMutationWalletOrZero({ wallets: [] }, 'SPOT', 'BTC'),
    {
      walletType: 'SPOT',
      asset: 'BTC',
      total: '0',
      available: '0',
      locked: '0'
    }
  )

  const existing = {
    walletType: 'SPOT',
    asset: 'BTC',
    total: '1',
    available: '0.75',
    locked: '0.25'
  }
  assert.equal(
    coreContracts.findPreMutationWalletOrZero({ wallets: [existing] }, 'SPOT', 'BTC'),
    existing
  )
})

test('core cases persist only evidenced public-provider outages as BLOCKED', async () => {
  assert.equal(typeof coreContracts.P0PublicProviderUnavailableError, 'function')
  assert.throws(
    () => new coreContracts.P0PublicProviderUnavailableError('binance-usdm', [
      { status: 503, code: 'MARKET_DATA_UNAVAILABLE' }
    ]),
    /concrete failure evidence/
  )
  assert.throws(
    () => new coreContracts.P0PublicProviderUnavailableError('binance-usdm', [{
      provider: 'binance-usdm',
      status: 503,
      code: 'SOMETHING_WENT_WRONG',
      asOf: null
    }]),
    /concrete failure evidence/
  )
  assert.throws(
    () => new coreContracts.P0PublicProviderUnavailableError('binance-usdm', [{
      provider: 'binance-usdm',
      status: 'TIMEOUT',
      code: 'FUNDING_INGESTION_TIMEOUT',
      asOf: null,
      configuredAt: '2026-08-14T00:00:00.000Z',
      timeoutMs: 45000,
      binding: [{ providerCode: 'binance-usdm', enabled: true }]
    }]),
    /concrete failure evidence/
  )
  assert.doesNotThrow(
    () => new coreContracts.P0PublicProviderUnavailableError(
      ['binance-usdm', 'okx-swap'],
      [{
        provider: 'binance-usdm',
        status: 'TIMEOUT',
        code: 'FUNDING_INGESTION_TIMEOUT',
        asOf: null,
        configuredAt: '2026-08-14T00:00:00.000Z',
        timeoutMs: 45000,
        binding: [{ providerCode: 'binance-usdm', enabled: true }],
        fixedFallback: {
          status: 'PASS',
          provider: 'fixed',
          sourceMode: 'LOCAL_SIMULATED',
          settlementId: '00000000-0000-4000-8000-000000000003'
        }
      }, {
        provider: 'okx-swap',
        status: 'TIMEOUT',
        code: 'FUNDING_INGESTION_TIMEOUT',
        asOf: null,
        configuredAt: '2026-08-14T00:00:01.000Z',
        timeoutMs: 45000,
        binding: [{ providerCode: 'okx-swap', enabled: true }],
        fixedFallback: {
          status: 'PASS',
          provider: 'fixed',
          sourceMode: 'LOCAL_SIMULATED',
          settlementId: '00000000-0000-4000-8000-000000000003'
        }
      }]
    )
  )
  assert.doesNotThrow(
    () => new coreContracts.P0PublicProviderUnavailableError('binance-usdm', [{
      provider: 'binance-usdm',
      status: 200,
      code: 'FALLBACK_SELECTED',
      asOf: '2026-08-14T00:00:00.000Z'
    }])
  )
  const definition = {
    id: 'SOURCE-01',
    requiredSubruns: [{ id: 'desktop-binance', profile: 'UI_CORE', viewport: 'desktop' }]
  }
  const blocked = coreCaseContext()
  const result = await coreContracts.runSingleUserCoreCase(
    blocked.context,
    definition,
    {},
    async () => {
      throw new coreContracts.P0PublicProviderUnavailableError('binance-usdm', [
        {
          provider: 'binance-usdm',
          status: 503,
          code: 'MARKET_DATA_UNAVAILABLE',
          asOf: null
        }
      ])
    }
  )
  assert.equal(result.status, 'BLOCKED')
  assert.equal(result.cleanup.status, 'PASS')
  assert.equal(result.failureOrBlocker.reasonCode, 'PUBLIC_PROVIDER_UNAVAILABLE')
  assert.equal(result.subruns[0].status, 'BLOCKED')
  assert.equal(result.subruns[0].failureOrBlocker.reasonCode, 'PUBLIC_PROVIDER_UNAVAILABLE')
  assert.deepEqual(result.contractProbes, [{
    kind: 'PUBLIC_PROVIDER_UNAVAILABLE',
    provider: 'binance-usdm',
    failures: [{
      provider: 'binance-usdm',
      status: 503,
      code: 'MARKET_DATA_UNAVAILABLE',
      asOf: null
    }]
  }])
  assert.equal(blocked.persisted().status, 'BLOCKED')
  assert.equal(blocked.closed(), 2)
  assert.equal(result.checkpoints.at(-1).name, 'blocked-final')
  assert.equal(blocked.captures().at(-1).name, 'blocked-final')

  const failed = coreCaseContext()
  await assert.rejects(
    coreContracts.runSingleUserCoreCase(
      failed.context,
      definition,
      {},
      async () => { throw new Error('ordinary assertion failed') }
    ),
    /ordinary assertion failed/
  )
  assert.equal(failed.persisted().status, 'FAIL')

  const dirty = coreCaseContext({ openPosition: true })
  await assert.rejects(
    coreContracts.runSingleUserCoreCase(
      dirty.context,
      definition,
      {},
      async () => {
        throw new coreContracts.P0PublicProviderUnavailableError('binance-usdm', [{
          provider: 'binance-usdm',
          status: 'NETWORK_FAILURE',
          code: 'NETWORK_FAILURE',
          asOf: null
        }])
      }
    ),
    /zero open positions/
  )
  assert.equal(dirty.persisted().status, 'FAIL')

  const cleanupFailed = coreCaseContext({ pageCloseError: true })
  await assert.rejects(
    coreContracts.runSingleUserCoreCase(
      cleanupFailed.context,
      definition,
      {},
      async () => {
        throw new coreContracts.P0PublicProviderUnavailableError('binance-usdm', [{
          provider: 'binance-usdm',
          status: 'NETWORK_FAILURE',
          code: 'NETWORK_FAILURE',
          asOf: null
        }])
      }
    ),
    /P0_CORE_CASE_CLEANUP_FAILED/
  )
  assert.equal(cleanupFailed.persisted().status, 'FAIL')
})

test('core case capture accepts an explicit same-browser page list', async () => {
  const state = coreCaseContext()
  await coreContracts.runSingleUserCoreCase(
    state.context,
    {
      id: 'FUND-04',
      requiredSubruns: [{ id: 'desktop-race', profile: 'ORDER_TRIGGER', viewport: 'desktop' }]
    },
    {},
    async (scope) => {
      await scope.capture('two-tabs', null, {}, [scope.page, { sameBrowserPeer: true }])
    }
  )
  assert.equal(state.captures().find(({ name }) => name === 'two-tabs')?.pages, 2)
})

test('SPOT-01/03 bind every fill to fresh pricing, fee, wallet, and position evidence', () => {
  const spot03 = section(
    'async function runSpotMarketableLimitJourney',
    'async function runCatalogRouteJourney'
  )
  assert.match(spot03, /const sellMarket = await context\.api\.snapshotMarket\('BTCUSDT'\)/)
  assert.match(
    spot03,
    /const buyLimitPrice = alignPriceToTick\(buyAsk, rules, 'CEILING'\)/,
    'SPOT-03 BUY limit must align up to stay marketable and tick-valid'
  )
  assert.match(spot03, /price:\s*buyLimitPrice/)
  assert.match(
    spot03,
    /Math\.min\(Number\(buyAsk\), Number\(buyLimitPrice\)\)/,
    'SPOT-03 BUY fill oracle must use ask and the submitted limit'
  )
  assert.match(
    spot03,
    /const sellLimitPrice = alignPriceToTick\(sellBid, rules, 'FLOOR'\)/,
    'SPOT-03 SELL limit must align down to stay marketable and tick-valid'
  )
  assert.match(spot03, /price:\s*sellLimitPrice/)
  assert.match(
    spot03,
    /Math\.max\(Number\(sellBid\), Number\(sellLimitPrice\)\)/,
    'SPOT-03 SELL fill oracle must use bid and the submitted limit'
  )
  assert.match(spot03, /const sellFee =/)
  assert.match(spot03, /assertDecimalClose\(\s*sold\.trade\.fee,\s*sellFee/)
  assert.equal(
    [...spot03.matchAll(/assertSingleFullFillMutation\(/g)].length,
    2
  )

  const spot01 = section(
    'async function runSpotMarketLifecycle',
    'async function runPerpLifecycle'
  )
  assert.match(spot01, /prepareSpotAuthorityMarket\(/)
  assert.equal([...spot01.matchAll(/marketFillOracle\(/g)].length, 3)
  assert.equal([...spot01.matchAll(/spotSellOracle\(/g)].length, 2)
  assert.equal(
    [...spot01.matchAll(/assertSingleFullFillMutation\(/g)].length,
    3
  )
  assert.equal([...spot01.matchAll(/assertSpotWalletDelta\(/g)].length, 3)
  assert.match(spot01, /partialSpotPosition\.openPrice/)
  assert.match(spot01, /finalSpotPosition\.realizedPnl/)
  assert.match(spot01, /finalSpotRow\.fee_cost/)
  assert.match(spot01, /return \{\s*finalSnapshot: fullySold\.snapshot,\s*finalDb:/)
})

test('PERP-01/02 bind dynamic opening fills to persisted execution evidence', () => {
  const lifecycle = section(
    'async function runPerpLifecycle',
    'function assertPerpAccountSummary'
  )
  const opening = lifecycle.slice(
    0,
    lifecycle.indexOf('const openingHold = perpOpeningHoldOracle')
  )

  assert.doesNotMatch(opening, /bid:\s*quoteDecimal\(market, 'bid'\)/)
  assert.doesNotMatch(opening, /ask:\s*quoteDecimal\(market, 'ask'\)/)
  assert.match(opening, /opened\.order\.slippage/)
  assert.match(opening, /opened\.order\.executionPrice/)
  assert.match(opening, /opened\.order\.avgFillPrice/)
  assert.match(
    opening,
    /Number\(opened\.trade\.price\)[\s\S]*?options\.side === 'BUY' \? -openingSlippage : openingSlippage/
  )
  assert.match(opening, /openingPricing\.slippage/)
  assert.match(opening, /position\.openPrice/)
})

test('PERP-12 proves every rejection and cites the exact fresh backend full-fill test', () => {
  const perp12 = section(
    'async function runPerpValidationJourney',
    'async function runCloseAllJourney'
  )
  for (const kind of [
    'VALIDATION_ERROR',
    'QUANTITY_TOO_SMALL',
    'QUANTITY_STEP_MISMATCH',
    'QUANTITY_TOO_LARGE',
    'INSUFFICIENT_MARGIN',
    'INVALID_HEDGE_POSITION_SIDE'
  ]) {
    assert.match(perp12, new RegExp(`kind:\\s*'${kind}'`))
  }
  assert.match(perp12, /positionMode:\s*'HEDGE'/)
  assert.match(perp12, /positionSide:\s*'BOTH'/)
  assert.match(perp12, /backendFullFillContractProof\(/)
  assert.doesNotMatch(perp12, /BACKEND_CONTRACT_ONLY/)

  const proof = section(
    'function backendFullFillContractProof',
    'function assertCoreCleanup'
  )
  assert.match(proof, /FullFillCoordinatorTest/)
  assert.match(proof, /rejectsEveryNonFullAdapterResultBeforeItCanBecomeCanonical/)
  assert.match(proof, /createHash\('sha256'\)/)
  assert.match(proof, /failures="0"/)
  assert.match(proof, /errors="0"/)
})

test('BATCH-02 scopes the disabled-provider failure before reading the partial account state', async () => {
  const closeAll = section(
    'async function runCloseAllJourney',
    'async function runSpotValidationJourney'
  )
  const restore = closeAll.indexOf('await restoreBinding()')
  const accountRead = closeAll.indexOf("'BATCH-02 partial close-all'")
  assert.notEqual(restore, -1)
  assert.notEqual(accountRead, -1)
  assert.ok(restore < accountRead)
  assert.match(closeAll, /failures\[0\]\.errorCode,\s*'MARKET_PROVIDER_BINDING_NOT_FOUND'/)
  assert.match(closeAll, /const bindingFailureCursorStart = scope\.page\.p0Evidence\.cursor/)
  assert.match(closeAll, /const bindingFailureCursorEnd = scope\.page\.p0Evidence\.cursor/)
  assert.match(closeAll, /await allowExpectedBatchBindingErrors\(/)

  const url = 'http://127.0.0.1:18086/api/market/quotes/SOLUSDT-PERP'
  const expected = {
    cursor: 1,
    responseCursor: 3,
    requestId: 'expected-binding-failure',
    method: 'GET',
    url,
    response: { status: 400 },
    loadingFinished: false,
    loadingFailure: null
  }
  const directBindingFailure = {
    ...expected,
    cursor: 3,
    responseCursor: 4,
    requestId: 'direct-binding-failure',
    loadingFinished: true
  }
  const completedBeforeWindow = {
    ...expected,
    cursor: 0,
    responseCursor: 1,
    requestId: 'completed-before-window',
    loadingFinished: true
  }
  const requestAtWindowEnd = {
    ...expected,
    cursor: 4,
    responseCursor: 5,
    requestId: 'request-at-window-end',
    loadingFinished: true
  }
  const wrongMessage = {
    ...expected,
    cursor: 3,
    responseCursor: 4,
    requestId: 'wrong-message',
    loadingFinished: true
  }
  const outsideWindow = {
    ...expected,
    cursor: 5,
    responseCursor: 6,
    requestId: 'outside-window',
    loadingFinished: true
  }
  const missingResponseCursor = {
    ...expected,
    cursor: 3,
    responseCursor: null,
    requestId: 'missing-response-cursor',
    loadingFinished: true
  }
  const allowed = []
  const bodies = {
    'expected-binding-failure': {
      body: Buffer.from(JSON.stringify({
        code: 'MARKET_DATA_UNAVAILABLE',
        message: 'No complete fresh market bundle is available'
      })).toString('base64'),
      base64Encoded: true
    },
    'direct-binding-failure': {
      body: JSON.stringify({ code: 'MARKET_PROVIDER_BINDING_NOT_FOUND' }),
      base64Encoded: false
    },
    'request-at-window-end': {
      body: JSON.stringify({ code: 'MARKET_PROVIDER_BINDING_NOT_FOUND' }),
      base64Encoded: false
    },
    'missing-response-cursor': {
      body: JSON.stringify({ code: 'MARKET_PROVIDER_BINDING_NOT_FOUND' }),
      base64Encoded: false
    },
    'wrong-message': {
      body: JSON.stringify({
        code: 'MARKET_DATA_UNAVAILABLE',
        message: 'Linear Perpetual account snapshot requires a positive authority mark'
      }),
      base64Encoded: false
    }
  }
  const page = {
    p0Evidence: {
      requests: [
        completedBeforeWindow,
        expected,
        directBindingFailure,
        requestAtWindowEnd,
        outsideWindow,
        missingResponseCursor
      ]
    },
    async send(method, { requestId }) {
      assert.equal(method, 'Network.getResponseBody')
      return bodies[requestId]
    },
    allowHttpError(requestId) {
      allowed.push(requestId)
    }
  }
  queueMicrotask(() => { expected.loadingFinished = true })
  await coreContracts.allowExpectedBatchBindingErrors(page, new Set([url]), 2, 4)
  assert.deepEqual(allowed, [
    'expected-binding-failure',
    'direct-binding-failure',
    'request-at-window-end'
  ])

  const wrongMessagePage = {
    ...page,
    p0Evidence: { requests: [wrongMessage] }
  }
  await assert.rejects(
    coreContracts.allowExpectedBatchBindingErrors(
      wrongMessagePage,
      new Set([url]),
      2,
      4
    ),
    /unexpected disabled binding response/
  )
})

test('PERP-04 runs BASE, QUOTE, and CONTRACTS as independent users at one fixed mark', () => {
  const wrapper = section(
    'async function runPerpQuantityUnits',
    'async function runPerpQuantityUnitJourney'
  )
  assert.match(wrapper, /for \(const subrun of definition\.requiredSubruns\)/)
  assert.match(wrapper, /userFactory: \(seed\) => context\.userFactory\(`\$\{seed\}-\$\{subrun\.id\}`\)/)
  assert.match(wrapper, /subrunIdentity: subrun\.id/)
  assert.match(wrapper, /fixedMark/)
  assert.match(wrapper, /mergeIndependentCaseResults\(/)

  const journey = section(
    'async function runPerpQuantityUnitJourney',
    'async function runPerpWeightedEntryJourney'
  )
  for (const token of [
    "'desktop-base': { unit: 'BASE'",
    "'desktop-quote': { unit: 'QUOTE'",
    "'desktop-contracts': { unit: 'CONTRACTS'",
    'perpOpeningHoldOracle',
    'assertPerpLedgerDelta',
    'openingFee',
    'initialMargin'
  ]) {
    assert.match(journey, new RegExp(token.replaceAll(/[.*+?^${}()|[\]\\]/g, '\\$&')))
  }
})

test('PERP-04 checkpoints show the loaded account position count before capture', () => {
  const journey = section(
    'async function runPerpQuantityUnitJourney',
    'async function runPerpWeightedEntryJourney'
  )
  const openedView = journey.indexOf('await showAccountOverviewPositionCount(page, 1)')
  const openedCapture = journey.indexOf('const openedEvidence = await scope.capture(')
  const reopenedTrade = journey.indexOf('await context.ui.openTradePanel(page, {', openedCapture)
  const closePosition = journey.indexOf('await closePositionFromUi(scope, position)')
  const closedView = journey.indexOf('await showAccountOverviewPositionCount(page, 0)')
  const closedCapture = journey.indexOf('const closedEvidence = await scope.capture(')

  assert(openedView >= 0 && openedView < openedCapture)
  assert(reopenedTrade > openedCapture && reopenedTrade < closePosition)
  assert(closedView > closePosition && closedView < closedCapture)
  assert.match(
    journey.slice(reopenedTrade, closePosition),
    /product: 'perpetual',\s*symbol/
  )
  assert.doesNotMatch(
    journey.slice(closedView),
    /page\.navigate\(|context\.ui\.openTradePanel\(/
  )

  const helper = section(
    'async function showAccountOverviewPositionCount',
    'async function applyAuthorityMark'
  )
  assert.match(
    helper,
    /await page\.navigate\(`\$\{page\.p0Options\.webBaseUrl\}\/account\/overview`\)/
  )
  assert.match(helper, /window\.location\.pathname !== '\/account\/overview'/)
  assert.match(helper, /getElementById\('account-page-title'\)/)
  assert.match(helper, /\[aria-label="Account profile summary"\]/)
  assert.match(helper, /\[data-state-variant="loading"\]/)
  assert.match(helper, /Open positions/)
  assert.match(helper, /startsWith\('UID '\)/)
  assert.match(helper, /querySelector\(':scope > strong'\)/)
  assert.match(helper, /String\(expected\)/)
  assert.match(helper, /getBoundingClientRect\(\)/)
  assert.match(
    helper,
    /`account overview open positions \$\{expectedOpenPositions\}`,\s*expectedOpenPositions/
  )
})

test('PERP authority mark waits for the exact controlled snapshot and reuses it', () => {
  assert.equal(typeof coreContracts.isAuthorityMarketSnapshot, 'function')
  const controlled = {
    quote: {
      bid: '64922.1',
      ask: '64922.2',
      mid: '64922.1500000000',
      markPrice: '64922.1500000000',
      stale: false
    },
    reference: { mark: '64922.1500000000', stale: false }
  }
  const wrongMark = {
    quote: {
      bid: '64922.1',
      ask: '64922.2',
      mid: '64922.1500000000',
      markPrice: '64922.1499',
      stale: false
    },
    reference: { mark: '64922.1499', stale: false }
  }
  const previous = {
    quote: {
      bid: '64922.0',
      ask: '64922.3',
      mid: '64922.1500000000',
      markPrice: '64922.1500000000',
      stale: false
    },
    reference: { mark: '64922.1500000000', stale: false }
  }

  assert.equal(coreContracts.isAuthorityMarketSnapshot(previous, '64922.1', '64922.2'), false)
  assert.equal(coreContracts.isAuthorityMarketSnapshot(wrongMark, '64922.1', '64922.2'), false)
  assert.equal(coreContracts.isAuthorityMarketSnapshot(controlled, '64922.1', '64922.2'), true)

  const helper = section(
    'async function applyAuthorityMark',
    'async function waitForMarket'
  )

  assert.match(helper, /isAuthorityMarketSnapshot\(candidate, bid, ask\)/)
  assert.match(helper, /return \{ bid, ask, market \}/)

  const journey = section(
    'async function runPerpQuantityUnitJourney',
    'async function runPerpWeightedEntryJourney'
  )
  assert.match(journey, /const market = authority\.market/)
  assert.doesNotMatch(journey, /const market = await context\.api\.snapshotMarket\(symbol\)/)
})

test('Perp financial helper proves every position risk field and aggregate account summary', () => {
  const helper = section(
    'function assertPerpPositionFinancials',
    'function assertPerpLedgerDelta'
  )
  for (const token of [
    'perpPositionOracle',
    'position.floatingPnl',
    'position.maintenanceMargin',
    'position.initialMargin',
    'assertPerpAccountSummary',
    'usedMargin',
    'maintenanceMargin'
  ]) {
    assert.match(helper, new RegExp(token.replaceAll(/[.*+?^${}()|[\]\\]/g, '\\$&')))
  }
})

test('PERP-03/05-09 close every financial ledger and rejection invariant', () => {
  const sections = {
    perp03: section(
      'async function runPerpLeverageJourney',
      'async function runPerpQuantityUnits'
    ),
    perp05: section(
      'async function runPerpWeightedEntryJourney',
      'async function runPerpReductionJourney'
    ),
    perp06: section(
      'async function runPerpReductionJourney',
      'async function runPerpReversalJourney'
    ),
    perp07: section(
      'async function runPerpReversalJourney',
      'async function runPerpHedgeJourney'
    ),
    perp08: section(
      'async function runPerpHedgeJourney',
      'async function runPerpMarginJourney'
    ),
    perp09: section(
      'async function runPerpMarginJourney',
      'async function runPerpValidationJourney'
    )
  }

  for (const [id, body] of Object.entries(sections)) {
    assert.match(body, /assertPerpPositionFinancials\(/, `${id} position financials`)
    assert.match(body, /assertPerpLedgerDelta\(/, `${id} cash ledger`)
    assert.match(body, /return \{\s*finalSnapshot:[\s\S]*?finalDb:/, `${id} final DB`)
  }

  assert.match(sections.perp03, /perpOpeningHoldOracle\(/)
  assert.match(sections.perp03, /MARGIN_(?:HOLD|RELEASE)/)
  assert.ok(
    [...sections.perp03.matchAll(/assertTradingStateEqual\(/g)].length >= 2,
    'PERP-03 rejection fingerprints'
  )

  assert.ok(
    [...sections.perp05.matchAll(/perpOpeningHoldOracle\(/g)].length >= 2,
    'PERP-05 two opening holds'
  )
  assert.match(sections.perp05, /perpCloseOracle\(/)
  assert.match(sections.perp05, /history\.realizedPnl/)

  assert.match(sections.perp06, /partialCloseOracle\(/)
  assert.match(sections.perp06, /perpCloseOracle\(/)
  assert.match(sections.perp06, /history\.realizedPnl/)

  assert.match(sections.perp07, /reversalFee/)
  assert.match(sections.perp07, /\w+History\.realizedPnl/)
  assert.ok(
    [...sections.perp07.matchAll(/assertTradingStateEqual\(/g)].length >= 3,
    'PERP-07 idempotency and reduce-only rejection fingerprints'
  )

  assert.match(sections.perp08, /partialCloseOracle\(/)
  assert.ok(
    [...sections.perp08.matchAll(/perpCloseOracle\(/g)].length >= 2,
    'PERP-08 independent full-close oracles'
  )
  assert.ok(
    [...sections.perp08.matchAll(/assertTradingStateEqual\(/g)].length >= 2,
    'PERP-08 blocked switch and overclose fingerprints'
  )

  for (const operation of ['MARGIN_HOLD', 'MARGIN_RELEASE']) {
    assert.match(sections.perp09, new RegExp(`operationType:\\s*'${operation}'`))
  }
  assert.match(sections.perp09, /added\.position\.liquidationPrice/)
  assert.match(sections.perp09, /reduced\.position\.liquidationPrice/)
  assert.ok(
    [...sections.perp09.matchAll(/assertTradingStateEqual\(/g)].length >= 4,
    'PERP-09 every rejection is zero mutation'
  )
})
