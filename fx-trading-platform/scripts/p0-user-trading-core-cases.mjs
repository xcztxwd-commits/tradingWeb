import assert from 'node:assert/strict'
import { join } from 'node:path'

const DESKTOP = {
  name: 'p0-desktop',
  width: 1440,
  height: 900,
  mobile: false
}

export async function runAuth01(context, definition, details = {}) {
  return runAuthCase(context, definition, details, async (startedAt) => {
    const credentials = context.userFactory('AUTH-01')
    const browser = await context.ui.launchBrowser()
    let page
    try {
      page = await context.ui.createEvidencePage(browser, {
        caseId: definition.id,
        viewport: DESKTOP
      })
      await page.navigate(`${page.p0Options.webBaseUrl}/register`)
      await page.waitForFunction(
        () => Boolean(document.querySelector('[aria-labelledby="register-title"] form')),
        'AUTH-01 registration page'
      )
      const before = await checkpoint(context, definition, 'before', [page])
      const registration = await context.ui.registerViaUi(page, credentials)
      const submitted = await checkpoint(context, definition, 'submitted', [page])

      const priorTimeOrigin = await page.evaluate(() => performance.timeOrigin)
      await page.send('Page.reload')
      await page.waitForFunction(
        (previousTimeOrigin) => performance.timeOrigin !== previousTimeOrigin
          && window.location.pathname === '/account/overview'
          && document.readyState === 'complete',
        'AUTH-01 persisted browser session',
        priorTimeOrigin
      )
      await page.navigate(`${page.p0Options.webBaseUrl}/wallet`)
      await page.waitForFunction(
        () => window.location.pathname === '/wallet',
        'AUTH-01 wallet route'
      )
      await context.ui.openTradePanel(page, {
        product: 'spot',
        symbol: 'BTCUSDT'
      })
      await context.ui.openTradePanel(page, {
        product: 'perpetual',
        symbol: 'BTCUSDT-PERP'
      })

      const database = await context.db.assertDedicatedDatabase()
      const accountSnapshot = await context.api.snapshotAccount(page)
      const marketSnapshot = await context.api.snapshotMarket('BTCUSDT-PERP')
      const dbSnapshot = await context.db.snapshotTradingRows(
        accountSnapshot.account.id
      )
      assertAuth01State(accountSnapshot, dbSnapshot)
      const final = await checkpoint(context, definition, 'final', [page], {
        apiEvidence: [safeAccountEvidence(accountSnapshot), marketSnapshot],
        dbEvidence: [dbSnapshot]
      })
      return persistPass(context, definition, {
        startedAt,
        database,
        user: { accountId: accountSnapshot.account.id },
        userActions: [
          { action: 'register-via-ui', requestRef: registration.requestRef }
        ],
        checkpoints: [before, submitted, final],
        apiEvidence: [safeAccountEvidence(accountSnapshot), marketSnapshot],
        dbEvidence: [dbSnapshot]
      }, details)
    } finally {
      await closeBrowserPage(page, browser)
    }
  })
}

export async function runAuth02(context, definition, details = {}) {
  return runAuthCase(context, definition, details, async (startedAt) => {
    const credentials = context.userFactory('AUTH-01')
    const browser = await context.ui.launchBrowser()
    let page
    try {
      page = await context.ui.createEvidencePage(browser, {
        caseId: definition.id,
        viewport: DESKTOP
      })
      const initialLogin = await context.ui.loginViaUi(page, credentials)
      const beforeSnapshot = await context.api.snapshotAccount(page)
      const before = await checkpoint(context, definition, 'before', [page], {
        apiEvidence: [safeAccountEvidence(beforeSnapshot)]
      })
      const firstLogout = await context.ui.logoutViaUi(page)
      await context.ui.openTradePanel(page, {
        product: 'perpetual',
        symbol: 'BTCUSDT-PERP'
      })
      const guestAttempt = await context.ui.submitOrderViaUi(page, {
        side: 'BUY',
        orderType: 'MARKET',
        amount: '0.001',
        expectLogin: true
      })
      assert.equal(guestAttempt.loginRequired, true)
      assert.equal(guestAttempt.requestRef, null)
      const redirect = await context.ui.followLoginPromptViaUi(page)
      assert.equal(redirect.path, '/login')
      assert.equal(redirect.redirect, '/trade/perpetual/BTCUSDT-PERP')
      const redirectedLogin = await context.ui.loginViaUi(page, credentials, {
        redirect: redirect.redirect
      })
      const submitted = await checkpoint(context, definition, 'submitted', [page])

      const secondLogout = await context.ui.logoutViaUi(page)
      const wrongPassword = await context.ui.loginViaUi(
        page,
        { ...credentials, password: `${credentials.password}x` },
        {
          expectFailure: true,
          reason: 'AUTH-02 wrong password'
        }
      )
      assert.equal(wrongPassword.authenticated, false)
      const finalLogin = await context.ui.loginViaUi(page, credentials)
      const afterSnapshot = await context.api.snapshotAccount(page)
      const database = await context.db.assertDedicatedDatabase()
      const dbSnapshot = await context.db.snapshotTradingRows(
        afterSnapshot.account.id
      )
      assert.equal(afterSnapshot.account.id, beforeSnapshot.account.id)
      assert.equal(Number(dbSnapshot.activeDemoAccounts), 1)
      const final = await checkpoint(context, definition, 'final', [page], {
        apiEvidence: [safeAccountEvidence(afterSnapshot)],
        dbEvidence: [dbSnapshot]
      })
      return persistPass(context, definition, {
        startedAt,
        database,
        user: { accountId: afterSnapshot.account.id },
        userActions: [
          { action: 'login-via-ui', requestRef: initialLogin.requestRef },
          { action: 'logout-via-ui', requestRef: firstLogout.requestRef },
          { action: 'guest-submit-blocked', requestRef: null },
          { action: 'redirected-login-via-ui', requestRef: redirectedLogin.requestRef },
          { action: 'logout-via-ui', requestRef: secondLogout.requestRef },
          { action: 'wrong-password-via-ui', requestRef: wrongPassword.requestRef },
          { action: 'login-via-ui', requestRef: finalLogin.requestRef }
        ],
        checkpoints: [before, submitted, final],
        apiEvidence: [
          safeAccountEvidence(beforeSnapshot),
          safeAccountEvidence(afterSnapshot)
        ],
        dbEvidence: [dbSnapshot]
      }, details)
    } finally {
      await closeBrowserPage(page, browser)
    }
  })
}

export async function runAuth03(context, definition, details = {}) {
  return runAuthCase(context, definition, details, async (startedAt) => {
    const credentialsA = context.userFactory('AUTH-03-A')
    const credentialsB = context.userFactory('AUTH-03-B')
    const browserA = await context.ui.launchBrowser()
    let browserB
    let pageA
    let pageB
    try {
      browserB = await context.ui.launchBrowser()
      pageA = await context.ui.createEvidencePage(browserA, {
        caseId: definition.id,
        viewport: DESKTOP
      })
      pageB = await context.ui.createEvidencePage(browserB, {
        caseId: definition.id,
        viewport: DESKTOP
      })
      await Promise.all([
        openRegistrationPage(pageA, 'AUTH-03 USER_A'),
        openRegistrationPage(pageB, 'AUTH-03 USER_B')
      ])
      const before = await checkpoint(context, definition, 'before', [pageA, pageB])
      const registrationA = await context.ui.registerViaUi(pageA, credentialsA)
      const registrationB = await context.ui.registerViaUi(pageB, credentialsB)
      const logoutA = await context.ui.logoutViaUi(pageA)
      const logoutB = await context.ui.logoutViaUi(pageB)
      const loginA = await context.ui.loginViaUi(pageA, credentialsA)
      const loginB = await context.ui.loginViaUi(pageB, credentialsB)

      await context.ui.openTradePanel(pageA, {
        product: 'spot',
        symbol: 'BTCUSDT'
      })
      await context.ui.openTradePanel(pageB, {
        product: 'spot',
        symbol: 'BTCUSDT'
      })
      await context.events.waitForStompEvent(
        pageA,
        '/user/queue/trading-events'
      )
      await context.events.waitForStompEvent(
        pageB,
        '/user/queue/trading-events'
      )
      const accountA = await context.api.snapshotAccount(pageA)
      const accountB = await context.api.snapshotAccount(pageB)
      assert.notEqual(accountA.account.id, accountB.account.id)
      const bFrameCursor = context.events.snapshotFrames(pageB).length

      const marketBuy = await context.ui.submitOrderViaUi(pageA, {
        side: 'BUY',
        orderType: 'MARKET',
        amount: '10'
      })
      await context.events.waitForStompEvent(pageA, 'TRADE_CREATED')
      await context.events.waitForStompEvent(pageA, 'BALANCE_UPDATED')
      const quote = (await context.api.snapshotMarket('BTCUSDT')).quote
      const pendingLimit = await context.ui.submitOrderViaUi(pageA, {
        side: 'BUY',
        orderType: 'LIMIT',
        price: limitPriceBelow(quote),
        amount: '0.001'
      })
      const cancelAll = await context.ui.cancelAllOrdersViaUi(pageA)
      await context.events.waitForStompEvent(pageA, 'ORDER_CANCELED')
      const submitted = await checkpoint(context, definition, 'submitted', [pageA, pageB])

      await visitUserIsolationRoutes(pageB, accountA.account.id)
      const afterA = await context.api.snapshotAccount(pageA)
      const afterB = await context.api.snapshotAccount(pageB)
      assert.equal(afterA.trades.length > 0, true)
      assert.equal(afterB.trades.length, 0)
      assert.equal(afterB.orders.length, 0)
      assert.equal(
        JSON.stringify(afterB).includes(accountA.account.id),
        false,
        'USER_B snapshot must not contain USER_A account id'
      )
      const crossAccountProbe = await assertCrossAccountReadDenied(
        context,
        pageB,
        accountA.account.id
      )
      const legacyTopicProbe = await context.events.probeForbiddenSubscription(
        pageB,
        `/topic/trading/accounts/${accountA.account.id}/events`
      )
      assert.equal(legacyTopicProbe.status, 'REJECTED')
      const bFramesAfter = context.events.snapshotFrames(pageB).slice(bFrameCursor)
      assert.equal(
        bFramesAfter.some(({ eventType }) => (
          ['TRADE_CREATED', 'BALANCE_UPDATED', 'ORDER_CANCELED'].includes(eventType)
        )),
        false,
        'USER_B must not receive USER_A account events'
      )
      const database = await context.db.assertDedicatedDatabase()
      const dbA = await context.db.snapshotTradingRows(accountA.account.id)
      const dbB = await context.db.snapshotTradingRows(accountB.account.id)
      const final = await checkpoint(context, definition, 'final', [pageA, pageB], {
        apiEvidence: [safeAccountEvidence(afterA), safeAccountEvidence(afterB)],
        dbEvidence: [dbA, dbB]
      })
      return persistPass(context, definition, {
        startedAt,
        database,
        user: {
          account: {
            userA: accountA.account.id,
            userB: accountB.account.id
          }
        },
        userActions: [
          { action: 'register-user-a-via-ui', requestRef: registrationA.requestRef },
          { action: 'register-user-b-via-ui', requestRef: registrationB.requestRef },
          { action: 'logout-user-a-via-ui', requestRef: logoutA.requestRef },
          { action: 'logout-user-b-via-ui', requestRef: logoutB.requestRef },
          { action: 'login-user-a-via-ui', requestRef: loginA.requestRef },
          { action: 'login-user-b-via-ui', requestRef: loginB.requestRef },
          { action: 'market-buy-user-a-via-ui', requestRef: marketBuy.requestRef },
          { action: 'pending-limit-user-a-via-ui', requestRef: pendingLimit.requestRef },
          { action: 'cancel-all-user-a-via-ui', requestRef: cancelAll.requestRef }
        ],
        checkpoints: [before, submitted, final],
        contractProbes: [crossAccountProbe, legacyTopicProbe],
        apiEvidence: [safeAccountEvidence(afterA), safeAccountEvidence(afterB)],
        dbEvidence: [dbA, dbB]
      }, details)
    } finally {
      await closeBrowserPage(pageB, browserB)
      await closeBrowserPage(pageA, browserA)
    }
  })
}

export const CASE_HANDLERS = Object.freeze({
  runAuth01,
  runAuth02,
  runAuth03
})

async function runAuthCase(context, definition, details, execute) {
  const startedAt = new Date().toISOString()
  try {
    return await execute(startedAt)
  } catch (error) {
    const failedAt = new Date().toISOString()
    const terminal = terminalFields(
      definition,
      'FAIL',
      startedAt,
      failedAt,
      details,
      {}
    )
    const result = {
      ...terminal,
      commit: context.run.commit,
      startedAt,
      finishedAt: failedAt,
      profile: definition.requiredSubruns[0]?.profile,
      viewport: definition.requiredSubruns[0]?.viewport,
      consoleErrors: [],
      failureOrBlocker: {
        status: 'FAIL',
        reason: error instanceof Error ? error.message : 'P0_AUTH_CASE_FAILED'
      }
    }
    persistResult(context, definition.id, result)
    throw error
  }
}

function persistPass(context, definition, evidence, details) {
  const finishedAt = new Date().toISOString()
  const finalCheckpoint = evidence.checkpoints.at(-1)
  const artifactHashes = Object.assign(
    {},
    ...evidence.checkpoints.map((checkpoint) => checkpoint.artifactHashes ?? {})
  )
  const terminal = terminalFields(
    definition,
    'PASS',
    evidence.startedAt,
    finishedAt,
    details,
    artifactHashes
  )
  const result = {
    ...terminal,
    commit: context.run.commit,
    database: evidence.database,
    user: evidence.user,
    profile: definition.requiredSubruns[0]?.profile,
    viewport: definition.requiredSubruns[0]?.viewport,
    startedAt: evidence.startedAt,
    finishedAt,
    preconditions: [],
    userActions: evidence.userActions,
    fixtureActions: [],
    contractProbes: evidence.contractProbes ?? [],
    replayProbes: [],
    checkpoints: evidence.checkpoints.map(({ name, uiEvidence }) => ({
      name,
      uiEvidence
    })),
    uiEvidence: evidence.checkpoints.flatMap(({ uiEvidence }) => uiEvidence),
    networkEvidence: finalCheckpoint?.networkEvidence ?? [],
    apiEvidence: evidence.apiEvidence,
    dbEvidence: evidence.dbEvidence,
    eventEvidence: finalCheckpoint?.eventEvidence ?? [],
    consoleErrors: [],
    cleanup: { status: 'PASS' }
  }
  persistResult(context, definition.id, result)
  return result
}

function terminalFields(
  definition,
  status,
  startedAt,
  finishedAt,
  details,
  artifactHashes
) {
  const attempt = positiveAttempt(details?.attempt)
  const profileAttempt = positiveAttempt(details?.profileAttempt)
  const durationMs = Math.max(0, Date.parse(finishedAt) - Date.parse(startedAt))
  return {
    schemaVersion: 1,
    id: definition.id,
    status,
    attempt,
    durationMs,
    scopeComplete: true,
    artifactHashes,
    subruns: definition.requiredSubruns.map((subrun) => ({
      ...subrun,
      status,
      attempt,
      profileAttempt,
      durationMs,
      artifactHashes
    }))
  }
}

function positiveAttempt(value) {
  return Number.isSafeInteger(value) && value > 0 ? value : 1
}

function persistResult(context, caseId, result) {
  context.evidence.writeCaseResultAtomic(
    join(context.run.artifactRoot, caseId, 'result.json'),
    result
  )
}

function checkpoint(context, definition, name, pages, evidence = {}) {
  return context.evidence.captureCheckpoint(context, name, {
    caseId: definition.id,
    pages,
    ...evidence
  })
}

function assertAuth01State(snapshot, dbSnapshot) {
  const spotUsdt = snapshot.wallets.find((wallet) => (
    wallet.walletType === 'SPOT' && wallet.asset === 'USDT'
  ))
  assert(spotUsdt, 'AUTH-01 requires a Spot USDT wallet')
  assertNear(spotUsdt.total, 50000, 'Spot total')
  assertNear(spotUsdt.available, 50000, 'Spot available')
  assertNear(spotUsdt.locked, 0, 'Spot locked')
  assertNear(snapshot.summary.balance, 50000, 'Perpetual balance')
  assertNear(snapshot.summary.equity, 50000, 'Perpetual equity')
  assertNear(snapshot.summary.freeMargin, 50000, 'Perpetual free margin')
  assertNear(snapshot.summary.usedMargin, 0, 'Perpetual used margin')
  assert.equal(snapshot.settings.positionMode, 'ONE_WAY')
  const btcPerp = snapshot.settings.symbols?.find(({ symbol }) => (
    symbol === 'BTCUSDT-PERP'
  ))
  assert.equal(btcPerp?.marginMode, 'CROSS')
  assert.equal(Number(btcPerp?.leverage), 10)
  assert.equal(typeof btcPerp?.quantityUnit, 'string')
  assert.equal(snapshot.orders.length, 0)
  assert.equal(snapshot.trades.length, 0)
  assert.equal(snapshot.positions.length, 0)
  assert.equal(snapshot.fundingSettlements.length, 0)
  assert.equal(Number(dbSnapshot.activeDemoAccounts), 1)
  assert.equal(Number(dbSnapshot.orders), 0)
  assert.equal(Number(dbSnapshot.trades), 0)
  assert.equal(Number(dbSnapshot.openPositions), 0)
  assert.equal(Number(dbSnapshot.fundingSettlements), 0)
}

function safeAccountEvidence(snapshot) {
  return {
    account: {
      id: snapshot.account.id,
      accountType: snapshot.account.accountType,
      status: snapshot.account.status
    },
    wallet: snapshot.wallets,
    position: snapshot.positions,
    order: snapshot.orders,
    trade: snapshot.trades,
    data: {
      summary: snapshot.summary,
      settings: snapshot.settings,
      fundingSettlements: snapshot.fundingSettlements
    }
  }
}

async function assertCrossAccountReadDenied(context, page, accountId) {
  try {
    await context.api.user(
      page,
      `/api/accounts/${encodeURIComponent(accountId)}/summary`
    )
  } catch (error) {
    assert(
      [403, 404].includes(error?.status),
      `cross-account read must return 403/404, got ${error?.status}`
    )
    return {
      status: 'REJECTED',
      errorCode: error.code,
      resourceId: accountId
    }
  }
  throw new Error('cross-account read unexpectedly succeeded')
}

async function openRegistrationPage(page, label) {
  await page.navigate(`${page.p0Options.webBaseUrl}/register`)
  await page.waitForFunction(
    () => Boolean(document.querySelector('[aria-labelledby="register-title"] form')),
    `${label} registration page`
  )
}

async function visitUserIsolationRoutes(page, foreignAccountId) {
  for (const route of ['/orders', '/wallet', '/positions']) {
    await page.navigate(`${page.p0Options.webBaseUrl}${route}`)
    await page.waitForFunction(
      (expectedPath) => window.location.pathname === expectedPath
        && (document.body?.innerText?.trim().length ?? 0) > 40,
      `AUTH-03 USER_B route ${route}`,
      route
    )
    const leaked = await page.evaluate(
      (accountId) => document.body?.innerText?.includes(accountId) ?? false,
      foreignAccountId
    )
    assert.equal(leaked, false, `${route} must not render USER_A account id`)
  }
}

function limitPriceBelow(quote) {
  const value = Number(quote?.bid ?? quote?.mid ?? quote?.last)
  assert(Number.isFinite(value) && value > 0, 'AUTH-03 requires a positive BTCUSDT quote')
  return (value * 0.5).toFixed(2)
}

function assertNear(actual, expected, label) {
  const numeric = Number(actual)
  assert(
    Number.isFinite(numeric) && Math.abs(numeric - expected) <= 0.000001,
    `${label} expected ${expected}, got ${actual}`
  )
}

async function closeBrowserPage(page, browser) {
  let failure
  if (page) {
    try {
      page.assertEvidenceClean('AUTH browser cleanup')
      await page.close()
    } catch (error) {
      failure = error
    }
  }
  if (browser) {
    try {
      await browser.close()
    } catch (error) {
      failure = failure
        ? new AggregateError([failure, error], 'P0_AUTH_BROWSER_CLEANUP_FAILED')
        : error
    }
  }
  if (failure) throw failure
}
