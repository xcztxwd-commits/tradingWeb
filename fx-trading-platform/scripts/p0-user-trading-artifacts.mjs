import { createHash, randomUUID } from 'node:crypto'
import {
  closeSync,
  existsSync,
  fstatSync,
  fsyncSync,
  ftruncateSync,
  linkSync,
  mkdirSync,
  openSync,
  readFileSync,
  readdirSync,
  realpathSync,
  renameSync,
  rmSync,
  statSync,
  writeFileSync
} from 'node:fs'
import { basename, dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { TextDecoder, types } from 'node:util'

import {
  P0_CASES,
  P0_REGISTRY_FINGERPRINT,
  registryFingerprint
} from './p0-user-trading-cases.mjs'

const REDACTED = '[REDACTED]'
const SENSITIVE_KEY = /authorization|cookie|token|password|secret|api[-_]?key/i
const CANONICAL_SENSITIVE_FIELDS = new Set([
  'apikey',
  'authorization',
  'accesstoken',
  'cookie',
  'password',
  'refreshtoken',
  'secret',
  'session',
  'sessionid',
  'setcookie',
  'token'
])
const TERMINAL_STATUSES = new Set(['PASS', 'FAIL', 'BLOCKED', 'INVALID_TEST'])
const RAW_REQUEST_CONTAINERS = new Set([
  'request',
  'rawrequest',
  'requestpayload',
  'replayrequest',
  'rawreplayrequest',
  'originalrequest',
  'capturedrequest',
  'rawrequestbody',
  'rawpayload',
  'payload'
])
const SAFE_REPLAY_FIELDS = new Set([
  'id',
  'caseId',
  'subrunId',
  'referenceId',
  'requestId',
  'clientOrderId',
  'fingerprint',
  'requestFingerprint',
  'status',
  'errorCode'
])
const SAFE_REPLAY_OUTCOME_FIELDS = new Set(['status', 'errorCode'])
const EVIDENCE_REFERENCE_FIELDS = new Set([
  'id',
  'accountid',
  'caseid',
  'runid',
  'subrunid',
  'clientorderid',
  'orderid',
  'parentorderid',
  'parentpositionid',
  'contingencygroupid',
  'holdownerorderid',
  'relatedresourceid',
  'fingerprint',
  'referenceid',
  'resourceid',
  'requestid',
  'requestfingerprint',
  'requestref'
])
const FINGERPRINT_FIELDS = new Set(['fingerprint', 'requestfingerprint'])
const STATUS_FIELDS = new Set([
  'status',
  'errorcode',
  'fromstatus',
  'tostatus',
  'reasoncode',
  'rejectcode'
])
const DIAGNOSTIC_FIELDS = new Set(['error', 'reason'])
const SAFE_DIAGNOSTIC_CODES = new Set([
  'CLI_COMMAND_REQUIRED',
  'CLI_UNKNOWN_COMMAND',
  'CLI_MALFORMED_OPTION',
  'CLI_UNKNOWN_OPTION',
  'CLI_DUPLICATE_OPTION',
  'CLI_OPTION_REQUIRED',
  'CLI_INVALID_OPTION',
  'CLI_INTERNAL_ERROR',
  'CLI_WRITE_FAILED',
  'SUREFIRE_EXPECTED_CLASSES_REQUIRED',
  'SUREFIRE_EXPECTED_CLASS_INVALID',
  'SUREFIRE_EXPECTED_CLASSES_DUPLICATE',
  'SUREFIRE_INVALID_INVOCATION_TIME',
  'SUREFIRE_MALFORMED_XML',
  'SUREFIRE_MISSING_CLASS',
  'SUREFIRE_DUPLICATE_CLASS',
  'SUREFIRE_STALE_REPORT',
  'SUREFIRE_UNSTABLE_REPORT',
  'SUREFIRE_FUTURE_REPORT',
  'SUREFIRE_INVALID_SUITE'
])
const SIMPLE_EVIDENCE_STATUSES = new Set([
  'PASS',
  'FAIL',
  'BLOCKED',
  'INVALID_TEST',
  'RECEIVED',
  'VALIDATING',
  'WORKING',
  'PARTIALLY_FILLED',
  'PENDING_ACTIVATION',
  'FILLED',
  'CANCEL_PENDING',
  'CANCELED',
  'REJECTED',
  'EXPIRED',
  'FAILED',
  'OBSERVED',
  'RUNNING',
  'PENDING',
  'ACCEPTED',
  'CANCELLED',
  'SKIPPED',
  'OPEN',
  'CLOSED'
])
const STRUCTURED_HEADER_KEYS = new Set(['headers', 'requestheaders', 'responseheaders'])
const REQUEST_BODY_KEYS = new Set(['body', 'postdata'])
const RESPONSE_BODY_KEYS = new Set(['responsebody', 'responsepayload'])
const SAFE_HEADER_NAMES = new Set([
  'content-type',
  'x-node',
  'x-region',
  'x-request-id',
  'x-trace-id'
])
const REFERENCE_HEADER_NAMES = new Set(['x-request-id', 'x-trace-id'])
const SAFE_CONTENT_TYPES = new Set([
  'application/json',
  'application/json; charset=UTF-8'
])
const PUBLIC_SYMBOL_VALUES = new Set([
  'BTCUSDT',
  'ETHUSDT',
  'BNBUSDT',
  'SOLUSDT',
  'XRPUSDT',
  'BTCUSDT-PERP',
  'ETHUSDT-PERP',
  'BNBUSDT-PERP',
  'SOLUSDT-PERP',
  'XRPUSDT-PERP'
])
const PUBLIC_URL_HOSTS = new Set([
  'contract.invalid',
  'example.com',
  'example.invalid',
  'example.net',
  'example.org',
  'example.test',
  'localhost',
  'redaction.invalid',
  'redacted.invalid'
])
const PUBLIC_URL_PATH_SEGMENTS = new Set([
  'account',
  'api',
  'auth',
  'events',
  'ledger',
  'login',
  'logout',
  'market',
  'me',
  'orders',
  'positions',
  'quotes',
  'session',
  'trades',
  'wallet'
])
const NETWORK_EVIDENCE_FIELDS = new Set([
  'caseid',
  'clientorderid',
  'errorcode',
  'fingerprint',
  'headers',
  'id',
  'method',
  'mimetype',
  'referenceid',
  'requestfingerprint',
  'requestheaders',
  'requestid',
  'requestref',
  'resourceid',
  'response',
  'responsebody',
  'responseheaders',
  'responsepayload',
  'status',
  'subrunid',
  'url'
])
const HTTP_METHODS = new Set(['DELETE', 'GET', 'HEAD', 'OPTIONS', 'PATCH', 'POST', 'PUT'])
const CANONICAL_CASE_IDS = new Set(P0_CASES.map(({ id }) => id))
const CANONICAL_SUBRUN_IDS = new Set(P0_CASES.flatMap(({ requiredSubruns }) => (
  requiredSubruns.map(({ id }) => id)
)))
const CANONICAL_PHASE_VALUES = new Set(P0_CASES.map(({ phase }) => phase))
const CANONICAL_PROFILE_VALUES = new Set(P0_CASES.flatMap(({ requiredSubruns }) => (
  requiredSubruns.map(({ profile }) => profile)
)))
const CANONICAL_VIEWPORT_VALUES = new Set(P0_CASES.flatMap(({ requiredSubruns }) => (
  requiredSubruns.map(({ viewport }) => viewport)
)))
const ORDER_TYPE_VALUES = new Set(['MARKET', 'LIMIT', 'STOP', 'STOP_MARKET'])
const ORDER_ORIGIN_VALUES = new Set([
  'USER',
  'PROTECTIVE',
  'LIQUIDATION',
  'ADMIN_FORCE_CLOSE',
  'BATCH_CLOSE',
  'OCO'
])
const TRADING_EVENT_VALUES = new Set([
  'ORDER_ACCEPTED',
  'ORDER_PENDING',
  'ORDER_FILLED',
  'ORDER_CANCELED',
  'ORDER_REJECTED',
  'ORDER_EXPIRED',
  'ORDER_MODIFIED',
  'TRADE_CREATED',
  'BALANCE_UPDATED',
  'POSITION_UPDATED',
  'POSITION_CLOSED',
  'PROTECTION_CREATED',
  'PROTECTION_UPDATED',
  'PROTECTION_ACTIVATED',
  'PROTECTION_TRIGGERED',
  'PROTECTION_RESIZED',
  'PROTECTION_CANCELED',
  'PROTECTION_EXPIRED',
  'FUNDING_SETTLED',
  'MARGIN_ADJUSTED',
  'TRANSFER_COMPLETED',
  'LIQUIDATION',
  'DEMO_RESET',
  'MARKET_SOURCE_CHANGED'
])
const LEDGER_ENTRY_VALUES = new Set([
  'DEMO_INIT',
  'DEMO_RESET',
  'TRANSFER_IN',
  'TRANSFER_OUT',
  'DEMO_DEPOSIT',
  'ORDER_HOLD',
  'ORDER_RELEASE',
  'MARGIN_HOLD',
  'MARGIN_RELEASE',
  'TRADE_FEE',
  'TRADE_PNL',
  'FUNDING_FEE',
  'FINANCING',
  'CONVERSION_FEE',
  'LIQUIDATION_FEE',
  'BANKRUPTCY_SHORTFALL',
  'FORCED_CLOSE',
  'ADMIN_ADJUSTMENT',
  'CREDIT_AVAILABLE',
  'DEBIT_AVAILABLE',
  'LOCK_AVAILABLE',
  'RELEASE_LOCKED',
  'DEBIT_LOCKED'
])
const PROVIDER_CODE_VALUES = new Set([
  'binance',
  'okx',
  'local-spot',
  'binance-usdm',
  'okx-swap',
  'local-perp',
  'fixed'
])
const ASSET_VALUES = new Set(['BTC', 'ETH', 'BNB', 'SOL', 'XRP', 'USDT'])
const RESOURCE_TYPE_VALUES = new Set([
  'ACCOUNT',
  'ORDER',
  'TRADE',
  'POSITION',
  'WALLET',
  'LEDGER',
  'TRANSFER',
  'FUNDING',
  'PROTECTION',
  'LIQUIDATION',
  'DEMO_RESET'
])
const WS_EVENT_VALUES = new Set([
  'quote',
  'candle',
  'order_update',
  'trade_update',
  'wallet_update',
  'position_update',
  'funding_update',
  'transfer_update',
  'liquidation_update',
  'market_source_changed',
  'account_update'
])
const TYPE_VALUES = new Set([...ORDER_TYPE_VALUES, ...TRADING_EVENT_VALUES, ...WS_EVENT_VALUES])
const DOMAIN_ENUM_FIELDS = new Map([
  ['symbol', PUBLIC_SYMBOL_VALUES],
  ['mode', new Set(['DISCOVERY', 'EXECUTION', 'CERTIFICATION'])],
  ['verdict', new Set(['PASS', 'PARTIAL_PASS', 'FAIL', 'BLOCKED'])],
  ['phase', CANONICAL_PHASE_VALUES],
  ['profile', CANONICAL_PROFILE_VALUES],
  ['viewport', CANONICAL_VIEWPORT_VALUES],
  ['provider', PROVIDER_CODE_VALUES],
  ['providercode', PROVIDER_CODE_VALUES],
  ['sourcemode', new Set(['PUBLIC_EXTERNAL', 'LOCAL_SIMULATED'])],
  ['ordertype', ORDER_TYPE_VALUES],
  ['type', TYPE_VALUES],
  ['origin', ORDER_ORIGIN_VALUES],
  ['orderorigin', ORDER_ORIGIN_VALUES],
  ['side', new Set(['BUY', 'SELL', 'BOTH', 'LONG', 'SHORT'])],
  ['positionside', new Set(['BOTH', 'LONG', 'SHORT'])],
  ['positionmode', new Set(['ONE_WAY', 'HEDGE'])],
  ['quantityunit', new Set(['BASE', 'QUOTE', 'CONTRACTS'])],
  ['unit', new Set(['BASE', 'QUOTE', 'CONTRACTS'])],
  ['positionunit', new Set(['BASE', 'QUOTE', 'CONTRACTS'])],
  ['marginmode', new Set(['CASH', 'CROSS', 'ISOLATED'])],
  ['liquidityrole', new Set(['MAKER', 'TAKER'])],
  ['wallettype', new Set(['FX_MARGIN', 'SPOT', 'USDT_PERP', 'COIN_PERP', 'FUNDING'])],
  ['producttype', new Set(['FX_MARGIN', 'CRYPTO_SPOT', 'LINEAR_PERP', 'INVERSE_PERP'])],
  ['instrumenttype', new Set(['FX_MARGIN', 'CRYPTO_SPOT', 'LINEAR_PERP', 'INVERSE_PERP'])],
  ['accounttype', new Set(['DEMO', 'LIVE'])],
  ['authoritybundlefixture', new Set(['PASS', 'FAIL'])],
  ['feeasset', ASSET_VALUES],
  ['asset', ASSET_VALUES],
  ['currency', ASSET_VALUES],
  ['basecurrency', ASSET_VALUES],
  ['holdcurrency', ASSET_VALUES],
  ['operation', LEDGER_ENTRY_VALUES],
  ['operationtype', LEDGER_ENTRY_VALUES],
  ['entrytype', LEDGER_ENTRY_VALUES],
  ['eventtype', TRADING_EVENT_VALUES],
  ['referencetype', RESOURCE_TYPE_VALUES],
  ['resourcetype', RESOURCE_TYPE_VALUES],
  ['protectiontype', new Set(['TAKE_PROFIT', 'STOP_LOSS'])],
  ['triggerpricetype', new Set(['LAST_PRICE', 'MARK_PRICE'])],
  ['triggerexecutiontype', new Set(['MARKET', 'LIMIT'])],
  ['timeinforce', new Set(['GTC'])]
])
const DECIMAL_FIELDS = new Set([
  'amount',
  'ask',
  'available',
  'avgfillprice',
  'balance',
  'balanceafter',
  'basequantity',
  'bid',
  'breakevenprice',
  'currentprice',
  'entry',
  'entryprice',
  'equity',
  'executionprice',
  'fee',
  'fill',
  'filledquantity',
  'floatingpnl',
  'floatingpnlratio',
  'freemargin',
  'fundingpnl',
  'holdamount',
  'index',
  'indexprice',
  'last',
  'lastprice',
  'liquidation',
  'liquidationprice',
  'locked',
  'lots',
  'maintenance',
  'maintenancemargin',
  'maintenancemarginrate',
  'margin',
  'marginavailable',
  'marginheld',
  'marginlevel',
  'mark',
  'markprice',
  'mid',
  'notional',
  'openfloatingpnl',
  'openprice',
  'originalquantity',
  'positionvalue',
  'price',
  'quantity',
  'realizedpnl',
  'remainingquantity',
  'slippage',
  'spread',
  'stoploss',
  'takeprofit',
  'total',
  'triggerprice',
  'upl',
  'usedmargin'
])
const INTEGER_FIELDS = new Set([
  'adllevel',
  'errors',
  'failures',
  'leverage',
  'skipped',
  'slot',
  'tests',
  'timestamp',
  'version'
])
const BOOLEAN_FIELDS = new Set(['enabled', 'reduceonly', 'stale', 'tradable'])
const UTC_TIMESTAMP_FIELDS = new Set([
  'asof',
  'canceledat',
  'changedat',
  'closedat',
  'createdat',
  'executedat',
  'expiresat',
  'finishedat',
  'filledat',
  'invocationstartedat',
  'lastsnapshotat',
  'modifiedat',
  'openedat',
  'startedat',
  'updatedat'
])
const P0_SUREFIRE_CLASSES = new Set([
  'PostgresDatabaseIT',
  'V46V47EmptyDatabaseIT',
  'V45ToV47DemoResetIT',
  'Task5PostgresFullFillIT',
  'Task6PostgresSpotIT',
  'Task7PostgresDemoLifecycleIT',
  'Task8PostgresTradingSettingsIT',
  'Task9PostgresPerpetualOrderIT',
  'Task10PostgresProtectionIT',
  'Task11PostgresFundingIT',
  'DemoTradingConcurrencyIT',
  'PerpetualPositionConcurrencyIT',
  'ProtectionOrderConcurrencyIT',
  'FundingLiquidationConcurrencyIT'
])
const SCALAR_ARRAY_FIELDS = new Map([
  ['caseids', 'caseid'],
  ['phases', 'phase'],
  ['profiles', 'profile'],
  ['subrunids', 'subrunid'],
  ['symbols', 'symbol'],
  ['expectedclasses', 'surefireclass'],
  ['viewports', 'viewport']
])
const HTTP_FIELD_NAME = /^[!#$%&'*+.^_`|~A-Za-z\d-]+$/
const INVALID_HEADER_VALUE = /[\u0000-\u0008\u000a-\u001f\u007f]/
const RAW_HTTP_REQUEST_TARGET_FIELDS = new Set(['endpoint', 'url'])
const RAW_HTTP_REQUEST_DETAIL_FIELDS = new Set(['headers', 'body', 'data', 'postdata', 'payload'])
const NETWORK_REQUEST_BODY_FIELDS = new Set(['body', 'postdata', 'payload'])
const XML_WHITESPACE = /[ \t\r\n]/
const XML_WHITESPACE_ONLY = /^[ \t\r\n]*$/
// Filesystem timestamp rounding can put a freshly written report slightly ahead of wall time.
const SUREFIRE_FUTURE_MTIME_TOLERANCE_MS = 2_000
const UTF8_DECODER = new TextDecoder('utf-8', { fatal: true })
const RUN_STATE_IDENTITY_FIELDS = [
  'commit',
  'worktreeFingerprint',
  'schemaVersion',
  'registryFingerprint'
]
const RUN_STATE_IDENTITY_KEYS = new Set([
  'commit',
  'worktreefingerprint',
  'schemaversion',
  'registryfingerprint'
])
const CONTRACT_STRUCTURAL_FIELDS = new Set([
  'account',
  'action',
  'apievidence',
  'arbitraryevidence',
  'blocker',
  'cases',
  'catalog',
  'checkpoint',
  'checkpoints',
  'checks',
  'cleanup',
  'complete',
  'consoleerrors',
  'contractprobes',
  'count',
  'counts',
  'credentialsamples',
  'data',
  'database',
  'dbevidence',
  'definitions',
  'empty',
  'entries',
  'enums',
  'evidence',
  'eventevidence',
  'eventtypes',
  'executiongroup',
  'failure',
  'failureorblocker',
  'file',
  'filtered',
  'financialcalculation',
  'flag',
  'fixtureactions',
  'issues',
  'ledger',
  'ledgerentrytypes',
  'list',
  'liquidityroles',
  'market',
  'marginmodes',
  'metadata',
  'modifiedat',
  'nested',
  'networkevidence',
  'note',
  'ordinary',
  'order',
  'orderstatuses',
  'ordertypes',
  'origins',
  'outcome',
  'position',
  'positionsides',
  'preconditions',
  'providers',
  'publicreferences',
  'replayprobes',
  'requiredsubruns',
  'response',
  'scopecomplete',
  'selection',
  'sides',
  'snapshots',
  'sourcemodes',
  'subruns',
  'suitename',
  'suites',
  'totals',
  'trade',
  'uievidence',
  'units',
  'url',
  'user',
  'useractions',
  'values',
  'verdict',
  'wallet'
])
const STATUS_COUNT_KEYS = new Set(['PASS', 'FAIL', 'BLOCKED', 'INVALID_TEST', 'MISSING'])

function defineOwnData(target, property, value) {
  Object.defineProperty(target, property, {
    value,
    enumerable: true,
    configurable: true,
    writable: true
  })
  return target
}

function ownDataDictionary(entries = []) {
  const dictionary = Object.create(null)
  for (const [property, value] of entries) defineOwnData(dictionary, property, value)
  return dictionary
}

function ownDataDescriptor(source, property) {
  if (!source || typeof source !== 'object') return undefined
  const descriptor = Object.getOwnPropertyDescriptor(source, property)
  return descriptor && 'value' in descriptor ? descriptor : undefined
}

function redactUrl(value) {
  if (typeof value !== 'string') return undefined
  const absolute = /^[a-z][a-z\d+.-]*:/i.test(value)
  const parsed = new URL(value, 'https://redaction.invalid')
  if (absolute && !['http:', 'https:'].includes(parsed.protocol)) return undefined
  if (absolute && !isPublicUrlHost(parsed.hostname)) parsed.hostname = 'redacted.invalid'
  parsed.username = ''
  parsed.password = ''
  parsed.pathname = sanitizeUrlPath(parsed.pathname)
  const query = new URLSearchParams()
  for (const [field, fieldValue] of parsed.searchParams) {
    const semanticField = normalizedKey(field)
    if (isSensitiveFieldName(field)) {
      if (isCanonicalSensitiveField(field)) query.append(field, REDACTED)
    } else if (isEvidenceScalarField(semanticField)) {
      const safeValue = sanitizeEvidenceScalar(semanticField, fieldValue)
      if (safeValue !== undefined) query.append(field, String(safeValue))
    } else if (isDomainScalarField(semanticField)) {
      const safeValue = sanitizeDomainScalar(semanticField, fieldValue)
      if (safeValue !== undefined) query.append(field, String(safeValue))
    } else if (semanticField === 'next' && fieldValue.startsWith('/')) {
      query.append(field, sanitizeUrlPath(fieldValue))
    }
  }
  parsed.search = query.toString()
  parsed.hash = ''
  return absolute ? parsed.toString() : `${parsed.pathname}${parsed.search}${parsed.hash}`
}

function isPublicUrlHost(hostname) {
  const normalized = hostname.toLowerCase()
  return PUBLIC_URL_HOSTS.has(normalized)
    || /^127(?:\.\d{1,3}){3}$/.test(normalized)
    || normalized === '[::1]'
    || normalized === '::1'
}

function sanitizeUrlPath(pathname) {
  return pathname.split('/').map((segment) => {
    if (!segment) return segment
    let decoded
    try {
      decoded = decodeURIComponent(segment)
    } catch {
      return REDACTED
    }
    if (PUBLIC_URL_PATH_SEGMENTS.has(decoded)) return decoded
    return sanitizeReferenceId('resourceid', decoded) ?? REDACTED
  }).join('/')
}

function isFormBody(value) {
  if (value.length === 0 || !value.split('&').every((field) => (
    /^[A-Za-z_][A-Za-z\d_.-]*=/.test(field)
  ))) return false
  const params = new URLSearchParams(value)
  const entries = [...params]
  return params.toString() === value
    && entries.some(([, fieldValue]) => fieldValue !== '')
}

function sanitizeResponseJsonValue(value, keyed = false) {
  if (Array.isArray(value)) {
    return value
      .map((item) => sanitizeResponseJsonValue(item))
      .filter((item) => item !== undefined)
  }
  if (value && typeof value === 'object') {
    const sanitized = ownDataDictionary()
    for (const [field, fieldValue] of Object.entries(value)) {
      const safeField = sanitizeGenericObjectKey(field)
      const safeValue = sanitizeResponseJsonValue(fieldValue, true)
      if (safeField !== undefined && safeValue !== undefined) {
        defineOwnData(sanitized, safeField, safeValue)
      }
    }
    return sanitized
  }
  return keyed ? value : undefined
}

function redactBody(value, dropOpaque = false) {
  let parsed
  try {
    parsed = JSON.parse(value)
  } catch {
    if (!isFormBody(value)) {
      return undefined
    }
    const params = new URLSearchParams(value)
    const sanitized = new URLSearchParams()
    for (const [field, fieldValue] of params) {
      const semanticField = normalizedKey(field)
      if (isEvidenceScalarField(semanticField)) {
        const safeValue = sanitizeEvidenceScalar(semanticField, fieldValue)
        if (safeValue !== undefined) sanitized.append(field, String(safeValue))
      } else if (isSensitiveFieldName(field)) {
        if (isCanonicalSensitiveField(field)) sanitized.append(field, REDACTED)
      }
      else {
        const safeValue = sanitizeBodyScalar(semanticField, fieldValue)
        if (safeValue !== undefined) sanitized.append(field, String(safeValue))
      }
    }
    return sanitized.size > 0 ? sanitized.toString() : undefined
  }
  try {
    const redacted = sanitizeBodyJsonValue(parsed)
    return ownDataJson(dropOpaque ? sanitizeResponseJsonValue(redacted) : redacted)
  } catch {
    return undefined
  }
}

function sanitizeBodyJsonValue(value, key = '') {
  const semanticKey = normalizedKey(key)
  if (isEvidenceScalarField(semanticKey)) return sanitizeEvidenceScalar(semanticKey, value)
  if (isSensitiveFieldName(key)) {
    return isCanonicalSensitiveField(key) ? REDACTED : undefined
  }
  if (semanticKey === 'url') return typeof value === 'string' ? redactUrl(value) : undefined
  if (Array.isArray(value)) {
    return value
      .map((item) => sanitizeBodyJsonValue(item))
      .filter((item) => item !== undefined)
  }
  if (value && typeof value === 'object') {
    const sanitized = ownDataDictionary()
    for (const [field, fieldValue] of Object.entries(value)) {
      const safeField = sanitizeGenericObjectKey(field)
      const safeValue = sanitizeBodyJsonValue(fieldValue, field)
      if (safeField !== undefined && safeValue !== undefined) {
        defineOwnData(sanitized, safeField, safeValue)
      }
    }
    return sanitized
  }
  return sanitizeBodyScalar(semanticKey, value)
}

function sanitizeBodyScalar(field, value) {
  return isDomainScalarField(field) ? sanitizeDomainScalar(field, value) : undefined
}

function sanitizeHeaderValue(name, value) {
  if (typeof name !== 'string' || !HTTP_FIELD_NAME.test(name)
    || typeof value !== 'string' || INVALID_HEADER_VALUE.test(value)) return undefined
  if (isSensitiveFieldName(name)) {
    return isCanonicalSensitiveField(name) ? REDACTED : undefined
  }
  const normalizedName = name.toLowerCase()
  if (!SAFE_HEADER_NAMES.has(normalizedName)) return undefined
  if (REFERENCE_HEADER_NAMES.has(normalizedName)) {
    return sanitizeReferenceId(normalizedName === 'x-request-id' ? 'requestid' : 'id', value)
  }
  if (normalizedName === 'content-type') return SAFE_CONTENT_TYPES.has(value) ? value : undefined
  return sanitizeReferenceId('id', value)
}

function sanitizeHeaderRepresentation(value, allowMap = false) {
  if (typeof value === 'string') {
    const match = value.match(/^([A-Za-z\d!#$%&'*+.^_`|~-]+):[ \t]*(.*)$/)
    if (!match) return undefined
    const sanitized = sanitizeHeaderValue(match[1], match[2])
    return sanitized === undefined ? undefined : `${match[1]}: ${sanitized}`
  }
  if (Array.isArray(value)) {
    if (value.length !== 2 || !value.every((item) => typeof item === 'string')) return undefined
    const sanitized = sanitizeHeaderValue(value[0], value[1])
    return sanitized === undefined ? undefined : [value[0], sanitized]
  }
  if (!value || typeof value !== 'object') return undefined
  const nameDescriptor = ownDataDescriptor(value, 'name')
  const valueDescriptor = ownDataDescriptor(value, 'value')
  if (typeof nameDescriptor?.value === 'string' && typeof valueDescriptor?.value === 'string') {
    const sanitized = sanitizeHeaderValue(nameDescriptor.value, valueDescriptor.value)
    return sanitized === undefined
      ? undefined
      : ownDataDictionary([['name', nameDescriptor.value], ['value', sanitized]])
  }
  if (!allowMap) return undefined

  const sanitized = ownDataDictionary()
  for (const [name, headerValue] of Object.entries(value)) {
    const safeValue = sanitizeHeaderValue(name, headerValue)
    if (safeValue !== undefined) defineOwnData(sanitized, name, safeValue)
  }
  return sanitized
}

function sanitizeHeaderEvidence(value) {
  if (!Array.isArray(value)) return sanitizeHeaderRepresentation(value, true)
  return value.map((item) => sanitizeHeaderRepresentation(item))
    .filter((item) => item !== undefined)
}

function redactValue(value, key = '') {
  const semanticKey = normalizedKey(key)
  if (semanticKey.includes('header') && !STRUCTURED_HEADER_KEYS.has(semanticKey)) {
    return undefined
  }
  if ((semanticKey.includes('body') || semanticKey.includes('payload'))
    && !REQUEST_BODY_KEYS.has(semanticKey) && !RESPONSE_BODY_KEYS.has(semanticKey)) {
    return undefined
  }
  if (semanticKey === 'definitions') return sanitizeDefinitions(value)
  if (RUN_STATE_IDENTITY_KEYS.has(semanticKey)) {
    return sanitizeRunStateIdentity(semanticKey, value)
  }
  if (isEvidenceScalarField(semanticKey)) return sanitizeEvidenceScalar(semanticKey, value)
  if (DIAGNOSTIC_FIELDS.has(semanticKey)) return sanitizeDiagnostic(value)
  if (semanticKey === 'surefireclass' || semanticKey === 'classname') {
    if (typeof value !== 'string') return undefined
    return P0_SUREFIRE_CLASSES.has(value) ? value : sanitizeUnknownString(value)
  }
  if (semanticKey === 'method') {
    return typeof value === 'string' && HTTP_METHODS.has(value) ? value : undefined
  }
  if (semanticKey === 'mimetype') {
    return sanitizeHeaderValue('Content-Type', value)
  }
  if (isDomainScalarField(semanticKey)) return sanitizeDomainScalar(semanticKey, value)
  if (isSensitiveFieldName(key)) {
    return isCanonicalSensitiveField(key) ? REDACTED : undefined
  }
  if (semanticKey === 'url') return redactUrl(value)
  if (STRUCTURED_HEADER_KEYS.has(semanticKey)) return sanitizeHeaderEvidence(value)
  if (REQUEST_BODY_KEYS.has(semanticKey)) {
    return typeof value === 'string' ? redactBody(value) : undefined
  }
  if (RESPONSE_BODY_KEYS.has(semanticKey)) {
    return typeof value === 'string' ? redactBody(value, true) : undefined
  }
  if (Array.isArray(value)) {
    const itemField = SCALAR_ARRAY_FIELDS.get(semanticKey)
    return value.map((item) => (
      itemField && (!item || typeof item !== 'object')
        ? redactValue(item, itemField)
        : redactValue(item)
    )).filter((item) => item !== undefined)
  }
  if (value === null || typeof value === 'boolean') return value
  if (typeof value === 'number') return Number.isFinite(value) ? value : undefined
  if (typeof value === 'string') return sanitizeUnknownString(value)
  if (!value || typeof value !== 'object') return undefined

  const redacted = ownDataDictionary()
  for (const [entryKey, entryValue] of Object.entries(value)) {
    const safeKey = sanitizeGenericObjectKey(entryKey)
    const safeValue = redactValue(entryValue, entryKey)
    if (safeKey !== undefined && safeValue !== undefined) {
      defineOwnData(redacted, safeKey, safeValue)
    }
  }
  const nameDescriptor = ownDataDescriptor(value, 'name')
  if (typeof nameDescriptor?.value === 'string' && SENSITIVE_KEY.test(nameDescriptor.value)
    && ownDataDescriptor(value, 'value')) {
    defineOwnData(redacted, 'value', REDACTED)
  }
  return redacted
}

export function redactNetworkEntry(entry) {
  const snapshot = inertJsonValue(entry)
  const stripped = looksLikeRootNetworkEntry(snapshot)
    ? stripRawRequests([snapshot], 'networkEvidence')[0]
    : stripRawRequests(snapshot)
  const sanitized = redactValue(stripped)
  if (sanitized === null || sanitized === undefined) return {}
  return JSON.parse(ownDataJson(sanitized))
}

function normalizedKey(key) {
  return key.replaceAll(/[^a-z\d]/gi, '').toLowerCase()
}

function sanitizeGenericObjectKey(field) {
  if (typeof field !== 'string') return undefined
  if (isCanonicalSensitiveField(field)) return field
  if (isSensitiveFieldName(field) || isCredentialEnvelope(field)) return undefined
  const semanticField = normalizedKey(field)
  if (CONTRACT_STRUCTURAL_FIELDS.has(semanticField)
    || EVIDENCE_REFERENCE_FIELDS.has(semanticField)
    || STATUS_FIELDS.has(semanticField)
    || DIAGNOSTIC_FIELDS.has(semanticField)
    || RUN_STATE_IDENTITY_KEYS.has(semanticField)
    || DOMAIN_ENUM_FIELDS.has(semanticField)
    || DECIMAL_FIELDS.has(semanticField)
    || INTEGER_FIELDS.has(semanticField)
    || BOOLEAN_FIELDS.has(semanticField)
    || UTC_TIMESTAMP_FIELDS.has(semanticField)
    || SCALAR_ARRAY_FIELDS.has(semanticField)
    || STRUCTURED_HEADER_KEYS.has(semanticField)
    || REQUEST_BODY_KEYS.has(semanticField)
    || RESPONSE_BODY_KEYS.has(semanticField)
    || ['classname', 'method', 'mimetype', 'surefireclass'].includes(semanticField)
    || CANONICAL_CASE_IDS.has(field)
    || CANONICAL_SUBRUN_IDS.has(field)
    || STATUS_COUNT_KEYS.has(field)) return field
  return sanitizeUnknownString(field)
}

function isDomainScalarField(field) {
  return DOMAIN_ENUM_FIELDS.has(field)
    || DECIMAL_FIELDS.has(field)
    || INTEGER_FIELDS.has(field)
    || BOOLEAN_FIELDS.has(field)
    || UTC_TIMESTAMP_FIELDS.has(field)
}

function sanitizeDomainScalar(field, value) {
  if (DOMAIN_ENUM_FIELDS.has(field)) {
    return typeof value === 'string' && DOMAIN_ENUM_FIELDS.get(field).has(value)
      ? value
      : undefined
  }
  if (DECIMAL_FIELDS.has(field)) {
    if (typeof value === 'number') return Number.isFinite(value) ? value : undefined
    return typeof value === 'string'
      && value.length <= 128
      && /^-?(?:0|[1-9]\d*)(?:\.\d+)?$/.test(value)
      ? value
      : undefined
  }
  if (INTEGER_FIELDS.has(field)) {
    return Number.isSafeInteger(value) && value >= 0 ? value : undefined
  }
  if (BOOLEAN_FIELDS.has(field)) return typeof value === 'boolean' ? value : undefined
  if (UTC_TIMESTAMP_FIELDS.has(field)) {
    if (typeof value !== 'string'
      || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(value)) return undefined
    const timestamp = Date.parse(value)
    return Number.isFinite(timestamp) && new Date(timestamp).toISOString() === value
      ? value
      : undefined
  }
  return undefined
}

function sanitizeRunStateIdentity(field, value) {
  if (field === 'schemaversion') {
    return Number.isSafeInteger(value) && value > 0 ? value : undefined
  }
  if (typeof value !== 'string') return undefined
  if (field === 'registryfingerprint') {
    if (value === P0_REGISTRY_FINGERPRINT) return value
    return value.trim().length > 0 ? sanitizeUnknownString(value) : undefined
  }
  if (field === 'commit') {
    return /^(?:[a-f\d]{40}|[a-f\d]{64})$/i.test(value) ? value.toLowerCase() : undefined
  }
  if (field === 'worktreefingerprint') {
    const fingerprint = value.match(/^(sha256:)?([a-f\d]{64})$/i)
    return fingerprint
      ? `${fingerprint[1] ? 'sha256:' : ''}${fingerprint[2].toLowerCase()}`
      : undefined
  }
  return undefined
}

function sanitizeCanonicalDefinitions(value) {
  return registryIssue(value, P0_REGISTRY_FINGERPRINT) === null ? value : undefined
}

function sanitizeDefinitions(value) {
  const canonical = sanitizeCanonicalDefinitions(value)
  if (canonical) return canonical
  if (!Array.isArray(value)) return undefined
  return value.map((definition) => redactValue(definition))
    .filter((definition) => definition !== undefined)
}

function sha256Representation(value) {
  return `sha256:${createHash('sha256').update(value).digest('hex')}`
}

function boundedControlFreeString(value) {
  return typeof value === 'string' && value.length > 0 && value.length <= 512
    && !/[\u0000-\u001f\u007f]/.test(value)
}

function isSensitiveFieldName(field) {
  return typeof field === 'string' && (SENSITIVE_KEY.test(field) || /session/i.test(field))
}

function isCanonicalSensitiveField(field) {
  return CANONICAL_SENSITIVE_FIELDS.has(normalizedKey(field))
}

function isCredentialEnvelope(value) {
  return /^(?:Bearer\s+\S+|Cookie\s*:\s*\S.*)$/i.test(value)
    || /^(?:session|password|token|api[-_]?key|secret)\s*=\s*\S.*$/i.test(value)
    || /^[A-Za-z\d_-]{8,}\.[A-Za-z\d_-]{8,}\.[A-Za-z\d_-]{8,}$/.test(value)
    || /^(?:AKIA|ASIA|AIDA|AROA)[A-Z\d]{16}$/.test(value)
    || (value.length >= 32 && value.length <= 512
      && value.length % 4 === 0
      && /^(?:[A-Za-z\d+/]{4})*(?:[A-Za-z\d+/]{2}==|[A-Za-z\d+/]{3}=)?$/.test(value))
}

function sanitizeUnknownString(value) {
  if (!boundedControlFreeString(value) || isCredentialEnvelope(value)) return undefined
  const canonical = value.match(/^sha256:([a-f\d]{64})$/i)
  return canonical
    ? `sha256:${canonical[1].toLowerCase()}`
    : sha256Representation(value)
}

function hashableReference(value) {
  return boundedControlFreeString(value)
    && !isReferenceCredentialEnvelope(value)
}

function isReferenceCredentialEnvelope(value) {
  return /^(?:Bearer\s+\S+|Cookie\s*:\s*\S.*)$/i.test(value)
    || /^(?:session|password|token|api[-_]?key|secret)\s*=\s*\S.*$/i.test(value)
}

function sanitizeFingerprint(value) {
  if (typeof value !== 'string') return undefined
  const canonical = value.match(/^sha256:([a-f\d]{64})$/i)
  if (canonical) return `sha256:${canonical[1].toLowerCase()}`
  return boundedControlFreeString(value) ? sha256Representation(value) : undefined
}

function sanitizeStatus(value) {
  if (Number.isSafeInteger(value) && value >= 100 && value <= 599) return value
  if (typeof value !== 'string' || value.length > 64) return undefined
  return SIMPLE_EVIDENCE_STATUSES.has(value)
    || /^[A-Z][A-Z\d]*(?:_[A-Z\d]+)+$/.test(value)
    ? value
    : undefined
}

function sanitizeDiagnostic(value) {
  if (typeof value !== 'string') return undefined
  return SAFE_DIAGNOSTIC_CODES.has(value) ? value : sanitizeUnknownString(value)
}

function isPublicReference(field, value) {
  if (field === 'caseid') return CANONICAL_CASE_IDS.has(value)
  if (field === 'subrunid') return CANONICAL_SUBRUN_IDS.has(value)
  if (field === 'id' && (CANONICAL_CASE_IDS.has(value)
    || CANONICAL_SUBRUN_IDS.has(value))) return true
  if (/^[a-f\d]{8}-[a-f\d]{4}-[1-5][a-f\d]{3}-[89ab][a-f\d]{3}-[a-f\d]{12}$/i.test(value)) {
    return true
  }
  return field === 'requestid'
    && value.length <= 33
    && /^\d{1,16}(?:\.\d{1,16})?$/.test(value)
}

function sanitizeReferenceId(field, value) {
  if ((field === 'caseid' || field === 'subrunid') && typeof value !== 'string') {
    return undefined
  }
  if (typeof value === 'number') {
    return Number.isSafeInteger(value) && value >= 0 ? value : undefined
  }
  if (!hashableReference(value)) return undefined
  const canonicalHash = value.match(/^sha256:([a-f\d]{64})$/i)
  if (canonicalHash) return `sha256:${canonicalHash[1].toLowerCase()}`
  return isPublicReference(field, value) ? value : sha256Representation(value)
}

function sanitizeEvidenceScalar(field, value) {
  const semanticField = normalizedKey(field)
  if (FINGERPRINT_FIELDS.has(semanticField)) return sanitizeFingerprint(value)
  if (STATUS_FIELDS.has(semanticField)) return sanitizeStatus(value)
  return sanitizeReferenceId(semanticField, value)
}

function isEvidenceScalarField(field) {
  return EVIDENCE_REFERENCE_FIELDS.has(field) || STATUS_FIELDS.has(field)
}

function sanitizeReplayProbe(probe) {
  if (!probe || typeof probe !== 'object' || Array.isArray(probe)) return ownDataDictionary()
  const safe = ownDataDictionary()
  for (const [field, value] of Object.entries(probe)) {
    if (!SAFE_REPLAY_FIELDS.has(field)) continue
    const safeValue = sanitizeEvidenceScalar(field, value)
    if (safeValue !== undefined) defineOwnData(safe, field, safeValue)
  }
  const outcome = ownDataDictionary()
  const outcomeDescriptor = ownDataDescriptor(probe, 'outcome')
  for (const [field, value] of Object.entries(outcomeDescriptor?.value ?? ownDataDictionary())) {
    if (!SAFE_REPLAY_OUTCOME_FIELDS.has(field)) continue
    const safeValue = sanitizeEvidenceScalar(field, value)
    if (safeValue !== undefined) defineOwnData(outcome, field, safeValue)
  }
  if (Object.keys(outcome).length > 0) defineOwnData(safe, 'outcome', outcome)
  return safe
}

function looksLikeRootNetworkEntry(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false
  const fields = Object.keys(value).map((key) => [
    normalizedKey(key),
    ownDataDescriptor(value, key)?.value
  ])
  return fields.some(([field, fieldValue]) => (
    field === 'method' && typeof fieldValue === 'string' && HTTP_METHODS.has(fieldValue)
  )) && fields.some(([field, fieldValue]) => (
    RAW_HTTP_REQUEST_TARGET_FIELDS.has(field)
    && typeof fieldValue === 'string'
    && fieldValue.length > 0
  ))
}

function looksLikeRawHttpRequest(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false
  const fields = new Set(Object.keys(value).map(normalizedKey))
  return fields.has('method') && [...RAW_HTTP_REQUEST_TARGET_FIELDS].some((field) => fields.has(field))
    && [...RAW_HTTP_REQUEST_DETAIL_FIELDS].some((field) => fields.has(field))
}

function sanitizeNetworkEvidence(value) {
  if (!Array.isArray(value)) return []
  return value
    .filter((entry) => entry && typeof entry === 'object' && !Array.isArray(entry))
    .map((entry) => stripRawRequests(entry, '', true))
    .filter((entry) => entry && Object.keys(entry).length > 0)
}

function stripRawRequests(value, key = '', inNetworkEvidence = false) {
  const semanticKey = normalizedKey(key)
  if (semanticKey === 'networkevidence') return sanitizeNetworkEvidence(value)
  if (inNetworkEvidence && semanticKey && !NETWORK_EVIDENCE_FIELDS.has(semanticKey)) {
    return undefined
  }
  if (inNetworkEvidence && semanticKey === 'response'
    && (!value || typeof value !== 'object' || Array.isArray(value))) return undefined
  if (inNetworkEvidence && NETWORK_REQUEST_BODY_FIELDS.has(semanticKey)) return undefined
  if (isEvidenceScalarField(semanticKey)) {
    return sanitizeEvidenceScalar(semanticKey, value)
  }
  if (STRUCTURED_HEADER_KEYS.has(semanticKey)) return value
  if (RAW_REQUEST_CONTAINERS.has(semanticKey) || semanticKey.includes('request')) return undefined
  if (semanticKey === 'replayprobes') {
    if (!Array.isArray(value)) return []
    return value.map(sanitizeReplayProbe)
  }
  if (!inNetworkEvidence && looksLikeRawHttpRequest(value)) return undefined
  if (Array.isArray(value)) {
    return value
      .map((item) => stripRawRequests(item, '', inNetworkEvidence))
      .filter((item) => item !== undefined)
  }
  if (!value || typeof value !== 'object') return value

  const sanitized = ownDataDictionary()
  for (const [field, fieldValue] of Object.entries(value)) {
    const safeValue = stripRawRequests(fieldValue, field, inNetworkEvidence)
    if (safeValue !== undefined) defineOwnData(sanitized, field, safeValue)
  }
  return sanitized
}

function inertJsonValue(value, key = '', strictArrays = false, strictObjects = false) {
  if (key === 'toJSON' || value === undefined
    || typeof value === 'function' || typeof value === 'symbol') return undefined
  if (typeof value === 'bigint') {
    throw new TypeError('UNSAFE_PERSISTENCE_VALUE: bigint')
  }
  if (typeof value === 'number' && !Number.isFinite(value)) {
    throw new TypeError('UNSAFE_PERSISTENCE_VALUE: non-finite number')
  }
  if (!value || typeof value !== 'object') return value
  if (types.isProxy(value)) throw new TypeError('UNSAFE_PERSISTENCE_VALUE: Proxy')

  const array = Array.isArray(value)
  const prototype = Object.getPrototypeOf(value)
  if ((array && prototype !== Array.prototype)
    || (!array && prototype !== Object.prototype && prototype !== null)) {
    throw new TypeError('UNSAFE_PERSISTENCE_VALUE: non-plain object')
  }
  const descriptors = Object.getOwnPropertyDescriptors(value)
  const descriptorKeys = Reflect.ownKeys(descriptors)
  for (const property of descriptorKeys) {
    const descriptor = descriptors[property]
    if ('get' in descriptor || 'set' in descriptor) {
      throw new TypeError('UNSAFE_PERSISTENCE_VALUE: accessor')
    }
  }

  if (array) {
    const exactArray = strictArrays || normalizedKey(key) === 'definitions'
    if (exactArray) {
      const length = descriptors.length.value
      if (descriptorKeys.length !== length + 1
        || !descriptorKeys.every((property) => (
          property === 'length'
          || (typeof property === 'string'
            && Number.isSafeInteger(Number(property))
            && String(Number(property)) === property
            && Number(property) >= 0
            && Number(property) < length)
        ))) {
        throw new TypeError('UNSAFE_IDENTITY_ARRAY')
      }
      const inert = []
      for (let index = 0; index < length; index += 1) {
        const descriptor = descriptors[index]
        if (!descriptor) throw new TypeError('UNSAFE_IDENTITY_ARRAY')
        const item = inertJsonValue(descriptor.value, '', true, strictObjects)
        if (item === undefined) throw new TypeError('UNSAFE_IDENTITY_ARRAY')
        inert.push(item)
      }
      return inert
    }
    const inert = []
    for (let index = 0; index < descriptors.length.value; index += 1) {
      const descriptor = descriptors[index]
      if (!descriptor) continue
      const item = inertJsonValue(descriptor.value, '', false, strictObjects)
      if (item !== undefined) inert.push(item)
    }
    return inert
  }

  if (strictObjects && descriptorKeys.some((property) => (
    typeof property !== 'string' || !descriptors[property].enumerable
  ))) {
    throw new TypeError('UNSAFE_IDENTITY_OBJECT')
  }

  const inert = Object.create(null)
  for (const [field, descriptor] of Object.entries(descriptors)) {
    if (!descriptor.enumerable) continue
    const fieldValue = inertJsonValue(descriptor.value, field, strictArrays, strictObjects)
    if (fieldValue === undefined && strictObjects) {
      throw new TypeError('UNSAFE_IDENTITY_OBJECT')
    }
    if (fieldValue === undefined) continue
    Object.defineProperty(inert, field, {
      value: fieldValue,
      enumerable: true,
      configurable: true,
      writable: true
    })
  }
  return inert
}

function inertIdentityValue(value) {
  return inertJsonValue(value, '', true, true)
}

function sanitizeForPersistence(value) {
  return redactValue(stripRawRequests(inertJsonValue(value)))
}

function ownDataSerializationSnapshot(source) {
  if (!source || typeof source !== 'object') return source
  if (types.isProxy(source)) throw new TypeError('UNSAFE_PERSISTENCE_VALUE: Proxy')

  const descriptors = Object.getOwnPropertyDescriptors(source)
  const ownKeys = Reflect.ownKeys(descriptors)
  for (const property of ownKeys) {
    const descriptor = descriptors[property]
    if ('get' in descriptor || 'set' in descriptor) {
      throw new TypeError('UNSAFE_PERSISTENCE_VALUE: accessor')
    }
  }

  if (Array.isArray(source)) {
    const length = descriptors.length.value
    if (ownKeys.length !== length + 1 || ownKeys.some((property) => (
      property !== 'length'
      && (typeof property !== 'string'
        || !Number.isSafeInteger(Number(property))
        || String(Number(property)) !== property
        || Number(property) < 0
        || Number(property) >= length)
    ))) throw new TypeError('UNSAFE_IDENTITY_ARRAY')

    const inert = []
    for (let index = 0; index < length; index += 1) {
      const descriptor = descriptors[index]
      if (!descriptor) throw new TypeError('UNSAFE_IDENTITY_ARRAY')
      defineOwnData(inert, index, ownDataSerializationSnapshot(descriptor.value))
    }
    Object.setPrototypeOf(inert, null)
    return inert
  }

  const inert = Object.create(null)
  for (const property of ownKeys) {
    const descriptor = descriptors[property]
    if (typeof property !== 'string' || !descriptor.enumerable) continue
    defineOwnData(inert, property, ownDataSerializationSnapshot(descriptor.value))
  }
  return inert
}

function ownDataJson(value, spacing) {
  return JSON.stringify(ownDataSerializationSnapshot(value), null, spacing)
}

function ownDataSerializationSource(value) {
  const json = ownDataJson(value, 2)
  if (typeof json !== 'string') throw new TypeError('UNSAFE_PERSISTENCE_ROOT: not serializable')
  return `${json}\n`
}

function writeCaseResultAtomicInternal(path, result, mode) {
  const sanitized = sanitizeForPersistence(result)
  if (!sanitized || typeof sanitized !== 'object' || Array.isArray(sanitized)) {
    throw new TypeError('UNSAFE_PERSISTENCE_ROOT: expected plain object')
  }
  const serialized = ownDataSerializationSource(sanitized)
  const temporaryPath = `${path}.${process.pid}.${randomUUID()}.tmp`
  mkdirSync(dirname(path), { recursive: true })
  let descriptor
  let owned = false

  try {
    descriptor = openSync(temporaryPath, 'wx')
    owned = true
    writeFileSync(descriptor, serialized, 'utf8')
    closeSync(descriptor)
    descriptor = undefined
    if (mode === 'replace') {
      renameSync(temporaryPath, path)
      owned = false
      return true
    }
    try {
      linkSync(temporaryPath, path)
      return true
    } catch (error) {
      if (error?.code === 'EEXIST') return false
      throw error
    }
  } finally {
    if (descriptor !== undefined) closeSync(descriptor)
    if (owned) rmSync(temporaryPath, { force: true })
  }
}

export function writeCaseResultAtomic(path, result) {
  writeCaseResultAtomicInternal(path, result, 'replace')
}

function normalizedRunStateIdentity(source, label) {
  const identity = Object.create(null)
  for (const field of RUN_STATE_IDENTITY_FIELDS) {
    if (!source || typeof source !== 'object' || !Object.hasOwn(source, field)) {
      throw new Error(`INVALID_RUN_STATE_IDENTITY: ${label}.${field}`)
    }
    const descriptor = Object.getOwnPropertyDescriptor(source, field)
    if (!descriptor || !('value' in descriptor)) {
      throw new Error(`INVALID_RUN_STATE_IDENTITY: ${label}.${field}`)
    }
    const normalized = sanitizeRunStateIdentity(normalizedKey(field), descriptor.value)
    if (normalized === undefined) {
      throw new Error(`INVALID_RUN_STATE_IDENTITY: ${label}.${field}`)
    }
    identity[field] = normalized
  }
  return identity
}

function registryIssue(definitions, fingerprint) {
  if (!Array.isArray(definitions)) return 'MISSING_REGISTRY'
  let inertDefinitions
  try {
    inertDefinitions = inertJsonValue(definitions)
  } catch {
    return 'INVALID_REGISTRY'
  }
  if (inertDefinitions.length === 0) return 'MISSING_REGISTRY'
  if (inertDefinitions.length !== P0_CASES.length
    || new Set(inertDefinitions.map((definition) => definition?.id)).size !== P0_CASES.length) {
    return 'INVALID_REGISTRY'
  }
  const computed = registryFingerprint(inertDefinitions)
  if (computed !== P0_REGISTRY_FINGERPRINT) return 'INVALID_REGISTRY'
  if (fingerprint !== computed) return 'REGISTRY_FINGERPRINT_MISMATCH'
  return null
}

function assertCanonicalRegistry(definitions, fingerprint, label) {
  const issue = registryIssue(definitions, fingerprint)
  if (issue) throw new Error(`${issue}: ${label}`)
}

export function loadOrCreateRunState(options) {
  const snapshot = inertIdentityValue(options)
  if (!snapshot || typeof snapshot !== 'object' || Array.isArray(snapshot)) {
    throw new TypeError('INVALID_RUN_STATE_OPTIONS')
  }
  const {
    path,
    runId,
    mode,
    commit,
    worktreeFingerprint,
    schemaVersion,
    registryFingerprint,
    definitions
  } = snapshot
  const selection = Object.hasOwn(snapshot, 'selection')
    ? snapshot.selection
    : Object.create(null)
  if (typeof path !== 'string' || path.trim().length === 0) {
    throw new TypeError('INVALID_RUN_STATE_PATH')
  }
  if (!validSelectionSnapshot(selection)) {
    throw new TypeError('INVALID_RUN_STATE_SELECTION')
  }
  const identity = normalizedRunStateIdentity(
    { commit, worktreeFingerprint, schemaVersion, registryFingerprint },
    'options'
  )
  if (!existsSync(path)) {
    assertCanonicalRegistry(definitions, identity.registryFingerprint, 'options')
    const state = sanitizeForPersistence({
      schemaVersion: identity.schemaVersion,
      runId,
      mode,
      commit: identity.commit,
      worktreeFingerprint: identity.worktreeFingerprint,
      registryFingerprint: identity.registryFingerprint,
      definitions,
      selection,
      cases: {},
      createdAt: new Date().toISOString()
    })
    if (writeCaseResultAtomicInternal(path, state, 'no-clobber')) {
      return JSON.parse(ownDataJson(state))
    }
  }

  const parsedState = JSON.parse(readFileSync(path, 'utf8'))
  const state = inertIdentityValue(parsedState)
  const stateIdentity = normalizedRunStateIdentity(state, 'state')
  for (const field of RUN_STATE_IDENTITY_FIELDS) {
    if (stateIdentity[field] !== identity[field]) throw new Error(`RESUME_MISMATCH: ${field}`)
  }
  assertCanonicalRegistry(definitions, identity.registryFingerprint, 'options')
  assertCanonicalRegistry(state.definitions, stateIdentity.registryFingerprint, 'state')
  return parsedState
}

const SELECTION_FIELDS = ['caseIds', 'phases', 'profiles', 'viewports']

function selectionValues(selection, key) {
  return Object.hasOwn(selection, key) ? selection[key] : undefined
}

function validSelectionSnapshot(selection) {
  return Boolean(selection) && typeof selection === 'object' && !Array.isArray(selection)
    && Object.keys(selection).every((key) => (
      SELECTION_FIELDS.includes(key)
      && Array.isArray(selectionValues(selection, key))
      && selectionValues(selection, key).every((value) => (
        typeof value === 'string' && value.length > 0
      ))
    ))
}

function hasSelection(selection, key, value) {
  const values = selectionValues(selection, key)
  return !values?.length || values.includes(value)
}

function resolveSelection(definitions, selection) {
  const filtered = SELECTION_FIELDS.some((key) => selectionValues(selection, key)?.length)
  const entries = definitions
    .filter((definition) => (
      hasSelection(selection, 'caseIds', definition.id)
      && hasSelection(selection, 'phases', definition.phase)
    ))
    .map((definition) => ({
      definition,
      subruns: selectedSubruns(definition, selection)
    }))
    .filter(({ subruns }) => subruns.length > 0)
  if (filtered && entries.length === 0) {
    return { filtered, entries, issues: ['EMPTY_SELECTION'] }
  }

  const coverage = {
    caseIds: new Set(entries.map(({ definition }) => definition.id)),
    phases: new Set(entries.map(({ definition }) => definition.phase)),
    profiles: new Set(entries.flatMap(({ subruns }) => subruns.map(({ profile }) => profile))),
    viewports: new Set(entries.flatMap(({ subruns }) => subruns.map(({ viewport }) => viewport)))
  }
  const issues = []
  for (const key of SELECTION_FIELDS) {
    const missing = [...new Set(selectionValues(selection, key) ?? [])]
      .filter((value) => !coverage[key].has(value))
    if (missing.length > 0) issues.push(`INVALID_SELECTION: ${key}=${missing.join(',')}`)
  }
  return { filtered, entries, issues }
}

function coversRequiredSubruns(result, definition) {
  if (result?.id !== definition.id
    || result.status !== 'PASS'
    || result.scopeComplete !== true) return false
  if (!Array.isArray(result.subruns) || result.subruns.length !== definition.requiredSubruns.length) {
    return false
  }
  return definition.requiredSubruns.every((required) => (
    result.subruns.filter((actual) => (
      actual.id === required.id
      && actual.profile === required.profile
      && actual.viewport === required.viewport
      && actual.status === 'PASS'
    )).length === 1
  ))
}

function ownResumeCaseResult(state, caseId) {
  const cases = ownDataDescriptor(state, 'cases')?.value
  if (!cases || typeof cases !== 'object' || Array.isArray(cases)) return undefined
  return ownDataDescriptor(cases, caseId)?.value
}

export function planResume(state, definitions, selection = {}) {
  const stateSnapshot = inertIdentityValue(state)
  const definitionsSnapshot = inertIdentityValue(definitions)
  const selectionSnapshot = inertIdentityValue(selection)
  if (!validSelectionSnapshot(selectionSnapshot)) {
    throw new TypeError('INVALID_SELECTION: malformed')
  }
  assertCanonicalRegistry(definitionsSnapshot, P0_REGISTRY_FINGERPRINT, 'definitions')
  assertCanonicalRegistry(
    stateSnapshot?.definitions,
    stateSnapshot?.registryFingerprint,
    'state'
  )
  const resolved = resolveSelection(definitionsSnapshot, selectionSnapshot)
  if (resolved.issues.length > 0) throw new Error(resolved.issues[0])
  const { filtered } = resolved
  const subrunsFiltered = ['profiles', 'viewports']
    .some((key) => selectionValues(selectionSnapshot, key)?.length)
  const entries = resolved.entries
    .map(({ definition, subruns }) => {
      const result = ownResumeCaseResult(stateSnapshot, definition.id)
      return {
        id: definition.id,
        executionGroup: definition.executionGroup,
        subruns,
        result,
        complete: !subrunsFiltered && coversRequiredSubruns(result, definition)
      }
    })

  const rerunGroups = new Set(
    entries.filter((entry) => !entry.complete).map((entry) => entry.executionGroup)
  )
  const planned = entries.map((entry) => {
    const groupRerun = rerunGroups.has(entry.executionGroup)
    let reason = 'COMPLETE'
    if (subrunsFiltered) reason = 'FILTERED_SCOPE'
    else if (entry.result?.status === 'RUNNING') reason = 'RUNNING'
    else if (!entry.complete) reason = entry.result ? 'INCOMPLETE_SUBRUNS' : 'NOT_STARTED'
    else if (groupRerun) reason = 'GROUP_RERUN'

    return {
      id: entry.id,
      action: groupRerun ? 'RUN' : 'SKIP',
      reason,
      scopeComplete: entry.complete && !groupRerun,
      subruns: entry.subruns.map((subrun) => ({ ...subrun }))
    }
  })

  return {
    filtered,
    scopeComplete: !filtered && planned.length > 0
      && planned.every((entry) => entry.action === 'SKIP'),
    entries: planned
  }
}

function selectedSubruns(definition, selection) {
  return definition.requiredSubruns.filter((subrun) => (
    hasSelection(selection, 'profiles', subrun.profile)
    && hasSelection(selection, 'viewports', subrun.viewport)
  ))
}

function subrunsPass(result, requiredSubruns) {
  if (!Array.isArray(result.subruns) || result.subruns.length !== requiredSubruns.length) {
    return false
  }
  return requiredSubruns.every((required) => (
    result.subruns.filter((actual) => (
      actual.id === required.id
      && actual.profile === required.profile
      && actual.viewport === required.viewport
      && actual.status === 'PASS'
    )).length === 1
  ))
}

function invalidAggregateInputReport() {
  return {
    verdict: 'FAIL',
    scopeComplete: false,
    counts: { PASS: 0, FAIL: 0, BLOCKED: 0, INVALID_TEST: 0, MISSING: 0 },
    issues: ['INVALID_AGGREGATE_INPUT']
  }
}

function hasOwnString(source, field) {
  return typeof ownDataDescriptor(source, field)?.value === 'string'
}

function validAggregateResultScalars(result) {
  if (!hasOwnString(result, 'id') || !hasOwnString(result, 'status')) return false
  if (ownDataDescriptor(result, 'status').value !== 'PASS') return true
  const subruns = ownDataDescriptor(result, 'subruns')?.value
  if (!Array.isArray(subruns)) return true
  return subruns.every((subrun) => (
    Boolean(subrun) && typeof subrun === 'object' && !Array.isArray(subrun)
    && ['id', 'profile', 'viewport', 'status'].every((field) => hasOwnString(subrun, field))
  ))
}

export function aggregateReport(state, results) {
  let stateSnapshot
  let resultsSnapshot
  try {
    stateSnapshot = inertIdentityValue(state)
    resultsSnapshot = inertIdentityValue(results)
    if (!stateSnapshot || typeof stateSnapshot !== 'object' || Array.isArray(stateSnapshot)
      || !Array.isArray(resultsSnapshot)) {
      throw new TypeError('INVALID_AGGREGATE_INPUT')
    }
  } catch {
    return invalidAggregateInputReport()
  }

  const selection = Object.hasOwn(stateSnapshot, 'selection')
    ? stateSnapshot.selection
    : Object.create(null)
  if (!validSelectionSnapshot(selection)
    || resultsSnapshot.some((result) => (
      !result || typeof result !== 'object' || Array.isArray(result)
    ))) {
    return invalidAggregateInputReport()
  }
  const invalidRegistry = registryIssue(
    stateSnapshot.definitions,
    stateSnapshot.registryFingerprint
  )
  if (invalidRegistry) {
    return {
      verdict: 'FAIL',
      scopeComplete: false,
      counts: { PASS: 0, FAIL: 0, BLOCKED: 0, INVALID_TEST: 0, MISSING: 0 },
      issues: [invalidRegistry]
    }
  }
  if (resultsSnapshot.some((result) => !validAggregateResultScalars(result))) {
    return invalidAggregateInputReport()
  }
  const resolved = resolveSelection(stateSnapshot.definitions, selection)
  const { filtered } = resolved
  const subrunsFiltered = ['profiles', 'viewports']
    .some((key) => selectionValues(selection, key)?.length)
  const definitions = resolved.entries.map(({ definition }) => definition)
  const selectedById = new Map(resolved.entries.map(({ definition, subruns }) => (
    [definition.id, subruns]
  )))
  const expectedIds = new Set(definitions.map(({ id }) => id))
  const byId = Map.groupBy(resultsSnapshot, ({ id }) => id)
  const counts = { PASS: 0, FAIL: 0, BLOCKED: 0, INVALID_TEST: 0, MISSING: 0 }
  const issues = [...resolved.issues]

  for (const result of resultsSnapshot) {
    if (!expectedIds.has(result.id)) issues.push(`UNEXPECTED_CASE: ${result.id}`)
  }
  for (const definition of definitions) {
    const matches = byId.get(definition.id) ?? []
    if (matches.length === 0) {
      counts.MISSING += 1
      issues.push(`MISSING_CASE: ${definition.id}`)
      continue
    }
    if (matches.length > 1) issues.push(`DUPLICATE_CASE: ${definition.id}`)
    const result = matches[0]
    if (!TERMINAL_STATUSES.has(result.status)) {
      issues.push(`INVALID_STATUS: ${definition.id}/${result.status}`)
      continue
    }
    if (result.status !== 'PASS') {
      counts[result.status] += 1
      if (result.status === 'INVALID_TEST') issues.push(`INVALID_TEST: ${definition.id}`)
      continue
    }

    const requiredSubruns = selectedById.get(definition.id)
    if (!subrunsPass(result, requiredSubruns)
      || (!subrunsFiltered && result.scopeComplete !== true)
      || (subrunsFiltered && result.scopeComplete !== false)) {
      issues.push(`INCOMPLETE_MATRIX: ${definition.id}`)
      continue
    }
    counts.PASS += 1
  }

  let verdict = 'PASS'
  if (counts.FAIL > 0 || counts.INVALID_TEST > 0 || issues.length > 0) verdict = 'FAIL'
  else if (counts.BLOCKED > 0) verdict = 'BLOCKED'
  else if (filtered) verdict = 'PARTIAL_PASS'

  return {
    verdict,
    scopeComplete: verdict === 'PASS',
    counts,
    issues
  }
}

function validXmlCodePoint(codePoint) {
  return codePoint === 0x9 || codePoint === 0xa || codePoint === 0xd
    || (codePoint >= 0x20 && codePoint <= 0xd7ff)
    || (codePoint >= 0xe000 && codePoint <= 0xfffd)
    || (codePoint >= 0x10000 && codePoint <= 0x10ffff)
}

function validXmlCodePoints(value) {
  for (const character of value) {
    if (!validXmlCodePoint(character.codePointAt(0))) return false
  }
  return true
}

function validXmlCharacterData(value) {
  if (!validXmlCodePoints(value) || value.includes(']]>')) return false
  let cursor = 0
  while (true) {
    const start = value.indexOf('&', cursor)
    if (start === -1) return true
    const entity = value.slice(start).match(
      /^&(amp|lt|gt|apos|quot|#\d+|#x[\da-fA-F]+);/
    )?.[0]
    if (!entity) return false
    if (entity.startsWith('&#')) {
      const hexadecimal = entity.startsWith('&#x')
      const digits = entity.slice(hexadecimal ? 3 : 2, -1)
      const codePoint = Number.parseInt(digits, hexadecimal ? 16 : 10)
      if (!validXmlCodePoint(codePoint)) return false
    }
    cursor = start + entity.length
  }
}

function xmlAttributes(source) {
  const attributes = new Map()
  let cursor = 0
  while (cursor < source.length) {
    while (cursor < source.length && XML_WHITESPACE.test(source[cursor])) cursor += 1
    if (cursor === source.length) break
    const name = source.slice(cursor).match(/^[A-Za-z_:][\w:.-]*/)?.[0]
    if (!name || attributes.has(name)) return null
    cursor += name.length
    while (cursor < source.length && XML_WHITESPACE.test(source[cursor])) cursor += 1
    if (source[cursor] !== '=') return null
    cursor += 1
    while (cursor < source.length && XML_WHITESPACE.test(source[cursor])) cursor += 1
    const quote = source[cursor]
    if (quote !== '"' && quote !== "'") return null
    const end = source.indexOf(quote, cursor + 1)
    if (end === -1) return null
    const value = source.slice(cursor + 1, end)
    if (value.includes('<') || !validXmlCharacterData(value)) return null
    attributes.set(name, value)
    cursor = end + 1
    if (cursor < source.length && !XML_WHITESPACE.test(source[cursor])) return null
  }
  const parsed = Object.create(null)
  for (const [name, value] of attributes) {
    Object.defineProperty(parsed, name, {
      value,
      enumerable: true,
      configurable: true,
      writable: true
    })
  }
  return parsed
}

function ownXmlAttribute(attributes, name) {
  return attributes && Object.hasOwn(attributes, name) ? attributes[name] : undefined
}

function validXmlDeclaration(attributes) {
  if (!['1.0', '1.1'].includes(ownXmlAttribute(attributes, 'version'))) return false
  const expectedFields = ['version']
  if (Object.hasOwn(attributes, 'encoding')) {
    if (!/^utf-8$/i.test(ownXmlAttribute(attributes, 'encoding'))) return false
    expectedFields.push('encoding')
  }
  if (Object.hasOwn(attributes, 'standalone')) {
    if (!['yes', 'no'].includes(ownXmlAttribute(attributes, 'standalone'))) return false
    expectedFields.push('standalone')
  }
  return Object.keys(attributes).join(',') === expectedFields.join(',')
}

function xmlOpening(markup) {
  const selfClosing = markup.endsWith('/>')
  const inner = markup.slice(1, selfClosing ? -2 : -1)
  const name = inner.match(/^([A-Za-z_:][\w:.-]*)/)?.[1]
  if (!name) return null
  const remainder = inner.slice(name.length)
  if (remainder && !XML_WHITESPACE.test(remainder[0])) return null
  const attributes = xmlAttributes(remainder)
  return attributes && { name, attributes, selfClosing }
}

function xmlMarkupEnd(source, start, trackSubset = false) {
  let quote = ''
  let subsetDepth = 0
  for (let index = start + 1; index < source.length; index += 1) {
    const character = source[index]
    if (quote) {
      if (character === quote) quote = ''
      continue
    }
    if (character === '"' || character === "'") {
      quote = character
      continue
    }
    if (trackSubset && character === '[') subsetDepth += 1
    else if (trackSubset && character === ']') subsetDepth -= 1
    else if (character === '>' && subsetDepth === 0) return index
  }
  return -1
}

function surefireRootAttributes(source) {
  if (!validXmlCodePoints(source)) return null
  const stack = []
  let cursor = 0
  let rootAttributes = null
  let declarationSeen = false
  const outcomeElements = { failure: 0, error: 0, skipped: 0 }

  while (cursor < source.length) {
    const start = source.indexOf('<', cursor)
    const textEnd = start === -1 ? source.length : start
    const characterData = source.slice(cursor, textEnd)
    if (!validXmlCharacterData(characterData)
      || (stack.length === 0 && !XML_WHITESPACE_ONLY.test(characterData))) return null
    if (start === -1) break

    if (source.startsWith('<!--', start)) {
      const end = source.indexOf('-->', start + 4)
      if (end === -1) return null
      const comment = source.slice(start + 4, end)
      if (comment.includes('--') || comment.endsWith('-') || !validXmlCodePoints(comment)) {
        return null
      }
      cursor = end + 3
      continue
    }
    if (source.startsWith('<![CDATA[', start)) {
      if (stack.length === 0) return null
      const end = source.indexOf(']]>', start + 9)
      if (end === -1 || !validXmlCodePoints(source.slice(start + 9, end))) return null
      cursor = end + 3
      continue
    }
    if (source.startsWith('<?', start)) {
      const end = source.indexOf('?>', start + 2)
      if (end === -1) return null
      if (start !== 0 || declarationSeen || rootAttributes || stack.length > 0
        || !source.startsWith('<?xml', start)
        || !XML_WHITESPACE.test(source[start + 5])) return null
      const declarationAttributes = xmlAttributes(source.slice(start + 5, end))
      if (!validXmlDeclaration(declarationAttributes)) return null
      declarationSeen = true
      cursor = end + 2
      continue
    }
    if (source.startsWith('<!DOCTYPE', start)) {
      return null
    }

    const end = xmlMarkupEnd(source, start)
    if (end === -1) return null
    const markup = source.slice(start, end + 1)
    const closing = markup.match(/^<\/([A-Za-z_:][\w:.-]*)[ \t\r\n]*>$/)
    if (closing) {
      if (stack.pop() !== closing[1]) return null
      cursor = end + 1
      continue
    }
    const opening = xmlOpening(markup)
    if (!opening) return null
    if (stack.length === 0) {
      if (rootAttributes || opening.name !== 'testsuite') return null
      rootAttributes = opening.attributes
    }
    if (Object.hasOwn(outcomeElements, opening.name)) outcomeElements[opening.name] += 1
    if (!opening.selfClosing) stack.push(opening.name)
    cursor = end + 1
  }

  return stack.length === 0 && rootAttributes
    ? { attributes: rootAttributes, outcomeElements }
    : null
}

function parseSurefireCounter(value) {
  if (typeof value !== 'string' || !/^\d+$/.test(value)) return null
  const counter = Number(value)
  return Number.isSafeInteger(counter) ? counter : null
}

function parseInvocationStartedAt(value, errorMessage = 'SUREFIRE_INVALID_INVOCATION_TIME') {
  if (typeof value === 'string') {
    if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(value)) {
      throw new Error(errorMessage)
    }
    const startedAt = new Date(value)
    if (!Number.isFinite(startedAt.getTime()) || startedAt.toISOString() !== value) {
      throw new Error(errorMessage)
    }
    return startedAt
  }

  if (!value || typeof value !== 'object' || types.isProxy(value)
    || Object.getPrototypeOf(value) !== Date.prototype
    || Reflect.ownKeys(value).length > 0) {
    throw new Error(errorMessage)
  }
  const milliseconds = Date.prototype.getTime.call(value)
  if (!Number.isFinite(milliseconds)) throw new Error(errorMessage)
  return new Date(milliseconds)
}

function snapshotExpectedClasses(value) {
  if (!value || typeof value !== 'object') {
    throw new Error('SUREFIRE_EXPECTED_CLASSES_REQUIRED')
  }
  if (types.isProxy(value)) throw new TypeError('UNSAFE_PERSISTENCE_VALUE: Proxy')
  if (!Array.isArray(value)) throw new Error('SUREFIRE_EXPECTED_CLASSES_REQUIRED')
  if (Object.getPrototypeOf(value) !== Array.prototype) {
    throw new TypeError('UNSAFE_PERSISTENCE_VALUE: non-plain object')
  }

  const descriptors = Object.getOwnPropertyDescriptors(value)
  for (const descriptor of Object.values(descriptors)) {
    if ('get' in descriptor || 'set' in descriptor) {
      throw new TypeError('UNSAFE_PERSISTENCE_VALUE: accessor')
    }
  }
  if (descriptors.length.value === 0) {
    throw new Error('SUREFIRE_EXPECTED_CLASSES_REQUIRED')
  }
  const extraField = Reflect.ownKeys(descriptors).find((field) => (
    field !== 'length' && (typeof field !== 'string'
      || !/^(?:0|[1-9]\d*)$/.test(field)
      || Number(field) >= descriptors.length.value)
  ))
  if (extraField !== undefined) {
    throw new Error(`SUREFIRE_EXPECTED_CLASS_INVALID: index ${String(extraField)}`)
  }
  const classes = []
  for (let index = 0; index < descriptors.length.value; index += 1) {
    const descriptor = descriptors[index]
    if (!descriptor || typeof descriptor.value !== 'string') {
      throw new Error(`SUREFIRE_EXPECTED_CLASS_INVALID: index ${index}`)
    }
    classes.push(descriptor.value)
  }
  if (new Set(classes).size !== classes.length) {
    const duplicateClass = classes.find((className, index) => (
      classes.indexOf(className) !== index
    ))
    throw new Error(`SUREFIRE_EXPECTED_CLASSES_DUPLICATE: ${duplicateClass}`)
  }
  const invalidClass = classes.find((className) => (
    typeof className !== 'string' || !/^[A-Za-z_$][A-Za-z\d_$]*$/.test(className)
  ))
  if (invalidClass !== undefined) {
    throw new Error(`SUREFIRE_EXPECTED_CLASS_INVALID: ${invalidClass}`)
  }
  return classes
}

function readStableReport(path) {
  let descriptor
  try {
    descriptor = openSync(path, 'r')
    const before = fstatSync(descriptor, { bigint: true })
    const bytes = readFileSync(descriptor)
    const after = fstatSync(descriptor, { bigint: true })
    if (['dev', 'ino', 'size', 'mtimeNs', 'ctimeNs']
      .some((field) => before[field] !== after[field])) {
      throw new Error('SUREFIRE_UNSTABLE_REPORT')
    }
    return {
      bytes,
      modifiedAt: new Date(Number(after.mtimeNs / 1_000_000n)).toISOString()
    }
  } finally {
    if (descriptor !== undefined) closeSync(descriptor)
  }
}

export function parseSurefireReports(reportDir, expectedClasses, invocationStartedAt) {
  const classes = snapshotExpectedClasses(expectedClasses)
  const startedAt = parseInvocationStartedAt(invocationStartedAt)
  const verificationTime = new Date()
  const expected = new Set(classes)
  const found = []
  for (const name of readdirSync(reportDir).filter((file) => file.endsWith('.xml')).toSorted()) {
    const path = `${reportDir}/${name}`
    const { bytes, modifiedAt } = readStableReport(path)
    let source
    try {
      source = UTF8_DECODER.decode(bytes)
    } catch {
      throw new Error(`SUREFIRE_MALFORMED_XML: ${name}`)
    }
    const structure = surefireRootAttributes(source)
    if (!structure) {
      throw new Error(`SUREFIRE_MALFORMED_XML: ${name}`)
    }
    const { attributes, outcomeElements } = structure
    const suiteName = ownXmlAttribute(attributes, 'name')
    const className = suiteName?.split('.').at(-1)
    if (!expected.has(className)) continue
    found.push({
      className,
      suiteName,
      file: name,
      modifiedAt,
      tests: parseSurefireCounter(ownXmlAttribute(attributes, 'tests')),
      skipped: parseSurefireCounter(ownXmlAttribute(attributes, 'skipped')),
      failures: parseSurefireCounter(ownXmlAttribute(attributes, 'failures')),
      errors: parseSurefireCounter(ownXmlAttribute(attributes, 'errors')),
      outcomeElements
    })
  }
  for (const className of classes) {
    const matches = found.filter((suite) => suite.className === className)
    if (matches.length === 0) throw new Error(`SUREFIRE_MISSING_CLASS: ${className}`)
    if (matches.length > 1) throw new Error(`SUREFIRE_DUPLICATE_CLASS: ${className}`)
    if (new Date(matches[0].modifiedAt) < startedAt) {
      throw new Error(`SUREFIRE_STALE_REPORT: ${className}`)
    }
    if (new Date(matches[0].modifiedAt).getTime()
      > verificationTime.getTime() + SUREFIRE_FUTURE_MTIME_TOLERANCE_MS) {
      throw new Error(`SUREFIRE_FUTURE_REPORT: ${className}`)
    }
    const suite = matches[0]
    if (!Number.isSafeInteger(suite.tests) || suite.tests <= 0
      || suite.skipped !== 0 || suite.failures !== 0 || suite.errors !== 0
      || Object.values(suite.outcomeElements).some((count) => count > 0)) {
      throw new Error(`SUREFIRE_INVALID_SUITE: ${className}`)
    }
  }
  const suites = classes.map((className) => (
    found.find((suite) => suite.className === className)
  )).filter(Boolean).map(({ outcomeElements: _, ...suite }) => suite)
  const totals = suites.reduce((sum, suite) => ({
    tests: sum.tests + suite.tests,
    skipped: sum.skipped + suite.skipped,
    failures: sum.failures + suite.failures,
    errors: sum.errors + suite.errors
  }), { tests: 0, skipped: 0, failures: 0, errors: 0 })

  return {
    status: 'PASS',
    invocationStartedAt: startedAt.toISOString(),
    expectedClasses: classes,
    suites,
    totals
  }
}

const SUREFIRE_CLI_OPTIONS = new Set(['reports', 'classes', 'started-at', 'output'])
const SUREFIRE_CLI_REQUIRED_OPTIONS = ['reports', 'classes', 'started-at', 'output']

function canonicalOutputTarget(output) {
  try {
    let existingAncestor = resolve(output)
    const missingSegments = []
    while (!existsSync(existingAncestor)) {
      const parent = dirname(existingAncestor)
      if (parent === existingAncestor) return undefined
      missingSegments.unshift(basename(existingAncestor))
      existingAncestor = parent
    }
    return resolve(realpathSync(existingAncestor), ...missingSegments)
  } catch {
    return undefined
  }
}

function platformPathIdentity(path) {
  return process.platform === 'win32' ? path.toLowerCase() : path
}

function cliOutputCandidate(output) {
  const alias = resolve(output)
  const target = canonicalOutputTarget(alias)
  if (!target) return undefined
  try {
    if (!existsSync(target)) {
      return {
        alias,
        aliasIdentity: platformPathIdentity(alias),
        comparisonIdentity: `path:${platformPathIdentity(target)}`
      }
    }
    const { dev, ino } = statSync(target, { bigint: true })
    return {
      alias,
      aliasIdentity: platformPathIdentity(alias),
      comparisonIdentity: `file:${dev}:${ino}`
    }
  } catch {
    return undefined
  }
}

function suppliedCliOutputs(rawArguments) {
  const outputs = rawArguments
    .filter((argument) => argument.startsWith('--output='))
    .map((argument) => argument.slice('--output='.length))
    .filter(Boolean)
  const candidates = outputs.map(cliOutputCandidate)
  if (candidates.length === 0 || candidates.some((candidate) => !candidate)) return undefined
  const identities = new Set(candidates.map(({ comparisonIdentity }) => comparisonIdentity))
  if (identities.size !== 1) return undefined
  return {
    aliases: [...new Map(candidates.map((candidate) => (
      [candidate.aliasIdentity, candidate.alias]
    ))).values()]
  }
}

function invalidateExistingOutputAliases(aliases, source) {
  const opened = []
  let failed = false
  for (const alias of aliases) {
    if (!existsSync(alias)) continue
    let descriptor
    try {
      descriptor = openSync(alias, 'r+')
      const descriptorStat = fstatSync(descriptor, { bigint: true })
      const pathStat = statSync(alias, { bigint: true })
      if (!descriptorStat.isFile()
        || descriptorStat.dev !== pathStat.dev
        || descriptorStat.ino !== pathStat.ino) {
        failed = true
        continue
      }
      opened.push({
        descriptor,
        identity: `${descriptorStat.dev}:${descriptorStat.ino}`
      })
      descriptor = undefined
    } catch {
      failed = true
    } finally {
      if (descriptor !== undefined) {
        try {
          closeSync(descriptor)
        } catch {
          failed = true
        }
      }
    }
  }

  const invalidated = new Set()
  try {
    for (const { descriptor, identity } of opened) {
      if (invalidated.has(identity)) continue
      try {
        ftruncateSync(descriptor, 0)
        writeFileSync(descriptor, source, 'utf8')
        fsyncSync(descriptor)
        invalidated.add(identity)
      } catch {
        failed = true
      }
    }
  } finally {
    for (const { descriptor } of opened) {
      try {
        closeSync(descriptor)
      } catch {
        failed = true
      }
    }
  }
  return failed
}

function outputIsTerminalPass(output) {
  try {
    const evidence = JSON.parse(readFileSync(output, 'utf8'))
    return Boolean(evidence) && typeof evidence === 'object'
      && Object.hasOwn(evidence, 'status') && evidence.status === 'PASS'
  } catch {
    return false
  }
}

function parseCliArguments(rawArguments) {
  const [command, ...rawOptions] = rawArguments
  if (!command || command.startsWith('--')) throw new Error('CLI_COMMAND_REQUIRED')
  if (command !== 'verify-surefire') throw new Error(`CLI_UNKNOWN_COMMAND: ${command}`)

  const options = ownDataDictionary()
  for (const argument of rawOptions) {
    const match = argument.match(/^--([a-z][a-z-]*)=(.*)$/)
    if (!match) throw new Error(`CLI_MALFORMED_OPTION: ${argument}`)
    const [, name, value] = match
    if (!SUREFIRE_CLI_OPTIONS.has(name)) throw new Error(`CLI_UNKNOWN_OPTION: ${name}`)
    if (Object.hasOwn(options, name)) throw new Error(`CLI_DUPLICATE_OPTION: ${name}`)
    if (!value) throw new Error(`CLI_OPTION_REQUIRED: ${name}`)
    defineOwnData(options, name, value)
  }
  for (const name of SUREFIRE_CLI_REQUIRED_OPTIONS) {
    if (!Object.hasOwn(options, name)) throw new Error(`CLI_OPTION_REQUIRED: ${name}`)
  }

  let classes
  try {
    classes = snapshotExpectedClasses(options.classes.split(','))
  } catch {
    throw new Error('CLI_INVALID_OPTION: classes')
  }
  parseInvocationStartedAt(options['started-at'], 'CLI_INVALID_OPTION: started-at')
  return { options, classes }
}

function isMainModule() {
  if (!process.argv[1]) return false
  try {
    return realpathSync(process.argv[1]) === realpathSync(fileURLToPath(import.meta.url))
  } catch {
    return false
  }
}

function cliDiagnosticCode(error) {
  const code = typeof error?.message === 'string'
    ? error.message.match(/^([A-Z][A-Z\d]*(?:_[A-Z\d]+)+)(?::|$)/)?.[1]
    : undefined
  return SAFE_DIAGNOSTIC_CODES.has(code) ? code : 'CLI_INTERNAL_ERROR'
}

if (isMainModule()) {
  const rawArguments = process.argv.slice(2)
  const outputs = suppliedCliOutputs(rawArguments)
  try {
    const { options, classes } = parseCliArguments(rawArguments)
    const result = parseSurefireReports(
      options.reports,
      classes,
      options['started-at']
    )
    writeCaseResultAtomic(options.output, result)
  } catch (error) {
    const diagnostic = cliDiagnosticCode(error)
    const aliases = outputs?.aliases ?? []
    const failure = { status: 'FAIL', error: diagnostic }
    const failureSource = ownDataSerializationSource(failure)
    let writeFailed = invalidateExistingOutputAliases(aliases, failureSource)
    for (const output of aliases) {
      try {
        writeCaseResultAtomic(output, { status: 'FAIL', error: diagnostic })
      } catch {
        writeFailed = true
      }
    }
    const staleAliases = aliases.filter(outputIsTerminalPass)
    if (staleAliases.length > 0) {
      writeFailed = true
      if (invalidateExistingOutputAliases(staleAliases, failureSource)) writeFailed = true
    }
    if (aliases.some(outputIsTerminalPass)) writeFailed = true
    if (writeFailed) console.error('CLI_WRITE_FAILED')
    console.error(diagnostic)
    process.exitCode = 1
  }
}
