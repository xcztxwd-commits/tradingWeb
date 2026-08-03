import assert from 'node:assert/strict'
import { createHash, randomUUID } from 'node:crypto'
import { readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'

import {
  alignPriceToTick,
  DEMO_RATES,
  effectiveQuantityStep,
  floorToStep,
  marketFillOracle,
  partialCloseOracle,
  perpCloseOracle,
  perpOpeningHoldOracle,
  perpPositionOracle,
  projectedNetOracle,
  quantityFromUnit,
  spotBuyOracle,
  spotSellOracle,
  tolerancesFromRules,
  transferConservationOracle,
  walletBalanceOracle,
  withinTolerance
} from './p0-user-trading-oracles.mjs'

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
      await page.waitForFunction(
        () => Boolean(document.getElementById('account-page-title')),
        'AUTH-01 registered account view'
      )
      const submitted = await checkpoint(context, definition, 'submitted', [page])

      const priorTimeOrigin = await page.evaluate(() => performance.timeOrigin)
      await page.send('Page.reload')
      await page.waitForFunction(
        (previousTimeOrigin) => performance.timeOrigin !== previousTimeOrigin
          && window.location.pathname === '/account/overview'
          && document.readyState === 'complete'
          && Boolean(document.getElementById('account-page-title')),
        'AUTH-01 persisted browser session',
        priorTimeOrigin
      )
      await page.navigate(`${page.p0Options.webBaseUrl}/wallet`)
      await page.waitForFunction(
        () => window.location.pathname === '/wallet'
          && Boolean(document.getElementById('wallet-overview')),
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
      assertAuth02SessionContinuity(beforeSnapshot, afterSnapshot)
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
      const market = await context.api.snapshotMarket('BTCUSDT')
      const pendingLimit = await context.ui.submitOrderViaUi(pageA, {
        side: 'BUY',
        orderType: 'LIMIT',
        price: limitPriceBelow(market),
        amount: '0.001'
      })
      const cancelAll = await context.ui.cancelAllOrdersViaUi(pageA)
      await context.events.waitForStompEvent(pageA, 'ORDER_CANCELED')
      const submitted = await checkpoint(context, definition, 'submitted', [pageA, pageB])

      const afterA = await context.api.snapshotAccount(pageA)
      const afterB = await context.api.snapshotAccount(pageB)
      assert.equal(afterA.trades.length > 0, true)
      assert.equal(afterB.trades.length, 0)
      assert.equal(afterB.orders.length, 0)
      assert.equal(afterB.positions.length, 0)
      assert.equal(afterB.fundingSettlements.length, 0)
      assert.equal(
        JSON.stringify(afterB).includes(accountA.account.id),
        false,
        'USER_B snapshot must not contain USER_A account id'
      )
      const uiIsolationProbe = await visitUserIsolationRoutes(
        pageB,
        accountA.account.id,
        afterB.summary
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
      assertAuth03DatabaseIsolation(database, dbA, dbB)
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
        contractProbes: [uiIsolationProbe, crossAccountProbe, legacyTopicProbe],
        apiEvidence: [safeAccountEvidence(afterA), safeAccountEvidence(afterB)],
        dbEvidence: [dbA, dbB]
      }, details)
    } finally {
      await closeBrowserPages(
        [pageB, browserB],
        [pageA, browserA]
      )
    }
  })
}

export async function runCat01(context, definition, details = {}) {
  return runSingleUserCoreCase(context, definition, details, runCatalogRouteJourney)
}

export async function runCat02(context, definition, details = {}) {
  return runCoreCatalogSweep(context, definition, details)
}

export async function runCat03(context, definition, details = {}) {
  return runSingleUserCoreCase(context, definition, details, runCatalogGuardJourney)
}

export async function runSpot01(context, definition, details = {}) {
  return runSingleUserCoreCase(context, definition, details, runSpotMarketLifecycle)
}

export async function runSpot02(context, definition, details = {}) {
  return runSingleUserCoreCase(context, definition, details, runSpotValidationJourney)
}

export async function runSpot03(context, definition, details = {}) {
  return runSingleUserCoreCase(context, definition, details, runSpotMarketableLimitJourney)
}

export async function runPerp01(context, definition, details = {}) {
  return runSingleUserCoreCase(context, definition, details, (scope) => (
    runPerpLifecycle(scope, {
      side: 'BUY',
      positionSide: 'BOTH',
      marginMode: 'CROSS',
      leverage: 50
    })
  ))
}

export async function runPerp02(context, definition, details = {}) {
  return runSingleUserCoreCase(context, definition, details, (scope) => (
    runPerpLifecycle(scope, {
      side: 'SELL',
      positionSide: 'BOTH',
      marginMode: 'ISOLATED',
      leverage: 100,
      adjustMargin: true
    })
  ))
}

export async function runPerp03(context, definition, details = {}) {
  return runSingleUserCoreCase(context, definition, details, runPerpLeverageJourney)
}

export async function runPerp04(context, definition, details = {}) {
  return runPerpQuantityUnits(context, definition, details)
}

export async function runPerp05(context, definition, details = {}) {
  return runSingleUserCoreCase(context, definition, details, runPerpWeightedEntryJourney)
}

export async function runPerp06(context, definition, details = {}) {
  return runSingleUserCoreCase(context, definition, details, runPerpReductionJourney)
}

export async function runPerp07(context, definition, details = {}) {
  return runSingleUserCoreCase(context, definition, details, runPerpReversalJourney)
}

export async function runPerp08(context, definition, details = {}) {
  return runSingleUserCoreCase(context, definition, details, runPerpHedgeJourney)
}

export async function runPerp09(context, definition, details = {}) {
  return runSingleUserCoreCase(context, definition, details, runPerpMarginJourney)
}

export async function runPerp12(context, definition, details = {}) {
  return runSingleUserCoreCase(context, definition, details, runPerpValidationJourney)
}

export async function runBatch02(context, definition, details = {}) {
  return runSingleUserCoreCase(context, definition, details, runCloseAllJourney)
}

export async function runWallet01(context, definition, details = {}) {
  return runSingleUserCoreCase(context, definition, details, runWalletTransferJourney)
}

export async function runLife02(context, definition, details = {}) {
  return runSingleUserCoreCase(context, definition, details, runDemoResetJourney)
}

export const CASE_HANDLERS = Object.freeze({
  runAuth01,
  runAuth02,
  runAuth03,
  runCat01,
  runCat02,
  runCat03,
  runSpot01,
  runSpot02,
  runSpot03,
  runPerp01,
  runPerp02,
  runPerp03,
  runPerp04,
  runPerp05,
  runPerp06,
  runPerp07,
  runPerp08,
  runPerp09,
  runPerp12,
  runBatch02,
  runWallet01,
  runLife02
})

export async function runSingleUserCoreCase(context, definition, details, execute) {
  return runCoreCase(context, definition, details, async (startedAt) => {
    const browser = await context.ui.launchBrowser()
    let page
    let adminPage
    let scope
    let caseFailure
    const fixtureRestores = []
    const restoreAll = async () => {
      const failures = []
      for (const fixture of [...fixtureRestores].reverse()) {
        if (fixture.restored) continue
        try {
          await fixture.restore()
          fixture.restored = true
          scope?.fixtureActions.push({ ...fixture.evidence, status: 'PASS' })
        } catch (error) {
          scope?.fixtureActions.push({
            ...fixture.evidence,
            status: 'FAIL',
            reason: error instanceof Error ? error.message : 'P0_FIXTURE_RESTORE_FAILED'
          })
          failures.push(error)
        }
      }
      if (failures.length > 0) {
        const error = new AggregateError(failures, 'P0_FIXTURE_RESTORE_FAILED')
        error.p0CleanupFailure = true
        error.p0FixtureActions = structuredClone(scope?.fixtureActions ?? [])
        throw error
      }
    }
    try {
      page = await context.ui.createEvidencePage(browser, {
        caseId: definition.id,
        viewport: DESKTOP
      })
      const credentials = context.userFactory(definition.id)
      const registration = await context.ui.registerViaUi(page, credentials)
      const database = await context.db.assertDedicatedDatabase()
      const beforeSnapshot = await context.api.snapshotAccount(page)
      const beforeDb = await context.db.snapshotTradingRows(
        beforeSnapshot.account.id
      )
      const before = await checkpoint(context, definition, 'before', [page], {
        apiEvidence: [safeAccountEvidence(beforeSnapshot)],
        dbEvidence: [beforeDb]
      })
      const captures = []
      scope = {
        context,
        definition,
        details,
        page,
        database,
        before: beforeSnapshot,
        beforeDb,
        userActions: [
          { action: 'register-via-ui', requestRef: registration.requestRef }
        ],
        checkpoints: [before],
        apiEvidence: [safeAccountEvidence(beforeSnapshot)],
        dbEvidence: [beforeDb],
        oracleEvidence: [],
        contractProbes: [],
        replayProbes: [],
        fixtureActions: [],
        snapshots: { before: safeAccountEvidence(beforeSnapshot) },
        async getAdminPage() {
          if (adminPage) return adminPage
          assert.equal(
            typeof context.adminFactory,
            'function',
            `${definition.id} requires run-owned Admin credentials`
          )
          adminPage = await context.ui.createEvidencePage(browser, {
            caseId: `${definition.id}-admin`,
            viewport: DESKTOP
          })
          const login = await context.ui.loginAdminViaUi(
            adminPage,
            context.adminFactory()
          )
          this.fixtureActions.push({
            action: 'login-run-owned-admin-via-ui',
            requestRef: login.requestRef
          })
          return adminPage
        },
        addMutation(action, capture) {
          assert(
            capture && typeof capture.requestRef === 'string',
            `${definition.id} mutation must be captured from the real UI`
          )
          this.userActions.push({ action, requestRef: capture.requestRef })
          return capture
        },
        registerFixtureRestore(evidence, restore) {
          assert.equal(
            typeof restore,
            'function',
            `${definition.id} fixture restore is required`
          )
          fixtureRestores.push({ evidence, restore, restored: false })
        },
        async capture(name, snapshot, evidence = {}) {
          const current = snapshot ?? await context.api.snapshotAccount(page)
          const db = await context.db.snapshotTradingRows(current.account.id)
          const captured = await checkpoint(context, definition, name, [page], {
            apiEvidence: [safeAccountEvidence(current)],
            dbEvidence: [db],
            ...evidence
          })
          this.checkpoints.push(captured)
          this.apiEvidence.push(safeAccountEvidence(current))
          this.dbEvidence.push(db)
          this.snapshots[name] = safeAccountEvidence(current)
          captures.push({ name, snapshot: current, db })
          return { checkpoint: captured, snapshot: current, db }
        }
      }
      const outcome = await execute(scope) ?? {}
      const finalSnapshot = outcome.finalSnapshot
        ?? await context.api.snapshotAccount(page)
      const finalDb = outcome.finalDb
        ?? await context.db.snapshotTradingRows(finalSnapshot.account.id)
      assertCoreCleanup(definition.id, finalSnapshot, finalDb)
      const final = await checkpoint(context, definition, 'final', [page], {
        apiEvidence: [safeAccountEvidence(finalSnapshot)],
        dbEvidence: [finalDb]
      })
      scope.checkpoints.push(final)
      scope.apiEvidence.push(safeAccountEvidence(finalSnapshot))
      scope.dbEvidence.push(finalDb)
      scope.snapshots.final = safeAccountEvidence(finalSnapshot)
      await restoreAll()
      return persistPass(context, definition, {
        startedAt,
        database,
        user: { accountId: finalSnapshot.account.id },
        userActions: scope.userActions,
        fixtureActions: scope.fixtureActions,
        checkpoints: scope.checkpoints,
        contractProbes: scope.contractProbes,
        replayProbes: scope.replayProbes,
        oracleEvidence: scope.oracleEvidence,
        snapshots: scope.snapshots,
        apiEvidence: scope.apiEvidence,
        dbEvidence: scope.dbEvidence
      }, details)
    } catch (error) {
      caseFailure = error
      throw error
    } finally {
      const failures = []
      try {
        await restoreAll()
      } catch (error) {
        failures.push(error)
      }
      try {
        await closeBrowserPages(
          [adminPage],
          [page, browser]
        )
      } catch (error) {
        failures.push(error)
      }
      if (failures.length > 0) {
        const allFailures = caseFailure ? [caseFailure, ...failures] : failures
        if (allFailures.length === 1) throw allFailures[0]
        const error = new AggregateError(allFailures, 'P0_CORE_CASE_CLEANUP_FAILED')
        const fixtureFailure = allFailures.find((failure) => failure?.p0CleanupFailure)
        if (fixtureFailure) {
          error.p0CleanupFailure = true
          error.p0FixtureActions = fixtureFailure.p0FixtureActions
        }
        throw error
      }
    }
  })
}

async function runCoreCase(context, definition, details, execute) {
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
      ...(error?.p0CleanupFailure
        ? {
            fixtureActions: error.p0FixtureActions ?? [],
            cleanup: { status: 'FAIL' }
          }
        : {}),
      failureOrBlocker: {
        status: 'FAIL',
        reason: error instanceof Error ? error.message : 'P0_CORE_CASE_FAILED'
      }
    }
    persistResult(context, definition.id, result)
    throw error
  }
}

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
  const financialChecks = evidence.oracleEvidence?.length > 0
    ? evidence.oracleEvidence
    : [{
        kind: 'NOT_APPLICABLE',
        reason: 'Case does not require a financial calculation'
      }]
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
    fixtureActions: evidence.fixtureActions ?? [],
    contractProbes: evidence.contractProbes ?? [],
    replayProbes: evidence.replayProbes ?? [],
    checkpoints: evidence.checkpoints.map(({ name, uiEvidence }) => ({
      name,
      uiEvidence
    })),
    uiEvidence: evidence.checkpoints.flatMap(({ uiEvidence }) => uiEvidence),
    networkEvidence: finalCheckpoint?.networkEvidence ?? [],
    apiEvidence: evidence.apiEvidence,
    dbEvidence: evidence.dbEvidence,
    eventEvidence: finalCheckpoint?.eventEvidence ?? [],
    financialCalculation: {
      status: 'PASS',
      checks: financialChecks
    },
    oracleEvidence: evidence.oracleEvidence ?? [],
    snapshots: evidence.snapshots ?? {},
    consoleErrors: [],
    cleanup: { status: 'PASS' }
  }
  persistResult(context, definition.id, result)
  return result
}

function mergeIndependentCaseResults(definition, results, summaryEvidence) {
  const startedAt = results.map(({ startedAt }) => startedAt).toSorted()[0]
  const finishedAt = results.map(({ finishedAt }) => finishedAt).toSorted().at(-1)
  const artifactHashes = {}
  for (const result of results) {
    for (const [path, hash] of Object.entries(result.artifactHashes ?? {})) {
      assert(
        artifactHashes[path] === undefined || artifactHashes[path] === hash,
        `${definition.id} artifact hash collision ${path}`
      )
      artifactHashes[path] = hash
    }
  }
  const oracleEvidence = [
    summaryEvidence,
    ...results.flatMap(({ oracleEvidence }) => oracleEvidence ?? [])
  ]
  return {
    ...results[0],
    id: definition.id,
    status: 'PASS',
    durationMs: Math.max(0, Date.parse(finishedAt) - Date.parse(startedAt)),
    scopeComplete: true,
    artifactHashes,
    subruns: results.flatMap(({ subruns }) => subruns),
    startedAt,
    finishedAt,
    userActions: results.flatMap(({ userActions }) => userActions ?? []),
    fixtureActions: results.flatMap(({ fixtureActions }) => fixtureActions ?? []),
    contractProbes: results.flatMap(({ contractProbes }) => contractProbes ?? []),
    replayProbes: results.flatMap(({ replayProbes }) => replayProbes ?? []),
    checkpoints: results.flatMap(({ checkpoints }) => checkpoints ?? []),
    uiEvidence: results.flatMap(({ uiEvidence }) => uiEvidence ?? []),
    networkEvidence: results.flatMap(({ networkEvidence }) => networkEvidence ?? []),
    apiEvidence: results.flatMap(({ apiEvidence }) => apiEvidence ?? []),
    dbEvidence: results.flatMap(({ dbEvidence }) => dbEvidence ?? []),
    eventEvidence: results.flatMap(({ eventEvidence }) => eventEvidence ?? []),
    financialCalculation: { status: 'PASS', checks: oracleEvidence },
    oracleEvidence,
    snapshots: Object.fromEntries(results.flatMap((result, index) => (
      Object.entries(result.snapshots ?? {}).map(([name, value]) => [
        `${result.subruns[0]?.id ?? index}:${name}`,
        value
      ])
    ))),
    consoleErrors: results.flatMap(({ consoleErrors }) => consoleErrors ?? []),
    cleanup: { status: 'PASS' }
  }
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

const SPOT_SYMBOLS = Object.freeze([
  'BTCUSDT',
  'ETHUSDT',
  'BNBUSDT',
  'SOLUSDT',
  'XRPUSDT'
])
const PERP_SYMBOLS = Object.freeze(SPOT_SYMBOLS.map((symbol) => `${symbol}-PERP`))
const ACTIVE_ORDER_STATUSES = new Set([
  'RECEIVED',
  'VALIDATING',
  'ACCEPTED',
  'PENDING_ACTIVATION',
  'PENDING',
  'WORKING',
  'PARTIALLY_FILLED',
  'CANCEL_PENDING'
])

export function assertAuth02SessionContinuity(beforeSnapshot, afterSnapshot) {
  assert.deepEqual(
    auth02SessionContinuityEvidence(afterSnapshot),
    auth02SessionContinuityEvidence(beforeSnapshot),
    'AUTH-02 session continuity must preserve account, wallet, settings, and trading history'
  )
}

function auth02SessionContinuityEvidence(snapshot) {
  const evidence = safeAccountEvidence(snapshot)
  const summary = { ...evidence.data.summary }
  delete summary.lastSnapshotAt
  return { ...evidence, data: { ...evidence.data, summary } }
}

function assertAuth03DatabaseIsolation(database, dbA, dbB) {
  assert.equal(
    dbA.database,
    database,
    'AUTH-03 USER_A database isolation must use the active database'
  )
  assert.equal(
    dbB.database,
    database,
    'AUTH-03 USER_B database isolation must use the active database'
  )
  assert(
    Number(dbA.orders) >= 2 && Number(dbA.trades) >= 1,
    'AUTH-03 USER_A database isolation requires the submitted orders and trade'
  )
  for (const field of ['orders', 'trades', 'openPositions', 'fundingSettlements']) {
    assert.equal(
      Number(dbB[field]),
      0,
      `AUTH-03 USER_B database isolation requires zero ${field}`
    )
  }
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
      fundingSettlements: snapshot.fundingSettlements,
      ...(Array.isArray(snapshot.positionHistory)
        ? { positionHistory: snapshot.positionHistory }
        : {}),
      ...(Array.isArray(snapshot.assetLedger)
        ? { assetLedger: snapshot.assetLedger }
        : {}),
      ...(Array.isArray(snapshot.cashLedger)
        ? { cashLedger: snapshot.cashLedger }
        : {}),
      ...(Array.isArray(snapshot.transfers)
        ? { transfers: snapshot.transfers }
        : {})
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

async function visitUserIsolationRoutes(page, foreignAccountId, expectedSummary) {
  const routes = []
  routes.push(await inspectIsolationTables(
    page,
    '/orders',
    ['CURRENT', 'HISTORY', 'TRADES'],
    foreignAccountId
  ))
  await openIsolationRoute(page, '/wallet', foreignAccountId)
  const wallet = await page.evaluate(readIsolationWallet, foreignAccountId)
  assert.equal(wallet.leaked, false, '/wallet must not render USER_A account id')
  assert.equal(
    wallet.balance,
    String(expectedSummary.balance),
    'AUTH-03 USER_B UI isolation wallet balance must match USER_B API'
  )
  assert.equal(
    wallet.freeMargin,
    String(expectedSummary.freeMargin),
    'AUTH-03 USER_B UI isolation wallet free margin must match USER_B API'
  )
  routes.push(wallet)
  routes.push(await inspectIsolationTables(
    page,
    '/positions',
    ['CURRENT', 'HISTORY'],
    foreignAccountId
  ))
  return {
    status: 'PASS',
    kind: 'USER_UI_ISOLATION',
    accountRole: 'USER_B',
    routes
  }
}

async function inspectIsolationTables(page, route, views, foreignAccountId) {
  await openIsolationRoute(page, route, foreignAccountId)
  const tables = []
  for (let index = 0; index < views.length; index += 1) {
    const view = views[index]
    assert.equal(
      await page.evaluate(clickIsolationTab, index),
      true,
      `AUTH-03 USER_B UI isolation requires ${view} tab`
    )
    await page.waitForFunction(
      isIsolationTableReady,
      `AUTH-03 USER_B ${route} ${view} table`,
      route,
      index
    )
    const table = await page.evaluate(readIsolationTable, route, view, index)
    assert.equal(
      table.dataRows,
      0,
      `AUTH-03 USER_B UI isolation requires zero ${view} rows`
    )
    assert.equal(
      table.emptyRows,
      1,
      `AUTH-03 USER_B UI isolation requires the ${view} empty state`
    )
    tables.push(table)
  }
  return { route, views: tables }
}

async function openIsolationRoute(page, route, foreignAccountId) {
  await page.navigate(`${page.p0Options.webBaseUrl}${route}`)
  await page.waitForFunction(
    isIsolationRouteReady,
    `AUTH-03 USER_B route ${route}`,
    route
  )
  const leaked = await page.evaluate(readIsolationLeak, foreignAccountId)
  assert.equal(leaked, false, `${route} must not render USER_A account id`)
}

function isIsolationRouteReady(expectedPath) {
  if (window.location.pathname !== expectedPath) return false
  if (document.querySelector(
    '[data-state-variant="loading"], [data-state-variant="error"], [data-state-variant="login"]'
  )) return false
  if (expectedPath === '/wallet') {
    return Boolean(document.querySelector('#wallet-overview > article strong'))
  }
  return Boolean(document.querySelector('[role="tablist"]') && document.querySelector('table'))
}

function clickIsolationTab(index) {
  const buttons = document.querySelectorAll('[role="tablist"] button')
  const button = buttons[index]
  if (!(button instanceof HTMLButtonElement)) return false
  button.click()
  return true
}

function isIsolationTableReady(expectedPath, index) {
  if (window.location.pathname !== expectedPath) return false
  if (document.querySelector(
    '[data-state-variant="loading"], [data-state-variant="error"], [data-state-variant="login"]'
  )) return false
  if (!document.querySelector('table')) return false
  const buttons = document.querySelectorAll('[role="tablist"] button')
  const active = buttons[index]
  return active instanceof HTMLButtonElement
    && active.className.trim().length > 0
}

function readIsolationTable(route, view, index) {
  const rows = Array.from(document.querySelectorAll('table tbody tr'))
  const emptyRows = rows.filter((row) => (
    row.cells.length === 1 && row.cells[0].colSpan > 1
  )).length
  return {
    route,
    view,
    tab: document.querySelectorAll('[role="tablist"] button')[index]?.textContent?.trim(),
    dataRows: rows.length - emptyRows,
    emptyRows
  }
}

function readIsolationWallet(foreignAccountId) {
  const overview = document.querySelector('#wallet-overview')
  return {
    route: '/wallet',
    balance: overview?.children[0]?.querySelector('strong')?.textContent?.trim(),
    freeMargin: overview?.children[1]?.querySelector('strong')?.textContent?.trim(),
    leaked: document.body?.innerText?.includes(foreignAccountId) ?? false
  }
}

function readIsolationLeak(foreignAccountId) {
  return document.body?.innerText?.includes(foreignAccountId) ?? false
}

function limitPriceBelow(market) {
  const quote = market?.quote
  const value = Number(quote?.bid ?? quote?.mid ?? quote?.last)
  assert(Number.isFinite(value) && value > 0, 'AUTH-03 requires a positive BTCUSDT quote')
  return alignPriceToTick((value * 0.5).toFixed(10), rulesFor(market))
}

function assertNear(actual, expected, label) {
  const numeric = Number(actual)
  assert(
    Number.isFinite(numeric) && Math.abs(numeric - expected) <= 0.000001,
    `${label} expected ${expected}, got ${actual}`
  )
}

async function runSpotMarketableLimitJourney(scope) {
  const { context, page } = scope
  const { market, fixedAuthority } = await prepareSpotAuthorityMarket(
    scope,
    'BTCUSDT',
    'SPOT-03'
  )
  const rules = rulesFor(market)
  const quantity = stepAlignedQuantity(market, 0.001)
  await context.ui.openTradePanel(page, {
    product: 'spot',
    symbol: 'BTCUSDT'
  })
  const beforeBuy = await context.api.snapshotAccount(page)
  const buy = await context.ui.submitOrderViaUi(page, {
    side: 'BUY',
    orderType: 'LIMIT',
    price: quoteDecimal(market, 'ask'),
    amount: quantity
  })
  scope.addMutation('marketable-limit-buy-via-ui', buy)
  const bought = await waitForFilledMutation(scope, beforeBuy, buy, 'BTCUSDT')
  const buyFill = assertSingleFullFillMutation(beforeBuy, bought, 'SPOT-03 BUY')
  assert.equal(bought.trade.side, 'BUY')
  assert.equal(bought.trade.liquidityRole, 'TAKER')
  assert.equal(bought.trade.feeAsset, 'BTC')
  assertDecimalClose(
    bought.trade.price,
    Math.min(Number(quoteDecimal(market, 'ask')), Number(quoteDecimal(market, 'ask'))),
    tolerancesFromRules(rules).price,
    'SPOT-03 BUY fill'
  )
  const buyFee = Number(bought.trade.lots) * Number(DEMO_RATES.takerFeeRate)
  assertDecimalClose(
    bought.trade.fee,
    buyFee,
    tolerancesFromRules(rules).amount,
    'SPOT-03 BUY fee'
  )
  const buyLedger = assertSpotTradeLedger(
    bought.snapshot,
    bought.trade,
    rules,
    'SPOT-03 BUY'
  )
  await scope.capture('submitted', bought.snapshot)

  const baseWallet = findWallet(bought.snapshot, 'SPOT', 'BTC')
  const sellQuantity = floorToStep(
    String(baseWallet.available),
    effectiveQuantityStep(rules)
  )
  assert(Number(sellQuantity) > 0, 'SPOT-03 requires sellable BTC')
  const beforeSell = bought.snapshot
  const sellMarket = await context.api.snapshotMarket('BTCUSDT')
  const sell = await context.ui.submitOrderViaUi(page, {
    side: 'SELL',
    orderType: 'LIMIT',
    price: quoteDecimal(sellMarket, 'bid'),
    amount: sellQuantity
  })
  scope.addMutation('marketable-limit-sell-via-ui', sell)
  const sold = await waitForFilledMutation(scope, beforeSell, sell, 'BTCUSDT')
  const sellFill = assertSingleFullFillMutation(beforeSell, sold, 'SPOT-03 SELL')
  assert.equal(sold.trade.side, 'SELL')
  assert.equal(sold.trade.liquidityRole, 'TAKER')
  assert.equal(sold.trade.feeAsset, 'USDT')
  assertDecimalClose(
    sold.trade.price,
    quoteDecimal(sellMarket, 'bid'),
    tolerancesFromRules(rules).price,
    'SPOT-03 SELL fill'
  )
  const sellFee = Number(sold.trade.lots)
    * Number(sold.trade.price)
    * Number(DEMO_RATES.takerFeeRate)
  assertDecimalClose(
    sold.trade.fee,
    sellFee,
    tolerancesFromRules(rules).amount,
    'SPOT-03 SELL fee'
  )
  assert.equal(activeOrders(sold.snapshot, 'BTCUSDT').length, 0)
  assertNear(
    findWallet(sold.snapshot, 'SPOT', 'BTC').locked,
    0,
    'SPOT-03 BTC locked'
  )
  const sellLedger = assertSpotTradeLedger(
    sold.snapshot,
    sold.trade,
    rules,
    'SPOT-03 SELL'
  )
  assertSpotDust(sold.snapshot, sellMarket, 'BTC', 'SPOT-03')
  scope.oracleEvidence.push({
    kind: 'SPOT_MARKETABLE_LIMIT',
    symbol: 'BTCUSDT',
    quantity,
    fixedAuthority,
    buy: {
      orderId: bought.order.id,
      tradeId: bought.trade.id,
      expectedLiquidityRole: 'TAKER',
      fullFill: buyFill,
      ledger: buyLedger
    },
    sell: {
      orderId: sold.order.id,
      tradeId: sold.trade.id,
      expectedLiquidityRole: 'TAKER',
      fullFill: sellFill,
      fee: sellFee,
      ledger: sellLedger
    },
    wallet: assertWalletInvariant(
      findWallet(sold.snapshot, 'SPOT', 'USDT'),
      rules,
      'SPOT-03 USDT'
    )
  })
  await scope.capture('fully-sold', sold.snapshot)
  return { finalSnapshot: sold.snapshot }
}

async function runCatalogRouteJourney(scope) {
  const { context, page } = scope
  const catalogSnapshot = await context.api.snapshotMarket('BTCUSDT')
  const symbols = catalogSnapshot.symbols.filter((symbol) => (
    symbol.tradable !== false && symbol.enabled !== false
  ))
  const spot = symbols
    .filter(({ productType }) => productType === 'CRYPTO_SPOT')
    .map(({ symbol }) => symbol)
    .sort()
  const perpetual = symbols
    .filter(({ productType }) => productType === 'LINEAR_PERP')
    .map(({ symbol }) => symbol)
    .sort()
  assert.deepEqual(spot, [...SPOT_SYMBOLS].sort(), 'CAT-01 Spot catalog')
  assert.deepEqual(perpetual, [...PERP_SYMBOLS].sort(), 'CAT-01 Perp catalog')

  await openTradingProductFromMenu(page, '现货交易', '/trade/spot/')
  await openTradingProductFromMenu(page, 'USDT 永续', '/trade/perpetual/')
  const routes = []
  for (const [product, expectedSymbols] of [
    ['spot', SPOT_SYMBOLS],
    ['perpetual', PERP_SYMBOLS]
  ]) {
    for (const symbol of expectedSymbols) {
      await context.ui.openTradePanel(page, { product, symbol })
      const surface = await inspectTradingSurface(page, symbol, product)
      const market = await context.api.snapshotMarket(symbol)
      assert.equal(surface.symbol, symbol)
      assert.equal(
        surface.mode.some((value) => (
          product === 'spot'
            ? /CASH|现货/i.test(value)
            : /CROSS|ISOLATED|全仓|逐仓/i.test(value)
        )),
        true,
        `CAT-01 ${symbol} margin label`
      )
      assert.equal(surface.marketSymbols.includes(symbol), true)
      if (product === 'perpetual') {
        for (const field of ['mark', 'index']) quoteDecimal(market, field)
        assert(market.reference, `CAT-01 ${symbol} perpetual reference`)
      }
      routes.push({
        product,
        symbol,
        path: surface.path,
        mode: surface.mode,
        marketSymbols: surface.marketSymbols,
        quote: market.quote,
        reference: market.reference
      })
    }
  }

  for (const [product, invalid] of [
    ['spot', 'INVALIDUSDT'],
    ['perpetual', 'INVALIDUSDT-PERP']
  ]) {
    const result = await inspectInvalidTradingRoute(page, product, invalid)
    assert.equal(
      result.pathEndsWithInvalid && result.enabledOrderSubmit,
      false,
      `CAT-01 ${product} invalid route must not expose a submit-ready form`
    )
    scope.contractProbes.push({
      status: 'PASS',
      kind: 'INVALID_TRADING_ROUTE',
      product,
      symbol: invalid,
      result
    })
  }
  scope.oracleEvidence.push({
    kind: 'P0_CATALOG',
    expected: {
      spot: SPOT_SYMBOLS,
      perpetual: PERP_SYMBOLS
    },
    observed: { spot, perpetual },
    routes
  })
  await scope.capture('catalog-routes')
}

async function runCoreCatalogSweep(context, definition, details) {
  return runSingleUserCoreCase(
    context,
    definition,
    details,
    runCatalogSweepJourney
  )
}

async function runCatalogSweepJourney(scope) {
  const { context, page } = scope
  const sweep = []
  for (const symbol of SPOT_SYMBOLS) {
    const market = await context.api.snapshotMarket(symbol)
    const rules = rulesFor(market)
    await context.ui.openTradePanel(page, { product: 'spot', symbol })
    const beforeBuy = await context.api.snapshotAccount(page)
    const buy = await context.ui.submitOrderViaUi(page, {
      side: 'BUY',
      orderType: 'MARKET',
      amount: '25'
    })
    scope.addMutation(`catalog-${symbol}-buy-via-ui`, buy)
    const bought = await waitForFilledMutation(scope, beforeBuy, buy, symbol)
    const metadata = market.symbols.find((candidate) => candidate.symbol === symbol)
    const baseAsset = metadata?.baseCurrency
      ?? metadata?.baseAsset
      ?? symbol.slice(0, -4)
    const wallet = findWallet(bought.snapshot, 'SPOT', baseAsset)
    const sellQuantity = floorToStep(
      String(wallet.available),
      effectiveQuantityStep(rules)
    )
    assert(Number(sellQuantity) > 0, `CAT-02 ${symbol} sell quantity`)
    const sell = await context.ui.submitOrderViaUi(page, {
      side: 'SELL',
      orderType: 'MARKET',
      amount: sellQuantity
    })
    scope.addMutation(`catalog-${symbol}-sell-via-ui`, sell)
    const sold = await waitForFilledMutation(scope, bought.snapshot, sell, symbol)
    assert.equal(activeOrders(sold.snapshot, symbol).length, 0)
    assert.equal(
      Number(findWallet(sold.snapshot, 'SPOT', baseAsset).locked),
      0
    )
    assertSpotDust(sold.snapshot, market, baseAsset, `CAT-02 ${symbol}`)
    sweep.push({
      product: 'CRYPTO_SPOT',
      symbol,
      openingOrderId: bought.order.id,
      openingTradeId: bought.trade.id,
      closingOrderId: sold.order.id,
      closingTradeId: sold.trade.id,
      providerCode: bought.trade.providerCode,
      sourceMode: bought.trade.sourceMode
    })
    await scope.capture(`sweep-${symbol.toLowerCase()}`, sold.snapshot)
  }

  for (const symbol of PERP_SYMBOLS) {
    const market = await context.api.snapshotMarket(symbol)
    const quantity = stepAlignedQuantity(market)
    await context.ui.openTradePanel(page, { product: 'perpetual', symbol })
    await ensurePerpSettings(scope, {
      positionMode: 'ONE_WAY',
      marginMode: 'CROSS',
      leverage: 10,
      quantityUnit: 'BASE'
    })
    const beforeOpen = await context.api.snapshotAccount(page)
    const opening = await context.ui.submitOrderViaUi(page, {
      side: 'BUY',
      orderType: 'MARKET',
      amount: quantity
    })
    scope.addMutation(`catalog-${symbol}-open-via-ui`, opening)
    const opened = await waitForFilledMutation(
      scope,
      beforeOpen,
      opening,
      symbol
    )
    const position = openPositions(opened.snapshot, symbol).at(0)
    assert(position, `CAT-02 ${symbol} open position`)
    assert.equal(position.symbol, symbol)
    const closing = await closePositionFromUi(scope, position)
    const closed = await waitForAccount(
      context,
      page,
      `CAT-02 ${symbol} close`,
      (candidate) => openPositions(candidate, symbol).length === 0 && candidate
    )
    const closingOrder = orderForCapture(opened.snapshot, closed, closing)
    const closingTrade = closingOrder
      ? tradeForOrder(closed, closingOrder.id)
      : recordsAfter(opened.snapshot, closed, 'trades').at(-1)
    assert(closingTrade, `CAT-02 ${symbol} closing trade`)
    assert.equal(activeOrders(closed, symbol).length, 0)
    sweep.push({
      product: 'LINEAR_PERP',
      symbol,
      openingOrderId: opened.order.id,
      openingTradeId: opened.trade.id,
      positionId: position.id,
      closingOrderId: closingOrder?.id,
      closingTradeId: closingTrade.id,
      providerCode: opened.trade.providerCode,
      sourceMode: opened.trade.sourceMode
    })
    await scope.capture(`sweep-${symbol.toLowerCase()}`, closed)
  }
  assert.equal(sweep.length, 10)
  scope.oracleEvidence.push({
    kind: 'P0_PRODUCT_TRADING_SWEEP',
    products: sweep
  })
}

async function runCatalogGuardJourney(scope) {
  const { context, page } = scope
  const reachability = await inspectForbiddenProductReachability(page)
  assert.equal(reachability.liveAccountEntry, false)
  assert.deepEqual(reachability.forbiddenProducts, [])
  assert(reachability.tradingMenu, 'CAT-03 trading menu must be readable')
  scope.contractProbes.push({
    status: 'PASS',
    kind: 'UI_PRODUCT_REACHABILITY',
    ...reachability
  })

  await context.ui.openTradePanel(page, {
    product: 'spot',
    symbol: 'BTCUSDT'
  })
  const legalBefore = await context.api.snapshotAccount(page)
  const legal = await context.ui.submitOrderViaUi(page, {
    side: 'BUY',
    orderType: 'MARKET',
    amount: '10'
  })
  scope.addMutation('catalog-guard-legal-capture-via-ui', legal)
  const bought = await waitForFilledMutation(
    scope,
    legalBefore,
    legal,
    'BTCUSDT'
  )
  const market = await context.api.snapshotMarket('BTCUSDT')
  const sellable = floorToStep(
    String(findWallet(bought.snapshot, 'SPOT', 'BTC').available),
    effectiveQuantityStep(rulesFor(market))
  )
  const cleanup = await context.ui.submitOrderViaUi(page, {
    side: 'SELL',
    orderType: 'MARKET',
    amount: sellable
  })
  scope.addMutation('catalog-guard-legal-cleanup-via-ui', cleanup)
  const baseline = await waitForFilledMutation(
    scope,
    bought.snapshot,
    cleanup,
    'BTCUSDT'
  )
  assertSpotDust(baseline.snapshot, market, 'BTC', 'CAT-03')
  await scope.capture('submitted', baseline.snapshot)

  let liveAccountId
  try {
    liveAccountId = scalarResult(await context.db.query(`
      INSERT INTO core.trading_accounts (
        user_id, account_type, base_currency, balance, equity, used_margin,
        free_margin, leverage, status, position_mode, demo_generation
      )
      SELECT
        user_id, 'LIVE', 'USDT', 0, 0, 0, 0, 10, 'ACTIVE',
        'ONE_WAY', 0
      FROM core.trading_accounts
      WHERE id = '${baseline.snapshot.account.id}'
      RETURNING id;
    `))
    assertUuid(liveAccountId, 'CAT-03 LIVE sentinel')
    scope.fixtureActions.push({
      action: 'create-zero-balance-live-sentinel',
      accountId: liveAccountId
    })
    const liveBefore = await context.db.snapshotTradingRows(liveAccountId)
    const liveProbe = await replayCapturedMutation(
      scope,
      legal,
      (body) => ({
        ...body,
        accountId: liveAccountId,
        idempotencyKey: randomUUID(),
        clientOrderId: randomUUID()
      }),
      {
        expectFailure: true,
        reason: 'CAT-03 LIVE account guard'
      }
    )
    assertRejectedCode(
      liveProbe,
      'DEMO_ACCOUNT_REQUIRED',
      'CAT-03 LIVE account guard'
    )
    const liveAfter = await context.db.snapshotTradingRows(liveAccountId)
    assert.deepEqual(liveAfter, liveBefore, 'CAT-03 LIVE sentinel zero mutation')
    scope.contractProbes.push({
      status: 'REJECTED',
      kind: 'L7_LIVE_ACCOUNT',
      requestRef: liveProbe.requestRef,
      errorCode: responseCode(liveProbe),
      accountId: liveAccountId
    })

    const productProbe = await replayCapturedMutation(
      scope,
      legal,
      (body) => ({
        ...body,
        symbol: 'BTCUSDT',
        marginMode: 'CROSS',
        reduceOnly: true,
        idempotencyKey: randomUUID(),
        clientOrderId: randomUUID()
      }),
      {
        expectFailure: true,
        reason: 'CAT-03 non-P0 product guard'
      }
    )
    assertRejectedCode(
      productProbe,
      'PRODUCT_NOT_ALLOWED',
      'CAT-03 non-P0 product guard'
    )
    scope.contractProbes.push({
      status: 'REJECTED',
      kind: 'L7_NON_P0_PRODUCT',
      requestRef: productProbe.requestRef,
      symbol: 'BTCUSDT',
      marginMode: 'CROSS',
      reduceOnly: true,
      errorCode: responseCode(productProbe)
    })

    const symbolProbe = await replayCapturedMutation(
      scope,
      legal,
      (body) => ({
        ...body,
        symbol: 'NOTP0USDT',
        idempotencyKey: randomUUID(),
        clientOrderId: randomUUID()
      }),
      {
        expectFailure: true,
        reason: 'CAT-03 symbol allowlist guard'
      }
    )
    assertRejectedCode(
      symbolProbe,
      'SYMBOL_NOT_ALLOWED',
      'CAT-03 symbol allowlist guard'
    )
    scope.contractProbes.push({
      status: 'REJECTED',
      kind: 'L7_NON_ALLOWLIST_SYMBOL',
      requestRef: symbolProbe.requestRef,
      errorCode: responseCode(symbolProbe)
    })
    const after = await context.api.snapshotAccount(page)
    assertTradingStateEqual(
      baseline.snapshot,
      after,
      'CAT-03 rejected product probes zero mutation'
    )
    return { finalSnapshot: after }
  } finally {
    if (liveAccountId) {
      await context.db.query(`
        DELETE FROM core.trading_accounts
        WHERE id = '${liveAccountId}'
          AND account_type = 'LIVE'
          AND balance = 0
          AND used_margin = 0;
      `)
      scope.fixtureActions.push({
        action: 'delete-zero-balance-live-sentinel',
        accountId: liveAccountId
      })
    }
  }
}

async function runSpotMarketLifecycle(scope) {
  const { context, page } = scope
  const { market, fixedAuthority } = await prepareSpotAuthorityMarket(
    scope,
    'BTCUSDT',
    'SPOT-01'
  )
  const rules = rulesFor(market)
  await context.ui.openTradePanel(page, {
    product: 'spot',
    symbol: 'BTCUSDT'
  })
  const beforeBuy = await context.api.snapshotAccount(page)
  const buy = await context.ui.submitOrderViaUi(page, {
    side: 'BUY',
    orderType: 'MARKET',
    amount: '1000'
  })
  scope.addMutation('market-buy-via-ui', buy)
  const bought = await waitForFilledMutation(scope, beforeBuy, buy, 'BTCUSDT')
  const buyFill = assertSingleFullFillMutation(beforeBuy, bought, 'SPOT-01 BUY')
  const buyPricing = marketFillOracle({
    productType: 'CRYPTO_SPOT',
    side: 'BUY',
    bid: quoteDecimal(market, 'bid'),
    ask: quoteDecimal(market, 'ask')
  })
  assertDecimalClose(
    bought.trade.price,
    buyPricing.filledPrice,
    tolerancesFromRules(rules).price,
    'SPOT-01 BUY fill'
  )
  assert.equal(bought.trade.feeAsset, 'BTC')
  assert.equal(bought.trade.liquidityRole, 'TAKER')
  const buyOracle = spotBuyOracle({
    quoteBudget: '1000',
    fillPrice: String(bought.trade.price),
    rules
  })
  assertDecimalClose(
    bought.trade.lots,
    buyOracle.grossBase,
    buyOracle.tolerances.quantity,
    'SPOT-01 BUY gross base'
  )
  assertDecimalClose(
    bought.trade.fee,
    buyOracle.baseFee,
    buyOracle.tolerances.amount,
    'SPOT-01 BUY base fee'
  )
  const boughtPosition = bought.snapshot.positions.find((position) => (
    isSpotPosition(position) && position.symbol === 'BTCUSDT'
  ))
  assert(boughtPosition, 'SPOT-01 bought Spot position')
  assertDecimalClose(
    boughtPosition.lots,
    buyOracle.netBase,
    buyOracle.tolerances.quantity,
    'SPOT-01 bought net base'
  )
  assertDecimalClose(
    boughtPosition.openPrice,
    buyOracle.averageCost,
    buyOracle.tolerances.price,
    'SPOT-01 bought average cost'
  )
  assertDecimalClose(
    boughtPosition.realizedPnl,
    '0',
    buyOracle.tolerances.amount,
    'SPOT-01 bought realized PnL'
  )
  const buyWallets = assertSpotWalletDelta(
    beforeBuy,
    bought.snapshot,
    [
      {
        asset: 'USDT',
        total: negativeAmount(buyOracle.quoteSpent),
        available: negativeAmount(buyOracle.quoteSpent),
        locked: '0'
      },
      {
        asset: 'BTC',
        total: buyOracle.netBase,
        available: buyOracle.netBase,
        locked: '0'
      }
    ],
    rules,
    'SPOT-01 BUY'
  )
  const buyLedger = assertSpotTradeLedger(
    bought.snapshot,
    bought.trade,
    rules,
    'SPOT-01 BUY'
  )
  await scope.capture('bought', bought.snapshot)

  const boughtWallet = findWallet(bought.snapshot, 'SPOT', 'BTC')
  const partialQuantity = floorFraction(
    String(boughtWallet.available),
    3,
    10,
    rules
  )
  assert(Number(partialQuantity) > 0, 'SPOT-01 30% sell quantity is required')
  const partialMarket = await context.api.snapshotMarket('BTCUSDT')
  const partialSell = await context.ui.submitOrderViaUi(page, {
    side: 'SELL',
    orderType: 'MARKET',
    amount: partialQuantity
  })
  scope.addMutation('market-sell-30-percent-via-ui', partialSell)
  const partiallySold = await waitForFilledMutation(
    scope,
    bought.snapshot,
    partialSell,
    'BTCUSDT'
  )
  const partialFill = assertSingleFullFillMutation(
    bought.snapshot,
    partiallySold,
    'SPOT-01 partial SELL'
  )
  const partialPricing = marketFillOracle({
    productType: 'CRYPTO_SPOT',
    side: 'SELL',
    bid: quoteDecimal(partialMarket, 'bid'),
    ask: quoteDecimal(partialMarket, 'ask')
  })
  assertDecimalClose(
    partiallySold.trade.price,
    partialPricing.filledPrice,
    tolerancesFromRules(rules).price,
    'SPOT-01 partial SELL fill'
  )
  assert.equal(partiallySold.trade.liquidityRole, 'TAKER')
  const partialOracle = spotSellOracle({
    soldBase: String(partiallySold.trade.lots),
    fillPrice: String(partiallySold.trade.price),
    averageCost: buyOracle.averageCost,
    rules
  })
  const partialSpotPosition = partiallySold.snapshot.positions.find((position) => (
    isSpotPosition(position) && position.symbol === 'BTCUSDT'
  ))
  assert(partialSpotPosition, 'SPOT-01 partial Spot position')
  assert.equal(partiallySold.trade.feeAsset, 'USDT')
  assertDecimalClose(
    partiallySold.trade.fee,
    partialOracle.quoteFee,
    partialOracle.tolerances.amount,
    'SPOT-01 partial SELL fee'
  )
  assertDecimalClose(
    partialSpotPosition.openPrice,
    boughtPosition.openPrice,
    partialOracle.tolerances.price,
    'SPOT-01 partial average cost retention'
  )
  assertDecimalClose(
    partialSpotPosition.realizedPnl,
    partialOracle.realizedPnl,
    partialOracle.tolerances.amount,
    'SPOT-01 partial realized PnL'
  )
  const partialWallets = assertSpotWalletDelta(
    bought.snapshot,
    partiallySold.snapshot,
    [
      {
        asset: 'USDT',
        total: partialOracle.netQuote,
        available: partialOracle.netQuote,
        locked: '0'
      },
      {
        asset: 'BTC',
        total: negativeAmount(partiallySold.trade.lots),
        available: negativeAmount(partiallySold.trade.lots),
        locked: '0'
      }
    ],
    rules,
    'SPOT-01 partial SELL'
  )
  const partialSellLedger = assertSpotTradeLedger(
    partiallySold.snapshot,
    partiallySold.trade,
    rules,
    'SPOT-01 partial SELL'
  )
  await scope.capture('partially-sold', partiallySold.snapshot)

  const remainingWallet = findWallet(partiallySold.snapshot, 'SPOT', 'BTC')
  const remainingQuantity = floorToStep(
    String(remainingWallet.available),
    effectiveQuantityStep(rules)
  )
  assert(Number(remainingQuantity) > 0, 'SPOT-01 remaining sell quantity is required')
  const finalMarket = await context.api.snapshotMarket('BTCUSDT')
  const fullSell = await context.ui.submitOrderViaUi(page, {
    side: 'SELL',
    orderType: 'MARKET',
    amount: remainingQuantity
  })
  scope.addMutation('market-sell-remaining-via-ui', fullSell)
  const fullySold = await waitForFilledMutation(
    scope,
    partiallySold.snapshot,
    fullSell,
    'BTCUSDT'
  )
  const finalFill = assertSingleFullFillMutation(
    partiallySold.snapshot,
    fullySold,
    'SPOT-01 final SELL'
  )
  const finalPricing = marketFillOracle({
    productType: 'CRYPTO_SPOT',
    side: 'SELL',
    bid: quoteDecimal(finalMarket, 'bid'),
    ask: quoteDecimal(finalMarket, 'ask')
  })
  assertDecimalClose(
    fullySold.trade.price,
    finalPricing.filledPrice,
    tolerancesFromRules(rules).price,
    'SPOT-01 final SELL fill'
  )
  assert.equal(fullySold.trade.liquidityRole, 'TAKER')
  assert.equal(fullySold.trade.feeAsset, 'USDT')
  const finalOracle = spotSellOracle({
    soldBase: String(fullySold.trade.lots),
    fillPrice: String(fullySold.trade.price),
    averageCost: buyOracle.averageCost,
    rules
  })
  assertDecimalClose(
    fullySold.trade.fee,
    finalOracle.quoteFee,
    finalOracle.tolerances.amount,
    'SPOT-01 final SELL fee'
  )
  const finalWallets = assertSpotWalletDelta(
    partiallySold.snapshot,
    fullySold.snapshot,
    [
      {
        asset: 'USDT',
        total: finalOracle.netQuote,
        available: finalOracle.netQuote,
        locked: '0'
      },
      {
        asset: 'BTC',
        total: negativeAmount(fullySold.trade.lots),
        available: negativeAmount(fullySold.trade.lots),
        locked: '0'
      }
    ],
    rules,
    'SPOT-01 final SELL'
  )
  const finalSellLedger = assertSpotTradeLedger(
    fullySold.snapshot,
    fullySold.trade,
    rules,
    'SPOT-01 final SELL'
  )
  const finalDust = assertSpotDust(
    fullySold.snapshot,
    finalMarket,
    'BTC',
    'SPOT-01 final BTC'
  )
  const finalSpotPosition = [
    ...fullySold.snapshot.positions,
    ...(fullySold.snapshot.positionHistory ?? [])
  ].find((position) => (
    isSpotPosition(position) && position.symbol === 'BTCUSDT'
  ))
  assert(finalSpotPosition, 'SPOT-01 final Spot position')
  const cumulativeRealizedPnl = Number(partialOracle.realizedPnl)
    + Number(finalOracle.realizedPnl)
  assertDecimalClose(
    finalSpotPosition.realizedPnl,
    cumulativeRealizedPnl,
    finalOracle.tolerances.amount,
    'SPOT-01 final cumulative realized PnL'
  )
  if (Number(finalSpotPosition.lots) === 0) {
    assertNear(finalSpotPosition.openPrice, 0, 'SPOT-01 final average cost')
    assertNear(finalSpotPosition.floatingPnl, 0, 'SPOT-01 final UPL')
  } else {
    assertDecimalClose(
      finalSpotPosition.openPrice,
      boughtPosition.openPrice,
      finalOracle.tolerances.price,
      'SPOT-01 dust average cost retention'
    )
  }
  assertNear(
    fullySold.snapshot.summary.balance,
    scope.before.summary.balance,
    'SPOT-01 Perp balance isolation'
  )
  scope.oracleEvidence.push({
    kind: 'SPOT_MARKET_LIFECYCLE',
    fixedAuthority,
    pricing: {
      buy: buyPricing,
      partialSell: partialPricing,
      finalSell: finalPricing
    },
    buy: buyOracle,
    partialSell: {
      ...partialOracle,
      fullFill: partialFill,
      wallets: partialWallets
    },
    finalSell: {
      ...finalOracle,
      fullFill: finalFill,
      wallets: finalWallets
    },
    fullFill: {
      buy: buyFill,
      partialSell: partialFill,
      finalSell: finalFill
    },
    walletDeltas: {
      buy: buyWallets,
      partialSell: partialWallets,
      finalSell: finalWallets
    },
    ledger: {
      buy: buyLedger,
      partialSell: partialSellLedger,
      finalSell: finalSellLedger
    },
    finalDust,
    wallets: fullySold.snapshot.wallets.map((wallet) => ({
      walletType: wallet.walletType,
      asset: wallet.asset,
      ...assertWalletInvariant(wallet, rules, `SPOT-01 ${wallet.asset}`)
    }))
  })
  const finalCapture = await scope.capture('fully-sold', fullySold.snapshot)
  const finalSpotRow = (finalCapture.db.spotPositionRows ?? []).find(({ asset }) => (
    asset === 'BTC'
  ))
  assert(finalSpotRow, 'SPOT-01 final DB Spot position')
  const cumulativeFeeCost = Number(buyOracle.baseFee) * Number(bought.trade.price)
    + Number(partialOracle.quoteFee)
    + Number(finalOracle.quoteFee)
  assertDecimalClose(
    finalSpotRow.realized_pnl,
    cumulativeRealizedPnl,
    finalOracle.tolerances.amount,
    'SPOT-01 final DB cumulative realized PnL'
  )
  assertDecimalClose(
    finalSpotRow.fee_cost,
    cumulativeFeeCost,
    finalOracle.tolerances.amount,
    'SPOT-01 final DB cumulative fee cost'
  )
  return {
    finalSnapshot: fullySold.snapshot,
    finalDb: finalCapture.db
  }
}

async function runPerpLifecycle(scope, options) {
  const { context, definition, page } = scope
  const symbol = 'BTCUSDT-PERP'
  const market = await context.api.snapshotMarket(symbol)
  const rules = rulesFor(market)
  const quantity = stepAlignedQuantity(market, 0.01)
  const partialQuantity = floorFraction(quantity, 3, 10, rules)
  assert(Number(partialQuantity) > 0, `${definition.id} partial quantity`)
  await context.ui.openTradePanel(page, {
    product: 'perpetual',
    symbol
  })
  await ensurePerpSettings(scope, {
    positionMode: 'ONE_WAY',
    marginMode: options.marginMode,
    leverage: options.leverage,
    quantityUnit: 'BASE'
  })
  const beforeOpen = await context.api.snapshotAccount(page)
  const opening = await context.ui.submitOrderViaUi(page, {
    side: options.side,
    orderType: 'MARKET',
    amount: quantity
  })
  scope.addMutation('perpetual-market-open-via-ui', opening)
  const opened = await waitForFilledMutation(
    scope,
    beforeOpen,
    opening,
    symbol
  )
  const position = openPositions(opened.snapshot, symbol).at(0)
  assert(position, `${definition.id} opening position`)
  assert.equal(position.positionMode, 'ONE_WAY')
  assert.equal(position.positionSide, 'BOTH')
  assert.equal(position.marginMode, options.marginMode)
  assert.equal(Number(position.leverage), options.leverage)
  assertDecimalClose(
    position.lots,
    quantity,
    tolerancesFromRules(rules).quantity,
    `${definition.id} opening quantity`
  )
  const openingPricing = marketFillOracle({
    productType: 'LINEAR_PERP',
    side: options.side,
    bid: quoteDecimal(market, 'bid'),
    ask: quoteDecimal(market, 'ask')
  })
  assertDecimalClose(
    opened.trade.price,
    openingPricing.filledPrice,
    tolerancesFromRules(rules).price,
    `${definition.id} opening fill`
  )
  assert.equal(opened.trade.liquidityRole, 'TAKER')
  const positionSide = options.side === 'BUY' ? 'LONG' : 'SHORT'
  const openingHold = perpOpeningHoldOracle({
    baseQuantity: String(position.lots),
    worstPrice: String(opened.trade.price),
    leverage: String(position.leverage),
    rules
  })
  assertDecimalClose(
    opened.trade.fee,
    openingHold.feeBuffer,
    openingHold.tolerances.amount,
    `${definition.id} opening fee`
  )
  assertDecimalClose(
    position.marginHeld,
    openingHold.openingInitialMargin,
    openingHold.tolerances.amount,
    `${definition.id} opening margin held`
  )
  assertDecimalClose(
    position.realizedPnl,
    '0',
    openingHold.tolerances.amount,
    `${definition.id} opening realized PnL`
  )
  const positionOracle = perpPositionOracle({
    side: positionSide,
    quantity: String(position.lots),
    entryPrice: String(position.openPrice),
    markPrice: String(position.markPrice),
    leverage: String(position.leverage),
    positionMargin: String(position.marginHeld),
    maintenanceMarginRate: String(
      position.maintenanceMarginRate
        ?? rules.maintenanceMarginRate
        ?? '0.005'
    ),
    rules
  })
  assertDecimalClose(
    position.floatingPnl,
    positionOracle.unrealizedPnl,
    positionOracle.tolerances.amount,
    `${definition.id} opening UPL`
  )
  assertDecimalClose(
    position.maintenanceMargin,
    positionOracle.maintenanceMargin,
    positionOracle.tolerances.amount,
    `${definition.id} opening maintenance`
  )
  assertDecimalClose(
    opened.snapshot.summary.usedMargin,
    position.marginHeld,
    positionOracle.tolerances.amount,
    `${definition.id} opening used margin`
  )
  assertPerpAccountSummary(
    opened.snapshot.summary,
    {
      balance: Number(beforeOpen.summary.balance) - Number(openingHold.feeBuffer),
      openFloatingPnl: positionOracle.unrealizedPnl,
      usedMargin: openingHold.openingInitialMargin,
      maintenanceMargin: positionOracle.maintenanceMargin,
      marginMode: options.marginMode
    },
    `${definition.id} opening summary`
  )
  assert.equal(
    (opened.snapshot.positionHistory ?? []).length,
    (beforeOpen.positionHistory ?? []).length,
    `${definition.id} opening must not create closed history`
  )
  const openedCapture = await scope.capture('opened', opened.snapshot)
  const openingRow = (openedCapture.db.positionRows ?? []).find(({ id }) => (
    id === position.id
  ))
  assert(openingRow, `${definition.id} opening DB position`)
  assertDecimalClose(
    openingRow.initial_margin,
    openingHold.openingInitialMargin,
    openingHold.tolerances.amount,
    `${definition.id} opening DB initial margin`
  )
  assertDecimalClose(
    openingRow.margin_held,
    openingHold.openingInitialMargin,
    openingHold.tolerances.amount,
    `${definition.id} opening DB margin held`
  )
  const openingLedger = assertPerpLedgerDelta(
    scope.beforeDb,
    openedCapture.db,
    [
      {
        operationType: 'MARGIN_HOLD',
        referenceType: 'POSITION',
        referenceId: position.id,
        amount: openingHold.openingInitialMargin
      },
      {
        operationType: 'TRADE_FEE',
        referenceType: 'TRADE',
        referenceId: opened.trade.id,
        amount: negativeAmount(openingHold.feeBuffer)
      }
    ],
    `${definition.id} opening`
  )

  const baselineProjection = projectedNetOracle({
    side: positionSide,
    quantity: String(position.lots),
    entryPrice: String(position.openPrice),
    closingBid: quoteDecimal(market, 'bid'),
    closingAsk: quoteDecimal(market, 'ask'),
    executionPath: 'MARKET',
    openingFee: String(opened.trade.fee ?? 0),
    rules
  })
  let ledgerBaselineDb = openedCapture.db
  const authorityTargets = []
  if (definition.requiredSubruns.some(({ id }) => id.includes('target-mark'))) {
    const direction = positionSide === 'LONG' ? 1 : -1
    const target = Number(position.openPrice) * (1 + direction * 0.01)
    const targetMarket = await applyAuthorityMark(scope, symbol, target)
    const refreshed = await waitForAccount(
      context,
      page,
      `${definition.id} target mark`,
      (candidate) => {
        const current = openPositions(candidate, symbol).at(0)
        return current && Math.abs(Number(current.markPrice) - target) <= (
          Number(targetMarket.ask) - Number(targetMarket.bid)
        ) ? { snapshot: candidate, position: current } : false
      }
    )
    const targetBundle = await context.api.snapshotMarket(symbol)
    const t1Risk = assertPerpTargetRisk(
      refreshed.position,
      refreshed.snapshot.summary,
      targetBundle,
      String(opened.trade.fee ?? 0),
      rules,
      `${definition.id} T1`
    )
    assert(
      Number(t1Risk.position.unrealizedPnl) > 0
        && Number(t1Risk.projection.projectedGrossPnl) > 0,
      `${definition.id} T1 must prove favorable UPL and projected gross PnL`
    )
    const targetCapture = await scope.capture('target-mark-t1', refreshed.snapshot)
    ledgerBaselineDb = targetCapture.db
    authorityTargets.push({ id: 'T1', target, ...t1Risk })
    scope.snapshots['target-mark'] = safeAccountEvidence(refreshed.snapshot)
  }

  const partial = await closePositionFromUi(scope, position, {
    quantity: partialQuantity,
    quantityUnit: 'BASE'
  })
  const partiallyClosed = await waitForAccount(
    context,
    page,
    `${definition.id} partial close`,
    (candidate) => {
      const current = openPositions(candidate, symbol).find(({ id }) => (
        id === position.id
      ))
      const trade = recordsAfter(opened.snapshot, candidate, 'trades').at(-1)
      if (!current || !trade) return false
      const expectedRemaining = Number(quantity) - Number(partialQuantity)
      return Math.abs(Number(current.lots) - expectedRemaining) <= (
        Number(tolerancesFromRules(rules).quantity)
      ) ? { snapshot: candidate, position: current, trade } : false
    }
  )
  const partialOracle = partialCloseOracle({
    side: positionSide,
    originalQuantity: String(position.lots),
    oldMargin: String(position.marginHeld),
    entryPrice: String(position.openPrice),
    closeFillPrice: String(partiallyClosed.trade.price),
    previousPositionRealizedPnl: String(position.realizedPnl ?? 0),
    rules
  })
  assertDecimalClose(
    partiallyClosed.position.lots,
    partialOracle.remainingQuantity,
    partialOracle.tolerances.quantity,
    `${definition.id} partial remaining quantity`
  )
  assertDecimalClose(
    partiallyClosed.position.openPrice,
    position.openPrice,
    partialOracle.tolerances.price,
    `${definition.id} partial entry retention`
  )
  assertDecimalClose(
    partiallyClosed.trade.realizedPnl,
    partialOracle.tradeRealizedPnl,
    partialOracle.tolerances.amount,
    `${definition.id} partial realized PnL`
  )
  assertDecimalClose(
    partiallyClosed.trade.fee,
    partialOracle.closeFee,
    partialOracle.tolerances.amount,
    `${definition.id} partial close fee`
  )
  assertDecimalClose(
    partiallyClosed.position.marginHeld,
    partialOracle.remainingMargin,
    partialOracle.tolerances.amount,
    `${definition.id} partial remaining margin`
  )
  assertDecimalClose(
    partiallyClosed.position.realizedPnl,
    partialOracle.positionRealizedPnl,
    partialOracle.tolerances.amount,
    `${definition.id} partial cumulative realized PnL`
  )
  assertPerpAccountSummary(
    partiallyClosed.snapshot.summary,
    {
      balance: Number(opened.snapshot.summary.balance)
        + Number(partialOracle.tradeRealizedPnl)
        - Number(partialOracle.closeFee),
      openFloatingPnl: partiallyClosed.position.floatingPnl,
      usedMargin: partialOracle.remainingMargin,
      maintenanceMargin: partiallyClosed.position.maintenanceMargin,
      marginMode: options.marginMode
    },
    `${definition.id} partial summary`
  )
  assert.equal(
    (partiallyClosed.snapshot.positionHistory ?? []).length,
    (beforeOpen.positionHistory ?? []).length,
    `${definition.id} partial close must not create history`
  )
  const partialCapture = await scope.capture(
    'partially-closed',
    partiallyClosed.snapshot
  )
  const partialLedger = assertPerpLedgerDelta(
    ledgerBaselineDb,
    partialCapture.db,
    [
      {
        operationType: 'MARGIN_RELEASE',
        referenceType: 'POSITION',
        referenceId: position.id,
        amount: partialOracle.releasedMargin
      },
      {
        operationType: 'TRADE_FEE',
        referenceType: 'TRADE',
        referenceId: partiallyClosed.trade.id,
        amount: negativeAmount(partialOracle.closeFee)
      }
    ],
    `${definition.id} partial close`
  )
  ledgerBaselineDb = partialCapture.db

  let currentPosition = partiallyClosed.position
  let marginLedger = null
  if (options.adjustMargin) {
    const beforeMargin = partiallyClosed.snapshot
    const beforeMarginPosition = currentPosition
    const marginCapture = await context.ui.positionActionViaUi(page, {
      positionId: currentPosition.id,
      positionSide: currentPosition.positionSide,
      action: 'ADJUST_MARGIN',
      marginDirection: 'ADD',
      marginAmount: '10'
    })
    scope.addMutation('isolated-margin-add-via-ui', marginCapture)
    const adjusted = await waitForAccount(
      context,
      page,
      `${definition.id} isolated margin add`,
      (candidate) => {
        const next = openPositions(candidate, symbol).find(({ id }) => (
          id === currentPosition.id
        ))
        return next && Number(next.marginHeld) >= Number(currentPosition.marginHeld) + 10
          ? { snapshot: candidate, position: next }
          : false
      }
    )
    assertDecimalClose(
      adjusted.snapshot.summary.balance,
      beforeMargin.summary.balance,
      '0.00000001',
      `${definition.id} margin add balance`
    )
    assertDecimalClose(
      adjusted.snapshot.summary.equity,
      beforeMargin.summary.equity,
      '0.00000001',
      `${definition.id} margin add equity`
    )
    assertDecimalClose(
      adjusted.position.lots,
      currentPosition.lots,
      tolerancesFromRules(rules).quantity,
      `${definition.id} margin add quantity`
    )
    assertDecimalClose(
      adjusted.position.openPrice,
      beforeMarginPosition.openPrice,
      tolerancesFromRules(rules).price,
      `${definition.id} margin add entry`
    )
    assertDecimalClose(
      adjusted.position.realizedPnl,
      beforeMarginPosition.realizedPnl,
      tolerancesFromRules(rules).amount,
      `${definition.id} margin add realized PnL`
    )
    assertDecimalClose(
      adjusted.position.marginHeld,
      Number(beforeMarginPosition.marginHeld) + 10,
      tolerancesFromRules(rules).amount,
      `${definition.id} margin add held margin`
    )
    const beforeLiquidation = Number(beforeMarginPosition.liquidationPrice)
    const adjustedLiquidation = Number(adjusted.position.liquidationPrice)
    assert(
      Number.isFinite(beforeLiquidation) && Number.isFinite(adjustedLiquidation),
      `${definition.id} margin add liquidation prices`
    )
    assert(
      positionSide === 'SHORT'
        ? adjustedLiquidation > beforeLiquidation
        : adjustedLiquidation < beforeLiquidation,
      `${definition.id} margin add must move liquidation in the safer direction`
    )
    assertPerpAccountSummary(
      adjusted.snapshot.summary,
      {
        balance: beforeMargin.summary.balance,
        openFloatingPnl: adjusted.position.floatingPnl,
        usedMargin: adjusted.position.marginHeld,
        maintenanceMargin: adjusted.position.maintenanceMargin,
        marginMode: options.marginMode
      },
      `${definition.id} margin-adjusted summary`
    )
    currentPosition = adjusted.position
    const adjustedCapture = await scope.capture(
      'margin-adjusted',
      adjusted.snapshot
    )
    marginLedger = assertPerpLedgerDelta(
      ledgerBaselineDb,
      adjustedCapture.db,
      [{
        operationType: 'MARGIN_HOLD',
        referenceType: 'POSITION',
        referenceId: currentPosition.id,
        amount: '10'
      }],
      `${definition.id} margin add`
    )
    ledgerBaselineDb = adjustedCapture.db
  }

  if (definition.requiredSubruns.some(({ id }) => id.includes('target-mark'))) {
    const direction = positionSide === 'LONG' ? 1 : -1
    const reverseTarget = Number(currentPosition.openPrice) * (1 - direction * 0.01)
    const reverseMarket = await applyAuthorityMark(scope, symbol, reverseTarget)
    const refreshed = await waitForAccount(
      context,
      page,
      `${definition.id} reverse target mark`,
      (candidate) => {
        const current = openPositions(candidate, symbol).find(({ id }) => (
          id === currentPosition.id
        ))
        return current && Math.abs(Number(current.markPrice) - reverseTarget) <= (
          Number(reverseMarket.ask) - Number(reverseMarket.bid)
        ) ? { snapshot: candidate, position: current } : false
      }
    )
    const reverseBundle = await context.api.snapshotMarket(symbol)
    const t2Risk = assertPerpTargetRisk(
      refreshed.position,
      refreshed.snapshot.summary,
      reverseBundle,
      '0',
      rules,
      `${definition.id} T2`
    )
    assert(
      Number(t2Risk.position.unrealizedPnl) < 0
        && Number(t2Risk.projection.projectedGrossPnl) < 0,
      `${definition.id} T2 must prove adverse UPL and projected gross PnL`
    )
    currentPosition = refreshed.position
    const reverseCapture = await scope.capture(
      'target-mark-t2',
      refreshed.snapshot
    )
    ledgerBaselineDb = reverseCapture.db
    authorityTargets.push({ id: 'T2', target: reverseTarget, ...t2Risk })
  }

  const beforeFull = await context.api.snapshotAccount(page)
  const full = await closePositionFromUi(scope, currentPosition)
  const fullyClosed = await waitForAccount(
    context,
    page,
    `${definition.id} full close`,
    (candidate) => {
      if (openPositions(candidate, symbol).length > 0) return false
      const trade = recordsAfter(beforeFull, candidate, 'trades').at(-1)
      const history = (candidate.positionHistory ?? []).filter(({ id }) => (
        id === position.id
      ))
      return trade && history.length === 1
        ? { snapshot: candidate, trade, history: history[0] }
        : false
    }
  )
  const fullOracle = perpCloseOracle({
    side: positionSide,
    quantity: String(currentPosition.lots),
    entryPrice: String(currentPosition.openPrice),
    closeFillPrice: String(fullyClosed.trade.price),
    openingFee: '0',
    rules
  })
  assertDecimalClose(
    fullyClosed.trade.realizedPnl,
    fullOracle.grossRealizedPnl,
    fullOracle.tolerances.amount,
    `${definition.id} final realized PnL`
  )
  assertDecimalClose(
    fullyClosed.trade.fee,
    fullOracle.closeFee,
    fullOracle.tolerances.amount,
    `${definition.id} final close fee`
  )
  assert.equal(fullyClosed.history.status, 'CLOSED')
  assert.equal(fullyClosed.history.id, position.id)
  const cumulativeRealized = Number(partialOracle.tradeRealizedPnl)
    + Number(fullOracle.grossRealizedPnl)
  assertDecimalClose(
    fullyClosed.history.realizedPnl,
    cumulativeRealized,
    fullOracle.tolerances.amount,
    `${definition.id} history cumulative realized PnL`
  )
  assertDecimalClose(
    fullyClosed.history.fundingPnl ?? '0',
    '0',
    fullOracle.tolerances.amount,
    `${definition.id} funding cashflow`
  )
  assert.equal(
    (fullyClosed.snapshot.fundingSettlements ?? []).length,
    (beforeOpen.fundingSettlements ?? []).length,
    `${definition.id} lifecycle funding settlements`
  )
  const lifecycleTrades = fullyClosed.snapshot.trades.filter(({ symbol: value }) => (
    value === symbol
  ))
  assert.equal(lifecycleTrades.length, 3, `${definition.id} exact lifecycle trades`)
  assert.equal(
    new Set(lifecycleTrades.map(({ id }) => id)).size,
    3,
    `${definition.id} unique lifecycle trade ids`
  )
  assert.equal(
    new Set(lifecycleTrades.map(({ orderId }) => orderId)).size,
    3,
    `${definition.id} one Trade per fill`
  )
  assert.deepEqual(
    new Set(lifecycleTrades.map(({ id }) => id)),
    new Set([opened.trade.id, partiallyClosed.trade.id, fullyClosed.trade.id]),
    `${definition.id} lifecycle Trade identities`
  )
  const finalBalanceDelta = cumulativeRealized
    - Number(openingHold.feeBuffer)
    - Number(partialOracle.closeFee)
    - Number(fullOracle.closeFee)
  assertDecimalClose(
    Number(fullyClosed.snapshot.summary.balance) - Number(beforeOpen.summary.balance),
    finalBalanceDelta,
    fullOracle.tolerances.amount,
    `${definition.id} final balance delta`
  )
  assertNear(
    fullyClosed.snapshot.summary.usedMargin,
    0,
    `${definition.id} final used margin`
  )
  assertDecimalClose(
    fullyClosed.snapshot.summary.freeMargin,
    fullyClosed.snapshot.summary.equity,
    '0.00000001',
    `${definition.id} final free margin`
  )
  assertDecimalClose(
    fullyClosed.snapshot.summary.equity,
    fullyClosed.snapshot.summary.balance,
    '0.00000001',
    `${definition.id} final equity`
  )
  assertDecimalClose(
    fullyClosed.snapshot.summary.openFloatingPnl,
    '0',
    '0.00000001',
    `${definition.id} final open UPL`
  )
  const finalCapture = await scope.capture('fully-closed', fullyClosed.snapshot)
  const finalLedger = assertPerpLedgerDelta(
    ledgerBaselineDb,
    finalCapture.db,
    [
      {
        operationType: 'MARGIN_RELEASE',
        referenceType: 'POSITION',
        referenceId: position.id,
        amount: currentPosition.marginHeld
      },
      {
        operationType: 'TRADE_FEE',
        referenceType: 'TRADE',
        referenceId: fullyClosed.trade.id,
        amount: negativeAmount(fullOracle.closeFee)
      }
    ],
    `${definition.id} final close`
  )
  scope.oracleEvidence.push({
    kind: 'PERP_MARKET_LIFECYCLE',
    symbol,
    side: positionSide,
    leverage: options.leverage,
    marginMode: options.marginMode,
    opening: {
      pricing: openingPricing,
      hold: openingHold,
      position: positionOracle,
      projected: baselineProjection,
      ledger: openingLedger
    },
    authorityTargets,
    partial: {
      ...partialOracle,
      ledger: partialLedger
    },
    marginAdjustment: options.adjustMargin
      ? { amount: '10', ledger: marginLedger }
      : null,
    final: {
      ...fullOracle,
      cumulativeRealized,
      finalBalanceDelta,
      ledger: finalLedger
    },
    ids: {
      positionId: position.id,
      openingOrderId: opened.order.id,
      openingTradeId: opened.trade.id,
      partialRequestRef: partial.requestRef,
      fullRequestRef: full.requestRef
    }
  })
  return {
    finalSnapshot: fullyClosed.snapshot,
    finalDb: finalCapture.db
  }
}

function assertPerpAccountSummary(summary, expected, label) {
  const equity = Number(expected.balance) + Number(expected.openFloatingPnl)
  const freeMargin = (expected.marginMode === 'ISOLATED'
    ? Number(expected.balance)
    : equity) - Number(expected.usedMargin)
  for (const [field, value] of [
    ['balance', expected.balance],
    ['openFloatingPnl', expected.openFloatingPnl],
    ['equity', equity],
    ['usedMargin', expected.usedMargin],
    ['freeMargin', freeMargin],
    ['maintenanceMargin', expected.maintenanceMargin]
  ]) {
    assertDecimalClose(summary[field], value, '0.00000001', `${label} ${field}`)
  }
  return { equity, freeMargin }
}

function assertPerpTargetRisk(position, summary, market, openingFee, rules, label) {
  const side = position.side === 'BUY' ? 'LONG' : 'SHORT'
  const mark = String(market.reference?.mark ?? market.quote?.markPrice)
  assertDecimalClose(
    position.markPrice,
    mark,
    tolerancesFromRules(rules).price,
    `${label} authority mark`
  )
  const positionRisk = perpPositionOracle({
    side,
    quantity: String(position.lots),
    entryPrice: String(position.openPrice),
    markPrice: String(position.markPrice),
    leverage: String(position.leverage),
    positionMargin: String(position.marginHeld),
    maintenanceMarginRate: String(
      position.maintenanceMarginRate
        ?? rules.maintenanceMarginRate
        ?? '0.005'
    ),
    rules
  })
  assertDecimalClose(
    position.floatingPnl,
    positionRisk.unrealizedPnl,
    positionRisk.tolerances.amount,
    `${label} UPL`
  )
  assertPerpAccountSummary(
    summary,
    {
      balance: summary.balance,
      openFloatingPnl: positionRisk.unrealizedPnl,
      usedMargin: position.marginHeld,
      maintenanceMargin: positionRisk.maintenanceMargin,
      marginMode: position.marginMode
    },
    `${label} summary`
  )
  const projection = projectedNetOracle({
    side,
    quantity: String(position.lots),
    entryPrice: String(position.openPrice),
    closingBid: quoteDecimal(market, 'bid'),
    closingAsk: quoteDecimal(market, 'ask'),
    executionPath: 'MARKET',
    openingFee,
    rules
  })
  const closingPricing = marketFillOracle({
    productType: 'LINEAR_PERP',
    side: side === 'LONG' ? 'SELL' : 'BUY',
    bid: quoteDecimal(market, 'bid'),
    ask: quoteDecimal(market, 'ask')
  })
  assertDecimalClose(
    projection.projectedCloseFill,
    closingPricing.filledPrice,
    projection.tolerances.price,
    `${label} projected close fill`
  )
  const projectedClose = perpCloseOracle({
    side,
    quantity: String(position.lots),
    entryPrice: String(position.openPrice),
    closeFillPrice: projection.projectedCloseFill,
    rules
  })
  assertDecimalClose(
    projection.projectedGrossPnl,
    projectedClose.grossRealizedPnl,
    projection.tolerances.amount,
    `${label} projected gross PnL`
  )
  assertDecimalClose(
    projection.projectedCloseFee,
    projectedClose.closeFee,
    projection.tolerances.amount,
    `${label} projected close fee`
  )
  assertDecimalClose(
    projection.projectedNetFromNow,
    Number(projectedClose.grossRealizedPnl) - Number(projectedClose.closeFee),
    projection.tolerances.amount,
    `${label} projected net from now`
  )
  assertDecimalClose(
    projection.projectedWholeTradeNet,
    Number(projection.projectedNetFromNow) - Number(openingFee),
    projection.tolerances.amount,
    `${label} projected whole-trade net`
  )
  return { position: positionRisk, projection }
}

function assertPerpPositionFinancials(snapshot, positions, rules, label) {
  assert(Array.isArray(positions) && positions.length > 0, `${label} positions`)
  const financials = positions.map((position) => {
    const side = ['BUY', 'LONG'].includes(position.side) ? 'LONG' : 'SHORT'
    const risk = perpPositionOracle({
      side,
      quantity: String(position.lots),
      entryPrice: String(position.openPrice),
      markPrice: String(position.markPrice),
      leverage: String(position.leverage),
      positionMargin: String(position.marginHeld),
      maintenanceMarginRate: String(
        position.maintenanceMarginRate
          ?? rules.maintenanceMarginRate
          ?? '0.005'
      ),
      rules
    })
    assertDecimalClose(
      position.floatingPnl,
      risk.unrealizedPnl,
      risk.tolerances.amount,
      `${label} ${position.id} UPL`
    )
    assertDecimalClose(
      position.maintenanceMargin,
      risk.maintenanceMargin,
      risk.tolerances.amount,
      `${label} ${position.id} maintenance margin`
    )
    assertDecimalClose(
      position.initialMargin,
      risk.initialMargin,
      risk.tolerances.amount,
      `${label} ${position.id} initial margin`
    )
    return { position, risk }
  })
  const usedMargin = financials.reduce(
    (total, { position }) => total + Number(position.marginHeld),
    0
  )
  const openFloatingPnl = financials.reduce(
    (total, { risk }) => total + Number(risk.unrealizedPnl),
    0
  )
  const maintenanceMargin = financials.reduce(
    (total, { risk }) => total + Number(risk.maintenanceMargin),
    0
  )
  const marginMode = positions.every(({ marginMode: value }) => value === 'ISOLATED')
    ? 'ISOLATED'
    : 'CROSS'
  const account = assertPerpAccountSummary(
    snapshot.summary,
    {
      balance: snapshot.summary.balance,
      openFloatingPnl,
      usedMargin,
      maintenanceMargin,
      marginMode
    },
    `${label} account`
  )
  return { positions: financials, usedMargin, maintenanceMargin, account }
}

function assertPerpLedgerDelta(beforeDb, afterDb, expected, label) {
  const beforeIds = new Set((beforeDb.cashLedgerRows ?? []).map(({ id }) => id))
  const added = (afterDb.cashLedgerRows ?? []).filter(({ id }) => !beforeIds.has(id))
  const expectedTypes = new Set(expected.map(({ operationType }) => operationType))
  assert.equal(
    added.filter(({ operation_type: type }) => expectedTypes.has(type)).length,
    expected.length,
    `${label} exact relevant cash ledger rows`
  )
  return expected.map((entry) => {
    const matches = added.filter((candidate) => (
      candidate.operation_type === entry.operationType
        && candidate.reference_type === entry.referenceType
        && candidate.reference_id === entry.referenceId
    ))
    assert.equal(matches.length, 1, `${label} ${entry.operationType} ledger row`)
    assertDecimalClose(
      matches[0].amount,
      entry.amount,
      '0.00000001',
      `${label} ${entry.operationType} amount`
    )
    return {
      id: matches[0].id,
      operationType: entry.operationType,
      referenceType: entry.referenceType,
      referenceId: entry.referenceId,
      amount: matches[0].amount
    }
  })
}

function negativeAmount(value) {
  return String(value).startsWith('-') ? String(value) : `-${value}`
}

async function runPerpLeverageJourney(scope) {
  const { context, page } = scope
  const symbol = 'BTCUSDT-PERP'
  const market = await context.api.snapshotMarket(symbol)
  const rules = rulesFor(market)
  const quantity = stepAlignedQuantity(market, 0.01)
  await context.ui.openTradePanel(page, {
    product: 'perpetual',
    symbol
  })
  const settingEvidence = []
  for (const leverage of [1, 10, 50, 100]) {
    const captures = await ensurePerpSettings(scope, {
      positionMode: 'ONE_WAY',
      marginMode: 'CROSS',
      leverage,
      quantityUnit: 'BASE'
    })
    const snapshot = await context.api.snapshotAccount(page)
    assert.equal(Number(findSymbolSettings(snapshot, symbol).leverage), leverage)
    await page.send('Page.reload')
    await page.waitForFunction(
      () => window.location.pathname === '/trade/perpetual/BTCUSDT-PERP',
      `PERP-03 ${leverage}x reload`
    )
    const persisted = await context.api.snapshotAccount(page)
    assert.equal(Number(findSymbolSettings(persisted, symbol).leverage), leverage)
    settingEvidence.push({
      leverage,
      requestRefs: captures.map(({ requestRef }) => requestRef),
      version: findSymbolSettings(persisted, symbol).version
    })
  }

  await ensurePerpSettings(scope, { leverage: 50 })
  const clampedHigh = await setRawLeverageViaUi(scope, 101)
  assert.equal(Number(clampedHigh.payload.leverage), 100)
  scope.addMutation('clamped-100x-settings-via-ui', clampedHigh.capture)
  const clampedLow = await setRawLeverageViaUi(scope, 0)
  assert.equal(Number(clampedLow.payload.leverage), 1)
  scope.addMutation('clamped-1x-settings-via-ui', clampedLow.capture)
  scope.contractProbes.push(
    {
      status: 'PASS',
      kind: 'UI_LEVERAGE_CLAMP',
      input: 101,
      submitted: clampedHigh.payload.leverage,
      requestRef: clampedHigh.capture.requestRef
    },
    {
      status: 'PASS',
      kind: 'UI_LEVERAGE_CLAMP',
      input: 0,
      submitted: clampedLow.payload.leverage,
      requestRef: clampedLow.capture.requestRef
    }
  )
  const currentSettings = await context.api.snapshotAccount(page)
  const currentVersion = findSymbolSettings(currentSettings, symbol).version
  const illegal = await replayCapturedMutation(
    scope,
    clampedLow.capture,
    (body) => ({
      ...body,
      leverage: 101,
      expectedVersion: currentVersion
    }),
    {
      expectFailure: true,
      reason: 'PERP-03 leverage range guard'
    }
  )
  assertRejectedCode(
    illegal,
    'LEVERAGE_OUT_OF_RANGE',
    'PERP-03 leverage range guard'
  )
  scope.contractProbes.push({
    status: 'REJECTED',
    kind: 'L7_LEVERAGE_OUT_OF_RANGE',
    requestRef: illegal.requestRef,
    errorCode: responseCode(illegal)
  })
  assertTradingStateEqual(
    currentSettings,
    await context.api.snapshotAccount(page),
    'PERP-03 illegal leverage zero mutation'
  )

  await ensurePerpSettings(scope, { leverage: 50 })
  const beforeOpen = await context.api.snapshotAccount(page)
  const open = await context.ui.submitOrderViaUi(page, {
    side: 'BUY',
    orderType: 'MARKET',
    amount: quantity
  })
  scope.addMutation('perpetual-open-50x-via-ui', open)
  const opened = await waitForFilledMutation(scope, beforeOpen, open, symbol)
  let position = openPositions(opened.snapshot, symbol).at(0)
  assert(position, 'PERP-03 opening position')
  assertSingleFullFillMutation(beforeOpen, opened, 'PERP-03 opening')
  const openingHold = perpOpeningHoldOracle({
    baseQuantity: String(opened.trade.lots),
    worstPrice: String(opened.trade.price),
    leverage: '50',
    rules
  })
  assertDecimalClose(
    opened.trade.fee,
    openingHold.feeBuffer,
    openingHold.tolerances.amount,
    'PERP-03 opening fee'
  )
  assertDecimalClose(
    position.marginHeld,
    openingHold.openingInitialMargin,
    openingHold.tolerances.amount,
    'PERP-03 opening margin'
  )
  assertDecimalClose(
    opened.snapshot.summary.balance,
    Number(beforeOpen.summary.balance) - Number(openingHold.feeBuffer),
    openingHold.tolerances.amount,
    'PERP-03 opening balance'
  )
  const openingFinancials = assertPerpPositionFinancials(
    opened.snapshot,
    [position],
    rules,
    'PERP-03 opening'
  )
  const openedCapture = await scope.capture('opened-50x', opened.snapshot)
  const openingLedger = assertPerpLedgerDelta(
    scope.beforeDb,
    openedCapture.db,
    [
      {
        operationType: 'MARGIN_HOLD',
        referenceType: 'POSITION',
        referenceId: position.id,
        amount: openingHold.openingInitialMargin
      },
      {
        operationType: 'TRADE_FEE',
        referenceType: 'TRADE',
        referenceId: opened.trade.id,
        amount: negativeAmount(openingHold.feeBuffer)
      }
    ],
    'PERP-03 opening'
  )
  let ledgerBaselineDb = openedCapture.db
  const margins = [{
    leverage: 50,
    margin: String(position.marginHeld),
    financials: openingFinancials,
    ledger: openingLedger
  }]
  for (const leverage of [100, 10, 1, 50]) {
    const before = position
    await ensurePerpSettings(scope, { leverage })
    const updated = await waitForAccount(
      context,
      page,
      `PERP-03 position leverage ${leverage}`,
      (candidate) => {
        const current = openPositions(candidate, symbol).find(({ id }) => (
          id === before.id
        ))
        return current && Number(current.leverage) === leverage
          ? { snapshot: candidate, position: current }
          : false
      }
    )
    assertDecimalClose(
      updated.position.lots,
      before.lots,
      tolerancesFromRules(rules).quantity,
      `PERP-03 ${leverage}x quantity`
    )
    assertDecimalClose(
      updated.position.openPrice,
      before.openPrice,
      tolerancesFromRules(rules).price,
      `PERP-03 ${leverage}x entry`
    )
    assertDecimalClose(
      updated.position.realizedPnl,
      before.realizedPnl,
      tolerancesFromRules(rules).amount,
      `PERP-03 ${leverage}x realized PnL`
    )
    assertDecimalClose(
      updated.snapshot.summary.balance,
      opened.snapshot.summary.balance,
      tolerancesFromRules(rules).amount,
      `PERP-03 ${leverage}x balance`
    )
    const financials = assertPerpPositionFinancials(
      updated.snapshot,
      [updated.position],
      rules,
      `PERP-03 ${leverage}x`
    )
    assertDecimalClose(
      updated.position.marginHeld,
      financials.positions[0].risk.initialMargin,
      tolerancesFromRules(rules).amount,
      `PERP-03 ${leverage}x theoretical margin`
    )
    const marginDelta = Number(updated.position.marginHeld) - Number(before.marginHeld)
    if (leverage > Number(before.leverage)) {
      assert(
        marginDelta < 0,
        `PERP-03 increasing leverage to ${leverage} must release margin`
      )
    } else {
      assert(
        marginDelta > 0,
        `PERP-03 reducing leverage to ${leverage} must add margin`
      )
    }
    const leverageCapture = await scope.capture(
      `leverage-${leverage}x`,
      updated.snapshot
    )
    const ledger = assertPerpLedgerDelta(
      ledgerBaselineDb,
      leverageCapture.db,
      [{
        operationType: marginDelta > 0 ? 'MARGIN_HOLD' : 'MARGIN_RELEASE',
        referenceType: 'POSITION',
        referenceId: updated.position.id,
        amount: Math.abs(marginDelta).toFixed(8)
      }],
      `PERP-03 ${leverage}x`
    )
    ledgerBaselineDb = leverageCapture.db
    position = updated.position
    margins.push({
      leverage,
      margin: String(position.marginHeld),
      financials,
      ledger
    })
  }

  const beforeLarge = await context.api.snapshotAccount(page)
  const large = await context.ui.submitOrderViaUi(page, {
    side: 'BUY',
    orderType: 'MARKET',
    amount: stepAlignedQuantity(market, 1)
  })
  scope.addMutation('perpetual-large-open-via-ui', large)
  const enlarged = await waitForFilledMutation(scope, beforeLarge, large, symbol)
  position = openPositions(enlarged.snapshot, symbol).at(0)
  assert(position, 'PERP-03 enlarged position')
  assertSingleFullFillMutation(beforeLarge, enlarged, 'PERP-03 enlarged opening')
  const largeHold = perpOpeningHoldOracle({
    baseQuantity: String(enlarged.trade.lots),
    worstPrice: String(enlarged.trade.price),
    leverage: '50',
    rules
  })
  assertDecimalClose(
    enlarged.trade.fee,
    largeHold.feeBuffer,
    largeHold.tolerances.amount,
    'PERP-03 enlarged opening fee'
  )
  assertDecimalClose(
    position.marginHeld,
    Number(beforeLarge.positions.find(({ id }) => id === position.id).marginHeld)
      + Number(largeHold.openingInitialMargin),
    largeHold.tolerances.amount,
    'PERP-03 enlarged margin'
  )
  const enlargedFinancials = assertPerpPositionFinancials(
    enlarged.snapshot,
    [position],
    rules,
    'PERP-03 enlarged'
  )
  const enlargedCapture = await scope.capture('enlarged-50x', enlarged.snapshot)
  const enlargedLedger = assertPerpLedgerDelta(
    ledgerBaselineDb,
    enlargedCapture.db,
    [
      {
        operationType: 'MARGIN_HOLD',
        referenceType: 'POSITION',
        referenceId: position.id,
        amount: largeHold.openingInitialMargin
      },
      {
        operationType: 'TRADE_FEE',
        referenceType: 'TRADE',
        referenceId: enlarged.trade.id,
        amount: negativeAmount(largeHold.feeBuffer)
      }
    ],
    'PERP-03 enlarged opening'
  )
  ledgerBaselineDb = enlargedCapture.db
  const failedSettings = await context.ui.setPerpetualSettingsViaUi(page, {
    leverage: 1,
    expectFailure: true,
    reason: 'PERP-03 insufficient margin while lowering leverage'
  })
  assert.equal(failedSettings.length, 1)
  assertRejectedCode(
    failedSettings[0],
    'INSUFFICIENT_MARGIN',
    'PERP-03 insufficient margin'
  )
  scope.contractProbes.push({
    status: 'REJECTED',
    kind: 'LEVERAGE_INSUFFICIENT_MARGIN',
    requestRef: failedSettings[0].requestRef,
    errorCode: responseCode(failedSettings[0])
  })
  const afterReject = await context.api.snapshotAccount(page)
  assertTradingStateEqual(
    enlarged.snapshot,
    afterReject,
    'PERP-03 insufficient margin zero mutation'
  )
  const rejectedCapture = await scope.capture('leverage-rejected', afterReject)
  assert.deepEqual(
    rejectedCapture.db,
    enlargedCapture.db,
    'PERP-03 insufficient margin DB zero mutation'
  )
  const rejectedPosition = openPositions(afterReject, symbol).at(0)
  assert.equal(Number(rejectedPosition.leverage), 50)
  assertDecimalClose(
    rejectedPosition.lots,
    position.lots,
    tolerancesFromRules(rules).quantity,
    'PERP-03 rejected leverage quantity'
  )
  const close = await closePositionFromUi(scope, rejectedPosition)
  const closed = await waitForFilledMutation(
    scope,
    afterReject,
    close,
    symbol
  )
  assert.equal(openPositions(closed.snapshot, symbol).length, 0)
  const closeOracle = perpCloseOracle({
    side: ['BUY', 'LONG'].includes(rejectedPosition.side) ? 'LONG' : 'SHORT',
    quantity: String(rejectedPosition.lots),
    entryPrice: String(rejectedPosition.openPrice),
    closeFillPrice: String(closed.trade.price),
    rules
  })
  assertDecimalClose(
    closed.trade.realizedPnl,
    closeOracle.grossRealizedPnl,
    closeOracle.tolerances.amount,
    'PERP-03 close realized PnL'
  )
  assertDecimalClose(
    closed.trade.fee,
    closeOracle.closeFee,
    closeOracle.tolerances.amount,
    'PERP-03 close fee'
  )
  const finalCapture = await scope.capture('fully-closed', closed.snapshot)
  const closeLedger = assertPerpLedgerDelta(
    ledgerBaselineDb,
    finalCapture.db,
    [
      {
        operationType: 'MARGIN_RELEASE',
        referenceType: 'POSITION',
        referenceId: rejectedPosition.id,
        amount: rejectedPosition.marginHeld
      },
      {
        operationType: 'TRADE_FEE',
        referenceType: 'TRADE',
        referenceId: closed.trade.id,
        amount: negativeAmount(closeOracle.closeFee)
      }
    ],
    'PERP-03 close'
  )
  scope.oracleEvidence.push({
    kind: 'PERP_LEVERAGE_LIFECYCLE',
    settings: settingEvidence,
    margins,
    enlarged: {
      hold: largeHold,
      financials: enlargedFinancials,
      ledger: enlargedLedger
    },
    final: { ...closeOracle, ledger: closeLedger },
    finalCloseRequestRef: close.requestRef
  })
  return {
    finalSnapshot: closed.snapshot,
    finalDb: finalCapture.db
  }
}

async function runPerpQuantityUnits(context, definition, details) {
  const initialMarket = await context.api.snapshotMarket('BTCUSDT-PERP')
  const fixedMark = Number(
    initialMarket.reference?.mark
      ?? initialMarket.quote?.markPrice
      ?? initialMarket.quote?.mid
  )
  assert(Number.isFinite(fixedMark) && fixedMark > 0, 'PERP-04 fixed mark')
  const results = []
  for (const subrun of definition.requiredSubruns) {
    let persisted
    const evidence = context.evidence
    const subContext = {
      ...context,
      userFactory: (seed) => context.userFactory(`${seed}-${subrun.id}`),
      evidence: {
        ...evidence,
        captureCheckpoint(checkpointContext, name, scope = {}) {
          return evidence.captureCheckpoint.call(
            evidence,
            checkpointContext,
            name,
            { ...scope, subrunIdentity: subrun.id }
          )
        },
        writeCaseResultAtomic(_path, result) {
          persisted = result
          return result
        }
      }
    }
    const subDefinition = { ...definition, requiredSubruns: [subrun] }
    try {
      const result = await runSingleUserCoreCase(
        subContext,
        subDefinition,
        details,
        (scope) => runPerpQuantityUnitJourney(scope, {
          fixedMark,
          subrunId: subrun.id
        })
      )
      assert.equal(result, persisted, `PERP-04 ${subrun.id} persisted result`)
      results.push(result)
    } catch (error) {
      if (persisted) {
        context.evidence.writeCaseResultAtomic(
          join(context.run.artifactRoot, definition.id, 'result.json'),
          persisted
        )
      }
      throw error
    }
  }
  assert(results.length > 0, 'PERP-04 requires an executable quantity subrun')
  const unitEvidence = results.map((result) => (
    result.oracleEvidence.find(({ kind }) => kind === 'PERP_QUANTITY_UNIT')
  ))
  assert(unitEvidence.every(Boolean), 'PERP-04 quantity evidence')
  const canonical = unitEvidence[0]
  for (const outcome of unitEvidence.slice(1)) {
    assertDecimalClose(
      outcome.baseQuantity,
      canonical.baseQuantity,
      '0.00000001',
      `PERP-04 ${outcome.unit} canonical base`
    )
    assertDecimalClose(
      outcome.openingFee,
      canonical.openingFee,
      '0.00000001',
      `PERP-04 ${outcome.unit} opening fee`
    )
    assertDecimalClose(
      outcome.initialMargin,
      canonical.initialMargin,
      '0.00000001',
      `PERP-04 ${outcome.unit} initial margin`
    )
  }
  const merged = mergeIndependentCaseResults(definition, results, {
    kind: 'PERP_QUANTITY_UNIT_EQUIVALENCE',
    fixedMark: String(fixedMark),
    accounts: unitEvidence.map(({ accountId, unit }) => ({ accountId, unit })),
    outcomes: unitEvidence
  })
  context.evidence.writeCaseResultAtomic(
    join(context.run.artifactRoot, definition.id, 'result.json'),
    merged
  )
  return merged
}

async function runPerpQuantityUnitJourney(scope, { fixedMark, subrunId }) {
  const { context, page } = scope
  const symbol = 'BTCUSDT-PERP'
  const initialMarket = await context.api.snapshotMarket(symbol)
  const rules = rulesFor(initialMarket)
  assert(
    rules.contractSize !== undefined && rules.contractMultiplier !== undefined,
    'PERP-04 public symbol rules must expose contractSize and contractMultiplier'
  )
  const authority = await applyAuthorityMark(scope, symbol, fixedMark)
  const market = await context.api.snapshotMarket(symbol)
  const mark = String(market.reference?.mark ?? (
    (Number(authority.bid) + Number(authority.ask)) / 2
  ))
  const targetBase = quantityFromUnit({
    unit: 'CONTRACTS',
    quantity: '1',
    authorityMark: mark,
    rules
  })
  const quoteQuantity = (
    Number(targetBase) * Number(mark)
  ).toFixed(8)
  const inputs = {
    'desktop-base': { unit: 'BASE', quantity: targetBase },
    'desktop-quote': { unit: 'QUOTE', quantity: quoteQuantity },
    'desktop-contracts': { unit: 'CONTRACTS', quantity: '1' }
  }
  const input = inputs[subrunId]
  assert(input, `PERP-04 unknown subrun ${subrunId}`)
  await context.ui.openTradePanel(page, {
    product: 'perpetual',
    symbol
  })
  await ensurePerpSettings(scope, {
    positionMode: 'ONE_WAY',
    marginMode: 'CROSS',
    leverage: 10
  })
  await ensurePerpSettings(scope, { quantityUnit: input.unit })
  const before = await context.api.snapshotAccount(page)
  const capture = await context.ui.submitOrderViaUi(page, {
    side: 'BUY',
    orderType: 'MARKET',
    amount: input.quantity
  })
  scope.addMutation(`perpetual-${input.unit.toLowerCase()}-open-via-ui`, capture)
  const opened = await waitForFilledMutation(scope, before, capture, symbol)
  assertSingleFullFillMutation(before, opened, `PERP-04 ${input.unit} opening`)
  const position = openPositions(opened.snapshot, symbol).at(0)
  assert(position, `PERP-04 ${input.unit} position`)
  const expectedBase = quantityFromUnit({
    unit: input.unit,
    quantity: input.quantity,
    authorityMark: mark,
    rules
  })
  assert.equal(opened.order.quantityUnit, input.unit)
  assertDecimalClose(
    opened.order.originalQuantity ?? opened.order.quantity,
    input.quantity,
    input.unit === 'QUOTE'
      ? tolerancesFromRules(rules).amount
      : tolerancesFromRules(rules).quantity,
    `PERP-04 ${input.unit} original quantity`
  )
  assertDecimalClose(
    opened.order.baseQuantity ?? opened.order.lots,
    expectedBase,
    tolerancesFromRules(rules).quantity,
    `PERP-04 ${input.unit} normalized order quantity`
  )
  assertDecimalClose(
    position.lots,
    expectedBase,
    tolerancesFromRules(rules).quantity,
    `PERP-04 ${input.unit} position quantity`
  )
  const openingHold = perpOpeningHoldOracle({
    baseQuantity: expectedBase,
    worstPrice: String(opened.trade.price),
    leverage: '10',
    rules
  })
  assertDecimalClose(
    opened.trade.fee,
    openingHold.feeBuffer,
    openingHold.tolerances.amount,
    `PERP-04 ${input.unit} opening fee`
  )
  assertDecimalClose(
    position.marginHeld,
    openingHold.openingInitialMargin,
    openingHold.tolerances.amount,
    `PERP-04 ${input.unit} initial margin`
  )
  const openedEvidence = await scope.capture(
    `unit-${input.unit.toLowerCase()}-opened`,
    opened.snapshot
  )
  const openingLedger = assertPerpLedgerDelta(
    scope.beforeDb,
    openedEvidence.db,
    [
      {
        operationType: 'MARGIN_HOLD',
        referenceType: 'POSITION',
        referenceId: position.id,
        amount: openingHold.openingInitialMargin
      },
      {
        operationType: 'TRADE_FEE',
        referenceType: 'TRADE',
        referenceId: opened.trade.id,
        amount: negativeAmount(openingHold.feeBuffer)
      }
    ],
    `PERP-04 ${input.unit} opening`
  )
  const beforeClose = opened.snapshot
  await closePositionFromUi(scope, position)
  const closed = await waitForAccount(
    context,
    page,
    `PERP-04 ${input.unit} cleanup`,
    (candidate) => {
      const trade = recordsAfter(beforeClose, candidate, 'trades').at(-1)
      return openPositions(candidate, symbol).length === 0 && trade
        ? { snapshot: candidate, trade }
        : false
    }
  )
  const closeOracle = perpCloseOracle({
    side: 'LONG',
    quantity: expectedBase,
    entryPrice: String(position.openPrice),
    closeFillPrice: String(closed.trade.price),
    rules
  })
  assertDecimalClose(
    closed.trade.fee,
    closeOracle.closeFee,
    closeOracle.tolerances.amount,
    `PERP-04 ${input.unit} close fee`
  )
  const closedEvidence = await scope.capture(
    `unit-${input.unit.toLowerCase()}-closed`,
    closed.snapshot
  )
  const closingLedger = assertPerpLedgerDelta(
    openedEvidence.db,
    closedEvidence.db,
    [
      {
        operationType: 'MARGIN_RELEASE',
        referenceType: 'POSITION',
        referenceId: position.id,
        amount: position.marginHeld
      },
      {
        operationType: 'TRADE_FEE',
        referenceType: 'TRADE',
        referenceId: closed.trade.id,
        amount: negativeAmount(closeOracle.closeFee)
      }
    ],
    `PERP-04 ${input.unit} close`
  )
  scope.oracleEvidence.push({
    kind: 'PERP_QUANTITY_UNIT',
    accountId: closed.snapshot.account.id,
    subrunId,
    unit: input.unit,
    inputQuantity: input.quantity,
    mark,
    rules: {
      stepSize: rules.stepSize,
      contractSize: rules.contractSize,
      contractMultiplier: rules.contractMultiplier
    },
    baseQuantity: expectedBase,
    openingFee: opened.trade.fee,
    initialMargin: position.marginHeld,
    openingLedger,
    close: closeOracle,
    closingLedger
  })
  return { finalSnapshot: closed.snapshot, finalDb: closedEvidence.db }
}

async function runPerpWeightedEntryJourney(scope) {
  const { context, page } = scope
  const symbol = 'BTCUSDT-PERP'
  const initial = await context.api.snapshotMarket(symbol)
  const rules = rulesFor(initial)
  const q1 = stepAlignedQuantity(initial, 0.01)
  const q2 = stepAlignedQuantity(initial, 0.02)
  await context.ui.openTradePanel(page, {
    product: 'perpetual',
    symbol
  })
  await ensurePerpSettings(scope, {
    positionMode: 'ONE_WAY',
    marginMode: 'CROSS',
    leverage: 10,
    quantityUnit: 'BASE'
  })
  const p1 = Number(initial.reference?.mark ?? initial.quote?.mid)
  await applyAuthorityMark(scope, symbol, p1)
  const beforeFirst = await context.api.snapshotAccount(page)
  const first = await context.ui.submitOrderViaUi(page, {
    side: 'BUY',
    orderType: 'MARKET',
    amount: q1
  })
  scope.addMutation('perpetual-first-add-via-ui', first)
  const firstFill = await waitForFilledMutation(
    scope,
    beforeFirst,
    first,
    symbol
  )
  const firstPosition = openPositions(firstFill.snapshot, symbol).at(0)
  assert(firstPosition, 'PERP-05 first position')
  assertSingleFullFillMutation(beforeFirst, firstFill, 'PERP-05 first opening')
  const firstHold = perpOpeningHoldOracle({
    baseQuantity: String(firstFill.trade.lots),
    worstPrice: String(firstFill.trade.price),
    leverage: '10',
    rules
  })
  assertDecimalClose(
    firstFill.trade.fee,
    firstHold.feeBuffer,
    firstHold.tolerances.amount,
    'PERP-05 first opening fee'
  )
  assertDecimalClose(
    firstPosition.marginHeld,
    firstHold.openingInitialMargin,
    firstHold.tolerances.amount,
    'PERP-05 first opening margin'
  )
  const firstFinancials = assertPerpPositionFinancials(
    firstFill.snapshot,
    [firstPosition],
    rules,
    'PERP-05 first opening'
  )
  const firstCapture = await scope.capture('first-opened', firstFill.snapshot)
  const firstLedger = assertPerpLedgerDelta(
    scope.beforeDb,
    firstCapture.db,
    [
      {
        operationType: 'MARGIN_HOLD',
        referenceType: 'POSITION',
        referenceId: firstPosition.id,
        amount: firstHold.openingInitialMargin
      },
      {
        operationType: 'TRADE_FEE',
        referenceType: 'TRADE',
        referenceId: firstFill.trade.id,
        amount: negativeAmount(firstHold.feeBuffer)
      }
    ],
    'PERP-05 first opening'
  )

  const p2 = p1 * 1.02
  await applyAuthorityMark(scope, symbol, p2)
  const second = await context.ui.submitOrderViaUi(page, {
    side: 'BUY',
    orderType: 'MARKET',
    amount: q2
  })
  scope.addMutation('perpetual-second-add-via-ui', second)
  const secondFill = await waitForFilledMutation(
    scope,
    firstFill.snapshot,
    second,
    symbol
  )
  const combined = openPositions(secondFill.snapshot, symbol).at(0)
  assert(combined, 'PERP-05 combined position')
  assertSingleFullFillMutation(
    firstFill.snapshot,
    secondFill,
    'PERP-05 second opening'
  )
  assert.equal(combined.id, firstPosition.id)
  const expectedQuantity = Number(firstFill.trade.lots) + Number(secondFill.trade.lots)
  const expectedEntry = (
    Number(firstFill.trade.lots) * Number(firstFill.trade.price)
      + Number(secondFill.trade.lots) * Number(secondFill.trade.price)
  ) / expectedQuantity
  assertDecimalClose(
    combined.lots,
    expectedQuantity,
    tolerancesFromRules(rules).quantity,
    'PERP-05 combined quantity'
  )
  assertDecimalClose(
    combined.openPrice,
    expectedEntry,
    tolerancesFromRules(rules).price,
    'PERP-05 weighted entry'
  )
  assertNear(combined.realizedPnl, 0, 'PERP-05 add realized PnL')
  const secondHold = perpOpeningHoldOracle({
    baseQuantity: String(secondFill.trade.lots),
    worstPrice: String(secondFill.trade.price),
    leverage: '10',
    rules
  })
  assertDecimalClose(
    secondFill.trade.fee,
    secondHold.feeBuffer,
    secondHold.tolerances.amount,
    'PERP-05 second opening fee'
  )
  assertDecimalClose(
    combined.marginHeld,
    Number(firstPosition.marginHeld) + Number(secondHold.openingInitialMargin),
    secondHold.tolerances.amount,
    'PERP-05 combined margin'
  )
  const combinedFinancials = assertPerpPositionFinancials(
    secondFill.snapshot,
    [combined],
    rules,
    'PERP-05 combined'
  )
  const secondCapture = await scope.capture('submitted', secondFill.snapshot)
  const secondLedger = assertPerpLedgerDelta(
    firstCapture.db,
    secondCapture.db,
    [
      {
        operationType: 'MARGIN_HOLD',
        referenceType: 'POSITION',
        referenceId: combined.id,
        amount: secondHold.openingInitialMargin
      },
      {
        operationType: 'TRADE_FEE',
        referenceType: 'TRADE',
        referenceId: secondFill.trade.id,
        amount: negativeAmount(secondHold.feeBuffer)
      }
    ],
    'PERP-05 second opening'
  )
  const close = await closePositionFromUi(scope, combined)
  const closed = await waitForFilledMutation(
    scope,
    secondFill.snapshot,
    close,
    symbol
  )
  assert.equal(openPositions(closed.snapshot, symbol).length, 0)
  const closeOracle = perpCloseOracle({
    side: 'LONG',
    quantity: String(combined.lots),
    entryPrice: String(combined.openPrice),
    closeFillPrice: String(closed.trade.price),
    rules
  })
  assertDecimalClose(
    closed.trade.realizedPnl,
    closeOracle.grossRealizedPnl,
    closeOracle.tolerances.amount,
    'PERP-05 close realized PnL'
  )
  assertDecimalClose(
    closed.trade.fee,
    closeOracle.closeFee,
    closeOracle.tolerances.amount,
    'PERP-05 close fee'
  )
  const history = (closed.snapshot.positionHistory ?? [])
    .find(({ id }) => id === combined.id)
  assert(history, 'PERP-05 closed position history')
  assertDecimalClose(
    history.realizedPnl,
    closeOracle.grossRealizedPnl,
    closeOracle.tolerances.amount,
    'PERP-05 history realizedPnl'
  )
  assert.equal(
    closed.snapshot.trades.filter(({ symbol: value }) => value === symbol).length,
    3,
    'PERP-05 exact lifecycle trades'
  )
  const finalBalanceDelta = Number(closeOracle.grossRealizedPnl)
    - Number(firstHold.feeBuffer)
    - Number(secondHold.feeBuffer)
    - Number(closeOracle.closeFee)
  assertDecimalClose(
    Number(closed.snapshot.summary.balance) - Number(beforeFirst.summary.balance),
    finalBalanceDelta,
    closeOracle.tolerances.amount,
    'PERP-05 final balance delta'
  )
  const finalCapture = await scope.capture('fully-closed', closed.snapshot)
  const closeLedger = assertPerpLedgerDelta(
    secondCapture.db,
    finalCapture.db,
    [
      {
        operationType: 'MARGIN_RELEASE',
        referenceType: 'POSITION',
        referenceId: combined.id,
        amount: combined.marginHeld
      },
      {
        operationType: 'TRADE_FEE',
        referenceType: 'TRADE',
        referenceId: closed.trade.id,
        amount: negativeAmount(closeOracle.closeFee)
      }
    ],
    'PERP-05 close'
  )
  scope.oracleEvidence.push({
    kind: 'PERP_WEIGHTED_ENTRY',
    positionId: combined.id,
    fills: [
      { quantity: firstFill.trade.lots, price: firstFill.trade.price },
      { quantity: secondFill.trade.lots, price: secondFill.trade.price }
    ],
    expectedQuantity: String(expectedQuantity),
    expectedEntry: String(expectedEntry),
    actualEntry: combined.openPrice,
    opening: [
      { hold: firstHold, financials: firstFinancials, ledger: firstLedger },
      { hold: secondHold, financials: combinedFinancials, ledger: secondLedger }
    ],
    final: {
      ...closeOracle,
      historyRealizedPnl: history.realizedPnl,
      finalBalanceDelta,
      ledger: closeLedger
    }
  })
  return {
    finalSnapshot: closed.snapshot,
    finalDb: finalCapture.db
  }
}

async function runPerpReductionJourney(scope) {
  const { context, page } = scope
  const symbol = 'BTCUSDT-PERP'
  const market = await context.api.snapshotMarket(symbol)
  const rules = rulesFor(market)
  const quantity = stepAlignedQuantity(market, 0.01)
  const partialQuantity = floorFraction(quantity, 3, 10, rules)
  const remainingQuantity = (
    Number(quantity) - Number(partialQuantity)
  ).toFixed((effectiveQuantityStep(rules).split('.')[1] ?? '').length)
  await context.ui.openTradePanel(page, {
    product: 'perpetual',
    symbol
  })
  await ensurePerpSettings(scope, {
    positionMode: 'ONE_WAY',
    marginMode: 'CROSS',
    leverage: 10,
    quantityUnit: 'BASE'
  })
  const beforeOpen = await context.api.snapshotAccount(page)
  const open = await context.ui.submitOrderViaUi(page, {
    side: 'BUY',
    orderType: 'MARKET',
    amount: quantity,
    reduceOnly: false
  })
  scope.addMutation('perpetual-open-long-via-ui', open)
  const opened = await waitForFilledMutation(scope, beforeOpen, open, symbol)
  const original = openPositions(opened.snapshot, symbol).at(0)
  assert(original, 'PERP-06 original long')
  assertSingleFullFillMutation(beforeOpen, opened, 'PERP-06 opening')
  const openingHold = perpOpeningHoldOracle({
    baseQuantity: String(opened.trade.lots),
    worstPrice: String(opened.trade.price),
    leverage: '10',
    rules
  })
  assertDecimalClose(
    opened.trade.fee,
    openingHold.feeBuffer,
    openingHold.tolerances.amount,
    'PERP-06 opening fee'
  )
  assertDecimalClose(
    original.marginHeld,
    openingHold.openingInitialMargin,
    openingHold.tolerances.amount,
    'PERP-06 opening margin'
  )
  const openingFinancials = assertPerpPositionFinancials(
    opened.snapshot,
    [original],
    rules,
    'PERP-06 opening'
  )
  const openedCapture = await scope.capture('opened', opened.snapshot)
  const openingLedger = assertPerpLedgerDelta(
    scope.beforeDb,
    openedCapture.db,
    [
      {
        operationType: 'MARGIN_HOLD',
        referenceType: 'POSITION',
        referenceId: original.id,
        amount: openingHold.openingInitialMargin
      },
      {
        operationType: 'TRADE_FEE',
        referenceType: 'TRADE',
        referenceId: opened.trade.id,
        amount: negativeAmount(openingHold.feeBuffer)
      }
    ],
    'PERP-06 opening'
  )
  const partial = await context.ui.submitOrderViaUi(page, {
    side: 'SELL',
    orderType: 'MARKET',
    amount: partialQuantity,
    reduceOnly: false
  })
  scope.addMutation('perpetual-opposite-reduce-via-ui', partial)
  const reduced = await waitForFilledMutation(
    scope,
    opened.snapshot,
    partial,
    symbol
  )
  const remaining = openPositions(reduced.snapshot, symbol).at(0)
  assert(remaining, 'PERP-06 remaining long')
  assertSingleFullFillMutation(
    opened.snapshot,
    reduced,
    'PERP-06 partial close'
  )
  assertDecimalClose(
    remaining.lots,
    remainingQuantity,
    tolerancesFromRules(rules).quantity,
    'PERP-06 remaining quantity'
  )
  assertDecimalClose(
    remaining.openPrice,
    original.openPrice,
    tolerancesFromRules(rules).price,
    'PERP-06 retained entry'
  )
  const partialOracle = partialCloseOracle({
    side: 'LONG',
    originalQuantity: String(original.lots),
    oldMargin: String(original.marginHeld),
    entryPrice: String(original.openPrice),
    closeFillPrice: String(reduced.trade.price),
    previousPositionRealizedPnl: String(original.realizedPnl),
    rules
  })
  assertDecimalClose(
    reduced.trade.lots,
    partialOracle.closedQuantity,
    partialOracle.tolerances.quantity,
    'PERP-06 partial closed quantity'
  )
  assertDecimalClose(
    reduced.trade.realizedPnl,
    partialOracle.tradeRealizedPnl,
    partialOracle.tolerances.amount,
    'PERP-06 partial realized PnL'
  )
  assertDecimalClose(
    reduced.trade.fee,
    partialOracle.closeFee,
    partialOracle.tolerances.amount,
    'PERP-06 partial close fee'
  )
  assertDecimalClose(
    remaining.marginHeld,
    partialOracle.remainingMargin,
    partialOracle.tolerances.amount,
    'PERP-06 partial remaining margin'
  )
  assertDecimalClose(
    remaining.realizedPnl,
    partialOracle.positionRealizedPnl,
    partialOracle.tolerances.amount,
    'PERP-06 partial cumulative realized PnL'
  )
  assert.equal(
    (reduced.snapshot.positionHistory ?? []).length,
    (opened.snapshot.positionHistory ?? []).length,
    'PERP-06 partial close history'
  )
  const partialFinancials = assertPerpPositionFinancials(
    reduced.snapshot,
    [remaining],
    rules,
    'PERP-06 partial close'
  )
  const partialCapture = await scope.capture(
    'partially-closed',
    reduced.snapshot
  )
  const partialLedger = assertPerpLedgerDelta(
    openedCapture.db,
    partialCapture.db,
    [
      {
        operationType: 'MARGIN_RELEASE',
        referenceType: 'POSITION',
        referenceId: original.id,
        amount: partialOracle.releasedMargin
      },
      {
        operationType: 'TRADE_FEE',
        referenceType: 'TRADE',
        referenceId: reduced.trade.id,
        amount: negativeAmount(partialOracle.closeFee)
      }
    ],
    'PERP-06 partial close'
  )
  const close = await context.ui.submitOrderViaUi(page, {
    side: 'SELL',
    orderType: 'MARKET',
    amount: remainingQuantity,
    reduceOnly: false
  })
  scope.addMutation('perpetual-equal-opposite-close-via-ui', close)
  const closed = await waitForFilledMutation(
    scope,
    reduced.snapshot,
    close,
    symbol
  )
  assert.equal(openPositions(closed.snapshot, symbol).length, 0)
  assertSingleFullFillMutation(
    reduced.snapshot,
    closed,
    'PERP-06 final close'
  )
  const finalOracle = perpCloseOracle({
    side: 'LONG',
    quantity: String(remaining.lots),
    entryPrice: String(remaining.openPrice),
    closeFillPrice: String(closed.trade.price),
    rules
  })
  assertDecimalClose(
    closed.trade.realizedPnl,
    finalOracle.grossRealizedPnl,
    finalOracle.tolerances.amount,
    'PERP-06 final realized PnL'
  )
  assertDecimalClose(
    closed.trade.fee,
    finalOracle.closeFee,
    finalOracle.tolerances.amount,
    'PERP-06 final close fee'
  )
  assert.equal(
    (closed.snapshot.positionHistory ?? []).filter(({ id }) => id === original.id).length,
    1
  )
  const history = closed.snapshot.positionHistory
    .find(({ id }) => id === original.id)
  const cumulativeRealized = Number(partialOracle.tradeRealizedPnl)
    + Number(finalOracle.grossRealizedPnl)
  assertDecimalClose(
    history.realizedPnl,
    cumulativeRealized,
    finalOracle.tolerances.amount,
    'PERP-06 history realizedPnl'
  )
  assert.equal(
    closed.snapshot.trades.filter(({ symbol: value }) => value === symbol).length,
    3,
    'PERP-06 exact lifecycle trades'
  )
  const finalBalanceDelta = cumulativeRealized
    - Number(openingHold.feeBuffer)
    - Number(partialOracle.closeFee)
    - Number(finalOracle.closeFee)
  assertDecimalClose(
    Number(closed.snapshot.summary.balance) - Number(beforeOpen.summary.balance),
    finalBalanceDelta,
    finalOracle.tolerances.amount,
    'PERP-06 final balance delta'
  )
  const finalCapture = await scope.capture('fully-closed', closed.snapshot)
  const finalLedger = assertPerpLedgerDelta(
    partialCapture.db,
    finalCapture.db,
    [
      {
        operationType: 'MARGIN_RELEASE',
        referenceType: 'POSITION',
        referenceId: original.id,
        amount: remaining.marginHeld
      },
      {
        operationType: 'TRADE_FEE',
        referenceType: 'TRADE',
        referenceId: closed.trade.id,
        amount: negativeAmount(finalOracle.closeFee)
      }
    ],
    'PERP-06 final close'
  )
  scope.oracleEvidence.push({
    kind: 'PERP_ONE_WAY_REDUCTION',
    originalQuantity: quantity,
    partialQuantity,
    remainingQuantity,
    partial: partialOracle,
    positionId: original.id,
    opening: {
      hold: openingHold,
      financials: openingFinancials,
      ledger: openingLedger
    },
    partialFinancials,
    partialLedger,
    final: {
      ...finalOracle,
      cumulativeRealized,
      finalBalanceDelta,
      ledger: finalLedger
    }
  })
  return {
    finalSnapshot: closed.snapshot,
    finalDb: finalCapture.db
  }
}

async function runPerpReversalJourney(scope) {
  const { context, page } = scope
  const symbol = 'BTCUSDT-PERP'
  const market = await context.api.snapshotMarket(symbol)
  const rules = rulesFor(market)
  const quantity = stepAlignedQuantity(market, 0.01)
  const reversalQuantity = (
    Number(quantity) * 1.5
  ).toFixed((effectiveQuantityStep(rules).split('.')[1] ?? '').length)
  const remainder = (
    Number(reversalQuantity) - Number(quantity)
  ).toFixed((effectiveQuantityStep(rules).split('.')[1] ?? '').length)
  await context.ui.openTradePanel(page, {
    product: 'perpetual',
    symbol
  })
  await ensurePerpSettings(scope, {
    positionMode: 'ONE_WAY',
    marginMode: 'CROSS',
    leverage: 10,
    quantityUnit: 'BASE'
  })
  const beforeFirstOpen = await context.api.snapshotAccount(page)
  const firstOpen = await context.ui.submitOrderViaUi(page, {
    side: 'BUY',
    orderType: 'MARKET',
    amount: quantity
  })
  scope.addMutation('perpetual-reversal-seed-via-ui', firstOpen)
  const opened = await waitForFilledMutation(
    scope,
    beforeFirstOpen,
    firstOpen,
    symbol
  )
  const long = openPositions(opened.snapshot, symbol).at(0)
  assert(long, 'PERP-07 seed long')
  assertSingleFullFillMutation(beforeFirstOpen, opened, 'PERP-07 seed opening')
  const openingHold = perpOpeningHoldOracle({
    baseQuantity: String(opened.trade.lots),
    worstPrice: String(opened.trade.price),
    leverage: '10',
    rules
  })
  assertDecimalClose(
    opened.trade.fee,
    openingHold.feeBuffer,
    openingHold.tolerances.amount,
    'PERP-07 seed opening fee'
  )
  assertDecimalClose(
    long.marginHeld,
    openingHold.openingInitialMargin,
    openingHold.tolerances.amount,
    'PERP-07 seed opening margin'
  )
  const openingFinancials = assertPerpPositionFinancials(
    opened.snapshot,
    [long],
    rules,
    'PERP-07 seed opening'
  )
  const openedCapture = await scope.capture('seed-opened', opened.snapshot)
  const openingLedger = assertPerpLedgerDelta(
    scope.beforeDb,
    openedCapture.db,
    [
      {
        operationType: 'MARGIN_HOLD',
        referenceType: 'POSITION',
        referenceId: long.id,
        amount: openingHold.openingInitialMargin
      },
      {
        operationType: 'TRADE_FEE',
        referenceType: 'TRADE',
        referenceId: opened.trade.id,
        amount: negativeAmount(openingHold.feeBuffer)
      }
    ],
    'PERP-07 seed opening'
  )
  const reversal = await context.ui.submitOrderViaUi(page, {
    side: 'SELL',
    orderType: 'MARKET',
    amount: reversalQuantity,
    reduceOnly: false
  })
  scope.addMutation('perpetual-over-reversal-via-ui', reversal)
  const reversed = await waitForFilledMutation(
    scope,
    opened.snapshot,
    reversal,
    symbol
  )
  const short = openPositions(reversed.snapshot, symbol).at(0)
  assert(short, 'PERP-07 reversal short')
  assertSingleFullFillMutation(opened.snapshot, reversed, 'PERP-07 reversal')
  assert(
    short.side === 'SHORT' || short.side === 'SELL',
    `PERP-07 reversal must create short, got ${short.side}`
  )
  assertDecimalClose(
    short.lots,
    remainder,
    tolerancesFromRules(rules).quantity,
    'PERP-07 reversal remainder'
  )
  assertDecimalClose(
    short.openPrice,
    reversed.trade.price,
    tolerancesFromRules(rules).price,
    'PERP-07 reversal entry'
  )
  const reversalClose = perpCloseOracle({
    side: 'LONG',
    quantity: String(long.lots),
    entryPrice: String(long.openPrice),
    closeFillPrice: String(reversed.trade.price),
    rules
  })
  const remainderHold = perpOpeningHoldOracle({
    baseQuantity: String(short.lots),
    worstPrice: String(reversed.trade.price),
    leverage: '10',
    rules
  })
  const reversalFee = perpOpeningHoldOracle({
    baseQuantity: String(reversed.trade.lots),
    worstPrice: String(reversed.trade.price),
    leverage: '10',
    rules
  }).feeBuffer
  assertDecimalClose(
    reversed.trade.realizedPnl,
    reversalClose.grossRealizedPnl,
    reversalClose.tolerances.amount,
    'PERP-07 reversal realized PnL'
  )
  assertDecimalClose(
    reversed.trade.fee,
    reversalFee,
    reversalClose.tolerances.amount,
    'PERP-07 reversal fee'
  )
  assertDecimalClose(
    short.marginHeld,
    remainderHold.openingInitialMargin,
    remainderHold.tolerances.amount,
    'PERP-07 reversal remainder margin'
  )
  const reversalHistory = (reversed.snapshot.positionHistory ?? [])
    .find(({ id }) => id === long.id)
  assert(reversalHistory, 'PERP-07 reversal closed long history')
  assertDecimalClose(
    reversalHistory.realizedPnl,
    reversalClose.grossRealizedPnl,
    reversalClose.tolerances.amount,
    'PERP-07 reversal history realizedPnl'
  )
  const reversalFinancials = assertPerpPositionFinancials(
    reversed.snapshot,
    [short],
    rules,
    'PERP-07 reversal short'
  )
  const reversedCapture = await scope.capture('reversed', reversed.snapshot)
  const reversalLedger = assertPerpLedgerDelta(
    openedCapture.db,
    reversedCapture.db,
    [
      {
        operationType: 'MARGIN_RELEASE',
        referenceType: 'POSITION',
        referenceId: long.id,
        amount: long.marginHeld
      },
      {
        operationType: 'MARGIN_HOLD',
        referenceType: 'POSITION',
        referenceId: short.id,
        amount: remainderHold.openingInitialMargin
      },
      {
        operationType: 'TRADE_FEE',
        referenceType: 'TRADE',
        referenceId: reversed.trade.id,
        amount: negativeAmount(reversalFee)
      }
    ],
    'PERP-07 reversal'
  )
  const replay = await replayCapturedMutation(
    scope,
    reversal,
    (body) => body,
    { reason: 'PERP-07 same fingerprint replay' }
  )
  const replayAfter = await context.api.snapshotAccount(page)
  assertTradingStateEqual(
    reversed.snapshot,
    replayAfter,
    'PERP-07 replay must not duplicate reversal'
  )
  scope.replayProbes.push({
    status: 'PASS',
    kind: 'SAME_KEY_SAME_FINGERPRINT',
    requestRef: replay.requestRef,
    originalRequestRef: reversal.requestRef,
    positionId: short.id
  })
  const shortClose = await closePositionFromUi(scope, short)
  const shortClosed = await waitForFilledMutation(
    scope,
    replayAfter,
    shortClose,
    symbol
  )
  assert.equal(openPositions(shortClosed.snapshot, symbol).length, 0)
  const shortCloseOracle = perpCloseOracle({
    side: 'SHORT',
    quantity: String(short.lots),
    entryPrice: String(short.openPrice),
    closeFillPrice: String(shortClosed.trade.price),
    rules
  })
  assertDecimalClose(
    shortClosed.trade.realizedPnl,
    shortCloseOracle.grossRealizedPnl,
    shortCloseOracle.tolerances.amount,
    'PERP-07 reversed short realized PnL'
  )
  assertDecimalClose(
    shortClosed.trade.fee,
    shortCloseOracle.closeFee,
    shortCloseOracle.tolerances.amount,
    'PERP-07 reversed short close fee'
  )
  const shortHistory = shortClosed.snapshot.positionHistory
    .find(({ id }) => id === short.id)
  assert(shortHistory, 'PERP-07 reversed short history')
  assertDecimalClose(
    shortHistory.realizedPnl,
    shortCloseOracle.grossRealizedPnl,
    shortCloseOracle.tolerances.amount,
    'PERP-07 reversed short history realizedPnl'
  )
  const shortClosedCapture = await scope.capture(
    'reversal-cleaned',
    shortClosed.snapshot
  )
  const shortCloseLedger = assertPerpLedgerDelta(
    reversedCapture.db,
    shortClosedCapture.db,
    [
      {
        operationType: 'MARGIN_RELEASE',
        referenceType: 'POSITION',
        referenceId: short.id,
        amount: short.marginHeld
      },
      {
        operationType: 'TRADE_FEE',
        referenceType: 'TRADE',
        referenceId: shortClosed.trade.id,
        amount: negativeAmount(shortCloseOracle.closeFee)
      }
    ],
    'PERP-07 reversed short close'
  )
  const empty = shortClosed.snapshot

  const emptyReduce = await context.ui.submitOrderViaUi(page, {
    side: 'BUY',
    orderType: 'MARKET',
    amount: quantity,
    reduceOnly: true,
    expectFailure: true,
    reason: 'PERP-07 empty reduce-only'
  })
  assertRejectedCode(
    emptyReduce,
    'REDUCE_ONLY_WOULD_INCREASE',
    'PERP-07 empty reduce-only'
  )
  scope.contractProbes.push({
    status: 'REJECTED',
    kind: 'EMPTY_REDUCE_ONLY',
    requestRef: emptyReduce.requestRef,
    errorCode: responseCode(emptyReduce)
  })
  assertTradingStateEqual(
    empty,
    await context.api.snapshotAccount(page),
    'PERP-07 empty reduce-only zero mutation'
  )

  const secondOpen = await context.ui.submitOrderViaUi(page, {
    side: 'BUY',
    orderType: 'MARKET',
    amount: quantity,
    reduceOnly: false
  })
  scope.addMutation('perpetual-reduce-only-seed-via-ui', secondOpen)
  const secondOpened = await waitForFilledMutation(
    scope,
    empty,
    secondOpen,
    symbol
  )
  const secondLong = openPositions(secondOpened.snapshot, symbol).at(0)
  assert(secondLong, 'PERP-07 reduce-only seed long')
  assertSingleFullFillMutation(empty, secondOpened, 'PERP-07 reduce-only seed')
  const secondHold = perpOpeningHoldOracle({
    baseQuantity: String(secondOpened.trade.lots),
    worstPrice: String(secondOpened.trade.price),
    leverage: '10',
    rules
  })
  assertDecimalClose(
    secondOpened.trade.fee,
    secondHold.feeBuffer,
    secondHold.tolerances.amount,
    'PERP-07 reduce-only seed fee'
  )
  assertDecimalClose(
    secondLong.marginHeld,
    secondHold.openingInitialMargin,
    secondHold.tolerances.amount,
    'PERP-07 reduce-only seed margin'
  )
  const secondFinancials = assertPerpPositionFinancials(
    secondOpened.snapshot,
    [secondLong],
    rules,
    'PERP-07 reduce-only seed'
  )
  const secondCapture = await scope.capture(
    'reduce-only-seed-opened',
    secondOpened.snapshot
  )
  const secondLedger = assertPerpLedgerDelta(
    shortClosedCapture.db,
    secondCapture.db,
    [
      {
        operationType: 'MARGIN_HOLD',
        referenceType: 'POSITION',
        referenceId: secondLong.id,
        amount: secondHold.openingInitialMargin
      },
      {
        operationType: 'TRADE_FEE',
        referenceType: 'TRADE',
        referenceId: secondOpened.trade.id,
        amount: negativeAmount(secondHold.feeBuffer)
      }
    ],
    'PERP-07 reduce-only seed'
  )
  const oversizedReduce = await context.ui.submitOrderViaUi(page, {
    side: 'SELL',
    orderType: 'MARKET',
    amount: (
      Number(quantity) * 1.1
    ).toFixed((effectiveQuantityStep(rules).split('.')[1] ?? '').length),
    reduceOnly: true,
    expectFailure: true,
    reason: 'PERP-07 oversized reduce-only'
  })
  assertRejectedCode(
    oversizedReduce,
    'REDUCE_ONLY_EXCEEDS_POSITION',
    'PERP-07 oversized reduce-only'
  )
  scope.contractProbes.push({
    status: 'REJECTED',
    kind: 'OVERSIZED_REDUCE_ONLY',
    requestRef: oversizedReduce.requestRef,
    errorCode: responseCode(oversizedReduce)
  })
  const afterOversized = await context.api.snapshotAccount(page)
  assertTradingStateEqual(
    secondOpened.snapshot,
    afterOversized,
    'PERP-07 oversized reduce-only zero mutation'
  )
  const finalClose = await closePositionFromUi(scope, secondLong)
  const final = await waitForFilledMutation(
    scope,
    afterOversized,
    finalClose,
    symbol
  )
  assert.equal(openPositions(final.snapshot, symbol).length, 0)
  const finalOracle = perpCloseOracle({
    side: 'LONG',
    quantity: String(secondLong.lots),
    entryPrice: String(secondLong.openPrice),
    closeFillPrice: String(final.trade.price),
    rules
  })
  assertDecimalClose(
    final.trade.realizedPnl,
    finalOracle.grossRealizedPnl,
    finalOracle.tolerances.amount,
    'PERP-07 final close realized PnL'
  )
  assertDecimalClose(
    final.trade.fee,
    finalOracle.closeFee,
    finalOracle.tolerances.amount,
    'PERP-07 final close fee'
  )
  const finalHistory = final.snapshot.positionHistory
    .find(({ id }) => id === secondLong.id)
  assert(finalHistory, 'PERP-07 final long history')
  assertDecimalClose(
    finalHistory.realizedPnl,
    finalOracle.grossRealizedPnl,
    finalOracle.tolerances.amount,
    'PERP-07 final history realizedPnl'
  )
  const finalCapture = await scope.capture('fully-closed', final.snapshot)
  const finalLedger = assertPerpLedgerDelta(
    secondCapture.db,
    finalCapture.db,
    [
      {
        operationType: 'MARGIN_RELEASE',
        referenceType: 'POSITION',
        referenceId: secondLong.id,
        amount: secondLong.marginHeld
      },
      {
        operationType: 'TRADE_FEE',
        referenceType: 'TRADE',
        referenceId: final.trade.id,
        amount: negativeAmount(finalOracle.closeFee)
      }
    ],
    'PERP-07 final close'
  )
  scope.oracleEvidence.push({
    kind: 'PERP_ONE_WAY_REVERSAL',
    originalQuantity: quantity,
    reversalQuantity,
    remainder,
    originalPositionId: long.id,
    reversedPositionId: short.id,
    opening: {
      hold: openingHold,
      financials: openingFinancials,
      ledger: openingLedger
    },
    reversal: {
      close: reversalClose,
      remainderHold,
      reversalFee,
      financials: reversalFinancials,
      ledger: reversalLedger
    },
    reversalCleanup: {
      ...shortCloseOracle,
      ledger: shortCloseLedger
    },
    reduceOnlySeed: {
      hold: secondHold,
      financials: secondFinancials,
      ledger: secondLedger
    },
    final: { ...finalOracle, ledger: finalLedger }
  })
  return {
    finalSnapshot: final.snapshot,
    finalDb: finalCapture.db
  }
}

async function runPerpHedgeJourney(scope) {
  const { context, page } = scope
  const symbol = 'BTCUSDT-PERP'
  const market = await context.api.snapshotMarket(symbol)
  const rules = rulesFor(market)
  const quantity = stepAlignedQuantity(market, 0.01)
  const partialQuantity = floorFraction(quantity, 3, 10, rules)
  await context.ui.openTradePanel(page, {
    product: 'perpetual',
    symbol
  })
  await ensurePerpSettings(scope, {
    positionMode: 'HEDGE',
    marginMode: 'CROSS',
    leverage: 10,
    quantityUnit: 'BASE'
  })
  const beforeLong = await context.api.snapshotAccount(page)
  const longOpen = await context.ui.submitOrderViaUi(page, {
    side: 'BUY',
    positionSide: 'LONG',
    orderType: 'MARKET',
    amount: quantity
  })
  scope.addMutation('hedge-long-open-via-ui', longOpen)
  const longOpened = await waitForFilledMutation(
    scope,
    beforeLong,
    longOpen,
    symbol
  )
  const openedLong = openPositions(longOpened.snapshot, symbol)
    .find(({ positionSide }) => positionSide === 'LONG')
  assert(openedLong, 'PERP-08 opening LONG slot')
  assertSingleFullFillMutation(beforeLong, longOpened, 'PERP-08 LONG opening')
  const longHold = perpOpeningHoldOracle({
    baseQuantity: String(longOpened.trade.lots),
    worstPrice: String(longOpened.trade.price),
    leverage: '10',
    rules
  })
  assertDecimalClose(
    longOpened.trade.fee,
    longHold.feeBuffer,
    longHold.tolerances.amount,
    'PERP-08 LONG opening fee'
  )
  assertDecimalClose(
    openedLong.marginHeld,
    longHold.openingInitialMargin,
    longHold.tolerances.amount,
    'PERP-08 LONG opening margin'
  )
  const longOpeningFinancials = assertPerpPositionFinancials(
    longOpened.snapshot,
    [openedLong],
    rules,
    'PERP-08 LONG opening'
  )
  const longCapture = await scope.capture('long-opened', longOpened.snapshot)
  const longOpeningLedger = assertPerpLedgerDelta(
    scope.beforeDb,
    longCapture.db,
    [
      {
        operationType: 'MARGIN_HOLD',
        referenceType: 'POSITION',
        referenceId: openedLong.id,
        amount: longHold.openingInitialMargin
      },
      {
        operationType: 'TRADE_FEE',
        referenceType: 'TRADE',
        referenceId: longOpened.trade.id,
        amount: negativeAmount(longHold.feeBuffer)
      }
    ],
    'PERP-08 LONG opening'
  )
  const beforeShort = longOpened.snapshot
  const shortOpen = await context.ui.submitOrderViaUi(page, {
    side: 'SELL',
    positionSide: 'SHORT',
    orderType: 'MARKET',
    amount: quantity
  })
  scope.addMutation('hedge-short-open-via-ui', shortOpen)
  const bothOpened = await waitForFilledMutation(
    scope,
    beforeShort,
    shortOpen,
    symbol
  )
  const positions = openPositions(bothOpened.snapshot, symbol)
  assert.equal(positions.length, 2)
  const long = positions.find(({ positionSide }) => positionSide === 'LONG')
  const short = positions.find(({ positionSide }) => positionSide === 'SHORT')
  assert(long && short, 'PERP-08 requires independent LONG and SHORT slots')
  assertSingleFullFillMutation(
    beforeShort,
    bothOpened,
    'PERP-08 SHORT opening'
  )
  assert.notEqual(long.id, short.id)
  assert.equal(long.id, openedLong.id)
  const shortHold = perpOpeningHoldOracle({
    baseQuantity: String(bothOpened.trade.lots),
    worstPrice: String(bothOpened.trade.price),
    leverage: '10',
    rules
  })
  assertDecimalClose(
    bothOpened.trade.fee,
    shortHold.feeBuffer,
    shortHold.tolerances.amount,
    'PERP-08 SHORT opening fee'
  )
  assertDecimalClose(
    short.marginHeld,
    shortHold.openingInitialMargin,
    shortHold.tolerances.amount,
    'PERP-08 SHORT opening margin'
  )
  const bothFinancials = assertPerpPositionFinancials(
    bothOpened.snapshot,
    [long, short],
    rules,
    'PERP-08 independent openings'
  )
  const bothCapture = await scope.capture('submitted', bothOpened.snapshot)
  const shortOpeningLedger = assertPerpLedgerDelta(
    longCapture.db,
    bothCapture.db,
    [
      {
        operationType: 'MARGIN_HOLD',
        referenceType: 'POSITION',
        referenceId: short.id,
        amount: shortHold.openingInitialMargin
      },
      {
        operationType: 'TRADE_FEE',
        referenceType: 'TRADE',
        referenceId: bothOpened.trade.id,
        amount: negativeAmount(shortHold.feeBuffer)
      }
    ],
    'PERP-08 SHORT opening'
  )

  const blockedSwitch = await context.ui.setPerpetualSettingsViaUi(page, {
    positionMode: 'ONE_WAY',
    expectFailure: true,
    reason: 'PERP-08 position mode switch blocked'
  })
  assert.equal(blockedSwitch.length, 1)
  assertRejectedCode(
    blockedSwitch[0],
    'POSITION_MODE_SWITCH_BLOCKED',
    'PERP-08 position mode switch'
  )
  scope.contractProbes.push({
    status: 'REJECTED',
    kind: 'POSITION_MODE_SWITCH_BLOCKED',
    requestRef: blockedSwitch[0].requestRef,
    errorCode: responseCode(blockedSwitch[0])
  })
  assertTradingStateEqual(
    bothOpened.snapshot,
    await context.api.snapshotAccount(page),
    'PERP-08 blocked position mode switch zero mutation'
  )

  const partialLong = await context.ui.submitOrderViaUi(page, {
    side: 'SELL',
    positionSide: 'LONG',
    orderType: 'MARKET',
    amount: partialQuantity,
    reduceOnly: true
  })
  scope.addMutation('hedge-long-partial-close-via-ui', partialLong)
  const longReduced = await waitForFilledMutation(
    scope,
    bothOpened.snapshot,
    partialLong,
    symbol
  )
  const remainingLong = openPositions(longReduced.snapshot, symbol)
    .find(({ positionSide }) => positionSide === 'LONG')
  const unchangedShort = openPositions(longReduced.snapshot, symbol)
    .find(({ positionSide }) => positionSide === 'SHORT')
  assert(remainingLong && unchangedShort)
  assertSingleFullFillMutation(
    bothOpened.snapshot,
    longReduced,
    'PERP-08 LONG partial close'
  )
  assertDecimalClose(
    remainingLong.lots,
    Number(quantity) - Number(partialQuantity),
    tolerancesFromRules(rules).quantity,
    'PERP-08 LONG remaining'
  )
  assertDecimalClose(
    unchangedShort.lots,
    short.lots,
    tolerancesFromRules(rules).quantity,
    'PERP-08 SHORT isolation'
  )
  assertDecimalClose(
    unchangedShort.openPrice,
    short.openPrice,
    tolerancesFromRules(rules).price,
    'PERP-08 SHORT entry isolation'
  )
  for (const [field, tolerance] of [
    ['realizedPnl', tolerancesFromRules(rules).amount],
    ['marginHeld', tolerancesFromRules(rules).amount]
  ]) {
    assertDecimalClose(
      unchangedShort[field],
      short[field],
      tolerance,
      `PERP-08 SHORT ${field} isolation`
    )
  }
  const longPartialOracle = partialCloseOracle({
    side: 'LONG',
    originalQuantity: String(long.lots),
    oldMargin: String(long.marginHeld),
    entryPrice: String(long.openPrice),
    closeFillPrice: String(longReduced.trade.price),
    previousPositionRealizedPnl: String(long.realizedPnl),
    rules
  })
  assertDecimalClose(
    longReduced.trade.realizedPnl,
    longPartialOracle.tradeRealizedPnl,
    longPartialOracle.tolerances.amount,
    'PERP-08 LONG partial realized PnL'
  )
  assertDecimalClose(
    longReduced.trade.fee,
    longPartialOracle.closeFee,
    longPartialOracle.tolerances.amount,
    'PERP-08 LONG partial close fee'
  )
  assertDecimalClose(
    remainingLong.marginHeld,
    longPartialOracle.remainingMargin,
    longPartialOracle.tolerances.amount,
    'PERP-08 LONG partial margin'
  )
  assertDecimalClose(
    remainingLong.realizedPnl,
    longPartialOracle.positionRealizedPnl,
    longPartialOracle.tolerances.amount,
    'PERP-08 LONG partial cumulative realized PnL'
  )
  const partialFinancials = assertPerpPositionFinancials(
    longReduced.snapshot,
    [remainingLong, unchangedShort],
    rules,
    'PERP-08 LONG partial close'
  )
  const partialCapture = await scope.capture(
    'long-partially-closed',
    longReduced.snapshot
  )
  const partialLedger = assertPerpLedgerDelta(
    bothCapture.db,
    partialCapture.db,
    [
      {
        operationType: 'MARGIN_RELEASE',
        referenceType: 'POSITION',
        referenceId: long.id,
        amount: longPartialOracle.releasedMargin
      },
      {
        operationType: 'TRADE_FEE',
        referenceType: 'TRADE',
        referenceId: longReduced.trade.id,
        amount: negativeAmount(longPartialOracle.closeFee)
      }
    ],
    'PERP-08 LONG partial close'
  )

  const shortClose = await context.ui.submitOrderViaUi(page, {
    side: 'BUY',
    positionSide: 'SHORT',
    orderType: 'MARKET',
    amount: String(unchangedShort.lots),
    reduceOnly: true
  })
  scope.addMutation('hedge-short-full-close-via-ui', shortClose)
  const shortClosed = await waitForFilledMutation(
    scope,
    longReduced.snapshot,
    shortClose,
    symbol
  )
  assert.equal(
    openPositions(shortClosed.snapshot, symbol)
      .some(({ positionSide }) => positionSide === 'SHORT'),
    false
  )
  assertSingleFullFillMutation(
    longReduced.snapshot,
    shortClosed,
    'PERP-08 SHORT full close'
  )
  const shortCloseOracle = perpCloseOracle({
    side: 'SHORT',
    quantity: String(unchangedShort.lots),
    entryPrice: String(unchangedShort.openPrice),
    closeFillPrice: String(shortClosed.trade.price),
    rules
  })
  assertDecimalClose(
    shortClosed.trade.realizedPnl,
    shortCloseOracle.grossRealizedPnl,
    shortCloseOracle.tolerances.amount,
    'PERP-08 SHORT realized PnL'
  )
  assertDecimalClose(
    shortClosed.trade.fee,
    shortCloseOracle.closeFee,
    shortCloseOracle.tolerances.amount,
    'PERP-08 SHORT close fee'
  )
  const shortHistory = shortClosed.snapshot.positionHistory
    .find(({ id }) => id === short.id)
  assert(shortHistory, 'PERP-08 SHORT history')
  assertDecimalClose(
    shortHistory.realizedPnl,
    shortCloseOracle.grossRealizedPnl,
    shortCloseOracle.tolerances.amount,
    'PERP-08 SHORT history realizedPnl'
  )
  const longBeforeReject = openPositions(shortClosed.snapshot, symbol)
    .find(({ positionSide }) => positionSide === 'LONG')
  assert(longBeforeReject)
  for (const [field, tolerance] of [
    ['lots', tolerancesFromRules(rules).quantity],
    ['openPrice', tolerancesFromRules(rules).price],
    ['realizedPnl', tolerancesFromRules(rules).amount],
    ['marginHeld', tolerancesFromRules(rules).amount]
  ]) {
    assertDecimalClose(
      longBeforeReject[field],
      remainingLong[field],
      tolerance,
      `PERP-08 LONG ${field} isolation`
    )
  }
  const longAfterShortFinancials = assertPerpPositionFinancials(
    shortClosed.snapshot,
    [longBeforeReject],
    rules,
    'PERP-08 SHORT closed LONG retained'
  )
  const shortClosedCapture = await scope.capture(
    'short-fully-closed',
    shortClosed.snapshot
  )
  const shortCloseLedger = assertPerpLedgerDelta(
    partialCapture.db,
    shortClosedCapture.db,
    [
      {
        operationType: 'MARGIN_RELEASE',
        referenceType: 'POSITION',
        referenceId: short.id,
        amount: unchangedShort.marginHeld
      },
      {
        operationType: 'TRADE_FEE',
        referenceType: 'TRADE',
        referenceId: shortClosed.trade.id,
        amount: negativeAmount(shortCloseOracle.closeFee)
      }
    ],
    'PERP-08 SHORT full close'
  )
  const oversized = await context.ui.submitOrderViaUi(page, {
    side: 'SELL',
    positionSide: 'LONG',
    orderType: 'MARKET',
    amount: (
      Number(longBeforeReject.lots) + Number(effectiveQuantityStep(rules))
    ).toFixed((effectiveQuantityStep(rules).split('.')[1] ?? '').length),
    reduceOnly: true,
    expectFailure: true,
    reason: 'PERP-08 LONG slot overclose'
  })
  assertRejectedCode(
    oversized,
    'REDUCE_ONLY_EXCEEDS_POSITION',
    'PERP-08 LONG overclose'
  )
  scope.contractProbes.push({
    status: 'REJECTED',
    kind: 'HEDGE_SLOT_OVERCLOSE',
    positionSide: 'LONG',
    requestRef: oversized.requestRef,
    errorCode: responseCode(oversized)
  })
  const afterReject = await context.api.snapshotAccount(page)
  assertTradingStateEqual(
    shortClosed.snapshot,
    afterReject,
    'PERP-08 overclose zero mutation'
  )
  const finalLongClose = await context.ui.submitOrderViaUi(page, {
    side: 'SELL',
    positionSide: 'LONG',
    orderType: 'MARKET',
    amount: String(longBeforeReject.lots),
    reduceOnly: true
  })
  scope.addMutation('hedge-long-full-close-via-ui', finalLongClose)
  const empty = await waitForFilledMutation(
    scope,
    afterReject,
    finalLongClose,
    symbol
  )
  assert.equal(openPositions(empty.snapshot, symbol).length, 0)
  assertSingleFullFillMutation(
    afterReject,
    empty,
    'PERP-08 LONG final close'
  )
  const longCloseOracle = perpCloseOracle({
    side: 'LONG',
    quantity: String(longBeforeReject.lots),
    entryPrice: String(longBeforeReject.openPrice),
    closeFillPrice: String(empty.trade.price),
    rules
  })
  assertDecimalClose(
    empty.trade.realizedPnl,
    longCloseOracle.grossRealizedPnl,
    longCloseOracle.tolerances.amount,
    'PERP-08 LONG final realized PnL'
  )
  assertDecimalClose(
    empty.trade.fee,
    longCloseOracle.closeFee,
    longCloseOracle.tolerances.amount,
    'PERP-08 LONG final close fee'
  )
  const longHistory = empty.snapshot.positionHistory
    .find(({ id }) => id === long.id)
  assert(longHistory, 'PERP-08 LONG history')
  const longCumulativeRealized = Number(longPartialOracle.tradeRealizedPnl)
    + Number(longCloseOracle.grossRealizedPnl)
  assertDecimalClose(
    longHistory.realizedPnl,
    longCumulativeRealized,
    longCloseOracle.tolerances.amount,
    'PERP-08 LONG history realizedPnl'
  )
  const emptyCapture = await scope.capture('hedge-fully-closed', empty.snapshot)
  const longCloseLedger = assertPerpLedgerDelta(
    shortClosedCapture.db,
    emptyCapture.db,
    [
      {
        operationType: 'MARGIN_RELEASE',
        referenceType: 'POSITION',
        referenceId: long.id,
        amount: longBeforeReject.marginHeld
      },
      {
        operationType: 'TRADE_FEE',
        referenceType: 'TRADE',
        referenceId: empty.trade.id,
        amount: negativeAmount(longCloseOracle.closeFee)
      }
    ],
    'PERP-08 LONG final close'
  )
  await ensurePerpSettings(scope, { positionMode: 'ONE_WAY' })
  const final = await context.api.snapshotAccount(page)
  assert.equal(final.settings.positionMode, 'ONE_WAY')
  const finalCapture = await scope.capture('one-way-restored', final)
  scope.oracleEvidence.push({
    kind: 'PERP_HEDGE_SLOT_ISOLATION',
    longPositionId: long.id,
    shortPositionId: short.id,
    longPartialQuantity: partialQuantity,
    shortUnchangedQuantity: unchangedShort.lots,
    opening: {
      long: {
        hold: longHold,
        financials: longOpeningFinancials,
        ledger: longOpeningLedger
      },
      short: {
        hold: shortHold,
        financials: bothFinancials,
        ledger: shortOpeningLedger
      }
    },
    partialLong: {
      ...longPartialOracle,
      financials: partialFinancials,
      ledger: partialLedger
    },
    shortClose: {
      ...shortCloseOracle,
      retainedLong: longAfterShortFinancials,
      ledger: shortCloseLedger
    },
    longClose: {
      ...longCloseOracle,
      cumulativeRealized: longCumulativeRealized,
      ledger: longCloseLedger
    }
  })
  return {
    finalSnapshot: final,
    finalDb: finalCapture.db
  }
}

async function runPerpMarginJourney(scope) {
  const { context, page } = scope
  const symbol = 'BTCUSDT-PERP'
  const market = await context.api.snapshotMarket(symbol)
  const rules = rulesFor(market)
  const quantity = stepAlignedQuantity(market, 0.01)
  await context.ui.openTradePanel(page, {
    product: 'perpetual',
    symbol
  })
  const versions = []
  for (const marginMode of ['ISOLATED', 'CROSS', 'ISOLATED']) {
    await ensurePerpSettings(scope, {
      positionMode: 'ONE_WAY',
      marginMode,
      leverage: 10,
      quantityUnit: 'BASE'
    })
    const snapshot = await context.api.snapshotAccount(page)
    const settings = findSymbolSettings(snapshot, symbol)
    assert.equal(settings.marginMode, marginMode)
    versions.push(Number(settings.version))
  }
  assert(
    versions.every((version, index) => index === 0 || version > versions[index - 1]),
    'PERP-09 margin settings version must increase'
  )
  const beforeOpen = await context.api.snapshotAccount(page)
  const open = await context.ui.submitOrderViaUi(page, {
    side: 'BUY',
    orderType: 'MARKET',
    amount: quantity
  })
  scope.addMutation('isolated-position-open-via-ui', open)
  const opened = await waitForFilledMutation(scope, beforeOpen, open, symbol)
  let position = openPositions(opened.snapshot, symbol).at(0)
  assert.equal(position.marginMode, 'ISOLATED')
  assertSingleFullFillMutation(beforeOpen, opened, 'PERP-09 isolated opening')
  const isolatedOpeningHold = perpOpeningHoldOracle({
    baseQuantity: String(opened.trade.lots),
    worstPrice: String(opened.trade.price),
    leverage: '10',
    rules
  })
  assertDecimalClose(
    opened.trade.fee,
    isolatedOpeningHold.feeBuffer,
    isolatedOpeningHold.tolerances.amount,
    'PERP-09 isolated opening fee'
  )
  assertDecimalClose(
    position.marginHeld,
    isolatedOpeningHold.openingInitialMargin,
    isolatedOpeningHold.tolerances.amount,
    'PERP-09 isolated opening margin'
  )
  const isolatedOpeningFinancials = assertPerpPositionFinancials(
    opened.snapshot,
    [position],
    rules,
    'PERP-09 isolated opening'
  )
  const openedCapture = await scope.capture('isolated-opened', opened.snapshot)
  const isolatedOpeningLedger = assertPerpLedgerDelta(
    scope.beforeDb,
    openedCapture.db,
    [
      {
        operationType: 'MARGIN_HOLD',
        referenceType: 'POSITION',
        referenceId: position.id,
        amount: isolatedOpeningHold.openingInitialMargin
      },
      {
        operationType: 'TRADE_FEE',
        referenceType: 'TRADE',
        referenceId: opened.trade.id,
        amount: negativeAmount(isolatedOpeningHold.feeBuffer)
      }
    ],
    'PERP-09 isolated opening'
  )

  const blockedSwitch = await context.ui.setPerpetualSettingsViaUi(page, {
    marginMode: 'CROSS',
    expectFailure: true,
    reason: 'PERP-09 margin mode switch blocked'
  })
  assert.equal(blockedSwitch.length, 1)
  assertRejectedCode(
    blockedSwitch[0],
    'MARGIN_MODE_SWITCH_BLOCKED',
    'PERP-09 margin mode switch'
  )
  scope.contractProbes.push({
    status: 'REJECTED',
    kind: 'MARGIN_MODE_SWITCH_BLOCKED',
    requestRef: blockedSwitch[0].requestRef,
    errorCode: responseCode(blockedSwitch[0])
  })
  assertTradingStateEqual(
    opened.snapshot,
    await context.api.snapshotAccount(page),
    'PERP-09 blocked margin mode switch zero mutation'
  )

  const addBefore = opened.snapshot
  const beforeAddPosition = position
  const add = await context.ui.positionActionViaUi(page, {
    positionId: position.id,
    positionSide: position.positionSide,
    action: 'ADJUST_MARGIN',
    marginDirection: 'ADD',
    marginAmount: '10'
  })
  scope.addMutation('isolated-margin-add-via-ui', add)
  const added = await waitForAccount(
    context,
    page,
    'PERP-09 isolated margin add',
    (candidate) => {
      const current = openPositions(candidate, symbol).find(({ id }) => id === position.id)
      return current && Number(current.marginHeld) >= Number(position.marginHeld) + 10
        ? { snapshot: candidate, position: current }
        : false
    }
  )
  assertDecimalClose(
    added.snapshot.summary.balance,
    addBefore.summary.balance,
    '0.00000001',
    'PERP-09 add balance'
  )
  assertDecimalClose(
    added.snapshot.summary.usedMargin,
    Number(addBefore.summary.usedMargin) + 10,
    '0.00000001',
    'PERP-09 add used margin'
  )
  assertDecimalClose(
    added.snapshot.summary.freeMargin,
    Number(addBefore.summary.freeMargin) - 10,
    '0.00000001',
    'PERP-09 add free margin'
  )
  assertDecimalClose(
    added.position.lots,
    position.lots,
    tolerancesFromRules(rules).quantity,
    'PERP-09 add quantity'
  )
  for (const [field, tolerance] of [
    ['openPrice', tolerancesFromRules(rules).price],
    ['realizedPnl', tolerancesFromRules(rules).amount]
  ]) {
    assertDecimalClose(
      added.position[field],
      position[field],
      tolerance,
      `PERP-09 add ${field}`
    )
  }
  assertDecimalClose(
    added.snapshot.summary.equity,
    addBefore.summary.equity,
    '0.00000001',
    'PERP-09 add equity'
  )
  assertDecimalClose(
    added.position.marginHeld,
    Number(position.marginHeld) + 10,
    '0.00000001',
    'PERP-09 add position margin'
  )
  assert(
    Number.isFinite(Number(position.liquidationPrice))
      && Number.isFinite(Number(added.position.liquidationPrice))
      && Number(added.position.liquidationPrice) < Number(position.liquidationPrice),
    'PERP-09 ADD must move LONG liquidation price farther away'
  )
  const addedFinancials = assertPerpPositionFinancials(
    added.snapshot,
    [added.position],
    rules,
    'PERP-09 margin added'
  )
  const addedCapture = await scope.capture('margin-added', added.snapshot)
  const addLedger = assertPerpLedgerDelta(
    openedCapture.db,
    addedCapture.db,
    [{
      operationType: 'MARGIN_HOLD',
      referenceType: 'POSITION',
      referenceId: added.position.id,
      amount: '10'
    }],
    'PERP-09 margin add'
  )

  const stale = await replayCapturedMutation(
    scope,
    add,
    (body) => body,
    {
      expectFailure: true,
      reason: 'PERP-09 stale position version'
    }
  )
  assertRejectedCode(
    stale,
    'POSITION_VERSION_CONFLICT',
    'PERP-09 stale position version'
  )
  scope.replayProbes.push({
    status: 'REJECTED',
    kind: 'STALE_POSITION_VERSION',
    requestRef: stale.requestRef,
    originalRequestRef: add.requestRef,
    errorCode: responseCode(stale)
  })
  assertTradingStateEqual(
    added.snapshot,
    await context.api.snapshotAccount(page),
    'PERP-09 stale version zero mutation'
  )

  position = added.position
  const reduce = await context.ui.positionActionViaUi(page, {
    positionId: position.id,
    positionSide: position.positionSide,
    action: 'ADJUST_MARGIN',
    marginDirection: 'REDUCE',
    marginAmount: '5'
  })
  scope.addMutation('isolated-margin-reduce-via-ui', reduce)
  const reduced = await waitForAccount(
    context,
    page,
    'PERP-09 isolated margin reduce',
    (candidate) => {
      const current = openPositions(candidate, symbol).find(({ id }) => id === position.id)
      return current && Number(current.marginHeld) <= Number(position.marginHeld) - 5
        ? { snapshot: candidate, position: current }
        : false
    }
  )
  assertDecimalClose(
    reduced.snapshot.summary.balance,
    added.snapshot.summary.balance,
    '0.00000001',
    'PERP-09 reduce balance'
  )
  assertDecimalClose(
    reduced.snapshot.summary.equity,
    added.snapshot.summary.equity,
    '0.00000001',
    'PERP-09 reduce equity'
  )
  assertDecimalClose(
    reduced.snapshot.summary.usedMargin,
    Number(added.snapshot.summary.usedMargin) - 5,
    '0.00000001',
    'PERP-09 reduce used margin'
  )
  assertDecimalClose(
    reduced.snapshot.summary.freeMargin,
    Number(added.snapshot.summary.freeMargin) + 5,
    '0.00000001',
    'PERP-09 reduce free margin'
  )
  assertDecimalClose(
    reduced.position.marginHeld,
    Number(added.position.marginHeld) - 5,
    '0.00000001',
    'PERP-09 reduce position margin'
  )
  for (const [field, tolerance] of [
    ['lots', tolerancesFromRules(rules).quantity],
    ['openPrice', tolerancesFromRules(rules).price],
    ['realizedPnl', tolerancesFromRules(rules).amount]
  ]) {
    assertDecimalClose(
      reduced.position[field],
      added.position[field],
      tolerance,
      `PERP-09 reduce ${field}`
    )
  }
  assert(
    Number.isFinite(Number(reduced.position.liquidationPrice))
      && Number(reduced.position.liquidationPrice) > Number(added.position.liquidationPrice)
      && Number(reduced.position.liquidationPrice) < Number(beforeAddPosition.liquidationPrice),
    'PERP-09 REDUCE must move LONG liquidation price back by exactly the released margin'
  )
  const reducedFinancials = assertPerpPositionFinancials(
    reduced.snapshot,
    [reduced.position],
    rules,
    'PERP-09 margin reduced'
  )
  const reducedCapture = await scope.capture('margin-reduced', reduced.snapshot)
  const reduceLedger = assertPerpLedgerDelta(
    addedCapture.db,
    reducedCapture.db,
    [{
      operationType: 'MARGIN_RELEASE',
      referenceType: 'POSITION',
      referenceId: reduced.position.id,
      amount: '5'
    }],
    'PERP-09 margin reduce'
  )
  const beforeUnsafe = reduced.snapshot
  const unsafe = await context.ui.positionActionViaUi(page, {
    positionId: reduced.position.id,
    positionSide: reduced.position.positionSide,
    action: 'ADJUST_MARGIN',
    marginDirection: 'REDUCE',
    marginAmount: String(reduced.position.marginHeld),
    expectFailure: true,
    reason: 'PERP-09 unsafe margin reduction'
  })
  assertRejectedCode(
    unsafe,
    'MARGIN_REDUCTION_UNSAFE',
    'PERP-09 unsafe margin reduction'
  )
  assertTradingStateEqual(
    beforeUnsafe,
    await context.api.snapshotAccount(page),
    'PERP-09 unsafe reduction zero mutation'
  )
  scope.contractProbes.push({
    status: 'REJECTED',
    kind: 'MARGIN_REDUCTION_UNSAFE',
    requestRef: unsafe.requestRef,
    errorCode: responseCode(unsafe)
  })

  const isolatedClose = await closePositionFromUi(scope, reduced.position)
  const isolatedClosed = await waitForFilledMutation(
    scope,
    beforeUnsafe,
    isolatedClose,
    symbol
  )
  assert.equal(openPositions(isolatedClosed.snapshot, symbol).length, 0)
  const isolatedCloseOracle = perpCloseOracle({
    side: 'LONG',
    quantity: String(reduced.position.lots),
    entryPrice: String(reduced.position.openPrice),
    closeFillPrice: String(isolatedClosed.trade.price),
    rules
  })
  assertDecimalClose(
    isolatedClosed.trade.realizedPnl,
    isolatedCloseOracle.grossRealizedPnl,
    isolatedCloseOracle.tolerances.amount,
    'PERP-09 isolated close realized PnL'
  )
  assertDecimalClose(
    isolatedClosed.trade.fee,
    isolatedCloseOracle.closeFee,
    isolatedCloseOracle.tolerances.amount,
    'PERP-09 isolated close fee'
  )
  const isolatedHistory = isolatedClosed.snapshot.positionHistory
    .find(({ id }) => id === reduced.position.id)
  assert(isolatedHistory, 'PERP-09 isolated history')
  assertDecimalClose(
    isolatedHistory.realizedPnl,
    isolatedCloseOracle.grossRealizedPnl,
    isolatedCloseOracle.tolerances.amount,
    'PERP-09 isolated history realizedPnl'
  )
  const isolatedClosedCapture = await scope.capture(
    'isolated-closed',
    isolatedClosed.snapshot
  )
  const isolatedCloseLedger = assertPerpLedgerDelta(
    reducedCapture.db,
    isolatedClosedCapture.db,
    [
      {
        operationType: 'MARGIN_RELEASE',
        referenceType: 'POSITION',
        referenceId: reduced.position.id,
        amount: reduced.position.marginHeld
      },
      {
        operationType: 'TRADE_FEE',
        referenceType: 'TRADE',
        referenceId: isolatedClosed.trade.id,
        amount: negativeAmount(isolatedCloseOracle.closeFee)
      }
    ],
    'PERP-09 isolated close'
  )
  await ensurePerpSettings(scope, { marginMode: 'CROSS' })
  const crossOpen = await context.ui.submitOrderViaUi(page, {
    side: 'BUY',
    orderType: 'MARKET',
    amount: quantity
  })
  scope.addMutation('cross-position-open-via-ui', crossOpen)
  const crossOpened = await waitForFilledMutation(
    scope,
    isolatedClosed.snapshot,
    crossOpen,
    symbol
  )
  const cross = openPositions(crossOpened.snapshot, symbol).at(0)
  assert.equal(cross.marginMode, 'CROSS')
  assertSingleFullFillMutation(
    isolatedClosed.snapshot,
    crossOpened,
    'PERP-09 CROSS opening'
  )
  const crossOpeningHold = perpOpeningHoldOracle({
    baseQuantity: String(crossOpened.trade.lots),
    worstPrice: String(crossOpened.trade.price),
    leverage: '10',
    rules
  })
  assertDecimalClose(
    crossOpened.trade.fee,
    crossOpeningHold.feeBuffer,
    crossOpeningHold.tolerances.amount,
    'PERP-09 CROSS opening fee'
  )
  assertDecimalClose(
    cross.marginHeld,
    crossOpeningHold.openingInitialMargin,
    crossOpeningHold.tolerances.amount,
    'PERP-09 CROSS opening margin'
  )
  const crossOpeningFinancials = assertPerpPositionFinancials(
    crossOpened.snapshot,
    [cross],
    rules,
    'PERP-09 CROSS opening'
  )
  const crossOpenedCapture = await scope.capture(
    'cross-opened',
    crossOpened.snapshot
  )
  const crossOpeningLedger = assertPerpLedgerDelta(
    isolatedClosedCapture.db,
    crossOpenedCapture.db,
    [
      {
        operationType: 'MARGIN_HOLD',
        referenceType: 'POSITION',
        referenceId: cross.id,
        amount: crossOpeningHold.openingInitialMargin
      },
      {
        operationType: 'TRADE_FEE',
        referenceType: 'TRADE',
        referenceId: crossOpened.trade.id,
        amount: negativeAmount(crossOpeningHold.feeBuffer)
      }
    ],
    'PERP-09 CROSS opening'
  )
  const availability = await inspectMarginActionAvailability(page, cross)
  assert.equal(availability.adjustMarginDisabled, true)
  assert.equal(availability.requestSent, false)
  scope.contractProbes.push({
    status: 'PASS',
    kind: 'CROSS_MARGIN_UI_DISABLED',
    ...availability
  })
  const crossUrl = add.rawRequest.url.replace(
    /\/positions\/[^/]+\/margin$/,
    `/positions/${cross.id}/margin`
  )
  const invalidMode = await replayCapturedMutation(
    scope,
    add,
    (body) => ({
      ...body,
      expectedVersion: cross.version
    }),
    {
      url: crossUrl,
      expectFailure: true,
      reason: 'PERP-09 CROSS margin adjustment'
    }
  )
  assertRejectedCode(
    invalidMode,
    'INVALID_MARGIN_MODE',
    'PERP-09 CROSS margin adjustment'
  )
  scope.contractProbes.push({
    status: 'REJECTED',
    kind: 'L7_CROSS_MARGIN_ADJUSTMENT',
    requestRef: invalidMode.requestRef,
    errorCode: responseCode(invalidMode)
  })
  assertTradingStateEqual(
    crossOpened.snapshot,
    await context.api.snapshotAccount(page),
    'PERP-09 CROSS margin rejection zero mutation'
  )
  const crossClose = await closePositionFromUi(scope, cross)
  const final = await waitForFilledMutation(
    scope,
    crossOpened.snapshot,
    crossClose,
    symbol
  )
  assert.equal(openPositions(final.snapshot, symbol).length, 0)
  const crossCloseOracle = perpCloseOracle({
    side: 'LONG',
    quantity: String(cross.lots),
    entryPrice: String(cross.openPrice),
    closeFillPrice: String(final.trade.price),
    rules
  })
  assertDecimalClose(
    final.trade.realizedPnl,
    crossCloseOracle.grossRealizedPnl,
    crossCloseOracle.tolerances.amount,
    'PERP-09 CROSS close realized PnL'
  )
  assertDecimalClose(
    final.trade.fee,
    crossCloseOracle.closeFee,
    crossCloseOracle.tolerances.amount,
    'PERP-09 CROSS close fee'
  )
  const crossHistory = final.snapshot.positionHistory
    .find(({ id }) => id === cross.id)
  assert(crossHistory, 'PERP-09 CROSS history')
  assertDecimalClose(
    crossHistory.realizedPnl,
    crossCloseOracle.grossRealizedPnl,
    crossCloseOracle.tolerances.amount,
    'PERP-09 CROSS history realizedPnl'
  )
  const finalCapture = await scope.capture('fully-closed', final.snapshot)
  const crossCloseLedger = assertPerpLedgerDelta(
    crossOpenedCapture.db,
    finalCapture.db,
    [
      {
        operationType: 'MARGIN_RELEASE',
        referenceType: 'POSITION',
        referenceId: cross.id,
        amount: cross.marginHeld
      },
      {
        operationType: 'TRADE_FEE',
        referenceType: 'TRADE',
        referenceId: final.trade.id,
        amount: negativeAmount(crossCloseOracle.closeFee)
      }
    ],
    'PERP-09 CROSS close'
  )
  scope.oracleEvidence.push({
    kind: 'PERP_MARGIN_MODE_AND_MANUAL_MARGIN',
    settingVersions: versions,
    addedAmount: '10',
    reducedAmount: '5',
    isolatedPositionId: position.id,
    crossPositionId: cross.id,
    isolated: {
      opening: {
        hold: isolatedOpeningHold,
        financials: isolatedOpeningFinancials,
        ledger: isolatedOpeningLedger
      },
      add: { financials: addedFinancials, ledger: addLedger },
      reduce: { financials: reducedFinancials, ledger: reduceLedger },
      close: { ...isolatedCloseOracle, ledger: isolatedCloseLedger }
    },
    cross: {
      opening: {
        hold: crossOpeningHold,
        financials: crossOpeningFinancials,
        ledger: crossOpeningLedger
      },
      close: { ...crossCloseOracle, ledger: crossCloseLedger }
    }
  })
  return {
    finalSnapshot: final.snapshot,
    finalDb: finalCapture.db
  }
}

async function runPerpValidationJourney(scope) {
  const { context, page } = scope
  const symbol = 'BTCUSDT-PERP'
  const market = await context.api.snapshotMarket(symbol)
  const rules = rulesFor(market)
  const quantity = stepAlignedQuantity(market, 0.01)
  await context.ui.openTradePanel(page, {
    product: 'perpetual',
    symbol
  })
  await ensurePerpSettings(scope, {
    positionMode: 'ONE_WAY',
    marginMode: 'CROSS',
    leverage: 10,
    quantityUnit: 'BASE'
  })
  const surface = await inspectPerpetualOrderSurface(page)
  assert.equal(surface.orderTabs.includes('STOP'), false)
  assert.equal(surface.orderTabs.some((label) => /OCO/i.test(label)), false)
  assert.equal(surface.positionSideVisible, false)
  scope.contractProbes.push({
    status: 'PASS',
    kind: 'PERPETUAL_UI_ALLOWLIST',
    ...surface
  })

  const step = Number(effectiveQuantityStep(rules))
  const minQuantity = Number(rules.minQty ?? rules.minimumQuantity ?? step)
  const maxQuantity = Number(rules.maxQty ?? rules.maximumQuantity ?? 100)
  const uiInputProbes = [
    { kind: 'VALIDATION_ERROR', amount: '' },
    { kind: 'ZERO_QUANTITY', amount: '0' },
    { kind: 'NEGATIVE_QUANTITY', amount: '-1' },
    { kind: 'QUANTITY_TOO_SMALL', amount: String(minQuantity / 2) },
    {
      kind: 'QUANTITY_STEP_MISMATCH',
      amount: String(Number(quantity) + step / 2)
    },
    {
      kind: 'QUANTITY_TOO_LARGE',
      amount: String(maxQuantity + step)
    },
    { kind: 'INSUFFICIENT_MARGIN', amount: String(maxQuantity) }
  ]
  for (const input of uiInputProbes) {
    const probe = await probeDisabledOrderSubmission(page, {
      side: 'BUY',
      orderType: 'MARKET',
      amount: input.amount
    })
    assert.equal(probe.requestSent, false)
    scope.contractProbes.push({
      status: 'PASS',
      kind: 'PERPETUAL_UI_INPUT_GUARD',
      validation: input.kind,
      input: input.amount || 'EMPTY',
      requestSent: false,
      guarded: probe.guarded,
      confirmationOpened: probe.confirmationOpen
    })
  }

  const beforeLegal = await context.api.snapshotAccount(page)
  const legal = await context.ui.submitOrderViaUi(page, {
    side: 'BUY',
    orderType: 'MARKET',
    amount: quantity
  })
  scope.addMutation('perpetual-legal-capture-via-ui', legal)
  const opened = await waitForFilledMutation(
    scope,
    beforeLegal,
    legal,
    symbol
  )
  const position = openPositions(opened.snapshot, symbol).at(0)
  await closePositionFromUi(scope, position)
  const baseline = await waitForAccount(
    context,
    page,
    'PERP-12 legal capture cleanup',
    (candidate) => openPositions(candidate, symbol).length === 0 && candidate
  )
  await scope.capture('submitted', baseline)

  const oneWayProbes = [
    {
      kind: 'INVALID_PERPETUAL_ORDER_TYPE',
      expected: 'INVALID_PERPETUAL_ORDER_TYPE',
      patch: (body) => ({
        ...body,
        orderType: 'STOP',
        triggerPrice: Number(quoteDecimal(market, 'mark'))
      })
    },
    {
      kind: 'INVALID_PERPETUAL_ORDER_FIELDS',
      expected: 'INVALID_PERPETUAL_ORDER_FIELDS',
      patch: (body) => ({
        ...body,
        orderType: 'MARKET',
        triggerPrice: Number(quoteDecimal(market, 'mark')),
        triggerPriceType: 'MARK_PRICE'
      })
    },
    {
      kind: 'INVALID_ONE_WAY_POSITION_SIDE',
      expected: 'INVALID_POSITION_SIDE',
      patch: (body) => ({ ...body, positionSide: 'LONG' })
    },
    {
      kind: 'VALIDATION_ERROR',
      expected: 'VALIDATION_ERROR',
      patch: (body) => ({ ...body, lots: null, quantity: null })
    },
    {
      kind: 'QUANTITY_STEP_MISMATCH',
      expected: 'QUANTITY_STEP_MISMATCH',
      patch: (body) => ({
        ...body,
        quantity: Number(quantity) + step / 2
      })
    },
    {
      kind: 'QUANTITY_TOO_LARGE',
      expected: 'QUANTITY_TOO_LARGE',
      patch: (body) => ({ ...body, quantity: maxQuantity + step })
    },
    {
      kind: 'INSUFFICIENT_MARGIN',
      expected: 'INSUFFICIENT_MARGIN',
      patch: (body) => ({ ...body, quantity: maxQuantity })
    }
  ]
  for (const probe of oneWayProbes) {
    const capture = await replayCapturedMutation(
      scope,
      legal,
      (body) => ({
        ...probe.patch(body),
        idempotencyKey: randomUUID(),
        clientOrderId: randomUUID()
      }),
      {
        expectFailure: true,
        reason: `PERP-12 ${probe.kind}`
      }
    )
    assertRejectedCode(capture, probe.expected, `PERP-12 ${probe.kind}`)
    scope.contractProbes.push({
      status: 'REJECTED',
      kind: probe.kind,
      requestRef: capture.requestRef,
      errorCode: responseCode(capture)
    })
    assertTradingStateEqual(
      baseline,
      await context.api.snapshotAccount(page),
      `PERP-12 ${probe.kind} zero mutation`
    )
  }

  await ensurePerpSettings(scope, {
    positionMode: 'HEDGE',
    marginMode: 'CROSS',
    leverage: 10,
    quantityUnit: 'BASE'
  })
  const hedgeSurface = await inspectPerpetualOrderSurface(page)
  assert.equal(hedgeSurface.positionSideVisible, true)
  scope.contractProbes.push({
    status: 'PASS',
    kind: 'PERPETUAL_HEDGE_UI_POSITION_SIDE',
    ...hedgeSurface
  })
  const hedgeBaseline = await context.api.snapshotAccount(page)
  const invalidHedge = await replayCapturedMutation(
    scope,
    legal,
    (body) => ({
      ...body,
      positionSide: 'BOTH',
      idempotencyKey: randomUUID(),
      clientOrderId: randomUUID()
    }),
    {
      expectFailure: true,
      reason: 'PERP-12 INVALID_HEDGE_POSITION_SIDE'
    }
  )
  assertRejectedCode(
    invalidHedge,
    'INVALID_POSITION_SIDE',
    'PERP-12 INVALID_HEDGE_POSITION_SIDE'
  )
  scope.contractProbes.push({
    status: 'REJECTED',
    kind: 'INVALID_HEDGE_POSITION_SIDE',
    requestRef: invalidHedge.requestRef,
    errorCode: responseCode(invalidHedge)
  })
  assertTradingStateEqual(
    hedgeBaseline,
    await context.api.snapshotAccount(page),
    'PERP-12 INVALID_HEDGE_POSITION_SIDE zero mutation'
  )
  await ensurePerpSettings(scope, {
    positionMode: 'ONE_WAY',
    marginMode: 'CROSS',
    leverage: 10,
    quantityUnit: 'BASE'
  })
  const finalSnapshot = await context.api.snapshotAccount(page)
  const db = await context.db.snapshotTradingRows(finalSnapshot.account.id)
  const partialOrders = Array.isArray(db.orderRows)
    ? db.orderRows.filter(({ status }) => status === 'PARTIALLY_FILLED')
    : []
  assert.equal(partialOrders.length, 0)
  const canonicalTrades = (db.tradeRows ?? []).filter(({ canonical_full_fill }) => (
    canonical_full_fill === true
  ))
  assert.equal(
    canonicalTrades.length,
    (db.tradeRows ?? []).length,
    'PERP-12 every legal Trade is canonical full fill'
  )
  const fullFillProof = backendFullFillContractProof(context)
  scope.oracleEvidence.push({
    kind: 'PERPETUAL_REJECTION_AND_FULL_FILL_CONTRACT',
    rejected: [
      ...oneWayProbes.map(({ kind, expected }) => ({ kind, expected })),
      {
        kind: 'INVALID_HEDGE_POSITION_SIDE',
        expected: 'INVALID_POSITION_SIDE'
      }
    ],
    partialOrderRows: partialOrders.length,
    canonicalTradeRows: canonicalTrades.length,
    fullFillContract: fullFillProof
  })
  return { finalSnapshot, finalDb: db }
}

async function runCloseAllJourney(scope) {
  const normal = await openBatchPositions(scope, 'normal')
  const normalCapture = await scope.context.ui.closeAllPositionsViaUi(scope.page)
  scope.addMutation('close-all-positions-via-ui', normalCapture)
  const normalResponse = parsedResponse(normalCapture)?.data
    ?? parsedResponse(normalCapture)
  assert.equal(Array.isArray(normalResponse?.items), true)
  assert.equal(normalResponse.items.length, normal.positionIds.length)
  assert.equal(
    normalResponse.items.every(({ status, orderId }) => (
      ['SUCCESS', 'CLOSED', 'FILLED'].includes(status) && Boolean(orderId)
    )),
    true,
    'BATCH-02 normal response items'
  )
  const normalClosed = await waitForAccount(
    scope.context,
    scope.page,
    'BATCH-02 normal close-all',
    (candidate) => openPositions(candidate).length === 0 && candidate
  )
  const normalClosedEvidence = await scope.capture(
    'normal-close-all',
    normalClosed
  )
  const normalOracle = assertBatchCloseTrades(
    normalClosed,
    normalClosedEvidence.db,
    normal.db,
    normalResponse.items,
    normal.positions,
    'BATCH-02 normal'
  )

  const partial = await openBatchPositions(scope, 'partial')
  const adminPage = await scope.getAdminPage()
  const bindingFixture = await scope.context.fixtures.providerBindings(adminPage, {
    symbol: 'SOLUSDT-PERP',
    enabledProviders: []
  })
  scope.fixtureActions.push({
    action: 'disable-provider-bindings',
    symbol: 'SOLUSDT-PERP',
    before: bindingFixture.before,
    after: bindingFixture.after
  })
  let bindingRestored = false
  const restoreBinding = async () => {
    if (bindingRestored) return
    await bindingFixture.restore()
    bindingRestored = true
  }
  scope.registerFixtureRestore({
    action: 'restore-provider-bindings',
    symbol: 'SOLUSDT-PERP'
  }, restoreBinding)
  const partialCapture = await scope.context.ui.closeAllPositionsViaUi(scope.page)
  scope.addMutation('close-all-positions-partial-via-ui', partialCapture)
  const partialResponse = parsedResponse(partialCapture)?.data
    ?? parsedResponse(partialCapture)
  assert.equal(Array.isArray(partialResponse?.items), true)
  assert.equal(partialResponse.items.length, partial.positionIds.length)
  const failures = partialResponse.items.filter(({ errorCode }) => Boolean(errorCode))
  const successes = partialResponse.items.filter(({ orderId, errorCode }) => (
    Boolean(orderId) && !errorCode
  ))
  assert.equal(failures.length, 1, 'BATCH-02 partial run failure count')
  assert.equal(successes.length, partial.positionIds.length - 1)
  const partiallyClosed = await waitForAccount(
    scope.context,
    scope.page,
    'BATCH-02 partial close-all',
    (candidate) => openPositions(candidate).length === 1 && candidate
  )
  const remaining = openPositions(partiallyClosed).at(0)
  assert.equal(remaining.symbol, 'SOLUSDT-PERP')
  const partialClosedEvidence = await scope.capture(
    'partial-close-all',
    partiallyClosed
  )
  const partialOracle = assertBatchCloseTrades(
    partiallyClosed,
    partialClosedEvidence.db,
    partial.db,
    successes,
    partial.positions.filter(({ id }) => id !== remaining.id),
    'BATCH-02 partial'
  )
  await restoreBinding()
  scope.fixtureActions.push({
    action: 'restore-provider-bindings',
    symbol: 'SOLUSDT-PERP',
    status: 'PASS'
  })
  const retry = await scope.context.ui.closeAllPositionsViaUi(scope.page)
  scope.addMutation('close-all-positions-retry-via-ui', retry)
  const retryResponse = parsedResponse(retry)?.data
    ?? parsedResponse(retry)
  assert.equal(Array.isArray(retryResponse?.items), true)
  const retrySuccesses = retryResponse.items.filter(({ orderId, errorCode }) => (
    Boolean(orderId) && !errorCode
  ))
  assert.equal(retrySuccesses.length, 1, 'BATCH-02 retry success count')
  const final = await waitForAccount(
    scope.context,
    scope.page,
    'BATCH-02 remaining close retry',
    (candidate) => openPositions(candidate).length === 0 && candidate
  )
  const finalEvidence = await scope.capture(
    'partial-retry-closed',
    final
  )
  const remainingEvidence = partial.positions.find(({ id }) => id === remaining.id)
  assert(remainingEvidence, 'BATCH-02 remaining position evidence')
  const retryOracle = assertBatchCloseTrades(
    final,
    finalEvidence.db,
    partialClosedEvidence.db,
    retrySuccesses,
    [remainingEvidence],
    'BATCH-02 retry'
  )
  assert.equal(
    new Set([...successes, ...retrySuccesses].map(({ positionId }) => positionId)).size,
    partial.positionIds.length,
    'BATCH-02 partial and retry close each position once'
  )
  scope.oracleEvidence.push({
    kind: 'BATCH_CLOSE_ALL',
    normal: {
      requestRef: normalCapture.requestRef,
      items: normalResponse.items,
      openingLedger: normal.openingLedger,
      financial: normalOracle
    },
    partial: {
      requestRef: partialCapture.requestRef,
      items: partialResponse.items,
      remainingPositionId: remaining.id,
      openingLedger: partial.openingLedger,
      financial: partialOracle
    },
    retry: {
      requestRef: retry.requestRef,
      items: retryResponse.items,
      financial: retryOracle
    }
  })
  return { finalSnapshot: final, finalDb: finalEvidence.db }
}

async function runSpotValidationJourney(scope) {
  const { context, page } = scope
  const market = await context.api.snapshotMarket('BTCUSDT')
  const rules = rulesFor(market)
  await context.ui.openTradePanel(page, {
    product: 'spot',
    symbol: 'BTCUSDT'
  })
  for (const value of ['', '0', '-1', '0.00000001', '50001']) {
    const probe = await probeDisabledOrderSubmission(page, {
      side: 'BUY',
      orderType: 'MARKET',
      amount: value
    })
    assert.equal(probe.requestSent, false, `SPOT-02 input ${value || 'empty'} request`)
    assert.equal(probe.guarded, true, `SPOT-02 input ${value || 'empty'} UI guard`)
    scope.contractProbes.push({
      status: 'PASS',
      kind: 'UI_INPUT_GUARD',
      input: value || 'EMPTY',
      requestSent: false,
      visibleError: probe.visibleError,
      submitDisabled: probe.submitDisabled
    })
  }
  const emptySell = await probeDisabledOrderSubmission(page, {
    side: 'SELL',
    orderType: 'MARKET',
    amount: stepAlignedQuantity(market, 0.001)
  })
  assert.equal(emptySell.requestSent, false, 'SPOT-02 empty BTC sell request')
  assert.equal(emptySell.guarded, true, 'SPOT-02 empty BTC sell UI guard')

  const beforeSeed = await context.api.snapshotAccount(page)
  const seed = await context.ui.submitOrderViaUi(page, {
    side: 'BUY',
    orderType: 'MARKET',
    amount: '10'
  })
  scope.addMutation('legal-capture-seed-buy-via-ui', seed)
  const seeded = await waitForFilledMutation(scope, beforeSeed, seed, 'BTCUSDT')
  const seedWallet = findWallet(seeded.snapshot, 'SPOT', 'BTC')
  const seedSellQuantity = floorToStep(
    String(seedWallet.available),
    effectiveQuantityStep(rules)
  )
  const seedSell = await context.ui.submitOrderViaUi(page, {
    side: 'SELL',
    orderType: 'MARKET',
    amount: seedSellQuantity
  })
  scope.addMutation('legal-capture-seed-cleanup-via-ui', seedSell)
  const cleanBaseline = await waitForFilledMutation(
    scope,
    seeded.snapshot,
    seedSell,
    'BTCUSDT'
  )
  assertSpotDust(cleanBaseline.snapshot, market, 'BTC', 'SPOT-02')
  await scope.capture('submitted', cleanBaseline.snapshot)

  const invalidUnit = await replayCapturedMutation(
    scope,
    seed,
    (body) => ({
      ...body,
      quantityUnit: 'BASE',
      idempotencyKey: randomUUID(),
      clientOrderId: randomUUID()
    }),
    {
      expectFailure: true,
      reason: 'SPOT-02 invalid Spot quantity unit'
    }
  )
  assertRejectedCode(
    invalidUnit,
    'INVALID_QUANTITY_UNIT',
    'SPOT-02 invalid quantity unit'
  )
  scope.contractProbes.push({
    status: 'REJECTED',
    kind: 'L7_INVALID_QUANTITY_UNIT',
    requestRef: invalidUnit.requestRef,
    errorCode: responseCode(invalidUnit)
  })

  const insufficient = await replayCapturedMutation(
    scope,
    seed,
    (body) => ({
      ...body,
      quantity: 50001,
      idempotencyKey: randomUUID(),
      clientOrderId: randomUUID()
    }),
    {
      expectFailure: true,
      reason: 'SPOT-02 insufficient balance'
    }
  )
  assertRejectedCode(
    insufficient,
    'INSUFFICIENT_BALANCE',
    'SPOT-02 insufficient balance'
  )
  scope.contractProbes.push({
    status: 'REJECTED',
    kind: 'L7_INSUFFICIENT_BALANCE',
    requestRef: insufficient.requestRef,
    errorCode: responseCode(insufficient)
  })
  const after = await context.api.snapshotAccount(page)
  assertTradingStateEqual(
    cleanBaseline.snapshot,
    after,
    'SPOT-02 rejection zero mutation'
  )
  scope.oracleEvidence.push({
    kind: 'SPOT_REJECTION_ZERO_MUTATION',
    baseline: tradingStateFingerprint(cleanBaseline.snapshot),
    final: tradingStateFingerprint(after)
  })
  return { finalSnapshot: after }
}

async function runWalletTransferJourney(scope) {
  const { context, page } = scope
  await page.navigate(`${page.p0Options.webBaseUrl}/wallet`)
  await page.waitForFunction(
    () => Boolean(document.querySelector('#wallet-overview')),
    'WALLET-01 wallet route'
  )
  const before = await context.api.snapshotAccount(page)
  const beforeSpot = findWallet(before, 'SPOT', 'USDT')
  const beforeState = walletTransferState(before)
  assertDecimalClose(
    beforeSpot.total,
    beforeSpot.available,
    '0.00000001',
    'WALLET-01 initial Spot total/available'
  )
  assertDecimalClose(
    beforeSpot.locked,
    '0',
    '0.00000001',
    'WALLET-01 initial Spot locked'
  )
  const forwardOracle = transferConservationOracle({
    direction: 'SPOT_TO_PERP',
    amount: '1000',
    spotAvailable: String(beforeSpot.available),
    perpBalance: String(before.summary.balance),
    perpEquity: String(before.summary.equity),
    perpFreeMargin: String(before.summary.freeMargin)
  })
  const forward = await context.ui.transferViaUi(page, {
    direction: 'SPOT_TO_PERP',
    amount: '1000'
  })
  scope.addMutation('spot-to-perp-transfer-via-ui', forward)
  const forwardRequest = JSON.parse(forward.rawRequest.postData)
  const forwardResponse = parsedResponse(forward)?.data
    ?? parsedResponse(forward)
  const forwardTransfer = assertCapturedTransfer(
    forwardRequest,
    forwardResponse,
    'SPOT_TO_PERP',
    '1000',
    'WALLET-01 forward'
  )
  const afterForward = await waitForAccount(
    context,
    page,
    'WALLET-01 Spot to Perp transfer',
    (candidate) => {
      const spot = findWallet(candidate, 'SPOT', 'USDT')
      return Number(spot.available) === Number(forwardOracle.spotAvailableAfter)
        && Number(candidate.summary.balance) === Number(forwardOracle.perpBalanceAfter)
    }
  )
  const forwardState = assertWalletTransferSnapshot(
    afterForward,
    forwardOracle,
    'WALLET-01 forward'
  )
  assert.equal(
    forwardOracle.combinedBefore,
    forwardOracle.combinedAfter,
    'WALLET-01 forward combined conservation'
  )
  await scope.capture('forward-transfer', afterForward)

  const reverseOracle = transferConservationOracle({
    direction: 'PERP_TO_SPOT',
    amount: '400',
    spotAvailable: String(findWallet(afterForward, 'SPOT', 'USDT').available),
    perpBalance: String(afterForward.summary.balance),
    perpEquity: String(afterForward.summary.equity),
    perpFreeMargin: String(afterForward.summary.freeMargin)
  })
  const reverse = await context.ui.transferViaUi(page, {
    direction: 'PERP_TO_SPOT',
    amount: '400'
  })
  scope.addMutation('perp-to-spot-transfer-via-ui', reverse)
  const reverseRequest = JSON.parse(reverse.rawRequest.postData)
  const reverseResponse = parsedResponse(reverse)?.data
    ?? parsedResponse(reverse)
  const reverseTransfer = assertCapturedTransfer(
    reverseRequest,
    reverseResponse,
    'PERP_TO_SPOT',
    '400',
    'WALLET-01 reverse'
  )
  const afterReverse = await waitForAccount(
    context,
    page,
    'WALLET-01 Perp to Spot transfer',
    (candidate) => {
      const spot = findWallet(candidate, 'SPOT', 'USDT')
      return Number(spot.available) === Number(reverseOracle.spotAvailableAfter)
        && Number(candidate.summary.balance) === Number(reverseOracle.perpBalanceAfter)
    }
  )
  const reverseState = assertWalletTransferSnapshot(
    afterReverse,
    reverseOracle,
    'WALLET-01 reverse'
  )
  assert.equal(
    reverseOracle.combinedBefore,
    reverseOracle.combinedAfter,
    'WALLET-01 reverse combined conservation'
  )
  assert.equal(
    forwardOracle.combinedAfter,
    reverseOracle.combinedBefore,
    'WALLET-01 middle snapshot conservation'
  )
  const transferIds = new Set([
    forwardTransfer.transferId,
    reverseTransfer.transferId
  ])
  const newTransfers = (afterReverse.transfers ?? [])
    .filter(({ transferId }) => transferIds.has(transferId))
  assert.equal(newTransfers.length, 2, 'WALLET-01 requires two captured transfer records')
  for (const transferId of transferIds) {
    assert.equal(
      newTransfers.filter((record) => record.transferId === transferId).length,
      1,
      `WALLET-01 transfer history ${transferId}`
    )
  }
  const reverseCheckpoint = await scope.capture('reverse-transfer', afterReverse)
  const forwardLedger = assertTransferLedgerPair(
    reverseCheckpoint.db,
    forwardTransfer,
    'WALLET-01 forward'
  )
  const reverseLedger = assertTransferLedgerPair(
    reverseCheckpoint.db,
    reverseTransfer,
    'WALLET-01 reverse'
  )
  scope.oracleEvidence.push({
    kind: 'WALLET_TRANSFER_CONSERVATION',
    forward: forwardOracle,
    reverse: reverseOracle,
    snapshots: {
      before: beforeState,
      afterForward: forwardState,
      afterReverse: reverseState
    },
    transfers: [
      { ...forwardTransfer, ledger: forwardLedger },
      { ...reverseTransfer, ledger: reverseLedger }
    ]
  })
  return { finalSnapshot: afterReverse }
}

function walletTransferState(snapshot) {
  const spot = findWallet(snapshot, 'SPOT', 'USDT')
  return {
    spot: {
      total: spot.total,
      available: spot.available,
      locked: spot.locked
    },
    perpetual: {
      balance: snapshot.summary.balance,
      equity: snapshot.summary.equity,
      freeMargin: snapshot.summary.freeMargin
    }
  }
}

function assertWalletTransferSnapshot(snapshot, oracle, label) {
  const state = walletTransferState(snapshot)
  for (const field of ['total', 'available']) {
    assertDecimalClose(
      state.spot[field],
      oracle.spotAvailableAfter,
      '0.00000001',
      `${label} Spot ${field}`
    )
  }
  assertDecimalClose(state.spot.locked, '0', '0.00000001', `${label} Spot locked`)
  for (const [field, expected] of [
    ['balance', oracle.perpBalanceAfter],
    ['equity', oracle.perpEquityAfter],
    ['freeMargin', oracle.perpFreeMarginAfter]
  ]) {
    assertDecimalClose(
      state.perpetual[field],
      expected,
      '0.00000001',
      `${label} Perp ${field}`
    )
  }
  return state
}

function assertCapturedTransfer(request, response, direction, amount, label) {
  assert(request && typeof request === 'object', `${label} raw request`)
  assert(response && typeof response === 'object', `${label} response`)
  assertUuid(request.requestId, `${label} request`)
  assertUuid(response.transferId, `${label} response`)
  assert.equal(response.transferId, request.requestId, `${label} request/transfer id`)
  assert.equal(request.direction, direction, `${label} request direction`)
  assert.equal(response.direction, direction, `${label} response direction`)
  assertDecimalClose(request.amount, amount, '0.00000001', `${label} request amount`)
  assertDecimalClose(response.amount, amount, '0.00000001', `${label} response amount`)
  assert.equal(response.replayed, false, `${label} must be a fresh transfer`)
  return {
    requestId: request.requestId,
    transferId: response.transferId,
    direction,
    amount
  }
}

function assertTransferLedgerPair(db, transfer, label) {
  const { requestId, transferId, direction, amount } = transfer
  assert.equal(requestId, transferId, `${label} ledger correlation id`)
  const assetEntries = (db.assetLedgerRows ?? []).filter((entry) => (
    entry.reference_type === 'TRANSFER' && entry.reference_id === transferId
  ))
  const cashEntries = (db.cashLedgerRows ?? []).filter((entry) => (
    entry.reference_type === 'TRANSFER' && entry.reference_id === transferId
  ))
  assert.equal(assetEntries.length, 1, `${label} exact asset ledger row`)
  assert.equal(cashEntries.length, 1, `${label} exact cash ledger row`)
  const asset = assetEntries[0]
  const cash = cashEntries[0]
  const spotToPerp = direction === 'SPOT_TO_PERP'
  assert.equal(
    asset.operation_type,
    spotToPerp ? 'TRANSFER_OUT' : 'TRANSFER_IN',
    `${label} asset ledger direction`
  )
  assert.equal(
    cash.operation_type,
    spotToPerp ? 'TRANSFER_IN' : 'TRANSFER_OUT',
    `${label} cash ledger direction`
  )
  assertDecimalClose(
    asset.amount,
    spotToPerp ? `-${amount}` : amount,
    '0.00000001',
    `${label} asset ledger amount`
  )
  assertDecimalClose(
    cash.amount,
    spotToPerp ? amount : `-${amount}`,
    '0.00000001',
    `${label} cash ledger amount`
  )
  return {
    asset: {
      id: asset.id,
      amount: asset.amount,
      operationType: asset.operation_type
    },
    cash: {
      id: cash.id,
      amount: cash.amount,
      operationType: cash.operation_type
    }
  }
}

async function runDemoResetJourney(scope) {
  const { context, page } = scope
  const spotMarket = await context.api.snapshotMarket('BTCUSDT')
  await context.ui.openTradePanel(page, {
    product: 'spot',
    symbol: 'BTCUSDT'
  })
  const beforeSpot = await context.api.snapshotAccount(page)
  const spotBuy = await context.ui.submitOrderViaUi(page, {
    side: 'BUY',
    orderType: 'MARKET',
    amount: '25'
  })
  scope.addMutation('reset-history-spot-buy-via-ui', spotBuy)
  const spotBought = await waitForFilledMutation(
    scope,
    beforeSpot,
    spotBuy,
    'BTCUSDT'
  )
  const spotSell = await context.ui.submitOrderViaUi(page, {
    side: 'SELL',
    orderType: 'MARKET',
    amount: floorToStep(
      String(findWallet(spotBought.snapshot, 'SPOT', 'BTC').available),
      effectiveQuantityStep(rulesFor(spotMarket))
    )
  })
  scope.addMutation('reset-history-spot-sell-via-ui', spotSell)
  const spotClosed = await waitForFilledMutation(
    scope,
    spotBought.snapshot,
    spotSell,
    'BTCUSDT'
  )
  assertSpotDust(spotClosed.snapshot, spotMarket, 'BTC', 'LIFE-02')

  const perpMarket = await context.api.snapshotMarket('BTCUSDT-PERP')
  await context.ui.openTradePanel(page, {
    product: 'perpetual',
    symbol: 'BTCUSDT-PERP'
  })
  await ensurePerpSettings(scope, {
    positionMode: 'ONE_WAY',
    marginMode: 'CROSS',
    leverage: 10,
    quantityUnit: 'BASE'
  })
  const perpOpen = await context.ui.submitOrderViaUi(page, {
    side: 'BUY',
    orderType: 'MARKET',
    amount: stepAlignedQuantity(perpMarket, 0.01)
  })
  scope.addMutation('reset-history-perp-open-via-ui', perpOpen)
  const perpOpened = await waitForFilledMutation(
    scope,
    spotClosed.snapshot,
    perpOpen,
    'BTCUSDT-PERP'
  )
  await closePositionFromUi(
    scope,
    openPositions(perpOpened.snapshot, 'BTCUSDT-PERP').at(0)
  )
  const perpClosed = await waitForAccount(
    context,
    page,
    'LIFE-02 Perp history cleanup',
    (candidate) => openPositions(candidate, 'BTCUSDT-PERP').length === 0
      && candidate
  )

  const transfer = await context.ui.transferViaUi(page, {
    direction: 'SPOT_TO_PERP',
    amount: '100'
  })
  scope.addMutation('reset-history-transfer-via-ui', transfer)
  const transferResponse = parsedResponse(transfer)?.data
    ?? parsedResponse(transfer)
  assert(transferResponse && typeof transferResponse === 'object')
  assertUuid(transferResponse.transferId, 'LIFE-02 transfer')
  await waitForAccount(
    context,
    page,
    'LIFE-02 transfer history',
    (candidate) => (candidate.transfers ?? [])
      .some(({ transferId }) => transferId === transferResponse.transferId)
      && candidate
  )
  await context.ui.openTradePanel(page, {
    product: 'perpetual',
    symbol: 'BTCUSDT-PERP'
  })
  await ensurePerpSettings(scope, {
    positionMode: 'HEDGE',
    marginMode: 'ISOLATED',
    leverage: 50,
    quantityUnit: 'CONTRACTS'
  })
  const resetBefore = await context.api.snapshotAccount(page)
  assert.equal(activeOrders(resetBefore).length, 0)
  assert.equal(openPositions(resetBefore).length, 0)
  await context.events.waitForStompEvent(
    page,
    '/user/queue/trading-events'
  )
  const resetEventCursor = context.events.snapshotFrames(page).length
  const readCashLedger = async () => {
    const response = await context.api.user(
      page,
      `/api/ledger?accountId=${encodeURIComponent(resetBefore.account.id)}&page=0&size=200`
    )
    return Array.isArray(response)
      ? response
      : response?.content ?? response?.items ?? response?.records ?? []
  }
  const cashLedgerBefore = await readCashLedger()
  const submitted = await scope.capture('submitted', resetBefore)
  const preserved = {
    orderIds: resetBefore.orders.map(({ id }) => id),
    tradeIds: resetBefore.trades.map(({ id }) => id),
    transferIds: (resetBefore.transfers ?? []).map(({ transferId }) => transferId),
    assetLedgerIds: (resetBefore.assetLedger ?? []).map(({ id }) => id),
    cashLedgerIds: cashLedgerBefore.map(({ id }) => id)
  }
  const generationBefore = Number(submitted.db.accountRow.demo_generation)
  assert(Number.isSafeInteger(generationBefore) && generationBefore >= 0)
  const reset = await context.ui.resetDemoViaUi(page)
  scope.addMutation('demo-reset-via-ui', reset)
  const resetRequest = JSON.parse(reset.rawRequest.postData)
  const resetResponse = parsedResponse(reset)?.data
    ?? parsedResponse(reset)
  const resetEvent = await context.events.waitForStompEvent(page, 'DEMO_RESET')
  assert(
    context.events.snapshotFrames(page)
      .slice(resetEventCursor)
      .some(({ direction, eventType }) => (
        direction === 'received' && eventType === 'DEMO_RESET'
      )),
    'LIFE-02 requires a new DEMO_RESET event from the real user queue'
  )
  assert(resetResponse && typeof resetResponse === 'object')
  assert.equal(resetResponse.accountId, resetBefore.account.id)
  assertUuid(resetRequest.requestId, 'LIFE-02 reset raw request')
  assertUuid(resetResponse.requestId, 'LIFE-02 reset')
  assert.equal(resetResponse.requestId, resetRequest.requestId)
  assert.equal(resetResponse.replayed, false)
  assert.equal(Number(resetResponse.demoGeneration), generationBefore + 1)
  for (const [field, expected] of [
    ['spotAvailable', '50000'],
    ['perpBalance', '50000'],
    ['perpFreeMargin', '50000']
  ]) {
    assertDecimalClose(
      resetResponse[field],
      expected,
      '0.00000001',
      `LIFE-02 reset response ${field}`
    )
  }
  const resetCompleted = await waitForAccount(
    context,
    page,
    'LIFE-02 demo reset',
    (candidate) => {
      const spotUsdt = findWallet(candidate, 'SPOT', 'USDT')
      const settings = candidate.settings.symbols ?? []
      return Number(spotUsdt.total) === 50000
        && Number(candidate.summary.balance) === 50000
        && Number(candidate.summary.usedMargin) === 0
        && candidate.settings.positionMode === 'ONE_WAY'
        && PERP_SYMBOLS.every((symbol) => {
          const value = settings.find((item) => item.symbol === symbol)
          return value
            && value.marginMode === 'CROSS'
            && Number(value.leverage) === 10
            && value.quantityUnit === 'BASE'
        })
        ? candidate
        : false
    }
  )
  const resetDb = await context.db.snapshotTradingRows(resetBefore.account.id)
  assert.equal(
    Number(resetDb.accountRow.demo_generation),
    Number(resetResponse.demoGeneration),
    'LIFE-02 reset response generation must match DB'
  )
  const resetState = assertDemoResetState(resetCompleted, resetDb)
  const resetCashEntries = (resetDb.cashLedgerRows ?? []).filter((entry) => (
    entry.entry_type === 'DEMO_RESET'
      && entry.reference_type === 'DEMO_RESET'
      && entry.reference_id === resetResponse.requestId
  ))
  assert.equal(
    resetCashEntries.length,
    1,
    'LIFE-02 requires exactly one request-correlated cash DEMO_RESET row'
  )
  const cashLedgerAfterReset = await readCashLedger()
  const current = {
    orderIds: resetCompleted.orders.map(({ id }) => id),
    tradeIds: resetCompleted.trades.map(({ id }) => id),
    transferIds: (resetCompleted.transfers ?? [])
      .map(({ transferId }) => transferId),
    assetLedgerIds: (resetCompleted.assetLedger ?? []).map(({ id }) => id),
    cashLedgerIds: cashLedgerAfterReset.map(({ id }) => id)
  }
  for (const [field, ids] of Object.entries(preserved)) {
    const currentIds = new Set(current[field])
    assert.equal(
      ids.every((id) => currentIds.has(id)),
      true,
      `LIFE-02 preserves ${field}`
    )
  }
  const beforeReplayFingerprint = completeResetStateFingerprint(
    resetCompleted,
    resetDb,
    cashLedgerAfterReset
  )
  const replay = await replayCapturedMutation(
    scope,
    reset,
    (body) => body,
    { reason: 'LIFE-02 same reset request replay' }
  )
  const replayResponse = parsedResponse(replay)?.data
    ?? parsedResponse(replay)
  assert(replayResponse && typeof replayResponse === 'object')
  assert.equal(replayResponse.accountId, resetResponse.accountId)
  assert.equal(replayResponse.requestId, resetResponse.requestId)
  assert.equal(replayResponse.demoGeneration, resetResponse.demoGeneration)
  assert.equal(replayResponse.resetAt, resetResponse.resetAt)
  assert.equal(replayResponse.replayed, true)
  const replayed = await context.api.snapshotAccount(page)
  const replayDb = await context.db.snapshotTradingRows(resetBefore.account.id)
  assert.equal(
    Number(replayDb.accountRow.demo_generation),
    Number(resetResponse.demoGeneration),
    'LIFE-02 reset replay generation must stay unchanged'
  )
  const replayCashLedger = await readCashLedger()
  const replayResetCashEntries = (replayDb.cashLedgerRows ?? []).filter((entry) => (
    entry.entry_type === 'DEMO_RESET'
      && entry.reference_type === 'DEMO_RESET'
      && entry.reference_id === resetResponse.requestId
  ))
  assert.deepEqual(
    replayResetCashEntries,
    resetCashEntries,
    'LIFE-02 reset replay must preserve the single cash DEMO_RESET row'
  )
  const afterReplayFingerprint = completeResetStateFingerprint(
    replayed,
    replayDb,
    replayCashLedger
  )
  assert.deepEqual(
    afterReplayFingerprint,
    beforeReplayFingerprint,
    'LIFE-02 reset replay must preserve the complete REST and DB state'
  )
  scope.replayProbes.push({
    status: 'PASS',
    kind: 'SAME_RESET_REQUEST_REPLAY',
    originalRequestRef: reset.requestRef,
    requestRef: replay.requestRef,
    requestId: replayResponse.requestId,
    demoGeneration: replayResponse.demoGeneration,
    replayed: replayResponse.replayed
  })
  const resetAssetEntries = (replayed.assetLedger ?? []).filter(({ entryType }) => (
    entryType === 'DEMO_RESET'
  ))
  scope.oracleEvidence.push({
    kind: 'DEMO_RESET_HISTORY_PRESERVATION',
    generationBefore,
    generationAfter: replayResponse.demoGeneration,
    preserved,
    resetState,
    event: resetEvent,
    beforeReplayFingerprint,
    afterReplayFingerprint,
    reset: {
      requestId: resetResponse.requestId,
      replayed: resetResponse.replayed
    },
    replay: {
      requestId: replayResponse.requestId,
      replayed: replayResponse.replayed
    },
    resetLedgerIds: [...resetAssetEntries, ...resetCashEntries]
      .map(({ id }) => id)
  })
  await scope.capture('reset-complete', replayed)
  return { finalSnapshot: replayed }
}

function assertDemoResetState(snapshot, db) {
  const spotWallets = snapshot.wallets.filter(({ walletType }) => walletType === 'SPOT')
  const spotUsdt = findWallet(snapshot, 'SPOT', 'USDT')
  for (const field of ['available', 'total']) {
    assertDecimalClose(
      spotUsdt[field],
      '50000',
      '0.00000001',
      `LIFE-02 Spot USDT ${field}`
    )
  }
  assertDecimalClose(spotUsdt.locked, '0', '0.00000001', 'LIFE-02 Spot USDT locked')
  for (const wallet of spotWallets.filter(({ asset }) => asset !== 'USDT')) {
    for (const field of ['available', 'total', 'locked']) {
      assertDecimalClose(
        wallet[field],
        '0',
        '0.00000001',
        `LIFE-02 Spot ${wallet.asset} ${field}`
      )
    }
  }
  for (const [field, expected] of [
    ['balance', '50000'],
    ['equity', '50000'],
    ['freeMargin', '50000'],
    ['usedMargin', '0']
  ]) {
    assertDecimalClose(
      snapshot.summary[field],
      expected,
      '0.00000001',
      `LIFE-02 Perp ${field}`
    )
  }
  const spotPositions = [
    ...snapshot.positions,
    ...(snapshot.positionHistory ?? [])
  ].filter(isSpotPosition)
  assert(spotPositions.length > 0, 'LIFE-02 requires reset Spot position REST evidence')
  for (const position of spotPositions) {
    for (const field of ['lots', 'openPrice', 'floatingPnl']) {
      assertDecimalClose(
        position[field],
        '0',
        '0.00000001',
        `LIFE-02 Spot position ${position.symbol} ${field}`
      )
    }
  }
  const dbSpotWallets = (db.walletRows ?? [])
    .filter(({ wallet_type }) => wallet_type === 'SPOT')
  assert.equal(dbSpotWallets.length, spotWallets.length, 'LIFE-02 DB Spot wallets')
  for (const wallet of dbSpotWallets) {
    const expected = wallet.asset === 'USDT' ? '50000' : '0'
    for (const field of ['available', 'total']) {
      assertDecimalClose(
        wallet[field],
        expected,
        '0.00000001',
        `LIFE-02 DB Spot ${wallet.asset} ${field}`
      )
    }
    assertDecimalClose(
      wallet.locked,
      '0',
      '0.00000001',
      `LIFE-02 DB Spot ${wallet.asset} locked`
    )
  }
  const spotPositionRows = db.spotPositionRows ?? []
  assert(spotPositionRows.length > 0, 'LIFE-02 requires reset Spot position DB evidence')
  for (const position of spotPositionRows) {
    for (const field of ['quantity', 'average_cost', 'unrealized_pnl']) {
      assertDecimalClose(
        position[field],
        '0',
        '0.00000001',
        `LIFE-02 DB Spot position ${position.asset} ${field}`
      )
    }
  }
  for (const [field, expected] of [
    ['balance', '50000'],
    ['equity', '50000'],
    ['free_margin', '50000'],
    ['used_margin', '0']
  ]) {
    assertDecimalClose(
      db.accountRow[field],
      expected,
      '0.00000001',
      `LIFE-02 DB Perp ${field}`
    )
  }
  return {
    spotWallets,
    spotPositions,
    dbSpotWallets,
    dbSpotPositions: spotPositionRows,
    perpetual: snapshot.summary,
    dbPerpetual: db.accountRow
  }
}

function completeResetStateFingerprint(snapshot, db, cashLedger) {
  const serialized = JSON.stringify({ rest: snapshot, db, cashLedger })
  return {
    algorithm: 'SHA-256',
    digest: createHash('sha256').update(serialized).digest('hex'),
    bytes: Buffer.byteLength(serialized),
    scope: ['REST_ACCOUNT', 'DB_TRADING_ROWS', 'REST_LEDGER']
  }
}

function backendFullFillContractProof(context) {
  const gatePath = join(
    context.run.artifactRoot,
    'preflight',
    'backend-unit.json'
  )
  const gate = JSON.parse(readFileSync(gatePath, 'utf8'))
  assert.equal(gate.id, 'backend-unit', 'PERP-12 backend unit gate identity')
  assert.equal(gate.status, 'PASS', 'PERP-12 backend unit gate')

  const className = 'com.fxplatform.execution.FullFillCoordinatorTest'
  const testCase = 'rejectsEveryNonFullAdapterResultBeforeItCanBecomeCanonical'
  const reportPath = join(
    process.cwd(),
    'backend',
    'target',
    'surefire-reports',
    `TEST-${className}.xml`
  )
  const xml = readFileSync(reportPath, 'utf8')
  assert(
    xml.includes(`name="${className}"`),
    'PERP-12 exact full-fill test class'
  )
  assert(xml.includes('failures="0"'), 'PERP-12 full-fill test failures')
  assert(xml.includes('errors="0"'), 'PERP-12 full-fill test errors')
  assert(
    xml.includes(testCase),
    'PERP-12 exact non-full adapter rejection test'
  )
  const gateWrittenAt = statSync(gatePath).mtimeMs
  const reportWrittenAt = statSync(reportPath).mtimeMs
  assert(
    reportWrittenAt <= gateWrittenAt + 1000
      && gateWrittenAt - reportWrittenAt <= 30 * 60 * 1000,
    'PERP-12 full-fill Surefire proof must come from the current preflight'
  )
  return {
    gate: 'backend-unit',
    surefireClass: className,
    testCase,
    reportSha256: createHash('sha256').update(xml).digest('hex'),
    reportBytes: Buffer.byteLength(xml),
    reportWrittenAt: new Date(reportWrittenAt).toISOString()
  }
}

export function assertCoreCleanup(caseId, snapshot, finalDb) {
  const assertWallets = (wallets, label) => {
    assert(Array.isArray(wallets), `${label} wallets are required`)
    assert(wallets.length > 0, `${label} wallets must not be empty`)
    for (const wallet of wallets) {
      for (const field of ['total', 'available', 'locked']) {
        const value = Number(wallet[field])
        assert(
          Number.isFinite(value) && value >= 0,
          `${label} ${wallet.asset} ${field} must be non-negative`
        )
      }
      assertDecimalClose(
        wallet.locked,
        '0',
        '0',
        `${label} ${wallet.asset} locked`
      )
      assertDecimalClose(
        wallet.total,
        wallet.available,
        '0',
        `${label} ${wallet.asset} total must equal available plus locked`
      )
    }
  }
  const assertSummary = (summary, label, names = {}) => {
    const fields = {
      balance: summary[names.balance ?? 'balance'],
      equity: summary[names.equity ?? 'equity'],
      usedMargin: summary[names.usedMargin ?? 'usedMargin'],
      freeMargin: summary[names.freeMargin ?? 'freeMargin']
    }
    for (const [field, raw] of Object.entries(fields)) {
      const value = Number(raw)
      assert(
        Number.isFinite(value) && value >= 0,
        `${label} ${field} must be non-negative`
      )
    }
    assertDecimalClose(
      fields.usedMargin,
      '0',
      '0.00000001',
      `${label} used margin cleanup`
    )
    assertDecimalClose(
      fields.equity,
      fields.balance,
      '0.00000001',
      `${label} equity reconciliation`
    )
    assertDecimalClose(
      fields.freeMargin,
      fields.equity,
      '0.00000001',
      `${label} free margin reconciliation`
    )
  }
  const activeOrders = snapshot.orders.filter(({ status }) => (
    ACTIVE_ORDER_STATUSES.has(status)
  ))
  const perpetualPositions = openPositions(snapshot)
  assert.equal(
    activeOrders.length,
    0,
    `${caseId} cleanup requires zero active orders`
  )
  assert.equal(
    perpetualPositions.length,
    0,
    `${caseId} cleanup requires zero open positions`
  )
  for (const order of snapshot.orders) {
    assert(
      order.holdAmount !== undefined && order.holdAmount !== null,
      `${caseId} REST order hold evidence ${order.id}`
    )
    assertDecimalClose(
      order.holdAmount,
      '0',
      '0.00000001',
      `${caseId} REST order hold ${order.id}`
    )
  }
  for (const position of snapshot.positions.filter((row) => !isSpotPosition(row))) {
    assert(
      position.marginHeld !== undefined && position.marginHeld !== null,
      `${caseId} REST position hold evidence ${position.id}`
    )
    assertDecimalClose(
      position.marginHeld,
      '0',
      '0.00000001',
      `${caseId} REST position hold ${position.id}`
    )
  }
  assertWallets(snapshot.wallets, `${caseId} REST`)
  assertSummary(snapshot.summary, `${caseId} REST`)

  if (!finalDb) return
  assert(Array.isArray(finalDb.orderRows), `${caseId} DB order rows are required`)
  assert(Array.isArray(finalDb.positionRows), `${caseId} DB position rows are required`)
  assert(Array.isArray(finalDb.walletRows), `${caseId} DB wallet rows are required`)
  assert(finalDb.accountRow, `${caseId} DB account row is required`)
  assert.equal(
    finalDb.orderRows.filter(({ status }) => ACTIVE_ORDER_STATUSES.has(status)).length,
    0,
    `${caseId} DB active orders cleanup`
  )
  assert.equal(
    finalDb.positionRows.filter(({ status }) => status === 'OPEN').length,
    0,
    `${caseId} DB open positions cleanup`
  )
  assert.equal(
    Number(finalDb.openPositions),
    0,
    `${caseId} DB open positions aggregate cleanup`
  )
  for (const order of finalDb.orderRows) {
    const hold = order.hold_amount ?? order.holdAmount
    assert(
      hold !== undefined && hold !== null,
      `${caseId} DB order hold evidence ${order.id}`
    )
    assertDecimalClose(
      hold,
      '0',
      '0.00000001',
      `${caseId} DB order hold ${order.id}`
    )
  }
  for (const position of finalDb.positionRows) {
    const hold = position.margin_held ?? position.marginHeld
    assert(
      hold !== undefined && hold !== null,
      `${caseId} DB position hold evidence ${position.id}`
    )
    assertDecimalClose(
      hold,
      '0',
      '0.00000001',
      `${caseId} DB position hold ${position.id}`
    )
  }
  assertWallets(finalDb.walletRows, `${caseId} DB`)
  assertSummary(finalDb.accountRow, `${caseId} DB`, {
    usedMargin: 'used_margin',
    freeMargin: 'free_margin'
  })
}

async function waitForAccount(context, page, description, predicate, timeoutMs = 30000) {
  const deadline = Date.now() + timeoutMs
  let latest
  while (Date.now() < deadline) {
    latest = await context.api.snapshotAccount(page)
    const matched = predicate(latest)
    if (matched) return typeof matched === 'object' ? matched : latest
    await delay(100)
  }
  throw new Error(`Timed out waiting for ${description}`)
}

function findWallet(snapshot, walletType, asset) {
  const wallet = snapshot.wallets.find((candidate) => (
    candidate.walletType === walletType && candidate.asset === asset
  ))
  assert(wallet, `wallet ${walletType}/${asset} is required`)
  return wallet
}

function findSymbolSettings(snapshot, symbol) {
  const settings = snapshot.settings.symbols?.find((candidate) => (
    candidate.symbol === symbol
  ))
  assert(settings, `settings for ${symbol} are required`)
  return settings
}

function openPositions(snapshot, symbol) {
  return snapshot.positions.filter((position) => (
    !isSpotPosition(position)
      && (!symbol || position.symbol === symbol)
      && (position.status === undefined || position.status === 'OPEN')
  ))
}

function isSpotPosition(position) {
  return String(position.instrumentType ?? '').toUpperCase() === 'SPOT'
}

function activeOrders(snapshot, symbol) {
  return snapshot.orders.filter((order) => (
    (!symbol || order.symbol === symbol) && ACTIVE_ORDER_STATUSES.has(order.status)
  ))
}

function recordsAfter(before, after, field) {
  const known = new Set((before[field] ?? []).map(({ id }) => id))
  return (after[field] ?? []).filter(({ id }) => !known.has(id))
}

function assertSpotTradeLedger(snapshot, trade, rules, label) {
  assert.equal(trade.symbol, 'BTCUSDT', `${label} symbol`)
  assert(['BUY', 'SELL'].includes(trade.side), `${label} side`)
  const entries = (snapshot.assetLedger ?? []).filter((entry) => (
    entry.referenceType === 'TRADE' && entry.referenceId === trade.id
  ))
  const expected = trade.side === 'BUY'
    ? {
        SPOT_BUY_DEBIT: {
          asset: 'USDT',
          amount: String(-Number(trade.lots) * Number(trade.price))
        },
        SPOT_BUY_CREDIT: {
          asset: 'BTC',
          amount: String(trade.lots)
        },
        TRADE_FEE: {
          asset: 'BTC',
          amount: String(-Number(trade.fee))
        }
      }
    : {
        SPOT_SELL_DEBIT: {
          asset: 'BTC',
          amount: String(-Number(trade.lots))
        },
        SPOT_SELL_CREDIT: {
          asset: 'USDT',
          amount: String(Number(trade.lots) * Number(trade.price))
        },
        TRADE_FEE: {
          asset: 'USDT',
          amount: String(-Number(trade.fee))
        }
      }
  assert.deepEqual(
    entries.map(({ entryType }) => entryType).toSorted(),
    Object.keys(expected).toSorted(),
    `${label} exact Trade-linked ledger types`
  )
  for (const entry of entries) {
    const oracle = expected[entry.entryType]
    assert(oracle, `${label} unexpected ledger ${entry.entryType}`)
    assert.equal(entry.walletType, 'SPOT', `${label} ${entry.entryType} wallet`)
    assert.equal(entry.asset, oracle.asset, `${label} ${entry.entryType} asset`)
    assertDecimalClose(
      entry.amount,
      oracle.amount,
      tolerancesFromRules(rules).amount,
      `${label} ${entry.entryType} amount`
    )
  }
  return {
    tradeId: trade.id,
    entries: entries.map(({ id, entryType, asset, amount }) => ({
      id,
      entryType,
      asset,
      amount
    }))
  }
}

async function prepareSpotAuthorityMarket(scope, symbol, label) {
  let market = await scope.context.api.snapshotMarket(symbol)
  const fixedAuthority = (
    scope.context.authority?.authorityBundleFixture === 'PASS'
  )
  if (!fixedAuthority) return { market, fixedAuthority }

  const bid = quoteDecimal(market, 'bid')
  const ask = quoteDecimal(market, 'ask')
  const adminPage = await scope.getAdminPage()
  const fixture = await scope.context.fixtures.marketOverride(adminPage, {
    symbol,
    bid,
    ask,
    ttl: 'PT5M'
  })
  scope.registerFixtureRestore({
    action: 'restore-fixed-spot-market-bundle',
    symbol
  }, fixture.restore)
  scope.fixtureActions.push({
    action: 'set-fixed-spot-market-bundle',
    symbol,
    bid,
    ask
  })
  market = await waitForMarket(
    scope.context,
    symbol,
    `${label} fixed market bundle`,
    (candidate) => (
      Number(candidate.quote?.bid) === Number(bid)
        && Number(candidate.quote?.ask) === Number(ask)
    )
  )
  return { market, fixedAuthority }
}

function assertSingleFullFillMutation(before, result, label) {
  const orders = recordsAfter(before, result.snapshot, 'orders')
  const trades = recordsAfter(before, result.snapshot, 'trades')
  assert.equal(orders.length, 1, `${label} exact new Order`)
  assert.equal(trades.length, 1, `${label} exact new Trade`)
  assert.equal(orders[0].id, result.order.id, `${label} Order identity`)
  assert.equal(trades[0].id, result.trade.id, `${label} Trade identity`)
  assert.equal(result.order.status, 'FILLED', `${label} Order status`)
  assert.equal(result.trade.orderId, result.order.id, `${label} Trade reference`)
  assert.equal(
    result.snapshot.orders.some(({ status }) => status === 'PARTIALLY_FILLED'),
    false,
    `${label} full-fill-only`
  )
  return {
    orderId: result.order.id,
    tradeId: result.trade.id,
    status: result.order.status
  }
}

function assertSpotWalletDelta(before, after, expected, rules, label) {
  return expected.map((delta) => {
    const beforeWallet = findWallet(before, 'SPOT', delta.asset)
    const afterWallet = findWallet(after, 'SPOT', delta.asset)
    for (const field of ['total', 'available', 'locked']) {
      assertDecimalClose(
        Number(afterWallet[field]) - Number(beforeWallet[field]),
        delta[field],
        tolerancesFromRules(rules).amount,
        `${label} ${delta.asset} ${field} delta`
      )
    }
    return {
      asset: delta.asset,
      expected: delta,
      invariant: assertWalletInvariant(
        afterWallet,
        rules,
        `${label} ${delta.asset}`
      )
    }
  })
}

function quoteDecimal(market, field) {
  const value = market?.quote?.[field]
    ?? market?.reference?.[field]
  const numeric = Number(value)
  assert(Number.isFinite(numeric) && numeric > 0, `market ${field} must be positive`)
  return String(value)
}

function rulesFor(market) {
  assert(market?.rules && typeof market.rules === 'object', 'symbol rules are required')
  return market.rules
}

function stepAlignedQuantity(market, preferred = 0.01) {
  const rules = rulesFor(market)
  const stepText = effectiveQuantityStep(rules)
  const step = Number(stepText)
  const mark = Number(
    market.reference?.mark
      ?? market.quote?.markPrice
      ?? market.quote?.mid
      ?? market.quote?.last
      ?? market.quote?.ask
  )
  assert(Number.isFinite(step) && step > 0, 'effective quantity step must be positive')
  assert(Number.isFinite(mark) && mark > 0, 'quantity reference mark must be positive')
  const minQuantity = Math.max(
    step,
    Number(rules.minQty ?? rules.minimumQuantity ?? step),
    Number(rules.minNotional ?? rules.minimumNotional ?? 0) / mark,
    preferred
  )
  const steps = Math.ceil((minQuantity - step * 1e-8) / step)
  const scale = Math.max(0, (stepText.split('.')[1] ?? '').length)
  return (steps * step).toFixed(scale)
}

function floorFraction(quantity, numerator, denominator, rules) {
  const raw = Number(quantity) * numerator / denominator
  const scale = Math.max(
    8,
    (String(effectiveQuantityStep(rules)).split('.')[1] ?? '').length
  )
  return floorToStep(raw.toFixed(scale), effectiveQuantityStep(rules))
}

function assertSpotDust(snapshot, market, baseAsset, label) {
  const rules = rulesFor(market)
  const minimum = String(rules.minQty ?? effectiveQuantityStep(rules))
  const wallet = findWallet(snapshot, 'SPOT', baseAsset)
  const symbol = market.quote?.symbol
  assert(typeof symbol === 'string' && symbol.length > 0, `${label} market symbol`)
  const positions = snapshot.positions.filter((candidate) => (
    isSpotPosition(candidate) && candidate.symbol === symbol
  ))
  assert(positions.length <= 1, `${label} unique Spot position`)
  const position = positions[0]
  assertNear(wallet.locked, 0, `${label} locked`)
  assert(
    Number(wallet.available) < Number(minimum),
    `${label} wallet dust must be below ${minimum}, got ${wallet.available}`
  )
  if (position) {
    assert(
      Number(position.lots) < Number(minimum),
      `${label} position dust must be below ${minimum}, got ${position.lots}`
    )
  }
  return {
    available: wallet.available,
    minimum,
    positionLots: position?.lots ?? '0'
  }
}

function assertWalletInvariant(wallet, rules, label) {
  const oracle = walletBalanceOracle({
    total: String(wallet.total),
    available: String(wallet.available),
    locked: String(wallet.locked),
    rules
  })
  assert.equal(oracle.valid, true, `${label} wallet invariant`)
  return oracle
}

function assertDecimalClose(actual, expected, tolerance, label) {
  assert.equal(
    withinTolerance(String(actual), String(expected), String(tolerance)),
    true,
    `${label}: expected ${expected}, got ${actual}`
  )
}

function tradeForOrder(snapshot, orderId) {
  return snapshot.trades.find((trade) => trade.orderId === orderId)
}

function orderForCapture(before, after, capture) {
  const created = recordsAfter(before, after, 'orders')
  const matching = created.find((order) => (
    !capture.idempotencyKey
      || order.idempotencyKey === capture.idempotencyKey
      || order.clientOrderId === capture.idempotencyKey
  ))
  return matching ?? created.at(-1)
}

async function waitForFilledMutation(scope, before, capture, symbol) {
  const snapshot = await waitForAccount(
    scope.context,
    scope.page,
    `${scope.definition.id} ${symbol} fill`,
    (candidate) => {
      const order = orderForCapture(before, candidate, capture)
      if (!order || order.symbol !== symbol || order.status !== 'FILLED') return false
      const trade = tradeForOrder(candidate, order.id)
      return trade ? { snapshot: candidate, order, trade } : false
    }
  )
  return snapshot
}

async function closePositionFromUi(scope, position, options = {}) {
  const capture = await scope.context.ui.positionActionViaUi(scope.page, {
    positionId: position.id,
    positionSide: position.positionSide,
    action: options.quantity ? 'PARTIAL_CLOSE' : 'FULL_CLOSE',
    quantity: options.quantity,
    quantityUnit: options.quantityUnit ?? 'BASE',
    expectFailure: options.expectFailure,
    reason: options.reason
  })
  if (options.expectFailure) return capture
  scope.addMutation(options.quantity ? 'partial-close-via-ui' : 'full-close-via-ui', capture)
  return capture
}

async function ensurePerpSettings(scope, settings) {
  const captures = await scope.context.ui.setPerpetualSettingsViaUi(
    scope.page,
    settings
  )
  for (const capture of captures) {
    scope.addMutation('perpetual-settings-via-ui', capture)
  }
  return captures
}

function parsedResponse(capture) {
  if (capture?.parsedResponse !== undefined) return capture.parsedResponse
  const body = capture?.networkEvidence?.responseBody
  if (typeof body !== 'string' || body.length === 0) return undefined
  try {
    return JSON.parse(body)
  } catch {
    return body
  }
}

async function replayCapturedMutation(scope, capture, mutate, options = {}) {
  const raw = capture?.rawRequest
  assert(raw && typeof raw.url === 'string', 'captured raw request is required')
  const targetUrl = options.url ?? raw.url
  const body = raw.postData ? JSON.parse(raw.postData) : undefined
  const nextBody = typeof mutate === 'function' ? mutate(structuredClone(body)) : body
  const replay = await scope.context.ui.withCapturedMutation(
    scope.page,
    ({ method, url }) => method === raw.method && url === targetUrl,
    () => scope.page.evaluate(async (request, payload) => {
      const token = localStorage.getItem('fx-platform-auth-token')
      const response = await fetch(request.url, {
        method: request.method,
        headers: {
          'Content-Type': 'application/json',
          ...(token ? { Authorization: `Bearer ${token}` } : {})
        },
        body: payload === undefined ? undefined : JSON.stringify(payload)
      })
      const text = await response.text()
      let data
      try {
        data = text ? JSON.parse(text) : null
      } catch {
        data = text
      }
      return { status: response.status, data }
    }, {
      method: raw.method,
      url: targetUrl
    }, nextBody)
  )
  if (options.expectFailure) {
    assert(
      replay.status >= 400 && replay.status < 500,
      `${options.reason ?? 'contract replay'} must return 4xx`
    )
    scope.page.allowHttpError(
      replay.requestRef,
      options.reason ?? 'expected P0 contract rejection'
    )
  } else {
    assert(
      replay.status >= 200 && replay.status < 300,
      `${options.reason ?? 'replay'} must return 2xx`
    )
  }
  return replay
}

function responseCode(capture) {
  const response = parsedResponse(capture)
  return response?.code
    ?? response?.data?.code
    ?? capture?.actionResult?.data?.code
}

function assertRejectedCode(capture, expected, label) {
  assert.equal(capture.status >= 400 && capture.status < 500, true, `${label} status`)
  assert.equal(responseCode(capture), expected, `${label} error code`)
}

async function openTradingProductFromMenu(page, itemLabel, expectedPrefix) {
  await page.navigate(page.p0Options.webBaseUrl)
  await page.waitForFunction(
    () => Boolean([...document.querySelectorAll('button[aria-haspopup="menu"]')]
      .find((button) => button.textContent?.trim().includes('交易'))),
    'trading navigation menu'
  )
  const opened = await page.evaluate(() => {
    const trigger = [...document.querySelectorAll('button[aria-haspopup="menu"]')]
      .find((button) => button.textContent?.trim().includes('交易'))
    if (!(trigger instanceof HTMLButtonElement)) return false
    trigger.click()
    return true
  })
  assert.equal(opened, true, 'trading menu trigger')
  await page.waitForFunction(
    (label) => [...(document.querySelector(
      '[role="menu"][aria-label="交易分类"]'
    )?.querySelectorAll('[role="menuitem"]') ?? [])]
      .some((candidate) => candidate.textContent?.includes(label)),
    `trading menu item ${itemLabel}`,
    itemLabel
  )
  const clicked = await page.evaluate((label) => {
    const menu = document.querySelector('[role="menu"][aria-label="交易分类"]')
    const item = [...(menu?.querySelectorAll('[role="menuitem"]') ?? [])]
      .find((candidate) => candidate.textContent?.includes(label))
    if (!(item instanceof HTMLButtonElement)) return false
    item.click()
    return true
  }, itemLabel)
  assert.equal(clicked, true, `trading menu item ${itemLabel}`)
  await page.waitForFunction(
    (prefix) => window.location.pathname.startsWith(prefix),
    `trading menu route ${itemLabel}`,
    expectedPrefix
  )
}

async function inspectTradingSurface(page, expectedSymbol, product) {
  const opened = await page.evaluate((symbol) => {
    const strong = [...document.querySelectorAll('header button strong')]
      .find((candidate) => candidate.textContent?.trim() === symbol)
    const button = strong?.closest('button')
    if (!(button instanceof HTMLButtonElement)) return false
    button.click()
    return true
  }, expectedSymbol)
  assert.equal(opened, true, `market drawer opener ${expectedSymbol}`)
  await page.waitForFunction(
    () => [...document.querySelectorAll('aside h2')]
      .some((heading) => heading.textContent?.trim() === 'Markets'),
    `market drawer ${expectedSymbol}`
  )
  const result = await page.evaluate((symbol, expectedProduct) => {
    const strong = [...document.querySelectorAll('header button strong')]
      .find((candidate) => candidate.textContent?.trim() === symbol)
    const header = strong?.closest('header')
    const mode = [...(header?.querySelectorAll('[aria-label] span') ?? [])]
      .map((element) => element.textContent?.trim())
      .filter(Boolean)
    const aside = [...document.querySelectorAll('aside')]
      .find((candidate) => candidate.querySelector('h2')?.textContent?.trim() === 'Markets')
    const marketSymbols = [...(aside?.querySelectorAll('button') ?? [])]
      .flatMap((button) => [...button.querySelectorAll('span, strong')]
        .map((element) => element.textContent?.trim()))
      .filter((value) => /^[A-Z0-9]+USDT(?:-PERP)?$/.test(value))
    const visiblePanels = [...document.querySelectorAll(
      '[data-platform-view="pc"] [data-panel-id="trade"] section[aria-label]'
    )].filter((panel) => {
      const rect = panel.getBoundingClientRect()
      const style = getComputedStyle(panel)
      return rect.width > 0 && rect.height > 0
        && style.display !== 'none' && style.visibility !== 'hidden'
    })
    return {
      symbol: strong?.textContent?.trim(),
      mode,
      marketSymbols: [...new Set(marketSymbols)],
      product: expectedProduct,
      path: window.location.pathname,
      visibleTradePanels: visiblePanels.length
    }
  }, expectedSymbol, product)
  assert(result.marketSymbols.length > 0, `market drawer symbols ${expectedSymbol}`)
  return result
}

async function inspectInvalidTradingRoute(page, product, symbol) {
  const path = `/trade/${product}/${encodeURIComponent(symbol)}`
  await page.navigate(`${page.p0Options.webBaseUrl}${path}`)
  await page.waitForFunction(
    () => document.readyState === 'complete' && document.body.innerText.length > 20,
    `invalid ${product} route`
  )
  await delay(200)
  return page.evaluate((invalidPath) => {
    const enabledOrderSubmit = [...document.querySelectorAll(
      '[data-trading-action="submit-order"]'
    )].some((button) => {
      const rect = button.getBoundingClientRect()
      const style = getComputedStyle(button)
      return !button.disabled && rect.width > 0 && rect.height > 0
        && style.display !== 'none' && style.visibility !== 'hidden'
    })
    return {
      path: window.location.pathname,
      pathEndsWithInvalid: window.location.pathname === invalidPath,
      enabledOrderSubmit
    }
  }, path)
}

async function inspectForbiddenProductReachability(page) {
  await page.navigate(page.p0Options.webBaseUrl)
  await page.waitForFunction(
    () => Boolean([...document.querySelectorAll('button[aria-haspopup="menu"]')]
      .find((button) => button.textContent?.includes('交易'))),
    'CAT-03 trading menu'
  )
  const opened = await page.evaluate(() => {
    const trigger = [...document.querySelectorAll('button[aria-haspopup="menu"]')]
      .find((button) => button.textContent?.includes('交易'))
    if (!(trigger instanceof HTMLButtonElement)) return false
    trigger.click()
    return true
  })
  assert.equal(opened, true, 'CAT-03 trading menu trigger')
  await page.waitForFunction(
    () => Boolean(document.querySelector(
      '[role="menu"][aria-label="交易分类"]'
    )?.textContent?.trim()),
    'CAT-03 trading menu content'
  )
  return page.evaluate(() => {
    const menuText = document.querySelector(
      '[role="menu"][aria-label="交易分类"]'
    )?.textContent ?? ''
    const accountControls = [...document.querySelectorAll(
      'button, a, [role="menuitem"]'
    )].map((element) => element.textContent?.trim()).filter(Boolean)
    return {
      liveAccountEntry: accountControls.some((label) => (
        /(?:^|\s)LIVE(?:\s|$)/i.test(label)
      )),
      forbiddenProducts: ['INVERSE_PERP', 'OPTION', 'FOREX']
        .filter((label) => menuText.toUpperCase().includes(label)),
      tradingMenu: menuText.trim()
    }
  })
}

function scalarResult(raw) {
  const lines = String(raw ?? '')
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line.length > 0)
    .filter((line) => !/^(?:INSERT|UPDATE|DELETE|SELECT)\b/i.test(line))
  return lines[0] ?? ''
}

function assertUuid(value, label) {
  assert.match(
    value,
    /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
    `${label} UUID`
  )
}

async function applyAuthorityMark(scope, symbol, target) {
  assert(Number.isFinite(Number(target)) && Number(target) > 0)
  const market = await scope.context.api.snapshotMarket(symbol)
  const currentBid = Number(quoteDecimal(market, 'bid'))
  const currentAsk = Number(quoteDecimal(market, 'ask'))
  const minimumSpread = Math.max(
    currentAsk - currentBid,
    Number(rulesFor(market).tickSize ?? rulesFor(market).priceTick ?? 0.1)
  )
  const tick = String(
    rulesFor(market).tickSize ?? rulesFor(market).priceTick ?? '0.1'
  )
  const decimals = (tick.split('.')[1] ?? '').length
  const bid = (Number(target) - minimumSpread / 2).toFixed(decimals)
  const ask = (Number(target) + minimumSpread / 2).toFixed(decimals)
  const adminPage = await scope.getAdminPage()
  const fixture = await scope.context.fixtures.marketOverride(adminPage, {
    symbol,
    bid,
    ask,
    ttl: 'PT5M'
  })
  scope.registerFixtureRestore({
    action: 'restore-authority-market-bundle',
    symbol
  }, fixture.restore)
  scope.fixtureActions.push({
    action: 'set-authority-market-bundle',
    symbol,
    bid,
    ask
  })
  await waitForMarket(
    scope.context,
    symbol,
    `${scope.definition.id} authority mark ${target}`,
    (candidate) => {
      const mark = Number(candidate.reference?.mark ?? candidate.quote?.markPrice)
      return Number.isFinite(mark)
        && Math.abs(mark - Number(target)) <= minimumSpread
    }
  )
  return { bid, ask }
}

async function waitForMarket(context, symbol, description, predicate, timeoutMs = 30000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const market = await context.api.snapshotMarket(symbol)
    if (predicate(market)) return market
    await delay(100)
  }
  throw new Error(`Timed out waiting for ${description}`)
}

async function setRawLeverageViaUi(scope, leverage) {
  const capture = await scope.context.ui.withCapturedMutation(
    scope.page,
    {
      method: 'PATCH',
      url: /\/api\/accounts\/[^/]+\/symbols\/BTCUSDT-PERP\/settings$/
    },
    () => scope.page.evaluate((value) => {
      const section = document.querySelector(
        'section[aria-label="Perpetual trading settings"]'
      )
      const input = section?.querySelector('input[type="number"]')
      if (!(input instanceof HTMLInputElement)) {
        throw new Error('P0_PERPETUAL_LEVERAGE_INPUT_MISSING')
      }
      const setter = Object.getOwnPropertyDescriptor(
        HTMLInputElement.prototype,
        'value'
      )?.set
      setter?.call(input, String(value))
      input.dispatchEvent(new Event('input', { bubbles: true }))
      input.dispatchEvent(new Event('change', { bubbles: true }))
      return true
    }, leverage)
  )
  assert(capture.status >= 200 && capture.status < 300)
  return {
    capture,
    payload: JSON.parse(capture.rawRequest.postData)
  }
}

async function inspectMarginActionAvailability(page, position) {
  const cursor = page.p0Evidence.cursor
  const opened = await page.evaluate((positionSide) => {
    const rows = [...document.querySelectorAll('table tbody tr')]
      .filter((row) => row.querySelector('button[title]'))
    const matching = rows.filter((row) => (
      !positionSide || row.textContent?.includes(positionSide)
    ))
    const row = matching.length === 1 ? matching[0] : rows.length === 1 ? rows[0] : null
    const button = row?.querySelector('button[title]')
    if (!(button instanceof HTMLButtonElement)) return false
    button.click()
    return true
  }, position.positionSide)
  assert.equal(opened, true, 'PERP-09 Position action dialog opener')
  await page.waitForFunction(
    () => Boolean(document.querySelector(
      'section[role="dialog"][aria-label="Position action"]'
    )),
    'PERP-09 Position action dialog'
  )
  const result = await page.evaluate(() => {
    const dialog = document.querySelector(
      'section[role="dialog"][aria-label="Position action"]'
    )
    const adjust = [...(dialog?.querySelectorAll('[role="tab"]') ?? [])]
      .find((button) => button.textContent?.trim() === 'Adjust margin')
    const close = dialog?.querySelector('header button[aria-label="Close"]')
    const adjustMarginDisabled = !(adjust instanceof HTMLButtonElement)
      || adjust.disabled
    if (close instanceof HTMLButtonElement) close.click()
    return { adjustMarginDisabled }
  })
  await delay(100)
  const requestSent = page.p0Evidence.requests.some((request) => (
    request.cursor > cursor
      && request.method === 'POST'
      && /\/api\/trading\/positions\/[^/]+\/margin$/.test(request.url)
  ))
  return { ...result, requestSent }
}

async function inspectPerpetualOrderSurface(page) {
  const selector = page.p0TradePanel?.selector
  assert(selector, 'PERP-12 scoped trade panel')
  return page.evaluate((panelSelector) => {
    const panel = document.querySelector(panelSelector)
    const orderTabs = [...(panel?.querySelectorAll('[role="tab"]') ?? [])]
      .map((tab) => tab.textContent?.trim())
      .filter(Boolean)
    const options = panel?.querySelector(
      'section[aria-label="Perpetual order options"]'
    )
    return {
      orderTabs,
      positionSideVisible: Boolean(options?.querySelector(
        'label span + select'
      ))
    }
  }, selector)
}

function assertBatchCloseTrades(snapshot, db, beforeDb, items, positions, label) {
  const successful = items.filter(({ orderId, errorCode }) => (
    Boolean(orderId) && !errorCode
  ))
  assert.equal(successful.length, positions.length, `${label} successful closes`)
  assert.equal(
    new Set(successful.map(({ orderId }) => orderId)).size,
    positions.length,
    `${label} unique close orders`
  )
  assert.deepEqual(
    successful.map(({ positionId }) => positionId).toSorted(),
    positions.map(({ id }) => id).toSorted(),
    `${label} response position ids`
  )
  const financial = successful.map((item) => {
    const position = positions.find(({ id }) => id === item.positionId)
    assert(position, `${label} position ${item.positionId}`)
    const order = snapshot.orders.find(({ id }) => id === item.orderId)
    assert(order, `${label} close Order ${item.orderId}`)
    assert.equal(order.origin, 'BATCH_CLOSE', `${label} close origin`)
    assert.equal(
      order.parentPositionId,
      position.id,
      `${label} close Order position reference`
    )
    const trades = snapshot.trades.filter(({ orderId }) => orderId === item.orderId)
    assert.equal(trades.length, 1, `${label} order ${item.orderId} exact Trade`)
    const trade = trades[0]
    const oracle = perpCloseOracle({
      side: ['BUY', 'LONG'].includes(position.side) ? 'LONG' : 'SHORT',
      quantity: String(position.lots),
      entryPrice: String(position.openPrice),
      closeFillPrice: String(trade.price),
      rules: position.rules
    })
    assertDecimalClose(
      trade.realizedPnl,
      oracle.grossRealizedPnl,
      oracle.tolerances.amount,
      `${label} ${position.symbol} realized PnL`
    )
    assertDecimalClose(
      trade.fee,
      oracle.closeFee,
      oracle.tolerances.amount,
      `${label} ${position.symbol} close fee`
    )
    assert.equal(
      (snapshot.positionHistory ?? []).filter(({ id }) => id === position.id).length,
      1,
      `${label} ${position.symbol} closed history`
    )
    return { item, position, trade, oracle }
  })
  const ledger = assertPerpLedgerDelta(
    beforeDb,
    db,
    financial.flatMap(({ position, trade, oracle }) => [
      {
        operationType: 'MARGIN_RELEASE',
        referenceType: 'POSITION',
        referenceId: position.id,
        amount: position.marginHeld
      },
      {
        operationType: 'TRADE_FEE',
        referenceType: 'TRADE',
        referenceId: trade.id,
        amount: negativeAmount(oracle.closeFee)
      }
    ]),
    label
  )
  return {
    positions: financial.map(({ position, trade, oracle }) => ({
      positionId: position.id,
      symbol: position.symbol,
      orderId: trade.orderId,
      tradeId: trade.id,
      realizedPnl: oracle.grossRealizedPnl,
      closeFee: oracle.closeFee
    })),
    ledger
  }
}

async function openBatchPositions(scope, label) {
  const beforeBatch = await scope.context.api.snapshotAccount(scope.page)
  const beforeBatchDb = await scope.context.db.snapshotTradingRows(
    beforeBatch.account.id
  )
  const positions = []
  for (const [index, symbol] of [
    'BTCUSDT-PERP',
    'ETHUSDT-PERP',
    'SOLUSDT-PERP'
  ].entries()) {
    const market = await scope.context.api.snapshotMarket(symbol)
    await scope.context.ui.openTradePanel(scope.page, {
      product: 'perpetual',
      symbol
    })
    await ensurePerpSettings(scope, {
      positionMode: 'ONE_WAY',
      marginMode: index === 1 ? 'ISOLATED' : 'CROSS',
      leverage: 10,
      quantityUnit: 'BASE'
    })
    const before = await scope.context.api.snapshotAccount(scope.page)
    const capture = await scope.context.ui.submitOrderViaUi(scope.page, {
      side: index === 1 ? 'SELL' : 'BUY',
      orderType: 'MARKET',
      amount: stepAlignedQuantity(market, 0.01)
    })
    scope.addMutation(`batch-${label}-${symbol}-open-via-ui`, capture)
    const opened = await waitForFilledMutation(
      scope,
      before,
      capture,
      symbol
    )
    assertSingleFullFillMutation(before, opened, `BATCH-02 ${label} ${symbol}`)
    const position = openPositions(opened.snapshot, symbol).at(0)
    assert(position, `BATCH-02 ${label} ${symbol} position`)
    const rules = rulesFor(market)
    const hold = perpOpeningHoldOracle({
      baseQuantity: String(position.lots),
      worstPrice: String(opened.trade.price),
      leverage: String(position.leverage),
      rules
    })
    assertDecimalClose(
      opened.trade.fee,
      hold.feeBuffer,
      hold.tolerances.amount,
      `BATCH-02 ${label} ${symbol} opening fee`
    )
    assertDecimalClose(
      position.marginHeld,
      hold.openingInitialMargin,
      hold.tolerances.amount,
      `BATCH-02 ${label} ${symbol} opening margin`
    )
    positions.push({
      ...position,
      rules,
      openingTradeId: opened.trade.id,
      openingFee: opened.trade.fee
    })
  }
  const snapshot = await scope.context.api.snapshotAccount(scope.page)
  assert.equal(openPositions(snapshot).length, 3)
  const openedEvidence = await scope.capture(`${label}-positions-open`, snapshot)
  const openingLedger = assertPerpLedgerDelta(
    beforeBatchDb,
    openedEvidence.db,
    positions.flatMap((position) => [
      {
        operationType: 'MARGIN_HOLD',
        referenceType: 'POSITION',
        referenceId: position.id,
        amount: position.marginHeld
      },
      {
        operationType: 'TRADE_FEE',
        referenceType: 'TRADE',
        referenceId: position.openingTradeId,
        amount: negativeAmount(position.openingFee)
      }
    ]),
    `BATCH-02 ${label} opening`
  )
  return {
    positionIds: positions.map(({ id }) => id),
    positions,
    snapshot,
    db: openedEvidence.db,
    openingLedger
  }
}

async function probeDisabledOrderSubmission(page, order) {
  const cursor = page.p0Evidence.cursor
  const panelSelector = page.p0TradePanel?.selector
  assert(panelSelector, 'scoped trade panel is required for input probe')
  await page.evaluate((selector, input) => {
    const panel = document.querySelector(selector)
    if (!panel) throw new Error('P0_SCOPED_TRADE_PANEL_MISSING')
    const side = String(input.side ?? 'BUY').toLowerCase()
    const type = String(input.orderType ?? 'MARKET').toUpperCase()
    const visible = (element) => {
      const rect = element?.getBoundingClientRect()
      const style = element ? getComputedStyle(element) : null
      return Boolean(rect && style && rect.width > 0 && rect.height > 0
        && style.display !== 'none' && style.visibility !== 'hidden')
    }
    const tabIndex = type === 'LIMIT' ? 0 : type === 'MARKET' ? 1 : 2
    const tabs = [...panel.querySelectorAll('[role="tablist"]')]
      .map((tablist) => [...tablist.querySelectorAll(':scope > [role="tab"]')]
        .filter(visible))
      .find((candidates) => candidates.length >= 3) ?? []
    tabs[tabIndex]?.click()
    const form = panel.querySelector(
      `section[data-price-precision][class*="side--${side}"]`
    )
    if (!form) throw new Error('P0_SCOPED_ORDER_FORM_MISSING')
    const inputs = [...form.querySelectorAll('input[inputmode="decimal"]:not([disabled])')]
    const amount = inputs.at(-1)
    if (!(amount instanceof HTMLInputElement)) {
      throw new Error('P0_SCOPED_ORDER_AMOUNT_MISSING')
    }
    const setter = Object.getOwnPropertyDescriptor(
      HTMLInputElement.prototype,
      'value'
    )?.set
    setter?.call(amount, String(input.amount ?? ''))
    amount.dispatchEvent(new Event('input', { bubbles: true }))
    amount.dispatchEvent(new Event('change', { bubbles: true }))
  }, panelSelector, order)
  await delay(150)
  const click = await page.evaluate((selector, input) => {
    const panel = document.querySelector(selector)
    if (!panel) throw new Error('P0_SCOPED_TRADE_PANEL_MISSING')
    const side = String(input.side ?? 'BUY').toLowerCase()
    const form = panel.querySelector(
      `section[data-price-precision][class*="side--${side}"]`
    )
    if (!form) throw new Error('P0_SCOPED_ORDER_FORM_MISSING')
    const submit = form.querySelector('[data-trading-action="submit-order"]')
    if (!(submit instanceof HTMLButtonElement)) {
      throw new Error('P0_SCOPED_ORDER_SUBMIT_MISSING')
    }
    const submitDisabled = submit.disabled
    if (!submitDisabled) submit.click()
    return { clicked: !submitDisabled, submitDisabled }
  }, panelSelector, order)
  await delay(150)
  const guard = await page.evaluate((selector, input) => {
    const panel = document.querySelector(selector)
    if (!panel) throw new Error('P0_SCOPED_TRADE_PANEL_MISSING')
    const side = String(input.side ?? 'BUY').toLowerCase()
    const form = panel.querySelector(
      `section[data-price-precision][class*="side--${side}"]`
    )
    if (!form) throw new Error('P0_SCOPED_ORDER_FORM_MISSING')
    const visible = (element) => {
      const rect = element?.getBoundingClientRect()
      const style = element ? getComputedStyle(element) : null
      return Boolean(rect && style && rect.width > 0 && rect.height > 0
        && style.display !== 'none' && style.visibility !== 'hidden')
    }
    const errorText = [...form.querySelectorAll('[class*="trade-panel__error"]')]
      .filter(visible)
      .map((element) => element.textContent?.trim())
      .filter(Boolean)
    return {
      confirmationOpen: Boolean(document.querySelector(
        '[role="dialog"][aria-modal="true"]'
      )),
      errorText,
      visibleError: errorText.length > 0
    }
  }, panelSelector, order)
  if (guard.confirmationOpen) {
    const dismissed = await page.evaluate(() => {
      const dialog = document.querySelector('[role="dialog"][aria-modal="true"]')
      const button = [...(dialog?.querySelectorAll('button') ?? [])].find(
        (candidate) => /^(?:Cancel|Close|取消|关闭)$/i.test(
          candidate.textContent?.trim() ?? ''
        )
      )
      if (!(button instanceof HTMLButtonElement)) return false
      button.click()
      return true
    })
    if (!dismissed) {
      await page.send('Input.dispatchKeyEvent', {
        type: 'keyDown',
        key: 'Escape',
        code: 'Escape'
      })
      await page.send('Input.dispatchKeyEvent', {
        type: 'keyUp',
        key: 'Escape',
        code: 'Escape'
      })
    }
    await page.waitForFunction(
      () => !document.querySelector('[role="dialog"][aria-modal="true"]'),
      'invalid order confirmation dismissal'
    )
  }
  const requestSent = page.p0Evidence.requests.some((request) => (
    request.cursor > cursor
      && request.method === 'POST'
      && /\/api\/trading\/orders$/.test(request.url)
  ))
  return {
    ...click,
    ...guard,
    requestSent,
    guarded: !requestSent
      && !guard.confirmationOpen
      && (click.submitDisabled || (click.clicked && guard.visibleError))
  }
}

export function tradingStateFingerprint(snapshot) {
  const canonicalize = (value) => {
    if (Array.isArray(value)) {
      return value
        .map(canonicalize)
        .sort((left, right) => (
          JSON.stringify(left).localeCompare(JSON.stringify(right))
        ))
    }
    if (value && typeof value === 'object') {
      return Object.fromEntries(
        Object.entries(value)
          .sort(([left], [right]) => left.localeCompare(right))
          .map(([key, entry]) => [key, canonicalize(entry)])
      )
    }
    return value
  }
  return canonicalize({
    wallets: snapshot.wallets ?? [],
    summary: snapshot.summary ?? {},
    orders: snapshot.orders ?? [],
    trades: snapshot.trades ?? [],
    positions: snapshot.positions ?? [],
    positionHistory: snapshot.positionHistory ?? [],
    settings: snapshot.settings ?? {},
    transfers: snapshot.transfers ?? [],
    fundingSettlements: snapshot.fundingSettlements ?? [],
    assetLedger: snapshot.assetLedger ?? [],
    cashLedger: snapshot.cashLedger ?? []
  })
}

function assertTradingStateEqual(before, after, label) {
  assert.deepEqual(
    tradingStateFingerprint(after),
    tradingStateFingerprint(before),
    label
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

async function closeBrowserPages(...entries) {
  const failures = []
  for (const [page, browser] of entries) {
    try {
      await closeBrowserPage(page, browser)
    } catch (error) {
      failures.push(error)
    }
  }
  if (failures.length === 1) throw failures[0]
  if (failures.length > 1) {
    throw new AggregateError(failures, 'P0_BROWSER_CLEANUP_FAILED')
  }
}
