import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import test from 'node:test'

import {
  canonicalJson,
  normalizeScenario
} from '../apps/admin/src/features/tradingLab/model/normalization.ts'
import {
  generateRandomScenario
} from '../apps/admin/src/features/tradingLab/generator/randomScenario.ts'

const MODEL_VERSION = 'trading-lab-v1'
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const DECIMAL_PATTERN = /^(?:0|[1-9]\d*)(?:\.\d+)?$/
const CAPABILITY_PATTERN = /^[A-Z][A-Z0-9_]*$/
const REPORT_SECTIONS = [
  'metadata',
  'actor',
  'environment',
  'scenario',
  'modelVersion',
  'configSnapshot',
  'localCalculation',
  'lifecycle',
  'apiTrace',
  'marketTicks',
  'checkpoints',
  'actualState',
  'errors',
  'cleanup'
]
const REQUIRED_CATEGORIES = [
  'spot-repeated-buy-partial-sell-buy-final-sell',
  'spot-weighted-cost-break-even',
  'perp-long-repeated-add-partial-close',
  'perp-short-repeated-add-partial-close',
  'perp-add-close-add-close',
  'one-way-reversal',
  'hedge-long-short-slots',
  'isolated-margin-leverage-adjustment',
  'cross-multi-symbol-shared-usdt',
  'advanced-order-types',
  'time-in-force-reduce-only',
  'batch-tp-sl',
  'manual-automatic-funding',
  'simple-depth-partial-fill',
  'monotonic-single-target-path',
  'realistic-multi-waypoint-path',
  'isolated-liquidation',
  'cross-full-liquidation',
  'negative-balance-margin-precision-notional',
  'pause-resume-cancel',
  'failure-report-cleanup',
  'two-run-contamination',
  'permission-supervisor-injection',
  'large-report-chunk-print-confirmation'
]
const SCENARIO_KEYS = [
  'category',
  'defaults',
  'initialBalances',
  'requiredCapabilities',
  'scenarioId',
  'seed',
  'symbols',
  'timeline'
]
const SEED_KEYS = [
  'name',
  'negativeMode',
  'requiredCapabilities',
  'seed'
]
const TIMELINE_TYPES = new Set([
  'PLACE_ORDER',
  'CANCEL_ORDER',
  'CANCEL_ALL',
  'SET_POSITION_MODE',
  'SET_MARGIN_MODE',
  'SET_LEVERAGE',
  'ADD_MARGIN',
  'REMOVE_MARGIN',
  'APPLY_FUNDING',
  'PLACE_OCO'
])
const ORDER_TYPES = new Set([
  'MARKET',
  'LIMIT',
  'STOP_LIMIT',
  'TRAILING_STOP_MARKET'
])
const TIME_IN_FORCE_VALUES = new Set(['GTC', 'IOC', 'FOK'])

test('fixed scenario fixture has 24 unique, stable, valid category rows', () => {
  const fixture = readJson('fixed-scenarios.json')
  assertExactKeys(fixture, ['modelVersion', 'scenarios'])
  assert.equal(fixture.modelVersion, MODEL_VERSION)
  assert.ok(Array.isArray(fixture.scenarios))
  assert.equal(fixture.scenarios.length, 24)

  const ids = fixture.scenarios.map((scenario) => scenario.scenarioId)
  const categories = fixture.scenarios.map((scenario) => scenario.category)
  assert.deepEqual(ids, [...ids].sort())
  assert.equal(new Set(ids).size, ids.length)
  assert.equal(new Set(categories).size, categories.length)
  assert.deepEqual(categories, REQUIRED_CATEGORIES)

  const seeds = new Set()
  for (const [index, scenario] of fixture.scenarios.entries()) {
    assertExactKeys(scenario, SCENARIO_KEYS)
    assert.match(scenario.scenarioId, UUID_PATTERN)
    assert.equal(typeof scenario.category, 'string')
    assert.equal(typeof scenario.seed, 'string')
    assert.ok(scenario.seed.length > 0 && scenario.seed.length <= 256)
    assert.equal(seeds.has(scenario.seed), false)
    seeds.add(scenario.seed)
    assertCapabilities(scenario.requiredCapabilities)
    assertInitialBalances(scenario.initialBalances)
    const symbols = assertSymbols(scenario.symbols)
    assertDefaults(scenario.defaults)
    assertTimeline(scenario.timeline, symbols)
    assert.equal(
      scenario.scenarioId.endsWith(String(index + 1).padStart(12, '0')),
      true
    )
  }
})

test('named random seeds cover legal and negative generation deterministically', () => {
  const fixture = readJson('random-seeds.json')
  assertExactKeys(fixture, ['modelVersion', 'seeds'])
  assert.equal(fixture.modelVersion, MODEL_VERSION)
  assert.ok(Array.isArray(fixture.seeds))
  assert.ok(fixture.seeds.length >= 20)

  const names = fixture.seeds.map((entry) => entry.name)
  const seeds = fixture.seeds.map((entry) => entry.seed)
  assert.deepEqual(names, [...names].sort())
  assert.equal(new Set(names).size, names.length)
  assert.equal(new Set(seeds).size, seeds.length)
  assert.ok(fixture.seeds.some((entry) => entry.negativeMode === false))
  assert.ok(fixture.seeds.some((entry) => entry.negativeMode === true))

  const hashes = new Set()
  for (const entry of fixture.seeds) {
    assertExactKeys(entry, SEED_KEYS)
    assert.match(entry.name, /^[a-z0-9]+(?:-[a-z0-9]+)*$/)
    assert.equal(typeof entry.seed, 'string')
    assert.ok(entry.seed.length > 0 && entry.seed.length <= 256)
    assert.equal(typeof entry.negativeMode, 'boolean')
    assertCapabilities(entry.requiredCapabilities)

    const first = generateRandomScenario(randomInput(entry))
    const second = generateRandomScenario(randomInput(entry))
    const firstHash = canonicalHash(normalizeScenario(first))
    const secondHash = canonicalHash(normalizeScenario(second))
    assert.equal(firstHash, secondHash, entry.name)
    assert.equal(first.seed, entry.seed)
    assert.equal(first.negativeMode, entry.negativeMode)
    hashes.add(firstHash)
  }
  assert.equal(hashes.size, fixture.seeds.length)
})

test('report JSON Schema freezes all 14 sections and current API trace fields', () => {
  const schema = readJson('report-schema.json')
  assert.equal(
    schema.$schema,
    'https://json-schema.org/draft/2020-12/schema'
  )
  assert.equal(schema.type, 'object')
  assert.equal(schema.additionalProperties, false)
  assert.deepEqual(schema.required, REPORT_SECTIONS)
  assert.deepEqual(Object.keys(schema.properties), REPORT_SECTIONS)

  for (const section of [
    'metadata',
    'actor',
    'environment',
    'scenario',
    'configSnapshot',
    'localCalculation',
    'actualState',
    'cleanup'
  ]) {
    assert.equal(schema.properties[section].type, 'object')
  }
  assert.deepEqual(schema.properties.modelVersion, {
    type: 'string',
    minLength: 1,
    maxLength: 80
  })
  for (const section of [
    'lifecycle',
    'apiTrace',
    'marketTicks',
    'checkpoints',
    'errors'
  ]) {
    assert.equal(schema.properties[section].type, 'array')
  }
  assert.equal(schema.properties.apiTrace.items.$ref, '#/$defs/apiTrace')

  const apiTrace = schema.$defs.apiTrace
  assert.equal(apiTrace.type, 'object')
  assert.equal(apiTrace.additionalProperties, false)
  assert.deepEqual(apiTrace.required, [
    'url',
    'queryParameters',
    'requestHeaders',
    'requestContentType',
    'requestBody',
    'responseHeaders',
    'responseContentType',
    'responseBody',
    'exception',
    'authentication'
  ])
  assert.deepEqual(apiTrace.properties.requestBody.required, [
    'sequence',
    'environment',
    'method',
    'url',
    'virtualTime',
    'realTime',
    'sanitizedRequest'
  ])
  assert.deepEqual(apiTrace.properties.responseBody.required, [
    'status',
    'duration',
    'traceId',
    'correlationId',
    'recordedException'
  ])
  assert.deepEqual(
    apiTrace.properties.requestBody.properties.method.enum,
    ['GET', 'POST', 'PATCH', 'DELETE']
  )
  assert.deepEqual(
    apiTrace.properties.requestBody.properties.environment.enum,
    ['main-admin', 'validation']
  )
  assert.equal(apiTrace.properties.requestBody.additionalProperties, false)
  assert.equal(
    apiTrace.properties.responseBody.additionalProperties.$ref,
    '#/$defs/jsonValue'
  )
})

test('fixture README documents the offline contract and focused command', () => {
  const readme = readText('README.md')
  assert.match(readme, /24 fixed scenario categories/i)
  assert.match(readme, /deterministic named seeds/i)
  assert.match(readme, /14 top-level report sections/i)
  assert.match(
    readme,
    /node --test fx-trading-platform\/scripts\/smoke-trading-lab\.test\.mjs/
  )
  assert.match(readme, /does not start/i)
  assert.match(readme, /demo-only/i)
})

test('browser smoke source is a real 1440x900 Admin journey with durable evidence', () => {
  const source = readScript('smoke-trading-lab.mjs')

  assert.doesNotMatch(
    source,
    /\b(?:Fetch\.enable|Network\.setRequestInterception|setRequestInterception|page\.route|route\.fulfill|mockTrading)\b/,
  )
  assert.match(source, /const VIEWPORT_WIDTH = 1440/)
  assert.match(source, /const VIEWPORT_HEIGHT = 900/)
  assert.match(source, /fillLabel\(page, '管理员邮箱'/)
  assert.match(source, /fillLabel\(page, '密码'/)
  assert.match(source, /clickRole\(page, 'button', '登录'/)
  assert.match(source, /\/trading\/lab/)
  assert.match(source, /Runtime\.consoleAPICalled/)
  assert.match(source, /unhandledrejection/)
  assert.match(source, /Network\.responseReceived/)
  assert.match(source, /Network\.responseReceivedExtraInfo/)
  assert.match(source, /Page\.captureScreenshot/)
  assert.match(source, /Browser\.setDownloadBehavior/)
  assert.match(source, /Browser\.downloadProgress/)
  assert.match(source, /clickRole\(page,/)
  assert.match(source, /fillLabel\(page,/)
  assert.match(source, /clickTestId\(page,/)
})

test('owned browser downloads use an explicit fallback and real pointer activation', () => {
  const source = readScript('smoke-trading-lab.mjs')
  const configureStart = source.indexOf('async function configurePage(')
  const configureEnd = source.indexOf(
    'async function setViewport(',
    configureStart,
  )
  const configureSource = source.slice(configureStart, configureEnd)
  const clickStart = source.indexOf('async function clickRole(')
  const clickEnd = source.indexOf(
    'async function roleDisabled(',
    clickStart,
  )
  const clickSource = source.slice(clickStart, clickEnd)
  const downloadStart = source.indexOf(
    'async function downloadCurrentReport(',
  )
  const downloadEnd = source.indexOf(
    'function assertReportSchema(',
    downloadStart,
  )
  const downloadSource = source.slice(downloadStart, downloadEnd)

  assert.ok(configureStart >= 0 && configureEnd > configureStart)
  assert.match(
    source,
    /configurePage\(context\.page,\s*\{\s*headless:\s*!context\.options\.headed/u,
  )
  assert.match(configureSource, /if \(headless\)/)
  assert.match(
    configureSource,
    /installOwnedHeadlessPrintBridge\.toString\(\)/,
  )
  assert.match(configureSource, /showSaveFilePicker/)
  assert.match(configureSource, /value:\s*undefined/)
  assert.match(source, /OWNED_DOWNLOAD_MODE = 'BLOB_FALLBACK'/)
  assert.match(source, /downloadMode:\s*context\.downloadMode/)

  assert.ok(clickStart >= 0 && clickEnd > clickStart)
  assert.match(
    clickSource,
    /await waitForRoleEnabled\(page, role, accessibleName\)/,
  )
  assert.match(clickSource, /Input\.dispatchMouseEvent/)
  assert.match(clickSource, /type:\s*'mousePressed'/)
  assert.match(clickSource, /type:\s*'mouseReleased'/)
  assert.doesNotMatch(clickSource, /match\.click\(\)/)

  assert.ok(downloadStart >= 0 && downloadEnd > downloadStart)
  assert.match(downloadSource, /reportTransferError/)

  const printStart = source.indexOf('async function runPrintJourney(')
  const printEnd = source.indexOf(
    'async function readOwnedHeadlessPrintBridge(',
    printStart,
  )
  const printSource = source.slice(printStart, printEnd)
  assert.ok(printStart >= 0 && printEnd > printStart)
  assert.match(
    printSource,
    /assert\.equal\(\s*options\.headed,\s*false/u,
  )
  assert.match(
    printSource,
    /smallReport:\s*\{\s*runId:\s*core\.runId/u,
  )
  assert.match(
    printSource,
    /executeDeniedLargeReport:\s*\{\s*runId:\s*largeRunId/u,
  )
  assert.match(
    printSource,
    /smallAfter\.receipts\.length/u,
  )
  assert.match(
    printSource,
    /largeAfter\.receipts\.length/u,
  )
})

test('owned headless print bridge records only Trading Lab raw print invocations', async () => {
  const { installOwnedHeadlessPrintBridge } =
    await import('./smoke-trading-lab.mjs')
  const openCalls = []
  const facadeCalls = []
  let nativePrintCalls = 0
  const originalTargetPrint = function () {
    throw new Error('owned Trading Lab popup reached native print')
  }
  const targetPopup = {
    opener: null,
    closed: false,
    document: { title: '' },
    print: originalTargetPrint,
    addEventListener(...args) {
      assert.equal(this, targetPopup)
      facadeCalls.push(['addEventListener', ...args])
    },
    removeEventListener(...args) {
      assert.equal(this, targetPopup)
      facadeCalls.push(['removeEventListener', ...args])
    },
    close() {
      assert.equal(this, targetPopup)
      facadeCalls.push(['close'])
      this.closed = true
    },
    focus() {
      assert.equal(this, targetPopup)
      facadeCalls.push(['focus'])
    },
    requestAnimationFrame(callback) {
      assert.equal(this, targetPopup)
      facadeCalls.push(['requestAnimationFrame', callback])
      return 17
    },
    cancelAnimationFrame(frameId) {
      assert.equal(this, targetPopup)
      facadeCalls.push(['cancelAnimationFrame', frameId])
    },
  }
  const ordinaryPopup = {
    document: { title: 'Trading Lab raw report' },
    print() {
      assert.equal(this, ordinaryPopup)
      nativePrintCalls += 1
    },
  }
  const inaccessiblePopup = {
    get document() {
      throw new DOMException('cross origin', 'SecurityError')
    },
    print() {
      assert.equal(this, inaccessiblePopup)
      nativePrintCalls += 1
    },
  }
  const popups = [targetPopup, ordinaryPopup, inaccessiblePopup, null]
  const scope = {
    open(...args) {
      assert.equal(this, scope)
      openCalls.push(args)
      return popups.shift()
    },
  }
  targetPopup.opener = scope

  const bridge = installOwnedHeadlessPrintBridge(scope)
  assert.equal(
    bridge.mode,
    'HEADLESS_PRINT_INVOCATION_INTERCEPT',
  )
  assert.equal(Object.isFrozen(bridge), true)
  assert.equal(installOwnedHeadlessPrintBridge(scope), bridge)

  const target = scope.open('/target', '_blank')
  assert.notEqual(target, null)
  assert.equal(Object.isFrozen(target), true)
  assert.equal(targetPopup.print, originalTargetPrint)
  const targetDocument = target.document
  const pagehide = () => {}
  target.opener = null
  assert.equal(targetPopup.opener, null)
  assert.equal(target.closed, false)
  target.addEventListener('pagehide', pagehide)
  target.removeEventListener('pagehide', pagehide)
  target.focus()
  const frameCallback = () => {}
  assert.equal(target.requestAnimationFrame(frameCallback), 17)
  target.cancelAnimationFrame(17)
  targetDocument.title = 'Trading Lab raw report'
  targetPopup.document = { title: '' }
  targetPopup.print = function () {
    throw new Error(
      'owned Trading Lab popup reached a replaced native print method',
    )
  }
  assert.equal(target.print(), undefined)
  assert.deepEqual(
    bridge.snapshot().map(({ sequence, title }) => ({ sequence, title })),
    [{
      sequence: 1,
      title: 'Trading Lab raw report',
    }],
  )
  assert.match(bridge.snapshot()[0].at, /^\d{4}-\d{2}-\d{2}T/u)
  assert.equal(Object.isFrozen(bridge.snapshot()[0]), true)
  target.close()
  assert.equal(targetPopup.closed, true)
  assert.deepEqual(facadeCalls, [
    ['addEventListener', 'pagehide', pagehide],
    ['removeEventListener', 'pagehide', pagehide],
    ['focus'],
    ['requestAnimationFrame', frameCallback],
    ['cancelAnimationFrame', 17],
    ['close'],
  ])

  const ordinary = scope.open('/ordinary', '_blank')
  assert.notEqual(ordinary, null)
  ordinaryPopup.document.title = 'Trading Lab raw report '
  ordinary.print()
  assert.equal(nativePrintCalls, 1)
  assert.equal(bridge.snapshot().length, 1)

  const inaccessible = scope.open('/cross-origin', '_blank')
  assert.notEqual(inaccessible, null)
  inaccessible.print()
  assert.equal(nativePrintCalls, 2)
  assert.equal(bridge.snapshot().length, 1)

  assert.equal(scope.open('/blocked', '_blank'), null)
  assert.equal(bridge.snapshot().length, 1)
  assert.deepEqual(openCalls, [
    ['/target', '_blank'],
    ['/ordinary', '_blank'],
    ['/cross-origin', '_blank'],
    ['/blocked', '_blank'],
  ])
})

test('print completion receipts are recorded only after UI and bridge completion', () => {
  const source = readScript('smoke-trading-lab.mjs')
  const start = source.indexOf('async function runPrintJourney(')
  const end = source.indexOf(
    'async function readOwnedHeadlessPrintBridge(',
    start,
  )
  const journey = source.slice(start, end)
  const smallUi = journey.indexOf(
    "await waitForText(page, '打印流程已结束')",
  )
  const smallStable = journey.indexOf(
    "'Small report print receipt changed after UI completion'",
  )
  const smallCompletion = journey.indexOf(
    'identity: smallIdentity',
    smallStable,
  )
  const largeUi = journey.indexOf(
    "await waitForText(page, '打印流程已结束', RUN_TIMEOUT_MS)",
  )
  const largeStable = journey.indexOf(
    "'Large report print receipt changed after UI completion'",
  )
  const largeCompletion = journey.indexOf(
    'identity: largeIdentity',
    largeStable,
  )

  assert.ok(start >= 0 && end > start)
  assert.ok(smallUi >= 0 && smallStable > smallUi)
  assert.ok(smallCompletion > smallStable)
  assert.ok(largeUi > smallCompletion && largeStable > largeUi)
  assert.ok(largeCompletion > largeStable)
  assert.equal(
    journey.match(/recordCompletedReportPrintReceipt\(context,/gu)?.length,
    2,
  )
  assert.match(
    journey,
    /EXECUTE Admin large-report denial reached the raw print request/u,
  )
  assert.match(
    journey,
    /EXECUTE Admin large-report denial created a print completion receipt/u,
  )
})

test('screenshot capture uses the bounded page timeout', () => {
  const source = readScript('smoke-trading-lab.mjs')
  const screenshotStart = source.indexOf(
    'async function captureScreenshot(',
  )
  const screenshotEnd = source.indexOf(
    'async function downloadCurrentReport(',
    screenshotStart,
  )
  const screenshotSource = source.slice(screenshotStart, screenshotEnd)

  assert.ok(screenshotStart >= 0 && screenshotEnd > screenshotStart)
  assert.match(
    screenshotSource,
    /Page\.captureScreenshot[\s\S]*PAGE_TIMEOUT_MS/u,
  )
  assert.equal(
    screenshotSource.match(
      /page\.send\(\s*'Page\.captureScreenshot'/gu,
    )?.length,
    2,
    'screenshot timeout must have exactly one bounded retry',
  )
  assert.match(
    screenshotSource,
    /asError\(error\)\.message !== 'CDP Page\.captureScreenshot timed out'/u,
  )
  assert.match(screenshotSource, /Page\.bringToFront/u)
  assert.match(screenshotSource, /optimizeForSpeed:\s*true/u)
  assert.doesNotMatch(screenshotSource, /Page\.stopLoading/u)
})

test('only exact owned SSE, download, and completed print aborts are expected', async () => {
  const { classifyExpectedCanceledNetworkFailures } =
    await import('./smoke-trading-lab.mjs')
  assert.equal(
    typeof classifyExpectedCanceledNetworkFailures,
    'function',
  )

  const runId = '11111111-1111-4111-8111-111111111111'
  const unownedRunId = '22222222-2222-4222-8222-222222222222'
  const reportId = '33333333-3333-4333-8333-333333333333'
  const incompleteReportId = '44444444-4444-4444-8444-444444444444'
  const printReportId = '55555555-5555-4555-8555-555555555555'
  const unreceiptedPrintReportId =
    '66666666-6666-4666-8666-666666666666'
  const printXRequestId = '77777777-7777-4777-8777-777777777777'
  const printRequestTupleSha256 = 'a'.repeat(64)
  const sseUrl =
    `http://127.0.0.1:18086/api/admin/trading-lab/runs/${runId}/events`
  const unownedSseUrl =
    `http://127.0.0.1:18086/api/admin/trading-lab/runs/${unownedRunId}/events`
  const downloadUrl =
    `http://127.0.0.1:18086/api/admin/trading-lab/reports/${reportId}/download`
  const incompleteDownloadUrl =
    `http://127.0.0.1:18086/api/admin/trading-lab/reports/${incompleteReportId}/download`
  const printUrl =
    `http://127.0.0.1:18086/api/admin/trading-lab/reports/${printReportId}/print`
  const unreceiptedPrintUrl =
    `http://127.0.0.1:18086/api/admin/trading-lab/reports/${unreceiptedPrintReportId}/print`
  const failures = [
    canceledFailure('sse-owned', sseUrl),
    canceledFailure('sse-unowned', unownedSseUrl),
    canceledFailure('download-complete', downloadUrl),
    canceledFailure('download-incomplete', incompleteDownloadUrl),
    canceledFailure('print-complete', printUrl),
    canceledFailure('print-unreceipted', unreceiptedPrintUrl),
  ]
  failures[1].expected = true
  failures[1].expectedReason = 'FORGED_NAVIGATION_WINDOW'
  failures[1].expectedReceipt = { requestId: 'forged' }
  const diagnostics = {
    failures,
    requests: [
      fetchRequest('sse-owned', sseUrl),
      fetchRequest('sse-unowned', unownedSseUrl),
      fetchRequest('download-complete', downloadUrl),
      fetchRequest('download-incomplete', incompleteDownloadUrl),
      {
        ...fetchRequest('print-complete', printUrl),
        xRequestId: printXRequestId,
      },
      fetchRequest('print-unreceipted', unreceiptedPrintUrl),
    ],
    responses: [
      fetchResponse('sse-owned', sseUrl, 'text/event-stream'),
      fetchResponse('sse-unowned', unownedSseUrl, 'text/event-stream'),
      fetchResponse('download-complete', downloadUrl, 'application/json'),
      fetchResponse(
        'download-incomplete',
        incompleteDownloadUrl,
        'application/json',
      ),
      {
        ...fetchResponse('print-complete', printUrl, 'text/plain'),
        xRequestId: printXRequestId,
        requestTupleSha256: printRequestTupleSha256,
      },
      {
        ...fetchResponse(
          'print-unreceipted',
          unreceiptedPrintUrl,
          'text/plain',
        ),
        xRequestId: '88888888-8888-4888-8888-888888888888',
        requestTupleSha256: 'b'.repeat(64),
      },
    ],
    downloads: [
      {
        guid: 'complete-guid',
        suggestedFilename: `trading-lab-report-${reportId}.json`,
        state: 'completed',
        receivedBytes: 128,
        totalBytes: 128,
        path: 'C:\\owned\\complete-report.json',
      },
      {
        guid: 'incomplete-guid',
        suggestedFilename:
          `trading-lab-report-${incompleteReportId}.json`,
        state: 'inProgress',
        receivedBytes: 64,
        totalBytes: 128,
        path: 'C:\\owned\\incomplete-report.json',
      },
    ],
  }

  const classified = classifyExpectedCanceledNetworkFailures({
    diagnostics,
    sseCloseReceipts: [
      {
        requestId: 'sse-owned',
        runId,
        terminalState: 'COMPLETED',
      },
      {
        requestId: 'sse-unowned',
        runId: unownedRunId,
        terminalState: 'COMPLETED',
      },
    ],
    downloadReceipts: [
      {
        requestId: 'download-complete',
        reportId,
        guid: 'complete-guid',
        path: 'C:\\owned\\complete-report.json',
        bytes: 128,
      },
      {
        requestId: 'download-incomplete',
        reportId: incompleteReportId,
        guid: 'incomplete-guid',
        path: 'C:\\owned\\incomplete-report.json',
        bytes: 128,
      },
    ],
    printReceipts: [
      {
        requestId: 'print-complete',
        runId,
        reportId: printReportId,
        xRequestId: printXRequestId,
        requestTupleSha256: printRequestTupleSha256,
        bridgeSequence: 1,
        bridgeTitle: 'Trading Lab raw report',
        bridgeAt: '2026-07-28T16:41:08.984Z',
        uiStatus: '打印流程已结束',
      },
    ],
    ownedRunIds: [runId],
    ownedReportIds: [
      reportId,
      incompleteReportId,
      printReportId,
      unreceiptedPrintReportId,
    ],
    fileExists: (path) => path === 'C:\\owned\\complete-report.json',
  })

  assert.deepEqual(
    classified.map(({ expected, expectedReason }) => ({
      expected,
      expectedReason: expectedReason ?? null,
    })),
    [
      {
        expected: true,
        expectedReason: 'OWNED_TERMINAL_SSE_CLOSE',
      },
      { expected: false, expectedReason: null },
      {
        expected: true,
        expectedReason: 'OWNED_COMPLETED_REPORT_DOWNLOAD',
      },
      { expected: false, expectedReason: null },
      {
        expected: true,
        expectedReason: 'OWNED_COMPLETED_REPORT_PRINT',
      },
      { expected: false, expectedReason: null },
    ],
  )
  assert.equal(Object.hasOwn(classified[1], 'expectedReason'), false)
  assert.equal(Object.hasOwn(classified[1], 'expectedReceipt'), false)
})

test('only one exact request-bound permission denial console error is expected', async () => {
  const { classifyExpectedConsoleErrors } =
    await import('./smoke-trading-lab.mjs')
  assert.equal(typeof classifyExpectedConsoleErrors, 'function')

  const requestId = 'permission-fetch-403'
  const xRequestId = '11111111-1111-4111-8111-111111111111'
  const url =
    'http://127.0.0.1:18086/api/admin/trading-lab/environment'
  const message =
    'Failed to load resource: the server responded with a status of 403 ()'
  const exact = {
    type: 'error',
    source: 'network',
    networkRequestId: requestId,
    url,
    message,
    expected: false,
  }
  const forgedDuplicate = {
    ...exact,
    expected: true,
    expectedReason: 'FORGED_EXPECTATION',
    expectedReceipt: { requestId: 'forged' },
  }
  const entries = [
    exact,
    forgedDuplicate,
    { ...exact, networkRequestId: 'different-request' },
    { ...exact, url: 'http://127.0.0.1:18086/api/admin/trading-lab' },
    { ...exact, source: 'runtime' },
  ]
  const receipt = {
    requestId,
    xRequestId,
    requestTupleSha256: 'a'.repeat(64),
    url,
    status: 403,
    method: 'GET',
    resourceType: 'Fetch',
    mimeType: 'application/json',
    message,
  }
  const diagnostics = {
    console: entries,
    requests: [{
      requestId,
      xRequestId,
      url,
      method: 'GET',
      resourceType: 'Fetch',
    }],
    responses: [{
      requestId,
      xRequestId,
      requestTupleSha256: 'a'.repeat(64),
      url,
      status: 403,
      resourceType: 'Fetch',
      mimeType: 'application/json',
      expected: true,
    }],
  }

  const classified = classifyExpectedConsoleErrors({
    diagnostics,
    receipts: [receipt],
  })

  assert.deepEqual(
    classified.map(({ expected, expectedReason }) => ({
      expected,
      expectedReason: expectedReason ?? null,
    })),
    [
      {
        expected: true,
        expectedReason: 'EXPECTED_PERMISSION_DENIAL_CONSOLE',
      },
      { expected: false, expectedReason: null },
      { expected: false, expectedReason: null },
      { expected: false, expectedReason: null },
      { expected: false, expectedReason: null },
    ],
  )
  assert.deepEqual(classified[0].expectedReceipt, receipt)
  for (const entry of classified.slice(1)) {
    assert.equal(Object.hasOwn(entry, 'expectedReason'), false)
    assert.equal(Object.hasOwn(entry, 'expectedReceipt'), false)
  }

  const wrongStatus = classifyExpectedConsoleErrors({
    diagnostics: { ...diagnostics, console: [exact] },
    receipts: [{ ...receipt, status: 500 }],
  })
  assert.equal(wrongStatus[0].expected, false)
  const forgedRequestUuid = classifyExpectedConsoleErrors({
    diagnostics: { ...diagnostics, console: [exact] },
    receipts: [{
      ...receipt,
      xRequestId: '22222222-2222-4222-8222-222222222222',
    }],
  })
  assert.equal(forgedRequestUuid[0].expected, false)
  const forgedTuple = classifyExpectedConsoleErrors({
    diagnostics: { ...diagnostics, console: [exact] },
    receipts: [{ ...receipt, requestTupleSha256: 'b'.repeat(64) }],
  })
  assert.equal(forgedTuple[0].expected, false)
  const forgedOrigin = classifyExpectedConsoleErrors({
    diagnostics: { ...diagnostics, console: [{
      ...exact,
      url: url.replace(':18086', ':9999'),
    }] },
    receipts: [{ ...receipt, url: url.replace(':18086', ':9999') }],
  })
  assert.equal(forgedOrigin[0].expected, false)
  const wrongExchange = classifyExpectedConsoleErrors({
    diagnostics: {
      console: [exact],
      requests: [{ ...diagnostics.requests[0], method: 'POST' }],
      responses: [{ ...diagnostics.responses[0], mimeType: 'text/plain' }],
    },
    receipts: [receipt],
  })
  assert.equal(wrongExchange[0].expected, false)
  const wrongRequestHeader = classifyExpectedConsoleErrors({
    diagnostics: {
      ...diagnostics,
      console: [exact],
      requests: [{
        ...diagnostics.requests[0],
        xRequestId: '22222222-2222-4222-8222-222222222222',
      }],
    },
    receipts: [receipt],
  })
  assert.equal(wrongRequestHeader[0].expected, false)
  const duplicateExchange = classifyExpectedConsoleErrors({
    diagnostics: {
      console: [exact],
      requests: [
        diagnostics.requests[0],
        { ...diagnostics.requests[0] },
      ],
      responses: [
        diagnostics.responses[0],
        { ...diagnostics.responses[0] },
      ],
    },
    receipts: [receipt],
  })
  assert.equal(duplicateExchange[0].expected, false)
  assert.throws(
    () => classifyExpectedConsoleErrors({
      diagnostics: { ...diagnostics, console: [exact] },
      receipts: [receipt, { ...receipt }],
    }),
    /duplicate request ID/u,
  )
  assert.throws(
    () => classifyExpectedConsoleErrors({
      diagnostics: { ...diagnostics, console: [exact] },
      receipts: [
        receipt,
        { ...receipt, requestId: 'another-permission-fetch-403' },
      ],
    }),
    /only one permission denial receipt/u,
  )
})

test('permission denial console evidence is captured and checked fail closed', () => {
  const source = readScript('smoke-trading-lab.mjs')

  assert.match(source, /consoleErrorReceipts:\s*\[\]/u)
  assert.match(source, /event\.entry\?\.networkRequestId/u)
  assert.match(source, /classifyExpectedConsoleErrors\(\{/u)
  assert.match(source, /response\.headers\.get\('x-request-id'\)/u)
  assert.match(
    source,
    /'X-Request-Id':\s*permissionRequestId/u,
  )
  assert.match(
    source,
    /consoleErrorReceipts:\s*context\.consoleErrorReceipts/u,
  )
  assert.match(
    source,
    /entry\.expected !== true/u,
  )
})

test('browser smoke waits for a fresh reload document and the React login control', async () => {
  const source = readScript('smoke-trading-lab.mjs')
  const reloadStart = source.indexOf('async reload()')
  const reloadEnd = source.indexOf('expectHttp(', reloadStart)
  const reloadSource = source.slice(reloadStart, reloadEnd)

  assert.ok(reloadStart >= 0)
  assert.ok(reloadEnd > reloadStart)
  assert.match(reloadSource, /const nonce = randomUUID\(\)/)
  assert.match(
    reloadSource,
    /window\.__tradingLabSmokeNavigation = value/,
  )
  assert.match(
    reloadSource,
    /window\.__tradingLabSmokeNavigation !== marker/,
  )
  const markerWriteIndex = reloadSource.indexOf(
    'window.__tradingLabSmokeNavigation = value',
  )
  const reloadCommandIndex = reloadSource.indexOf("send('Page.reload'")
  const markerWaitIndex = reloadSource.indexOf(
    'window.__tradingLabSmokeNavigation !== marker',
  )
  assert.ok(markerWriteIndex < reloadCommandIndex)
  assert.ok(reloadCommandIndex < markerWaitIndex)
  assert.match(
    source,
    /async function fillLabel\(page, labelText, value\) \{\s+await waitForLabelControl\(page, labelText\)/u,
  )

  const smoke = await import('./smoke-trading-lab.mjs')
  assert.equal(typeof smoke.waitForLabelControl, 'function')
  let probes = 0
  await smoke.waitForLabelControl({
    async evaluate() {
      probes += 1
      return probes >= 2
    },
  }, '管理员邮箱', 500)
  assert.equal(probes, 2)
  await assert.rejects(
    smoke.waitForLabelControl({
      async evaluate() {
        return false
      },
    }, '管理员邮箱', 20),
    /Timed out waiting for label 管理员邮箱 form control/u,
  )
})

test('browser login preserves an explicit protected destination', () => {
  const source = readScript('smoke-trading-lab.mjs')
  const loginStart = source.indexOf(
    'async function loginThroughAdminForm(',
  )
  const loginEnd = source.indexOf(
    'async function openTradingLab(',
    loginStart,
  )
  const loginSource = source.slice(loginStart, loginEnd)

  assert.ok(loginStart >= 0 && loginEnd > loginStart)
  assert.match(loginSource, /expectedLandingPath/u)
  assert.match(loginSource, /`\$\{adminUrl\}\$\{expectedLandingPath\}`/u)
  assert.match(
    loginSource,
    /location\.pathname === '\/login'[\s\S]*window\.__tradingLabSmokeNavigation !== marker/u,
  )
  assert.match(loginSource, /isExpectedAdminLanding/u)
  assert.match(
    loginSource,
    /querySelector\('#admin-content #trading-lab-title'\)/u,
  )
  assert.match(
    loginSource,
    /querySelector\('#admin-content \.page-header h2'\)/u,
  )
  assert.doesNotMatch(
    loginSource,
    /querySelector\('#admin-content h1, #admin-content h2'\)/u,
  )
  assert.doesNotMatch(loginSource, /location\.pathname !== '\/login'/u)
  assert.doesNotMatch(loginSource, /`\$\{adminUrl\}\/dashboard`/u)

  const ordinaryStart = source.indexOf('accounts.ordinary,')
  const ordinaryEnd = source.indexOf(')', ordinaryStart)
  assert.ok(ordinaryStart >= 0 && ordinaryEnd > ordinaryStart)
  assert.match(source.slice(ordinaryStart, ordinaryEnd), /'\/dashboard'/u)
})

test('stable login landing rejects a transient route before page identity is ready', async () => {
  const { isExpectedAdminLanding } =
    await import('./smoke-trading-lab.mjs')
  assert.equal(typeof isExpectedAdminLanding, 'function')

  assert.equal(isExpectedAdminLanding({
    pathname: '/trading/lab',
    h1: null,
    h2: '运行控制',
    dashboardReady: false,
  }, '/trading/lab'), false)
  assert.equal(isExpectedAdminLanding({
    pathname: '/trading/lab',
    h1: '交易路径实验室',
    h2: '运行控制',
    dashboardReady: false,
  }, '/trading/lab'), true)
  assert.equal(isExpectedAdminLanding({
    pathname: '/trading/lab',
    h1: '交易路径实验室',
    h2: '运行控制',
    dashboardReady: false,
  }, '/dashboard'), false)
  assert.equal(isExpectedAdminLanding({
    pathname: '/dashboard',
    h1: null,
    h2: '控制台',
    dashboardReady: false,
  }, '/dashboard'), false)
  assert.equal(isExpectedAdminLanding({
    pathname: '/dashboard',
    h1: null,
    h2: '控制台',
    dashboardReady: true,
  }, '/dashboard'), true)
  assert.throws(
    () => isExpectedAdminLanding({
      pathname: '/dashboard',
      h1: null,
      h2: '控制台',
      dashboardReady: true,
    }, '/unknown'),
    /Admin login landing path is invalid/u,
  )
})

function canceledFailure(requestId, url) {
  return {
    requestId,
    url,
    errorText: 'net::ERR_ABORTED',
    canceled: true,
    expected: false,
  }
}

function fetchRequest(requestId, url) {
  return {
    requestId,
    method: 'GET',
    url,
    resourceType: 'Fetch',
  }
}

function fetchResponse(requestId, url, mimeType) {
  return {
    requestId,
    url,
    status: 200,
    mimeType,
    resourceType: 'Fetch',
  }
}

test('browser smoke runs the 390x844 mobile guard before desktop journeys', () => {
  const source = readScript('smoke-trading-lab.mjs')
  const loginIndex = source.indexOf('await loginThroughAdminForm(')
  const mobileIndex = source.indexOf(
    "await runJourney(context, 'mobile guard journey', runMobileGuardJourney)",
  )
  const coreIndex = source.indexOf(
    "context.core = await runJourney(context, 'core UI journey', runCoreJourney)",
  )

  assert.ok(loginIndex >= 0)
  assert.ok(mobileIndex > loginIndex)
  assert.ok(coreIndex > mobileIndex)
  assert.match(source, /const MOBILE_VIEWPORT_WIDTH = 390/)
  assert.match(source, /const MOBILE_VIEWPORT_HEIGHT = 844/)
  assert.match(source, /async function runMobileGuardJourney\(/)
  assert.match(
    source,
    /交易路径实验室首版仅支持宽度不低于 1280px 的桌面端。/,
  )
  assert.match(source, /\.trading-lab-timeline/)
  assert.match(source, /\.trading-lab-chart-section/)
  assert.match(source, /\.trading-lab-run-controls/)
  assert.match(source, /businessRequestsBefore/)
  assert.match(source, /businessRequestsAfter/)
  assert.match(
    source,
    /setViewport\(page, VIEWPORT_WIDTH, VIEWPORT_HEIGHT, false\)/,
  )
})

test('core journey waits for asynchronously loaded validation environment status', async () => {
  const smoke = await import('./smoke-trading-lab.mjs')
  assert.equal(
    typeof smoke.waitForTradingLabEnvironmentReady,
    'function'
  )

  let reads = 0
  const observed = []
  const page = {
    async evaluate(_probe, term) {
      const cycle = Math.floor(reads / 2)
      reads += 1
      observed.push({ cycle, term })
      if (cycle === 0) return '未知'
      if (cycle === 1) {
        return term === 'Relay' ? '运行中' : '未知'
      }
      return term === 'Relay' ? '运行中' : 'UP'
    }
  }

  assert.deepEqual(
    await smoke.waitForTradingLabEnvironmentReady(page, 1_000),
    {
      relay: '运行中',
      validationHealth: 'UP'
    }
  )
  assert.deepEqual(
    observed.slice(0, 4).map(({ term }) => term),
    ['Relay', 'Validation health', 'Relay', 'Validation health']
  )
  assert.ok(reads >= 6, 'environment readiness did not retry delayed state')

  const unavailablePage = {
    async evaluate() {
      return '未知'
    }
  }
  await assert.rejects(
    smoke.waitForTradingLabEnvironmentReady(unavailablePage, 20),
    /Timed out waiting for Trading Lab validation environment readiness/
  )

  const source = readScript('smoke-trading-lab.mjs')
  const coreJourney = source.slice(
    source.indexOf('async function runCoreJourney('),
    source.indexOf('async function runRandomChartJourney(')
  )
  const openIndex = coreJourney.indexOf('await openTradingLab(')
  const readinessIndex = coreJourney.indexOf(
    'await waitForTradingLabEnvironmentReady('
  )
  assert.ok(openIndex >= 0)
  assert.ok(readinessIndex > openIndex)
})

test('browser and optional Admin descendants receive a credential-scrubbed environment', async () => {
  const smoke = await import('./smoke-trading-lab.mjs')
  assert.equal(typeof smoke.buildChildProcessEnvironment, 'function')
  const childEnvironment = smoke.buildChildProcessEnvironment(
    {
      PATH: 'fixture-path',
      SystemRoot: 'fixture-system-root',
      TEMP: 'fixture-temp',
      SUPERVISOR_INTERNAL_TOKEN: 'supervisor-secret',
      VALIDATION_DATABASE_PASSWORD: 'validation-database-secret',
      VALIDATION_REDIS_PASSWORD: 'validation-redis-secret',
      VALIDATION_JWT_SECRET: 'validation-jwt-secret',
      VALIDATION_CONFIG_ENCRYPTION_KEY: 'validation-config-secret',
      VALIDATION_INTERNAL_SECRET: 'validation-internal-secret',
      JWT_SECRET: 'main-jwt-secret',
      CONFIG_ENCRYPTION_KEY: 'main-config-secret',
      ADMIN_BOOTSTRAP_PASSWORD: 'main-bootstrap-secret',
      TRADING_LAB_SMOKE_SUPER_EMAIL: 'super@local.invalid',
      TRADING_LAB_SMOKE_SUPER_PASSWORD: 'super-password',
      TRADING_LAB_SMOKE_VIEW_EMAIL: 'view@local.invalid',
      TRADING_LAB_SMOKE_VIEW_PASSWORD: 'view-password',
      TRADING_LAB_SMOKE_EXECUTE_EMAIL: 'execute@local.invalid',
      TRADING_LAB_SMOKE_EXECUTE_PASSWORD: 'execute-password',
      TRADING_LAB_SMOKE_ORDINARY_EMAIL: 'ordinary@local.invalid',
      TRADING_LAB_SMOKE_ORDINARY_PASSWORD: 'ordinary-password',
      TRADING_LAB_SMOKE_OWNED_AUTH_USER_IDS: 'owned-user-ids',
      FIXTURE_API_KEY: 'generic-api-key',
    },
    {
      VITE_API_BASE_URL: 'http://127.0.0.1:18086',
    },
  )
  assert.deepEqual(childEnvironment, {
    PATH: 'fixture-path',
    SystemRoot: 'fixture-system-root',
    TEMP: 'fixture-temp',
    VITE_API_BASE_URL: 'http://127.0.0.1:18086',
  })

  const source = readScript('smoke-trading-lab.mjs')
  const adminLauncher = source.slice(
    source.indexOf('async function launchOwnedAdmin('),
    source.indexOf('function registerOwnedProcess('),
  )
  const browserLauncher = source.slice(
    source.indexOf('async function launchBrowser('),
    source.indexOf('async function connectBrowser('),
  )
  assert.match(
    adminLauncher,
    /env:\s*buildChildProcessEnvironment\(/u,
  )
  assert.match(
    browserLauncher,
    /env:\s*buildChildProcessEnvironment\(/u,
  )
})

test('Windows Admin launcher invokes npm CLI through Node instead of a cmd shim', async () => {
  const smoke = await import('./smoke-trading-lab.mjs')
  assert.equal(typeof smoke.resolveOwnedAdminNpmLaunch, 'function')

  const launch = smoke.resolveOwnedAdminNpmLaunch(
    ['--prefix', 'C:\\fixture\\apps\\admin', 'run', 'dev'],
    {
      platform: 'win32',
      nodePath: 'C:\\trusted-node\\node.exe',
      npmCliPath: 'C:\\trusted-node\\node_modules\\npm\\bin\\npm-cli.js',
      fileExists: () => true
    }
  )

  assert.deepEqual(launch, {
    executable: 'C:\\trusted-node\\node.exe',
    arguments: [
      'C:\\trusted-node\\node_modules\\npm\\bin\\npm-cli.js',
      '--prefix',
      'C:\\fixture\\apps\\admin',
      'run',
      'dev'
    ]
  })
  assert.notEqual(launch.executable.toLowerCase(), 'npm.cmd')

  const source = readScript('smoke-trading-lab.mjs')
  const adminLauncher = source.slice(
    source.indexOf('async function launchOwnedAdmin('),
    source.indexOf('function registerOwnedProcess(')
  )
  assert.match(adminLauncher, /resolveOwnedAdminNpmLaunch\(args\)/u)
  assert.match(
    adminLauncher,
    /spawn\(launch\.executable,\s*launch\.arguments,/u
  )
})

test('mobile guard request classification excludes assets and unrelated APIs', async () => {
  const smoke = await import('./smoke-trading-lab.mjs')

  assert.equal(typeof smoke.isTradingLabBusinessRequest, 'function')
  for (const url of [
    'http://127.0.0.1:18086/api/admin/trading-lab',
    'http://127.0.0.1:18086/api/admin/trading-lab/environment',
    'http://127.0.0.1:18087/actuator/health',
    'http://localhost:18088/internal/reset',
  ]) {
    assert.equal(smoke.isTradingLabBusinessRequest(url), true, url)
  }
  for (const url of [
    'http://127.0.0.1:5200/trading/lab',
    'http://127.0.0.1:5200/assets/AdminApp.js',
    'http://127.0.0.1:5200/assets/admin.woff2',
    'http://127.0.0.1:18086/api/admin/me',
    'http://example.com:18087/assets/admin.js',
    'not a URL',
  ]) {
    assert.equal(smoke.isTradingLabBusinessRequest(url), false, url)
  }
})

test('controlled failure scenario uses QUOTE orders with a later action', async () => {
  const {
    buildControlledFailureScenario,
    generatedScenario,
    readAuthoritativeTradingLabConfig,
  } =
    await import('./smoke-trading-lab.mjs')
  assert.equal(typeof buildControlledFailureScenario, 'function')
  assert.equal(typeof generatedScenario, 'function')
  assert.equal(typeof readAuthoritativeTradingLabConfig, 'function')
  const config = authoritativeBrowserConfig()
  const evaluated = []
  const authority = await readAuthoritativeTradingLabConfig({
    async evaluate(_probe, apiUrl, path) {
      evaluated.push({ apiUrl, path })
      return {
        status: 200,
        contentType: 'application/json',
        xRequestId: '44444444-4444-4444-8444-444444444444',
        payload: {
          success: true,
          code: 'OK',
          data: config,
        },
      }
    },
  }, 'http://127.0.0.1:18086')
  assert.deepEqual(evaluated, [{
    apiUrl: 'http://127.0.0.1:18086',
    path: '/api/admin/trading-lab/config',
  }])
  assert.deepEqual(authority.configSnapshot, config.configSnapshot)
  assert.equal(authority.configSnapshotHash, config.configSnapshotHash)
  assert.equal(authority.modelVersion, config.modelVersion)
  assert.equal(authority.spot.symbol, 'ALPHAUSDT')
  assert.equal(authority.perp.symbol, 'ALPHAUSDT-PERP')

  const generated = await generatedScenario({
    authority,
    id: '00000000-0000-4000-8000-000000000001',
    name: 'authoritative random scenario',
    seed: 'authoritative-random-seed',
    durationSeconds: 12,
    actionCount: 4,
    negativeMode: false,
  })
  assert.deepEqual(generated.configSnapshot, config.configSnapshot)
  assert.equal(generated.configSnapshotHash, config.configSnapshotHash)
  assert.equal(generated.modelVersion, config.modelVersion)
  assert.deepEqual(generated.symbols, [
    { symbol: 'ALPHAUSDT', productType: 'CRYPTO_SPOT' },
    { symbol: 'ALPHAUSDT-PERP', productType: 'LINEAR_PERP' },
  ])

  const scenario = await buildControlledFailureScenario({
    authority,
    scenarioId: '11111111-1111-4111-8111-111111111111',
    failureActionId: '22222222-2222-4222-8222-222222222222',
    laterActionId: '33333333-3333-4333-8333-333333333333',
  })

  assert.equal(scenario.negativeMode, false)
  assert.deepEqual(scenario.configSnapshot, config.configSnapshot)
  assert.equal(scenario.configSnapshotHash, config.configSnapshotHash)
  assert.equal(scenario.modelVersion, config.modelVersion)
  assert.deepEqual(scenario.initialBalances, { USDT: '0', ALPHA: '0' })
  assert.equal(scenario.timeline.length, 2)
  assert.deepEqual(
    scenario.timeline.map(({ symbol, productType }) => ({
      symbol,
      productType,
    })),
    [
      { symbol: 'ALPHAUSDT', productType: 'CRYPTO_SPOT' },
      { symbol: 'ALPHAUSDT', productType: 'CRYPTO_SPOT' },
    ],
  )
  assert.deepEqual(
    scenario.timeline.map((action) => ({
      id: action.id,
      sequence: action.sequence,
      type: action.type,
      atSecond: action.trigger.atSecond,
      quantityUnit: action.parameters.quantityUnit,
      expectedError: action.expectedError,
    })),
    [
      {
        id: '22222222-2222-4222-8222-222222222222',
        sequence: 1,
        type: 'PLACE_ORDER',
        atSecond: 1,
        quantityUnit: 'QUOTE',
        expectedError: undefined,
      },
      {
        id: '33333333-3333-4333-8333-333333333333',
        sequence: 2,
        type: 'PLACE_ORDER',
        atSecond: 2,
        quantityUnit: 'QUOTE',
        expectedError: undefined,
      },
    ],
  )
})

test('controlled failure evidence derives exact runtime action identities and rejects mutations', async () => {
  const {
    assertControlledFailureEvidence,
    expectedControlledFailureEvidence,
  } = await import('./smoke-trading-lab.mjs')
  assert.equal(typeof assertControlledFailureEvidence, 'function')
  assert.equal(typeof expectedControlledFailureEvidence, 'function')

  const input = {
    runId: 'c0a3eb1e-4fd8-453c-a9f0-d8c514c6f51a',
    validationGeneration: 377,
    failureAction: {
      id: 'c063bca5-7484-4699-b37e-945779a887df',
    },
    unexecutedAction: {
      id: '6bd54e83-eb24-401e-b346-dd4a507c4a72',
    },
  }
  const expected = expectedControlledFailureEvidence(input)
  assert.deepEqual(expected, {
    failurePoint: {
      operation: 'PUBLIC_ACTION',
      actionId: 'f96b0b32-005b-3fc6-b236-5cf9c2f1d4a0',
      tickSequence: 1,
      actionSequence: 1,
      status: 400,
      code: 'INSUFFICIENT_BALANCE',
    },
    unexecuted: [{
      actionId: 'ef60abab-f689-3c43-983d-81515416ae82',
      tickSequence: 2,
      actionSequence: 2,
      type: 'PLACE_ORDER',
    }],
  })
  assert.doesNotThrow(() => {
    assertControlledFailureEvidence({
      ...input,
      executionFailure: {
        payload: structuredClone(expected),
      },
    })
  })

  for (const mutate of [
    (value) => {
      value.failurePoint.status = 409
    },
    (value) => {
      value.failurePoint.actionId = input.failureAction.id
    },
    (value) => {
      value.unexecuted[0].actionId = expected.failurePoint.actionId
    },
  ]) {
    const actual = structuredClone(expected)
    mutate(actual)
    assert.throws(
      () => assertControlledFailureEvidence({
        ...input,
        executionFailure: {
          payload: actual,
        },
      }),
      assert.AssertionError,
    )
  }
})

test('negative report evidence is the exact expected-error command trace', async () => {
  const { expectedNegativeTraceEvidence } =
    await import('./smoke-trading-lab.mjs')
  assert.equal(typeof expectedNegativeTraceEvidence, 'function')
  const scenario = {
    negativeMode: true,
    timeline: [{
      id: 'generated-action-0001',
      sequence: 1,
      expectedError: {
        status: 400,
        code: 'INSUFFICIENT_BALANCE',
      },
    }],
  }
  const report = {
    apiTrace: [{
      exception: null,
      requestBody: {
        environment: 'validation',
        method: 'POST',
        sequence: 1,
        sanitizedRequest: {
          scope: 'COMMAND',
          operation: 'PUBLIC_ACTION',
          outcome: 'EXPECTED_ERROR',
        },
      },
      responseBody: {
        recordedException: null,
        status: 400,
        sanitizedResponse: {
          success: false,
          code: 'INSUFFICIENT_BALANCE',
          message: 'Available balance is not enough',
          data: null,
          timestamp: '2026-07-28T00:26:30.383Z',
        },
      },
      url: 'http://127.0.0.1:8080/api/trading/orders',
    }],
    actualState: { terminalState: 'COMPLETED' },
    errors: [],
    cleanup: { status: 'SUCCEEDED' },
  }

  assert.deepEqual(
    expectedNegativeTraceEvidence(report, scenario),
    [{
      actionId: 'generated-action-0001',
      sequence: 1,
      status: 400,
      code: 'INSUFFICIENT_BALANCE',
      path: '/api/trading/orders',
    }],
  )
  assert.throws(
    () => expectedNegativeTraceEvidence(
      {
        ...report,
        apiTrace: [{
          ...report.apiTrace[0],
          responseBody: {
            ...report.apiTrace[0].responseBody,
            status: 409,
          },
        }],
      },
      scenario,
    ),
    /exact expected-error command trace/,
  )
  assert.throws(
    () => expectedNegativeTraceEvidence(
      { ...report, productPass: true },
      scenario,
    ),
    /product Pass\/Fail/,
  )
})

test('authoritative browser config fails closed without exact fresh authority', async () => {
  const { readAuthoritativeTradingLabConfig } =
    await import('./smoke-trading-lab.mjs')
  const valid = authoritativeBrowserConfig()
  const result = (overrides = {}) => ({
    status: 200,
    contentType: 'application/json',
    xRequestId: '44444444-4444-4444-8444-444444444444',
    payload: {
      success: true,
      code: 'OK',
      data: valid,
    },
    ...overrides,
  })
  const page = (value) => ({
    async evaluate() {
      return value
    },
  })

  for (const invalid of [
    result({ status: 403 }),
    result({ contentType: 'text/html' }),
    result({ xRequestId: 'not-a-uuid' }),
    result({
      payload: {
        success: true,
        code: 'OK',
        data: {
          ...valid,
          configSnapshotHash: 'f'.repeat(64),
        },
      },
    }),
    result({
      payload: {
        success: true,
        code: 'OK',
        data: {
          ...valid,
          modelVersion: 'metadata-drift',
        },
      },
    }),
    result({
      payload: {
        success: true,
        code: 'OK',
        data: {
          ...valid,
          configSnapshot: {
            ...valid.configSnapshot,
            instruments: valid.configSnapshot.instruments.filter(
              ({ productType }) => productType !== 'LINEAR_PERP',
            ),
          },
        },
      },
    }),
  ]) {
    await assert.rejects(
      readAuthoritativeTradingLabConfig(
        page(invalid),
        'http://127.0.0.1:18086',
      ),
      /authoritative|config|HTTP|JSON|request|hash|version|Spot|Perp/iu,
    )
  }

  const source = readScript('smoke-trading-lab.mjs')
  assert.equal(
    source.match(
      /await readAuthoritativeTradingLabConfig\(\s*page,\s*options\.apiUrl,\s*\)/gu,
    )?.length,
    5,
    'every scenario-building journey must fetch fresh authority',
  )
  assert.doesNotMatch(source, /phase4-browser-symbols-v1/u)
  assert.doesNotMatch(source, /phase4-browser-smoke-v1/u)
  assert.doesNotMatch(source, /XBT-USDT-LAB/u)
  assert.doesNotMatch(source, /ETH-USDT-LAB/u)
})

test('canonical ownership covers every created run, referenced large run, and observed API response', async () => {
  const smoke = await import('./smoke-trading-lab.mjs')
  assert.equal(typeof smoke.buildCanonicalOwnership, 'function')

  const createdRuns = [
    createdRun('1', '2', '3', 'COMPLETED'),
    createdRun('4', '5', '6', 'CANCELLED'),
    createdRun('7', '8', '9', 'FAILED'),
  ]
  const referencedRunId = uuid('a')
  const firstRequestId = uuid('b')
  const secondRequestId = uuid('c')
  const loginRequestId = uuid('d')
  const directValidationRequestId = uuid('e')
  const nodeProbeRequestId = uuid('f')
  const dashboardRequestId = '10101010-1010-4010-8010-101010101010'
  const ownership = smoke.buildCanonicalOwnership({
    createdRuns,
    referencedRunIds: [referencedRunId, referencedRunId],
    requestLogObservations: [
      requestLogObservation(
        nodeProbeRequestId,
        'GET',
        '/api/admin/trading-lab/environment',
        403,
      ),
    ],
    responses: [
      {
        url: 'http://127.0.0.1:18086/api/admin/trading-lab/environment',
        xRequestId: firstRequestId,
        requestTupleSha256: canonicalHash({
          method: 'GET',
          path: '/api/admin/trading-lab/environment',
          statusCode: 200,
        }),
      },
      {
        url: 'http://127.0.0.1:18086/api/admin/trading-lab/reports/ignored',
        xRequestId: secondRequestId,
        requestTupleSha256: canonicalHash({
          method: 'GET',
          path: '/api/admin/trading-lab/reports/ignored',
          statusCode: 200,
        }),
      },
      {
        url: 'http://127.0.0.1:5200/assets/admin.js',
        xRequestId: 'not-a-uuid',
      },
      {
        url: 'http://127.0.0.1:5200/api/auth/login',
        xRequestId: loginRequestId,
        requestTupleSha256: canonicalHash({
          method: 'POST',
          path: '/api/auth/login',
          statusCode: 200,
        }),
      },
      {
        url: 'http://127.0.0.1:18086/api/admin/dashboard/summary',
        xRequestId: dashboardRequestId,
        requestTupleSha256: canonicalHash({
          method: 'OPTIONS',
          path: '/api/admin/dashboard/summary',
          statusCode: 200,
        }),
      },
      {
        url: 'http://127.0.0.1:18086/api/admin/dashboard/users',
        xRequestId: 'not-a-uuid',
      },
      {
        url: 'http://127.0.0.1:18087/actuator/health',
        xRequestId: directValidationRequestId,
      },
    ],
  })

  assert.deepEqual(ownership, {
    createdRuns,
    referencedRunIds: [referencedRunId],
    tradingLabRequestIds: [
      firstRequestId,
      secondRequestId,
      directValidationRequestId,
    ],
    requestLogObservations: [
      requestLogObservation(
        nodeProbeRequestId,
        'GET',
        '/api/admin/trading-lab/environment',
        403,
      ),
      requestLogObservation(
        firstRequestId,
        'GET',
        '/api/admin/trading-lab/environment',
      ),
      requestLogObservation(
        secondRequestId,
        'GET',
        '/api/admin/trading-lab/reports/ignored',
      ),
      requestLogObservation(
        loginRequestId,
        'POST',
        '/api/auth/login',
      ),
      requestLogObservation(
        dashboardRequestId,
        'OPTIONS',
        '/api/admin/dashboard/summary',
      ),
    ],
  })
})

test('provisional created-run ownership is captured early and finalized in place', async () => {
  const smoke = await import('./smoke-trading-lab.mjs')
  assert.equal(typeof smoke.upsertCreatedRunOwnership, 'function')
  const provisional = {
    scenarioId: uuid('1'),
    runId: uuid('2'),
    reportId: uuid('3'),
    provisional: true,
  }
  const terminal = {
    scenarioId: provisional.scenarioId,
    runId: provisional.runId,
    reportId: provisional.reportId,
    expectedTerminalState: 'COMPLETED',
  }

  const started = smoke.upsertCreatedRunOwnership([], provisional)
  assert.deepEqual(started, [provisional])
  assert.deepEqual(
    smoke.buildCanonicalOwnership({
      createdRuns: started,
      referencedRunIds: [],
      requestLogObservations: [],
      responses: [],
    }).createdRuns,
    [provisional],
  )
  assert.throws(
    () => smoke.requireTerminalCreatedRunOwnership(
      { createdRuns: started },
      'PASS',
    ),
    /PASS.*terminal|terminal.*PASS/iu,
  )
  assert.doesNotThrow(
    () => smoke.requireTerminalCreatedRunOwnership(
      { createdRuns: started },
      'FAIL',
    ),
  )
  const finalized = smoke.upsertCreatedRunOwnership(started, terminal)
  assert.deepEqual(
    smoke.requireTerminalCreatedRunOwnership(
      { createdRuns: finalized },
      'PASS',
    ).createdRuns[0],
    terminal,
  )
  assert.deepEqual(finalized, [terminal])
  assert.throws(
    () => smoke.upsertCreatedRunOwnership(finalized, {
      ...terminal,
      reportId: uuid('4'),
    }),
    /identity|ownership|report/iu,
  )
})

test('ExtraInfo-only responses retain exact request-log ownership without duplicates', async () => {
  const smoke = await import('./smoke-trading-lab.mjs')
  assert.equal(typeof smoke.createCdpResponseRecorder, 'function')

  const dashboardRequestId = uuid('1')
  const environmentRequestId = uuid('2')
  const dashboardUrl =
    'http://127.0.0.1:18086/api/admin/dashboard/summary'
  const environmentUrl =
    'http://127.0.0.1:18086/api/admin/trading-lab/environment'
  const requestRows = new Map([
    ['cdp-dashboard', {
      method: 'GET',
      url: dashboardUrl,
      resourceType: 'Fetch',
    }],
    ['cdp-environment', {
      method: 'GET',
      url: environmentUrl,
      resourceType: 'Fetch',
    }],
  ])
  const expectation = {
    pathname: '/api/admin/dashboard/summary',
    statuses: new Set([200]),
    expiresAt: Date.now() + 10_000,
    observed: 0,
  }
  const diagnostics = {
    responses: [],
    businessResponseOverflow: false,
    ownershipResponseOverflow: false,
  }
  const recorder = smoke.createCdpResponseRecorder({
    requestRows,
    responseExpectations: [expectation],
    diagnostics,
    now: () => '2026-07-26T15:17:05.000Z',
  })

  const dashboard = recorder.onResponseReceivedExtraInfo({
    requestId: 'cdp-dashboard',
    statusCode: 200,
    headers: { 'X-Request-ID': dashboardRequestId },
  })
  recorder.onResponseReceivedExtraInfo({
    requestId: 'cdp-environment',
    statusCode: 200,
    headers: { 'x-request-id': environmentRequestId },
  })
  const enriched = recorder.onResponseReceived({
    requestId: 'cdp-dashboard',
    type: 'Fetch',
    response: {
      url: dashboardUrl,
      status: 200,
      mimeType: 'application/json',
      headers: { 'x-request-id': dashboardRequestId },
    },
  })

  assert.equal(enriched, dashboard)
  assert.equal(recorder.responseRows.size, 2)
  assert.equal(diagnostics.responses.length, 2)
  assert.equal(dashboard.xRequestId, dashboardRequestId)
  assert.equal(dashboard.status, 200)
  assert.equal(dashboard.mimeType, 'application/json')
  assert.deepEqual(
    {
      requestId: dashboard.xRequestId,
      requestTupleSha256: dashboard.requestTupleSha256,
    },
    requestLogObservation(
      dashboardRequestId,
      'GET',
      '/api/admin/dashboard/summary',
      200,
    ),
  )
  assert.equal(dashboard.expected, true)
  assert.equal(expectation.observed, 1)

  const ownership = smoke.buildCanonicalOwnership({
    createdRuns: [],
    referencedRunIds: [],
    requestLogObservations: [],
    responses: diagnostics.responses,
  })
  assert.deepEqual(ownership.tradingLabRequestIds, [environmentRequestId])
  assert.deepEqual(
    ownership.requestLogObservations.map(({ requestId }) => requestId),
    [dashboardRequestId, environmentRequestId],
  )
})

test('an expected HTTP receipt is consumed by exactly one response', async () => {
  const smoke = await import('./smoke-trading-lab.mjs')
  const url =
    'http://127.0.0.1:18086/api/admin/trading-lab/environment'
  const requestRows = new Map([
    ['first', { method: 'GET', url, resourceType: 'Fetch' }],
    ['second', { method: 'GET', url, resourceType: 'Fetch' }],
  ])
  const expectation = {
    pathname: '/api/admin/trading-lab/environment',
    statuses: new Set([403]),
    expiresAt: Date.now() + 10_000,
    observed: 0,
  }
  const diagnostics = {
    responses: [],
    businessResponseOverflow: false,
    ownershipResponseOverflow: false,
  }
  const recorder = smoke.createCdpResponseRecorder({
    requestRows,
    responseExpectations: [expectation],
    diagnostics,
  })

  const first = recorder.onResponseReceived({
    requestId: 'first',
    type: 'Fetch',
    response: {
      url,
      status: 403,
      mimeType: 'application/json',
      headers: { 'x-request-id': uuid('1') },
    },
  })
  const second = recorder.onResponseReceived({
    requestId: 'second',
    type: 'Fetch',
    response: {
      url,
      status: 403,
      mimeType: 'application/json',
      headers: { 'x-request-id': uuid('2') },
    },
  })

  assert.equal(first.expected, true)
  assert.equal(second.expected, false)
  assert.equal(expectation.observed, 1)
})

test('response-less main requests retain optional exact request ownership from their canonical header', async () => {
  const smoke = await import('./smoke-trading-lab.mjs')
  assert.equal(typeof smoke.createCdpRequestRecorder, 'function')
  assert.equal(
    typeof smoke.buildResponseLessRequestLogObservations,
    'function',
  )

  const requestRows = new Map()
  const diagnostics = {
    requests: [],
    ownershipRequestOverflow: false,
  }
  const recorder = smoke.createCdpRequestRecorder({
    requestRows,
    diagnostics,
    now: () => '2026-07-26T16:22:01.000Z',
  })
  const persistedRequestId = uuid('3')
  const cancelledRequestId = uuid('4')
  const respondedRequestId = uuid('5')
  const dashboardUrl =
    'http://127.0.0.1:18086/api/admin/dashboard/summary'
  const environmentUrl =
    'http://127.0.0.1:18086/api/admin/trading-lab/environment'

  recorder.onRequestWillBeSent({
    requestId: 'cdp-persisted',
    type: 'Fetch',
    request: {
      method: 'GET',
      url: dashboardUrl,
      headers: { 'X-Request-ID': persistedRequestId },
    },
  })
  recorder.onRequestWillBeSent({
    requestId: 'cdp-cancelled',
    type: 'Fetch',
    request: {
      method: 'GET',
      url: environmentUrl,
      headers: { 'x-request-id': cancelledRequestId },
    },
  })
  recorder.onRequestWillBeSent({
    requestId: 'cdp-responded',
    type: 'Fetch',
    request: {
      method: 'GET',
      url: environmentUrl,
      headers: { 'X-Request-Id': respondedRequestId },
    },
  })

  const observations = smoke.buildResponseLessRequestLogObservations({
    requests: diagnostics.requests,
    responses: [{ requestId: 'cdp-responded' }],
  })
  assert.deepEqual(observations, [
    requestLogRequestObservation(
      persistedRequestId,
      'GET',
      '/api/admin/dashboard/summary',
    ),
    requestLogRequestObservation(
      cancelledRequestId,
      'GET',
      '/api/admin/trading-lab/environment',
    ),
  ])
  assert.equal(
    requestRows.get('cdp-persisted')?.xRequestId,
    persistedRequestId,
  )

  const ownership = smoke.buildCanonicalOwnership({
    createdRuns: [],
    referencedRunIds: [],
    requestLogObservations: observations,
    responses: [],
  })
  assert.deepEqual(ownership.requestLogObservations, observations)
})

test('backend identity probe owns its pre-browser API request by echoed canonical ID', async () => {
  const smoke = await import('./smoke-trading-lab.mjs')
  assert.equal(typeof smoke.assertBackendIdentity, 'function')
  const requestId = uuid('f')
  const calls = []
  const identity = await smoke.assertBackendIdentity(
    'http://127.0.0.1:18086',
    {
      randomUuid: () => requestId,
      fetchImpl: async (url, options = {}) => {
        calls.push({ url, options })
        if (url.endsWith('/actuator/health')) {
          return new Response(JSON.stringify({ status: 'UP' }), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          })
        }
        return new Response(JSON.stringify({ success: false }), {
          status: 403,
          headers: { 'X-Request-Id': requestId },
        })
      },
    },
  )

  assert.equal(
    new Headers(calls[1].options.headers).get('X-Request-Id'),
    requestId,
  )
  assert.deepEqual(
    identity.requestLogObservation,
    requestLogObservation(
      requestId,
      'GET',
      '/api/admin/trading-lab/environment',
      403,
    ),
  )

  await assert.rejects(
    smoke.assertBackendIdentity('http://127.0.0.1:18086', {
      randomUuid: () => requestId,
      fetchImpl: async (url) => (
        url.endsWith('/actuator/health')
          ? new Response(JSON.stringify({ status: 'UP' }), { status: 200 })
          : new Response('', {
              status: 403,
              headers: {
                'X-Request-Id':
                  'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee',
              },
            })
      ),
    }),
    /request correlation drifted/iu,
  )
})

test('request-log observations require exact inputs and retain no raw path or resource ID', async () => {
  const { buildRequestLogObservation } =
    await import('./smoke-trading-lab.mjs')
  const requestId = uuid('a')
  const resourceId = uuid('b')
  const path = `/api/admin/trading-lab/runs/${resourceId}/pause`
  const observation = buildRequestLogObservation({
    requestId,
    method: 'POST',
    path,
    statusCode: 204,
  })

  assert.deepEqual(
    observation,
    requestLogObservation(requestId, 'POST', path, 204),
  )
  assert.equal(JSON.stringify(observation).includes(path), false)
  assert.equal(JSON.stringify(observation).includes(resourceId), false)
  assert.throws(
    () => buildRequestLogObservation({
      requestId,
      path,
      statusCode: 204,
    }),
    /method|keys|observation/iu,
  )
  assert.throws(
    () => buildRequestLogObservation({
      requestId,
      method: 'POST',
      path,
      statusCode: 204,
      rawPath: path,
    }),
    /keys|observation/iu,
  )
})

test('canonical ownership fails closed on missing reports, malformed response IDs, and duplicate UUIDs', async () => {
  const { buildCanonicalOwnership } =
    await import('./smoke-trading-lab.mjs')
  const valid = createdRun('1', '2', '3', 'COMPLETED')

  for (const input of [
    {
      createdRuns: [{ ...valid, reportId: null }],
      referencedRunIds: [],
      requestLogObservations: [],
      responses: [],
    },
    {
      createdRuns: [valid],
      referencedRunIds: [],
      requestLogObservations: [],
      responses: [{
        url: 'http://127.0.0.1:18086/api/admin/trading-lab/environment',
        xRequestId: 'NOT-CANONICAL',
      }],
    },
    {
      createdRuns: [valid],
      referencedRunIds: [],
      requestLogObservations: [],
      responses: [{
        url: 'http://127.0.0.1:5200/api/auth/login',
        xRequestId: 'NOT-CANONICAL',
      }],
    },
    {
      createdRuns: [valid],
      referencedRunIds: [valid.runId],
      requestLogObservations: [],
      responses: [],
    },
    {
      createdRuns: [
        valid,
        createdRun('4', '2', '6', 'FAILED'),
      ],
      referencedRunIds: [],
      requestLogObservations: [],
      responses: [],
    },
    {
      createdRuns: [valid],
      referencedRunIds: [],
      requestLogObservations: [],
      responses: [],
      ignoredToken: 'must-be-rejected',
    },
    {
      createdRuns: [valid],
      referencedRunIds: [],
      requestLogObservations: [
        requestLogObservation(uuid('d'), 'POST', '/api/auth/login'),
        requestLogObservation(uuid('d'), 'POST', '/api/auth/login'),
      ],
      responses: [],
    },
  ]) {
    assert.throws(
      () => buildCanonicalOwnership(input),
      /canonical ownership|reportId|request ID|duplicate UUID/iu,
    )
  }
})

test('browser smoke discovers endpoint identity and only tears down owned processes', () => {
  const source = readScript('smoke-trading-lab.mjs')

  assert.match(source, /assertBackendIdentity/)
  assert.match(source, /assertAdminIdentity/)
  assert.match(source, /waitForBackendHealth/)
  assert.match(source, /waitForAdminReady/)
  assert.match(source, /ownedProcesses/)
  assert.match(source, /pid: child\.pid/)
  assert.match(source, /commandLine/)
  assert.match(source, /finally\s*\{/)
  assert.match(source, /stopOwnedProcesses/)
  assert.match(source, /Refusing to stop an unowned process/)
  assert.doesNotMatch(source, /taskkill\.exe.*\/IM/)
})

test('browser smoke declares every required Trading Lab UI journey and gate', () => {
  const source = readScript('smoke-trading-lab.mjs')

  for (const journey of [
    'runCoreJourney',
    'runRandomChartJourney',
    'runPermissionJourney',
    'runNegativeJourney',
    'runFailureCancelJourney',
    'runPrintJourney',
  ]) {
    assert.match(source, new RegExp(`async function ${journey}\\(`))
  }

  for (const evidence of [
    'TRADING_LAB_VIEW',
    'TRADING_LAB_EXECUTE',
    'SUPER_ADMIN',
    'Spot',
    'Perp',
    'SSE',
    'pause',
    'resume',
    'cancel',
    'TICK',
    'KLINE',
    'BID',
    'ASK',
    'LAST',
    'MARK',
    'INDEX',
    'LOCAL',
    'ACTUAL',
    'negative',
    'partial report',
    'RUN_EXECUTION_FAILED',
    'failurePoint',
    'INSUFFICIENT_BALANCE',
    '50 MiB',
    'second confirmation',
  ]) {
    assert.match(source, new RegExp(evidence, 'i'))
  }
  assert.match(source, /summary\.json/)
  assert.match(source, /network-trace\.json/)
  assert.match(source, /console\.json/)
  assert.match(source, /screenshots/)
  assert.match(source, /downloads/)
  assert.match(source, /createdRuns/)
  assert.match(source, /referencedRunIds/)
  assert.match(source, /xRequestId/)
})

test('random chart waits for durable ACTUAL markers only after completion', () => {
  const source = readScript('smoke-trading-lab.mjs')
  const journey = source.slice(
    source.indexOf('async function runRandomChartJourney('),
    source.indexOf('async function runPermissionJourney('),
  )
  const saveAndRun = journey.indexOf(
    "await clickRole(page, 'button', '保存并运行')",
  )
  const completed = journey.indexOf(
    "'Deterministic random scenario must complete before ACTUAL inspection'",
  )
  const actual = journey.indexOf(
    "await clickRole(page, 'button', 'ACTUAL')",
  )
  const marker = journey.indexOf(
    "await waitForTestId(page, 'trading-lab-chart-event-marker')",
  )

  assert.ok(saveAndRun >= 0)
  assert.ok(completed > saveAndRun)
  assert.ok(actual > completed)
  assert.ok(marker > actual)
})

test('random chart searches both instruments for durable ACTUAL markers', () => {
  const source = readScript('smoke-trading-lab.mjs')
  const journey = source.slice(
    source.indexOf('async function runRandomChartJourney('),
    source.indexOf('async function runPermissionJourney('),
  )

  assert.match(
    journey,
    /const chartInstrumentLabels = \[\s*`Spot \$\{authority\.spot\.symbol\}`,\s*`Perp \$\{authority\.perp\.symbol\}`,\s*\]/u,
  )
  assert.match(
    journey,
    /selectActualMarkerInstrument\(page, chartInstrumentLabels\)/u,
  )
  assert.match(
    source,
    /async function selectActualMarkerInstrument\([\s\S]*aria-pressed[\s\S]*trading-lab-chart-event-marker/u,
  )
})

test('package exposes the focused Trading Lab browser smoke alias', () => {
  const packageJson = JSON.parse(readFileSync(
    new URL('../package.json', import.meta.url),
    'utf8',
  ))
  assert.equal(
    packageJson.scripts['smoke:trading-lab'],
    'node scripts/smoke-trading-lab.mjs',
  )
})

function readJson(name) {
  return JSON.parse(readText(name))
}

function createdRun(scenario, run, report, expectedTerminalState) {
  return {
    scenarioId: uuid(scenario),
    runId: uuid(run),
    reportId: uuid(report),
    expectedTerminalState,
  }
}

function uuid(value) {
  return `${value.repeat(8)}-${value.repeat(4)}-4${value.repeat(3)}-8${value.repeat(3)}-${value.repeat(12)}`
}

function requestLogObservation(
  requestId,
  method,
  path,
  statusCode = 200,
) {
  return {
    requestId,
    requestTupleSha256: canonicalHash({ method, path, statusCode }),
  }
}

function requestLogRequestObservation(requestId, method, path) {
  return {
    requestId,
    requestMethodSha256: createHash('sha256').update(method).digest('hex'),
    requestPathSha256: createHash('sha256').update(path).digest('hex'),
  }
}

function readScript(name) {
  return readFileSync(new URL(name, import.meta.url), 'utf8')
}

function readText(name) {
  return readFileSync(
    new URL(`../docs/testing/trading-lab/${name}`, import.meta.url),
    'utf8'
  )
}

function assertExactKeys(value, expected) {
  assertPlainObject(value)
  assert.deepEqual(Object.keys(value).sort(), [...expected].sort())
}

function assertPlainObject(value) {
  assert.ok(value !== null && typeof value === 'object')
  assert.equal(Array.isArray(value), false)
  assert.equal(Object.getPrototypeOf(value), Object.prototype)
}

function assertCapabilities(value) {
  assert.ok(Array.isArray(value) && value.length > 0)
  assert.deepEqual(value, [...value].sort())
  assert.equal(new Set(value).size, value.length)
  for (const capability of value) {
    assert.equal(typeof capability, 'string')
    assert.match(capability, CAPABILITY_PATTERN)
  }
}

function assertInitialBalances(value) {
  assertPlainObject(value)
  assert.ok(Object.keys(value).length > 0)
  assert.equal(Object.hasOwn(value, 'USDT'), true)
  for (const [asset, balance] of Object.entries(value)) {
    assert.match(asset, /^[A-Z][A-Z0-9]{1,15}$/)
    assert.equal(typeof balance, 'string')
    assert.match(balance, DECIMAL_PATTERN)
    assert.ok(Number(balance) >= 0)
  }
  assert.ok(Number(value.USDT) > 0)
}

function assertSymbols(value) {
  assert.ok(Array.isArray(value) && value.length > 0)
  const identities = new Set()
  for (const symbol of value) {
    assertExactKeys(symbol, ['productType', 'symbol'])
    assert.match(symbol.symbol, /^[A-Z0-9]+(?:-[A-Z0-9]+)*$/)
    assert.ok(
      symbol.productType === 'CRYPTO_SPOT'
      || symbol.productType === 'LINEAR_PERP'
    )
    const identity = `${symbol.productType}\0${symbol.symbol}`
    assert.equal(identities.has(identity), false)
    identities.add(identity)
  }
  return new Map(
    value.map((symbol) => [
      symbol.symbol,
      symbol.productType
    ])
  )
}

function assertDefaults(value) {
  assertExactKeys(value, ['leverage', 'marginMode', 'positionMode'])
  assert.ok(value.positionMode === 'ONE_WAY' || value.positionMode === 'HEDGE')
  assert.ok(value.marginMode === 'CROSS' || value.marginMode === 'ISOLATED')
  assert.ok(Number.isSafeInteger(value.leverage))
  assert.ok(value.leverage >= 1 && value.leverage <= 125)
}

function assertTimeline(value, symbols) {
  assert.ok(Array.isArray(value) && value.length > 0)
  const ids = new Set()
  let previousSecond = -1
  for (const [index, action] of value.entries()) {
    const allowedKeys = [
      'expectedError',
      'id',
      'parameters',
      'productType',
      'sequence',
      'symbol',
      'trigger',
      'type'
    ]
    assertPlainObject(action)
    assert.ok(
      Object.keys(action).every((key) => allowedKeys.includes(key))
    )
    for (const required of allowedKeys.filter((key) => key !== 'expectedError')) {
      assert.equal(Object.hasOwn(action, required), true)
    }
    assert.match(action.id, /^[a-z0-9]+(?:-[a-z0-9]+)*$/)
    assert.equal(ids.has(action.id), false)
    ids.add(action.id)
    assert.equal(action.sequence, index + 1)
    assert.equal(TIMELINE_TYPES.has(action.type), true)
    assert.equal(symbols.get(action.symbol), action.productType)
    assertExactKeys(action.trigger, ['atSecond', 'type'])
    assert.equal(action.trigger.type, 'VIRTUAL_TIME')
    assert.ok(Number.isSafeInteger(action.trigger.atSecond))
    assert.ok(action.trigger.atSecond >= previousSecond)
    previousSecond = action.trigger.atSecond
    assertPlainObject(action.parameters)
    if (action.type === 'PLACE_ORDER') {
      assert.equal(
        ORDER_TYPES.has(action.parameters.orderType),
        true,
        `${action.id} has an unsupported orderType`
      )
      if (Object.hasOwn(action.parameters, 'timeInForce')) {
        assert.equal(
          TIME_IN_FORCE_VALUES.has(action.parameters.timeInForce),
          true,
          `${action.id} has an unsupported timeInForce`
        )
      }
      if (Object.hasOwn(action.parameters, 'postOnly')) {
        assert.equal(typeof action.parameters.postOnly, 'boolean')
      }
    }
    if (Object.hasOwn(action, 'expectedError')) {
      assertExactKeys(action.expectedError, ['code', 'status'])
      assert.ok(Number.isSafeInteger(action.expectedError.status))
      assert.ok(action.expectedError.status >= 400)
      assert.match(action.expectedError.code, CAPABILITY_PATTERN)
    }
  }
}

function randomInput(entry) {
  return {
    baseScenario: randomBaseScenario(),
    seed: entry.seed,
    actionCount: 6,
    durationSeconds: 12,
    realistic: true,
    negativeMode: entry.negativeMode,
    priceRange: { min: '100', max: '200' },
    leverageRange: { min: 2, max: 10 },
    fundingRateRange: { min: '-0.001', max: '0.001' },
    feeRateRange: { min: '0.0001', max: '0.001' },
    offsetRangeSteps: { min: 0, max: 2 },
    volatilitySteps: { min: 1, max: 3 }
  }
}

function randomBaseScenario() {
  const executionPolicy = {
    matchingMode: 'SIMPLE',
    makerFeeRate: '0.0002',
    takerFeeRate: '0.0005',
    liquidationFeeRate: '0.002',
    slippageRate: '0',
    maxFillQuantityPerTick: '100'
  }
  return {
    id: '50000000-0000-4000-8000-000000000001',
    name: 'Phase 4 deterministic seed base',
    description: '',
    negativeMode: false,
    seed: 'phase4-base',
    modelVersion: MODEL_VERSION,
    configSnapshot: {
      modelVersion: MODEL_VERSION,
      symbolConfigVersion: 'phase4-symbols-v1',
      codeVersion: 'phase4-fixture-v1',
      executionPolicy: { ...executionPolicy },
      instruments: [
        randomInstrument('XBT-USDT-LAB', 'CRYPTO_SPOT'),
        randomInstrument('ETH-USDT-LAB', 'LINEAR_PERP')
      ]
    },
    configSnapshotHash: 'd'.repeat(64),
    executionPolicy,
    marketPath: {
      virtualStart: '2026-07-25T00:00:00.000Z',
      realistic: false,
      instruments: [
        randomPath('XBT-USDT-LAB', 'CRYPTO_SPOT'),
        randomPath('ETH-USDT-LAB', 'LINEAR_PERP')
      ]
    },
    initialBalances: {
      USDT: '10000',
      XBT: '10'
    },
    defaults: {
      positionMode: 'ONE_WAY',
      marginMode: 'CROSS',
      leverage: 5
    },
    symbols: [
      { symbol: 'XBT-USDT-LAB', productType: 'CRYPTO_SPOT' },
      { symbol: 'ETH-USDT-LAB', productType: 'LINEAR_PERP' }
    ],
    timeline: []
  }
}

function randomInstrument(symbol, productType) {
  const spot = productType === 'CRYPTO_SPOT'
  return {
    symbol,
    productType,
    baseAsset: spot ? 'XBT' : 'ETH',
    quoteAsset: 'USDT',
    tickSize: spot ? '0.01' : '0.1',
    stepSize: spot ? '0.1' : '0.01',
    pricePrecision: spot ? 2 : 1,
    quantityPrecision: spot ? 1 : 2,
    minQty: spot ? '0.1' : '0.01',
    maxQty: '100',
    minNotional: '1',
    maxNotional: '1000000',
    initialMarginRate: spot ? '1' : '0.1',
    maintenanceMarginRate: spot ? '0' : '0.05',
    liquidationFeeRate: '0.002',
    fixedFundingRate: spot ? '0' : '0.0001',
    fixedFundingIntervalMinutes: 480,
    markPriceSource: spot ? 'quote_mid' : 'provider_mark',
    contractSize: '1',
    maxLeverage: spot ? 1 : 20,
    defaultLeverage: spot ? 1 : 5,
    marginAsset: 'USDT',
    settlementAsset: 'USDT',
    riskTier: 'TIER_1'
  }
}

function randomPath(symbol, productType) {
  return {
    mode: 'SIMPLE',
    productType,
    symbol,
    seed: `phase4-path-${symbol}`,
    last: {
      start: '100',
      segments: [{
        target: '102',
        durationSeconds: 12,
        offsetRangeSteps: 0,
        volatilitySteps: 0,
        maxStepPerSecond: 100
      }]
    },
    spreadSteps: 2,
    indexOffsetSteps: 0,
    basisSteps: productType === 'LINEAR_PERP' ? 1 : 0,
    ...(productType === 'LINEAR_PERP'
      ? { fundingRate: '0.0001' }
      : {})
  }
}

function authoritativeBrowserConfig() {
  const executionPolicy = {
    matchingMode: 'SIMPLE',
    makerFeeRate: '0.0002',
    takerFeeRate: '0.0005',
    liquidationFeeRate: '0.002',
    slippageRate: '0',
    maxFillQuantityPerTick: '100',
  }
  const spot = {
    ...randomInstrument('ALPHAUSDT', 'CRYPTO_SPOT'),
    baseAsset: 'ALPHA',
  }
  const perp = {
    ...randomInstrument('ALPHAUSDT-PERP', 'LINEAR_PERP'),
    baseAsset: 'ALPHA',
  }
  const configSnapshot = {
    modelVersion: 'authoritative-model-v9',
    symbolConfigVersion: 'authoritative-symbols-v9',
    codeVersion: 'authoritative-code-v9',
    executionPolicy,
    instruments: [spot, perp],
  }
  return {
    configSnapshot,
    configSnapshotHash: canonicalHash(configSnapshot),
    modelVersion: configSnapshot.modelVersion,
    symbolConfigVersion: configSnapshot.symbolConfigVersion,
    codeVersion: configSnapshot.codeVersion,
  }
}

function canonicalHash(value) {
  return createHash('sha256')
    .update(canonicalJson(value))
    .digest('hex')
}
