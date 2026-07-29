import type {
  TimelineAction,
  TimelineActionType,
  TimelineTrigger,
  TradingLabInstrumentConfig,
  TradingLabScenario,
} from '../model/types.ts'
import { validateScenario } from '../model/validation.ts'
import {
  add,
  compare,
  decimal,
  divide,
  subtract,
  toDecimalString,
} from './decimal.ts'
import { executePerpetualAction } from './perpetualOracle.ts'
import {
  availableMarginUsdt,
  calculateRiskSnapshot,
  projectPerpetualRisk,
  refreshPerpetualRisk,
  revaluePerpetualPositions,
} from './riskOracle.ts'
import { executeSpotMarketOrder } from './spotOracle.ts'
import type {
  CompiledAction,
  InstrumentTick,
  LocalActionSnapshot,
  LocalCalculationResult,
  LocalOracleIssue,
  MarketTick,
  MutableOracleState,
  OracleCalculationOutcome,
  OracleMarketInput,
  PerpetualInstrumentTick,
  SpotInstrumentTick,
} from './types.ts'

export type {
  LocalActionSnapshot,
  LocalCalculationResult,
  LocalOracleIssue,
  MarketTick,
  OracleCalculationOutcome,
  OracleMarketInput,
} from './types.ts'

const ZERO = decimal('0')
const SUPPORTED_ACTIONS = new Set<TimelineActionType>([
  'PLACE_ORDER',
  'SET_POSITION_MODE',
  'SET_MARGIN_MODE',
  'SET_LEVERAGE',
  'ADD_MARGIN',
  'REMOVE_MARGIN',
  'APPLY_FUNDING',
])

const ACTION_PARAMETER_KEYS: Readonly<Record<string, ReadonlySet<string>>> = {
  PLACE_ORDER: new Set([
    'side',
    'orderType',
    'quantity',
    'quantityUnit',
    'positionSide',
    'marginMode',
    'leverage',
    'reduceOnly',
  ]),
  SET_POSITION_MODE: new Set(['positionMode']),
  SET_MARGIN_MODE: new Set(['marginMode']),
  SET_LEVERAGE: new Set(['leverage']),
  ADD_MARGIN: new Set(['amount', 'positionSide']),
  REMOVE_MARGIN: new Set(['amount', 'positionSide']),
  APPLY_FUNDING: new Set(['positionSide']),
}

function identity(productType: unknown, symbol: unknown): string {
  return `${String(productType)}\u0000${String(symbol)}`
}

function issue(path: string, code: string, message: string): LocalOracleIssue {
  return { path, code, message }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function hasOnlyKeys(
  value: Record<string, unknown>,
  allowed: ReadonlySet<string>,
): boolean {
  return Object.keys(value).every((key) => allowed.has(key))
}

function triggerHasExactShape(
  value: unknown,
  depth = 0,
): value is TimelineTrigger {
  if (!isRecord(value) || depth > 16) {
    return false
  }
  switch (value.type) {
    case 'VIRTUAL_TIME':
      return hasOnlyKeys(value, new Set(['type', 'atSecond']))
    case 'PRICE':
      return hasOnlyKeys(
        value,
        new Set(['type', 'priceType', 'operator', 'value']),
      )
    case 'AFTER_ACTION':
      return hasOnlyKeys(
        value,
        new Set(['type', 'actionId', 'delaySeconds']),
      )
    case 'GROUP':
      return hasOnlyKeys(value, new Set(['type', 'operator', 'items']))
        && Array.isArray(value.items)
        && value.items.every((item) => triggerHasExactShape(item, depth + 1))
    default:
      return false
  }
}

function parseIsoInstant(value: unknown): number | null {
  if (typeof value !== 'string') {
    return null
  }
  const match = value.match(
    /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,3}))?Z$/,
  )
  if (match === null) {
    return null
  }
  const parsed = Date.parse(value)
  if (!Number.isFinite(parsed)) {
    return null
  }
  const fraction = (match[7] ?? '').padEnd(3, '0') || '000'
  const canonical = value.replace(/(?:\.\d{1,3})?Z$/, `.${fraction}Z`)
  if (new Date(parsed).toISOString() !== canonical) {
    return null
  }
  return parsed
}

function parsePositive(value: unknown): ReturnType<typeof decimal> | null {
  try {
    const parsed = decimal(value as string)
    return compare(parsed, ZERO) > 0 ? parsed : null
  } catch {
    return null
  }
}

function parseNonNegative(value: unknown): ReturnType<typeof decimal> | null {
  try {
    const parsed = decimal(value as string)
    return compare(parsed, ZERO) >= 0 ? parsed : null
  } catch {
    return null
  }
}

function parseDecimal(value: unknown): ReturnType<typeof decimal> | null {
  try {
    return decimal(value as string)
  } catch {
    return null
  }
}

function tickInstrument(
  tick: MarketTick,
  productType: unknown,
  symbol: unknown,
): InstrumentTick | undefined {
  return tick.instruments.find(
    (candidate) =>
      candidate.productType === productType
      && candidate.symbol === symbol,
  )
}

function priceAt(
  tick: MarketTick,
  action: TimelineAction,
  priceType: unknown,
): ReturnType<typeof decimal> | null | 'UNAVAILABLE' {
  const instrument = tickInstrument(tick, action.productType, action.symbol)
  if (instrument === undefined) {
    return null
  }
  if (
    instrument.productType === 'CRYPTO_SPOT'
    && (priceType === 'MARK' || priceType === 'INDEX')
  ) {
    return 'UNAVAILABLE'
  }
  const field = String(priceType).toLowerCase() as
    | 'bid'
    | 'ask'
    | 'last'
    | 'mark'
    | 'index'
  if (!(field in instrument)) {
    return null
  }
  return parsePositive(
    (instrument as unknown as Record<string, unknown>)[field],
  )
}

function triggerMatches(
  trigger: TimelineTrigger,
  action: TimelineAction,
  tick: MarketTick,
  resolved: ReadonlyMap<string, number>,
): boolean | 'UNAVAILABLE' {
  if (!isRecord(trigger)) {
    return false
  }
  switch (trigger.type) {
    case 'VIRTUAL_TIME':
      return Number.isSafeInteger(trigger.atSecond)
        && tick.sequence >= trigger.atSecond
    case 'PRICE': {
      const current = priceAt(tick, action, trigger.priceType)
      if (current === 'UNAVAILABLE') {
        return 'UNAVAILABLE'
      }
      const target = parsePositive(trigger.value)
      if (current === null || target === null) {
        return false
      }
      return trigger.operator === 'GTE'
        ? compare(current, target) >= 0
        : trigger.operator === 'LTE' && compare(current, target) <= 0
    }
    case 'AFTER_ACTION': {
      const prior = resolved.get(trigger.actionId)
      return prior !== undefined
        && Number.isSafeInteger(trigger.delaySeconds)
        && trigger.delaySeconds >= 0
        && tick.sequence >= prior + trigger.delaySeconds
    }
    case 'GROUP': {
      if (
        (trigger.operator !== 'ALL' && trigger.operator !== 'ANY')
        || !Array.isArray(trigger.items)
        || trigger.items.length === 0
      ) {
        return false
      }
      const values = trigger.items.map((child) =>
        triggerMatches(child, action, tick, resolved))
      if (values.includes('UNAVAILABLE')) {
        return 'UNAVAILABLE'
      }
      return trigger.operator === 'ALL'
        ? values.every((value) => value === true)
        : values.some((value) => value === true)
    }
    default:
      return false
  }
}

function requiresSameTickFunding(
  trigger: TimelineTrigger,
  action: TimelineAction,
  tick: MarketTick,
  resolved: ReadonlyMap<string, number>,
  resolvedTypes: ReadonlyMap<string, TimelineActionType>,
): boolean {
  if (trigger.type === 'AFTER_ACTION') {
    return resolved.get(trigger.actionId) === tick.sequence
      && resolvedTypes.get(trigger.actionId) === 'APPLY_FUNDING'
  }
  if (trigger.type !== 'GROUP') {
    return false
  }
  const matchingChildren = trigger.items.filter((child) =>
    triggerMatches(child, action, tick, resolved) === true)
  if (trigger.operator === 'ALL') {
    return matchingChildren.some((child) =>
      requiresSameTickFunding(
        child,
        action,
        tick,
        resolved,
        resolvedTypes,
      ))
  }
  return matchingChildren.length > 0
    && matchingChildren.every((child) =>
      requiresSameTickFunding(
        child,
        action,
        tick,
        resolved,
        resolvedTypes,
      ))
}

function validateInstrumentTick(
  tick: InstrumentTick,
  path: string,
  issues: LocalOracleIssue[],
): void {
  const bid = parsePositive(tick.bid)
  const ask = parsePositive(tick.ask)
  const last = parsePositive(tick.last)
  if (bid === null || ask === null || last === null) {
    issues.push(issue(
      path,
      'ORACLE_TICK_PRICE_INVALID',
      'Tick 的 bid、ask、last 必须是大于零的普通十进制字符串',
    ))
    return
  }
  if (compare(bid, ask) > 0) {
    issues.push(issue(
      path,
      'ORACLE_TICK_SPREAD_INVALID',
      'Tick 的 bid 不能大于 ask',
    ))
  }
  if (
    tick.productType === 'LINEAR_PERP'
    && (parsePositive(tick.mark) === null || parsePositive(tick.index) === null)
  ) {
    issues.push(issue(
      path,
      'ORACLE_TICK_PERPETUAL_PRICE_INVALID',
      '永续 Tick 必须提供大于零的 mark 和 index',
    ))
  }
}

function compileScenario(
  scenario: TradingLabScenario,
  market: OracleMarketInput,
): Readonly<{
  actions: readonly CompiledAction[]
  instruments: ReadonlyMap<string, TradingLabInstrumentConfig>
  ticks: ReadonlyMap<number, MarketTick>
  issues: readonly LocalOracleIssue[]
}> {
  const issues: LocalOracleIssue[] = []
  const instruments = new Map<string, TradingLabInstrumentConfig>()
  const marketTicks = Array.isArray(market?.ticks) ? market.ticks : []

  if (
    !isRecord(scenario)
    || !isRecord(scenario.configSnapshot)
    || !Array.isArray(scenario.configSnapshot.instruments)
  ) {
    return {
      actions: [],
      instruments,
      ticks: new Map(),
      issues: [issue('scenario', 'ORACLE_SCENARIO_INVALID', '场景结构无效')],
    }
  }
  if (
    !isRecord(market)
    || !hasOnlyKeys(market, new Set(['virtualStart', 'ticks']))
  ) {
    issues.push(issue(
      'market',
      'ORACLE_MARKET_FIELDS_UNSUPPORTED',
      'Oracle 市场输入包含未声明字段',
    ))
  }
  if (!hasOnlyKeys(
    scenario,
    new Set([
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
    ]),
  )) {
    issues.push(issue(
      'scenario',
      'ORACLE_SCENARIO_FIELDS_UNSUPPORTED',
      'Oracle 场景包含未声明的顶层字段',
    ))
  }
  if (!hasOnlyKeys(
    scenario.configSnapshot,
    new Set([
      'modelVersion',
      'symbolConfigVersion',
      'codeVersion',
      'executionPolicy',
      'instruments',
    ]),
  )) {
    issues.push(issue(
      'scenario.configSnapshot',
      'ORACLE_CONFIG_FIELDS_UNSUPPORTED',
      'Oracle 配置快照包含未声明字段',
    ))
  }
  if (
    !isRecord(scenario.configSnapshot.executionPolicy)
    || !hasOnlyKeys(
      scenario.configSnapshot.executionPolicy,
      new Set([
        'matchingMode',
        'makerFeeRate',
        'takerFeeRate',
        'liquidationFeeRate',
        'slippageRate',
        'maxFillQuantityPerTick',
      ]),
    )
  ) {
    issues.push(issue(
      'scenario.configSnapshot.executionPolicy',
      'ORACLE_POLICY_FIELDS_UNSUPPORTED',
      'Oracle 执行策略包含未声明字段',
    ))
  }
  if (
    !isRecord(scenario.executionPolicy)
    || !hasOnlyKeys(
      scenario.executionPolicy,
      new Set([
        'matchingMode',
        'makerFeeRate',
        'takerFeeRate',
        'liquidationFeeRate',
        'slippageRate',
        'maxFillQuantityPerTick',
      ]),
    )
  ) {
    issues.push(issue(
      'scenario.executionPolicy',
      'ORACLE_POLICY_FIELDS_UNSUPPORTED',
      'Oracle 场景执行策略包含未声明字段',
    ))
  }
  if (
    !isRecord(scenario.defaults)
    || !hasOnlyKeys(
      scenario.defaults,
      new Set(['positionMode', 'marginMode', 'leverage']),
    )
  ) {
    issues.push(issue(
      'scenario.defaults',
      'ORACLE_DEFAULT_FIELDS_UNSUPPORTED',
      'Oracle 默认设置包含未声明字段',
    ))
  }

  scenario.configSnapshot.instruments.forEach((instrument, index) => {
    if (
      !isRecord(instrument)
      || (instrument.productType !== 'CRYPTO_SPOT'
        && instrument.productType !== 'LINEAR_PERP')
      || typeof instrument.symbol !== 'string'
      || instrument.symbol.length === 0
    ) {
      issues.push(issue(
        `scenario.configSnapshot.instruments[${index}]`,
        'ORACLE_INSTRUMENT_INVALID',
        'Oracle 品种配置无效',
      ))
      return
    }
    if (!hasOnlyKeys(
      instrument,
      new Set([
        'symbol',
        'productType',
        'baseAsset',
        'quoteAsset',
        'tickSize',
        'stepSize',
        'pricePrecision',
        'quantityPrecision',
        'minQty',
        'maxQty',
        'minNotional',
        'maxNotional',
        'initialMarginRate',
        'maintenanceMarginRate',
        'liquidationFeeRate',
        'fixedFundingRate',
        'fixedFundingIntervalMinutes',
        'markPriceSource',
        'contractSize',
        'maxLeverage',
        'defaultLeverage',
        'marginAsset',
        'settlementAsset',
        'riskTier',
      ]),
    )) {
      issues.push(issue(
        `scenario.configSnapshot.instruments[${index}]`,
        'ORACLE_INSTRUMENT_FIELDS_UNSUPPORTED',
        'Oracle 品种配置包含未声明字段',
      ))
      return
    }
    const key = identity(instrument.productType, instrument.symbol)
    if (instruments.has(key)) {
      issues.push(issue(
        `scenario.configSnapshot.instruments[${index}]`,
        'ORACLE_INSTRUMENT_DUPLICATE',
        'Oracle 品种身份不能重复',
      ))
      return
    }
    if (
      typeof instrument.baseAsset !== 'string'
      || instrument.baseAsset.trim().length === 0
      || typeof instrument.quoteAsset !== 'string'
      || instrument.quoteAsset.trim().length === 0
    ) {
      issues.push(issue(
        `scenario.configSnapshot.instruments[${index}]`,
        'ORACLE_ASSET_IDENTITY_REQUIRED',
        'Oracle 必须使用显式基础资产和计价资产',
      ))
      return
    }
    if (
      (instrument.productType === 'CRYPTO_SPOT'
        && instrument.quoteAsset !== 'USDT')
      || (instrument.productType === 'LINEAR_PERP'
        && (
          instrument.quoteAsset !== 'USDT'
          || instrument.marginAsset !== 'USDT'
          || instrument.settlementAsset !== 'USDT'
        ))
    ) {
      issues.push(issue(
        `scenario.configSnapshot.instruments[${index}]`,
        'ORACLE_ASSET_MODEL_UNSUPPORTED',
        'Oracle 当前只支持显式 USDT 计价和结算模型',
      ))
      return
    }
    if (
      parsePositive(instrument.tickSize) === null
      || parsePositive(instrument.stepSize) === null
      || parsePositive(instrument.contractSize) === null
    ) {
      issues.push(issue(
        `scenario.configSnapshot.instruments[${index}]`,
        'ORACLE_INSTRUMENT_DECIMAL_INVALID',
        'Oracle 品种步长和合约大小必须有效',
      ))
      return
    }
    instruments.set(key, instrument as TradingLabInstrumentConfig)
  })

  const selected = new Set<string>()
  if (!Array.isArray(scenario.symbols) || scenario.symbols.length === 0) {
    issues.push(issue(
      'scenario.symbols',
      'ORACLE_SYMBOLS_REQUIRED',
      'Oracle 至少需要一个显式品种',
    ))
  } else {
    scenario.symbols.forEach((symbol, index) => {
      if (
        !isRecord(symbol)
        || !hasOnlyKeys(symbol, new Set(['symbol', 'productType']))
      ) {
        issues.push(issue(
          `scenario.symbols[${index}]`,
          'ORACLE_SYMBOL_FIELDS_UNSUPPORTED',
          'Oracle 场景品种包含未声明字段',
        ))
        return
      }
      const key = identity(symbol?.productType, symbol?.symbol)
      if (!instruments.has(key)) {
        issues.push(issue(
          `scenario.symbols[${index}]`,
          'ORACLE_SYMBOL_UNSUPPORTED',
          '场景品种不在显式配置快照中',
        ))
      } else if (selected.has(key)) {
        issues.push(issue(
          `scenario.symbols[${index}]`,
          'ORACLE_SYMBOL_DUPLICATE',
          '场景品种身份不能重复',
        ))
      }
      selected.add(key)
    })
  }

  if (scenario.executionPolicy?.matchingMode !== 'SIMPLE') {
    issues.push(issue(
      'scenario.executionPolicy.matchingMode',
      'ORACLE_MATCHING_MODE_UNSUPPORTED',
      'Task 4 Oracle 当前只支持 SIMPLE 撮合',
    ))
  }

  const start = parseIsoInstant(market?.virtualStart)
  if (start === null) {
    issues.push(issue(
      'market.virtualStart',
      'ORACLE_VIRTUAL_START_INVALID',
      '虚拟开始时间必须是规范 UTC ISO instant',
    ))
  }

  const ticksBySequence = new Map<number, MarketTick>()
  if (marketTicks.length === 0) {
    issues.push(issue('market.ticks', 'ORACLE_TICKS_REQUIRED', 'Oracle 必须提供显式 Tick'))
  } else {
    marketTicks.forEach((tick, index) => {
      const path = `market.ticks[${index}]`
      if (
        !isRecord(tick)
        || !Number.isSafeInteger(tick.sequence)
        || tick.sequence !== index + 1
      ) {
        issues.push(issue(
          `${path}.sequence`,
          'ORACLE_TICK_SEQUENCE_INVALID',
          'Tick sequence 必须从 1 连续递增',
        ))
        return
      }
      if (!hasOnlyKeys(
        tick,
        new Set(['sequence', 'virtualTime', 'instruments', 'fundingRates']),
      )) {
        issues.push(issue(
          path,
          'ORACLE_TICK_FIELDS_UNSUPPORTED',
          'Tick 包含 Oracle 未声明的字段',
        ))
      }
      if (
        start !== null
        && parseIsoInstant(tick.virtualTime) !== start + tick.sequence * 1000
      ) {
        issues.push(issue(
          `${path}.virtualTime`,
          'ORACLE_TICK_TIME_INVALID',
          'Tick virtualTime 必须等于 virtualStart 加 sequence 秒',
        ))
      }
      if (!Array.isArray(tick.instruments)) {
        issues.push(issue(
          `${path}.instruments`,
          'ORACLE_TICK_INSTRUMENTS_INVALID',
          'Tick instruments 必须是数组',
        ))
        return
      }
      const present = new Set<string>()
      tick.instruments.forEach((row, rowIndex) => {
        if (
          !isRecord(row)
          || (row.productType !== 'CRYPTO_SPOT'
            && row.productType !== 'LINEAR_PERP')
          || typeof row.symbol !== 'string'
        ) {
          issues.push(issue(
            `${path}.instruments[${rowIndex}]`,
            'ORACLE_TICK_INSTRUMENT_INVALID',
            'Tick 品种行无效',
          ))
          return
        }
        const key = identity(row.productType, row.symbol)
        const allowedTickFields = row.productType === 'CRYPTO_SPOT'
          ? new Set(['productType', 'symbol', 'bid', 'ask', 'last'])
          : new Set([
              'productType',
              'symbol',
              'bid',
              'ask',
              'last',
              'mark',
              'index',
            ])
        if (!hasOnlyKeys(row, allowedTickFields)) {
          issues.push(issue(
            `${path}.instruments[${rowIndex}]`,
            'ORACLE_TICK_INSTRUMENT_FIELDS_UNSUPPORTED',
            'Tick 品种行包含 Oracle 未声明的字段',
          ))
        }
        if (present.has(key)) {
          issues.push(issue(
            `${path}.instruments[${rowIndex}]`,
            'ORACLE_TICK_INSTRUMENT_DUPLICATE',
            '每个 Tick 的品种身份只能出现一次',
          ))
          return
        }
        present.add(key)
        if (!selected.has(key)) {
          issues.push(issue(
            `${path}.instruments[${rowIndex}]`,
            'ORACLE_TICK_INSTRUMENT_UNEXPECTED',
            'Tick 不能包含未选择的品种',
          ))
        }
        validateInstrumentTick(
          row as InstrumentTick,
          `${path}.instruments[${rowIndex}]`,
          issues,
        )
      })
      for (const key of selected) {
        if (!present.has(key)) {
          issues.push(issue(
            `${path}.instruments`,
            'ORACLE_TICK_INSTRUMENT_MISSING',
            '每个 Tick 必须完整提供所有已选择品种',
          ))
        }
      }
      if (!Array.isArray(tick.fundingRates)) {
        issues.push(issue(
          `${path}.fundingRates`,
          'ORACLE_FUNDING_RATES_INVALID',
          'Tick fundingRates 必须是数组',
        ))
      } else {
        const fundingSymbols = new Set<string>()
        tick.fundingRates.forEach((funding, fundingIndex) => {
          if (
            !isRecord(funding)
            || typeof funding.symbol !== 'string'
            || fundingSymbols.has(funding.symbol)
            || parseDecimal(funding.rate) === null
            || !hasOnlyKeys(funding, new Set(['symbol', 'rate']))
          ) {
            issues.push(issue(
              `${path}.fundingRates[${fundingIndex}]`,
              'ORACLE_FUNDING_RATE_INVALID',
              '资金费率必须是每品种唯一的普通十进制字符串',
            ))
          } else {
            fundingSymbols.add(funding.symbol)
          }
        })
      }
      ticksBySequence.set(tick.sequence, tick as MarketTick)
    })
  }

  const compiled: CompiledAction[] = []
  const resolved = new Map<string, number>()
  const resolvedTypes = new Map<string, TimelineActionType>()
  let previousResolvedSequence: number | undefined
  const triggerTicks = Array.from(ticksBySequence.values())
    .sort((left, right) => left.sequence - right.sequence)
  if (!Array.isArray(scenario.timeline) || scenario.timeline.length === 0) {
    issues.push(issue(
      'scenario.timeline',
      'ORACLE_TIMELINE_REQUIRED',
      'Oracle 至少需要一个动作',
    ))
  } else {
    scenario.timeline.forEach((action, index) => {
      const path = `scenario.timeline[${index}]`
      if (!isRecord(action) || typeof action.id !== 'string') {
        issues.push(issue(path, 'ORACLE_ACTION_INVALID', 'Oracle 动作结构无效'))
        return
      }
      if (action.id.startsWith('system:')) {
        issues.push(issue(
          `${path}.id`,
          'ORACLE_ACTION_ID_RESERVED',
          'system: action id 前缀保留给 Oracle 合成证据',
        ))
        return
      }
      if (!hasOnlyKeys(
        action,
        new Set([
          'id',
          'sequence',
          'type',
          'symbol',
          'productType',
          'trigger',
          'parameters',
          'overrides',
          'expectedError',
        ]),
      )) {
        issues.push(issue(
          path,
          'ORACLE_ACTION_FIELDS_UNSUPPORTED',
          'Oracle 动作包含未声明的顶层字段',
        ))
        return
      }
      if (!triggerHasExactShape(action.trigger)) {
        issues.push(issue(
          `${path}.trigger`,
          'ORACLE_TRIGGER_FIELDS_UNSUPPORTED',
          'Oracle 触发条件包含未声明字段或结构无效',
        ))
        return
      }
      if (
        action.expectedError !== undefined
        && (
          !isRecord(action.expectedError)
          || !hasOnlyKeys(action.expectedError, new Set(['status', 'code']))
        )
      ) {
        issues.push(issue(
          `${path}.expectedError`,
          'ORACLE_EXPECTED_ERROR_FIELDS_UNSUPPORTED',
          'Oracle expectedError 包含未声明字段',
        ))
        return
      }
      if (!SUPPORTED_ACTIONS.has(action.type as TimelineActionType)) {
        issues.push(issue(
          `${path}.type`,
          'ORACLE_ACTION_UNSUPPORTED',
          'Oracle 尚未实现该动作',
        ))
        return
      }
      const key = identity(action.productType, action.symbol)
      if (!selected.has(key) || !instruments.has(key)) {
        issues.push(issue(
          `${path}.symbol`,
          'ORACLE_ACTION_INSTRUMENT_INVALID',
          'Oracle 动作必须绑定已选择的显式产品品种',
        ))
        return
      }
      if (!isRecord(action.parameters)) {
        issues.push(issue(
          `${path}.parameters`,
          'ORACLE_ACTION_PARAMETERS_INVALID',
          'Oracle 动作参数必须是对象',
        ))
        return
      }
      const allowed = ACTION_PARAMETER_KEYS[action.type]
      if (
        allowed === undefined
        || Object.keys(action.parameters).some((field) => !allowed.has(field))
        || action.overrides !== undefined
      ) {
        issues.push(issue(
          `${path}.parameters`,
          'ORACLE_ACTION_PARAMETERS_UNSUPPORTED',
          'Oracle 拒绝未知参数或 override',
        ))
        return
      }

      let unavailable = false
      let resolvedSequence: number | undefined
      for (const tick of triggerTicks) {
        const matches = triggerMatches(
          action.trigger as TimelineTrigger,
          action as TimelineAction,
          tick,
          resolved,
        )
        if (matches === 'UNAVAILABLE') {
          unavailable = true
          break
        }
        if (matches) {
          resolvedSequence = tick.sequence
          break
        }
      }
      if (unavailable) {
        issues.push(issue(
          `${path}.trigger`,
          'ORACLE_PRICE_TYPE_UNAVAILABLE',
          '所选品种没有该价格类型',
        ))
      } else if (resolvedSequence === undefined) {
        issues.push(issue(
          `${path}.trigger`,
          'ORACLE_TRIGGER_UNRESOLVED',
          '动作触发条件无法在显式 Tick 中解析',
        ))
      } else if (
        action.type !== 'APPLY_FUNDING'
        && requiresSameTickFunding(
          action.trigger as TimelineTrigger,
          action as TimelineAction,
          ticksBySequence.get(resolvedSequence) as MarketTick,
          resolved,
          resolvedTypes,
        )
      ) {
        issues.push(issue(
          `${path}.trigger`,
          'ORACLE_ACTION_PHASE_DEPENDENCY_INVALID',
          '普通动作不能依赖同一 Tick 的 funding 完成',
        ))
      } else if (
        previousResolvedSequence !== undefined
        && resolvedSequence < previousResolvedSequence
      ) {
        issues.push(issue(
          `${path}.trigger`,
          'ORACLE_ACTION_TICK_ORDER_INVALID',
          '动作解析后的 Tick 不能早于前一已编译动作',
        ))
      } else {
        resolved.set(action.id, resolvedSequence)
        resolvedTypes.set(
          action.id,
          action.type as TimelineActionType,
        )
        previousResolvedSequence = resolvedSequence
        compiled.push({
          action: action as TimelineAction,
          tickSequence: resolvedSequence,
        })
      }
    })
  }

  return {
    actions: compiled,
    instruments,
    ticks: ticksBySequence,
    issues,
  }
}

function createState(
  scenario: TradingLabScenario,
  instruments: ReadonlyMap<string, TradingLabInstrumentConfig>,
): MutableOracleState {
  const wallets = new Map<string, ReturnType<typeof decimal>>()
  for (const [asset, value] of Object.entries(scenario.initialBalances)) {
    const balance = parseNonNegative(value)
    if (balance === null) {
      throw new Error('invalid initial balance')
    }
    wallets.set(asset, balance)
  }
  const settings = new Map()
  for (const symbol of scenario.symbols) {
    const key = identity(symbol.productType, symbol.symbol)
    if (instruments.has(key)) {
      settings.set(key, {
        positionMode: scenario.defaults.positionMode,
        marginMode: scenario.defaults.marginMode,
        leverage: scenario.defaults.leverage,
      })
    }
  }
  return {
    wallets,
    spotPositions: new Map(),
    perpetualPositions: new Map(),
    settings,
    orders: [],
    trades: [],
    ledger: [],
    warnings: [],
    assumptions: new Set(),
    liquidationTriggered: false,
  }
}

function spotPositionResults(state: MutableOracleState) {
  return Array.from(state.spotPositions.entries())
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([, position]) => {
      const isOpen = compare(position.quantity, ZERO) > 0
      const averageCost = isOpen
        ? divide(position.grossQuoteCost, position.quantity, 18, 'DOWN')
        : null
      const breakEvenPrice = isOpen
        ? divide(
            add(position.grossQuoteCost, position.feeCostUsdt),
            position.quantity,
            18,
            'DOWN',
          )
        : null
      return {
        symbol: position.instrument.symbol,
        baseAsset: position.instrument.baseAsset,
        quoteAsset: position.instrument.quoteAsset,
        status: isOpen ? 'OPEN' as const : 'CLOSED' as const,
        quantity: toDecimalString(position.quantity),
        averageCost: averageCost === null ? null : toDecimalString(averageCost),
        grossQuoteCost: toDecimalString(position.grossQuoteCost),
        feeCostUsdt: toDecimalString(position.feeCostUsdt),
        netInvestedUsdt: toDecimalString(position.netInvestedUsdt),
        breakEvenPrice: breakEvenPrice === null
          ? null
          : toDecimalString(breakEvenPrice),
        realizedGrossPnl: toDecimalString(position.realizedGrossPnl),
        realizedNetPnl: toDecimalString(position.realizedNetPnl),
      }
    })
}

function perpetualPositionResults(state: MutableOracleState) {
  return Array.from(state.perpetualPositions.entries())
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([, position]) => ({
      symbol: position.instrument.symbol,
      positionSide: position.positionSide,
      direction: position.direction,
      marginMode: position.marginMode,
      status: position.status,
      quantity: toDecimalString(position.quantity),
      entryPrice: toDecimalString(position.entryPrice),
      markPrice: toDecimalString(position.markPrice),
      leverage: position.leverage,
      initialMargin: toDecimalString(position.initialMargin),
      isolatedMargin: toDecimalString(position.isolatedMargin),
      maintenanceMargin: toDecimalString(position.maintenanceMargin),
      unrealizedGrossPnl: toDecimalString(position.unrealizedGrossPnl),
      realizedGrossPnl: toDecimalString(position.realizedGrossPnl),
      tradingFeeUsdt: toDecimalString(position.tradingFeeUsdt),
      fundingPnlUsdt: toDecimalString(position.fundingPnlUsdt),
      liquidationFeeUsdt: toDecimalString(position.liquidationFeeUsdt),
      realizedNetPnl: toDecimalString(position.realizedNetPnl),
      estimatedLiquidationPrice: position.estimatedLiquidationPrice === null
        ? null
        : toDecimalString(position.estimatedLiquidationPrice),
    }))
}

function wallets(state: MutableOracleState) {
  let lockedUsdt = ZERO
  for (const position of state.perpetualPositions.values()) {
    if (compare(position.quantity, ZERO) <= 0) {
      continue
    }
    lockedUsdt = add(
      lockedUsdt,
      position.marginMode === 'ISOLATED'
        ? position.isolatedMargin
        : position.initialMargin,
    )
  }
  return Array.from(state.wallets.entries())
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([asset, balance]) => {
      const locked = asset === 'USDT' ? lockedUsdt : ZERO
      const rawAvailable = asset === 'USDT'
        ? availableMarginUsdt(state)
        : balance
      const available = compare(rawAvailable, ZERO) >= 0
        ? rawAvailable
        : ZERO
      return {
        asset,
        available: toDecimalString(available),
        locked: toDecimalString(locked),
        total: toDecimalString(balance),
      }
    })
}

function deepFreeze<T>(value: T): T {
  if (value !== null && typeof value === 'object' && !Object.isFrozen(value)) {
    for (const child of Object.values(value)) {
      deepFreeze(child)
    }
    Object.freeze(value)
  }
  return value
}

function snapshot(
  state: MutableOracleState,
  actionId: string,
  tick: MarketTick,
): LocalActionSnapshot {
  const risk = calculateRiskSnapshot(state)
  return deepFreeze({
    actionId,
    tickSequence: tick.sequence,
    virtualTime: tick.virtualTime,
    orders: state.orders.map((order) => ({ ...order })),
    trades: state.trades.map((trade) => ({ ...trade })),
    spotPositions: spotPositionResults(state),
    perpetualPositions: perpetualPositionResults(state),
    wallets: wallets(state),
    accountSummary: { ...risk.accountSummary },
    ledgerProjection: state.ledger.map((entry) => ({ ...entry })),
    risk: { ...risk.risk },
    warnings: state.warnings.map((warning) => ({ ...warning })),
  })
}

function blocked(
  issues: readonly LocalOracleIssue[],
  runnerIssues: ReturnType<typeof validateScenario>,
): OracleCalculationOutcome {
  return deepFreeze({
    status: 'BLOCKED',
    issues: issues.map((candidate) => ({ ...candidate })),
    runnerIssues: runnerIssues.map((candidate) => ({ ...candidate })),
  })
}

export function calculateScenario(input: Readonly<{
  scenario: TradingLabScenario
  market: OracleMarketInput
}>): OracleCalculationOutcome {
  const runnerIssues = validateScenario(input.scenario)
  const program = compileScenario(input.scenario, input.market)
  const localBlockingRunnerIssues = runnerIssues
    .filter((candidate) =>
      candidate.severity === 'ERROR'
      && candidate.code !== 'ACTION_REQUIRES_RUNNER_SUPPORT')
    .map((candidate) => issue(
      candidate.path,
      'ORACLE_SCENARIO_VALIDATION_FAILED',
      candidate.message,
    ))
  if (program.issues.length > 0 || localBlockingRunnerIssues.length > 0) {
    return blocked(
      [...program.issues, ...localBlockingRunnerIssues],
      runnerIssues,
    )
  }

  let state: MutableOracleState
  try {
    state = createState(input.scenario, program.instruments)
  } catch {
    return blocked([
      issue(
        'scenario.initialBalances',
        'ORACLE_INITIAL_BALANCE_INVALID',
        'Oracle 初始余额必须是非负普通十进制字符串',
      ),
    ], runnerIssues)
  }

  const snapshots: LocalActionSnapshot[] = []
  try {
    const actionsByTick = new Map<number, CompiledAction[]>()
    for (const compiled of program.actions) {
      const current = actionsByTick.get(compiled.tickSequence) ?? []
      current.push(compiled)
      actionsByTick.set(compiled.tickSequence, current)
    }

    for (const tick of program.ticks.values()) {
      const tickActions = actionsByTick.get(tick.sequence) ?? []
      const phasedActions = [
        ...tickActions.filter((compiled) =>
          compiled.action.type !== 'APPLY_FUNDING'),
        ...tickActions.filter((compiled) =>
          compiled.action.type === 'APPLY_FUNDING'),
      ]
      if (phasedActions.length === 0) {
        const systemActionId = `system:risk:${tick.sequence}`
        refreshPerpetualRisk(
          state,
          tick,
          input.scenario.executionPolicy,
          systemActionId,
        )
        if (state.liquidationTriggered) {
          snapshots.push(snapshot(state, systemActionId, tick))
        }
        continue
      }

      for (const [index, compiled] of phasedActions.entries()) {
        const action = compiled.action
        const instrument = program.instruments.get(
          identity(action.productType, action.symbol),
        )
        if (instrument === undefined) {
          throw new Error('compiled program lost authority')
        }
        const marketTick = tickInstrument(
          tick,
          action.productType,
          action.symbol,
        )
        if (marketTick === undefined) {
          throw new Error('compiled tick missing instrument')
        }
        revaluePerpetualPositions(state, tick)

        if (
          action.type === 'PLACE_ORDER'
          && marketTick.productType === 'CRYPTO_SPOT'
        ) {
          executeSpotMarketOrder(
            state,
            action,
            marketTick as SpotInstrumentTick,
            instrument,
            input.scenario.executionPolicy,
          )
        } else if (marketTick.productType === 'LINEAR_PERP') {
          executePerpetualAction(
            state,
            action,
            marketTick as PerpetualInstrumentTick,
            tick.fundingRates,
            instrument,
            input.scenario.executionPolicy,
          )
        } else {
          throw new Error('action does not match product')
        }

        if (index === phasedActions.length - 1) {
          refreshPerpetualRisk(
            state,
            tick,
            input.scenario.executionPolicy,
            action.id,
          )
        } else {
          projectPerpetualRisk(
            state,
            tick,
            input.scenario.executionPolicy,
          )
        }
        snapshots.push(snapshot(state, action.id, tick))
      }
    }
  } catch (error) {
    if (
      isRecord(error)
      && isRecord(error.issue)
      && typeof error.issue.code === 'string'
    ) {
      return blocked([error.issue as LocalOracleIssue], runnerIssues)
    }
    return blocked([
      issue(
        'scenario.timeline',
        'ORACLE_CALCULATION_FAILED',
        'Oracle 计算失败且未返回部分结果',
      ),
    ], runnerIssues)
  }

  const result: LocalCalculationResult = {
    modelVersion: input.scenario.modelVersion,
    configSnapshotHash: input.scenario.configSnapshotHash,
    assumptions: Array.from(state.assumptions).sort(),
    snapshots,
  }
  return deepFreeze({
    status: 'CALCULATED',
    result,
    runnerIssues: runnerIssues.map((candidate) => ({ ...candidate })),
  })
}
