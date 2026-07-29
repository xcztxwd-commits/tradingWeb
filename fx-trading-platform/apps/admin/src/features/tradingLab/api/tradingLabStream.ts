import { apiRaw } from '../../../services/apiClient.ts'

const DURABLE_EVENT_NAMES = [
  'state',
  'progress',
  'tick',
  'checkpoint',
  'api-trace',
  'warning',
  'error',
] as const
const ALL_EVENT_NAMES = [...DURABLE_EVENT_NAMES, 'complete'] as const
const TERMINAL_STATES = ['COMPLETED', 'FAILED', 'CANCELLED'] as const
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const DECIMAL_ID_PATTERN = /^(0|[1-9][0-9]*)$/
const LONG_MAX = 9_223_372_036_854_775_807n
const MAX_SSE_BYTES = 1024 * 1024
const MAX_EVENT_JSON_DEPTH = 32
const MAX_EVENT_JSON_NODES = 10_000
const MAX_EVENT_COLLECTION_SIZE = 2_048
const MAX_EVENT_STRING_LENGTH = 64 * 1024

export type TradingLabStreamDurableEventName =
  (typeof DURABLE_EVENT_NAMES)[number]
export type TradingLabStreamEventName = (typeof ALL_EVENT_NAMES)[number]
export type TradingLabTerminalState = (typeof TERMINAL_STATES)[number]
export type TradingLabStreamData = Readonly<Record<string, unknown>>

export type TradingLabStreamEvent = Readonly<{
  id: string | null
  event: TradingLabStreamEventName
  data: TradingLabStreamData
}>

export type TradingLabStreamHandlers = Readonly<{
  onEvent: (event: TradingLabStreamEvent) => void
}>

export type TradingLabStreamOptions = Readonly<{
  signal: AbortSignal
  lastEventId?: string
}>

export type TradingLabStreamResult =
  | Readonly<{
      kind: 'FINAL_REFRESH_REQUIRED'
      terminalState: TradingLabTerminalState
      lastEventId: string | null
    }>
  | Readonly<{
      kind: 'ABORTED'
      lastEventId: string | null
    }>

export type TradingLabStreamErrorKind =
  | 'HTTP'
  | 'MIME'
  | 'BODY'
  | 'NETWORK'
  | 'READER'
  | 'EOF'
  | 'PROTOCOL'

export type TradingLabStreamErrorInit = Readonly<{
  kind: TradingLabStreamErrorKind
  retryable: boolean
  message: string
  lastEventId: string | null
  status?: number
  cause?: unknown
}>

export class TradingLabStreamError extends Error {
  readonly kind: TradingLabStreamErrorKind
  readonly retryable: boolean
  readonly status: number | undefined
  readonly lastEventId: string | null

  constructor(init: TradingLabStreamErrorInit) {
    super(init.message, init.cause === undefined ? undefined : { cause: init.cause })
    this.name = 'TradingLabStreamError'
    this.kind = init.kind
    this.retryable = init.retryable
    this.status = init.status
    this.lastEventId = init.lastEventId
  }
}

export async function streamTradingLabRun(
  runId: string,
  handlers: TradingLabStreamHandlers,
  options: TradingLabStreamOptions,
): Promise<TradingLabStreamResult> {
  requireCanonicalRunId(runId)
  requireHandlers(handlers)
  requireOptions(options)
  requireAbortSignal(options?.signal)
  const initialCursor = parseInitialCursor(options.lastEventId)
  let cursor = initialCursor

  if (options.signal.aborted) {
    return aborted(cursor)
  }

  const headers = new Headers({ Accept: 'text/event-stream' })
  if (options.lastEventId !== undefined) {
    headers.set('Last-Event-ID', options.lastEventId)
  }

  let response: Response
  try {
    response = await apiRaw(
      `/api/admin/trading-lab/runs/${runId}/events`,
      {
        method: 'GET',
        headers,
        signal: options.signal,
      },
    )
  } catch (cause) {
    if (options.signal.aborted || isAbortFailure(cause)) {
      return aborted(cursor)
    }
    throw streamError({
      kind: 'NETWORK',
      retryable: true,
      message: 'Trading Lab stream connection failed',
      lastEventId: cursor,
      cause,
    })
  }

  if (options.signal.aborted) {
    return aborted(cursor)
  }
  if (!response.ok) {
    throw streamError({
      kind: 'HTTP',
      retryable: isTransientStatus(response.status),
      message: `Trading Lab stream returned HTTP ${response.status}`,
      status: response.status,
      lastEventId: cursor,
    })
  }
  if (!isEventStreamMime(response.headers.get('Content-Type'))) {
    throw streamError({
      kind: 'MIME',
      retryable: false,
      message: 'Trading Lab stream response is not text/event-stream',
      lastEventId: cursor,
    })
  }
  if (response.body === null) {
    throw streamError({
      kind: 'BODY',
      retryable: false,
      message: 'Trading Lab stream response body is missing',
      lastEventId: cursor,
    })
  }

  let reader: ReadableStreamDefaultReader<Uint8Array>
  try {
    reader = response.body.getReader()
  } catch (cause) {
    throw streamError({
      kind: 'BODY',
      retryable: false,
      message: 'Trading Lab stream response body is not readable',
      lastEventId: cursor,
      cause,
    })
  }
  const decoder = new TextDecoder('utf-8', { fatal: true })
  const parser = createSseParser({
    initialCursor,
    onEvent(event) {
      handlers.onEvent(event)
      if (event.id !== null) cursor = event.id
    },
  })

  try {
    while (true) {
      let read: ReadableStreamReadResult<Uint8Array>
      try {
        read = await reader.read()
      } catch (cause) {
        if (options.signal.aborted || isAbortFailure(cause)) {
          return aborted(cursor)
        }
        throw streamError({
          kind: 'READER',
          retryable: true,
          message: 'Trading Lab stream reader failed',
          lastEventId: cursor,
          cause,
        })
      }

      if (read.done) {
        const decoderTail = decodeChunk(decoder, undefined, false, cursor)
        const decodedResult = parser.feed(decoderTail)
        if (decodedResult !== null) {
          await cancelReader(reader)
          return decodedResult
        }
        const tailResult = parser.finish()
        if (tailResult !== null) {
          await cancelReader(reader)
          return tailResult
        }
        throw streamError({
          kind: 'EOF',
          retryable: true,
          message: 'Trading Lab stream ended before complete',
          lastEventId: cursor,
        })
      }

      const text = decodeChunk(decoder, read.value, true, cursor)
      const result = parser.feed(text)
      if (result !== null) {
        await cancelReader(reader)
        return result
      }
    }
  } catch (failure) {
    await cancelReader(reader)
    if (failure instanceof TradingLabStreamError) {
      throw failure
    }
    throw streamError({
      kind: 'PROTOCOL',
      retryable: false,
      message: 'Trading Lab stream protocol handling failed',
      lastEventId: cursor,
      cause: failure,
    })
  } finally {
    reader.releaseLock()
  }
}

type MutableFrame = {
  event: string | null
  id: string | null
  dataLines: string[]
  bytes: number
  touched: boolean
}

type SseParser = {
  feed(text: string): TradingLabStreamResult | null
  finish(): TradingLabStreamResult | null
}

function createSseParser(input: Readonly<{
  initialCursor: string | null
  onEvent: (event: TradingLabStreamEvent) => void
}>): SseParser {
  let expectedId =
    input.initialCursor === null ? 0n : BigInt(input.initialCursor) + 1n
  let cursor = input.initialCursor
  let frame = emptyFrame()
  let line = ''
  let lineBytes = 0
  let pendingCarriageReturn = false

  const ensureAccumulatedLimit = () => {
    if (frame.bytes + lineBytes > MAX_SSE_BYTES) {
      throw protocolFailure(
        'Trading Lab stream frame exceeds 1 MiB',
        cursor,
      )
    }
  }

  const appendCharacter = (character: string) => {
    line += character
    lineBytes += utf8Bytes(character)
    ensureAccumulatedLimit()
  }

  const resetFrame = () => {
    frame = emptyFrame()
  }

  const emitLine = (
    terminatorBytes: number,
  ): TradingLabStreamResult | null => {
    frame.bytes += lineBytes + terminatorBytes
    line = ''
    lineBytes = 0
    if (frame.bytes > MAX_SSE_BYTES) {
      throw protocolFailure(
        'Trading Lab stream frame exceeds 1 MiB',
        cursor,
      )
    }

    const currentLine = emittedLine
    emittedLine = ''
    if (currentLine.length === 0) {
      const result = dispatchFrame(frame, expectedId, cursor, input.onEvent)
      if (result?.kind === 'DURABLE') {
        cursor = result.id
        expectedId = BigInt(result.id) + 1n
        resetFrame()
        return null
      }
      resetFrame()
      return result?.result ?? null
    }

    frame.touched = true
    if (currentLine.startsWith(':')) {
      return null
    }
    const separator = currentLine.indexOf(':')
    const field = separator < 0
      ? currentLine
      : currentLine.slice(0, separator)
    let value = separator < 0 ? '' : currentLine.slice(separator + 1)
    if (value.startsWith(' ')) value = value.slice(1)

    if (field === 'event') {
      if (frame.event !== null) {
        throw protocolFailure(
          'Trading Lab stream contains duplicate event fields',
          cursor,
        )
      }
      frame.event = value
    } else if (field === 'id') {
      if (frame.id !== null || value.includes('\0')) {
        throw protocolFailure(
          'Trading Lab stream contains an invalid id field',
          cursor,
        )
      }
      frame.id = value
    } else if (field === 'data') {
      frame.dataLines.push(value)
    }
    return null
  }

  let emittedLine = ''
  const queueLineForEmission = (
    terminatorBytes: number,
  ): TradingLabStreamResult | null => {
    emittedLine = line
    return emitLine(terminatorBytes)
  }

  return {
    feed(text) {
      for (const character of text) {
        if (pendingCarriageReturn) {
          pendingCarriageReturn = false
          const result = queueLineForEmission(character === '\n' ? 2 : 1)
          if (result !== null) return result
          if (character === '\n') continue
        }
        if (character === '\r') {
          pendingCarriageReturn = true
        } else if (character === '\n') {
          const result = queueLineForEmission(1)
          if (result !== null) return result
        } else {
          appendCharacter(character)
        }
      }
      return null
    },
    finish() {
      if (pendingCarriageReturn) {
        pendingCarriageReturn = false
        const result = queueLineForEmission(1)
        if (result !== null) return result
      } else if (line.length > 0) {
        const result = queueLineForEmission(0)
        if (result !== null) return result
      }
      if (frame.touched) {
        const result = dispatchFrame(frame, expectedId, cursor, input.onEvent)
        if (result?.kind === 'DURABLE') {
          cursor = result.id
          expectedId = BigInt(result.id) + 1n
          resetFrame()
          return null
        }
        resetFrame()
        return result?.result ?? null
      }
      return null
    },
  }
}

type DispatchResult =
  | Readonly<{ kind: 'DURABLE'; id: string }>
  | Readonly<{ kind: 'TERMINAL'; result: TradingLabStreamResult }>

function dispatchFrame(
  frame: MutableFrame,
  expectedId: bigint,
  cursor: string | null,
  onEvent: (event: TradingLabStreamEvent) => void,
): DispatchResult | null {
  if (!frame.touched) return null
  if (
    frame.event === null
    && frame.id === null
    && frame.dataLines.length === 0
  ) {
    return null
  }
  if (!isOneOf(frame.event, ALL_EVENT_NAMES)) {
    throw protocolFailure(
      'Trading Lab stream event is missing or unknown',
      cursor,
    )
  }

  const data = parseEventData(frame.dataLines, cursor)
  if (frame.event === 'complete') {
    if (frame.id !== null || !isTerminalData(data)) {
      throw protocolFailure(
        'Trading Lab complete event is invalid',
        cursor,
      )
    }
    dispatchToHandler(onEvent, {
      id: null,
      event: 'complete',
      data,
    }, cursor)
    return {
      kind: 'TERMINAL',
      result: {
        kind: 'FINAL_REFRESH_REQUIRED',
        terminalState: data.state,
        lastEventId: cursor,
      },
    }
  }

  const id = parseDurableId(frame.id, cursor)
  if (BigInt(id) !== expectedId) {
    throw protocolFailure(
      'Trading Lab durable event IDs are not continuous',
      cursor,
    )
  }
  dispatchToHandler(onEvent, {
    id,
    event: frame.event,
    data,
  }, cursor)
  return { kind: 'DURABLE', id }
}

function parseEventData(
  dataLines: readonly string[],
  cursor: string | null,
): TradingLabStreamData {
  if (dataLines.length === 0) {
    throw protocolFailure(
      'Trading Lab stream event data is missing',
      cursor,
    )
  }
  let value: unknown
  try {
    value = JSON.parse(dataLines.join('\n'))
  } catch (cause) {
    throw protocolFailure(
      'Trading Lab stream event data is not valid JSON',
      cursor,
      cause,
    )
  }
  if (!isPlainRecord(value)) {
    throw protocolFailure(
      'Trading Lab stream event data must be an object',
      cursor,
    )
  }
  assertBoundedEventJson(value, cursor)
  return value
}

function assertBoundedEventJson(
  value: unknown,
  cursor: string | null,
) {
  let nodes = 0
  const visit = (item: unknown, depth: number): void => {
    nodes += 1
    if (nodes > MAX_EVENT_JSON_NODES || depth > MAX_EVENT_JSON_DEPTH) {
      throw protocolFailure(
        'Trading Lab stream event JSON is too large',
        cursor,
      )
    }
    if (
      item === null
      || typeof item === 'boolean'
      || (typeof item === 'number' && Number.isFinite(item))
    ) {
      return
    }
    if (typeof item === 'string') {
      if (item.length > MAX_EVENT_STRING_LENGTH) {
        throw protocolFailure(
          'Trading Lab stream event JSON is too large',
          cursor,
        )
      }
      return
    }
    if (Array.isArray(item)) {
      if (item.length > MAX_EVENT_COLLECTION_SIZE) {
        throw protocolFailure(
          'Trading Lab stream event JSON is too large',
          cursor,
        )
      }
      for (const child of item) visit(child, depth + 1)
      return
    }
    if (isPlainRecord(item)) {
      const entries = Object.entries(item)
      if (entries.length > MAX_EVENT_COLLECTION_SIZE) {
        throw protocolFailure(
          'Trading Lab stream event JSON is too large',
          cursor,
        )
      }
      for (const [key, child] of entries) {
        if (key.length > MAX_EVENT_STRING_LENGTH) {
          throw protocolFailure(
            'Trading Lab stream event JSON is too large',
            cursor,
          )
        }
        visit(child, depth + 1)
      }
      return
    }
    throw protocolFailure(
      'Trading Lab stream event JSON contains an invalid value',
      cursor,
    )
  }
  visit(value, 0)
}

function parseDurableId(
  value: string | null,
  cursor: string | null,
) {
  if (
    value === null
    || value.length > LONG_MAX.toString().length
    || !DECIMAL_ID_PATTERN.test(value)
  ) {
    throw protocolFailure(
      'Trading Lab durable event ID is invalid',
      cursor,
    )
  }
  const numeric = BigInt(value)
  if (numeric > LONG_MAX) {
    throw protocolFailure(
      'Trading Lab durable event ID exceeds Long.MAX_VALUE',
      cursor,
    )
  }
  return value
}

function parseInitialCursor(value: string | undefined) {
  if (value === undefined) return null
  if (
    value.length > LONG_MAX.toString().length
    || !DECIMAL_ID_PATTERN.test(value)
    || BigInt(value) > LONG_MAX
  ) {
    throw protocolFailure(
      'Trading Lab Last-Event-ID is invalid',
      null,
    )
  }
  return value
}

function isTerminalData(
  value: TradingLabStreamData,
): value is Readonly<{ state: TradingLabTerminalState }> {
  const keys = Object.keys(value)
  return keys.length === 1
    && keys[0] === 'state'
    && isOneOf(value.state, TERMINAL_STATES)
}

function dispatchToHandler(
  handler: (event: TradingLabStreamEvent) => void,
  event: TradingLabStreamEvent,
  cursor: string | null,
) {
  try {
    handler(event)
  } catch (cause) {
    throw protocolFailure(
      'Trading Lab stream event handler failed',
      cursor,
      cause,
    )
  }
}

function decodeChunk(
  decoder: TextDecoder,
  chunk: Uint8Array | undefined,
  stream: boolean,
  cursor: string | null,
) {
  try {
    return chunk === undefined
      ? decoder.decode()
      : decoder.decode(chunk, { stream })
  } catch (cause) {
    throw protocolFailure(
      'Trading Lab stream contains invalid UTF-8',
      cursor,
      cause,
    )
  }
}

function requireCanonicalRunId(value: string) {
  if (typeof value !== 'string' || !UUID_PATTERN.test(value)) {
    throw protocolFailure(
      'Trading Lab run ID must be a canonical lowercase UUID',
      null,
    )
  }
}

function requireHandlers(value: TradingLabStreamHandlers) {
  if (
    value === null
    || typeof value !== 'object'
    || typeof value.onEvent !== 'function'
  ) {
    throw protocolFailure(
      'Trading Lab stream event handler is required',
      null,
    )
  }
}

function requireAbortSignal(value: AbortSignal) {
  if (
    value === null
    || typeof value !== 'object'
    || typeof value.aborted !== 'boolean'
    || typeof value.addEventListener !== 'function'
  ) {
    throw protocolFailure(
      'Trading Lab stream AbortSignal is required',
      null,
    )
  }
}

function requireOptions(value: TradingLabStreamOptions) {
  if (value === null || typeof value !== 'object') {
    throw protocolFailure(
      'Trading Lab stream options are required',
      null,
    )
  }
}

function isEventStreamMime(value: string | null) {
  return value
    ?.split(';', 1)[0]
    ?.trim()
    .toLowerCase() === 'text/event-stream'
}

function isTransientStatus(status: number) {
  return status === 408 || status === 429 || status >= 500
}

function isAbortFailure(value: unknown) {
  return value instanceof DOMException && value.name === 'AbortError'
}

function emptyFrame(): MutableFrame {
  return {
    event: null,
    id: null,
    dataLines: [],
    bytes: 0,
    touched: false,
  }
}

function utf8Bytes(value: string) {
  const codePoint = value.codePointAt(0)
  if (codePoint === undefined) return 0
  if (codePoint <= 0x7f) return 1
  if (codePoint <= 0x7ff) return 2
  if (codePoint <= 0xffff) return 3
  return 4
}

function protocolFailure(
  message: string,
  lastEventId: string | null,
  cause?: unknown,
) {
  return streamError({
    kind: 'PROTOCOL',
    retryable: false,
    message,
    lastEventId,
    cause,
  })
}

function streamError(init: TradingLabStreamErrorInit) {
  return new TradingLabStreamError(init)
}

function aborted(lastEventId: string | null): TradingLabStreamResult {
  return {
    kind: 'ABORTED',
    lastEventId,
  }
}

async function cancelReader(
  reader: ReadableStreamDefaultReader<Uint8Array>,
) {
  try {
    await reader.cancel()
  } catch {
    // The original terminal/error result remains authoritative.
  }
}

function isOneOf<const Values extends readonly string[]>(
  value: unknown,
  values: Values,
): value is Values[number] {
  return typeof value === 'string' && values.includes(value)
}

function isPlainRecord(value: unknown): value is Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    return false
  }
  const prototype = Object.getPrototypeOf(value)
  return prototype === Object.prototype || prototype === null
}
