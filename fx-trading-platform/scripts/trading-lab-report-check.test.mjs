import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { Readable } from 'node:stream'
import test from 'node:test'

import {
  checkTradingLabReportStream,
  loadTradingLabReportSchema,
} from './trading-lab-report-check.mjs'

const FIFTY_MIB = 50 * 1024 * 1024
const THIRTY_TWO_MIB = 32 * 1024 * 1024
const schemaUrl = new URL(
  '../docs/testing/trading-lab/report-schema.json',
  import.meta.url,
)

const TRACE = JSON.stringify({
  url: 'http://validation-backend/api/validation/run',
  queryParameters: {},
  requestHeaders: {},
  requestContentType: 'application/json',
  requestBody: {
    sequence: 0,
    environment: 'validation',
    method: 'GET',
    url: '/api/validation/run',
    virtualTime: null,
    realTime: '2026-07-26T00:00:00Z',
    sanitizedRequest: {},
  },
  responseHeaders: {},
  responseContentType: 'application/json',
  responseBody: {
    status: 200,
    duration: 1,
    traceId: null,
    correlationId: null,
    recordedException: null,
  },
  exception: null,
  authentication: null,
})

function minimalReport(trace = TRACE) {
  return [
    '{"metadata":{},"actor":{},"environment":{},"scenario":{},',
    '"modelVersion":"large-report-it","configSnapshot":{},',
    '"localCalculation":{},"lifecycle":[],"apiTrace":[',
    trace,
    '],"marketTicks":[],"checkpoints":[],"actualState":{},',
    '"errors":[],"cleanup":{}}',
  ].join('')
}

async function* largeReportChunks() {
  yield [
    '{"metadata":{},"actor":{},"environment":{},"scenario":{},',
    '"modelVersion":"large-report-it","configSnapshot":{},',
    '"localCalculation":{},"lifecycle":[],"apiTrace":[',
    TRACE,
    '],"marketTicks":[',
  ].join('')

  const payload = 'x'.repeat(64 * 1024)
  for (let index = 0; index < 805; index += 1) {
    yield `${index === 0 ? '' : ','}{"sequence":${index},"payload":"${payload}"}`
  }

  yield '],"checkpoints":[],"actualState":{},"errors":[],"cleanup":{}}'
}

test('schema freezes controlled run execution failure evidence', async () => {
  const schema = await loadTradingLabReportSchema(schemaUrl)

  assert.equal(schema.properties.errors.items.$ref, '#/$defs/reportError')
  assert.deepEqual(schema.$defs.runExecutionFailure.required, [
    'runId',
    'sequence',
    'durableKey',
    'fingerprint',
    'type',
    'virtualTime',
    'correlationId',
    'payload',
  ])
  assert.equal(
    schema.$defs.runExecutionFailure.properties.type.const,
    'RUN_EXECUTION_FAILED',
  )
  assert.deepEqual(
    schema.$defs.runExecutionFailure.properties.payload.required,
    ['failurePoint', 'unexecuted'],
  )
  assert.deepEqual(
    schema.$defs.runExecutionFailure.properties.payload
      .properties.failurePoint.required,
    [
      'operation',
      'actionId',
      'tickSequence',
      'actionSequence',
      'status',
      'code',
    ],
  )
})

test('incrementally validates a schema-shaped report larger than 50 MiB', async () => {
  const schema = await loadTradingLabReportSchema(schemaUrl)
  const result = await checkTradingLabReportStream(
    Readable.from(largeReportChunks()),
    {
      schema,
      minBytes: FIFTY_MIB + 1,
      maxHeapDeltaBytes: THIRTY_TWO_MIB,
    },
  )

  assert.equal(result.status, 'PASS')
  assert.ok(result.totalBytes > FIFTY_MIB)
  assert.ok(result.peakHeapDeltaBytes < THIRTY_TWO_MIB)
  assert.deepEqual(result.topLevelSections, schema.required)
  assert.equal(result.apiTraceItems, 1)
  assert.equal(result.base64Wrapper, false)
})

test('reports bounded real HTTP categories across arbitrary stream boundaries', async () => {
  const schema = await loadTradingLabReportSchema(schemaUrl)
  const traces = [
    '/internal/validation/runs',
    '/api/trading/orders',
    '/api/accounts/account-id/wallet-balances',
    '/api/ledger',
  ].map((url, sequence) => {
    const trace = JSON.parse(TRACE)
    trace.url = `http://validation-backend${url}`
    trace.requestBody.sequence = sequence
    trace.requestBody.url = url
    return JSON.stringify(trace)
  })
  const encoded = minimalReport(traces.join(','))
  const chunks = []
  for (let offset = 0; offset < encoded.length; offset += 3) {
    chunks.push(encoded.slice(offset, offset + 3))
  }

  const result = await checkTradingLabReportStream(
    Readable.from(chunks),
    { schema },
  )

  assert.deepEqual(result.realHttpCategories, [
    'validation-run',
    'orders',
    'wallet',
    'ledger',
  ])
})

test('rejects malformed UTF-8 and bytes after the single JSON root', async () => {
  const schema = await loadTradingLabReportSchema(schemaUrl)
  const valid = minimalReport()
  const marker = '"large-report-it"'
  const markerAt = valid.indexOf(marker)
  const malformed = Buffer.concat([
    Buffer.from(valid.slice(0, markerAt + 1)),
    Buffer.from([0xc3, 0x28]),
    Buffer.from(valid.slice(markerAt + marker.length - 1)),
  ])

  await assert.rejects(
    checkTradingLabReportStream(Readable.from([malformed]), { schema }),
    /UTF-8/,
  )
  await assert.rejects(
    checkTradingLabReportStream(
      Readable.from([`${valid}\n{"second":true}`]),
      { schema },
    ),
    /single JSON|trailing/i,
  )
})

test('rejects Base64 wrappers and incomplete API trace schema objects', async () => {
  const schema = await loadTradingLabReportSchema(schemaUrl)
  await assert.rejects(
    checkTradingLabReportStream(
      Readable.from(['{"encoding":"base64","payload":"e30="}']),
      { schema },
    ),
    /schema|property|section/i,
  )

  const missingAuthentication = TRACE.replace(',"authentication":null', '')
  await assert.rejects(
    checkTradingLabReportStream(
      Readable.from([minimalReport(missingAuthentication)]),
      { schema },
    ),
    /authentication/,
  )
})

test('rejects reordered protocol sections and invalid schema date-time values', async () => {
  const schema = await loadTradingLabReportSchema(schemaUrl)
  const valid = minimalReport()
  const withoutCleanup = valid
    .slice(1, -1)
    .replace(',"cleanup":{}', '')
  await assert.rejects(
    checkTradingLabReportStream(
      Readable.from([`{"cleanup":{},${withoutCleanup}}`]),
      { schema },
    ),
    /order/i,
  )

  const invalidDateTime = TRACE.replace(
    '2026-07-26T00:00:00Z',
    'not-a-date',
  )
  await assert.rejects(
    checkTradingLabReportStream(
      Readable.from([minimalReport(invalidDateTime)]),
      { schema },
    ),
    /date-time/i,
  )
})

test('rejects JSON deeper than the backend streaming contract', async () => {
  const schema = await loadTradingLabReportSchema(schemaUrl)
  const nested = `${'['.repeat(70)}null${']'.repeat(70)}`
  const tooDeep = minimalReport().replace(
    '"marketTicks":[]',
    `"marketTicks":[${nested}]`,
  )
  await assert.rejects(
    checkTradingLabReportStream(Readable.from([tooDeep]), { schema }),
    /depth/i,
  )
})

test('package alias runs the focused report checker contract', async () => {
  const packageJson = JSON.parse(
    await readFile(new URL('../package.json', import.meta.url), 'utf8'),
  )
  assert.equal(
    packageJson.scripts['test:trading-lab-report-check'],
    'node --test scripts/trading-lab-report-check.test.mjs',
  )
})
