import { decimal, toDecimalString } from '../oracle/decimal.ts'
import type {
  TimelineAction,
  TradingLabConfigSnapshot,
  TradingLabScenario,
} from './types.ts'

type CanonicalValue =
  | null
  | boolean
  | number
  | string
  | CanonicalValue[]
  | { [key: string]: CanonicalValue }

const TRANSIENT_KEYS = new Set([
  'transient',
  'uistate',
  'validationerrors',
  'selected',
  'expanded',
  'dragging',
])

const CREDENTIAL_KEYS = [
  'authorization',
  'cookie',
  'token',
  'password',
  'secret',
  'apikey',
  'accesskey',
  'authcode',
  'credential',
  'privatekey',
  'encryptionkey',
]

const NON_FINITE_NUMERIC_TEXT = new Set([
  'nan',
  '+nan',
  '-nan',
  'inf',
  '+inf',
  '-inf',
  'infinity',
  '+infinity',
  '-infinity',
])

const NUMERIC_EXPONENT = /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)[eE][+-]?\d+$/
const PLAIN_DECIMAL = /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)$/

const OPAQUE_NUMERIC_SUFFIXES = [
  'id',
  'ids',
  'code',
  'version',
  'hash',
  'fingerprint',
  'symbol',
  'symbols',
  'asset',
  'assets',
  'type',
  'mode',
  'state',
  'status',
  'name',
  'description',
  'url',
  'path',
  'time',
  'date',
  'timestamp',
  'key',
  'label',
  'seed',
]

const DECIMAL_SUFFIXES = [
  'price',
  'prices',
  'quantity',
  'quantities',
  'qty',
  'amount',
  'balance',
  'notional',
  'rate',
  'ratio',
  'size',
  'pnl',
  'fee',
  'margin',
  'cost',
  'proceeds',
  'equity',
  'value',
  'volume',
  'budget',
  'hold',
  'multiplier',
  'offset',
  'volatility',
  'spread',
  'basis',
]

const DECIMAL_KEYS = new Set([
  'bid',
  'ask',
  'last',
  'mark',
  'index',
  'maxfillquantitypertick',
])

const DECIMAL_OBJECT_KEYS = new Set(['initialbalances'])

export class ScenarioNormalizationError extends Error {
  constructor(message = 'Trading Lab 场景不能规范化') {
    super(message)
    this.name = 'ScenarioNormalizationError'
  }
}

function normalizedKey(key: string): string {
  return key.toLowerCase().replace(/[^a-z0-9]/g, '')
}

function containsControl(value: string): boolean {
  for (const character of value) {
    const codePoint = character.codePointAt(0)
    if (codePoint !== undefined && (codePoint < 0x20 || codePoint === 0x7f)) {
      return true
    }
  }
  return false
}

function containsUnpairedSurrogate(value: string): boolean {
  for (let index = 0; index < value.length; index += 1) {
    const codeUnit = value.charCodeAt(index)
    if (codeUnit >= 0xd800 && codeUnit <= 0xdbff) {
      const next = value.charCodeAt(index + 1)
      if (!(next >= 0xdc00 && next <= 0xdfff)) {
        return true
      }
      index += 1
    } else if (codeUnit >= 0xdc00 && codeUnit <= 0xdfff) {
      return true
    }
  }
  return false
}

function isCredentialKey(key: string): boolean {
  return CREDENTIAL_KEYS.some((candidate) => key.includes(candidate))
}

function isOpaqueNumericString(contextKey: string | null): boolean {
  return contextKey !== null
    && OPAQUE_NUMERIC_SUFFIXES.some((suffix) => contextKey.endsWith(suffix))
}

function isDecimalContext(contextKey: string | null): boolean {
  return contextKey !== null
    && (
      DECIMAL_KEYS.has(contextKey)
      || DECIMAL_OBJECT_KEYS.has(contextKey)
      || DECIMAL_SUFFIXES.some((suffix) => contextKey.endsWith(suffix))
    )
}

function normalizeDecimalText(value: string): string {
  let normalized = value.startsWith('+') ? value.slice(1) : value
  if (normalized.startsWith('-.')) {
    normalized = `-0${normalized.slice(1)}`
  } else if (normalized.startsWith('.')) {
    normalized = `0${normalized}`
  }
  if (normalized.endsWith('.')) {
    normalized = normalized.slice(0, -1)
  }
  return toDecimalString(decimal(normalized))
}

function canonicalizeValue(
  value: unknown,
  contextKey: string | null,
  depth: number,
  ancestors: WeakSet<object>,
): CanonicalValue {
  if (depth > 64) {
    throw new ScenarioNormalizationError('Trading Lab 场景嵌套层级超过限制')
  }
  if (value === null || typeof value === 'boolean') {
    return value
  }
  if (typeof value === 'number') {
    if (!Number.isSafeInteger(value) || isDecimalContext(contextKey)) {
      throw new ScenarioNormalizationError('数值字段必须使用安全整数或小数字符串')
    }
    return Object.is(value, -0) ? 0 : value
  }
  if (typeof value === 'string') {
    if (containsControl(value) || containsUnpairedSurrogate(value)) {
      throw new ScenarioNormalizationError('字符串不能包含控制字符或无效 Unicode')
    }
    const lowercase = value.toLowerCase()
    if (NON_FINITE_NUMERIC_TEXT.has(lowercase) || NUMERIC_EXPONENT.test(value)) {
      throw new ScenarioNormalizationError('数值字符串必须使用普通十进制格式')
    }
    if (PLAIN_DECIMAL.test(value) && !isOpaqueNumericString(contextKey)) {
      return normalizeDecimalText(value)
    }
    return value
  }
  if (typeof value !== 'object') {
    throw new ScenarioNormalizationError('场景只能包含 JSON 值')
  }
  if (ancestors.has(value)) {
    throw new ScenarioNormalizationError('场景不能包含循环引用')
  }

  ancestors.add(value)
  try {
    if (Array.isArray(value)) {
      const ownKeys = Reflect.ownKeys(value)
      if (ownKeys.some((key) => typeof key === 'symbol')) {
        throw new ScenarioNormalizationError('场景数组不能包含 Symbol 字段')
      }
      const lengthDescriptor = Object.getOwnPropertyDescriptor(value, 'length')
      if (
        lengthDescriptor === undefined
        || !('value' in lengthDescriptor)
        || typeof lengthDescriptor.value !== 'number'
      ) {
        throw new ScenarioNormalizationError('场景数组长度无效')
      }
      const indexKeys = (ownKeys as string[]).filter((key) => key !== 'length')
      if (indexKeys.length !== lengthDescriptor.value) {
        throw new ScenarioNormalizationError('场景数组不能包含空缺或额外字段')
      }
      const elements = indexKeys.map((key) => {
        if (!/^(?:0|[1-9]\d*)$/.test(key)) {
          throw new ScenarioNormalizationError('场景数组只能包含连续索引')
        }
        const index = Number(key)
        if (!Number.isSafeInteger(index) || index >= lengthDescriptor.value) {
          throw new ScenarioNormalizationError('场景数组索引无效')
        }
        const descriptor = Object.getOwnPropertyDescriptor(value, key)
        if (
          descriptor === undefined
          || !descriptor.enumerable
          || !('value' in descriptor)
        ) {
          throw new ScenarioNormalizationError('场景数组不能包含隐藏字段或访问器')
        }
        return { index, value: descriptor.value }
      }).sort((left, right) => left.index - right.index)

      const result: CanonicalValue[] = []
      elements.forEach((element, position) => {
        if (element.index !== position) {
          throw new ScenarioNormalizationError('场景数组不能包含空缺位置')
        }
        result.push(canonicalizeValue(
          element.value,
          contextKey,
          depth + 1,
          ancestors,
        ))
      })
      return result
    }

    const prototype = Object.getPrototypeOf(value)
    if (prototype !== Object.prototype && prototype !== null) {
      throw new ScenarioNormalizationError('场景对象必须是普通 JSON 对象')
    }
    const ownKeys = Reflect.ownKeys(value)
    if (ownKeys.some((key) => typeof key === 'symbol')) {
      throw new ScenarioNormalizationError('场景对象不能包含 Symbol 字段')
    }

    const result = Object.create(null) as { [key: string]: CanonicalValue }
    const source = value as Record<string, unknown>
    const keys = (ownKeys as string[])
      .sort((left, right) => left < right ? -1 : left > right ? 1 : 0)
    for (const key of keys) {
      if (
        key.trim().length === 0
        || containsControl(key)
        || containsUnpairedSurrogate(key)
      ) {
        throw new ScenarioNormalizationError('场景字段名不能为空、空白或包含无效字符')
      }
      const descriptor = Object.getOwnPropertyDescriptor(source, key)
      if (
        descriptor === undefined
        || !descriptor.enumerable
        || !('value' in descriptor)
      ) {
        throw new ScenarioNormalizationError('场景对象不能包含隐藏字段或访问器')
      }
      const keyContext = normalizedKey(key)
      if (TRANSIENT_KEYS.has(keyContext)) {
        continue
      }
      if (isCredentialKey(keyContext)) {
        throw new ScenarioNormalizationError('场景不能包含凭据字段')
      }
      const childContext = DECIMAL_OBJECT_KEYS.has(contextKey ?? '')
        ? contextKey
        : keyContext
      result[key] = canonicalizeValue(
        descriptor.value,
        childContext,
        depth + 1,
        ancestors,
      )
    }
    return result
  } finally {
    ancestors.delete(value)
  }
}

function serializeCanonical(value: CanonicalValue): string {
  if (value === null) {
    return 'null'
  }
  if (typeof value === 'boolean' || typeof value === 'number') {
    return String(value)
  }
  if (typeof value === 'string') {
    return JSON.stringify(value)
  }
  if (Array.isArray(value)) {
    return `[${value.map(serializeCanonical).join(',')}]`
  }

  const fields = Object.keys(value)
    .sort((left, right) => left < right ? -1 : left > right ? 1 : 0)
    .map((key) => `${JSON.stringify(key)}:${serializeCanonical(value[key])}`)
  return `{${fields.join(',')}}`
}

function projectAction(action: TimelineAction): Record<string, unknown> {
  return {
    id: action.id,
    sequence: action.sequence,
    type: action.type,
    symbol: action.symbol,
    productType: action.productType,
    trigger: action.trigger,
    parameters: action.parameters,
    ...(action.overrides === undefined ? {} : { overrides: action.overrides }),
    ...(action.expectedError === undefined
      ? {}
      : { expectedError: action.expectedError }),
  }
}

export function canonicalJson(value: unknown): string {
  return serializeCanonical(canonicalizeValue(value, null, 0, new WeakSet()))
}

export function normalizeScenario(scenario: TradingLabScenario): TradingLabScenario {
  const projected = {
    id: scenario.id,
    name: scenario.name,
    description: scenario.description,
    negativeMode: scenario.negativeMode,
    seed: scenario.seed,
    modelVersion: scenario.modelVersion,
    configSnapshot: scenario.configSnapshot,
    configSnapshotHash: scenario.configSnapshotHash,
    executionPolicy: scenario.executionPolicy,
    marketPath: scenario.marketPath,
    initialBalances: scenario.initialBalances,
    defaults: scenario.defaults,
    symbols: scenario.symbols.map((symbol) => ({
      symbol: symbol.symbol,
      productType: symbol.productType,
    })),
    timeline: scenario.timeline.map(projectAction),
  }

  return JSON.parse(canonicalJson(projected)) as TradingLabScenario
}

async function hashCanonicalValue(value: unknown): Promise<string> {
  if (globalThis.crypto?.subtle === undefined) {
    throw new ScenarioNormalizationError('当前浏览器不支持 Web Crypto SHA-256')
  }
  const bytes = new TextEncoder().encode(canonicalJson(value))
  const digest = await globalThis.crypto.subtle.digest('SHA-256', bytes)
  return Array.from(
    new Uint8Array(digest),
    (byte) => byte.toString(16).padStart(2, '0'),
  ).join('')
}

export async function hashScenarioConfig(
  config: TradingLabConfigSnapshot,
): Promise<string> {
  return hashCanonicalValue(config)
}

export async function scenarioFingerprint(
  scenario: TradingLabScenario,
): Promise<string> {
  return hashCanonicalValue(normalizeScenario(scenario))
}
