import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { afterEach, test } from 'node:test'

import {
  streamTradingLabRun,
  TradingLabStreamError,
  type TradingLabStreamEvent,
} from './tradingLabStream.ts'

const RUN_ID = '11111111-1111-4111-8111-111111111111'
const LONG_MAX = '9223372036854775807'
const TOKEN = 'e30.eyJleHAiOjQxMDI0NDQ4MDB9.signature'
const encoder = new TextEncoder()
const originalFetch = globalThis.fetch
const originalLocalStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')

afterEach(() => {
  globalThis.fetch = originalFetch
  if (originalLocalStorage) {
    Object.defineProperty(globalThis, 'localStorage', originalLocalStorage)
  } else {
    Reflect.deleteProperty(globalThis, 'localStorage')
  }
})

test('decodes fragmented UTF-8 and an EOF-tail complete signal', async () => {
  const wire =
    'event: warning\nid: 0\ndata: {"message":"风险警告"}\n\n'
    + 'event: complete\ndata: {"state":"COMPLETED"}'
  const bytes = encoder.encode(wire)
  installStreamFetch(
    Array.from({ length: bytes.length }, (_, index) => {
      return bytes.slice(index, index + 1)
    }),
  )
  const events: TradingLabStreamEvent[] = []

  const result = await streamTradingLabRun(
    RUN_ID,
    { onEvent: (event) => events.push(event) },
    { signal: new AbortController().signal },
  )

  assert.deepEqual(events, [
    {
      id: '0',
      event: 'warning',
      data: { message: '风险警告' },
    },
    {
      id: null,
      event: 'complete',
      data: { state: 'COMPLETED' },
    },
  ])
  assert.deepEqual(result, {
    kind: 'FINAL_REFRESH_REQUIRED',
    terminalState: 'COMPLETED',
    lastEventId: '0',
  })
})

test('supports LF, CRLF, CR, multiline data, and comment heartbeats without dispatch', async () => {
  installStreamFetch([
    encoder.encode(': keep-alive\r\r'),
    encoder.encode('event: state\rid: 0\rdata: {"state":"RUNNING"}\r\r'),
    encoder.encode('event: progress\r\nid: 1\r\ndata: {\r\ndata: "processedTicks":1}\r\n\r\n'),
    encoder.encode('event: tick\nid: 2\ndata: {"symbol":"BTCUSDT"}\n\n'),
    encoder.encode('event: complete\r\ndata: {"state":"FAILED"}\r\n\r\n'),
  ])
  const events: TradingLabStreamEvent[] = []

  const result = await streamTradingLabRun(
    RUN_ID,
    { onEvent: (event) => events.push(event) },
    { signal: new AbortController().signal },
  )

  assert.deepEqual(
    events.map(({ id, event }) => ({ id, event })),
    [
      { id: '0', event: 'state' },
      { id: '1', event: 'progress' },
      { id: '2', event: 'tick' },
      { id: null, event: 'complete' },
    ],
  )
  assert.deepEqual(events[1]?.data, { processedTicks: 1 })
  assert.equal(result.lastEventId, '2')
})

test('fresh and reconnect requests set only the required SSE headers and preserve large IDs', async () => {
  const requests: Array<{
    accept: string | null
    lastEventId: string | null
  }> = []
  const streams = [
    sseResponse(
      'event: state\nid: 0\ndata: {"state":"RUNNING"}\n\n'
      + 'event: complete\ndata: {"state":"CANCELLED"}\n\n',
    ),
    sseResponse(
      'event: checkpoint\nid: 9007199254740994\ndata: {"tickSequence":1}\n\n'
      + 'event: complete\ndata: {"state":"COMPLETED"}\n\n',
    ),
  ]
  installToken()
  globalThis.fetch = (async (_url: string | URL | Request, init?: RequestInit) => {
    const headers = new Headers(init?.headers)
    requests.push({
      accept: headers.get('Accept'),
      lastEventId: headers.get('Last-Event-ID'),
    })
    return streams.shift()!
  }) as typeof fetch

  await streamTradingLabRun(
    RUN_ID,
    { onEvent: () => undefined },
    { signal: new AbortController().signal },
  )
  const reconnect = await streamTradingLabRun(
    RUN_ID,
    { onEvent: () => undefined },
    {
      signal: new AbortController().signal,
      lastEventId: '9007199254740993',
    },
  )

  assert.deepEqual(requests, [
    { accept: 'text/event-stream', lastEventId: null },
    { accept: 'text/event-stream', lastEventId: '9007199254740993' },
  ])
  assert.equal(reconnect.lastEventId, '9007199254740994')
})

test('classifies the final 401 after apiRaw performs its sealed one-shot auth refresh', async () => {
  installToken('refresh-token')
  const calls: string[] = []
  globalThis.fetch = (async (url: string | URL | Request) => {
    const requestUrl = String(url)
    calls.push(requestUrl)
    if (requestUrl === '/api/auth/refresh') {
      return apiJsonResponse({
        accessToken: 'refreshed-access-token',
        refreshToken: 'refreshed-refresh-token',
        authorities: ['TRADING_LAB_VIEW'],
      })
    }
    return new Response('expired', { status: 401 })
  }) as typeof fetch

  await expectStreamError(runNoop(), {
    kind: 'HTTP',
    retryable: false,
    status: 401,
    lastEventId: null,
  })
  assert.deepEqual(calls, [
    `/api/admin/trading-lab/runs/${RUN_ID}/events`,
    '/api/auth/refresh',
    `/api/admin/trading-lab/runs/${RUN_ID}/events`,
  ])
})

test('accepts Long.MAX_VALUE without converting IDs to number and rejects overflow', async () => {
  installStreamFetchText(
    `event: checkpoint\nid: ${LONG_MAX}\ndata: {"tickSequence":1}\n\n`
    + 'event: complete\ndata: {"state":"COMPLETED"}\n\n',
  )
  const success = await streamTradingLabRun(
    RUN_ID,
    { onEvent: () => undefined },
    {
      signal: new AbortController().signal,
      lastEventId: '9223372036854775806',
    },
  )
  assert.equal(success.lastEventId, LONG_MAX)

  installStreamFetchText(
    'event: checkpoint\nid: 9223372036854775808\ndata: {"tickSequence":2}\n\n',
  )
  await expectStreamError(
    streamTradingLabRun(
      RUN_ID,
      { onEvent: () => undefined },
      {
        signal: new AbortController().signal,
        lastEventId: LONG_MAX,
      },
    ),
    {
      kind: 'PROTOCOL',
      retryable: false,
      lastEventId: LONG_MAX,
    },
  )
})

test('duplicate and gap IDs terminate without dispatching the invalid frame', async () => {
  for (const invalidId of ['0', '2']) {
    installStreamFetchText(
      'event: state\nid: 0\ndata: {"state":"RUNNING"}\n\n'
      + `event: tick\nid: ${invalidId}\ndata: {"tickSequence":1}\n\n`,
    )
    const events: TradingLabStreamEvent[] = []

    await expectStreamError(
      streamTradingLabRun(
        RUN_ID,
        { onEvent: (event) => events.push(event) },
        { signal: new AbortController().signal },
      ),
      {
        kind: 'PROTOCOL',
        retryable: false,
        lastEventId: '0',
      },
    )
    assert.deepEqual(events.map((event) => event.id), ['0'])
  }
})

test('malformed JSON and unknown events do not advance the durable cursor', async () => {
  const invalidFrames = [
    'event: tick\nid: 1\ndata: {"broken":\n\n',
    'event: mystery\nid: 1\ndata: {"value":1}\n\n',
  ]

  for (const invalidFrame of invalidFrames) {
    installStreamFetchText(
      'event: state\nid: 0\ndata: {"state":"RUNNING"}\n\n'
      + invalidFrame,
    )
    const events: TradingLabStreamEvent[] = []

    await expectStreamError(
      streamTradingLabRun(
        RUN_ID,
        { onEvent: (event) => events.push(event) },
        { signal: new AbortController().signal },
      ),
      {
        kind: 'PROTOCOL',
        retryable: false,
        lastEventId: '0',
      },
    )
    assert.equal(events.length, 1)
  }
})

test('oversized frames fail closed without advancing the cursor', async () => {
  const oversized = 'x'.repeat(1024 * 1024 + 1)
  installStreamFetchText(
    'event: state\nid: 0\ndata: {"state":"RUNNING"}\n\n'
    + `event: warning\nid: 1\ndata: {"message":"${oversized}"}\n\n`,
  )
  const events: TradingLabStreamEvent[] = []

  await expectStreamError(
    streamTradingLabRun(
      RUN_ID,
      { onEvent: (event) => events.push(event) },
      { signal: new AbortController().signal },
    ),
    {
      kind: 'PROTOCOL',
      retryable: false,
      lastEventId: '0',
    },
  )
  assert.deepEqual(events.map((event) => event.id), ['0'])
})

test('product error is a normal durable event and complete retains the cursor', async () => {
  installStreamFetchText(
    'event: error\nid: 0\ndata: {"code":"EXPECTED_NEGATIVE"}\n\n'
    + 'event: complete\ndata: {"state":"FAILED"}\n\n',
  )
  const events: TradingLabStreamEvent[] = []

  const result = await streamTradingLabRun(
    RUN_ID,
    { onEvent: (event) => events.push(event) },
    { signal: new AbortController().signal },
  )

  assert.deepEqual(events.map((event) => event.event), ['error', 'complete'])
  assert.equal(result.kind, 'FINAL_REFRESH_REQUIRED')
  assert.equal(result.lastEventId, '0')
})

test('complete must have no ID and only one terminal-state field', async () => {
  const invalidCompleteFrames = [
    'event: complete\nid: 1\ndata: {"state":"COMPLETED"}\n\n',
    'event: complete\ndata: {"state":"RUNNING"}\n\n',
    'event: complete\ndata: {"state":"FAILED","extra":true}\n\n',
  ]

  for (const completeFrame of invalidCompleteFrames) {
    installStreamFetchText(
      'event: state\nid: 0\ndata: {"state":"RUNNING"}\n\n'
      + completeFrame,
    )
    const events: TradingLabStreamEvent[] = []

    await expectStreamError(
      streamTradingLabRun(
        RUN_ID,
        { onEvent: (event) => events.push(event) },
        { signal: new AbortController().signal },
      ),
      {
        kind: 'PROTOCOL',
        retryable: false,
        lastEventId: '0',
      },
    )
    assert.deepEqual(events.map((event) => event.event), ['state'])
  }
})

test('classifies HTTP, MIME, body, network, reader, and nonterminal EOF failures', async () => {
  installFetchResponse(new Response('busy', { status: 503 }))
  await expectStreamError(runNoop(), {
    kind: 'HTTP',
    retryable: true,
    status: 503,
    lastEventId: null,
  })

  installFetchResponse(new Response('missing', { status: 404 }))
  await expectStreamError(runNoop(), {
    kind: 'HTTP',
    retryable: false,
    status: 404,
    lastEventId: null,
  })

  installFetchResponse(new Response('not SSE', {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  }))
  await expectStreamError(runNoop(), {
    kind: 'MIME',
    retryable: false,
    lastEventId: null,
  })

  installFetchResponse(new Response(null, {
    status: 200,
    headers: { 'Content-Type': 'text/event-stream' },
  }))
  await expectStreamError(runNoop(), {
    kind: 'BODY',
    retryable: false,
    lastEventId: null,
  })

  installToken()
  globalThis.fetch = (async () => {
    throw new TypeError('offline')
  }) as typeof fetch
  await expectStreamError(runNoop(), {
    kind: 'NETWORK',
    retryable: true,
    lastEventId: null,
  })

  installFetchResponse(streamWithReadFailure())
  await expectStreamError(runNoop(), {
    kind: 'READER',
    retryable: true,
    lastEventId: null,
  })

  installStreamFetchText(': keep-alive\n\n')
  await expectStreamError(runNoop(), {
    kind: 'EOF',
    retryable: true,
    lastEventId: null,
  })
})

test('an intentional AbortController cancellation returns without reconnecting', async () => {
  installToken()
  let fetchCount = 0
  let bodyController: ReadableStreamDefaultController<Uint8Array> | null = null
  globalThis.fetch = (async (_url: string | URL | Request, init?: RequestInit) => {
    fetchCount += 1
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        bodyController = controller
        init?.signal?.addEventListener('abort', () => {
          controller.error(new DOMException('aborted', 'AbortError'))
        })
      },
    })
    return new Response(body, {
      status: 200,
      headers: { 'Content-Type': 'text/event-stream; charset=utf-8' },
    })
  }) as typeof fetch
  const abortController = new AbortController()

  const pending = streamTradingLabRun(
    RUN_ID,
    { onEvent: () => undefined },
    { signal: abortController.signal },
  )
  await waitFor(() => bodyController !== null)
  abortController.abort()

  assert.deepEqual(await pending, {
    kind: 'ABORTED',
    lastEventId: null,
  })
  assert.equal(fetchCount, 1)
})

test('a nonterminal EOF does not reconnect internally and production does not use EventSource', async () => {
  let fetchCount = 0
  installToken()
  globalThis.fetch = (async () => {
    fetchCount += 1
    return sseResponse('event: state\nid: 0\ndata: {"state":"RUNNING"}\n\n')
  }) as typeof fetch

  await expectStreamError(runNoop(), {
    kind: 'EOF',
    retryable: true,
    lastEventId: '0',
  })
  assert.equal(fetchCount, 1)

  const currentDir = dirname(fileURLToPath(import.meta.url))
  const source = readFileSync(join(currentDir, 'tradingLabStream.ts'), 'utf8')
  assert.doesNotMatch(source, /\bEventSource\b/)
  assert.equal((source.match(/\bapiRaw\s*\(/g) ?? []).length, 1)
})

function runNoop() {
  return streamTradingLabRun(
    RUN_ID,
    { onEvent: () => undefined },
    { signal: new AbortController().signal },
  )
}

async function expectStreamError(
  promise: Promise<unknown>,
  expected: Readonly<{
    kind: TradingLabStreamError['kind']
    retryable: boolean
    status?: number
    lastEventId: string | null
  }>,
) {
  await assert.rejects(promise, (error: unknown) => {
    assert.ok(error instanceof TradingLabStreamError)
    assert.equal(error.kind, expected.kind)
    assert.equal(error.retryable, expected.retryable)
    assert.equal(error.status, expected.status)
    assert.equal(error.lastEventId, expected.lastEventId)
    return true
  })
}

function installStreamFetchText(text: string) {
  installStreamFetch([encoder.encode(text)])
}

function installStreamFetch(chunks: readonly Uint8Array[]) {
  installFetchResponse(streamResponse(chunks))
}

function installFetchResponse(response: Response) {
  installToken()
  globalThis.fetch = (async () => response) as typeof fetch
}

function installToken(refreshToken?: string) {
  const values = new Map<string, string>([
    ['fx-platform-admin-token', TOKEN],
  ])
  if (refreshToken !== undefined) {
    values.set('fx-platform-admin-refresh-token', refreshToken)
  }
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    value: {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => values.set(key, value),
      removeItem: (key: string) => values.delete(key),
      clear: () => values.clear(),
      key: (index: number) => Array.from(values.keys())[index] ?? null,
      get length() {
        return values.size
      },
    } satisfies Storage,
  })
}

function apiJsonResponse(data: unknown) {
  return new Response(
    JSON.stringify({
      success: true,
      code: 'OK',
      message: 'OK',
      data,
    }),
    {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    },
  )
}

function sseResponse(text: string) {
  return streamResponse([encoder.encode(text)])
}

function streamResponse(chunks: readonly Uint8Array[]) {
  return new Response(
    new ReadableStream<Uint8Array>({
      start(controller) {
        for (const chunk of chunks) {
          controller.enqueue(chunk)
        }
        controller.close()
      },
    }),
    {
      status: 200,
      headers: { 'Content-Type': 'text/event-stream; charset=utf-8' },
    },
  )
}

function streamWithReadFailure() {
  return new Response(
    new ReadableStream<Uint8Array>({
      start(controller) {
        controller.error(new Error('reader failed'))
      },
    }),
    {
      status: 200,
      headers: { 'Content-Type': 'text/event-stream' },
    },
  )
}

async function waitFor(predicate: () => boolean) {
  for (let index = 0; index < 20 && !predicate(); index += 1) {
    await new Promise<void>((resolve) => setImmediate(resolve))
  }
  assert.equal(predicate(), true)
}
