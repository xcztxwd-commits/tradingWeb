import {
  canonicalJson,
  hashScenarioConfig,
  normalizeScenario,
} from '../model/normalization.ts'
import type {
  TimelineTrigger,
  TradingLabScenario,
} from '../model/types.ts'
import { validateScenario } from '../model/validation.ts'

const PRODUCT_TYPES = new Set(['CRYPTO_SPOT', 'LINEAR_PERP'])
const ACTION_TYPES = new Set([
  'PLACE_ORDER',
  'CANCEL_ORDER',
  'CANCEL_ALL',
  'SET_POSITION_MODE',
  'SET_MARGIN_MODE',
  'SET_LEVERAGE',
  'ADD_MARGIN',
  'REMOVE_MARGIN',
  'APPLY_FUNDING',
  'PLACE_OCO',
])
const TRIGGER_TYPES = new Set([
  'VIRTUAL_TIME',
  'PRICE',
  'AFTER_ACTION',
  'GROUP',
])
const STRUCTURAL_ISSUE_CODES = new Set([
  'SCENARIO_INVALID',
  'NEGATIVE_MODE_INVALID',
  'MODEL_VERSION_MISMATCH',
  'EXECUTION_POLICY_REQUIRED',
  'EXECUTION_POLICY_FIELDS_INVALID',
  'EXECUTION_POLICY_MATCHING_MODE_INVALID',
  'MARKET_PATH_REQUIRED',
  'MARKET_PATH_STRUCTURE_INVALID',
  'MARKET_PATH_REALISTIC_INVALID',
  'INSTRUMENTS_REQUIRED',
  'INSTRUMENT_INVALID',
  'PRODUCT_TYPE_INVALID',
  'DEFAULT_POSITION_MODE_INVALID',
  'DEFAULT_MARGIN_MODE_INVALID',
  'SCENARIO_SYMBOL_INVALID',
  'TRIGGER_INVALID',
  'TRIGGER_TYPE_INVALID',
  'TRIGGER_GROUP_INVALID',
  'ACTION_PARAMETERS_INVALID',
  'ATTACHED_PROTECTIONS_INVALID',
  'ATTACHED_PROTECTION_INVALID',
  'ACTION_PARAMETER_UNKNOWN',
  'ACTION_BOOLEAN_PARAMETER_INVALID',
  'ACTION_INVALID',
  'ACTION_TYPE_UNSUPPORTED',
  'EXPECTED_ERROR_INVALID',
])
const CONFIG_STRING_FIELDS = [
  'modelVersion',
  'symbolConfigVersion',
  'codeVersion',
] as const
const EXECUTION_POLICY_DECIMAL_FIELDS = [
  'makerFeeRate',
  'takerFeeRate',
  'liquidationFeeRate',
  'slippageRate',
] as const
const EXECUTION_POLICY_NULLABLE_DECIMAL_FIELDS = [
  'maxFillQuantityPerTick',
] as const
const INSTRUMENT_STRING_FIELDS = [
  'symbol',
  'baseAsset',
  'quoteAsset',
  'tickSize',
  'stepSize',
  'minQty',
  'maxQty',
  'initialMarginRate',
  'maintenanceMarginRate',
  'liquidationFeeRate',
  'fixedFundingRate',
  'markPriceSource',
  'contractSize',
  'marginAsset',
  'settlementAsset',
  'riskTier',
] as const
const INSTRUMENT_NULLABLE_STRING_FIELDS = [
  'minNotional',
  'maxNotional',
] as const
const INSTRUMENT_INTEGER_FIELDS = [
  'pricePrecision',
  'quantityPrecision',
  'fixedFundingIntervalMinutes',
  'maxLeverage',
  'defaultLeverage',
] as const
const PATH_SEGMENT_INTEGER_FIELDS = [
  'durationSeconds',
  'offsetRangeSteps',
  'volatilitySteps',
  'maxStepPerSecond',
] as const
const EXECUTION_POLICY_FIELDS = new Set([
  'matchingMode',
  ...EXECUTION_POLICY_DECIMAL_FIELDS,
  ...EXECUTION_POLICY_NULLABLE_DECIMAL_FIELDS,
])
const INSTRUMENT_FIELDS = new Set([
  'productType',
  ...INSTRUMENT_STRING_FIELDS,
  ...INSTRUMENT_NULLABLE_STRING_FIELDS,
  ...INSTRUMENT_INTEGER_FIELDS,
])
const CONFIG_SNAPSHOT_FIELDS = new Set([
  ...CONFIG_STRING_FIELDS,
  'executionPolicy',
  'instruments',
])
const SCALAR_PATH_FIELDS = new Set(['start', 'segments'])
const PATH_SEGMENT_FIELDS = new Set([
  'target',
  ...PATH_SEGMENT_INTEGER_FIELDS,
])
const MARKET_PATH_FIELDS = new Set([
  'virtualStart',
  'realistic',
  'instruments',
])
const DEFAULT_FIELDS = new Set([
  'positionMode',
  'marginMode',
  'leverage',
])
const SYMBOL_FIELDS = new Set(['symbol', 'productType'])
const ACTION_FIELDS = new Set([
  'id',
  'sequence',
  'type',
  'symbol',
  'productType',
  'trigger',
  'parameters',
  'overrides',
  'expectedError',
])
const EXPECTED_ERROR_FIELDS = new Set(['status', 'code'])
const SCENARIO_FIELDS = new Set([
  'id',
  'name',
  'description',
  'negativeMode',
  'seed',
  'modelVersion',
  'configSnapshot',
  'configSnapshotHash',
  'executionPolicy',
  'marketPath',
  'initialBalances',
  'defaults',
  'symbols',
  'timeline',
])
const SCENARIO_INPUT_FIELDS = new Set([
  ...SCENARIO_FIELDS,
  'schemaVersion',
  'updatedAt',
  'uiState',
])
const CANONICAL_TRANSIENT_KEYS = new Set([
  'transient',
  'uistate',
  'validationerrors',
  'selected',
  'expanded',
  'dragging',
])

function failure(message: string, cause?: unknown): Error {
  return new Error(
    `Trading Lab 场景文档无效：${message}`,
    cause === undefined ? undefined : { cause },
  )
}

function clonePlainJsonOwnData(
  value: unknown,
  depth = 0,
  ancestors = new WeakSet<object>(),
  allowTransientSubtree = false,
): unknown {
  if (depth > 64) {
    throw failure('输入嵌套层级超过限制')
  }
  if (
    value === null
    || typeof value === 'boolean'
    || typeof value === 'number'
    || typeof value === 'string'
  ) {
    return value
  }
  if (typeof value !== 'object') {
    throw failure('输入只能包含 JSON 值')
  }
  if (ancestors.has(value)) {
    throw failure('输入不能包含循环引用')
  }

  ancestors.add(value)
  try {
    const ownKeys = Reflect.ownKeys(value)
    if (ownKeys.some((key) => typeof key === 'symbol')) {
      throw failure('输入不能包含 Symbol 字段')
    }

    if (Array.isArray(value)) {
      if (Object.getPrototypeOf(value) !== Array.prototype) {
        throw failure('输入数组必须使用普通 JSON 数组')
      }
      const lengthDescriptor = Object.getOwnPropertyDescriptor(value, 'length')
      if (
        lengthDescriptor === undefined
        || !('value' in lengthDescriptor)
        || typeof lengthDescriptor.value !== 'number'
      ) {
        throw failure('输入数组长度无效')
      }
      const elementKeys = (ownKeys as string[])
        .filter((key) => key !== 'length')
      if (elementKeys.length !== lengthDescriptor.value) {
        throw failure('输入数组不能包含空缺或额外字段')
      }

      const elements = elementKeys.map((key) => {
        if (!/^(?:0|[1-9]\d*)$/.test(key)) {
          throw failure('输入数组只能包含连续索引')
        }
        const index = Number(key)
        if (!Number.isSafeInteger(index) || index >= lengthDescriptor.value) {
          throw failure('输入数组索引无效')
        }
        const descriptor = Object.getOwnPropertyDescriptor(value, key)
        if (
          descriptor === undefined
          || !descriptor.enumerable
          || !('value' in descriptor)
        ) {
          throw failure('输入数组不能包含隐藏字段或访问器')
        }
        return { index, value: descriptor.value }
      }).sort((left, right) => left.index - right.index)

      return elements.map((element, index) => {
        if (element.index !== index) {
          throw failure('输入数组不能包含空缺位置')
        }
        return clonePlainJsonOwnData(
          element.value,
          depth + 1,
          ancestors,
          allowTransientSubtree,
        )
      })
    }

    const prototype = Object.getPrototypeOf(value)
    if (prototype !== Object.prototype && prototype !== null) {
      throw failure('输入对象必须是普通 JSON 对象')
    }

    const result = Object.create(null) as Record<string, unknown>
    for (const key of ownKeys as string[]) {
      const canonicalKey = key.toLowerCase().replace(/[^a-z0-9]/g, '')
      if (
        !allowTransientSubtree
        && depth > 0
        && CANONICAL_TRANSIENT_KEYS.has(canonicalKey)
      ) {
        throw failure(`嵌套字段 ${key} 不能作为临时状态被静默剥离`)
      }
      const descriptor = Object.getOwnPropertyDescriptor(value, key)
      if (
        descriptor === undefined
        || !descriptor.enumerable
        || !('value' in descriptor)
      ) {
        throw failure('输入对象不能包含隐藏字段或访问器')
      }
      result[key] = clonePlainJsonOwnData(
        descriptor.value,
        depth + 1,
        ancestors,
        allowTransientSubtree || (depth === 0 && key === 'uiState'),
      )
    }
    return result
  } finally {
    ancestors.delete(value)
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function requireRecord(
  value: unknown,
  path: string,
): asserts value is Record<string, unknown> {
  if (!isRecord(value)) {
    throw failure(`${path} 必须是对象`)
  }
}

function requireOnlyFields(
  value: Record<string, unknown>,
  allowed: ReadonlySet<string>,
  path: string,
): void {
  const unknown = Object.keys(value).find((key) => !allowed.has(key))
  if (unknown !== undefined) {
    throw failure(`${path}.${unknown} 是未知字段`)
  }
}

function requireString(value: unknown, path: string): asserts value is string {
  if (typeof value !== 'string') {
    throw failure(`${path} 必须是字符串`)
  }
}

function requireNullableString(
  value: unknown,
  path: string,
): asserts value is string | null {
  if (value !== null) {
    requireString(value, path)
  }
}

function requireBoolean(value: unknown, path: string): asserts value is boolean {
  if (typeof value !== 'boolean') {
    throw failure(`${path} 必须是布尔值`)
  }
}

function requireKnown(
  value: unknown,
  allowed: ReadonlySet<string>,
  path: string,
): asserts value is string {
  requireString(value, path)
  if (!allowed.has(value)) {
    throw failure(`${path} 包含未知判别值`)
  }
}

function requireSafeInteger(value: unknown, path: string): void {
  if (!Number.isSafeInteger(value)) {
    throw failure(`${path} 必须是安全整数`)
  }
}

function assertExecutionPolicyShape(
  value: unknown,
  path: string,
): void {
  requireRecord(value, path)
  requireOnlyFields(value, EXECUTION_POLICY_FIELDS, path)
  requireKnown(
    value.matchingMode,
    new Set(['SIMPLE', 'DEPTH']),
    `${path}.matchingMode`,
  )
  for (const field of EXECUTION_POLICY_DECIMAL_FIELDS) {
    requireString(value[field], `${path}.${field}`)
  }
  for (const field of EXECUTION_POLICY_NULLABLE_DECIMAL_FIELDS) {
    requireNullableString(value[field], `${path}.${field}`)
  }
}

function assertInstrumentShape(
  value: unknown,
  path: string,
): void {
  requireRecord(value, path)
  requireOnlyFields(value, INSTRUMENT_FIELDS, path)
  requireKnown(value.productType, PRODUCT_TYPES, `${path}.productType`)
  for (const field of INSTRUMENT_STRING_FIELDS) {
    requireString(value[field], `${path}.${field}`)
  }
  for (const field of INSTRUMENT_NULLABLE_STRING_FIELDS) {
    requireNullableString(value[field], `${path}.${field}`)
  }
  for (const field of INSTRUMENT_INTEGER_FIELDS) {
    requireSafeInteger(value[field], `${path}.${field}`)
  }
}

function assertScalarPathShape(
  value: unknown,
  path: string,
): void {
  requireRecord(value, path)
  requireOnlyFields(value, SCALAR_PATH_FIELDS, path)
  requireString(value.start, `${path}.start`)
  if (!Array.isArray(value.segments)) {
    throw failure(`${path}.segments 必须是数组`)
  }
  value.segments.forEach((segment, index) => {
    const segmentPath = `${path}.segments[${index}]`
    requireRecord(segment, segmentPath)
    requireOnlyFields(segment, PATH_SEGMENT_FIELDS, segmentPath)
    requireString(segment.target, `${segmentPath}.target`)
    for (const field of PATH_SEGMENT_INTEGER_FIELDS) {
      requireSafeInteger(segment[field], `${segmentPath}.${field}`)
    }
  })
}

function assertMarketPathShape(value: unknown): void {
  const path = 'marketPath'
  requireRecord(value, path)
  requireOnlyFields(value, MARKET_PATH_FIELDS, path)
  requireString(value.virtualStart, `${path}.virtualStart`)
  requireBoolean(value.realistic, `${path}.realistic`)
  if (!Array.isArray(value.instruments)) {
    throw failure(`${path}.instruments 必须是数组`)
  }

  value.instruments.forEach((instrument, index) => {
    const instrumentPath = `${path}.instruments[${index}]`
    requireRecord(instrument, instrumentPath)
    requireKnown(
      instrument.mode,
      new Set(['SIMPLE', 'ADVANCED']),
      `${instrumentPath}.mode`,
    )
    requireKnown(
      instrument.productType,
      PRODUCT_TYPES,
      `${instrumentPath}.productType`,
    )
    requireString(instrument.symbol, `${instrumentPath}.symbol`)
    requireString(instrument.seed, `${instrumentPath}.seed`)

    if (instrument.mode === 'SIMPLE') {
      requireOnlyFields(
        instrument,
        new Set([
          'mode',
          'productType',
          'symbol',
          'seed',
          'last',
          'spreadSteps',
          'indexOffsetSteps',
          'basisSteps',
          ...(instrument.productType === 'LINEAR_PERP'
            ? ['fundingRate']
            : []),
        ]),
        instrumentPath,
      )
      assertScalarPathShape(instrument.last, `${instrumentPath}.last`)
      for (const field of [
        'spreadSteps',
        'indexOffsetSteps',
        'basisSteps',
      ]) {
        requireSafeInteger(instrument[field], `${instrumentPath}.${field}`)
      }
      if (instrument.fundingRate !== undefined) {
        requireString(
          instrument.fundingRate,
          `${instrumentPath}.fundingRate`,
        )
      }
      return
    }

    requireOnlyFields(
      instrument,
      new Set([
        'mode',
        'productType',
        'symbol',
        'seed',
        'prices',
        ...(instrument.productType === 'LINEAR_PERP'
          ? ['fundingRate']
          : []),
      ]),
      instrumentPath,
    )
    requireRecord(instrument.prices, `${instrumentPath}.prices`)
    const lanes = instrument.productType === 'LINEAR_PERP'
      ? ['bid', 'ask', 'last', 'mark', 'index']
      : ['bid', 'ask', 'last']
    requireOnlyFields(
      instrument.prices,
      new Set(lanes),
      `${instrumentPath}.prices`,
    )
    for (const lane of lanes) {
      assertScalarPathShape(
        instrument.prices[lane],
        `${instrumentPath}.prices.${lane}`,
      )
    }
    if (instrument.productType === 'LINEAR_PERP') {
      requireString(
        instrument.fundingRate,
        `${instrumentPath}.fundingRate`,
      )
    }
  })
}

function assertTriggerShape(
  value: unknown,
  path: string,
  depth = 0,
): asserts value is TimelineTrigger {
  if (depth > 16) {
    throw failure(`${path} 嵌套层级超过限制`)
  }
  requireRecord(value, path)
  requireKnown(value.type, TRIGGER_TYPES, `${path}.type`)
  switch (value.type) {
    case 'VIRTUAL_TIME':
      requireOnlyFields(value, new Set(['type', 'atSecond']), path)
      requireSafeInteger(value.atSecond, `${path}.atSecond`)
      return
    case 'PRICE':
      requireOnlyFields(
        value,
        new Set(['type', 'priceType', 'operator', 'value']),
        path,
      )
      requireKnown(
        value.priceType,
        new Set(['BID', 'ASK', 'LAST', 'MARK', 'INDEX']),
        `${path}.priceType`,
      )
      requireKnown(
        value.operator,
        new Set(['GTE', 'LTE']),
        `${path}.operator`,
      )
      requireString(value.value, `${path}.value`)
      return
    case 'AFTER_ACTION':
      requireOnlyFields(
        value,
        new Set(['type', 'actionId', 'delaySeconds']),
        path,
      )
      requireString(value.actionId, `${path}.actionId`)
      requireSafeInteger(value.delaySeconds, `${path}.delaySeconds`)
      return
    case 'GROUP':
      requireOnlyFields(
        value,
        new Set(['type', 'operator', 'items']),
        path,
      )
      requireKnown(
        value.operator,
        new Set(['ALL', 'ANY']),
        `${path}.operator`,
      )
      if (!Array.isArray(value.items)) {
        throw failure(`${path}.items 必须是数组`)
      }
      value.items.forEach((item, index) => {
        assertTriggerShape(item, `${path}.items[${index}]`, depth + 1)
      })
  }
}

function assertScenarioContainers(
  scenario: TradingLabScenario,
  allowedScenarioFields: ReadonlySet<string> = SCENARIO_FIELDS,
): void {
  requireOnlyFields(
    scenario as unknown as Record<string, unknown>,
    allowedScenarioFields,
    'scenario',
  )
  if (
    typeof scenario.id !== 'string'
    || scenario.id.trim().length === 0
    || /[\u0000-\u001f\u007f]/.test(scenario.id)
  ) {
    throw failure('id 必须是非空且不含控制字符的字符串')
  }
  requireString(scenario.name, 'name')
  requireString(scenario.description, 'description')
  if (typeof scenario.negativeMode !== 'boolean') {
    throw failure('negativeMode 必须是布尔值')
  }
  requireString(scenario.seed, 'seed')
  requireString(scenario.modelVersion, 'modelVersion')
  requireString(scenario.configSnapshotHash, 'configSnapshotHash')

  requireRecord(scenario.configSnapshot, 'configSnapshot')
  requireOnlyFields(
    scenario.configSnapshot,
    CONFIG_SNAPSHOT_FIELDS,
    'configSnapshot',
  )
  for (const field of CONFIG_STRING_FIELDS) {
    requireString(
      scenario.configSnapshot[field],
      `configSnapshot.${field}`,
    )
  }
  assertExecutionPolicyShape(
    scenario.configSnapshot.executionPolicy,
    'configSnapshot.executionPolicy',
  )
  if (
    !Array.isArray(scenario.configSnapshot.instruments)
    || scenario.configSnapshot.instruments.length === 0
  ) {
    throw failure('configSnapshot.instruments 必须是非空数组')
  }
  scenario.configSnapshot.instruments.forEach((instrument, index) => {
    assertInstrumentShape(
      instrument,
      `configSnapshot.instruments[${index}]`,
    )
  })

  assertExecutionPolicyShape(scenario.executionPolicy, 'executionPolicy')
  assertMarketPathShape(scenario.marketPath)

  requireRecord(scenario.initialBalances, 'initialBalances')
  for (const [asset, balance] of Object.entries(scenario.initialBalances)) {
    requireString(balance, `initialBalances.${asset}`)
  }
  requireRecord(scenario.defaults, 'defaults')
  requireOnlyFields(scenario.defaults, DEFAULT_FIELDS, 'defaults')
  requireKnown(
    scenario.defaults.positionMode,
    new Set(['ONE_WAY', 'HEDGE']),
    'defaults.positionMode',
  )
  requireKnown(
    scenario.defaults.marginMode,
    new Set(['CROSS', 'ISOLATED']),
    'defaults.marginMode',
  )
  requireSafeInteger(scenario.defaults.leverage, 'defaults.leverage')

  if (!Array.isArray(scenario.symbols)) {
    throw failure('symbols 必须是数组')
  }
  scenario.symbols.forEach((symbol, index) => {
    const path = `symbols[${index}]`
    requireRecord(symbol, path)
    requireOnlyFields(symbol, SYMBOL_FIELDS, path)
    requireString(symbol.symbol, `${path}.symbol`)
    requireKnown(
      symbol.productType,
      PRODUCT_TYPES,
      `${path}.productType`,
    )
  })

  if (!Array.isArray(scenario.timeline)) {
    throw failure('timeline 必须是数组')
  }
  scenario.timeline.forEach((action, index) => {
    const path = `timeline[${index}]`
    requireRecord(action, path)
    requireOnlyFields(action, ACTION_FIELDS, path)
    requireString(action.id, `${path}.id`)
    requireSafeInteger(action.sequence, `${path}.sequence`)
    requireKnown(action.type, ACTION_TYPES, `${path}.type`)
    requireString(action.symbol, `${path}.symbol`)
    requireKnown(action.productType, PRODUCT_TYPES, `${path}.productType`)
    assertTriggerShape(action.trigger, `${path}.trigger`)
    requireRecord(action.parameters, `${path}.parameters`)
    if (action.overrides !== undefined) {
      requireRecord(action.overrides, `${path}.overrides`)
    }
    if (action.expectedError !== undefined) {
      requireRecord(action.expectedError, `${path}.expectedError`)
      requireOnlyFields(
        action.expectedError,
        EXPECTED_ERROR_FIELDS,
        `${path}.expectedError`,
      )
    }
  })
}

export async function normalizeTradingLabScenarioDocument(
  value: unknown,
): Promise<TradingLabScenario> {
  if (!isRecord(value)) {
    throw failure('顶层值必须是对象')
  }

  let normalized: TradingLabScenario
  try {
    const ownDataInput = clonePlainJsonOwnData(value) as TradingLabScenario
    assertScenarioContainers(ownDataInput, SCENARIO_INPUT_FIELDS)
    const canonicalInput = JSON.parse(
      canonicalJson(ownDataInput),
    ) as TradingLabScenario
    assertScenarioContainers(canonicalInput, SCENARIO_INPUT_FIELDS)
    normalized = normalizeScenario(canonicalInput)
  } catch (error) {
    throw failure('不能规范化为当前场景结构', error)
  }

  assertScenarioContainers(normalized)

  let issues
  try {
    issues = validateScenario(normalized)
  } catch (error) {
    throw failure('结构校验失败', error)
  }
  const structuralIssue = issues.find((issue) =>
    STRUCTURAL_ISSUE_CODES.has(issue.code),
  )
  if (structuralIssue !== undefined) {
    throw failure(
      `${structuralIssue.path || 'scenario'}：${structuralIssue.code}`,
    )
  }

  let expectedHash: string
  try {
    expectedHash = await hashScenarioConfig(normalized.configSnapshot)
  } catch (error) {
    throw failure('配置快照不能计算规范哈希', error)
  }
  if (normalized.configSnapshotHash !== expectedHash) {
    throw failure('配置快照哈希与规范配置不一致')
  }

  return normalized
}
