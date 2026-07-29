import {
  compare,
  decimal,
  floorToStep,
  multiply,
  type Decimal,
} from '../oracle/decimal.ts'
import type {
  ScenarioValidationIssue,
  TimelineAction,
  TimelineTrigger,
  TradingLabInstrumentConfig,
  TradingLabScenario,
} from './types.ts'

type InstrumentValidation = {
  config: TradingLabInstrumentConfig
  tickSize: Decimal | null
  stepSize: Decimal | null
  minQty: Decimal | null
  maxQty: Decimal | null
  minNotional: Decimal | null
  maxNotional: Decimal | null
}

function instrumentIdentity(productType: unknown, symbol: unknown): string {
  return `${String(productType)}\u0000${String(symbol)}`
}

const ZERO = decimal('0')
const ONE = decimal('1')
const EXPECTED_ERROR_CODE = /^[A-Z][A-Z0-9_]{0,79}$/
const HASH = /^[0-9a-f]{64}$/
const SUPPORTED_ACTIONS = new Set([
  'PLACE_ORDER',
  'CANCEL_ORDER',
  'CANCEL_ALL',
  'SET_POSITION_MODE',
  'SET_MARGIN_MODE',
  'SET_LEVERAGE',
])
const LOCAL_ORACLE_ACTIONS = new Set([
  'ADD_MARGIN',
  'REMOVE_MARGIN',
  'APPLY_FUNDING',
  'PLACE_OCO',
])
const ORDER_TYPES = new Set([
  'MARKET',
  'LIMIT',
  'STOP_MARKET',
  'STOP_LIMIT',
  'TRAILING_STOP_MARKET',
])
const POSITION_SIDES = new Set(['BOTH', 'LONG', 'SHORT'])
const QUANTITY_UNITS = new Set(['BASE', 'QUOTE', 'CONTRACTS'])
const TIME_IN_FORCE_VALUES = new Set(['GTC', 'IOC', 'FOK'])
const TRIGGER_PRICE_TYPES = new Set(['LAST_PRICE', 'MARK_PRICE'])
const PROTECTION_TYPES = new Set(['TAKE_PROFIT', 'STOP_LOSS'])
const TRIGGER_EXECUTION_TYPES = new Set(['MARKET', 'LIMIT'])
const PLACE_ORDER_PARAMETERS = new Set([
  'side',
  'orderType',
  'lots',
  'requestedPrice',
  'stopLoss',
  'takeProfit',
  'quantity',
  'price',
  'leverage',
  'positionSide',
  'quantityUnit',
  'marginMode',
  'triggerPrice',
  'triggerPriceType',
  'reduceOnly',
  'attachedProtections',
  'timeInForce',
  'postOnly',
  'activationPrice',
  'trailingDelta',
  'trailingRate',
])
const PLACE_ORDER_DECIMAL_PARAMETERS = new Set([
  'lots',
  'requestedPrice',
  'stopLoss',
  'takeProfit',
  'quantity',
  'price',
  'triggerPrice',
  'activationPrice',
  'trailingDelta',
  'trailingRate',
])
const PLACE_ORDER_TEXT_PARAMETERS = new Set([
  'side',
  'orderType',
  'positionSide',
  'quantityUnit',
  'marginMode',
  'triggerPriceType',
  'timeInForce',
])
const PLACE_ORDER_BOOLEAN_PARAMETERS = new Set(['reduceOnly', 'postOnly'])
const EXECUTION_POLICY_FIELDS = new Set([
  'matchingMode',
  'makerFeeRate',
  'takerFeeRate',
  'liquidationFeeRate',
  'slippageRate',
  'maxFillQuantityPerTick',
])
const MARKET_PATH_FIELDS = new Set([
  'virtualStart',
  'realistic',
  'instruments',
])
const SCALAR_PATH_FIELDS = new Set(['start', 'segments'])
const PATH_SEGMENT_FIELDS = new Set([
  'target',
  'durationSeconds',
  'offsetRangeSteps',
  'volatilitySteps',
  'maxStepPerSecond',
])

function addIssue(
  issues: ScenarioValidationIssue[],
  path: string,
  code: string,
  message: string,
  severity: 'ERROR' | 'WARNING' = 'ERROR',
): void {
  issues.push({ path, code, message, severity })
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function hasExactFields(
  value: Record<string, unknown>,
  fields: ReadonlySet<string>,
): boolean {
  const keys = Object.keys(value)
  return keys.length === fields.size && keys.every((key) => fields.has(key))
}

function isNonNegativeSafeInteger(value: unknown): value is number {
  return typeof value === 'number'
    && Number.isSafeInteger(value)
    && value >= 0
}

function isPositiveSafeInteger(value: unknown): value is number {
  return isNonNegativeSafeInteger(value) && value > 0
}

function parseDecimalField(
  value: unknown,
  issues: ScenarioValidationIssue[],
  path: string,
  code: string,
  message: string,
): Decimal | null {
  if (typeof value !== 'string') {
    addIssue(issues, path, code, message)
    return null
  }
  try {
    return decimal(value)
  } catch {
    addIssue(issues, path, code, message)
    return null
  }
}

function parsePositiveDecimal(
  value: unknown,
  issues: ScenarioValidationIssue[],
  path: string,
  code: string,
  message: string,
): Decimal | null {
  const parsed = parseDecimalField(value, issues, path, code, message)
  if (parsed !== null && compare(parsed, ZERO) <= 0) {
    addIssue(issues, path, code, message)
    return null
  }
  return parsed
}

function parseOptionalPositiveDecimal(
  value: unknown,
  issues: ScenarioValidationIssue[],
  path: string,
  code: string,
  message: string,
): Decimal | null {
  if (value === null) {
    return null
  }
  return parsePositiveDecimal(value, issues, path, code, message)
}

function parseNonNegativeDecimal(
  value: unknown,
  issues: ScenarioValidationIssue[],
  path: string,
  code: string,
  message: string,
): Decimal | null {
  const parsed = parseDecimalField(value, issues, path, code, message)
  if (parsed !== null && compare(parsed, ZERO) < 0) {
    addIssue(issues, path, code, message)
    return null
  }
  return parsed
}

function validateRateBelowOne(
  value: unknown,
  issues: ScenarioValidationIssue[],
  path: string,
  code: string,
  message: string,
): void {
  const parsed = parseNonNegativeDecimal(value, issues, path, code, message)
  if (parsed !== null && compare(parsed, ONE) >= 0) {
    addIssue(issues, path, code, message)
  }
}

function validateExecutionPolicy(
  scenario: TradingLabScenario,
  issues: ScenarioValidationIssue[],
): void {
  const policy = scenario.executionPolicy
  if (!isRecord(policy)) {
    addIssue(
      issues,
      'executionPolicy',
      'EXECUTION_POLICY_REQUIRED',
      '场景必须持久化独立的执行策略',
    )
    return
  }
  if (!hasExactFields(policy, EXECUTION_POLICY_FIELDS)) {
    addIssue(
      issues,
      'executionPolicy',
      'EXECUTION_POLICY_FIELDS_INVALID',
      '场景执行策略必须且只能包含已声明字段',
    )
  }
  if (policy.matchingMode !== 'SIMPLE' && policy.matchingMode !== 'DEPTH') {
    addIssue(
      issues,
      'executionPolicy.matchingMode',
      'EXECUTION_POLICY_MATCHING_MODE_INVALID',
      '场景撮合模式只支持简单模式或深度模式',
    )
  }
  validateRateBelowOne(
    policy.makerFeeRate,
    issues,
    'executionPolicy.makerFeeRate',
    'EXECUTION_POLICY_MAKER_FEE_RATE_INVALID',
    '场景 Maker 手续费率必须是大于等于零且小于一的十进制字符串',
  )
  validateRateBelowOne(
    policy.takerFeeRate,
    issues,
    'executionPolicy.takerFeeRate',
    'EXECUTION_POLICY_TAKER_FEE_RATE_INVALID',
    '场景 Taker 手续费率必须是大于等于零且小于一的十进制字符串',
  )
  validateRateBelowOne(
    policy.liquidationFeeRate,
    issues,
    'executionPolicy.liquidationFeeRate',
    'EXECUTION_POLICY_LIQUIDATION_FEE_RATE_INVALID',
    '场景强平手续费率必须是大于等于零且小于一的十进制字符串',
  )
  validateRateBelowOne(
    policy.slippageRate,
    issues,
    'executionPolicy.slippageRate',
    'EXECUTION_POLICY_SLIPPAGE_RATE_INVALID',
    '场景滑点率必须是大于等于零且小于一的十进制字符串',
  )
  parseOptionalPositiveDecimal(
    policy.maxFillQuantityPerTick,
    issues,
    'executionPolicy.maxFillQuantityPerTick',
    'EXECUTION_POLICY_MAX_FILL_QUANTITY_INVALID',
    '场景每 Tick 最大成交数量必须是大于零的十进制字符串',
  )
}

function exactStepCount(value: Decimal, step: Decimal): bigint | null {
  const scale = value.scale > step.scale ? value.scale : step.scale
  const valueCoefficient = value.coefficient
    * (10n ** BigInt(scale - value.scale))
  const stepCoefficient = step.coefficient
    * (10n ** BigInt(scale - step.scale))
  if (stepCoefficient <= 0n || valueCoefficient % stepCoefficient !== 0n) {
    return null
  }
  return valueCoefficient / stepCoefficient
}

function exactDistanceInSteps(
  left: Decimal,
  right: Decimal,
  step: Decimal,
): bigint | null {
  const scale = left.scale > right.scale
    ? (left.scale > step.scale ? left.scale : step.scale)
    : (right.scale > step.scale ? right.scale : step.scale)
  const leftCoefficient = left.coefficient
    * (10n ** BigInt(scale - left.scale))
  const rightCoefficient = right.coefficient
    * (10n ** BigInt(scale - right.scale))
  const stepCoefficient = step.coefficient
    * (10n ** BigInt(scale - step.scale))
  const rawDistance = leftCoefficient - rightCoefficient
  const distance = rawDistance < 0n ? -rawDistance : rawDistance
  if (stepCoefficient <= 0n || distance % stepCoefficient !== 0n) {
    return null
  }
  return distance / stepCoefficient
}

type ValidatedScalarPath = {
  duration: bigint
  start: Decimal | null
  final: Decimal | null
  explicitPrices: Decimal[]
}

function validateScalarPath(
  value: unknown,
  path: string,
  tickSize: Decimal | null,
  issues: ScenarioValidationIssue[],
): ValidatedScalarPath | null {
  if (!isRecord(value) || !hasExactFields(value, SCALAR_PATH_FIELDS)) {
    addIssue(
      issues,
      path,
      'MARKET_PATH_STRUCTURE_INVALID',
      '价格路径必须包含起点和完整分段',
    )
    return null
  }

  const start = parsePositiveDecimal(
    value.start,
    issues,
    `${path}.start`,
    'MARKET_PATH_PRICE_INVALID',
    '路径起点价格必须是大于零的十进制字符串',
  )
  if (
    start !== null
    && tickSize !== null
    && exactStepCount(start, tickSize) === null
  ) {
    addIssue(
      issues,
      `${path}.start`,
      'MARKET_PATH_PRICE_TICK_MISMATCH',
      '路径起点价格必须按权威价格步长精确对齐',
    )
  }

  if (!Array.isArray(value.segments) || value.segments.length === 0) {
    addIssue(
      issues,
      `${path}.segments`,
      'MARKET_PATH_STRUCTURE_INVALID',
      '每条价格路径至少需要一个完整分段',
    )
    return {
      duration: 0n,
      start,
      final: start,
      explicitPrices: start === null ? [] : [start],
    }
  }

  let duration = 0n
  let previous = start
  let final = start
  const explicitPrices = start === null ? [] : [start]
  value.segments.forEach((segment, index) => {
    const segmentPath = `${path}.segments[${index}]`
    if (!isRecord(segment) || !hasExactFields(segment, PATH_SEGMENT_FIELDS)) {
      addIssue(
        issues,
        segmentPath,
        'MARKET_PATH_STRUCTURE_INVALID',
        '路径分段必须且只能包含目标、时长和整数步参数',
      )
      return
    }
    const target = parsePositiveDecimal(
      segment.target,
      issues,
      `${segmentPath}.target`,
      'MARKET_PATH_PRICE_INVALID',
      '路径目标价格必须是大于零的十进制字符串',
    )
    if (
      target !== null
      && tickSize !== null
      && exactStepCount(target, tickSize) === null
    ) {
      addIssue(
        issues,
        `${segmentPath}.target`,
        'MARKET_PATH_PRICE_TICK_MISMATCH',
        '路径目标价格必须按权威价格步长精确对齐',
      )
    }

    const durationSeconds = segment.durationSeconds
    const durationValid = isPositiveSafeInteger(durationSeconds)
    if (!durationValid) {
      addIssue(
        issues,
        `${segmentPath}.durationSeconds`,
        'MARKET_PATH_DURATION_INVALID',
        '路径分段时长必须是正安全整数秒',
      )
    } else {
      duration += BigInt(durationSeconds)
    }
    if (!isNonNegativeSafeInteger(segment.offsetRangeSteps)) {
      addIssue(
        issues,
        `${segmentPath}.offsetRangeSteps`,
        'MARKET_PATH_OFFSET_RANGE_INVALID',
        '路径分段偏移步数必须是非负安全整数',
      )
    }
    if (!isNonNegativeSafeInteger(segment.volatilitySteps)) {
      addIssue(
        issues,
        `${segmentPath}.volatilitySteps`,
        'MARKET_PATH_VOLATILITY_INVALID',
        '路径分段波动步数必须是非负安全整数',
      )
    }
    const maxStepPerSecond = segment.maxStepPerSecond
    const speedValid = isPositiveSafeInteger(maxStepPerSecond)
    if (!speedValid) {
      addIssue(
        issues,
        `${segmentPath}.maxStepPerSecond`,
        'MARKET_PATH_SPEED_INVALID',
        '路径每秒最大价格步数必须是正安全整数',
      )
    }
    if (
      previous !== null
      && target !== null
      && tickSize !== null
      && durationValid
      && speedValid
    ) {
      const requiredSteps = exactDistanceInSteps(previous, target, tickSize)
      const availableSteps = BigInt(durationSeconds)
        * BigInt(maxStepPerSecond)
      if (requiredSteps !== null && requiredSteps > availableSteps) {
        addIssue(
          issues,
          segmentPath,
          'MARKET_PATH_SPEED_UNREACHABLE',
          '路径目标无法在分段时长和每秒最大步数内到达',
        )
      }
    }
    if (target !== null) {
      explicitPrices.push(target)
      previous = target
      final = target
    } else {
      previous = null
      final = null
    }
  })

  if (duration > BigInt(Number.MAX_SAFE_INTEGER)) {
    addIssue(
      issues,
      `${path}.segments`,
      'MARKET_PATH_DURATION_INVALID',
      '价格路径总时长必须是正安全整数秒',
    )
  }
  return { duration, start, final, explicitPrices }
}

function validatePathDuration(
  duration: bigint,
  expected: bigint | null,
  path: string,
  issues: ScenarioValidationIssue[],
): bigint {
  if (expected !== null && duration !== expected) {
    addIssue(
      issues,
      path,
      'MARKET_PATH_DURATION_MISMATCH',
      '所有品种和高级价格通道必须使用相同总时长',
    )
  }
  return expected ?? duration
}

function validateSimpleDerivedPrices(
  path: Record<string, unknown>,
  scalar: ValidatedScalarPath | null,
  tickSize: Decimal | null,
  issues: ScenarioValidationIssue[],
  basePath: string,
): void {
  const spreadSteps = path.spreadSteps
  const indexOffsetSteps = path.indexOffsetSteps
  const basisSteps = path.basisSteps
  if (
    scalar === null
    || tickSize === null
    || !isPositiveSafeInteger(spreadSteps)
    || typeof indexOffsetSteps !== 'number'
    || !Number.isSafeInteger(indexOffsetSteps)
    || typeof basisSteps !== 'number'
    || !Number.isSafeInteger(basisSteps)
  ) {
    return
  }
  const halfSpread = BigInt(spreadSteps) / 2n
  const indexOffset = BigInt(indexOffsetSteps)
  const basis = BigInt(basisSteps)
  for (const price of scalar.explicitPrices) {
    const lastSteps = exactStepCount(price, tickSize)
    if (lastSteps === null) {
      continue
    }
    const bidSteps = lastSteps - halfSpread
    const indexSteps = lastSteps + indexOffset
    const markSteps = indexSteps + basis
    if (
      bidSteps <= 0n
      || (
        path.productType === 'LINEAR_PERP'
        && (indexSteps <= 0n || markSteps <= 0n)
      )
    ) {
      addIssue(
        issues,
        basePath,
        'MARKET_PATH_DERIVED_PRICE_INVALID',
        '简单路径派生价格必须始终大于零',
      )
      return
    }
  }
}

function validateMarketPath(
  scenario: TradingLabScenario,
  instruments: Map<string, InstrumentValidation>,
  issues: ScenarioValidationIssue[],
): void {
  const marketPath = scenario.marketPath
  if (!isRecord(marketPath)) {
    addIssue(
      issues,
      'marketPath',
      'MARKET_PATH_REQUIRED',
      '场景必须持久化完整市场路径',
    )
    return
  }
  if (!hasExactFields(marketPath, MARKET_PATH_FIELDS)) {
    addIssue(
      issues,
      'marketPath',
      'MARKET_PATH_STRUCTURE_INVALID',
      '市场路径必须且只能包含虚拟起点、真实波动标记和品种路径',
    )
  }
  if (
    typeof marketPath.virtualStart !== 'string'
    || !marketPath.virtualStart.endsWith('Z')
    || !Number.isFinite(Date.parse(marketPath.virtualStart))
  ) {
    addIssue(
      issues,
      'marketPath.virtualStart',
      'MARKET_PATH_VIRTUAL_START_INVALID',
      '市场路径虚拟起点必须是有效 UTC Z 时间',
    )
  }
  if (typeof marketPath.realistic !== 'boolean') {
    addIssue(
      issues,
      'marketPath.realistic',
      'MARKET_PATH_REALISTIC_INVALID',
      '市场路径真实波动标记必须是布尔值',
    )
  }

  const selected = new Set(
    Array.isArray(scenario.symbols)
      ? scenario.symbols.flatMap((symbol) =>
          isRecord(symbol)
            && typeof symbol.symbol === 'string'
            && (
              symbol.productType === 'CRYPTO_SPOT'
              || symbol.productType === 'LINEAR_PERP'
            )
            ? [instrumentIdentity(symbol.productType, symbol.symbol)]
            : [])
      : [],
  )
  const pathIdentities = new Set<string>()
  let expectedDuration: bigint | null = null
  const rows = marketPath.instruments
  if (!Array.isArray(rows) || rows.length === 0) {
    addIssue(
      issues,
      'marketPath.instruments',
      'MARKET_PATH_INSTRUMENTS_REQUIRED',
      '市场路径必须包含每个已选品种',
    )
  } else {
    rows.forEach((path, index) => {
      const base = `marketPath.instruments[${index}]`
      if (!isRecord(path)) {
        addIssue(
          issues,
          base,
          'MARKET_PATH_STRUCTURE_INVALID',
          '品种市场路径必须是对象',
        )
        return
      }
      const identity = instrumentIdentity(path.productType, path.symbol)
      if (pathIdentities.has(identity)) {
        addIssue(
          issues,
          base,
          'MARKET_PATH_IDENTITY_DUPLICATE',
          '市场路径品种身份不能重复',
        )
      }
      pathIdentities.add(identity)
      const instrument = instruments.get(identity)
      if (instrument === undefined) {
        addIssue(
          issues,
          base,
          'MARKET_PATH_IDENTITY_UNSUPPORTED',
          '市场路径品种身份不在权威配置快照中',
        )
      }
      if (!selected.has(identity)) {
        addIssue(
          issues,
          base,
          'MARKET_PATH_IDENTITY_UNSELECTED',
          '市场路径品种身份必须属于场景已选集合',
        )
      }
      if (
        typeof path.seed !== 'string'
        || path.seed.trim().length === 0
        || path.seed.length > 256
      ) {
        addIssue(
          issues,
          `${base}.seed`,
          'MARKET_PATH_SEED_INVALID',
          '品种路径种子必须是长度不超过 256 的非空文本',
        )
      }

      if (path.mode === 'SIMPLE') {
        const fields = new Set([
          'mode',
          'productType',
          'symbol',
          'seed',
          'last',
          'spreadSteps',
          'indexOffsetSteps',
          'basisSteps',
          ...(path.productType === 'LINEAR_PERP' ? ['fundingRate'] : []),
        ])
        if (
          (path.productType !== 'CRYPTO_SPOT'
            && path.productType !== 'LINEAR_PERP')
          || !hasExactFields(path, fields)
        ) {
          addIssue(
            issues,
            base,
            'MARKET_PATH_STRUCTURE_INVALID',
            '简单品种路径字段与产品类型不匹配',
          )
        }
        if (!isPositiveSafeInteger(path.spreadSteps)) {
          addIssue(
            issues,
            `${base}.spreadSteps`,
            'MARKET_PATH_SPREAD_INVALID',
            '简单路径价差步数必须是正安全整数',
          )
        }
        if (!Number.isSafeInteger(path.indexOffsetSteps)) {
          addIssue(
            issues,
            `${base}.indexOffsetSteps`,
            'MARKET_PATH_INDEX_OFFSET_INVALID',
            '简单路径指数偏移必须是安全整数步',
          )
        }
        if (!Number.isSafeInteger(path.basisSteps)) {
          addIssue(
            issues,
            `${base}.basisSteps`,
            'MARKET_PATH_BASIS_INVALID',
            '简单路径基差必须是安全整数步',
          )
        }
        if (path.productType === 'LINEAR_PERP') {
          parseDecimalField(
            path.fundingRate,
            issues,
            `${base}.fundingRate`,
            'MARKET_PATH_FUNDING_RATE_INVALID',
            '永续路径资金费率必须是十进制字符串',
          )
        }
        const scalar = validateScalarPath(
          path.last,
          `${base}.last`,
          instrument?.tickSize ?? null,
          issues,
        )
        if (scalar !== null) {
          expectedDuration = validatePathDuration(
            scalar.duration,
            expectedDuration,
            `${base}.last`,
            issues,
          )
        }
        validateSimpleDerivedPrices(
          path,
          scalar,
          instrument?.tickSize ?? null,
          issues,
          base,
        )
        return
      }

      if (path.mode !== 'ADVANCED') {
        addIssue(
          issues,
          `${base}.mode`,
          'MARKET_PATH_STRUCTURE_INVALID',
          '品种路径模式只支持 SIMPLE 或 ADVANCED',
        )
        return
      }
      const perpetual = path.productType === 'LINEAR_PERP'
      const fields = new Set([
        'mode',
        'productType',
        'symbol',
        'seed',
        'prices',
        ...(perpetual ? ['fundingRate'] : []),
      ])
      if (
        (path.productType !== 'CRYPTO_SPOT' && !perpetual)
        || !hasExactFields(path, fields)
      ) {
        addIssue(
          issues,
          base,
          'MARKET_PATH_STRUCTURE_INVALID',
          '高级品种路径字段与产品类型不匹配',
        )
      }
      if (perpetual) {
        parseDecimalField(
          path.fundingRate,
          issues,
          `${base}.fundingRate`,
          'MARKET_PATH_FUNDING_RATE_INVALID',
          '永续路径资金费率必须是十进制字符串',
        )
      }
      const laneNames = perpetual
        ? ['bid', 'ask', 'last', 'mark', 'index']
        : ['bid', 'ask', 'last']
      if (
        !isRecord(path.prices)
        || !hasExactFields(path.prices, new Set(laneNames))
      ) {
        addIssue(
          issues,
          `${base}.prices`,
          'MARKET_PATH_STRUCTURE_INVALID',
          '高级路径必须精确提供该产品类型的全部价格通道',
        )
        return
      }
      let instrumentDuration: bigint | null = null
      const laneResults = new Map<string, ValidatedScalarPath>()
      for (const lane of laneNames) {
        const result = validateScalarPath(
          path.prices[lane],
          `${base}.prices.${lane}`,
          instrument?.tickSize ?? null,
          issues,
        )
        if (result === null) {
          continue
        }
        laneResults.set(lane, result)
        instrumentDuration = validatePathDuration(
          result.duration,
          instrumentDuration,
          `${base}.prices.${lane}`,
          issues,
        )
      }
      if (instrumentDuration !== null) {
        expectedDuration = validatePathDuration(
          instrumentDuration,
          expectedDuration,
          base,
          issues,
        )
      }
      const bidFinal = laneResults.get('bid')?.final
      const askFinal = laneResults.get('ask')?.final
      if (
        bidFinal !== null
        && bidFinal !== undefined
        && askFinal !== null
        && askFinal !== undefined
        && compare(bidFinal, askFinal) >= 0
      ) {
        addIssue(
          issues,
          `${base}.prices`,
          'MARKET_PATH_FINAL_SPREAD_INVALID',
          '高级路径配置终点必须保持 bid 小于 ask',
        )
      }
    })
  }

  for (const identity of selected) {
    if (!pathIdentities.has(identity)) {
      addIssue(
        issues,
        'marketPath.instruments',
        'MARKET_PATH_IDENTITY_MISSING',
        '每个场景已选品种必须且只能有一条市场路径',
      )
    }
  }
}

function validateScenarioIdentity(
  scenario: TradingLabScenario,
  issues: ScenarioValidationIssue[],
): void {
  if (typeof scenario.id !== 'string' || scenario.id.trim().length === 0) {
    addIssue(issues, 'id', 'SCENARIO_ID_REQUIRED', '场景 ID 不能为空')
  }
  if (typeof scenario.name !== 'string' || scenario.name.trim().length === 0) {
    addIssue(issues, 'name', 'SCENARIO_NAME_REQUIRED', '场景名称不能为空')
  } else if (scenario.name.length > 200) {
    addIssue(issues, 'name', 'SCENARIO_NAME_TOO_LONG', '场景名称不能超过 200 个字符')
  }
  if (typeof scenario.description !== 'string' || scenario.description.length > 10_000) {
    addIssue(
      issues,
      'description',
      'SCENARIO_DESCRIPTION_INVALID',
      '场景说明必须是长度不超过 10000 的文本',
    )
  }
  if (
    typeof scenario.seed !== 'string'
    || scenario.seed.length > 256
    || scenario.seed.trim().length === 0
  ) {
    addIssue(
      issues,
      'seed',
      'SCENARIO_SEED_REQUIRED',
      '场景随机种子必须是长度不超过 256 的非空文本',
    )
  }
  if (typeof scenario.negativeMode !== 'boolean') {
    addIssue(
      issues,
      'negativeMode',
      'NEGATIVE_MODE_INVALID',
      '负向模式标记必须是布尔值',
    )
  }
  if (
    typeof scenario.modelVersion !== 'string'
    || scenario.modelVersion.length === 0
    || scenario.modelVersion !== scenario.configSnapshot?.modelVersion
  ) {
    addIssue(
      issues,
      'modelVersion',
      'MODEL_VERSION_MISMATCH',
      '场景模型版本必须与配置快照一致',
    )
  }
  if (
    typeof scenario.configSnapshotHash !== 'string'
    || !HASH.test(scenario.configSnapshotHash)
  ) {
    addIssue(
      issues,
      'configSnapshotHash',
      'CONFIG_SNAPSHOT_HASH_INVALID',
      '配置快照哈希必须是 64 位小写 SHA-256',
    )
  }
}

function validateInstrument(
  instrument: TradingLabInstrumentConfig,
  index: number,
  issues: ScenarioValidationIssue[],
): InstrumentValidation {
  const base = `configSnapshot.instruments[${index}]`
  if (
    typeof instrument.baseAsset !== 'string'
    || instrument.baseAsset.trim().length === 0
  ) {
    addIssue(
      issues,
      `${base}.baseAsset`,
      'INSTRUMENT_BASE_ASSET_REQUIRED',
      '基础资产必须由配置快照显式提供',
    )
  }
  if (
    typeof instrument.quoteAsset !== 'string'
    || instrument.quoteAsset.trim().length === 0
  ) {
    addIssue(
      issues,
      `${base}.quoteAsset`,
      'INSTRUMENT_QUOTE_ASSET_REQUIRED',
      '计价资产必须由配置快照显式提供',
    )
  }
  const tickSize = parsePositiveDecimal(
    instrument.tickSize,
    issues,
    `${base}.tickSize`,
    'TICK_SIZE_INVALID',
    '价格步长必须是大于零的十进制字符串',
  )
  if (!isNonNegativeSafeInteger(instrument.pricePrecision)) {
    addIssue(
      issues,
      `${base}.pricePrecision`,
      'PRICE_PRECISION_INVALID',
      '价格精度必须是非负安全整数',
    )
  } else if (tickSize !== null && tickSize.scale !== instrument.pricePrecision) {
    addIssue(
      issues,
      `${base}.pricePrecision`,
      'PRICE_PRECISION_MISMATCH',
      '价格精度必须与价格步长的小数位一致',
    )
  }

  const stepSize = parsePositiveDecimal(
    instrument.stepSize,
    issues,
    `${base}.stepSize`,
    'STEP_SIZE_INVALID',
    '数量步长必须是大于零的十进制字符串',
  )
  if (!isNonNegativeSafeInteger(instrument.quantityPrecision)) {
    addIssue(
      issues,
      `${base}.quantityPrecision`,
      'QUANTITY_PRECISION_INVALID',
      '数量精度必须是非负安全整数',
    )
  } else if (stepSize !== null && stepSize.scale !== instrument.quantityPrecision) {
    addIssue(
      issues,
      `${base}.quantityPrecision`,
      'QUANTITY_PRECISION_MISMATCH',
      '数量精度必须与数量步长的小数位一致',
    )
  }

  const minQty = parsePositiveDecimal(
    instrument.minQty,
    issues,
    `${base}.minQty`,
    'MIN_QUANTITY_INVALID',
    '最小数量必须是大于零的十进制字符串',
  )
  const maxQty = parsePositiveDecimal(
    instrument.maxQty,
    issues,
    `${base}.maxQty`,
    'MAX_QUANTITY_INVALID',
    '最大数量必须是大于零的十进制字符串',
  )
  const minNotional = parseOptionalPositiveDecimal(
    instrument.minNotional,
    issues,
    `${base}.minNotional`,
    'MIN_NOTIONAL_INVALID',
    '最小名义价值必须是大于零的十进制字符串',
  )
  const maxNotional = parseOptionalPositiveDecimal(
    instrument.maxNotional,
    issues,
    `${base}.maxNotional`,
    'MAX_NOTIONAL_INVALID',
    '最大名义价值必须是大于零的十进制字符串',
  )

  if (minQty !== null && maxQty !== null && compare(maxQty, minQty) < 0) {
    addIssue(
      issues,
      `${base}.maxQty`,
      'QUANTITY_RANGE_INVALID',
      '最大数量不能小于最小数量',
    )
  }
  if (
    minNotional !== null
    && maxNotional !== null
    && compare(maxNotional, minNotional) < 0
  ) {
    addIssue(
      issues,
      `${base}.maxNotional`,
      'NOTIONAL_RANGE_INVALID',
      '最大名义价值不能小于最小名义价值',
    )
  }

  const initialMarginRate = parsePositiveDecimal(
    instrument.initialMarginRate,
    issues,
    `${base}.initialMarginRate`,
    'INITIAL_MARGIN_RATE_INVALID',
    '初始保证金率必须是大于零的十进制字符串',
  )
  const maintenanceMarginRate = parseNonNegativeDecimal(
    instrument.maintenanceMarginRate,
    issues,
    `${base}.maintenanceMarginRate`,
    'MAINTENANCE_MARGIN_RATE_INVALID',
    '维持保证金率必须是非负十进制字符串',
  )
  parseNonNegativeDecimal(
    instrument.liquidationFeeRate,
    issues,
    `${base}.liquidationFeeRate`,
    'LIQUIDATION_FEE_RATE_INVALID',
    '强平手续费率必须是非负十进制字符串',
  )
  parseDecimalField(
    instrument.fixedFundingRate,
    issues,
    `${base}.fixedFundingRate`,
    'FUNDING_RATE_INVALID',
    '固定资金费率必须是十进制字符串',
  )
  parsePositiveDecimal(
    instrument.contractSize,
    issues,
    `${base}.contractSize`,
    'CONTRACT_SIZE_INVALID',
    '合约大小必须是大于零的十进制字符串',
  )
  if (initialMarginRate !== null && compare(initialMarginRate, ONE) > 0) {
    addIssue(
      issues,
      `${base}.initialMarginRate`,
      'INITIAL_MARGIN_RATE_OUT_OF_RANGE',
      '初始保证金率不能大于一',
    )
  }
  if (
    initialMarginRate !== null
    && maintenanceMarginRate !== null
    && compare(maintenanceMarginRate, initialMarginRate) > 0
  ) {
    addIssue(
      issues,
      `${base}.maintenanceMarginRate`,
      'MARGIN_RATE_RANGE_INVALID',
      '维持保证金率不能大于初始保证金率',
    )
  }
  if (!isPositiveSafeInteger(instrument.fixedFundingIntervalMinutes)) {
    addIssue(
      issues,
      `${base}.fixedFundingIntervalMinutes`,
      'FUNDING_INTERVAL_INVALID',
      '资金费间隔分钟数必须是正安全整数',
    )
  }
  if (!isPositiveSafeInteger(instrument.maxLeverage)) {
    addIssue(
      issues,
      `${base}.maxLeverage`,
      'MAX_LEVERAGE_INVALID',
      '最大杠杆必须是正安全整数',
    )
  }
  if (
    !isPositiveSafeInteger(instrument.defaultLeverage)
    || (
      isPositiveSafeInteger(instrument.maxLeverage)
      && instrument.defaultLeverage > instrument.maxLeverage
    )
  ) {
    addIssue(
      issues,
      `${base}.defaultLeverage`,
      'DEFAULT_LEVERAGE_INVALID',
      '默认杠杆必须处于一到最大杠杆范围内',
    )
  }

  return {
    config: instrument,
    tickSize,
    stepSize,
    minQty,
    maxQty,
    minNotional,
    maxNotional,
  }
}

function validateConfig(
  scenario: TradingLabScenario,
  issues: ScenarioValidationIssue[],
): Map<string, InstrumentValidation> {
  const instruments = new Map<string, InstrumentValidation>()
  const rows = scenario.configSnapshot?.instruments
  if (!Array.isArray(rows) || rows.length === 0) {
    addIssue(
      issues,
      'configSnapshot.instruments',
      'INSTRUMENTS_REQUIRED',
      '配置快照至少需要一个交易品种',
    )
    return instruments
  }

  rows.forEach((instrument, index) => {
    if (!isRecord(instrument)) {
      addIssue(
        issues,
        `configSnapshot.instruments[${index}]`,
        'INSTRUMENT_INVALID',
        '配置品种必须是对象',
      )
      return
    }
    const path = `configSnapshot.instruments[${index}].symbol`
    const identity = instrumentIdentity(instrument.productType, instrument.symbol)
    if (typeof instrument.symbol !== 'string' || instrument.symbol.trim().length === 0) {
      addIssue(issues, path, 'INSTRUMENT_SYMBOL_REQUIRED', '配置品种代码不能为空')
    } else if (instruments.has(identity)) {
      addIssue(issues, path, 'INSTRUMENT_SYMBOL_DUPLICATE', '相同产品类型的配置品种代码不能重复')
    }
    if (
      instrument.productType !== 'CRYPTO_SPOT'
      && instrument.productType !== 'LINEAR_PERP'
    ) {
      addIssue(
        issues,
        `configSnapshot.instruments[${index}].productType`,
        'PRODUCT_TYPE_INVALID',
        '配置品种类型只支持现货或线性永续',
      )
    }

    const validated = validateInstrument(instrument, index, issues)
    if (typeof instrument.symbol === 'string' && !instruments.has(identity)) {
      instruments.set(identity, validated)
    }
  })

  const policy = scenario.configSnapshot.executionPolicy
  if (policy?.matchingMode !== 'SIMPLE' && policy?.matchingMode !== 'DEPTH') {
    addIssue(
      issues,
      'configSnapshot.executionPolicy.matchingMode',
      'MATCHING_MODE_INVALID',
      '撮合模式只支持简单模式或深度模式',
    )
  }
  parseNonNegativeDecimal(
    policy?.makerFeeRate,
    issues,
    'configSnapshot.executionPolicy.makerFeeRate',
    'MAKER_FEE_RATE_INVALID',
    'Maker 手续费率必须是非负十进制字符串',
  )
  parseNonNegativeDecimal(
    policy?.takerFeeRate,
    issues,
    'configSnapshot.executionPolicy.takerFeeRate',
    'TAKER_FEE_RATE_INVALID',
    'Taker 手续费率必须是非负十进制字符串',
  )
  parseNonNegativeDecimal(
    policy?.liquidationFeeRate,
    issues,
    'configSnapshot.executionPolicy.liquidationFeeRate',
    'POLICY_LIQUIDATION_FEE_RATE_INVALID',
    '策略强平手续费率必须是非负十进制字符串',
  )
  parseNonNegativeDecimal(
    policy?.slippageRate,
    issues,
    'configSnapshot.executionPolicy.slippageRate',
    'SLIPPAGE_RATE_INVALID',
    '滑点率必须是非负十进制字符串',
  )
  parseOptionalPositiveDecimal(
    policy?.maxFillQuantityPerTick,
    issues,
    'configSnapshot.executionPolicy.maxFillQuantityPerTick',
    'MAX_FILL_QUANTITY_INVALID',
    '每 Tick 最大成交数量必须是大于零的十进制字符串',
  )
  return instruments
}

function validateBalances(
  scenario: TradingLabScenario,
  issues: ScenarioValidationIssue[],
): void {
  if (!isRecord(scenario.initialBalances) || Object.keys(scenario.initialBalances).length === 0) {
    addIssue(
      issues,
      'initialBalances',
      'INITIAL_BALANCES_REQUIRED',
      '初始余额至少需要一个资产',
    )
    return
  }
  const canonicalAssets = new Set<string>()
  for (const asset of Object.keys(scenario.initialBalances).sort()) {
    const canonicalAsset = asset.trim().toUpperCase()
    if (!/^[A-Z0-9]{2,20}$/.test(asset)) {
      addIssue(
        issues,
        `initialBalances.${asset}`,
        'INITIAL_BALANCE_ASSET_INVALID',
        '初始余额资产代码必须是 2 到 20 位大写字母或数字',
      )
    }
    if (canonicalAssets.has(canonicalAsset)) {
      addIssue(
        issues,
        `initialBalances.${asset}`,
        'INITIAL_BALANCE_ASSET_DUPLICATE',
        '规范化后的初始余额资产代码不能重复',
      )
    }
    canonicalAssets.add(canonicalAsset)
    const amount = parseNonNegativeDecimal(
      scenario.initialBalances[asset],
      issues,
      `initialBalances.${asset}`,
      'INITIAL_BALANCE_INVALID',
      '初始余额必须是非负十进制字符串',
    )
    if (amount !== null) {
      const coefficientDigits = amount.coefficient === 0n
        ? 1
        : (amount.coefficient < 0n
            ? -amount.coefficient
            : amount.coefficient).toString().length
      const exactScaleDigits = amount.scale <= 8
        ? coefficientDigits + (8 - amount.scale)
        : 25
      if (amount.scale > 8 || exactScaleDigits > 24) {
        addIssue(
          issues,
          `initialBalances.${asset}`,
          'INITIAL_BALANCE_NUMERIC_INVALID',
          '初始余额必须能精确表示为 NUMERIC(24,8)',
        )
      }
    }
  }
  if (!canonicalAssets.has('USDT')) {
    addIssue(
      issues,
      'initialBalances.USDT',
      'INITIAL_BALANCE_USDT_REQUIRED',
      '初始余额必须包含 USDT',
    )
  }
}

function validateDefaults(
  scenario: TradingLabScenario,
  instruments: Map<string, InstrumentValidation>,
  issues: ScenarioValidationIssue[],
): void {
  if (
    scenario.defaults?.positionMode !== 'ONE_WAY'
    && scenario.defaults?.positionMode !== 'HEDGE'
  ) {
    addIssue(
      issues,
      'defaults.positionMode',
      'DEFAULT_POSITION_MODE_INVALID',
      '默认持仓模式只支持单向或双向',
    )
  }
  if (
    scenario.defaults?.marginMode !== 'CROSS'
    && scenario.defaults?.marginMode !== 'ISOLATED'
  ) {
    addIssue(
      issues,
      'defaults.marginMode',
      'DEFAULT_MARGIN_MODE_INVALID',
      '默认保证金模式只支持全仓或逐仓',
    )
  }
  const selectedPerpetualLimits = Array.isArray(scenario.symbols)
    ? scenario.symbols.flatMap((symbol) => {
        if (!isRecord(symbol)) {
          return []
        }
        const instrument = instruments.get(
          instrumentIdentity(symbol.productType, symbol.symbol),
        )
        return instrument?.config.productType === 'LINEAR_PERP'
          && isPositiveSafeInteger(instrument.config.maxLeverage)
          ? [instrument.config.maxLeverage]
          : []
      })
    : []
  const maxLeverage = selectedPerpetualLimits.length === 0
    ? 1
    : selectedPerpetualLimits.reduce((lowest, value) =>
        value < lowest ? value : lowest)
  if (
    !isPositiveSafeInteger(scenario.defaults?.leverage)
    || scenario.defaults.leverage > maxLeverage
  ) {
    addIssue(
      issues,
      'defaults.leverage',
      'DEFAULT_LEVERAGE_OUT_OF_RANGE',
      '默认杠杆必须处于一到品种最大杠杆范围内',
    )
  }
}

function validateSymbols(
  scenario: TradingLabScenario,
  instruments: Map<string, InstrumentValidation>,
  issues: ScenarioValidationIssue[],
): void {
  if (!Array.isArray(scenario.symbols) || scenario.symbols.length === 0) {
    addIssue(issues, 'symbols', 'SCENARIO_SYMBOLS_REQUIRED', '场景至少需要一个交易品种')
    return
  }
  const seen = new Set<string>()
  scenario.symbols.forEach((symbol, index) => {
    const path = `symbols[${index}]`
    if (!isRecord(symbol)) {
      addIssue(issues, path, 'SCENARIO_SYMBOL_INVALID', '场景品种必须是对象')
      return
    }
    const identity = instrumentIdentity(symbol.productType, symbol.symbol)
    if (seen.has(identity)) {
      addIssue(
        issues,
        `${path}.symbol`,
        'SCENARIO_SYMBOL_DUPLICATE',
        '相同产品类型的场景品种代码不能重复',
      )
    }
    seen.add(identity)
    const instrument = instruments.get(identity)
    if (instrument === undefined) {
      addIssue(
        issues,
        `${path}.symbol`,
        'SCENARIO_SYMBOL_UNSUPPORTED',
        '场景品种不在配置快照中',
      )
    } else if (instrument.config.productType !== symbol.productType) {
      addIssue(
        issues,
        `${path}.productType`,
        'SCENARIO_PRODUCT_TYPE_MISMATCH',
        '场景品种类型必须与配置快照一致',
      )
    }
  })
}

function validateTrigger(
  trigger: TimelineTrigger,
  path: string,
  actionSequences: Map<string, number>,
  currentSequence: unknown,
  issues: ScenarioValidationIssue[],
  depth = 0,
): void {
  if (!isRecord(trigger) || depth > 16) {
    addIssue(issues, path, 'TRIGGER_INVALID', '动作触发条件无效或嵌套过深')
    return
  }
  switch (trigger.type) {
    case 'VIRTUAL_TIME':
      if (!isPositiveSafeInteger(trigger.atSecond)) {
        addIssue(
          issues,
          `${path}.atSecond`,
          'TRIGGER_SECOND_INVALID',
          '虚拟时间秒数必须是正安全整数',
        )
      }
      break
    case 'PRICE': {
      if (!['BID', 'ASK', 'LAST', 'MARK', 'INDEX'].includes(trigger.priceType)) {
        addIssue(
          issues,
          `${path}.priceType`,
          'TRIGGER_PRICE_TYPE_INVALID',
          '价格触发类型无效',
        )
      }
      if (trigger.operator !== 'GTE' && trigger.operator !== 'LTE') {
        addIssue(
          issues,
          `${path}.operator`,
          'TRIGGER_OPERATOR_INVALID',
          '价格触发运算符无效',
        )
      }
      parsePositiveDecimal(
        trigger.value,
        issues,
        `${path}.value`,
        'TRIGGER_PRICE_INVALID',
        '触发价格必须是大于零的十进制字符串',
      )
      break
    }
    case 'AFTER_ACTION':
      if (
        typeof trigger.actionId !== 'string'
        || trigger.actionId.length === 0
        || !actionSequences.has(trigger.actionId)
      ) {
        addIssue(
          issues,
          `${path}.actionId`,
          'TRIGGER_ACTION_INVALID',
          '动作后触发必须引用场景中的动作',
        )
      } else {
        const referencedSequence = actionSequences.get(trigger.actionId)
        if (
          referencedSequence === undefined
          || !isNonNegativeSafeInteger(currentSequence)
          || referencedSequence >= currentSequence
        ) {
          addIssue(
            issues,
            `${path}.actionId`,
            'TRIGGER_ACTION_ORDER_INVALID',
            '动作后触发只能引用顺序更早的动作',
          )
        }
      }
      if (!isNonNegativeSafeInteger(trigger.delaySeconds)) {
        addIssue(
          issues,
          `${path}.delaySeconds`,
          'TRIGGER_DELAY_INVALID',
          '动作后延迟秒数必须是非负安全整数',
        )
      }
      break
    case 'GROUP':
      if (
        (trigger.operator !== 'ALL' && trigger.operator !== 'ANY')
        || !Array.isArray(trigger.items)
        || trigger.items.length === 0
      ) {
        addIssue(issues, path, 'TRIGGER_GROUP_INVALID', '组合触发必须包含有效的全部或任一条件')
        return
      }
      trigger.items.forEach((item, index) => validateTrigger(
        item,
        `${path}.items[${index}]`,
        actionSequences,
        currentSequence,
        issues,
        depth + 1,
      ))
      break
    default:
      addIssue(issues, `${path}.type`, 'TRIGGER_TYPE_INVALID', '动作触发类型无效')
  }
}

function isAligned(value: Decimal, step: Decimal): boolean {
  return compare(floorToStep(value, step), value) === 0
}

function validateDecimalStringParameter(
  value: unknown,
  path: string,
  issues: ScenarioValidationIssue[],
): void {
  if (typeof value !== 'string') {
    addIssue(
      issues,
      path,
      'ACTION_DECIMAL_STRING_REQUIRED',
      '动作中的金额、价格、数量和费率必须使用十进制字符串',
    )
    return
  }
  try {
    decimal(value)
  } catch {
    addIssue(
      issues,
      path,
      'ACTION_DECIMAL_STRING_REQUIRED',
      '动作中的金额、价格、数量和费率必须使用普通十进制字符串',
    )
  }
}

function validateAttachedProtections(
  value: unknown,
  path: string,
  issues: ScenarioValidationIssue[],
): void {
  if (!Array.isArray(value) || value.length > 10) {
    addIssue(
      issues,
      path,
      'ATTACHED_PROTECTIONS_INVALID',
      '附加保护必须是最多十项的数组',
    )
    return
  }
  const allowed = new Set([
    'protectionType',
    'triggerPrice',
    'triggerPriceType',
    'triggerExecutionType',
    'price',
    'quantity',
    'quantityUnit',
  ])
  value.forEach((item, index) => {
    const itemPath = `${path}[${index}]`
    if (!isRecord(item)) {
      addIssue(
        issues,
        itemPath,
        'ATTACHED_PROTECTION_INVALID',
        '附加保护项必须是对象',
      )
      return
    }
    for (const key of Object.keys(item).sort()) {
      if (!allowed.has(key)) {
        addIssue(
          issues,
          `${itemPath}.${key}`,
          'ACTION_PARAMETER_UNKNOWN',
          '动作包含当前运行器不支持的参数',
        )
      }
    }
    for (const key of ['protectionType', 'triggerExecutionType']) {
      if (typeof item[key] !== 'string' || item[key].trim().length === 0) {
        addIssue(
          issues,
          `${itemPath}.${key}`,
          'ACTION_PARAMETER_REQUIRED',
          '附加保护缺少必填文本参数',
        )
      }
    }
    if (item.triggerPrice === undefined) {
      addIssue(
        issues,
        `${itemPath}.triggerPrice`,
        'ACTION_PARAMETER_REQUIRED',
        '附加保护缺少触发价格',
      )
    }
    for (const key of ['triggerPrice', 'price', 'quantity']) {
      if (item[key] !== undefined) {
        validateDecimalStringParameter(item[key], `${itemPath}.${key}`, issues)
      }
    }
    for (const key of ['triggerPriceType', 'quantityUnit']) {
      if (
        item[key] !== undefined
        && (typeof item[key] !== 'string' || item[key].trim().length === 0)
      ) {
        addIssue(
          issues,
          `${itemPath}.${key}`,
          'ACTION_TEXT_PARAMETER_INVALID',
          '动作文本参数不能为空',
        )
      }
    }
    if (item.quantityUnit !== undefined && item.quantity === undefined) {
      addIssue(
        issues,
        `${itemPath}.quantity`,
        'ATTACHED_PROTECTION_QUANTITY_REQUIRED',
        '附加保护指定数量单位时必须提供数量',
      )
    }
  })
}

function validateActionParameterSchema(
  action: TimelineAction,
  path: string,
): ScenarioValidationIssue[] {
  const issues: ScenarioValidationIssue[] = []
  if (!isRecord(action.parameters)) {
    addIssue(
      issues,
      `${path}.parameters`,
      'ACTION_PARAMETERS_INVALID',
      '动作参数必须是对象',
    )
    return issues
  }

  const parameters = action.parameters
  let allowed: ReadonlySet<string>
  switch (action.type) {
    case 'PLACE_ORDER':
      allowed = PLACE_ORDER_PARAMETERS
      break
    case 'CANCEL_ORDER':
      allowed = new Set(['clientOrderId'])
      break
    case 'CANCEL_ALL':
      allowed = new Set()
      break
    case 'SET_POSITION_MODE':
      allowed = new Set(['positionMode'])
      break
    case 'SET_MARGIN_MODE':
      allowed = new Set(['marginMode'])
      break
    case 'SET_LEVERAGE':
      allowed = new Set(['leverage'])
      break
    default:
      return issues
  }

  for (const key of Object.keys(parameters).sort()) {
    if (!allowed.has(key)) {
      addIssue(
        issues,
        `${path}.parameters.${key}`,
        'ACTION_PARAMETER_UNKNOWN',
        '动作包含当前运行器不支持的参数',
      )
    }
  }

  if (action.type === 'PLACE_ORDER') {
    for (const key of ['side', 'orderType', 'quantity']) {
      if (parameters[key] === undefined) {
        addIssue(
          issues,
          `${path}.parameters.${key}`,
          'ACTION_PARAMETER_REQUIRED',
          '下单动作缺少必填参数',
        )
      }
    }
    for (const key of PLACE_ORDER_DECIMAL_PARAMETERS) {
      if (parameters[key] !== undefined) {
        validateDecimalStringParameter(
          parameters[key],
          `${path}.parameters.${key}`,
          issues,
        )
      }
    }
    for (const key of PLACE_ORDER_TEXT_PARAMETERS) {
      if (
        parameters[key] !== undefined
        && (
          typeof parameters[key] !== 'string'
          || parameters[key].trim().length === 0
        )
      ) {
        addIssue(
          issues,
          `${path}.parameters.${key}`,
          'ACTION_TEXT_PARAMETER_INVALID',
          '动作文本参数不能为空',
        )
      }
    }
    for (const key of PLACE_ORDER_BOOLEAN_PARAMETERS) {
      if (parameters[key] !== undefined && typeof parameters[key] !== 'boolean') {
        addIssue(
          issues,
          `${path}.parameters.${key}`,
          'ACTION_BOOLEAN_PARAMETER_INVALID',
          '动作布尔参数类型无效',
        )
      }
    }
    if (
      parameters.leverage !== undefined
      && !isPositiveSafeInteger(parameters.leverage)
    ) {
      addIssue(
        issues,
        `${path}.parameters.leverage`,
        'ACTION_INTEGER_PARAMETER_INVALID',
        '动作杠杆必须是正安全整数',
      )
    }
    if (parameters.attachedProtections !== undefined) {
      validateAttachedProtections(
        parameters.attachedProtections,
        `${path}.parameters.attachedProtections`,
        issues,
      )
    }
    return issues
  }

  const requiredText = action.type === 'CANCEL_ORDER'
    ? 'clientOrderId'
    : action.type === 'SET_POSITION_MODE'
      ? 'positionMode'
      : action.type === 'SET_MARGIN_MODE'
        ? 'marginMode'
        : null
  if (
    requiredText !== null
    && (
      typeof parameters[requiredText] !== 'string'
      || parameters[requiredText].trim().length === 0
    )
  ) {
    addIssue(
      issues,
      `${path}.parameters.${requiredText}`,
      'ACTION_PARAMETER_REQUIRED',
      '动作缺少必填文本参数',
    )
  }
  if (
    action.type === 'SET_LEVERAGE'
    && !isPositiveSafeInteger(parameters.leverage)
  ) {
    addIssue(
      issues,
      `${path}.parameters.leverage`,
      'ACTION_PARAMETER_REQUIRED',
      '设置杠杆动作必须提供正安全整数杠杆',
    )
  }
  return issues
}

function validateOrderBusiness(
  action: TimelineAction,
  instrument: InstrumentValidation,
  path: string,
): ScenarioValidationIssue[] {
  const issues: ScenarioValidationIssue[] = []
  if (!isRecord(action.parameters)) {
    return issues
  }
  const parameters = action.parameters
  if (action.type === 'PLACE_ORDER') {
    const orderType = parameters.orderType
    if (parameters.side !== 'BUY' && parameters.side !== 'SELL') {
      addIssue(issues, `${path}.parameters.side`, 'ORDER_SIDE_INVALID', '订单方向只支持买入或卖出')
    }
    if (!ORDER_TYPES.has(orderType as string)) {
      addIssue(
        issues,
        `${path}.parameters.orderType`,
        'ORDER_TYPE_INVALID',
        '订单类型不在实验室支持范围内',
      )
    }
    if (
      parameters.positionSide !== undefined
      && !POSITION_SIDES.has(parameters.positionSide as string)
    ) {
      addIssue(
        issues,
        `${path}.parameters.positionSide`,
        'POSITION_SIDE_INVALID',
        '持仓方向只支持 BOTH、LONG 或 SHORT',
      )
    }
    if (
      parameters.quantityUnit !== undefined
      && !QUANTITY_UNITS.has(parameters.quantityUnit as string)
    ) {
      addIssue(
        issues,
        `${path}.parameters.quantityUnit`,
        'QUANTITY_UNIT_INVALID',
        '数量单位只支持 BASE、QUOTE 或 CONTRACTS',
      )
    }
    if (
      parameters.triggerPriceType !== undefined
      && !TRIGGER_PRICE_TYPES.has(parameters.triggerPriceType as string)
    ) {
      addIssue(
        issues,
        `${path}.parameters.triggerPriceType`,
        'TRIGGER_PRICE_TYPE_INVALID',
        '触发价格类型只支持最新价或标记价',
      )
    }
    if (
      parameters.timeInForce !== undefined
      && !TIME_IN_FORCE_VALUES.has(parameters.timeInForce as string)
    ) {
      addIssue(
        issues,
        `${path}.parameters.timeInForce`,
        'TIME_IN_FORCE_INVALID',
        '订单有效期只支持 GTC、IOC 或 FOK',
      )
    }

    const quantity = parseDecimalField(
      parameters.quantity,
      issues,
      `${path}.parameters.quantity`,
      'QUANTITY_INVALID',
      '订单数量必须是普通十进制字符串',
    )
    const isSpotMarketQuoteBudget =
      action.productType === 'CRYPTO_SPOT'
      && orderType === 'MARKET'
      && parameters.side === 'BUY'
      && parameters.quantityUnit === 'QUOTE'
    const isPerpetualContractQuantity =
      action.productType === 'LINEAR_PERP'
      && parameters.quantityUnit === 'CONTRACTS'
    if (quantity !== null) {
      if (
        !isSpotMarketQuoteBudget
        && !isPerpetualContractQuantity
        && compare(quantity, ZERO) >= 0
        && instrument.stepSize !== null
        && !isAligned(quantity, instrument.stepSize)
      ) {
        addIssue(
          issues,
          `${path}.parameters.quantity`,
          'QUANTITY_STEP_MISMATCH',
          '订单数量必须按品种数量步长对齐',
        )
      }
      if (
        !isSpotMarketQuoteBudget
        && !isPerpetualContractQuantity
        && instrument.minQty !== null
        && compare(quantity, instrument.minQty) < 0
      ) {
        addIssue(
          issues,
          `${path}.parameters.quantity`,
          'QUANTITY_BELOW_MINIMUM',
          '订单数量不能小于品种最小数量',
        )
      }
      if (
        !isSpotMarketQuoteBudget
        && !isPerpetualContractQuantity
        && instrument.maxQty !== null
        && compare(quantity, instrument.maxQty) > 0
      ) {
        addIssue(
          issues,
          `${path}.parameters.quantity`,
          'QUANTITY_ABOVE_MAXIMUM',
          '订单数量不能大于品种最大数量',
        )
      }
      if (
        isPerpetualContractQuantity
        && (compare(quantity, ZERO) <= 0 || quantity.scale !== 0)
      ) {
        addIssue(
          issues,
          `${path}.parameters.quantity`,
          'CONTRACT_QUANTITY_INTEGER_REQUIRED',
          '合约张数必须是正整数',
        )
      }
      if (
        isSpotMarketQuoteBudget
        && instrument.minNotional !== null
        && compare(quantity, instrument.minNotional) < 0
      ) {
        addIssue(
          issues,
          `${path}.parameters.quantity`,
          'NOTIONAL_BELOW_MINIMUM',
          '现货市价买单预算不能小于品种最小名义价值',
        )
      }
      if (
        isSpotMarketQuoteBudget
        && instrument.maxNotional !== null
        && compare(quantity, instrument.maxNotional) > 0
      ) {
        addIssue(
          issues,
          `${path}.parameters.quantity`,
          'NOTIONAL_ABOVE_MAXIMUM',
          '现货市价买单预算不能大于品种最大名义价值',
        )
      }
    }

    let price: Decimal | null = null
    if (parameters.price !== undefined) {
      price = parsePositiveDecimal(
        parameters.price,
        issues,
        `${path}.parameters.price`,
        'PRICE_INVALID',
        '订单价格必须是大于零的十进制字符串',
      )
      if (price !== null && instrument.tickSize !== null && !isAligned(price, instrument.tickSize)) {
        addIssue(
          issues,
          `${path}.parameters.price`,
          'PRICE_TICK_MISMATCH',
          '订单价格必须按品种价格步长对齐',
        )
      }
    } else if (orderType === 'LIMIT' || orderType === 'STOP_LIMIT') {
      addIssue(
        issues,
        `${path}.parameters.price`,
        'PRICE_REQUIRED',
        '限价订单必须提供价格',
      )
    }

    let triggerPrice: Decimal | null = null
    if (parameters.triggerPrice !== undefined) {
      triggerPrice = parsePositiveDecimal(
        parameters.triggerPrice,
        issues,
        `${path}.parameters.triggerPrice`,
        'TRIGGER_PRICE_INVALID',
        '触发价格必须是大于零的十进制字符串',
      )
      if (
        triggerPrice !== null
        && instrument.tickSize !== null
        && !isAligned(triggerPrice, instrument.tickSize)
      ) {
        addIssue(
          issues,
          `${path}.parameters.triggerPrice`,
          'TRIGGER_PRICE_TICK_MISMATCH',
          '触发价格必须按品种价格步长对齐',
        )
      }
    } else if (orderType === 'STOP_MARKET' || orderType === 'STOP_LIMIT') {
      addIssue(
        issues,
        `${path}.parameters.triggerPrice`,
        'TRIGGER_PRICE_REQUIRED',
        '止损订单必须提供触发价格',
      )
    }

    let activationPrice: Decimal | null = null
    if (parameters.activationPrice !== undefined) {
      activationPrice = parsePositiveDecimal(
        parameters.activationPrice,
        issues,
        `${path}.parameters.activationPrice`,
        'ACTIVATION_PRICE_INVALID',
        '追踪止损激活价格必须大于零',
      )
      if (
        activationPrice !== null
        && instrument.tickSize !== null
        && !isAligned(activationPrice, instrument.tickSize)
      ) {
        addIssue(
          issues,
          `${path}.parameters.activationPrice`,
          'ACTIVATION_PRICE_TICK_MISMATCH',
          '追踪止损激活价格必须按品种价格步长对齐',
        )
      }
    }

    if (
      quantity !== null
      && price !== null
      && !isPerpetualContractQuantity
    ) {
      const notional = multiply(quantity, price)
      if (
        instrument.minNotional !== null
        && compare(notional, instrument.minNotional) < 0
      ) {
        addIssue(
          issues,
          `${path}.parameters`,
          'NOTIONAL_BELOW_MINIMUM',
          '订单名义价值不能小于品种最小名义价值',
        )
      }
      if (
        instrument.maxNotional !== null
        && compare(notional, instrument.maxNotional) > 0
      ) {
        addIssue(
          issues,
          `${path}.parameters`,
          'NOTIONAL_ABOVE_MAXIMUM',
          '订单名义价值不能大于品种最大名义价值',
        )
      }
    }

    if (parameters.reduceOnly === true && action.productType !== 'LINEAR_PERP') {
      addIssue(
        issues,
        `${path}.parameters.reduceOnly`,
        'REDUCE_ONLY_PRODUCT_INVALID',
        '只减仓仅适用于线性永续品种',
      )
    }

    if (
      (
        orderType === 'MARKET'
        && (
          parameters.price !== undefined
          || parameters.requestedPrice !== undefined
          || parameters.triggerPrice !== undefined
          || parameters.triggerPriceType !== undefined
        )
      )
      || (
        orderType === 'LIMIT'
        && (
          parameters.triggerPrice !== undefined
          || parameters.triggerPriceType !== undefined
        )
      )
    ) {
      addIssue(
        issues,
        `${path}.parameters`,
        'ORDER_FIELDS_INVALID',
        '市价和普通限价订单不能携带不属于该订单类型的价格或触发字段',
      )
    }

    const attachedProtections = Array.isArray(parameters.attachedProtections)
      ? parameters.attachedProtections
      : []
    if (parameters.reduceOnly === true && attachedProtections.length > 0) {
      addIssue(
        issues,
        `${path}.parameters.attachedProtections`,
        'ATTACHED_PROTECTION_PARENT_INVALID',
        '只减仓父订单不能创建附加保护',
      )
    }
    if (action.productType === 'CRYPTO_SPOT') {
      if (
        parameters.positionSide !== undefined
        && parameters.positionSide !== 'BOTH'
      ) {
        addIssue(
          issues,
          `${path}.parameters.positionSide`,
          'SPOT_POSITION_SIDE_INVALID',
          '现货订单持仓方向只能是 BOTH',
        )
      }
      const expectedQuantityUnit = orderType === 'MARKET'
        && parameters.side === 'BUY'
        ? 'QUOTE'
        : 'BASE'
      if (
        parameters.quantityUnit !== undefined
        && parameters.quantityUnit !== expectedQuantityUnit
      ) {
        addIssue(
          issues,
          `${path}.parameters.quantityUnit`,
          'SPOT_QUANTITY_UNIT_INVALID',
          '现货市价买单使用 QUOTE，其他现货订单使用 BASE',
        )
      }
      if (attachedProtections.length > 0) {
        addIssue(
          issues,
          `${path}.parameters.attachedProtections`,
          'SPOT_PROTECTION_INVALID',
          '现货订单不支持附加保护',
        )
      }
    }
    if (
      parameters.stopLoss !== undefined
      || parameters.takeProfit !== undefined
    ) {
      addIssue(
        issues,
        `${path}.parameters`,
        action.productType === 'LINEAR_PERP'
          ? 'PERPETUAL_LEGACY_PROTECTION_INVALID'
          : 'SPOT_LEGACY_PROTECTION_INVALID',
        '新订单不能使用旧版标量止盈止损字段',
      )
    }

    attachedProtections.forEach((protection, index) => {
      if (!isRecord(protection)) {
        return
      }
      const protectionPath = `${path}.parameters.attachedProtections[${index}]`
      if (
        protection.protectionType !== undefined
        && !PROTECTION_TYPES.has(protection.protectionType as string)
      ) {
        addIssue(
          issues,
          `${protectionPath}.protectionType`,
          'ATTACHED_PROTECTION_TYPE_INVALID',
          '附加保护类型只支持止盈或止损',
        )
      }
      if (
        protection.triggerExecutionType !== undefined
        && !TRIGGER_EXECUTION_TYPES.has(protection.triggerExecutionType as string)
      ) {
        addIssue(
          issues,
          `${protectionPath}.triggerExecutionType`,
          'ATTACHED_PROTECTION_EXECUTION_INVALID',
          '附加保护执行类型只支持市价或限价',
        )
      }
      if (
        protection.quantityUnit !== undefined
        && !QUANTITY_UNITS.has(protection.quantityUnit as string)
      ) {
        addIssue(
          issues,
          `${protectionPath}.quantityUnit`,
          'ATTACHED_PROTECTION_QUANTITY_UNIT_INVALID',
          '附加保护数量单位无效',
        )
      }
      const protectionTrigger = parsePositiveDecimal(
        protection.triggerPrice,
        issues,
        `${protectionPath}.triggerPrice`,
        'ATTACHED_PROTECTION_TRIGGER_INVALID',
        '附加保护触发价格必须大于零',
      )
      if (
        protectionTrigger !== null
        && instrument.tickSize !== null
        && !isAligned(protectionTrigger, instrument.tickSize)
      ) {
        addIssue(
          issues,
          `${protectionPath}.triggerPrice`,
          'ATTACHED_PROTECTION_TRIGGER_TICK_MISMATCH',
          '附加保护触发价格必须按品种价格步长对齐',
        )
      }
      if (
        protection.triggerPriceType !== undefined
        && protection.triggerPriceType !== 'MARK_PRICE'
      ) {
        addIssue(
          issues,
          `${protectionPath}.triggerPriceType`,
          'ATTACHED_PROTECTION_TRIGGER_TYPE_INVALID',
          '附加保护只支持标记价触发',
        )
      }
      if (protection.triggerExecutionType === 'LIMIT') {
        const protectionPrice = parsePositiveDecimal(
          protection.price,
          issues,
          `${protectionPath}.price`,
          'ATTACHED_PROTECTION_PRICE_REQUIRED',
          '限价附加保护必须提供大于零的价格',
        )
        if (
          protectionPrice !== null
          && instrument.tickSize !== null
          && !isAligned(protectionPrice, instrument.tickSize)
        ) {
          addIssue(
            issues,
            `${protectionPath}.price`,
            'ATTACHED_PROTECTION_PRICE_TICK_MISMATCH',
            '附加保护限价必须按品种价格步长对齐',
          )
        }
      } else if (
        protection.triggerExecutionType === 'MARKET'
        && protection.price !== undefined
      ) {
        addIssue(
          issues,
          `${protectionPath}.price`,
          'ATTACHED_PROTECTION_PRICE_INVALID',
          '市价附加保护不能提供限价',
        )
      }
      if (protection.quantity !== undefined) {
        const protectionQuantity = parsePositiveDecimal(
          protection.quantity,
          issues,
          `${protectionPath}.quantity`,
          'ATTACHED_PROTECTION_QUANTITY_INVALID',
          '附加保护数量必须大于零',
        )
        if (
          protectionQuantity !== null
          && protection.quantityUnit === 'CONTRACTS'
          && protectionQuantity.scale !== 0
        ) {
          addIssue(
            issues,
            `${protectionPath}.quantity`,
            'CONTRACT_QUANTITY_INTEGER_REQUIRED',
            '合约张数必须是整数',
          )
        }
      }
    })

    const effectiveTimeInForce = parameters.timeInForce ?? 'GTC'
    if (
      parameters.postOnly === true
      && (orderType !== 'LIMIT' || effectiveTimeInForce !== 'GTC')
    ) {
      addIssue(
        issues,
        `${path}.parameters.postOnly`,
        'POST_ONLY_CONTRACT_INVALID',
        'Post Only 只支持 GTC 限价订单',
      )
    }

    const hasTrailingFields = parameters.activationPrice !== undefined
      || parameters.trailingDelta !== undefined
      || parameters.trailingRate !== undefined
    if (orderType === 'TRAILING_STOP_MARKET') {
      const trailingDelta = parameters.trailingDelta === undefined
        ? null
        : parsePositiveDecimal(
            parameters.trailingDelta,
            issues,
            `${path}.parameters.trailingDelta`,
            'TRAILING_CALLBACK_INVALID',
            '追踪止损绝对回调必须大于零',
          )
      const trailingRate = parameters.trailingRate === undefined
        ? null
        : parsePositiveDecimal(
            parameters.trailingRate,
            issues,
            `${path}.parameters.trailingRate`,
            'TRAILING_CALLBACK_INVALID',
            '追踪止损比例回调必须大于零且小于一',
          )
      if (
        (parameters.trailingDelta === undefined)
        === (parameters.trailingRate === undefined)
      ) {
        addIssue(
          issues,
          `${path}.parameters`,
          'TRAILING_CALLBACK_INVALID',
          '追踪止损必须且只能提供一种回调值',
        )
      }
      if (
        trailingDelta !== null
        && instrument.tickSize !== null
        && !isAligned(trailingDelta, instrument.tickSize)
      ) {
        addIssue(
          issues,
          `${path}.parameters.trailingDelta`,
          'TRAILING_DELTA_TICK_MISMATCH',
          '追踪止损绝对回调必须按品种价格步长对齐',
        )
      }
      if (trailingRate !== null && compare(trailingRate, ONE) >= 0) {
        addIssue(
          issues,
          `${path}.parameters.trailingRate`,
          'TRAILING_CALLBACK_INVALID',
          '追踪止损比例回调必须大于零且小于一',
        )
      }
      if (action.productType !== 'LINEAR_PERP') {
        addIssue(
          issues,
          `${path}.parameters.orderType`,
          'TRAILING_PRODUCT_INVALID',
          '追踪止损市价单仅适用于线性永续品种',
        )
      }
      if (
        parameters.reduceOnly !== true
        || effectiveTimeInForce !== 'GTC'
        || parameters.postOnly === true
        || parameters.price !== undefined
        || parameters.requestedPrice !== undefined
        || parameters.triggerPrice !== undefined
        || parameters.triggerPriceType !== undefined
        || parameters.stopLoss !== undefined
        || parameters.takeProfit !== undefined
        || (
          Array.isArray(parameters.attachedProtections)
          && parameters.attachedProtections.length > 0
        )
      ) {
        addIssue(
          issues,
          `${path}.parameters`,
          'TRAILING_CONTRACT_INVALID',
          '追踪止损必须只减仓、使用 GTC，且不能携带普通价格、触发或保护字段',
        )
      }
    } else if (hasTrailingFields) {
      addIssue(
        issues,
        `${path}.parameters`,
        'TRAILING_FIELDS_INVALID',
        '非追踪止损订单不能携带追踪参数',
      )
    }

    if (orderType === 'STOP_MARKET' || orderType === 'STOP_LIMIT') {
      const expectedTriggerPriceType = action.productType === 'LINEAR_PERP'
        ? 'MARK_PRICE'
        : 'LAST_PRICE'
      if (
        effectiveTimeInForce !== 'GTC'
        || parameters.postOnly === true
        || (
          orderType === 'STOP_MARKET'
          && (
            parameters.price !== undefined
            || parameters.requestedPrice !== undefined
          )
        )
        || (
          parameters.triggerPriceType !== undefined
          && parameters.triggerPriceType !== expectedTriggerPriceType
        )
      ) {
        addIssue(
          issues,
          `${path}.parameters`,
          'STOP_ORDER_CONTRACT_INVALID',
          '止损订单必须使用 GTC、正确的触发价格类型，且不能启用 Post Only',
        )
      }
    }
  }

  if (parameters.leverage !== undefined) {
    if (
      !isPositiveSafeInteger(parameters.leverage)
      || parameters.leverage > instrument.config.maxLeverage
    ) {
      addIssue(
        issues,
        `${path}.parameters.leverage`,
        'LEVERAGE_OUT_OF_RANGE',
        '动作杠杆必须处于一到品种最大杠杆范围内',
      )
    } else if (action.productType !== 'LINEAR_PERP') {
      addIssue(
        issues,
        `${path}.parameters.leverage`,
        'LEVERAGE_PRODUCT_INVALID',
        '杠杆设置仅适用于线性永续品种',
      )
    }
  } else if (action.type === 'SET_LEVERAGE') {
    addIssue(
      issues,
      `${path}.parameters.leverage`,
      'LEVERAGE_REQUIRED',
      '设置杠杆动作必须提供杠杆值',
    )
  }

  if (parameters.marginMode !== undefined) {
    if (parameters.marginMode !== 'CROSS' && parameters.marginMode !== 'ISOLATED') {
      addIssue(
        issues,
        `${path}.parameters.marginMode`,
        'MARGIN_MODE_INVALID',
        '保证金模式只支持全仓或逐仓',
      )
    } else if (action.productType !== 'LINEAR_PERP') {
      addIssue(
        issues,
        `${path}.parameters.marginMode`,
        'MARGIN_MODE_PRODUCT_INVALID',
        '保证金模式仅适用于线性永续品种',
      )
    }
  } else if (action.type === 'SET_MARGIN_MODE') {
    addIssue(
      issues,
      `${path}.parameters.marginMode`,
      'MARGIN_MODE_REQUIRED',
      '设置保证金模式动作必须提供目标模式',
    )
  }
  if (
    action.type === 'SET_POSITION_MODE'
    && parameters.positionMode !== 'ONE_WAY'
    && parameters.positionMode !== 'HEDGE'
  ) {
    addIssue(
      issues,
      `${path}.parameters.positionMode`,
      'POSITION_MODE_INVALID',
      '持仓模式只支持 ONE_WAY 或 HEDGE',
    )
  }
  return issues
}

function validateExpectedError(
  expectedError: unknown,
  path: string,
): ScenarioValidationIssue[] {
  const issues: ScenarioValidationIssue[] = []
  if (!isRecord(expectedError)) {
    addIssue(
      issues,
      `${path}.expectedError`,
      'EXPECTED_ERROR_INVALID',
      '预期错误必须包含精确的 HTTP 状态和业务代码',
    )
    return issues
  }
  if (
    !isNonNegativeSafeInteger(expectedError.status)
    || expectedError.status < 400
    || expectedError.status > 499
  ) {
    addIssue(
      issues,
      `${path}.expectedError.status`,
      'EXPECTED_ERROR_STATUS_INVALID',
      '预期错误状态必须是 400 到 499 的整数',
    )
  }
  if (typeof expectedError.code !== 'string' || !EXPECTED_ERROR_CODE.test(expectedError.code)) {
    addIssue(
      issues,
      `${path}.expectedError.code`,
      'EXPECTED_ERROR_CODE_INVALID',
      '预期业务代码必须使用大写字母、数字和下划线',
    )
  }
  return issues
}

function validateActions(
  scenario: TradingLabScenario,
  instruments: Map<string, InstrumentValidation>,
  issues: ScenarioValidationIssue[],
): void {
  if (!Array.isArray(scenario.timeline) || scenario.timeline.length === 0) {
    addIssue(issues, 'timeline', 'TIMELINE_REQUIRED', '场景时间线至少需要一个动作')
    return
  }
  const actionSequences = new Map<string, number>()
  for (const action of scenario.timeline) {
    if (
      isRecord(action)
      && typeof action.id === 'string'
      && action.id.length > 0
      && isNonNegativeSafeInteger(action.sequence)
      && !actionSequences.has(action.id)
    ) {
      actionSequences.set(action.id, action.sequence)
    }
  }
  const selectedSymbols = new Set(
    Array.isArray(scenario.symbols)
      ? scenario.symbols.flatMap((symbol) =>
          isRecord(symbol) && typeof symbol.symbol === 'string'
            ? [instrumentIdentity(symbol.productType, symbol.symbol)]
            : [])
      : [],
  )
  const seenIds = new Set<string>()
  const seenSequences = new Set<number>()
  let previousSequence = -1

  scenario.timeline.forEach((action, index) => {
    const path = `timeline[${index}]`
    if (!isRecord(action)) {
      addIssue(issues, path, 'ACTION_INVALID', '时间线动作必须是对象')
      return
    }
    if (typeof action.id !== 'string' || action.id.length === 0) {
      addIssue(issues, `${path}.id`, 'ACTION_ID_REQUIRED', '动作 ID 不能为空')
    } else if (seenIds.has(action.id)) {
      addIssue(issues, `${path}.id`, 'ACTION_ID_DUPLICATE', '动作 ID 不能重复')
    }
    if (typeof action.id === 'string') {
      seenIds.add(action.id)
    }

    if (!isNonNegativeSafeInteger(action.sequence)) {
      addIssue(
        issues,
        `${path}.sequence`,
        'ACTION_SEQUENCE_INVALID',
        '动作顺序必须是非负安全整数',
      )
    } else {
      if (seenSequences.has(action.sequence)) {
        addIssue(
          issues,
          `${path}.sequence`,
          'ACTION_SEQUENCE_DUPLICATE',
          '动作顺序不能重复',
        )
      }
      if (action.sequence <= previousSequence) {
        addIssue(
          issues,
          `${path}.sequence`,
          'ACTION_SEQUENCE_NOT_INCREASING',
          '时间线动作必须按顺序严格递增',
        )
      }
      seenSequences.add(action.sequence)
      previousSequence = action.sequence
    }

    if (!SUPPORTED_ACTIONS.has(action.type)) {
      if (LOCAL_ORACLE_ACTIONS.has(action.type)) {
        addIssue(
          issues,
          `${path}.type`,
          'ACTION_REQUIRES_RUNNER_SUPPORT',
          '该本地 Oracle 动作尚未建立真实运行器映射，不能启动运行',
        )
      } else {
        addIssue(issues, `${path}.type`, 'ACTION_TYPE_UNSUPPORTED', '动作类型不在支持范围内')
      }
    }
    const actionIdentity = instrumentIdentity(action.productType, action.symbol)
    const instrument = instruments.get(actionIdentity)
    if (instrument === undefined) {
      addIssue(issues, `${path}.symbol`, 'ACTION_SYMBOL_UNSUPPORTED', '动作品种不在配置快照中')
    } else if (instrument.config.productType !== action.productType) {
      addIssue(
        issues,
        `${path}.productType`,
        'ACTION_PRODUCT_TYPE_MISMATCH',
        '动作品种类型必须与配置快照一致',
      )
    }
    if (typeof action.symbol === 'string' && !selectedSymbols.has(actionIdentity)) {
      addIssue(
        issues,
        `${path}.symbol`,
        'ACTION_SYMBOL_NOT_SELECTED',
        '动作品种必须先加入场景品种列表',
      )
    }
    validateTrigger(
      action.trigger,
      `${path}.trigger`,
      actionSequences,
      action.sequence,
      issues,
    )

    if (instrument === undefined || !SUPPORTED_ACTIONS.has(action.type)) {
      return
    }
    const schemaIssues = validateActionParameterSchema(action, path)
    issues.push(...schemaIssues)
    const businessIssues = validateOrderBusiness(action, instrument, path)
    const expected = action.expectedError
    const expectedIssues = expected === undefined
      ? []
      : validateExpectedError(expected, path)

    if (scenario.negativeMode !== true) {
      if (expected !== undefined) {
        addIssue(
          issues,
          `${path}.expectedError`,
          'EXPECTED_ERROR_REQUIRES_NEGATIVE_MODE',
          '普通模式动作不能声明预期错误',
        )
      }
      issues.push(...businessIssues, ...expectedIssues)
      return
    }

    if (businessIssues.length === 0) {
      issues.push(...expectedIssues)
      return
    }
    if (expected === undefined) {
      issues.push(...businessIssues)
      addIssue(
        issues,
        `${path}.expectedError`,
        'EXPECTED_ERROR_REQUIRED',
        '负向模式中的非法动作必须声明预期错误',
      )
      return
    }
    if (expectedIssues.length > 0) {
      issues.push(...businessIssues, ...expectedIssues)
      return
    }
    issues.push(...businessIssues.map((issue) => ({
      ...issue,
      severity: 'WARNING' as const,
    })))
  })
}

export function validateScenario(
  scenario: TradingLabScenario,
): ScenarioValidationIssue[] {
  const issues: ScenarioValidationIssue[] = []
  if (!isRecord(scenario)) {
    addIssue(issues, '', 'SCENARIO_INVALID', '场景必须是对象')
    return issues
  }

  validateScenarioIdentity(scenario, issues)
  const instruments = validateConfig(scenario, issues)
  validateExecutionPolicy(scenario, issues)
  validateMarketPath(scenario, instruments, issues)
  validateBalances(scenario, issues)
  validateDefaults(scenario, instruments, issues)
  validateSymbols(scenario, instruments, issues)
  validateActions(scenario, instruments, issues)
  return issues
}

export function canRunScenario(
  issues: readonly ScenarioValidationIssue[],
): boolean {
  return !issues.some((issue) => issue.severity === 'ERROR')
}

export function isScenarioLocked(status: string): boolean {
  if (typeof status !== 'string') {
    return true
  }
  const normalized = status.trim().toUpperCase()
  return normalized !== 'DRAFT' && normalized !== 'LOCAL_DRAFT'
}
