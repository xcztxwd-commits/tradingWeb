import {
  createTradingLabReportPrintConfirmation,
  getTradingLabReport,
  getTradingLabReportPrintInfo,
  TRADING_LAB_REPORT_PRINT_THRESHOLD_BYTES,
  type TradingLabReportPrintConfirmationResponse,
  type TradingLabReportPrintInfoResponse,
  type TradingLabReportResponse,
} from './tradingLabApi.ts'
import { apiRaw } from '../../../services/apiClient.ts'
import {
  getValidAdminToken,
  hasAdminAuthority,
} from '../../../services/adminToken.ts'

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const DOWNLOAD_MIME = 'application/json'
const PRINT_MIME = 'text/plain;charset=utf-8'
const PRINT_CONFIRMATION_HEADER = 'X-Trading-Lab-Print-Confirmation'
const MAX_TEXT_APPEND_UNITS = 64 * 1024
const TEXT_YIELD_INTERVAL_UNITS = 1024 * 1024
const MAX_PRINT_JSON_DEPTH = 68
const MAX_CONFIRMATION_TOKEN_LENGTH = 16_384

export const TRADING_LAB_REPORT_BLOB_LIMIT_BYTES =
  TRADING_LAB_REPORT_PRINT_THRESHOLD_BYTES

export type TradingLabReportTransferOptions = Readonly<{
  signal?: AbortSignal
}>

type ReportMetadata = Pick<
  TradingLabReportResponse,
  'id' | 'uncompressedBytes'
>

type SavePickerOptions = Readonly<{
  suggestedName: string
  excludeAcceptAllOption: boolean
  types: readonly Readonly<{
    description: string
    accept: Readonly<Record<string, readonly string[]>>
  }>[]
}>

type ReportFileHandle = {
  createWritable(): Promise<WritableStream<Uint8Array>>
}

type DownloadAnchor = {
  href: string
  download: string
  rel: string
  style: { display: string }
  click(): void
  remove(): void
}

type PrintTextNode = {
  appendData(value: string): void
}

type PrintContainer = {
  style: {
    whiteSpace?: string
    overflowWrap?: string
    fontFamily?: string
    fontSize?: string
    display?: string
  }
  append(node: PrintTextNode): void
}

type PrintDocument = {
  title: string
  body: {
    append(node: PrintContainer): void
  } | null
  createElement(name: 'pre'): PrintContainer
  createTextNode(value: string): PrintTextNode
}

type PrintWindow = {
  opener: unknown
  readonly closed: boolean
  readonly document: PrintDocument
  addEventListener(name: 'pagehide', listener: () => void): void
  removeEventListener(name: 'pagehide', listener: () => void): void
  close(): void
  focus(): void
  print(): void
  requestAnimationFrame?(callback: () => void): number
  cancelAnimationFrame?(frameId: number): void
}

export type TradingLabReportTransferDependencies = Readonly<{
  getReport(reportId: string): Promise<ReportMetadata>
  getPrintInfo(reportId: string): Promise<TradingLabReportPrintInfoResponse>
  createPrintConfirmation(
    reportId: string,
  ): Promise<TradingLabReportPrintConfirmationResponse>
  requestRaw(
    path: string,
    init?: RequestInit & { signal?: AbortSignal },
  ): Promise<Response>
  pickSaveFile(options: SavePickerOptions): Promise<ReportFileHandle> | null
  createBlob(parts: BlobPart[], options: BlobPropertyBag): Blob
  createObjectUrl(blob: Blob): string
  revokeObjectUrl(url: string): void
  createDownloadAnchor(): DownloadAnchor
  appendDownloadAnchor(anchor: DownloadAnchor): void
  openPrintWindow(): PrintWindow | null
  hasSuperAdmin(): boolean
  confirmLargePrint(info: TradingLabReportPrintInfoResponse): boolean
  createTextDecoder(): TextDecoder
  yieldToBrowser(popup: PrintWindow): Promise<void>
}>

export type TradingLabReportTransfer = Readonly<{
  downloadReport(
    reportId: string,
    options?: TradingLabReportTransferOptions,
  ): Promise<void>
  printRawReport(
    reportId: string,
    options?: TradingLabReportTransferOptions,
  ): Promise<void>
}>

export class TradingLabReportTransferError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'TradingLabReportTransferError'
  }
}

export function createTradingLabReportTransfer(
  dependencies: TradingLabReportTransferDependencies,
): TradingLabReportTransfer {
  return {
    downloadReport: (reportId, options = {}) =>
      downloadWith(dependencies, reportId, options),
    printRawReport: (reportId, options = {}) =>
      printWith(dependencies, reportId, options),
  }
}

const browserTransfer = createTradingLabReportTransfer(
  browserDependencies(),
)

export function downloadReport(reportId: string): Promise<void>
export function downloadReport(
  reportId: string,
  options: TradingLabReportTransferOptions,
): Promise<void>
export function downloadReport(
  reportId: string,
  options: TradingLabReportTransferOptions = {},
) {
  return browserTransfer.downloadReport(reportId, options)
}

export function printRawReport(reportId: string): Promise<void>
export function printRawReport(
  reportId: string,
  options: TradingLabReportTransferOptions,
): Promise<void>
export function printRawReport(
  reportId: string,
  options: TradingLabReportTransferOptions = {},
) {
  return browserTransfer.printRawReport(reportId, options)
}

async function downloadWith(
  dependencies: TradingLabReportTransferDependencies,
  reportIdValue: string,
  options: TradingLabReportTransferOptions,
) {
  const reportId = canonicalReportId(reportIdValue)
  const pickerOptions = savePickerOptions(reportId)
  let selection: Promise<ReportFileHandle> | null
  try {
    selection = dependencies.pickSaveFile(pickerOptions)
  } catch (error) {
    if (isAbortError(error)) return
    throw error
  }

  if (selection !== null) {
    let handle: ReportFileHandle
    try {
      handle = await selection
    } catch (error) {
      if (isAbortError(error)) return
      throw error
    }
    await streamDownloadToFile(
      dependencies,
      reportId,
      handle,
      options.signal,
    )
    return
  }

  await streamDownloadToBlob(
    dependencies,
    reportId,
    options.signal,
  )
}

async function streamDownloadToFile(
  dependencies: TradingLabReportTransferDependencies,
  reportId: string,
  handle: ReportFileHandle,
  signal: AbortSignal | undefined,
) {
  throwIfAborted(signal)
  const metadata = requireReportMetadata(
    await dependencies.getReport(reportId),
    reportId,
  )
  throwIfAborted(signal)

  let writable: WritableStream<Uint8Array> | null = null
  let committed = false
  try {
    writable = await handle.createWritable()
    throwIfAborted(signal)
    const response = await dependencies.requestRaw(
      `/api/admin/trading-lab/reports/${reportId}/download`,
      {
        method: 'GET',
        headers: { Accept: DOWNLOAD_MIME },
        signal,
      },
    )
    await requireResponseStillWanted(response, signal)
    const body = await requireRawBody(response, DOWNLOAD_MIME)
    let receivedBytes = 0
    const counter = new TransformStream<Uint8Array, Uint8Array>({
      transform(chunk, controller) {
        requireByteChunk(chunk)
        receivedBytes += chunk.byteLength
        if (receivedBytes > metadata.uncompressedBytes) {
          throw byteMismatch()
        }
        controller.enqueue(chunk)
      },
    })

    await body
      .pipeThrough(counter)
      .pipeTo(writable, {
        preventClose: true,
        preventAbort: true,
        signal,
      })
    throwIfAborted(signal)
    if (receivedBytes !== metadata.uncompressedBytes) {
      throw byteMismatch()
    }
    await writable.close()
    committed = true
  } catch (error) {
    if (writable !== null && !committed) {
      await abortWritable(writable, error)
    }
    throw error
  }
}

async function streamDownloadToBlob(
  dependencies: TradingLabReportTransferDependencies,
  reportId: string,
  signal: AbortSignal | undefined,
) {
  throwIfAborted(signal)
  const metadata = requireReportMetadata(
    await dependencies.getReport(reportId),
    reportId,
  )
  throwIfAborted(signal)
  if (metadata.uncompressedBytes > TRADING_LAB_REPORT_BLOB_LIMIT_BYTES) {
    throw new TradingLabReportTransferError(
      'Trading Lab report is larger than the 50 MiB Blob fallback limit',
    )
  }

  const response = await dependencies.requestRaw(
    `/api/admin/trading-lab/reports/${reportId}/download`,
    {
      method: 'GET',
      headers: { Accept: DOWNLOAD_MIME },
      signal,
    },
  )
  await requireResponseStillWanted(response, signal)
  const body = await requireRawBody(response, DOWNLOAD_MIME)
  const chunks = await collectBoundedChunks(
    body,
    metadata.uncompressedBytes,
    signal,
  )
  throwIfAborted(signal)

  const blob = dependencies.createBlob(chunks, {
    type: DOWNLOAD_MIME,
  })
  const objectUrl = dependencies.createObjectUrl(blob)
  let anchor: DownloadAnchor | null = null
  try {
    anchor = dependencies.createDownloadAnchor()
    anchor.href = objectUrl
    anchor.download = reportFileName(reportId)
    anchor.rel = 'noopener'
    anchor.style.display = 'none'
    dependencies.appendDownloadAnchor(anchor)
    anchor.click()
  } finally {
    anchor?.remove()
    dependencies.revokeObjectUrl(objectUrl)
  }
}

async function collectBoundedChunks(
  body: ReadableStream<Uint8Array>,
  expectedBytes: number,
  signal: AbortSignal | undefined,
) {
  const reader = body.getReader()
  const chunks: BlobPart[] = []
  let receivedBytes = 0
  const cancel = () => {
    void reader.cancel(abortReason(signal)).catch(() => undefined)
  }
  signal?.addEventListener('abort', cancel, { once: true })
  try {
    while (true) {
      throwIfAborted(signal)
      const next = await reader.read()
      throwIfAborted(signal)
      if (next.done) break
      requireByteChunk(next.value)
      receivedBytes += next.value.byteLength
      if (
        receivedBytes > expectedBytes
        || receivedBytes > TRADING_LAB_REPORT_BLOB_LIMIT_BYTES
      ) {
        throw byteMismatch()
      }
      const retained = new Uint8Array(next.value.byteLength)
      retained.set(next.value)
      chunks.push(retained.buffer)
    }
    if (receivedBytes !== expectedBytes) {
      throw byteMismatch()
    }
    return chunks
  } catch (error) {
    await reader.cancel(error).catch(() => undefined)
    throw error
  } finally {
    signal?.removeEventListener('abort', cancel)
    reader.releaseLock()
  }
}

async function printWith(
  dependencies: TradingLabReportTransferDependencies,
  reportIdValue: string,
  options: TradingLabReportTransferOptions,
) {
  const reportId = canonicalReportId(reportIdValue)
  const popup = dependencies.openPrintWindow()
  if (popup === null) {
    throw new TradingLabReportTransferError(
      'Trading Lab print popup was blocked',
    )
  }

  const abort = linkedAbortController(options.signal)
  const pagehide = () => {
    abort.controller.abort(createAbortError('Print popup closed'))
  }
  popup.addEventListener('pagehide', pagehide)
  let printed = false
  try {
    const document = popup.document
    popup.opener = null
    const sink = createPrintTextSink(document)
    ensurePopupOpen(popup, abort.controller)

    const info = requirePrintInfo(
      await dependencies.getPrintInfo(reportId),
    )
    throwIfAborted(abort.controller.signal)
    ensurePopupOpen(popup, abort.controller)

    let confirmationToken: string | undefined
    if (info.requiresConfirmation) {
      if (!dependencies.hasSuperAdmin()) {
        throw new TradingLabReportTransferError(
          'SUPER_ADMIN authority is required for a large Trading Lab print',
        )
      }
      if (!dependencies.confirmLargePrint(info)) return
      const confirmation = requirePrintConfirmation(
        await dependencies.createPrintConfirmation(reportId),
        reportId,
      )
      throwIfAborted(abort.controller.signal)
      ensurePopupOpen(popup, abort.controller)
      confirmationToken = confirmation.token
    }

    popup.focus()
    const headers = new Headers({ Accept: PRINT_MIME })
    if (confirmationToken !== undefined) {
      headers.set(PRINT_CONFIRMATION_HEADER, confirmationToken)
    }
    const response = await dependencies.requestRaw(
      `/api/admin/trading-lab/reports/${reportId}/print`,
      {
        method: 'GET',
        headers,
        signal: abort.controller.signal,
      },
    )
    try {
      throwIfAborted(abort.controller.signal)
      ensurePopupOpen(popup, abort.controller)
    } catch (error) {
      await cancelResponseBody(response)
      throw error
    }
    const body = await requireRawBody(response, PRINT_MIME)
    await streamDecodedText(
      dependencies,
      popup,
      sink,
      body,
      abort.controller.signal,
    )
    throwIfAborted(abort.controller.signal)
    ensurePopupOpen(popup, abort.controller)
    sink.commit()
    popup.focus()
    sink.reveal()
    try {
      popup.print()
      printed = true
    } finally {
      sink.conceal()
    }
  } finally {
    popup.removeEventListener('pagehide', pagehide)
    abort.cleanup()
    if (!printed && !popup.closed) {
      popup.close()
    }
  }
}

function createPrintTextSink(document: PrintDocument) {
  const body = document.body
  if (body === null) {
    throw new TradingLabReportTransferError(
      'Trading Lab print popup document is unavailable',
    )
  }
  document.title = 'Trading Lab raw report'
  const container = document.createElement('pre')
  container.style.whiteSpace = 'pre-wrap'
  container.style.overflowWrap = 'anywhere'
  container.style.fontFamily = 'ui-monospace, monospace'
  container.style.fontSize = '12px'
  container.style.display = 'none'
  return {
    appendData(value: string) {
      container.append(document.createTextNode(value))
    },
    commit() {
      body.append(container)
    },
    reveal() {
      container.style.display = 'block'
    },
    conceal() {
      container.style.display = 'none'
    },
  }
}

async function streamDecodedText(
  dependencies: TradingLabReportTransferDependencies,
  popup: PrintWindow,
  sink: PrintTextNode,
  body: ReadableStream<Uint8Array>,
  signal: AbortSignal,
) {
  const reader = body.getReader()
  const decoder = dependencies.createTextDecoder()
  const jsonCompletion = createPrintJsonCompletionScanner()
  let pendingText = ''
  let appendedUnitsSinceYield = 0
  const cancel = () => {
    void reader.cancel(abortReason(signal)).catch(() => undefined)
  }
  signal.addEventListener('abort', cancel, { once: true })
  try {
    while (true) {
      throwIfAborted(signal)
      ensurePopupOpen(popup)
      const next = await reader.read()
      throwIfAborted(signal)
      ensurePopupOpen(popup)
      if (next.done) break
      requireByteChunk(next.value)
      const decoded = decoder.decode(next.value, { stream: true })
      jsonCompletion.accept(decoded)
      const nextBuffer = await appendBufferedText(
        sink,
        pendingText + decoded,
        dependencies.yieldToBrowser,
        popup,
        signal,
        appendedUnitsSinceYield,
        false,
      )
      pendingText = nextBuffer.pendingText
      appendedUnitsSinceYield = nextBuffer.appendedUnitsSinceYield
    }
    const finalDecoded = decoder.decode()
    jsonCompletion.accept(finalDecoded)
    const finalBuffer = await appendBufferedText(
      sink,
      pendingText + finalDecoded,
      dependencies.yieldToBrowser,
      popup,
      signal,
      appendedUnitsSinceYield,
      true,
    )
    pendingText = finalBuffer.pendingText
    appendedUnitsSinceYield = finalBuffer.appendedUnitsSinceYield
    if (
      pendingText.length !== 0
      || appendedUnitsSinceYield !== 0
    ) {
      throw new TradingLabReportTransferError(
        'Trading Lab print text buffer did not reach EOF',
      )
    }
    jsonCompletion.finish()
  } catch (error) {
    await reader.cancel(error).catch(() => undefined)
    throw error
  } finally {
    signal.removeEventListener('abort', cancel)
    reader.releaseLock()
  }
}

function createPrintJsonCompletionScanner() {
  const containers = new Uint8Array(MAX_PRINT_JSON_DEPTH)
  let depth = 0
  let started = false
  let complete = false
  let inString = false
  let escaped = false
  const invalid = () => new TradingLabReportTransferError(
    'Trading Lab print JSON response is incomplete or invalid',
  )
  const open = (container: number) => {
    if (depth >= containers.length) throw invalid()
    containers[depth] = container
    depth += 1
  }
  return {
    accept(value: string) {
      for (let index = 0; index < value.length; index += 1) {
        const code = value.charCodeAt(index)
        if (complete) {
          if (!isJsonWhitespace(code)) throw invalid()
          continue
        }
        if (!started) {
          if (isJsonWhitespace(code)) continue
          if (code !== 0x7b) throw invalid()
          started = true
          open(0x7b)
          continue
        }
        if (inString) {
          if (escaped) {
            escaped = false
          } else if (code === 0x5c) {
            escaped = true
          } else if (code === 0x22) {
            inString = false
          } else if (code <= 0x1f) {
            throw invalid()
          }
          continue
        }
        if (code === 0x22) {
          inString = true
          continue
        }
        if (code === 0x7b || code === 0x5b) {
          open(code)
          continue
        }
        if (code !== 0x7d && code !== 0x5d) continue
        const expected = code === 0x7d ? 0x7b : 0x5b
        if (depth === 0 || containers[depth - 1] !== expected) {
          throw invalid()
        }
        depth -= 1
        if (depth === 0) complete = true
      }
    },
    finish() {
      if (
        !started
        || !complete
        || depth !== 0
        || inString
        || escaped
      ) {
        throw invalid()
      }
    },
  }
}

function isJsonWhitespace(code: number) {
  return code === 0x20 || code === 0x0a || code === 0x0d || code === 0x09
}

async function appendBufferedText(
  sink: PrintTextNode,
  value: string,
  yieldToBrowser: (popup: PrintWindow) => Promise<void>,
  popup: PrintWindow,
  signal: AbortSignal,
  initialAppendedUnitsSinceYield: number,
  flushRemainder: boolean,
): Promise<Readonly<{
  pendingText: string
  appendedUnitsSinceYield: number
}>> {
  let offset = 0
  let appendedUnitsSinceYield = initialAppendedUnitsSinceYield
  while (
    value.length - offset >= MAX_TEXT_APPEND_UNITS
    || (flushRemainder && offset < value.length)
  ) {
    let end = Math.min(offset + MAX_TEXT_APPEND_UNITS, value.length)
    if (
      end < value.length
      && end > offset
      && isHighSurrogate(value.charCodeAt(end - 1))
    ) {
      end -= 1
    }
    const appendedUnits = end - offset
    sink.appendData(value.slice(offset, end))
    offset = end
    appendedUnitsSinceYield += appendedUnits
    if (appendedUnitsSinceYield >= TEXT_YIELD_INTERVAL_UNITS) {
      await yieldToBrowserOrAbort(
        () => yieldToBrowser(popup),
        signal,
      )
      ensurePopupOpen(popup)
      appendedUnitsSinceYield = 0
    }
  }
  if (flushRemainder && appendedUnitsSinceYield > 0) {
    await yieldToBrowserOrAbort(
      () => yieldToBrowser(popup),
      signal,
    )
    ensurePopupOpen(popup)
    appendedUnitsSinceYield = 0
  }
  return {
    pendingText: value.slice(offset),
    appendedUnitsSinceYield,
  }
}

function yieldToBrowserOrAbort(
  yieldToBrowser: () => Promise<void>,
  signal: AbortSignal,
): Promise<void> {
  throwIfAborted(signal)
  return new Promise<void>((resolve, reject) => {
    let settled = false
    const finish = (callback: () => void) => {
      if (settled) return
      settled = true
      signal.removeEventListener('abort', onAbort)
      callback()
    }
    const onAbort = () => {
      finish(() => reject(abortReason(signal)))
    }
    signal.addEventListener('abort', onAbort, { once: true })
    if (signal.aborted) {
      onAbort()
      return
    }
    Promise.resolve()
      .then(yieldToBrowser)
      .then(
        () => finish(resolve),
        (error: unknown) => finish(() => reject(error)),
      )
  })
}

export function yieldToPrintPopup(
  popup: Pick<
    PrintWindow,
    'requestAnimationFrame' | 'cancelAnimationFrame'
  >,
): Promise<void> {
  return new Promise((resolve) => {
    let settled = false
    let frameId: number | undefined
    let timerId: ReturnType<typeof setTimeout>
    let channel: MessageChannel | undefined
    const finish = () => {
      if (settled) return
      settled = true
      clearTimeout(timerId)
      channel?.port1.close()
      channel?.port2.close()
      if (
        frameId !== undefined
        && typeof popup.cancelAnimationFrame === 'function'
      ) {
        try {
          popup.cancelAnimationFrame(frameId)
        } catch {
          // The fallback task already made forward progress.
        }
      }
      resolve()
    }
    timerId = setTimeout(finish, 0)
    try {
      channel = new MessageChannel()
      channel.port1.onmessage = () => finish()
      channel.port2.postMessage(undefined)
    } catch {
      // The timer and popup frame remain bounded fallbacks.
    }
    if (typeof popup.requestAnimationFrame === 'function') {
      try {
        frameId = popup.requestAnimationFrame(finish)
      } catch {
        // A throttled or unavailable frame still settles through the timer.
      }
    }
  })
}

function requireReportMetadata(
  metadata: ReportMetadata,
  reportId: string,
) {
  if (
    metadata === null
    || typeof metadata !== 'object'
    || metadata.id !== reportId
  ) {
    throw new TradingLabReportTransferError(
      'Trading Lab report metadata identity mismatch',
    )
  }
  requireByteCount(metadata.uncompressedBytes)
  return metadata
}

function requirePrintInfo(info: TradingLabReportPrintInfoResponse) {
  if (info === null || typeof info !== 'object') {
    throw invalidPrintInfo()
  }
  requireByteCount(info.uncompressedBytes)
  requireByteCount(info.estimatedPageCount)
  if (
    info.thresholdBytes !== TRADING_LAB_REPORT_PRINT_THRESHOLD_BYTES
    || info.requiresConfirmation
      !== (
        info.uncompressedBytes
        > TRADING_LAB_REPORT_PRINT_THRESHOLD_BYTES
      )
  ) {
    throw invalidPrintInfo()
  }
  return info
}

function requirePrintConfirmation(
  confirmation: TradingLabReportPrintConfirmationResponse,
  reportId: string,
) {
  if (
    confirmation === null
    || typeof confirmation !== 'object'
    || confirmation.reportId !== reportId
    || typeof confirmation.token !== 'string'
    || confirmation.token.trim().length === 0
    || confirmation.token.length > MAX_CONFIRMATION_TOKEN_LENGTH
  ) {
    throw new TradingLabReportTransferError(
      'Trading Lab print confirmation is invalid',
    )
  }
  return confirmation
}

async function requireRawBody(
  response: Response,
  expectedMime: string,
) {
  if (response.status !== 200) {
    await cancelResponseBody(response)
    throw new TradingLabReportTransferError(
      `Trading Lab raw report request failed with HTTP ${response.status}`,
    )
  }
  const contentType = normalizedContentType(
    response.headers.get('Content-Type'),
  )
  const matches = expectedMime === DOWNLOAD_MIME
    ? (
        contentType === DOWNLOAD_MIME
        || contentType.startsWith(`${DOWNLOAD_MIME};`)
      )
    : contentType === PRINT_MIME
  if (!matches || response.body === null) {
    await cancelResponseBody(response)
    throw new TradingLabReportTransferError(
      'Trading Lab raw report response type or body is invalid',
    )
  }
  return response.body
}

async function cancelResponseBody(response: Response) {
  try {
    await response.body?.cancel()
  } catch {
    // The original status/contract failure remains authoritative.
  }
}

async function requireResponseStillWanted(
  response: Response,
  signal: AbortSignal | undefined,
) {
  try {
    throwIfAborted(signal)
  } catch (error) {
    await cancelResponseBody(response)
    throw error
  }
}

async function abortWritable(
  writable: WritableStream<Uint8Array>,
  reason: unknown,
) {
  try {
    await writable.abort(reason)
  } catch {
    // Preserve the stream failure that caused the abort.
  }
}

function browserDependencies(): TradingLabReportTransferDependencies {
  return {
    getReport: (reportId) =>
      getTradingLabReport(reportId, requireCurrentToken()),
    getPrintInfo: (reportId) =>
      getTradingLabReportPrintInfo(reportId, requireCurrentToken()),
    createPrintConfirmation: (reportId) =>
      createTradingLabReportPrintConfirmation(
        reportId,
        requireCurrentToken(),
      ),
    requestRaw: apiRaw,
    pickSaveFile: (options) => {
      const browser = globalThis as typeof globalThis & {
        showSaveFilePicker?: (
          pickerOptions: SavePickerOptions,
        ) => Promise<ReportFileHandle>
      }
      const picker = browser.showSaveFilePicker
      return typeof picker === 'function'
        ? picker.call(browser, options)
        : null
    },
    createBlob: (parts, options) => new Blob(parts, options),
    createObjectUrl: (blob) => URL.createObjectURL(blob),
    revokeObjectUrl: (url) => URL.revokeObjectURL(url),
    createDownloadAnchor: () => {
      if (typeof document === 'undefined') {
        throw new TradingLabReportTransferError(
          'Trading Lab download document is unavailable',
        )
      }
      return document.createElement('a')
    },
    appendDownloadAnchor: (anchor) => {
      if (typeof document === 'undefined' || document.body === null) {
        throw new TradingLabReportTransferError(
          'Trading Lab download document is unavailable',
        )
      }
      document.body.append(anchor as HTMLAnchorElement)
    },
    openPrintWindow: () => {
      if (typeof window === 'undefined') return null
      return window.open('', '_blank') as unknown as PrintWindow | null
    },
    hasSuperAdmin: () => hasAdminAuthority('SUPER_ADMIN'),
    confirmLargePrint: (info) => {
      if (typeof window === 'undefined') return false
      return window.confirm(
        `报告约 ${info.estimatedPageCount} 页且超过 50 MiB，确认继续打印？`,
      )
    },
    createTextDecoder: () => new TextDecoder('utf-8', { fatal: true }),
    yieldToBrowser: yieldToPrintPopup,
  }
}

function canonicalReportId(value: string) {
  if (typeof value !== 'string' || !UUID_PATTERN.test(value)) {
    throw new TradingLabReportTransferError(
      'Report ID must be a canonical lowercase UUID',
    )
  }
  return value
}

function requireCurrentToken() {
  const token = getValidAdminToken()
  if (token === null) {
    throw new TradingLabReportTransferError(
      'Trading Lab admin session is unavailable',
    )
  }
  return token
}

function requireByteCount(value: number) {
  if (
    typeof value !== 'number'
    || !Number.isSafeInteger(value)
    || value < 0
  ) {
    throw new TradingLabReportTransferError(
      'Trading Lab report byte size is invalid',
    )
  }
}

function requireByteChunk(value: unknown): asserts value is Uint8Array {
  if (!(value instanceof Uint8Array)) {
    throw new TradingLabReportTransferError(
      'Trading Lab raw report stream emitted a non-byte chunk',
    )
  }
}

function savePickerOptions(reportId: string): SavePickerOptions {
  return {
    suggestedName: reportFileName(reportId),
    excludeAcceptAllOption: true,
    types: [{
      description: 'JSON report',
      accept: {
        [DOWNLOAD_MIME]: ['.json'],
      },
    }],
  }
}

function reportFileName(reportId: string) {
  return `trading-lab-report-${reportId}.json`
}

function normalizedContentType(value: string | null) {
  if (value === null) return ''
  return value.toLowerCase().replace(/\s+/g, '')
}

function byteMismatch() {
  return new TradingLabReportTransferError(
    'Trading Lab report byte size does not match metadata',
  )
}

function invalidPrintInfo() {
  return new TradingLabReportTransferError(
    'Trading Lab print information is inconsistent',
  )
}

function isAbortError(value: unknown) {
  return value !== null
    && typeof value === 'object'
    && 'name' in value
    && value.name === 'AbortError'
}

function createAbortError(message: string) {
  if (typeof DOMException !== 'undefined') {
    return new DOMException(message, 'AbortError')
  }
  const error = new Error(message)
  error.name = 'AbortError'
  return error
}

function throwIfAborted(signal: AbortSignal | undefined) {
  if (!signal?.aborted) return
  throw abortReason(signal)
}

function abortReason(signal: AbortSignal | undefined) {
  return signal?.reason ?? createAbortError('Trading Lab transfer aborted')
}

function ensurePopupOpen(
  popup: PrintWindow,
  controller?: AbortController,
) {
  if (!popup.closed) return
  const error = createAbortError('Trading Lab print popup closed')
  controller?.abort(error)
  throw error
}

function linkedAbortController(parent: AbortSignal | undefined) {
  const controller = new AbortController()
  const propagate = () => controller.abort(abortReason(parent))
  if (parent?.aborted) {
    propagate()
  } else {
    parent?.addEventListener('abort', propagate, { once: true })
  }
  return {
    controller,
    cleanup() {
      parent?.removeEventListener('abort', propagate)
    },
  }
}

function isHighSurrogate(code: number) {
  return code >= 0xd800 && code <= 0xdbff
}
