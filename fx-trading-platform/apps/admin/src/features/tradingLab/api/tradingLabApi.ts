import { apiDelete, apiGet, apiPost } from '../../../services/apiClient.ts'

const RUN_STATES = [
  'DRAFT',
  'VALIDATING',
  'QUEUED',
  'RESETTING',
  'RUNNING',
  'PAUSED',
  'CANCELLING',
  'CANCELLED',
  'FAILED',
  'COMPLETED',
  'CLEANING',
] as const
const SCENARIO_STATUSES = ['DRAFT', 'FROZEN'] as const
const RUN_ACTIONS = ['pause', 'resume', 'cancel'] as const
const ENVIRONMENT_ACTIONS = ['start', 'stop', 'restart'] as const
const REPORT_STATUSES = ['COMPLETED', 'FAILED', 'CANCELLED'] as const
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const SHA256_PATTERN = /^[0-9a-f]{64}$/
const INSTANT_PATTERN =
  /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/
const MAX_JSON_DEPTH = 64
const MAX_JSON_NODES = 100_000
const MAX_JSON_COLLECTION_SIZE = 10_000
const MAX_JSON_STRING_LENGTH = 1024 * 1024
const MAX_TOKEN_LENGTH = 16_384
const ESTIMATED_PRINT_PAGE_BYTES = 4_096

export const TRADING_LAB_REPORT_PRINT_THRESHOLD_BYTES = 52_428_800

export type TradingLabRunState = (typeof RUN_STATES)[number]
export type TradingLabRunControlAction = (typeof RUN_ACTIONS)[number]
export type TradingLabEnvironmentAction = (typeof ENVIRONMENT_ACTIONS)[number]
export type TradingLabJsonPrimitive = string | number | boolean | null
export type TradingLabJsonValue =
  | TradingLabJsonPrimitive
  | readonly TradingLabJsonValue[]
  | { readonly [key: string]: TradingLabJsonValue }
export type TradingLabJsonObject = {
  readonly [key: string]: TradingLabJsonValue
}

export type TradingLabRunResponse = Readonly<{
  id: string
  scenarioId: string
  reportId: string | null
  state: TradingLabRunState
  queueSequence: number
  pauseRequested: boolean
  cancelRequested: boolean
  virtualStartedAt: string | null
  virtualCurrentAt: string | null
  processedTicks: number
  totalTicks: number
  speedMultiplier: number
  currentStep: number
  failureCode: string | null
  failureMessage: string | null
  configSnapshotHash: string
  modelVersion: string
  symbolConfigVersion: string
  codeVersion: string
  createdBy: string
  createdAt: string
  updatedAt: string
  startedAt: string | null
  finishedAt: string | null
  version: number
}>

export type TradingLabScenarioResponse = Readonly<{
  id: string
  name: string
  description: string | null
  status: (typeof SCENARIO_STATUSES)[number]
  negativeMode: boolean
  seed: string
  modelVersion: string
  scenario: TradingLabJsonObject
  configSnapshot: TradingLabJsonObject
  configSnapshotHash: string
  symbolConfigVersion: string
  codeVersion: string
  createdBy: string
  updatedBy: string
  createdAt: string
  updatedAt: string
  version: number
}>

export type TradingLabRunControlResult = Readonly<{
  runId: string
  state: TradingLabRunState
  runVersion: number
  pauseRequested: boolean
  cancelRequested: boolean
}>

export type TradingLabEnvironmentStatusResponse = Readonly<{
  relayRunning: boolean
  validationHealth: string
}>

export type TradingLabEnvironmentActionResponse = Readonly<{
  action: TradingLabEnvironmentAction
  relayRunning: boolean
}>

export type TradingLabReportResponse = Readonly<{
  id: string
  runId: string
  scenarioId: string
  status: (typeof REPORT_STATUSES)[number]
  modelVersion: string
  configSnapshotHash: string
  codeVersion: string
  uncompressedBytes: number
  compressedBytes: number
  chunkCount: number
  retainedUntil: string
  permanent: boolean
  failureCode: string | null
  failureMessage: string | null
  createdAt: string
  completedAt: string
  version: number
}>

export type TradingLabReportPermanentResponse = Readonly<{
  reportId: string
  permanent: boolean
  version: number
}>

export type TradingLabReportPrintInfoResponse = Readonly<{
  uncompressedBytes: number
  estimatedPageCount: number
  thresholdBytes: number
  requiresConfirmation: boolean
}>

export type TradingLabReportPrintConfirmationResponse = Readonly<{
  reportId: string
  token: string
  expiresAt: string
}>

export type TradingLabScenarioCreateInput = Readonly<{
  name: string
  description: string | null
  negativeMode: boolean
  seed: string
  modelVersion: string
  scenario: TradingLabJsonObject
  configSnapshot: TradingLabJsonObject
  configSnapshotHash: string
  expectedVersion?: number | null
}>

export type TradingLabRunCreateInput = Readonly<{
  scenarioVersion: number
  configSnapshotHash: string
  localCalculation: TradingLabJsonObject
}>

export class TradingLabApiContractError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'TradingLabApiContractError'
  }
}

export async function getTradingLabRun(
  runId: string,
  token: string,
): Promise<TradingLabRunResponse> {
  const id = requireCanonicalUuid(runId, 'Run ID')
  const explicitToken = requireAdminToken(token)
  return parseRunResponse(
    await apiGet<unknown>(`/api/admin/trading-lab/runs/${id}`, explicitToken),
  )
}

export async function getTradingLabScenario(
  scenarioId: string,
  token: string,
): Promise<TradingLabScenarioResponse> {
  const id = requireCanonicalUuid(scenarioId, 'Scenario ID')
  const explicitToken = requireAdminToken(token)
  return parseScenarioResponse(
    await apiGet<unknown>(`/api/admin/trading-lab/scenarios/${id}`, explicitToken),
  )
}

export async function createTradingLabScenario(
  input: TradingLabScenarioCreateInput,
  token: string,
): Promise<TradingLabScenarioResponse> {
  const body = parseScenarioCreateInput(input)
  const explicitToken = requireAdminToken(token)
  return parseScenarioResponse(
    await apiPost<unknown>(
      '/api/admin/trading-lab/scenarios',
      body,
      explicitToken,
      { retryOnAuthFailure: false },
    ),
  )
}

export async function createTradingLabRun(
  scenarioId: string,
  input: TradingLabRunCreateInput,
  token: string,
): Promise<TradingLabRunResponse> {
  const id = requireCanonicalUuid(scenarioId, 'Scenario ID')
  const body = parseRunCreateInput(input)
  const explicitToken = requireAdminToken(token)
  return parseRunResponse(
    await apiPost<unknown>(
      `/api/admin/trading-lab/scenarios/${id}/runs`,
      body,
      explicitToken,
      { retryOnAuthFailure: false },
    ),
  )
}

export async function controlTradingLabRun(
  runId: string,
  action: TradingLabRunControlAction,
  token: string,
): Promise<TradingLabRunControlResult> {
  const id = requireCanonicalUuid(runId, 'Run ID')
  if (!isOneOf(action, RUN_ACTIONS)) {
    throw new TradingLabApiContractError('Trading Lab run action is invalid')
  }
  const explicitToken = requireAdminToken(token)
  return parseRunControlResult(
    await apiPost<unknown>(
      `/api/admin/trading-lab/runs/${id}/${action}`,
      {},
      explicitToken,
    ),
  )
}

export async function getTradingLabEnvironment(
  token: string,
): Promise<TradingLabEnvironmentStatusResponse> {
  const explicitToken = requireAdminToken(token)
  return parseEnvironmentStatus(
    await apiGet<unknown>(
      '/api/admin/trading-lab/environment',
      explicitToken,
    ),
  )
}

export async function controlTradingLabEnvironment(
  action: TradingLabEnvironmentAction,
  token: string,
): Promise<TradingLabEnvironmentActionResponse> {
  if (!isOneOf(action, ENVIRONMENT_ACTIONS)) {
    throw new TradingLabApiContractError(
      'Trading Lab environment action is invalid',
    )
  }
  const explicitToken = requireAdminToken(token)
  return parseEnvironmentAction(
    await apiPost<unknown>(
      `/api/admin/trading-lab/environment/${action}`,
      {},
      explicitToken,
    ),
  )
}

export async function getTradingLabReport(
  reportId: string,
  token: string,
): Promise<TradingLabReportResponse> {
  const id = requireCanonicalUuid(reportId, 'Report ID')
  const explicitToken = requireAdminToken(token)
  return parseReportResponse(
    await apiGet<unknown>(
      `/api/admin/trading-lab/reports/${id}`,
      explicitToken,
    ),
    id,
  )
}

export async function deleteTradingLabReport(
  reportId: string,
  token: string,
): Promise<void> {
  const id = requireCanonicalUuid(reportId, 'Report ID')
  const explicitToken = requireAdminToken(token)
  const response = await apiDelete<unknown>(
    `/api/admin/trading-lab/reports/${id}`,
    undefined,
    explicitToken,
  )
  if (response !== null) {
    throw new TradingLabApiContractError(
      'Invalid Trading Lab delete response',
    )
  }
}

export async function setTradingLabReportPermanent(
  reportId: string,
  permanent: boolean,
  token: string,
): Promise<TradingLabReportPermanentResponse> {
  const id = requireCanonicalUuid(reportId, 'Report ID')
  if (typeof permanent !== 'boolean') {
    throw new TradingLabApiContractError(
      'Trading Lab report permanent must be a boolean',
    )
  }
  const explicitToken = requireAdminToken(token)
  return parseReportPermanentResponse(
    await apiPost<unknown>(
      `/api/admin/trading-lab/reports/${id}/permanent`,
      { permanent },
      explicitToken,
    ),
    id,
  )
}

export async function getTradingLabReportPrintInfo(
  reportId: string,
  token: string,
): Promise<TradingLabReportPrintInfoResponse> {
  const id = requireCanonicalUuid(reportId, 'Report ID')
  const explicitToken = requireAdminToken(token)
  return parseReportPrintInfoResponse(
    await apiGet<unknown>(
      `/api/admin/trading-lab/reports/${id}/print-info`,
      explicitToken,
    ),
  )
}

export async function createTradingLabReportPrintConfirmation(
  reportId: string,
  token: string,
): Promise<TradingLabReportPrintConfirmationResponse> {
  const id = requireCanonicalUuid(reportId, 'Report ID')
  const explicitToken = requireAdminToken(token)
  return parseReportPrintConfirmationResponse(
    await apiPost<unknown>(
      `/api/admin/trading-lab/reports/${id}/print-confirmation`,
      {},
      explicitToken,
      { retryOnAuthFailure: false },
    ),
    id,
  )
}

function parseRunResponse(value: unknown): TradingLabRunResponse {
  const label = 'Invalid Trading Lab run response'
  const record = exactRecord(value, [
    'id',
    'scenarioId',
    'reportId',
    'state',
    'queueSequence',
    'pauseRequested',
    'cancelRequested',
    'virtualStartedAt',
    'virtualCurrentAt',
    'processedTicks',
    'totalTicks',
    'speedMultiplier',
    'currentStep',
    'failureCode',
    'failureMessage',
    'configSnapshotHash',
    'modelVersion',
    'symbolConfigVersion',
    'codeVersion',
    'createdBy',
    'createdAt',
    'updatedAt',
    'startedAt',
    'finishedAt',
    'version',
  ], label)

  return {
    id: responseUuid(record.id, label),
    scenarioId: responseUuid(record.scenarioId, label),
    reportId: nullable(record.reportId, (item) => responseUuid(item, label), label),
    state: responseOneOf(record.state, RUN_STATES, label),
    queueSequence: responseNonNegativeInteger(record.queueSequence, label),
    pauseRequested: responseBoolean(record.pauseRequested, label),
    cancelRequested: responseBoolean(record.cancelRequested, label),
    virtualStartedAt: nullable(record.virtualStartedAt, (item) => responseInstant(item, label), label),
    virtualCurrentAt: nullable(record.virtualCurrentAt, (item) => responseInstant(item, label), label),
    processedTicks: responseNonNegativeInteger(record.processedTicks, label),
    totalTicks: responseNonNegativeInteger(record.totalTicks, label),
    speedMultiplier: responsePositiveNumber(record.speedMultiplier, label),
    currentStep: responseNonNegativeInteger(record.currentStep, label),
    failureCode: nullable(record.failureCode, (item) => responseString(item, 80, label), label),
    failureMessage: nullable(record.failureMessage, (item) => responseString(item, 10_000, label), label),
    configSnapshotHash: responseHash(record.configSnapshotHash, label),
    modelVersion: responseNonBlankString(record.modelVersion, 80, label),
    symbolConfigVersion: responseNonBlankString(record.symbolConfigVersion, 120, label),
    codeVersion: responseNonBlankString(record.codeVersion, 160, label),
    createdBy: responseUuid(record.createdBy, label),
    createdAt: responseInstant(record.createdAt, label),
    updatedAt: responseInstant(record.updatedAt, label),
    startedAt: nullable(record.startedAt, (item) => responseInstant(item, label), label),
    finishedAt: nullable(record.finishedAt, (item) => responseInstant(item, label), label),
    version: responseNonNegativeInteger(record.version, label),
  }
}

function parseScenarioResponse(value: unknown): TradingLabScenarioResponse {
  const label = 'Invalid Trading Lab scenario response'
  const record = exactRecord(value, [
    'id',
    'name',
    'description',
    'status',
    'negativeMode',
    'seed',
    'modelVersion',
    'scenario',
    'configSnapshot',
    'configSnapshotHash',
    'symbolConfigVersion',
    'codeVersion',
    'createdBy',
    'updatedBy',
    'createdAt',
    'updatedAt',
    'version',
  ], label)

  return {
    id: responseUuid(record.id, label),
    name: responseNonBlankString(record.name, 200, label),
    description: nullable(record.description, (item) => responseString(item, 10_000, label), label),
    status: responseOneOf(record.status, SCENARIO_STATUSES, label),
    negativeMode: responseBoolean(record.negativeMode, label),
    seed: responseNonBlankString(record.seed, 256, label),
    modelVersion: responseNonBlankString(record.modelVersion, 80, label),
    scenario: responseJsonObject(record.scenario, label),
    configSnapshot: responseJsonObject(record.configSnapshot, label),
    configSnapshotHash: responseHash(record.configSnapshotHash, label),
    symbolConfigVersion: responseNonBlankString(record.symbolConfigVersion, 120, label),
    codeVersion: responseNonBlankString(record.codeVersion, 160, label),
    createdBy: responseUuid(record.createdBy, label),
    updatedBy: responseUuid(record.updatedBy, label),
    createdAt: responseInstant(record.createdAt, label),
    updatedAt: responseInstant(record.updatedAt, label),
    version: responseNonNegativeInteger(record.version, label),
  }
}

function parseRunControlResult(value: unknown): TradingLabRunControlResult {
  const label = 'Invalid Trading Lab run control response'
  const record = exactRecord(value, [
    'runId',
    'state',
    'runVersion',
    'pauseRequested',
    'cancelRequested',
  ], label)

  return {
    runId: responseUuid(record.runId, label),
    state: responseOneOf(record.state, RUN_STATES, label),
    runVersion: responseNonNegativeInteger(record.runVersion, label),
    pauseRequested: responseBoolean(record.pauseRequested, label),
    cancelRequested: responseBoolean(record.cancelRequested, label),
  }
}

function parseEnvironmentStatus(
  value: unknown,
): TradingLabEnvironmentStatusResponse {
  const label = 'Invalid Trading Lab environment response'
  const record = exactRecord(
    value,
    ['relayRunning', 'validationHealth'],
    label,
  )
  return {
    relayRunning: responseBoolean(record.relayRunning, label),
    validationHealth: responseNonBlankString(
      record.validationHealth,
      128,
      label,
    ),
  }
}

function parseEnvironmentAction(
  value: unknown,
): TradingLabEnvironmentActionResponse {
  const label = 'Invalid Trading Lab environment control response'
  const record = exactRecord(value, ['action', 'relayRunning'], label)
  return {
    action: responseOneOf(record.action, ENVIRONMENT_ACTIONS, label),
    relayRunning: responseBoolean(record.relayRunning, label),
  }
}

function parseReportResponse(
  value: unknown,
  expectedReportId: string,
): TradingLabReportResponse {
  const label = 'Invalid Trading Lab report response'
  const record = exactRecord(value, [
    'id',
    'runId',
    'scenarioId',
    'status',
    'modelVersion',
    'configSnapshotHash',
    'codeVersion',
    'uncompressedBytes',
    'compressedBytes',
    'chunkCount',
    'retainedUntil',
    'permanent',
    'failureCode',
    'failureMessage',
    'createdAt',
    'completedAt',
    'version',
  ], label)
  const id = responseUuid(record.id, label)
  if (id !== expectedReportId) {
    throw new TradingLabApiContractError(label)
  }
  return {
    id,
    runId: responseUuid(record.runId, label),
    scenarioId: responseUuid(record.scenarioId, label),
    status: responseOneOf(record.status, REPORT_STATUSES, label),
    modelVersion: responseNonBlankString(record.modelVersion, 80, label),
    configSnapshotHash: responseHash(record.configSnapshotHash, label),
    codeVersion: responseNonBlankString(record.codeVersion, 160, label),
    uncompressedBytes: responseNonNegativeInteger(
      record.uncompressedBytes,
      label,
    ),
    compressedBytes: responseNonNegativeInteger(record.compressedBytes, label),
    chunkCount: responseNonNegativeInteger(record.chunkCount, label),
    retainedUntil: responseInstant(record.retainedUntil, label),
    permanent: responseBoolean(record.permanent, label),
    failureCode: nullable(
      record.failureCode,
      (item) => responseString(item, 80, label),
      label,
    ),
    failureMessage: nullable(
      record.failureMessage,
      (item) => responseString(item, 10_000, label),
      label,
    ),
    createdAt: responseInstant(record.createdAt, label),
    completedAt: responseInstant(record.completedAt, label),
    version: responseNonNegativeInteger(record.version, label),
  }
}

function parseReportPermanentResponse(
  value: unknown,
  expectedReportId: string,
): TradingLabReportPermanentResponse {
  const label = 'Invalid Trading Lab permanent response'
  const record = exactRecord(
    value,
    ['reportId', 'permanent', 'version'],
    label,
  )
  const reportId = responseUuid(record.reportId, label)
  if (reportId !== expectedReportId) {
    throw new TradingLabApiContractError(label)
  }
  return {
    reportId,
    permanent: responseBoolean(record.permanent, label),
    version: responseNonNegativeInteger(record.version, label),
  }
}

function parseReportPrintInfoResponse(
  value: unknown,
): TradingLabReportPrintInfoResponse {
  const label = 'Invalid Trading Lab print-info response'
  const record = exactRecord(
    value,
    [
      'uncompressedBytes',
      'estimatedPageCount',
      'thresholdBytes',
      'requiresConfirmation',
    ],
    label,
  )
  const uncompressedBytes = responseNonNegativeInteger(
    record.uncompressedBytes,
    label,
  )
  const estimatedPageCount = responseNonNegativeInteger(
    record.estimatedPageCount,
    label,
  )
  const thresholdBytes = responseNonNegativeInteger(
    record.thresholdBytes,
    label,
  )
  const requiresConfirmation = responseBoolean(
    record.requiresConfirmation,
    label,
  )
  const expectedPageCount = Math.floor(
    (Math.max(uncompressedBytes, 1) - 1) / ESTIMATED_PRINT_PAGE_BYTES,
  ) + 1
  if (
    thresholdBytes !== TRADING_LAB_REPORT_PRINT_THRESHOLD_BYTES
    || requiresConfirmation
      !== (uncompressedBytes > TRADING_LAB_REPORT_PRINT_THRESHOLD_BYTES)
    || estimatedPageCount !== expectedPageCount
  ) {
    throw new TradingLabApiContractError(label)
  }
  return {
    uncompressedBytes,
    estimatedPageCount,
    thresholdBytes,
    requiresConfirmation,
  }
}

function parseReportPrintConfirmationResponse(
  value: unknown,
  expectedReportId: string,
): TradingLabReportPrintConfirmationResponse {
  const label = 'Invalid Trading Lab print-confirmation response'
  const record = exactRecord(
    value,
    ['reportId', 'token', 'expiresAt'],
    label,
  )
  const reportId = responseUuid(record.reportId, label)
  if (reportId !== expectedReportId) {
    throw new TradingLabApiContractError(label)
  }
  return {
    reportId,
    token: responseNonBlankString(record.token, MAX_TOKEN_LENGTH, label),
    expiresAt: responseInstant(record.expiresAt, label),
  }
}

function parseScenarioCreateInput(
  value: TradingLabScenarioCreateInput,
): TradingLabScenarioCreateInput {
  const label = 'Invalid Trading Lab scenario create input'
  const hasExpectedVersion = isPlainRecord(value)
    && Object.prototype.hasOwnProperty.call(value, 'expectedVersion')
  const record = exactRecord(
    value,
    [
      'name',
      'description',
      'negativeMode',
      'seed',
      'modelVersion',
      'scenario',
      'configSnapshot',
      'configSnapshotHash',
      ...(hasExpectedVersion
        ? ['expectedVersion']
        : []),
    ],
    label,
  )
  const expectedVersion = Object.prototype.hasOwnProperty.call(
    record,
    'expectedVersion',
  )
    ? nullable(
        record.expectedVersion,
        (item) => responseNonNegativeInteger(item, label),
        label,
      )
    : undefined
  return {
    name: responseNonBlankString(record.name, 200, label),
    description: nullable(
      record.description,
      (item) => responseString(item, 10_000, label),
      label,
    ),
    negativeMode: responseBoolean(record.negativeMode, label),
    seed: responseNonBlankString(record.seed, 256, label),
    modelVersion: responseNonBlankString(record.modelVersion, 80, label),
    scenario: responseJsonObject(record.scenario, label),
    configSnapshot: responseJsonObject(record.configSnapshot, label),
    configSnapshotHash: responseHash(record.configSnapshotHash, label),
    ...(expectedVersion === undefined ? {} : { expectedVersion }),
  }
}

function parseRunCreateInput(
  value: TradingLabRunCreateInput,
): TradingLabRunCreateInput {
  const label = 'Invalid Trading Lab run create input'
  const record = exactRecord(
    value,
    ['scenarioVersion', 'configSnapshotHash', 'localCalculation'],
    label,
  )
  return {
    scenarioVersion: responseNonNegativeInteger(record.scenarioVersion, label),
    configSnapshotHash: responseHash(record.configSnapshotHash, label),
    localCalculation: responseJsonObject(record.localCalculation, label),
  }
}

function exactRecord(
  value: unknown,
  keys: readonly string[],
  label: string,
): Record<string, unknown> {
  if (!isPlainRecord(value)) {
    throw new TradingLabApiContractError(label)
  }
  const actual = Object.keys(value)
  if (
    actual.length !== keys.length
    || actual.some((key) => !keys.includes(key))
  ) {
    throw new TradingLabApiContractError(label)
  }
  return value
}

function responseJsonObject(
  value: unknown,
  label: string,
): TradingLabJsonObject {
  if (!isPlainRecord(value)) {
    throw new TradingLabApiContractError(label)
  }
  assertBoundedJson(value, label)
  return value as TradingLabJsonObject
}

function assertBoundedJson(value: unknown, label: string) {
  let nodes = 0
  const visit = (item: unknown, depth: number): void => {
    nodes += 1
    if (nodes > MAX_JSON_NODES || depth > MAX_JSON_DEPTH) {
      throw new TradingLabApiContractError(label)
    }
    if (
      item === null
      || typeof item === 'boolean'
      || (typeof item === 'number' && Number.isFinite(item))
    ) {
      return
    }
    if (typeof item === 'string') {
      if (item.length > MAX_JSON_STRING_LENGTH) {
        throw new TradingLabApiContractError(label)
      }
      return
    }
    if (Array.isArray(item)) {
      if (item.length > MAX_JSON_COLLECTION_SIZE) {
        throw new TradingLabApiContractError(label)
      }
      for (const child of item) visit(child, depth + 1)
      return
    }
    if (isPlainRecord(item)) {
      const entries = Object.entries(item)
      if (entries.length > MAX_JSON_COLLECTION_SIZE) {
        throw new TradingLabApiContractError(label)
      }
      for (const [key, child] of entries) {
        if (key.length > MAX_JSON_STRING_LENGTH) {
          throw new TradingLabApiContractError(label)
        }
        visit(child, depth + 1)
      }
      return
    }
    throw new TradingLabApiContractError(label)
  }
  visit(value, 0)
}

function requireCanonicalUuid(value: string, field: string) {
  if (typeof value !== 'string' || !UUID_PATTERN.test(value)) {
    throw new TradingLabApiContractError(
      `${field} must be a canonical lowercase UUID`,
    )
  }
  return value
}

function requireAdminToken(value: string) {
  if (
    typeof value !== 'string'
    || value.trim().length === 0
    || value.length > MAX_TOKEN_LENGTH
  ) {
    throw new TradingLabApiContractError('Trading Lab admin token is required')
  }
  return value
}

function responseUuid(value: unknown, label: string) {
  if (typeof value !== 'string' || !UUID_PATTERN.test(value)) {
    throw new TradingLabApiContractError(label)
  }
  return value
}

function responseHash(value: unknown, label: string) {
  if (typeof value !== 'string' || !SHA256_PATTERN.test(value)) {
    throw new TradingLabApiContractError(label)
  }
  return value
}

function responseInstant(value: unknown, label: string) {
  if (
    typeof value !== 'string'
    || !INSTANT_PATTERN.test(value)
    || !validUtcCalendarInstant(value)
  ) {
    throw new TradingLabApiContractError(label)
  }
  return value
}

function validUtcCalendarInstant(value: string) {
  const match = value.match(
    /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d{1,9})?Z$/,
  )
  if (match === null) return false
  const year = Number(match[1])
  const month = Number(match[2])
  const day = Number(match[3])
  const hour = Number(match[4])
  const minute = Number(match[5])
  const second = Number(match[6])
  const leapYear = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0)
  const daysInMonth = [
    31,
    leapYear ? 29 : 28,
    31,
    30,
    31,
    30,
    31,
    31,
    30,
    31,
    30,
    31,
  ]
  return (
    month >= 1
    && month <= 12
    && day >= 1
    && day <= (daysInMonth[month - 1] ?? 0)
    && hour >= 0
    && hour <= 23
    && minute >= 0
    && minute <= 59
    && second >= 0
    && second <= 59
    && Number.isFinite(Date.parse(value))
  )
}

function responseNonNegativeInteger(value: unknown, label: string) {
  if (
    typeof value !== 'number'
    || !Number.isSafeInteger(value)
    || value < 0
  ) {
    throw new TradingLabApiContractError(label)
  }
  return value
}

function responsePositiveNumber(value: unknown, label: string) {
  if (typeof value !== 'number' || !Number.isFinite(value) || value <= 0) {
    throw new TradingLabApiContractError(label)
  }
  return value
}

function responseBoolean(value: unknown, label: string) {
  if (typeof value !== 'boolean') {
    throw new TradingLabApiContractError(label)
  }
  return value
}

function responseString(value: unknown, maxLength: number, label: string) {
  if (typeof value !== 'string' || value.length > maxLength) {
    throw new TradingLabApiContractError(label)
  }
  return value
}

function responseNonBlankString(
  value: unknown,
  maxLength: number,
  label: string,
) {
  const result = responseString(value, maxLength, label)
  if (result.trim().length === 0) {
    throw new TradingLabApiContractError(label)
  }
  return result
}

function responseOneOf<const Values extends readonly string[]>(
  value: unknown,
  values: Values,
  label: string,
): Values[number] {
  if (!isOneOf(value, values)) {
    throw new TradingLabApiContractError(label)
  }
  return value
}

function nullable<T>(
  value: unknown,
  parse: (item: unknown) => T,
  label: string,
): T | null {
  if (value === null) return null
  try {
    return parse(value)
  } catch {
    throw new TradingLabApiContractError(label)
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
