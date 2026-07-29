#!/usr/bin/env node

import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { TextDecoder } from 'node:util'

const DEFAULT_MAX_HEAP_DELTA_BYTES = 64 * 1024 * 1024
const MAX_JSON_DEPTH = 68
const MAX_SCHEMA_KEY_LENGTH = 4096
const MAX_CAPTURED_URL_LENGTH = 8192
const MAX_NUMBER_TOKEN_LENGTH = 256
const REAL_HTTP_CATEGORY_RULES = [
  ['validation-run', ['/internal/validation/runs']],
  ['orders', ['/api/trading/orders']],
  ['wallet', ['/wallet-balances']],
  ['ledger', ['/asset-ledger', '/api/ledger']],
]
const NUMBER = /^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$/
const INTEGER = /^-?(?:0|[1-9]\d*)$/
const HEX = /^[0-9a-fA-F]$/
const RFC3339_DATE_TIME =
  /^(\d{4})-(\d{2})-(\d{2})[Tt](\d{2}):(\d{2}):(\d{2})(?:\.\d+)?(?:[Zz]|([+-])(\d{2}):(\d{2}))$/

export async function loadTradingLabReportSchema(pathOrUrl) {
  let text
  try {
    text = await readFile(pathOrUrl, 'utf8')
  } catch (error) {
    throw failure(`cannot read schema: ${error.message}`)
  }

  let schema
  try {
    schema = JSON.parse(text)
  } catch (error) {
    throw failure(`schema is not JSON: ${error.message}`)
  }
  requireReportSchema(schema)
  return schema
}

export async function checkTradingLabReportStream(
  readable,
  {
    schema,
    minBytes = 0,
    maxHeapDeltaBytes = DEFAULT_MAX_HEAP_DELTA_BYTES,
    maxRssDeltaBytes = Number.POSITIVE_INFINITY,
  } = {},
) {
  if (!readable || typeof readable[Symbol.asyncIterator] !== 'function') {
    throw new TypeError('Trading Lab report input must be an async byte stream')
  }
  requireReportSchema(schema)
  requireBound('minBytes', minBytes, true)
  requireBound('maxHeapDeltaBytes', maxHeapDeltaBytes, false)
  requireBound('maxRssDeltaBytes', maxRssDeltaBytes, false)

  const parser = new IncrementalSchemaParser(schema)
  const decoder = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true })
  const baseline = process.memoryUsage()
  let peakHeapUsedBytes = baseline.heapUsed
  let peakRssBytes = baseline.rss
  let totalBytes = 0
  let largestInputChunkBytes = 0
  const prefix = []

  const sampleMemory = () => {
    const current = process.memoryUsage()
    peakHeapUsedBytes = Math.max(peakHeapUsedBytes, current.heapUsed)
    peakRssBytes = Math.max(peakRssBytes, current.rss)
    const heapDelta = Math.max(0, peakHeapUsedBytes - baseline.heapUsed)
    const rssDelta = Math.max(0, peakRssBytes - baseline.rss)
    if (heapDelta > maxHeapDeltaBytes) {
      throw failure(
        `peak heap delta ${heapDelta} exceeds ${maxHeapDeltaBytes}`,
      )
    }
    if (rssDelta > maxRssDeltaBytes) {
      throw failure(
        `peak RSS delta ${rssDelta} exceeds ${maxRssDeltaBytes}`,
      )
    }
  }

  try {
    for await (const value of readable) {
      const bytes = Buffer.isBuffer(value)
        ? value
        : value instanceof Uint8Array
          ? Buffer.from(value.buffer, value.byteOffset, value.byteLength)
          : Buffer.from(String(value), 'utf8')
      if (bytes.length === 0) {
        continue
      }
      if (totalBytes > Number.MAX_SAFE_INTEGER - bytes.length) {
        throw failure('byte count exceeds the safe integer range')
      }
      totalBytes += bytes.length
      largestInputChunkBytes = Math.max(largestInputChunkBytes, bytes.length)
      for (let index = 0; index < bytes.length && prefix.length < 3; index += 1) {
        prefix.push(bytes[index])
      }
      if (prefix.length === 3
          && prefix[0] === 0xef
          && prefix[1] === 0xbb
          && prefix[2] === 0xbf) {
        throw failure('UTF-8 BOM is not allowed')
      }

      let text
      try {
        text = decoder.decode(bytes, { stream: true })
      } catch {
        throw failure('input is not strict UTF-8')
      }
      parser.feed(text)
      sampleMemory()
    }

    let tail
    try {
      tail = decoder.decode()
    } catch {
      throw failure('input is not strict UTF-8')
    }
    parser.feed(tail)
    parser.finish()
    sampleMemory()
  } catch (error) {
    if (error?.name === 'TradingLabReportCheckError') {
      throw error
    }
    throw failure(error?.message ?? String(error))
  }

  if (totalBytes < minBytes) {
    throw failure(
      `report has ${totalBytes} bytes; minimum is ${minBytes}`,
    )
  }
  const peakHeapDeltaBytes = Math.max(
    0,
    peakHeapUsedBytes - baseline.heapUsed,
  )
  const peakRssDeltaBytes = Math.max(0, peakRssBytes - baseline.rss)
  if (peakHeapDeltaBytes > maxHeapDeltaBytes) {
    throw failure(
      `peak heap delta ${peakHeapDeltaBytes} exceeds ${maxHeapDeltaBytes}`,
    )
  }
  if (peakRssDeltaBytes > maxRssDeltaBytes) {
    throw failure(
      `peak RSS delta ${peakRssDeltaBytes} exceeds ${maxRssDeltaBytes}`,
    )
  }

  return {
    status: 'PASS',
    totalBytes,
    largestInputChunkBytes,
    baselineHeapUsedBytes: baseline.heapUsed,
    peakHeapUsedBytes,
    peakHeapDeltaBytes,
    baselineRssBytes: baseline.rss,
    peakRssBytes,
    peakRssDeltaBytes,
    topLevelSections: parser.topLevelSections,
    apiTraceItems: parser.apiTraceItems,
    realHttpCategories: REAL_HTTP_CATEGORY_RULES
      .map(([category]) => category)
      .filter((category) => parser.realHttpCategories.has(category)),
    base64Wrapper: false,
  }
}

class IncrementalSchemaParser {
  constructor(schema) {
    this.contracts = buildContracts(schema)
    this.stack = []
    this.mode = 'normal'
    this.rootStarted = false
    this.rootComplete = false
    this.string = null
    this.number = ''
    this.numberSpec = null
    this.literal = null
    this.literalIndex = 0
    this.literalSpec = null
    this.unicode = ''
    this.topLevelSections = []
    this.apiTraceItems = 0
    this.realHttpCategories = new Set()
  }

  feed(text) {
    let index = 0
    while (index < text.length) {
      const character = text[index]
      if (this.mode === 'string') {
        if (character === '"') {
          this.finishString()
          index += 1
        } else if (character === '\\') {
          this.mode = 'escape'
          index += 1
        } else {
          if (character.charCodeAt(0) < 0x20) {
            throw failure('unescaped control character in JSON string')
          }
          this.appendString(character)
          index += 1
        }
        continue
      }
      if (this.mode === 'escape') {
        if (character === 'u') {
          this.unicode = ''
          this.mode = 'unicode'
        } else {
          const escaped = {
            '"': '"',
            '\\': '\\',
            '/': '/',
            b: '\b',
            f: '\f',
            n: '\n',
            r: '\r',
            t: '\t',
          }[character]
          if (escaped === undefined) {
            throw failure('invalid JSON string escape')
          }
          this.appendString(escaped)
          this.mode = 'string'
        }
        index += 1
        continue
      }
      if (this.mode === 'unicode') {
        if (!HEX.test(character)) {
          throw failure('invalid JSON unicode escape')
        }
        this.unicode += character
        index += 1
        if (this.unicode.length === 4) {
          this.appendString(String.fromCharCode(Number.parseInt(this.unicode, 16)))
          this.mode = 'string'
        }
        continue
      }
      if (this.mode === 'number') {
        if (/[0-9eE+.-]/.test(character)) {
          if (this.number.length >= MAX_NUMBER_TOKEN_LENGTH) {
            throw failure('JSON number token exceeds the bounded checker limit')
          }
          this.number += character
          index += 1
          continue
        }
        this.finishNumber()
        continue
      }
      if (this.mode === 'literal') {
        if (character !== this.literal[this.literalIndex]) {
          throw failure(`invalid JSON literal near ${this.literal}`)
        }
        this.literalIndex += 1
        index += 1
        if (this.literalIndex === this.literal.length) {
          this.validateScalar(
            this.literalSpec,
            this.literal === 'null' ? 'null' : 'boolean',
            this.literal === 'true'
              ? true
              : this.literal === 'false'
                ? false
                : null,
          )
          this.mode = 'normal'
          this.literal = null
          this.literalSpec = null
        }
        continue
      }

      if (isWhitespace(character)) {
        index += 1
        continue
      }
      if (this.rootComplete) {
        throw failure('bytes remain after the single JSON root')
      }
      if (character === '{' || character === '[') {
        this.beginComposite(character === '{' ? 'object' : 'array')
        index += 1
        continue
      }
      if (character === '}' || character === ']') {
        this.closeComposite(character === '}' ? 'object' : 'array')
        index += 1
        continue
      }
      if (character === ',') {
        this.acceptComma()
        index += 1
        continue
      }
      if (character === ':') {
        this.acceptColon()
        index += 1
        continue
      }
      if (character === '"') {
        this.beginString()
        index += 1
        continue
      }
      if (character === '-' || /[0-9]/.test(character)) {
        this.numberSpec = this.beginScalar('number')
        this.number = character
        this.mode = 'number'
        index += 1
        continue
      }
      if (character === 't' || character === 'f' || character === 'n') {
        this.literal = character === 't'
          ? 'true'
          : character === 'f'
            ? 'false'
            : 'null'
        this.literalIndex = 1
        this.literalSpec = this.beginScalar(
          character === 'n' ? 'null' : 'boolean',
        )
        this.mode = 'literal'
        index += 1
        continue
      }
      throw failure(`invalid JSON token ${JSON.stringify(character)}`)
    }
  }

  finish() {
    if (this.mode === 'number') {
      this.finishNumber()
    } else if (this.mode !== 'normal') {
      throw failure('JSON ends inside an incomplete token')
    }
    if (!this.rootComplete || this.stack.length !== 0) {
      throw failure('JSON ends before the single root is complete')
    }
  }

  beginString() {
    const context = this.stack.at(-1)
    const isKey = context?.type === 'object'
      && (context.expect === 'firstKeyOrEnd' || context.expect === 'key')
    if (isKey) {
      this.string = {
        isKey: true,
        collect: context.contract !== null,
        captureUrl: false,
        value: '',
        length: 0,
        spec: null,
      }
    } else {
      const captureUrl = context?.type === 'object'
        && ['apiTraceItem', 'apiTraceRequestBody'].includes(context.tracker)
        && context.currentKey === 'url'
      const spec = this.beginScalar('string')
      this.string = {
        isKey: false,
        collect: Boolean(
          captureUrl
          || spec?.enum
          || spec?.minLength !== undefined
          || spec?.maxLength !== undefined
          || spec?.format !== undefined,
        ),
        captureUrl,
        value: '',
        length: 0,
        spec,
      }
    }
    this.mode = 'string'
  }

  appendString(character) {
    this.string.length += character.length
    if (!this.string.collect) {
      return
    }
    const maximum = this.string.captureUrl
      ? MAX_CAPTURED_URL_LENGTH
      : MAX_SCHEMA_KEY_LENGTH
    if (this.string.value.length + character.length > maximum) {
      throw failure('captured JSON string exceeds the bounded checker limit')
    }
    this.string.value += character
  }

  finishString() {
    const token = this.string
    this.string = null
    this.mode = 'normal'
    if (token.isKey) {
      this.acceptKey(token.value)
      return
    }
    this.validateScalar(token.spec, 'string', token.value, token.length)
    if (token.captureUrl) {
      this.recordRealHttpCategories(token.value)
    }
  }

  recordRealHttpCategories(url) {
    for (const [category, fragments] of REAL_HTTP_CATEGORY_RULES) {
      if (fragments.some((fragment) => url.includes(fragment))) {
        this.realHttpCategories.add(category)
      }
    }
  }

  beginScalar(type) {
    const value = this.beginValue(type)
    if (value.tracker !== null) {
      throw failure(`schema requires ${type} value to be a container`)
    }
    return value.spec
  }

  finishNumber() {
    if (!NUMBER.test(this.number)) {
      throw failure(`invalid JSON number ${this.number}`)
    }
    this.validateScalar(this.numberSpec, 'number', this.number)
    this.number = ''
    this.numberSpec = null
    this.mode = 'normal'
  }

  beginComposite(type) {
    if (this.stack.length >= MAX_JSON_DEPTH) {
      throw failure(`JSON depth exceeds ${MAX_JSON_DEPTH}`)
    }
    const value = this.beginValue(type)
    const contract = value.tracker === null
      ? null
      : this.contracts[value.tracker]
    if (value.tracker === 'apiTraceItem') {
      this.apiTraceItems += 1
    }
    this.stack.push({
      type,
      tracker: value.tracker,
      contract,
      expect: type === 'object' ? 'firstKeyOrEnd' : 'firstValueOrEnd',
      currentKey: null,
      seen: contract === null ? null : new Set(),
    })
  }

  closeComposite(type) {
    const context = this.stack.at(-1)
    if (!context || context.type !== type) {
      throw failure(`unexpected JSON ${type === 'object' ? '}' : ']'}`)
    }
    const validEnd = type === 'object'
      ? context.expect === 'firstKeyOrEnd' || context.expect === 'commaOrEnd'
      : context.expect === 'firstValueOrEnd' || context.expect === 'commaOrEnd'
    if (!validEnd) {
      throw failure(`JSON ${type} ends before a value is complete`)
    }
    if (context.contract !== null) {
      const missing = context.contract.required.filter(
        (key) => !context.seen.has(key),
      )
      if (missing.length > 0) {
        throw failure(
          `${context.contract.label} is missing required property ${missing[0]}`,
        )
      }
    }
    this.stack.pop()
    if (this.stack.length === 0) {
      this.rootComplete = true
    }
  }

  acceptKey(key) {
    const context = this.stack.at(-1)
    if (!context || context.type !== 'object'
        || !['firstKeyOrEnd', 'key'].includes(context.expect)) {
      throw failure('JSON object key appears in the wrong position')
    }
    if (context.contract !== null) {
      const known = Object.hasOwn(context.contract.properties, key)
      if (!known && context.contract.additionalProperties === false) {
        throw failure(
          `${context.contract.label} has unknown property ${JSON.stringify(key)}`,
        )
      }
      if (context.seen.has(key)) {
        throw failure(
          `${context.contract.label} repeats property ${JSON.stringify(key)}`,
        )
      }
      if (context.tracker === 'root') {
        const expected = context.contract.required[context.seen.size]
        if (key !== expected) {
          throw failure(
            `report section order expected ${JSON.stringify(expected)}`
              + ` but received ${JSON.stringify(key)}`,
          )
        }
      }
      context.seen.add(key)
      if (context.tracker === 'root') {
        this.topLevelSections.push(key)
      }
    }
    context.currentKey = key
    context.expect = 'colon'
  }

  acceptColon() {
    const context = this.stack.at(-1)
    if (!context || context.type !== 'object' || context.expect !== 'colon') {
      throw failure('unexpected JSON colon')
    }
    context.expect = 'value'
  }

  acceptComma() {
    const context = this.stack.at(-1)
    if (!context || context.expect !== 'commaOrEnd') {
      throw failure('unexpected JSON comma')
    }
    context.expect = context.type === 'object' ? 'key' : 'value'
  }

  beginValue(type) {
    if (this.stack.length === 0) {
      if (this.rootStarted) {
        throw failure('input contains more than a single JSON root')
      }
      this.rootStarted = true
      const spec = this.contracts.root.schema
      this.requireType(spec, type, 'report root')
      return { spec, tracker: type === 'object' ? 'root' : null }
    }

    const parent = this.stack.at(-1)
    const expectsValue = parent.type === 'object'
      ? parent.expect === 'value'
      : parent.expect === 'firstValueOrEnd' || parent.expect === 'value'
    if (!expectsValue) {
      throw failure(`JSON ${type} value appears in the wrong position`)
    }

    let spec = null
    let key = null
    if (parent.type === 'object') {
      key = parent.currentKey
      if (parent.contract !== null) {
        spec = parent.contract.properties[key] ?? null
      }
      parent.currentKey = null
    } else if (parent.tracker === 'apiTraceArray') {
      spec = this.contracts.apiTraceItem.schema
    }
    this.requireType(spec, type, key ?? parent.tracker ?? 'array item')
    parent.expect = 'commaOrEnd'

    let tracker = null
    if (type === 'array'
        && parent.tracker === 'root'
        && key === 'apiTrace') {
      tracker = 'apiTraceArray'
    } else if (type === 'object' && parent.tracker === 'apiTraceArray') {
      tracker = 'apiTraceItem'
    } else if (type === 'object'
        && parent.tracker === 'apiTraceItem'
        && key === 'requestBody') {
      tracker = 'apiTraceRequestBody'
    } else if (type === 'object'
        && parent.tracker === 'apiTraceItem'
        && key === 'responseBody') {
      tracker = 'apiTraceResponseBody'
    }
    return { spec, tracker }
  }

  requireType(spec, actual, label) {
    if (spec === null || spec === undefined || spec.$ref || spec.oneOf) {
      return
    }
    const expected = Array.isArray(spec.type)
      ? spec.type
      : spec.type
        ? [spec.type]
        : null
    if (expected !== null
        && !expected.includes(actual)
        && !(actual === 'number' && expected.includes('integer'))) {
      throw failure(
        `${label} has schema type ${actual}; expected ${expected.join(' or ')}`,
      )
    }
    if (spec.enum) {
      const enumTypes = new Set(spec.enum.map((value) => jsonType(value)))
      if (!enumTypes.has(actual)) {
        throw failure(`${label} has a value outside its schema enum`)
      }
    }
  }

  validateScalar(spec, type, value, stringLength = null) {
    this.requireType(spec, type, 'JSON scalar')
    if (spec?.enum && !spec.enum.includes(value)) {
      throw failure(`JSON scalar ${JSON.stringify(value)} is outside its schema enum`)
    }
    if (type === 'string') {
      if (spec?.minLength !== undefined && stringLength < spec.minLength) {
        throw failure('JSON string is shorter than its schema minimum')
      }
      if (spec?.maxLength !== undefined && stringLength > spec.maxLength) {
        throw failure('JSON string is longer than its schema maximum')
      }
      if (spec?.format === 'date-time' && !isRfc3339DateTime(value)) {
        throw failure('JSON string is not a valid RFC 3339 date-time')
      }
    }
    if (type === 'number') {
      if (schemaTypes(spec).includes('integer') && !INTEGER.test(value)) {
        throw failure('JSON number is not a schema integer')
      }
      if (spec?.minimum !== undefined || spec?.maximum !== undefined) {
        const numeric = Number(value)
        if (!Number.isFinite(numeric)
            || (spec.minimum !== undefined && numeric < spec.minimum)
            || (spec.maximum !== undefined && numeric > spec.maximum)) {
          throw failure('JSON number is outside its schema bounds')
        }
      }
    }
  }
}

function buildContracts(schema) {
  const apiTrace = schema.$defs.apiTrace
  const requestBody = apiTrace.properties.requestBody
  const responseBody = apiTrace.properties.responseBody
  return {
    root: contract('report root', schema),
    apiTraceArray: null,
    apiTraceItem: contract('apiTrace item', apiTrace),
    apiTraceRequestBody: contract('apiTrace requestBody', requestBody),
    apiTraceResponseBody: contract('apiTrace responseBody', responseBody),
  }
}

function contract(label, schema) {
  return {
    label,
    schema,
    properties: schema.properties ?? {},
    required: schema.required ?? [],
    additionalProperties: schema.additionalProperties,
  }
}

function requireReportSchema(schema) {
  if (!schema || typeof schema !== 'object' || Array.isArray(schema)
      || schema.type !== 'object'
      || schema.additionalProperties !== false
      || !Array.isArray(schema.required)
      || !schema.properties
      || typeof schema.properties !== 'object'
      || !schema.$defs?.apiTrace) {
    throw failure('schema is not the fixed Trading Lab report schema')
  }
  const propertyKeys = Object.keys(schema.properties)
  if (schema.required.length !== propertyKeys.length
      || new Set(schema.required).size !== schema.required.length
      || schema.required.some((key) => !Object.hasOwn(schema.properties, key))) {
    throw failure('schema root properties and required sections differ')
  }
  const apiTrace = schema.$defs.apiTrace
  if (apiTrace.type !== 'object'
      || apiTrace.additionalProperties !== false
      || !Array.isArray(apiTrace.required)
      || !apiTrace.properties?.requestBody
      || !apiTrace.properties?.responseBody) {
    throw failure('schema API trace contract is incomplete')
  }
}

function schemaTypes(spec) {
  if (!spec?.type) {
    return []
  }
  return Array.isArray(spec.type) ? spec.type : [spec.type]
}

function jsonType(value) {
  if (value === null) {
    return 'null'
  }
  if (Array.isArray(value)) {
    return 'array'
  }
  if (typeof value === 'number') {
    return Number.isInteger(value) ? 'integer' : 'number'
  }
  return typeof value
}

function isWhitespace(character) {
  return character === ' '
    || character === '\n'
    || character === '\r'
    || character === '\t'
}

function isRfc3339DateTime(value) {
  const match = RFC3339_DATE_TIME.exec(value)
  if (match === null) {
    return false
  }
  const year = Number(match[1])
  const month = Number(match[2])
  const day = Number(match[3])
  const hour = Number(match[4])
  const minute = Number(match[5])
  const second = Number(match[6])
  const offsetHour = match[8] === undefined ? 0 : Number(match[8])
  const offsetMinute = match[9] === undefined ? 0 : Number(match[9])
  if (month < 1 || month > 12
      || day < 1 || day > daysInMonth(year, month)
      || hour > 23
      || minute > 59
      || second > 60
      || offsetHour > 23
      || offsetMinute > 59) {
    return false
  }
  return true
}

function daysInMonth(year, month) {
  if (month === 2) {
    const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0)
    return leap ? 29 : 28
  }
  return [4, 6, 9, 11].includes(month) ? 30 : 31
}

function requireBound(name, value, allowZero) {
  if (value === Number.POSITIVE_INFINITY) {
    return
  }
  if (!Number.isSafeInteger(value) || value < (allowZero ? 0 : 1)) {
    throw new TypeError(`${name} must be a bounded positive integer`)
  }
}

function failure(message) {
  const error = new Error(`Trading Lab report check failed: ${message}`)
  error.name = 'TradingLabReportCheckError'
  return error
}

function parseArguments(argv) {
  const options = {
    schema: null,
    minBytes: 0,
    maxHeapDeltaBytes: DEFAULT_MAX_HEAP_DELTA_BYTES,
    maxRssDeltaBytes: Number.POSITIVE_INFINITY,
  }
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index]
    const [name, inlineValue] = argument.split('=', 2)
    const value = inlineValue ?? argv[++index]
    if (!name.startsWith('--') || value === undefined) {
      throw failure(`invalid argument ${argument}`)
    }
    if (name === '--schema') {
      options.schema = resolve(value)
    } else if (name === '--min-bytes') {
      options.minBytes = parseInteger(name, value)
    } else if (name === '--max-heap-delta-bytes') {
      options.maxHeapDeltaBytes = parseInteger(name, value)
    } else if (name === '--max-rss-delta-bytes') {
      options.maxRssDeltaBytes = parseInteger(name, value)
    } else {
      throw failure(`unknown argument ${name}`)
    }
  }
  if (options.schema === null) {
    throw failure('--schema is required')
  }
  return options
}

function parseInteger(name, value) {
  if (!/^(?:0|[1-9]\d*)$/.test(value)) {
    throw failure(`${name} must be a non-negative integer`)
  }
  const parsed = Number(value)
  if (!Number.isSafeInteger(parsed)) {
    throw failure(`${name} exceeds the safe integer range`)
  }
  return parsed
}

function invokedAsProgram() {
  if (!process.argv[1]) {
    return false
  }
  return resolve(process.argv[1]).toLowerCase()
    === resolve(fileURLToPath(import.meta.url)).toLowerCase()
}

if (invokedAsProgram()) {
  try {
    const options = parseArguments(process.argv.slice(2))
    const schema = await loadTradingLabReportSchema(options.schema)
    const result = await checkTradingLabReportStream(process.stdin, {
      ...options,
      schema,
    })
    process.stdout.write(`${JSON.stringify(result)}\n`)
  } catch (error) {
    process.stderr.write(`${error?.message ?? error}\n`)
    process.exitCode = 1
  }
}
