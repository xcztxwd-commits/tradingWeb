import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { registerHooks } from 'node:module'
import { describe, it } from 'node:test'

import { createLocalPreviewOrder, deriveTradingBalances } from '@fx-platform/frontend-core'
import { repriceOpenPositionsForQuote } from './tradingSessionPositions.ts'
import {
  clearStoredAuthToken,
  readStoredAuthToken,
  readStoredRefreshToken,
  writeStoredAuthToken,
  writeStoredAuthTokens,
  type AccountSummary,
  type OrderPayload,
  type PositionResponse,
  type Quote,
  type WalletBalance
} from '@fx-platform/frontend-core'

registerHooks({
  resolve(specifier, context, nextResolve) {
    if ((specifier.startsWith('./') || specifier.startsWith('../')) && !/\.[a-z]+$/i.test(specifier)) {
      return nextResolve(`${specifier}.ts`, context)
    }
    return nextResolve(specifier, context)
  }
})

describe('trading session models', () => {
  it('keeps auth token storage optional for restricted browser contexts', () => {
    assert.equal(readStoredAuthToken(undefined), null)
    assert.doesNotThrow(() => writeStoredAuthToken('token-1', undefined))
    assert.doesNotThrow(() => clearStoredAuthToken(undefined))
  })

  it('reads and writes the auth token when browser storage is available', () => {
    const { storage, values } = makeAuthTokenStorage()

    writeStoredAuthToken('token-1', storage)

    assert.equal(readStoredAuthToken(storage), 'token-1')
    assert.equal(values.get('fx-platform-auth-token'), 'token-1')
    assert.equal(values.has('fx-platform-demo-token'), false)
    clearStoredAuthToken(storage)
    assert.equal(readStoredAuthToken(storage), null)
  })

  it('stores and clears the refresh token with the auth session', () => {
    const { storage, values } = makeAuthTokenStorage()

    writeStoredAuthTokens('access-1', 'refresh-1', storage)

    assert.equal(readStoredAuthToken(storage), 'access-1')
    assert.equal(readStoredRefreshToken(storage), 'refresh-1')
    assert.equal(values.get('fx-platform-auth-token'), 'access-1')
    assert.equal(values.get('fx-platform-auth-refresh-token'), 'refresh-1')

    clearStoredAuthToken(storage)

    assert.equal(readStoredAuthToken(storage), null)
    assert.equal(readStoredRefreshToken(storage), null)
    assert.equal(values.has('fx-platform-auth-token'), false)
    assert.equal(values.has('fx-platform-auth-refresh-token'), false)
  })

  it('migrates an existing demo token into the formal auth token key', () => {
    const { storage, values } = makeAuthTokenStorage([
      ['fx-platform-demo-token', 'legacy-token']
    ])

    assert.equal(readStoredAuthToken(storage), 'legacy-token')
    assert.equal(values.get('fx-platform-auth-token'), 'legacy-token')
    assert.equal(values.has('fx-platform-demo-token'), false)
  })

  it('prefers the formal auth token when both token keys exist', () => {
    const { storage, values } = makeAuthTokenStorage([
      ['fx-platform-demo-token', 'legacy-token'],
      ['fx-platform-auth-token', 'formal-token']
    ])

    assert.equal(readStoredAuthToken(storage), 'formal-token')
    assert.equal(values.get('fx-platform-auth-token'), 'formal-token')
    assert.equal(values.has('fx-platform-demo-token'), false)
  })

  it('clears both formal and legacy auth token keys', () => {
    const { storage, values } = makeAuthTokenStorage([
      ['fx-platform-demo-token', 'legacy-token'],
      ['fx-platform-auth-token', 'formal-token']
    ])

    clearStoredAuthToken(storage)

    assert.equal(values.has('fx-platform-auth-token'), false)
    assert.equal(values.has('fx-platform-demo-token'), false)
  })

  it('creates a local preview order from a backend order payload', () => {
    const payload: OrderPayload = {
      accountId: 'acct_1',
      symbol: 'BTCUSDT',
      side: 'BUY',
      orderType: 'LIMIT',
      quantity: 0.02,
      price: 60736.3,
      clientOrderId: 'client_123',
      idempotencyKey: 'client_123',
      positionSide: 'BOTH',
      quantityUnit: 'BASE',
      marginMode: 'CASH',
      reduceOnly: false
    }

    assert.deepEqual(createLocalPreviewOrder(payload, '2026-06-06T00:00:00.000Z'), {
      id: 'client_123',
      symbol: 'BTCUSDT',
      side: 'BUY',
      orderType: 'LIMIT',
      status: 'LOCAL_PREVIEW',
      lots: 0.02,
      executionPrice: null,
      createdAt: '2026-06-06T00:00:00.000Z'
    })
  })

  it('uses only Spot wallet balances for the Spot terminal', () => {
    const account: AccountSummary = {
      id: 'acct_1',
      accountType: 'DEMO',
      baseCurrency: 'USD',
      balance: '75000',
      equity: '75000',
      usedMargin: '120',
      freeMargin: '75000',
      marginLevel: null,
      leverage: 100,
      status: 'ACTIVE'
    }
    const positions: PositionResponse[] = [
      makePosition({ id: 'pos_1', symbol: 'BTCUSDT', side: 'BUY', lots: '0.50' })
    ]
    const walletBalances: WalletBalance[] = [
      walletBalance('USDT', '25000.00000000'),
      walletBalance('BTC', '0.09990000'),
      walletBalance('USDT', '75000.00000000', 'USDT_PERP')
    ]

    const balances = deriveTradingBalances('spot', account, positions, 'BTCUSDT', walletBalances)

    assert.equal(balances.USDT, 25000)
    assert.equal(balances.BTC, 0.0999)
    assert.equal(balances.USD, undefined)
  })

  it('uses Perpetual free margin even when Spot wallet balances exist', () => {
    const account: AccountSummary = {
      id: 'acct_1',
      accountType: 'DEMO',
      baseCurrency: 'USD',
      balance: '75000',
      equity: '75000',
      usedMargin: '120',
      freeMargin: '75000',
      marginLevel: null,
      leverage: 100,
      status: 'ACTIVE'
    }
    const positions: PositionResponse[] = [
      makePosition({ id: 'pos_1', symbol: 'BTCUSDT', side: 'BUY', lots: '0.50' })
    ]
    const walletBalances: WalletBalance[] = [
      walletBalance('USDT', '25000.00000000'),
      walletBalance('BTC', '0.09990000'),
      walletBalance('USDT', '75000.00000000', 'USDT_PERP')
    ]

    const balances = deriveTradingBalances('perpetual', account, positions, 'BTCUSDT-PERP', walletBalances)

    assert.equal(balances.USD, 75000)
    assert.equal(balances.USDT, 75000)
    assert.equal(balances.BTC, undefined)
  })

  it('reprices forex open positions from websocket quotes without touching history rows', () => {
    const positions: PositionResponse[] = [
      makePosition({
        id: 'buy-eurusd',
        symbol: 'EURUSD',
        side: 'BUY',
        instrumentType: 'FOREX',
        lots: '0.10',
        openPrice: '1.10020',
        marginHeld: '110.02000000',
        status: 'OPEN'
      }),
      makePosition({
        id: 'sell-eurusd',
        symbol: 'EURUSD',
        side: 'SELL',
        instrumentType: 'FOREX',
        lots: '0.20',
        openPrice: '1.10020',
        marginHeld: '220.04000000',
        status: 'OPEN'
      }),
      makePosition({
        id: 'closed-eurusd',
        symbol: 'EURUSD',
        side: 'BUY',
        instrumentType: 'FOREX',
        lots: '0.10',
        openPrice: '1.10020',
        currentPrice: '1.10020',
        floatingPnl: '0',
        status: 'CLOSED'
      })
    ]

    const next = repriceOpenPositionsForQuote(positions, quote('EURUSD', '1.10120', '1.10140'))

    assert.equal(next[0].currentPrice, '1.10120000')
    assert.equal(next[0].markPrice, '1.10130000')
    assert.equal(next[0].floatingPnl, '10.00000000')
    assert.equal(next[0].floatingPnlRatio, '0.09089256')
    assert.equal(next[1].currentPrice, '1.10140000')
    assert.equal(next[1].floatingPnl, '-24.00000000')
    assert.equal(next[2].currentPrice, '1.10020')
    assert.equal(next[2].floatingPnl, '0')
  })

  it('reprices swap positions using contract quantity instead of forex lot size', () => {
    const positions: PositionResponse[] = [
      makePosition({
        id: 'btc-swap',
        symbol: 'BTCUSDT',
        side: 'BUY',
        instrumentType: 'SWAP',
        lots: '0.50',
        openPrice: '60000',
        marginHeld: '1500',
        status: 'OPEN'
      })
    ]

    const next = repriceOpenPositionsForQuote(positions, quote('BTCUSDT', '60100', '60102'))

    assert.equal(next[0].currentPrice, '60100.00000000')
    assert.equal(next[0].markPrice, '60101.00000000')
    assert.equal(next[0].floatingPnl, '50.00000000')
    assert.equal(next[0].floatingPnlRatio, '0.03333333')
  })
})

describe('trading session submit mode', () => {
  it('gives every batch action a unique request id, calls the API once, and returns its result', async () => {
    const { runTradingBatchAction } = await import('./tradingSession.ts')
    const payloads: Array<{ accountId: string; requestId: string }> = []
    let refreshes = 0
    const action = async (payload: { accountId: string; requestId: string }) => {
      payloads.push(payload)
      return { accountId: payload.accountId, requestId: payload.requestId, items: [] }
    }
    const refresh = async () => { refreshes += 1 }

    const first = await runTradingBatchAction('acct_1', 'token_1', action, refresh)
    const second = await runTradingBatchAction('acct_1', 'token_1', action, refresh)

    assert.equal(payloads.length, 2)
    assert.equal(new Set(payloads.map(({ requestId }) => requestId)).size, 2)
    assert.deepEqual(first, { accountId: 'acct_1', requestId: payloads[0].requestId, items: [] })
    assert.deepEqual(second, { accountId: 'acct_1', requestId: payloads[1].requestId, items: [] })
    assert.equal(refreshes, 2)
  })

  it('refreshes account truth in finally when a batch action fails', async () => {
    const { runTradingBatchAction } = await import('./tradingSession.ts')
    let apiCalls = 0
    let refreshes = 0

    await assert.rejects(
      () => runTradingBatchAction(
        'acct_1',
        'token_1',
        async () => {
          apiCalls += 1
          throw new Error('batch failed')
        },
        async () => { refreshes += 1 }
      ),
      /batch failed/
    )

    assert.equal(apiCalls, 1)
    assert.equal(refreshes, 1)
  })

  it('wires both authenticated batch callbacks through the shared session refresh path', () => {
    const hookSource = readFileSync(new URL('./useTradingSession.ts', import.meta.url), 'utf8')

    assert.match(hookSource, /cancelAllOrders: submitCancelAllOrders/)
    assert.match(hookSource, /closeAllPositions: submitCloseAllPositions/)
    assert.match(hookSource, /runTradingBatchAction\(\s*accountId,\s*token,\s*cancelAllTradingOrders/)
    assert.match(hookSource, /runTradingBatchAction\(\s*accountId,\s*token,\s*closeAllTradingPositions/)
  })

  it('selects the active demo account from an unsorted mixed account list case-insensitively', async () => {
    const { selectActiveDemoAccount } = await import('@fx-platform/frontend-core')
    const liveActive = accountSummary({ id: 'live-active', accountType: 'LIVE', status: 'ACTIVE' })
    const demoDisabled = accountSummary({ id: 'demo-disabled', accountType: 'DEMO', status: 'DISABLED' })
    const demoActive = accountSummary({ id: 'demo-active', accountType: 'dEmO', status: 'aCtIvE' })

    assert.equal(selectActiveDemoAccount([liveActive, demoDisabled, demoActive]), demoActive)
  })

  it('reuses an active demo account without creating another one', async () => {
    const originalFetch = globalThis.fetch
    const requestedPaths: string[] = []
    const demoActive = accountSummary({ id: 'demo-active', accountType: 'DEMO', status: 'ACTIVE' })
    globalThis.fetch = async (input) => {
      requestedPaths.push(String(input))
      return jsonResponse([
        accountSummary({ id: 'live-active', accountType: 'LIVE', status: 'ACTIVE' }),
        accountSummary({ id: 'demo-disabled', accountType: 'DEMO', status: 'DISABLED' }),
        demoActive
      ])
    }

    try {
      const { firstOrCreatedAccount } = await import('./tradingSession.ts')

      assert.deepEqual(await firstOrCreatedAccount('token_1'), demoActive)
      assert.deepEqual(requestedPaths, ['/api/accounts'])
    } finally {
      globalThis.fetch = originalFetch
    }
  })

  it('creates a demo account only when no active demo account exists', async () => {
    const originalFetch = globalThis.fetch
    const requestedPaths: string[] = []
    const createdDemo = accountSummary({ id: 'demo-created', accountType: 'DEMO', status: 'ACTIVE' })
    globalThis.fetch = async (input) => {
      const path = String(input)
      requestedPaths.push(path)
      return jsonResponse(path === '/api/accounts/demo'
        ? createdDemo
        : [
            accountSummary({ id: 'live-active', accountType: 'LIVE', status: 'ACTIVE' }),
            accountSummary({ id: 'demo-disabled', accountType: 'DEMO', status: 'DISABLED' })
          ])
    }

    try {
      const { firstOrCreatedAccount } = await import('./tradingSession.ts')

      assert.deepEqual(await firstOrCreatedAccount('token_1'), createdDemo)
      assert.deepEqual(requestedPaths, ['/api/accounts', '/api/accounts/demo'])
    } finally {
      globalThis.fetch = originalFetch
    }
  })

  it('routes only canonical position mutations and refreshes through the authenticated account session', () => {
    const sessionSource = readFileSync(new URL('./tradingSession.ts', import.meta.url), 'utf8')
    const hookSource = readFileSync(new URL('./useTradingSession.ts', import.meta.url), 'utf8')

    assert.match(sessionSource, /export type PositionMutation =/)
    assert.match(sessionSource, /type: 'PARTIAL_CLOSE'/)
    assert.match(sessionSource, /type: 'FULL_CLOSE'/)
    assert.match(sessionSource, /type: 'ADJUST_MARGIN'/)
    assert.match(sessionSource, /type: 'CREATE_PROTECTIONS'/)
    assert.match(sessionSource, /for \(const payload of payloads\)/)
    assert.match(hookSource, /mutation: PositionMutation = \{ type: 'FULL_CLOSE' \}/)
    assert.match(hookSource, /mutateTradingPosition\(accountId, position\.id, mutation, token\)/)
    assert.match(hookSource, /finally \{[\s\S]*await refreshAccountData\(token, accountId\)\.catch\(\(\) => undefined\)/)
  })

  it('rejects order submission without a backend token', async () => {
    const { submitTradingOrder } = await import('./tradingSession.ts')

    await assert.rejects(
      () => submitTradingOrder({
        accountId: 'acct_1',
        symbol: 'BTCUSDT',
        side: 'BUY',
        orderType: 'MARKET',
        quantity: '0.01',
        clientOrderId: 'client_1',
        idempotencyKey: 'client_1'
      }),
      /Trading session token is required/
    )
  })

  it('treats auth failures as login-required session failures', async () => {
    const { ApiClientError } = await import('@fx-platform/frontend-core')
    const { isAuthSessionFailure } = await import('./tradingSession.ts')

    assert.equal(isAuthSessionFailure(new ApiClientError({
      status: 403,
      code: 'REQUEST_FAILED',
      message: 'Request failed: 403'
    })), true)
    assert.equal(isAuthSessionFailure(new ApiClientError({
      status: 401,
      code: 'REQUEST_FAILED',
      message: 'Request failed: 401'
    })), true)
    assert.equal(isAuthSessionFailure(new ApiClientError({
      status: 400,
      code: 'ACCOUNT_NOT_FOUND',
      message: 'Account not found'
    })), false)
  })

  it('keeps local preview creation out of the live submit flow', () => {
    const source = readFileSync(new URL('./useTradingSession.ts', import.meta.url), 'utf8')
    const sessionSource = readFileSync(new URL('./tradingSession.ts', import.meta.url), 'utf8')

    assert.equal(source.includes('createLocalPreviewOrder'), false)
    assert.equal(sessionSource.includes('createLocalPreviewOrder'), false)
  })

  it('uses an explicit auth session probe instead of auto demo login on trading page boot', async () => {
    const sessionSource = readFileSync(new URL('./tradingSession.ts', import.meta.url), 'utf8')
    const hookSource = readFileSync(new URL('./useTradingSession.ts', import.meta.url), 'utf8')
    const accountSource = readFileSync(
      new URL('../../../../../packages/frontend-core/src/account/accountOperations.ts', import.meta.url),
      'utf8'
    )
    const authApiSource = readFileSync(
      new URL('../../../../../packages/frontend-core/src/api/authApi.ts', import.meta.url),
      'utf8'
    )
    const { getTradingSessionMode } = await import('./useTradingSession.ts')

    assert.equal(getTradingSessionMode({
      sessionReady: false,
      token: null,
      sessionError: null,
      loginRequired: true
    }), 'login-required')
    assert.match(authApiSource, /getSessionStatus/)
    assert.match(authApiSource, /export type SessionAuthStatus = SessionStatus\['status'\]/)
    assert.match(authApiSource, /apiGet<SessionStatus>\('\/api\/auth\/session'/)
    assert.match(hookSource, /useAccountData\(\{ refreshMs \}\)/)
    assert.match(accountSource, /getSessionStatus/)
    assert.match(hookSource, /sessionAuthStatus/)
    assert.match(accountSource, /authStatus: 'valid_token'/)
    assert.match(accountSource, /requireLogin\('guest'\)/)
    assert.doesNotMatch(hookSource, /getCachedDemoSession\(\)/)
    assert.doesNotMatch(hookSource, /refreshCachedDemoSession\(\)/)
    assert.doesNotMatch(sessionSource, /const demoEmail =/)
    assert.doesNotMatch(sessionSource, /signInDemoUser/)
  })

  it('requires login when no stored token or an unauthenticated session probe is returned', () => {
    const source = readFileSync(
      new URL('../../../../../packages/frontend-core/src/account/accountOperations.ts', import.meta.url),
      'utf8'
    )
    const loadBlock = sourceBetween(source, 'const boot = async', 'return {')

    assert.match(loadBlock, /dependencies\.readStoredAuthToken\(\)/)
    assert.match(loadBlock, /getSessionStatus\(savedToken\)/)
    assert.match(loadBlock, /if \(status\.status === 'invalid_token'\)[\s\S]*requireLogin\('invalid_token'\)/)
    assert.match(loadBlock, /if \(!savedToken \|\| status\.status === 'guest' \|\| !status\.authenticated\)[\s\S]*requireLogin\('guest'\)/)
  })

  it('marks boot refresh failures as an error session without clearing the latest account snapshot', async () => {
    const source = readFileSync(
      new URL('../../../../../packages/frontend-core/src/account/accountOperations.ts', import.meta.url),
      'utf8'
    )
    const { getTradingSessionMode } = await import('./useTradingSession.ts')

    assert.equal(getTradingSessionMode({ sessionReady: false, token: null, sessionError: 'boot failed' }), 'error')
    assert.match(source, /const markError = \(error: unknown\)/)
    assert.match(source, /state: \{ status: 'error', error: apiError, message: toAccountErrorMessage\(apiError\) \}/)
    assert.match(source, /data: previousData/)
    assert.doesNotMatch(source, /setAccount\(undefined\)/)
  })

  it('turns coordinated refresh auth failures into login-required instead of offline preview', async () => {
    const source = readFileSync(
      new URL('../../../../../packages/frontend-core/src/account/accountOperations.ts', import.meta.url),
      'utf8'
    )
    const { getTradingSessionMode } = await import('./useTradingSession.ts')

    assert.equal(getTradingSessionMode({ sessionReady: false, token: null, sessionError: null, loginRequired: true }), 'login-required')
    assert.equal(getTradingSessionMode({ sessionReady: false, token: 'token-1', sessionError: 'poll failed' }), 'error')
    assert.match(source, /createAccountRefreshCoordinator\(\{[\s\S]*onError: markError/)
    assert.match(source, /isAuthSessionFailure\(error\)[\s\S]*requireLogin\('invalid_token'\)/)
  })

  it('uses the coordinator 15s visible fallback without a duplicate hook interval', () => {
    const hookSource = readFileSync(
      new URL('../../../../../packages/frontend-core/src/account/useAccountData.ts', import.meta.url),
      'utf8'
    )
    const source = readFileSync(
      new URL('../../../../../packages/frontend-core/src/account/accountOperations.ts', import.meta.url),
      'utf8'
    )

    assert.match(hookSource, /refreshMs = 15_000/)
    assert.match(source, /createAccountRefreshCoordinator\(\{[\s\S]*pollMs: refreshMs/)
    assert.doesNotMatch(hookSource, /window\.setInterval/)
  })

  it('refreshes the authoritative account snapshot after trading-session stream events', () => {
    const source = readFileSync(
      new URL('../../../../../packages/frontend-core/src/account/accountOperations.ts', import.meta.url),
      'utf8'
    )
    const streamSource = readFileSync(
      new URL('../../../../../packages/frontend-core/src/market/marketStream.ts', import.meta.url),
      'utf8'
    )

    assert.match(source, /subscribeAccountEvents/)
    assert.match(source, /subscribeAccountEvents\([\s\S]*coordinator\?\.notifyEvent\(\)[\s\S]*coordinator\?\.notifyReconnect\(\)/)
    assert.match(source, /coordinator\?\.dispose\(\)/)
    assert.match(streamSource, /subscribeTradingSessionEvents/)
    assert.match(streamSource, /'\/user\/queue\/trading-events'/)
    assert.doesNotMatch(streamSource, /\/topic\/trading\/accounts/)
  })

  it('uses backend position refresh as the authoritative open-position PnL source', () => {
    const source = readFileSync(
      new URL('../../../../../packages/frontend-core/src/account/accountOperations.ts', import.meta.url),
      'utf8'
    )

    assert.match(source, /createAccountRefreshCoordinator/)
    assert.match(source, /createAccountRefreshCoordinator\(\{[\s\S]*refresh,/)
    assert.doesNotMatch(source, /subscribeQuote/)
    assert.doesNotMatch(source, /repriceOpenPositionsForQuote/)
    assert.doesNotMatch(source, /positionQuoteSymbolsKey/)
  })

  it('loads merged ledger rows while keeping account asset ledger details available', () => {
    const source = readFileSync(
      new URL('../../../../../packages/frontend-core/src/account/accountOperations.ts', import.meta.url),
      'utf8'
    )
    const modelsSource = readFileSync(
      new URL('../../../../../packages/frontend-core/src/account/accountSessionModels.ts', import.meta.url),
      'utf8'
    )

    assert.match(source, /getLedgerEntries/)
    assert.match(source, /getAssetLedger/)
    assert.match(modelsSource, /assetLedgerEntries:\s*AssetLedgerEntry\[\]/)
    assert.match(source, /return \{[\s\S]*ledgerEntries,[\s\S]*assetLedgerEntries,[\s\S]*walletBalances[\s\S]*\}/)
    assert.doesNotMatch(source, /mapAssetLedgerEntry/)
  })

  it('loads the initial account, order, trade, position, funding and transfer snapshot in parallel', () => {
    const source = readFileSync(
      new URL('../../../../../packages/frontend-core/src/account/accountOperations.ts', import.meta.url),
      'utf8'
    )
    const loadBlock = sourceBetween(
      source,
      'export async function loadAccountData',
      'export function createAccountDataController'
    )

    assert.match(loadBlock, /Promise\.all\(\[/)
    for (const request of [
      'getAccountSummary',
      'getOrders',
      'getTrades',
      'getPositions',
      'getPositionHistory',
      'getFundingSettlements',
      'getAccountTransfers',
      'getLedgerEntries',
      'getAssetLedger',
      'getWalletBalances'
    ]) {
      assert.match(loadBlock, new RegExp(`${request}\\(accountId, token\\)`))
    }
    assert.match(
      loadBlock,
      /return \{[\s\S]*account,[\s\S]*orders,[\s\S]*trades,[\s\S]*positions,[\s\S]*positionHistory,[\s\S]*fundingSettlements,[\s\S]*transfers,/
    )
  })

  it('routes boot, public, event, order, OCO and position refreshes through one latest single-flight gate', () => {
    const source = readFileSync(
      new URL('../../../../../packages/frontend-core/src/account/accountOperations.ts', import.meta.url),
      'utf8'
    )
    const hookSource = readFileSync(new URL('./useTradingSession.ts', import.meta.url), 'utf8')

    assert.match(source, /createLatestSingleFlightRefreshGate/)
    assert.match(source, /refreshGate\.request\(/)
    assert.match(source, /dependencies\.loadAccountData\(token, data\.account\.id\)/)
    assert.match(source, /startRuntime\(savedToken\)/)
    assert.match(source, /coordinator\?\.notifyEvent\(\)/)
    assert.match(hookSource, /submitTradingOrder[\s\S]*await refreshAccountData\(token, accountId\)/)
    assert.match(hookSource, /submitTradingOco[\s\S]*await refreshAccountData\(token, accountId\)/)
    assert.match(hookSource, /mutateTradingPosition[\s\S]*await refreshAccountData\(token, accountId\)/)
  })
})

function sourceBetween(source: string, start: string, end: string) {
  const startIndex = source.indexOf(start)
  assert.notEqual(startIndex, -1, `Missing source marker: ${start}`)
  const endIndex = source.indexOf(end, startIndex)
  assert.notEqual(endIndex, -1, `Missing source marker: ${end}`)
  return source.slice(startIndex, endIndex)
}

function makeAuthTokenStorage(entries: Array<[string, string]> = []) {
  const values = new Map<string, string>(entries)
  const storage = {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
    removeItem: (key: string) => values.delete(key)
  }
  return { storage, values }
}

function makePosition(patch: Partial<PositionResponse>): PositionResponse {
  return {
    id: 'pos',
    symbol: 'BTCUSDT',
    side: 'BUY',
    lots: '0',
    openPrice: '60000',
    currentPrice: '61000',
    floatingPnl: '0',
    realizedPnl: '0',
    marginHeld: '0',
    status: 'OPEN',
    ...patch
  }
}

function walletBalance(asset: string, available: string, walletType = 'SPOT'): WalletBalance {
  return {
    id: `wallet-${asset}`,
    accountId: 'acct_1',
    walletType,
    asset,
    total: available,
    available,
    locked: '0'
  }
}

function accountSummary(patch: Partial<AccountSummary> = {}): AccountSummary {
  return {
    id: 'acct_1',
    accountType: 'DEMO',
    baseCurrency: 'USD',
    balance: '10000',
    equity: '10000',
    usedMargin: '0',
    freeMargin: '10000',
    marginLevel: null,
    leverage: 100,
    status: 'ACTIVE',
    ...patch
  }
}

function jsonResponse(data: unknown) {
  return new Response(JSON.stringify({
    success: true,
    code: 'OK',
    message: 'ok',
    data
  }), { status: 200, headers: { 'Content-Type': 'application/json' } })
}

function quote(symbol: string, bid: string, ask: string): Quote {
  const bidValue = Number(bid)
  const askValue = Number(ask)
  return {
    type: 'quote',
    symbol,
    bid,
    ask,
    mid: String((bidValue + askValue) / 2),
    spread: String(askValue - bidValue),
    source: 'test',
    timestamp: 1781265723478
  }
}
