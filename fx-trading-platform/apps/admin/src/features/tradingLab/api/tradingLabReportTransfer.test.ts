import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'

import {
  createTradingLabReportTransfer,
  TRADING_LAB_REPORT_BLOB_LIMIT_BYTES,
  type TradingLabReportTransferDependencies,
  yieldToPrintPopup,
} from './tradingLabReportTransfer.ts'

const REPORT_ID = '33333333-3333-4333-8333-33333333333a'
const OTHER_REPORT_ID = '44444444-4444-4444-8444-444444444444'
const encoder = new TextEncoder()

test('opens the save picker synchronously before metadata or raw network work', async () => {
  const events: string[] = []
  const picker = deferred<ReportFileHandle>()
  const file = writableFile()
  const source = encoder.encode('{"ok":true}')
  const fixture = dependencies({
    pickSaveFile(options) {
      events.push(`picker:${options.suggestedName}`)
      return picker.promise
    },
    async getReport() {
      events.push('metadata')
      return reportMetadata(source.byteLength)
    },
    async requestRaw() {
      events.push('raw')
      return responseFromChunks([source], 'application/json')
    },
  })
  const transfer = createTradingLabReportTransfer(fixture)

  const pending = transfer.downloadReport(REPORT_ID)
  assert.deepEqual(events, [
    `picker:trading-lab-report-${REPORT_ID}.json`,
  ])

  picker.resolve(file.handle)
  await pending

  assert.deepEqual(events, [
    `picker:trading-lab-report-${REPORT_ID}.json`,
    'metadata',
    'raw',
  ])
  assert.deepEqual(file.bytes(), source)
  assert.equal(file.closeCount(), 1)
  assert.equal(file.abortCount(), 0)
})

test('picker AbortError is a clean cancellation with zero network calls', async (t) => {
  for (const mode of ['throw', 'reject'] as const) {
    await t.test(mode, async () => {
      let networkCalls = 0
      const fixture = dependencies({
        pickSaveFile() {
          const cancelled = abortError()
          if (mode === 'throw') throw cancelled
          return Promise.reject(cancelled)
        },
        async getReport() {
          networkCalls += 1
          return reportMetadata(0)
        },
        async requestRaw() {
          networkCalls += 1
          return responseFromChunks([])
        },
      })

      await createTradingLabReportTransfer(fixture).downloadReport(REPORT_ID)
      assert.equal(networkCalls, 0)
    })
  }
})

test('picker security failures stay fail-closed with zero network fallback', async (t) => {
  for (const mode of ['throw', 'reject'] as const) {
    await t.test(mode, async () => {
      let networkCalls = 0
      const fixture = dependencies({
        pickSaveFile() {
          const denied = new DOMException(
            'Must be handling a user gesture',
            'SecurityError',
          )
          if (mode === 'throw') throw denied
          return Promise.reject(denied)
        },
        async getReport() {
          networkCalls += 1
          return reportMetadata(0)
        },
        async requestRaw() {
          networkCalls += 1
          return responseFromChunks([])
        },
      })

      await assert.rejects(
        createTradingLabReportTransfer(fixture).downloadReport(REPORT_ID),
        (error: unknown) => (
          error instanceof DOMException
          && error.name === 'SecurityError'
        ),
      )
      assert.equal(networkCalls, 0)
    })
  }
})

test('direct file streaming never materializes a Blob and commits once after exact EOF', async () => {
  const events: string[] = []
  const file = writableFile(events)
  const chunks = [
    encoder.encode('{"part":'),
    encoder.encode('"一"}'),
  ]
  const total = chunks.reduce((sum, chunk) => sum + chunk.byteLength, 0)
  let blobCalls = 0
  const fixture = dependencies({
    pickSaveFile: () => Promise.resolve(file.handle),
    getReport: async () => reportMetadata(total),
    requestRaw: async () => responseFromChunks(chunks, 'application/json;charset=utf-8', 200, events),
    createBlob() {
      blobCalls += 1
      return new Blob()
    },
  })

  await createTradingLabReportTransfer(fixture).downloadReport(REPORT_ID)

  assert.equal(blobCalls, 0)
  assert.equal(file.closeCount(), 1)
  assert.equal(file.abortCount(), 0)
  assert.deepEqual(file.bytes(), concat(chunks))
  assert.ok(events.indexOf('source:eof') < events.indexOf('file:close'))

  const source = readFileSync(
    new URL('./tradingLabReportTransfer.ts', import.meta.url),
    'utf8',
  )
  assert.match(source, /preventClose:\s*true/)
})

test('file streaming aborts without close for short, long, partial, HTTP, MIME, body, reader, and caller failures', async (t) => {
  const cases: ReadonlyArray<{
    name: string
    expectedBytes: number
    response: () => Response
    abortAfterRaw?: boolean
  }> = [
    {
      name: 'short stream',
      expectedBytes: 3,
      response: () => responseFromChunks([encoder.encode('ab')]),
    },
    {
      name: 'long stream',
      expectedBytes: 2,
      response: () => responseFromChunks([encoder.encode('abc')]),
    },
    {
      name: 'partial content',
      expectedBytes: 2,
      response: () => responseFromChunks(
        [encoder.encode('ok')],
        'application/json',
        206,
      ),
    },
    {
      name: 'HTTP failure',
      expectedBytes: 2,
      response: () => responseFromChunks([encoder.encode('no')], 'application/json', 503),
    },
    {
      name: 'wrong MIME',
      expectedBytes: 2,
      response: () => responseFromChunks([encoder.encode('no')], 'text/plain'),
    },
    {
      name: 'missing body',
      expectedBytes: 0,
      response: () => new Response(null, {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    },
    {
      name: 'reader failure',
      expectedBytes: 2,
      response: () => new Response(new ReadableStream<Uint8Array>({
        start(controller) {
          controller.enqueue(encoder.encode('a'))
          controller.error(new Error('reader failed'))
        },
      }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    },
    {
      name: 'caller abort',
      expectedBytes: 2,
      response: () => responseFromChunks([encoder.encode('ab')]),
      abortAfterRaw: true,
    },
  ]

  for (const current of cases) {
    await t.test(current.name, async () => {
      const file = writableFile()
      const caller = new AbortController()
      const rawStarted = deferred<void>()
      const fixture = dependencies({
        pickSaveFile: () => Promise.resolve(file.handle),
        getReport: async () => reportMetadata(current.expectedBytes),
        async requestRaw() {
          rawStarted.resolve()
          if (current.abortAfterRaw) caller.abort()
          return current.response()
        },
      })

      await assert.rejects(
        createTradingLabReportTransfer(fixture).downloadReport(
          REPORT_ID,
          { signal: caller.signal },
        ),
      )
      await rawStarted.promise
      assert.equal(file.closeCount(), 0)
      assert.equal(file.abortCount(), 1)
    })
  }
})

test('Blob fallback reads incrementally, verifies exact bytes, and always cleans URL and anchor', async () => {
  const chunks = [
    encoder.encode('{"safe":"'),
    encoder.encode('<script>not markup</script>'),
    encoder.encode('"}'),
  ]
  const expected = concat(chunks)
  const response = responseFromChunks(chunks)
  forbidResponseMaterializers(response)
  const events: string[] = []
  let blobParts: BlobPart[] = []
  const anchor = downloadAnchor(events)
  const fixture = dependencies({
    pickSaveFile: () => null,
    getReport: async () => reportMetadata(expected.byteLength),
    requestRaw: async () => response,
    createBlob(parts, options) {
      events.push(`blob:${options.type}`)
      blobParts = parts
      return new Blob(parts, options)
    },
    createObjectUrl() {
      events.push('url:create')
      return 'blob:report'
    },
    revokeObjectUrl(url) {
      events.push(`url:revoke:${url}`)
    },
    createDownloadAnchor: () => anchor,
    appendDownloadAnchor() {
      events.push('anchor:append')
    },
  })

  await createTradingLabReportTransfer(fixture).downloadReport(REPORT_ID)

  assert.deepEqual(
    new Uint8Array(await new Blob(blobParts).arrayBuffer()),
    expected,
  )
  assert.equal(anchor.download, `trading-lab-report-${REPORT_ID}.json`)
  assert.equal(anchor.href, 'blob:report')
  assert.deepEqual(events, [
    'blob:application/json',
    'url:create',
    'anchor:append',
    'anchor:click',
    'anchor:remove',
    'url:revoke:blob:report',
  ])
})

test('Blob fallback enforces both sides of the exact 50 MiB cap before raw transfer', async (t) => {
  await t.test('exact cap is permitted to reach the raw GET', async () => {
    let rawCalls = 0
    const fixture = dependencies({
      pickSaveFile: () => null,
      getReport: async () => reportMetadata(
        TRADING_LAB_REPORT_BLOB_LIMIT_BYTES,
      ),
      async requestRaw() {
        rawCalls += 1
        return responseFromChunks([])
      },
    })

    await assert.rejects(
      createTradingLabReportTransfer(fixture).downloadReport(REPORT_ID),
      /byte|字节|size/i,
    )
    assert.equal(rawCalls, 1)
  })

  await t.test('one byte above cap is rejected before the raw GET', async () => {
    let rawCalls = 0
    const fixture = dependencies({
      pickSaveFile: () => null,
      getReport: async () => reportMetadata(
        TRADING_LAB_REPORT_BLOB_LIMIT_BYTES + 1,
      ),
      async requestRaw() {
        rawCalls += 1
        return responseFromChunks([])
      },
    })

    await assert.rejects(
      createTradingLabReportTransfer(fixture).downloadReport(REPORT_ID),
      /50 MiB|large|过大/i,
    )
    assert.equal(rawCalls, 0)
  })
})

test('Blob fallback never creates a Blob or anchor for a byte mismatch', async () => {
  let blobCalls = 0
  let anchorCalls = 0
  const fixture = dependencies({
    pickSaveFile: () => null,
    getReport: async () => reportMetadata(2),
    requestRaw: async () => responseFromChunks([encoder.encode('x')]),
    createBlob() {
      blobCalls += 1
      return new Blob()
    },
    createDownloadAnchor() {
      anchorCalls += 1
      return downloadAnchor([])
    },
  })

  await assert.rejects(
    createTradingLabReportTransfer(fixture).downloadReport(REPORT_ID),
  )
  assert.equal(blobCalls, 0)
  assert.equal(anchorCalls, 0)
})

test('opens the popup synchronously and a blocked popup makes zero API calls', async () => {
  const events: string[] = []
  let apiCalls = 0
  const fixture = dependencies({
    openPrintWindow() {
      events.push('popup')
      return null
    },
    async getPrintInfo() {
      apiCalls += 1
      return printInfo(0)
    },
    async requestRaw() {
      apiCalls += 1
      return responseFromChunks([], 'text/plain;charset=UTF-8')
    },
  })
  const transfer = createTradingLabReportTransfer(fixture)

  const pending = transfer.printRawReport(REPORT_ID)
  assert.deepEqual(events, ['popup'])
  await assert.rejects(pending, /popup|弹窗/i)
  assert.equal(apiCalls, 0)
})

test('print task yield settles when a popup animation frame is throttled', async () => {
  const frameCallbacks: (() => void)[] = []
  const cancelledFrames: number[] = []

  await yieldToPrintPopup({
    requestAnimationFrame(callback) {
      frameCallbacks.push(callback)
      return 17
    },
    cancelAnimationFrame(frameId) {
      cancelledFrames.push(frameId)
    },
  })

  assert.deepEqual(cancelledFrames, [17])
  const settledFrameCallback = frameCallbacks[0]
  if (settledFrameCallback === undefined) {
    assert.fail('popup animation frame callback was not registered')
  }
  settledFrameCallback()
  assert.deepEqual(cancelledFrames, [17])
})

test('print task yield settles when popup frames and opener timers are throttled', async () => {
  const originalSetTimeout = globalThis.setTimeout
  const originalClearTimeout = globalThis.clearTimeout
  const throttledTimerCallbacks: (() => void)[] = []
  const cancelledFrames: number[] = []
  globalThis.setTimeout = ((callback: () => void) => {
    throttledTimerCallbacks.push(callback)
    return 91 as unknown as ReturnType<typeof setTimeout>
  }) as typeof globalThis.setTimeout
  globalThis.clearTimeout = (() => undefined) as typeof globalThis.clearTimeout
  try {
    const outcome = await Promise.race([
      yieldToPrintPopup({
        requestAnimationFrame() {
          return 23
        },
        cancelAnimationFrame(frameId) {
          cancelledFrames.push(frameId)
        },
      }).then(() => 'settled' as const),
      new Promise<'deadline'>((resolve) => {
        originalSetTimeout(() => resolve('deadline'), 100)
      }),
    ])

    assert.equal(outcome, 'settled')
    assert.equal(throttledTimerCallbacks.length, 1)
    assert.deepEqual(cancelledFrames, [23])
  } finally {
    globalThis.setTimeout = originalSetTimeout
    globalThis.clearTimeout = originalClearTimeout
  }
})

test('prints split UTF-8 and raw markup through detached <=64 KiB text nodes after EOF', async () => {
  const events: string[] = []
  const popup = printWindow(events)
  const raw = JSON.stringify({
    value:
      `${'a'.repeat(70_000)}风险<img src=x onerror=alert(1)>`
      + ' literal } ] and escaped " plus \\ 🙂',
  })
  const bytes = encoder.encode(raw)
  const emojiStart = bytes.indexOf(0xf0)
  assert.ok(emojiStart > 0)
  const chunks = [
    bytes.slice(0, emojiStart + 1),
    bytes.slice(emojiStart + 1, emojiStart + 3),
    bytes.slice(emojiStart + 3),
  ]
  let confirmationCalls = 0
  let rawHeaders = new Headers()
  const fixture = dependencies({
    openPrintWindow: () => popup.port,
    getPrintInfo: async () => printInfo(
      TRADING_LAB_REPORT_BLOB_LIMIT_BYTES,
    ),
    async createPrintConfirmation() {
      confirmationCalls += 1
      return confirmation()
    },
    async requestRaw(path, init) {
      events.push(`raw:${path}`)
      rawHeaders = new Headers(init?.headers)
      return responseFromChunks(
        chunks,
        'text/plain;charset=UTF-8',
        200,
        events,
      )
    },
    async yieldToBrowser() {
      events.push('yield')
    },
  })

  await createTradingLabReportTransfer(fixture).printRawReport(REPORT_ID)

  assert.equal(popup.opener(), null)
  assert.equal(popup.text(), raw)
  assert.ok(popup.appendSizes().length >= 2)
  assert.ok(popup.appendSizes().every((size) => size <= 65_536))
  assert.equal(popup.textNodeCount(), popup.appendSizes().length)
  assert.ok(popup.textNodeAppendCounts().every((count) => count === 0))
  assert.deepEqual(popup.bodyAppendDisplays(), ['none'])
  assert.deepEqual(popup.printDisplays(), ['block'])
  assert.equal(popup.containerDisplay(), 'none')
  assert.equal(popup.printCount(), 1)
  assert.equal(popup.focusCount(), 2)
  assert.equal(popup.closeCount(), 0)
  assert.equal(confirmationCalls, 0)
  assert.equal(
    rawHeaders.get('X-Trading-Lab-Print-Confirmation'),
    null,
  )
  assert.ok(events.indexOf('popup:focus') < events.indexOf('source:eof'))
  assert.ok(events.indexOf('source:eof') < events.indexOf('body:pre'))
  assert.ok(events.indexOf('body:pre') < events.indexOf('popup:print'))
  assert.ok(events.indexOf('source:eof') < events.indexOf('popup:print'))

  const source = readFileSync(
    new URL('./tradingLabReportTransfer.ts', import.meta.url),
    'utf8',
  )
  assert.doesNotMatch(source, /\.innerHTML\s*=/)
  assert.doesNotMatch(source, /\.document\.write\s*\(/)
  assert.doesNotMatch(source, /\.write\s*\(\s*decoded/)
})

test('coalesces tiny transport chunks into bounded 64 KiB print batches', async (t) => {
  await t.test('a 2 KiB stream appends and yields once', async () => {
    const popup = printWindow([])
    const raw = JSON.stringify({ value: 'x'.repeat(2_048) })
    const bytes = encoder.encode(raw)
    let yieldCount = 0
    const yieldedScopes: unknown[] = []
    const fixture = dependencies({
      openPrintWindow: () => popup.port,
      getPrintInfo: async () => printInfo(bytes.byteLength),
      requestRaw: async () => responseFromChunks(
        [...bytes].map((value) => Uint8Array.of(value)),
        'text/plain;charset=UTF-8',
      ),
      async yieldToBrowser(scope?: unknown) {
        yieldCount += 1
        yieldedScopes.push(scope)
      },
    })

    await createTradingLabReportTransfer(fixture).printRawReport(REPORT_ID)

    assert.equal(popup.text(), raw)
    assert.deepEqual(popup.appendSizes(), [raw.length])
    assert.equal(yieldCount, 1)
    assert.equal(yieldedScopes[0], popup.port)
    assert.equal(popup.printCount(), 1)
  })

  await t.test('a 70 KiB UTF-8 stream preserves emoji in two batches', async () => {
    const popup = printWindow([])
    const raw =
      `{"value":"${'a'.repeat(65_525)}🙂${'b'.repeat(4_998)}"}`
    const bytes = encoder.encode(raw)
    const chunks: Uint8Array[] = []
    for (let offset = 0; offset < bytes.byteLength; offset += 1) {
      chunks.push(bytes.slice(offset, offset + 1))
    }
    let yieldCount = 0
    const fixture = dependencies({
      openPrintWindow: () => popup.port,
      getPrintInfo: async () => printInfo(bytes.byteLength),
      requestRaw: async () => responseFromChunks(
        chunks,
        'text/plain;charset=UTF-8',
      ),
      async yieldToBrowser() {
        yieldCount += 1
      },
    })

    await createTradingLabReportTransfer(fixture).printRawReport(REPORT_ID)

    assert.equal(popup.text(), raw)
    assert.deepEqual(popup.appendSizes(), [65_535, 5_002])
    assert.equal(yieldCount, 1)
    assert.equal(popup.printCount(), 1)
  })

  await t.test('a 2 MiB stream keeps 64 KiB writes but yields per MiB', async () => {
    const popup = printWindow([])
    const rawLength = (2 * 1_024 * 1_024) + 17
    const raw =
      `{"value":"${'x'.repeat(rawLength - '{"value":""}'.length)}"}`
    assert.equal(raw.length, rawLength)
    const bytes = encoder.encode(raw)
    const chunks: Uint8Array[] = []
    for (let offset = 0; offset < bytes.byteLength; offset += 4_093) {
      chunks.push(bytes.slice(offset, offset + 4_093))
    }
    let yieldCount = 0
    const fixture = dependencies({
      openPrintWindow: () => popup.port,
      getPrintInfo: async () => printInfo(bytes.byteLength),
      requestRaw: async () => responseFromChunks(
        chunks,
        'text/plain;charset=UTF-8',
      ),
      async yieldToBrowser() {
        yieldCount += 1
      },
    })

    await createTradingLabReportTransfer(fixture).printRawReport(REPORT_ID)

    assert.equal(popup.text(), raw)
    assert.equal(popup.appendSizes().length, 33)
    assert.ok(popup.appendSizes().every((size) => size <= 65_536))
    assert.deepEqual(popup.appendSizes().slice(-2), [65_536, 17])
    assert.equal(yieldCount, 3)
    assert.equal(popup.printCount(), 1)
  })

  await t.test('a 16 MiB stream uses one detached text node per bounded batch', async () => {
    const events: string[] = []
    const popup = printWindow(events)
    const rawLength = (16 * 1_024 * 1_024) + 17
    const raw =
      `{"value":"${'x'.repeat(rawLength - '{"value":""}'.length)}"}`
    assert.equal(raw.length, rawLength)
    const bytes = encoder.encode(raw)
    const chunks: Uint8Array[] = []
    for (let offset = 0; offset < bytes.byteLength; offset += 5_631) {
      chunks.push(bytes.slice(offset, offset + 5_631))
    }
    const fixture = dependencies({
      openPrintWindow: () => popup.port,
      getPrintInfo: async () => printInfo(bytes.byteLength),
      requestRaw: async () => responseFromChunks(
        chunks,
        'text/plain;charset=UTF-8',
        200,
        events,
      ),
      yieldToBrowser: async () => undefined,
    })

    await createTradingLabReportTransfer(fixture).printRawReport(REPORT_ID)

    assert.equal(popup.text(), raw)
    assert.equal(popup.textNodeCount(), popup.appendSizes().length)
    assert.ok(popup.textNodeCount() > 250)
    assert.ok(popup.textNodeAppendCounts().every((count) => count === 0))
    assert.ok(popup.appendSizes().every((size) => size <= 65_536))
    assert.deepEqual(popup.bodyAppendDisplays(), ['none'])
    assert.deepEqual(popup.printDisplays(), ['block'])
    assert.equal(popup.containerDisplay(), 'none')
    assert.ok(events.indexOf('source:eof') < events.indexOf('body:pre'))
    assert.equal(popup.printCount(), 1)
  })
})

test('large print requires exact local SUPER_ADMIN and explicit confirmation', async (t) => {
  await t.test('missing authority closes before confirmation issuance', async () => {
    const popup = printWindow([])
    let confirmationCalls = 0
    let rawCalls = 0
    const fixture = dependencies({
      openPrintWindow: () => popup.port,
      getPrintInfo: async () => printInfo(
        TRADING_LAB_REPORT_BLOB_LIMIT_BYTES + 1,
      ),
      hasSuperAdmin: () => false,
      confirmLargePrint: () => {
        throw new Error('must not ask without authority')
      },
      async createPrintConfirmation() {
        confirmationCalls += 1
        return confirmation()
      },
      async requestRaw() {
        rawCalls += 1
        return responseFromChunks([], 'text/plain;charset=UTF-8')
      },
    })

    await assert.rejects(
      createTradingLabReportTransfer(fixture).printRawReport(REPORT_ID),
      /SUPER_ADMIN/,
    )
    assert.equal(confirmationCalls, 0)
    assert.equal(rawCalls, 0)
    assert.equal(popup.closeCount(), 1)
    assert.equal(popup.printCount(), 0)
  })

  await t.test('declined confirmation closes with no token or raw request', async () => {
    const popup = printWindow([])
    let confirmationCalls = 0
    let rawCalls = 0
    const fixture = dependencies({
      openPrintWindow: () => popup.port,
      getPrintInfo: async () => printInfo(
        TRADING_LAB_REPORT_BLOB_LIMIT_BYTES + 1,
      ),
      hasSuperAdmin: () => true,
      confirmLargePrint: () => false,
      async createPrintConfirmation() {
        confirmationCalls += 1
        return confirmation()
      },
      async requestRaw() {
        rawCalls += 1
        return responseFromChunks([], 'text/plain;charset=UTF-8')
      },
    })

    await createTradingLabReportTransfer(fixture).printRawReport(REPORT_ID)
    assert.equal(confirmationCalls, 0)
    assert.equal(rawCalls, 0)
    assert.equal(popup.closeCount(), 1)
    assert.equal(popup.printCount(), 0)
  })
})

test('large-print token is issued once and appears only in the dedicated raw GET header', async () => {
  const popup = printWindow([])
  const secret = 'one-time-secret-never-render'
  const calls: Array<{
    path: string
    headers: Headers
    body: BodyInit | null | undefined
  }> = []
  let confirmationCalls = 0
  const fixture = dependencies({
    openPrintWindow: () => popup.port,
    getPrintInfo: async () => printInfo(
      TRADING_LAB_REPORT_BLOB_LIMIT_BYTES + 1,
    ),
    hasSuperAdmin: () => true,
    confirmLargePrint: () => true,
    async createPrintConfirmation() {
      confirmationCalls += 1
      return {
        reportId: REPORT_ID,
        token: secret,
        expiresAt: '2026-07-25T01:00:00Z',
      }
    },
    async requestRaw(path, init) {
      calls.push({
        path,
        headers: new Headers(init?.headers),
        body: init?.body,
      })
      return responseFromChunks(
        [encoder.encode('{"large":true}')],
        'text/plain;charset=UTF-8',
      )
    },
  })

  await createTradingLabReportTransfer(fixture).printRawReport(REPORT_ID)

  assert.equal(confirmationCalls, 1)
  assert.equal(calls.length, 1)
  assert.equal(
    calls[0]?.path,
    `/api/admin/trading-lab/reports/${REPORT_ID}/print`,
  )
  assert.equal(calls[0]?.body, undefined)
  assert.equal(
    calls[0]?.headers.get('X-Trading-Lab-Print-Confirmation'),
    secret,
  )
  assert.equal(calls[0]?.path.includes(secret), false)
  assert.equal(popup.text().includes(secret), false)
  assert.equal(popup.printCount(), 1)
})

test('fatal UTF-8 flush, wrong response, and popup pagehide abort without printing', async (t) => {
  await t.test('clean EOF with a truncated JSON root fails closed', async () => {
    const events: string[] = []
    const popup = printWindow(events)
    const truncated = encoder.encode('{\n  "metadata": {\n    "id": "partial"')
    const fixture = dependencies({
      openPrintWindow: () => popup.port,
      getPrintInfo: async () => printInfo(truncated.byteLength),
      requestRaw: async () => responseFromChunks(
        [truncated],
        'text/plain;charset=UTF-8',
      ),
    })

    await assert.rejects(
      createTradingLabReportTransfer(fixture).printRawReport(REPORT_ID),
      /JSON|complete|EOF/i,
    )
    assert.equal(popup.printCount(), 0)
    assert.equal(popup.closeCount(), 1)
    assert.equal(events.includes('body:pre'), false)
  })

  await t.test('balanced JSON followed by a stream error never reaches print', async () => {
    const events: string[] = []
    const popup = printWindow(events)
    const completeRoot = encoder.encode('{"metadata":{"id":"complete"}}')
    const sourceError = new Error('stream failed before clean EOF')
    let emitted = false
    const body = new ReadableStream<Uint8Array>({
      pull(controller) {
        if (!emitted) {
          emitted = true
          controller.enqueue(completeRoot)
          return
        }
        controller.error(sourceError)
      },
    })
    const fixture = dependencies({
      openPrintWindow: () => popup.port,
      getPrintInfo: async () => printInfo(completeRoot.byteLength),
      requestRaw: async () => new Response(body, {
        status: 200,
        headers: { 'Content-Type': 'text/plain;charset=UTF-8' },
      }),
    })

    await assert.rejects(
      createTradingLabReportTransfer(fixture).printRawReport(REPORT_ID),
      /stream failed before clean EOF/,
    )
    assert.equal(popup.printCount(), 0)
    assert.equal(popup.closeCount(), 1)
    assert.equal(events.includes('body:pre'), false)
  })

  await t.test('incomplete UTF-8 fails closed', async () => {
    const popup = printWindow([])
    const fixture = dependencies({
      openPrintWindow: () => popup.port,
      getPrintInfo: async () => printInfo(2),
      requestRaw: async () => responseFromChunks(
        [new Uint8Array([0xe2, 0x82])],
        'text/plain;charset=UTF-8',
      ),
    })

    await assert.rejects(
      createTradingLabReportTransfer(fixture).printRawReport(REPORT_ID),
    )
    assert.equal(popup.printCount(), 0)
    assert.equal(popup.closeCount(), 1)
  })

  for (const [name, response] of [
    [
      'partial content',
      () => responseFromChunks(
        [encoder.encode('ok')],
        'text/plain;charset=UTF-8',
        206,
      ),
    ],
    [
      'HTTP failure',
      () => responseFromChunks([], 'text/plain;charset=UTF-8', 403),
    ],
    [
      'wrong MIME',
      () => responseFromChunks([], 'application/json'),
    ],
    [
      'missing body',
      () => new Response(null, {
        status: 200,
        headers: { 'Content-Type': 'text/plain;charset=UTF-8' },
      }),
    ],
  ] as const) {
    await t.test(name, async () => {
      const popup = printWindow([])
      const fixture = dependencies({
        openPrintWindow: () => popup.port,
        getPrintInfo: async () => printInfo(0),
        requestRaw: async () => response(),
      })

      await assert.rejects(
        createTradingLabReportTransfer(fixture).printRawReport(REPORT_ID),
      )
      assert.equal(popup.printCount(), 0)
      assert.equal(popup.closeCount(), 1)
    })
  }

  await t.test('pagehide cancels the pending reader', async () => {
    const popup = printWindow([])
    const readerWaiting = deferred<void>()
    let readerCancelCount = 0
    const body = new ReadableStream<Uint8Array>({
      pull() {
        readerWaiting.resolve()
      },
      cancel() {
        readerCancelCount += 1
      },
    })
    const fixture = dependencies({
      openPrintWindow: () => popup.port,
      getPrintInfo: async () => printInfo(1),
      requestRaw: async () => new Response(body, {
        status: 200,
        headers: { 'Content-Type': 'text/plain;charset=UTF-8' },
      }),
    })

    const pending = createTradingLabReportTransfer(fixture)
      .printRawReport(REPORT_ID)
    await readerWaiting.promise
    popup.pagehide()
    await assert.rejects(pending)

    assert.equal(readerCancelCount, 1)
    assert.equal(popup.printCount(), 0)
    assert.ok(popup.closeCount() <= 1)
  })

  await t.test('pagehide interrupts a pending browser yield', async () => {
    const popup = printWindow([])
    const enteredYield = deferred<void>()
    const releaseYield = deferred<void>()
    const fixture = dependencies({
      openPrintWindow: () => popup.port,
      getPrintInfo: async () => printInfo(2),
      requestRaw: async () => responseFromChunks(
        [encoder.encode('{}')],
        'text/plain;charset=UTF-8',
      ),
      async yieldToBrowser() {
        enteredYield.resolve()
        await releaseYield.promise
      },
    })

    const pending = createTradingLabReportTransfer(fixture)
      .printRawReport(REPORT_ID)
    await enteredYield.promise
    popup.pagehide()
    const outcome = await Promise.race([
      pending.then(
        () => 'resolved',
        () => 'rejected',
      ),
      new Promise<'timeout'>((resolve) => {
        setTimeout(() => resolve('timeout'), 20)
      }),
    ])
    releaseYield.resolve()
    await assert.rejects(pending)

    assert.equal(outcome, 'rejected')
    assert.equal(popup.printCount(), 0)
    assert.ok(popup.closeCount() <= 1)
  })
})

test('rejects noncanonical report IDs and mismatched authoritative identities before raw work', async (t) => {
  await t.test('noncanonical input', async () => {
    let browserCalls = 0
    const fixture = dependencies({
      pickSaveFile() {
        browserCalls += 1
        return null
      },
      openPrintWindow() {
        browserCalls += 1
        return null
      },
    })
    const transfer = createTradingLabReportTransfer(fixture)

    await assert.rejects(transfer.downloadReport(REPORT_ID.toUpperCase()))
    await assert.rejects(transfer.printRawReport('not-a-uuid'))
    assert.equal(browserCalls, 0)
  })

  await t.test('mismatched metadata identity', async () => {
    const file = writableFile()
    let rawCalls = 0
    const fixture = dependencies({
      pickSaveFile: () => Promise.resolve(file.handle),
      getReport: async () => ({
        ...reportMetadata(0),
        id: OTHER_REPORT_ID,
      }),
      async requestRaw() {
        rawCalls += 1
        return responseFromChunks([])
      },
    })

    await assert.rejects(
      createTradingLabReportTransfer(fixture).downloadReport(REPORT_ID),
    )
    assert.equal(rawCalls, 0)
    assert.equal(file.closeCount(), 0)
    assert.equal(file.abortCount(), 0)
  })
})

function dependencies(
  overrides: Partial<TradingLabReportTransferDependencies> = {},
): TradingLabReportTransferDependencies {
  const popup = printWindow([])
  return {
    getReport: async () => reportMetadata(0),
    getPrintInfo: async () => printInfo(0),
    createPrintConfirmation: async () => confirmation(),
    requestRaw: async () => responseFromChunks([]),
    pickSaveFile: () => null,
    createBlob: (parts, options) => new Blob(parts, options),
    createObjectUrl: () => 'blob:default',
    revokeObjectUrl: () => undefined,
    createDownloadAnchor: () => downloadAnchor([]),
    appendDownloadAnchor: () => undefined,
    openPrintWindow: () => popup.port,
    hasSuperAdmin: () => false,
    confirmLargePrint: () => false,
    createTextDecoder: () => new TextDecoder('utf-8', { fatal: true }),
    yieldToBrowser: async () => undefined,
    ...overrides,
  }
}

function reportMetadata(uncompressedBytes: number) {
  return {
    id: REPORT_ID,
    uncompressedBytes,
  }
}

function printInfo(uncompressedBytes: number) {
  return {
    uncompressedBytes,
    estimatedPageCount: Math.ceil(uncompressedBytes / 4096),
    thresholdBytes: TRADING_LAB_REPORT_BLOB_LIMIT_BYTES,
    requiresConfirmation:
      uncompressedBytes > TRADING_LAB_REPORT_BLOB_LIMIT_BYTES,
  }
}

function confirmation() {
  return {
    reportId: REPORT_ID,
    token: 'confirmation-token',
    expiresAt: '2026-07-25T01:00:00Z',
  }
}

type ReportFileHandle = {
  createWritable(): Promise<WritableStream<Uint8Array>>
}

function writableFile(events: string[] = []) {
  const chunks: Uint8Array[] = []
  let closes = 0
  let aborts = 0
  const writable = new WritableStream<Uint8Array>({
    write(chunk) {
      chunks.push(chunk.slice())
      events.push(`file:write:${chunk.byteLength}`)
    },
    close() {
      closes += 1
      events.push('file:close')
    },
    abort() {
      aborts += 1
      events.push('file:abort')
    },
  })
  return {
    handle: {
      async createWritable() {
        return writable
      },
    } satisfies ReportFileHandle,
    bytes: () => concat(chunks),
    closeCount: () => closes,
    abortCount: () => aborts,
  }
}

function responseFromChunks(
  chunks: readonly Uint8Array[],
  contentType = 'application/json',
  status = 200,
  events: string[] = [],
) {
  let index = 0
  return new Response(new ReadableStream<Uint8Array>({
    pull(controller) {
      const chunk = chunks[index]
      if (chunk) {
        index += 1
        events.push(`source:chunk:${chunk.byteLength}`)
        controller.enqueue(chunk)
        return
      }
      events.push('source:eof')
      controller.close()
    },
  }), {
    status,
    headers: { 'Content-Type': contentType },
  })
}

function forbidResponseMaterializers(response: Response) {
  for (const method of ['text', 'json', 'arrayBuffer', 'blob'] as const) {
    Object.defineProperty(response, method, {
      configurable: true,
      value: () => {
        throw new Error(`response.${method}() must not be used`)
      },
    })
  }
}

type DownloadAnchor = {
  href: string
  download: string
  rel: string
  style: { display: string }
  click(): void
  remove(): void
}

function downloadAnchor(events: string[]): DownloadAnchor {
  return {
    href: '',
    download: '',
    rel: '',
    style: { display: '' },
    click() {
      events.push('anchor:click')
    },
    remove() {
      events.push('anchor:remove')
    },
  }
}

function printWindow(events: string[]) {
  let opener: unknown = {}
  let closed = false
  let closes = 0
  let focuses = 0
  let prints = 0
  const appendSizes: number[] = []
  const bodyAppendDisplays: (string | undefined)[] = []
  const printDisplays: (string | undefined)[] = []
  const pagehideListeners = new Set<() => void>()
  const textNodes: {
    value: string
    appendCount: number
    appendData(value: string): void
  }[] = []
  const appendedTextNodes: typeof textNodes = []
  const pre = {
    style: {} as Record<string, string>,
    append(node: typeof textNodes[number]) {
      assert.ok(textNodes.includes(node))
      appendedTextNodes.push(node)
      events.push('pre:text-node')
    },
  }
  const body = {
    append(node: unknown) {
      assert.equal(node, pre)
      bodyAppendDisplays.push(pre.style.display)
      events.push('body:pre')
    },
  }
  const document = {
    title: '',
    body,
    createElement(name: string) {
      assert.equal(name, 'pre')
      return pre
    },
    createTextNode(initial: string) {
      const node = {
        value: initial,
        appendCount: 0,
        appendData(value: string) {
          node.appendCount += 1
          node.value += value
          appendSizes.push(value.length)
          events.push(`text:${value.length}`)
        },
      }
      textNodes.push(node)
      if (initial.length > 0) {
        appendSizes.push(initial.length)
        events.push(`text:${initial.length}`)
      }
      return node
    },
    write() {
      throw new Error('document.write is forbidden')
    },
  }
  const port = {
    get opener() {
      return opener
    },
    set opener(value: unknown) {
      opener = value
    },
    get closed() {
      return closed
    },
    document,
    addEventListener(name: string, listener: () => void) {
      if (name === 'pagehide') pagehideListeners.add(listener)
    },
    removeEventListener(name: string, listener: () => void) {
      if (name === 'pagehide') pagehideListeners.delete(listener)
    },
    close() {
      closes += 1
      closed = true
      events.push('popup:close')
    },
    focus() {
      focuses += 1
      events.push('popup:focus')
    },
    print() {
      prints += 1
      printDisplays.push(pre.style.display)
      events.push('popup:print')
    },
  }

  return {
    port,
    opener: () => opener,
    text: () => appendedTextNodes.map((node) => node.value).join(''),
    appendSizes: () => appendSizes,
    textNodeCount: () => textNodes.length,
    textNodeAppendCounts: () => textNodes.map((node) => node.appendCount),
    bodyAppendDisplays: () => bodyAppendDisplays,
    printDisplays: () => printDisplays,
    containerDisplay: () => pre.style.display,
    closeCount: () => closes,
    focusCount: () => focuses,
    printCount: () => prints,
    pagehide() {
      closed = true
      for (const listener of pagehideListeners) listener()
    },
  }
}

function concat(chunks: readonly Uint8Array[]) {
  const length = chunks.reduce((sum, chunk) => sum + chunk.byteLength, 0)
  const result = new Uint8Array(length)
  let offset = 0
  for (const chunk of chunks) {
    result.set(chunk, offset)
    offset += chunk.byteLength
  }
  return result
}

function abortError() {
  return new DOMException('cancelled', 'AbortError')
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((next) => {
    resolve = next
  })
  return { promise, resolve }
}
