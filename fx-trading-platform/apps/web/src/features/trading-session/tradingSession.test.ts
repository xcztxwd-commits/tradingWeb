import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { registerHooks } from 'node:module'
import { describe, it } from 'node:test'

import { createLocalPreviewOrder, deriveTradingBalances } from './tradingSessionModels.ts'
import { repriceOpenPositionsForQuote } from './tradingSessionPositions.ts'
import {
  clearStoredAuthToken,
  readStoredAuthToken,
  readStoredRefreshToken,
  writeStoredAuthToken,
  writeStoredAuthTokens
} from './tradingSessionStorage.ts'
import type { PositionResponse } from '../../components/tables/types.ts'
import type { AccountSummary, OrderPayload, Quote, WalletBalance } from '../../types/trading.ts'

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
      quantity: '0.02',
      price: '60736.3',
      clientOrderId: 'client_123',
      lots: '0.02',
      requestedPrice: '60736.3',
      idempotencyKey: 'client_123'
    }

    assert.deepEqual(createLocalPreviewOrder(payload, '2026-06-06T00:00:00.000Z'), {
      id: 'client_123',
      symbol: 'BTCUSDT',
      side: 'BUY',
      orderType: 'LIMIT',
      status: 'LOCAL_PREVIEW',
      lots: '0.02',
      executionPrice: null,
      createdAt: '2026-06-06T00:00:00.000Z'
    })
  })

  it('derives terminal balances from account free margin and open positions', () => {
    const account: AccountSummary = {
      id: 'acct_1',
      accountType: 'DEMO',
      baseCurrency: 'USD',
      balance: '10000',
      equity: '10020',
      usedMargin: '120',
      freeMargin: '9876.5',
      marginLevel: null,
      leverage: 100,
      status: 'ACTIVE'
    }
    const positions: PositionResponse[] = [
      makePosition({ id: 'pos_1', symbol: 'BTCUSDT', side: 'BUY', lots: '0.02' }),
      makePosition({ id: 'pos_2', symbol: 'BTC-USDT', side: 'BUY', lots: '0.01' }),
      makePosition({ id: 'pos_3', symbol: 'BTCUSDT', side: 'SELL', lots: '0.04' }),
      makePosition({ id: 'pos_4', symbol: 'ETHUSDT', side: 'BUY', lots: '1.5' })
    ]

    const balances = deriveTradingBalances(account, positions, 'BTCUSDT')

    assert.equal(balances.USD, 9876.5)
    assert.equal(balances.USDT, 9876.5)
    assert.equal(balances.BTC, 0.03)
    assert.equal(balances.ETH, undefined)
  })

  it('prefers wallet balances over free margin and legacy position-derived inventory', () => {
    const account: AccountSummary = {
      id: 'acct_1',
      accountType: 'DEMO',
      baseCurrency: 'USD',
      balance: '10000',
      equity: '10020',
      usedMargin: '120',
      freeMargin: '9876.5',
      marginLevel: null,
      leverage: 100,
      status: 'ACTIVE'
    }
    const positions: PositionResponse[] = [
      makePosition({ id: 'pos_1', symbol: 'BTCUSDT', side: 'BUY', lots: '0.50' })
    ]
    const walletBalances: WalletBalance[] = [
      walletBalance('USDT', '5000.00000000'),
      walletBalance('BTC', '0.09990000')
    ]

    const balances = deriveTradingBalances(account, positions, 'BTCUSDT', walletBalances)

    assert.equal(balances.USDT, 5000)
    assert.equal(balances.BTC, 0.0999)
    assert.equal(balances.USD, undefined)
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
    const { ApiClientError } = await import('../../services/apiClient.ts')
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
    const authApiSource = readFileSync(new URL('../../services/authApi.ts', import.meta.url), 'utf8')
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
    assert.match(hookSource, /getSessionStatus/)
    assert.match(hookSource, /sessionAuthStatus/)
    assert.match(hookSource, /setSessionAuthStatus\('valid_token'\)/)
    assert.match(hookSource, /setLoginRequired\(true\)/)
    assert.doesNotMatch(hookSource, /getCachedDemoSession\(\)/)
    assert.doesNotMatch(hookSource, /refreshCachedDemoSession\(\)/)
    assert.doesNotMatch(sessionSource, /const demoEmail =/)
    assert.doesNotMatch(sessionSource, /signInDemoUser/)
  })

  it('requires login when no stored token or an unauthenticated session probe is returned', () => {
    const source = readFileSync(new URL('./useTradingSession.ts', import.meta.url), 'utf8')
    const loadBlock = sourceBetween(source, 'const loadSessionSnapshot = useCallback', 'const retrySession = useCallback')

    assert.match(loadBlock, /readStoredAuthToken\(\)/)
    assert.match(loadBlock, /getSessionStatus\(savedToken\)/)
    assert.match(loadBlock, /if \(status\.status === 'invalid_token'\)[\s\S]*requireLogin\('invalid_token'\)/)
    assert.match(loadBlock, /if \(!savedToken \|\| status\.status === 'guest' \|\| !status\.authenticated\)[\s\S]*requireLogin\('guest'\)/)
  })

  it('marks boot refresh failures as an error session without clearing the latest account snapshot', async () => {
    const source = readFileSync(new URL('./useTradingSession.ts', import.meta.url), 'utf8')
    const bootBlock = sourceBetween(source, 'async function bootSession()', 'void bootSession()')
    const { getTradingSessionMode } = await import('./useTradingSession.ts')

    assert.equal(getTradingSessionMode({ sessionReady: false, token: null, sessionError: 'boot failed' }), 'error')
    assert.match(bootBlock, /catch\s*\(error\)\s*\{[\s\S]*markSessionError\(error\)/)
    assert.match(bootBlock, /isAuthSessionFailure\(error\)/)
    assert.doesNotMatch(bootBlock, /setAccount\(undefined\)/)
  })

  it('turns timed auth refresh failures into login-required instead of offline preview', async () => {
    const source = readFileSync(new URL('./useTradingSession.ts', import.meta.url), 'utf8')
    const pollingBlock = sourceBetween(source, 'const interval = window.setInterval', 'return () => {')
    const { getTradingSessionMode } = await import('./useTradingSession.ts')

    assert.equal(getTradingSessionMode({ sessionReady: false, token: null, sessionError: null, loginRequired: true }), 'login-required')
    assert.equal(getTradingSessionMode({ sessionReady: false, token: 'token-1', sessionError: 'poll failed' }), 'error')
    assert.match(pollingBlock, /isAuthSessionFailure\(error\)/)
    assert.match(pollingBlock, /requireLogin\(\)/)
    assert.doesNotMatch(pollingBlock, /setOrders\(\[\]\)/)
  })

  it('reduces REST polling frequency while the page is hidden', async () => {
    const source = readFileSync(new URL('./useTradingSession.ts', import.meta.url), 'utf8')
    const { getTradingSessionRefreshMs } = await import('./useTradingSession.ts')

    assert.equal(getTradingSessionRefreshMs(2000, 'visible'), 2000)
    assert.equal(getTradingSessionRefreshMs(2000, 'hidden'), 15000)
    assert.equal(getTradingSessionRefreshMs(5000, 'hidden'), 30000)
    assert.match(source, /document\.addEventListener\('visibilitychange',\s*syncVisibility\)/)
    assert.match(source, /document\.removeEventListener\('visibilitychange',\s*syncVisibility\)/)
    assert.match(source, /const effectiveRefreshMs = getTradingSessionRefreshMs\(refreshMs,\s*visibilityState\)/)
    assert.match(source, /}, effectiveRefreshMs\)/)
  })

  it('refreshes the authoritative account snapshot after trading-session stream events', () => {
    const source = readFileSync(new URL('./useTradingSession.ts', import.meta.url), 'utf8')
    const streamSource = readFileSync(new URL('../../services/marketStream.ts', import.meta.url), 'utf8')

    assert.match(source, /subscribeTradingSessionEvents/)
    assert.match(source, /subscribeTradingSessionEvents\(accountId,\s*token,\s*\(\) => \{/)
    assert.match(source, /refreshAccountData\(token,\s*accountId,\s*\(\) => active\)/)
    assert.match(streamSource, /subscribeTradingSessionEvents/)
    assert.match(streamSource, /`\/topic\/trading\/accounts\/\$\{accountId\}\/events`/)
  })

  it('uses backend position refresh as the authoritative open-position PnL source', () => {
    const source = readFileSync(new URL('./useTradingSession.ts', import.meta.url), 'utf8')

    assert.match(source, /setInterval/)
    assert.match(source, /refreshAccountData\(token,\s*accountId/)
    assert.doesNotMatch(source, /subscribeQuote/)
    assert.doesNotMatch(source, /repriceOpenPositionsForQuote/)
    assert.doesNotMatch(source, /positionQuoteSymbolsKey/)
  })

  it('loads merged ledger rows while keeping account asset ledger details available', () => {
    const source = readFileSync(new URL('./tradingSession.ts', import.meta.url), 'utf8')

    assert.match(source, /getLedgerEntries/)
    assert.match(source, /getAssetLedger/)
    assert.match(source, /assetLedgerEntries:\s*AssetLedgerEntry\[\]/)
    assert.match(source, /return \{ account, orders, positions, positionHistory, ledgerEntries, assetLedgerEntries, walletBalances \}/)
    assert.doesNotMatch(source, /mapAssetLedgerEntry/)
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

function walletBalance(asset: string, available: string): WalletBalance {
  return {
    id: `wallet-${asset}`,
    accountId: 'acct_1',
    walletType: 'SPOT',
    asset,
    total: available,
    available,
    locked: '0'
  }
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
