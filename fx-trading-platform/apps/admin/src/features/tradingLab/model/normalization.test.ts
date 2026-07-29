import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

import { createDefaultScenario } from './defaults.ts'
import {
  canonicalJson,
  hashScenarioConfig,
  normalizeScenario,
  scenarioFingerprint,
} from './normalization.ts'
import type {
  MarketPathDefinition,
  TradingLabConfigSnapshot,
  TradingLabScenario,
} from './types.ts'

function config(): TradingLabConfigSnapshot {
  return {
    modelVersion: 'model-1.0',
    symbolConfigVersion: '001',
    codeVersion: 'build-1.0',
    executionPolicy: {
      matchingMode: 'SIMPLE',
      makerFeeRate: '0.0010',
      takerFeeRate: '0.0020',
      liquidationFeeRate: '0.0030',
      slippageRate: '0.0000',
      maxFillQuantityPerTick: '100.000',
    },
    instruments: [{
      symbol: 'BTCUSDT',
      productType: 'CRYPTO_SPOT',
      baseAsset: 'BTC',
      quoteAsset: 'USDT',
      tickSize: '0.0100',
      stepSize: '0.0010',
      pricePrecision: 2,
      quantityPrecision: 3,
      minQty: '0.0010',
      maxQty: '100.000',
      minNotional: '10.00',
      maxNotional: '1000000.00',
      initialMarginRate: '0.01',
      maintenanceMarginRate: '0.0050',
      liquidationFeeRate: '0.0020',
      fixedFundingRate: '0.000100',
      fixedFundingIntervalMinutes: 480,
      markPriceSource: 'quote_mid',
      contractSize: '1.0',
      maxLeverage: 1,
      defaultLeverage: 1,
      marginAsset: 'USDT',
      settlementAsset: 'USDT',
      riskTier: 'TIER_1',
    }],
  }
}

function marketPath(): MarketPathDefinition {
  return {
    virtualStart: '2026-01-01T00:00:00.000Z',
    realistic: false,
    instruments: [{
      mode: 'SIMPLE',
      productType: 'CRYPTO_SPOT',
      symbol: 'BTCUSDT',
      seed: 'path-seed-001',
      last: {
        start: '60000.00',
        segments: [{
          target: '60100.00',
          durationSeconds: 300,
          offsetRangeSteps: 0,
          volatilitySteps: 0,
          maxStepPerSecond: 100,
        }],
      },
      spreadSteps: 2,
      indexOffsetSteps: 0,
      basisSteps: 0,
    }],
  }
}

function scenario(): TradingLabScenario {
  const snapshot = config()
  return {
    id: 'scenario-001',
    name: '规范化场景',
    description: '保持数组顺序并规范化金额',
    negativeMode: false,
    seed: '001',
    modelVersion: 'model-1.0',
    configSnapshot: snapshot,
    configSnapshotHash: '0'.repeat(64),
    executionPolicy: { ...snapshot.executionPolicy },
    marketPath: marketPath(),
    initialBalances: { USDT: '100000.00' },
    defaults: {
      positionMode: 'ONE_WAY',
      marginMode: 'CROSS',
      leverage: 1,
    },
    symbols: [{
      symbol: 'BTCUSDT',
      productType: 'CRYPTO_SPOT',
    }],
    timeline: [
      {
        id: 'action-002',
        sequence: 2,
        type: 'PLACE_ORDER',
        symbol: 'BTCUSDT',
        productType: 'CRYPTO_SPOT',
        trigger: { type: 'VIRTUAL_TIME', atSecond: 2 },
        parameters: {
          side: 'BUY',
          orderType: 'LIMIT',
          quantity: '0.0100',
          price: '60000.1200',
        },
      },
      {
        id: 'action-001',
        sequence: 1,
        type: 'CANCEL_ALL',
        symbol: 'BTCUSDT',
        productType: 'CRYPTO_SPOT',
        trigger: { type: 'VIRTUAL_TIME', atSecond: 3 },
        parameters: {},
      },
    ],
  }
}

test('matches the backend canonical key-order and SHA-256 vector', async () => {
  const value = {
    z: [2, 1],
    nested: { b: 2, a: 1 },
    a: '1.23',
  }
  const canonical = '{"a":"1.23","nested":{"a":1,"b":2},"z":[2,1]}'

  assert.equal(canonicalJson(value), canonical)
  assert.equal(
    await (hashScenarioConfig as (input: unknown) => Promise<string>)(value),
    'f44d9a6ba8e0069ebe7298b4b87a3a7366ed8d95712a4386f70a893a9e97be2e',
  )
})

test('normalizes backend-compatible numeric strings while preserving opaque identifiers', () => {
  assert.equal(
    canonicalJson({
      seed: '001',
      actionId: '001',
      modelVersion: '1.0',
      price: '001.2300',
      initialBalances: { USDT: '100000.00' },
    }),
    '{"actionId":"001","initialBalances":{"USDT":"100000"},"modelVersion":"1.0","price":"1.23","seed":"001"}',
  )
})

test('preserves explicit asset identity as opaque text instead of normalizing or deriving it', () => {
  const input = config()
  input.instruments[0].symbol = 'XBT-USDT-LAB'
  input.instruments[0].baseAsset = '001'
  input.instruments[0].quoteAsset = 'USDT'

  const normalized = JSON.parse(canonicalJson(input)) as TradingLabConfigSnapshot
  assert.equal(normalized.instruments[0].symbol, 'XBT-USDT-LAB')
  assert.equal(normalized.instruments[0].baseAsset, '001')
  assert.equal(normalized.instruments[0].quoteAsset, 'USDT')
})

test('equal semantic configs produce the same lowercase Web Crypto hash', async () => {
  const first = config()
  const second: TradingLabConfigSnapshot = {
    instruments: [{
      ...first.instruments[0],
      maxQty: '100.0',
      minNotional: '10.000',
      tickSize: '0.01',
    }],
    executionPolicy: {
      ...first.executionPolicy,
      makerFeeRate: '0.001',
      maxFillQuantityPerTick: '100',
    },
    codeVersion: first.codeVersion,
    symbolConfigVersion: first.symbolConfigVersion,
    modelVersion: first.modelVersion,
  }

  const firstHash = await hashScenarioConfig(first)
  const secondHash = await hashScenarioConfig(second)
  assert.match(firstHash, /^[0-9a-f]{64}$/)
  assert.equal(firstHash, secondHash)
})

test('fingerprints the whole normalized scenario and changes with an edit', async () => {
  const first = scenario()
  const equivalent = scenario()
  equivalent.initialBalances.USDT = '100000.0000'
  equivalent.timeline[0].parameters.price = '60000.12000'
  const runtimeEquivalent = equivalent as TradingLabScenario & {
    uiState: { selectedPanel: string }
  }
  runtimeEquivalent.uiState = { selectedPanel: 'timeline' }

  const firstHash = await scenarioFingerprint(first)
  const equivalentHash = await scenarioFingerprint(runtimeEquivalent)
  const edited = structuredClone(first)
  edited.timeline[0].parameters.quantity = '0.02'

  assert.match(firstHash, /^[0-9a-f]{64}$/)
  assert.equal(firstHash, equivalentHash)
  assert.notEqual(firstHash, await scenarioFingerprint(edited))
})

test('normalizes a projected scenario without mutating or reordering its timeline', () => {
  const input = scenario()
  const runtimeInput = input as TradingLabScenario & {
    uiState: { dirty: boolean }
  }
  runtimeInput.uiState = { dirty: true }
  const firstAction = runtimeInput.timeline[0] as typeof runtimeInput.timeline[number] & {
    selected: boolean
  }
  firstAction.selected = true
  const originalJson = JSON.stringify(runtimeInput)

  const normalized = normalizeScenario(runtimeInput)

  assert.deepEqual(normalized.timeline.map((action) => action.id), ['action-002', 'action-001'])
  assert.equal(normalized.initialBalances.USDT, '100000')
  assert.equal(normalized.timeline[0].parameters.price, '60000.12')
  assert.deepEqual(normalized.executionPolicy, {
    matchingMode: 'SIMPLE',
    makerFeeRate: '0.001',
    takerFeeRate: '0.002',
    liquidationFeeRate: '0.003',
    slippageRate: '0',
    maxFillQuantityPerTick: '100',
  })
  assert.equal(normalized.marketPath.virtualStart, '2026-01-01T00:00:00.000Z')
  assert.equal(normalized.marketPath.instruments[0].mode, 'SIMPLE')
  assert.notEqual(normalized.executionPolicy, runtimeInput.executionPolicy)
  assert.notEqual(normalized.marketPath, runtimeInput.marketPath)
  assert.equal('uiState' in normalized, false)
  assert.equal('selected' in normalized.timeline[0], false)
  assert.equal(JSON.stringify(runtimeInput), originalJson)
  assert.equal(runtimeInput.timeline[0].parameters.price, '60000.1200')
})

test('rejects old scenario payloads missing required execution policy or market path', () => {
  const missingPolicy = scenario() as unknown as Record<string, unknown>
  delete missingPolicy.executionPolicy
  assert.throws(() => normalizeScenario(missingPolicy as TradingLabScenario))

  const missingPath = scenario() as unknown as Record<string, unknown>
  delete missingPath.marketPath
  assert.throws(() => normalizeScenario(missingPath as TradingLabScenario))
})

test('rejects non-JSON, floating, unsafe, credential, exponent, and cyclic values', () => {
  assert.throws(() => canonicalJson({ price: 1.25 }))
  assert.throws(() => canonicalJson({ sequence: Number.MAX_SAFE_INTEGER + 1 }))
  assert.throws(() => canonicalJson({ apiKey: 'must-not-cross' }))
  assert.throws(() => canonicalJson({ price: '1e3' }))
  assert.throws(() => canonicalJson({ value: undefined }))
  assert.throws(() => canonicalJson({ value: 1n }))

  const cyclic: { self?: unknown } = {}
  cyclic.self = cyclic
  assert.throws(() => canonicalJson(cyclic))
})

test('rejects sparse arrays instead of silently changing their length or positions', () => {
  const sparse = [1, 2, 3]
  delete sparse[1]

  assert.throws(() => canonicalJson({ values: sparse }))
  assert.throws(() => canonicalJson({ values: [1, undefined, 3] }))
})

test('preserves __proto__ as JSON evidence instead of invoking an object setter', () => {
  const value = JSON.parse('{"__proto__":{"price":"1.00"},"a":1}')

  assert.equal(
    canonicalJson(value),
    '{"__proto__":{"price":"1"},"a":1}',
  )
})

test('rejects blank keys, unpaired surrogates, symbols, and accessor side effects', () => {
  assert.throws(() => canonicalJson({ ' ': 1 }))
  assert.throws(() => canonicalJson({ value: '\ud800' }))
  assert.throws(() => canonicalJson({ ['\udc00']: 1 }))
  assert.throws(() => canonicalJson({ [Symbol('hidden')]: 'value' }))

  const accessor: Record<string, unknown> = {}
  Object.defineProperty(accessor, 'safe', {
    enumerable: true,
    get() {
      delete accessor.apiKey
      return 1
    },
  })
  accessor.apiKey = 'must-not-disappear'
  assert.throws(() => canonicalJson(accessor))
  assert.equal(accessor.apiKey, 'must-not-disappear')
})

test('rejects non-JSON own properties on arrays and objects without invoking getters', () => {
  const arrayWithExtra = [1] as unknown[] & { apiKey?: string }
  arrayWithExtra.apiKey = 'must-not-cross'
  assert.throws(() => canonicalJson(arrayWithExtra))

  const arrayWithSymbol = [1]
  Object.defineProperty(arrayWithSymbol, Symbol('hidden'), {
    enumerable: true,
    value: 'must-not-cross',
  })
  assert.throws(() => canonicalJson(arrayWithSymbol))

  let reads = 0
  const arrayWithAccessor = [1]
  Object.defineProperty(arrayWithAccessor, '0', {
    configurable: true,
    enumerable: true,
    get() {
      reads += 1
      return 1
    },
  })
  assert.throws(() => canonicalJson(arrayWithAccessor))
  assert.equal(reads, 0)

  const objectWithHiddenCredential: Record<string, unknown> = { safe: 1 }
  Object.defineProperty(objectWithHiddenCredential, 'apiKey', {
    enumerable: false,
    value: 'must-not-cross',
  })
  assert.throws(() => canonicalJson(objectWithHiddenCredential))
})

test('default construction is deterministic and does not invent timeline or market values', () => {
  const input = {
    id: 'draft-1',
    seed: 'seed-1',
    configSnapshot: config(),
    configSnapshotHash: 'a'.repeat(64),
    initialBalances: { USDT: '1000' },
    defaults: {
      positionMode: 'ONE_WAY' as const,
      marginMode: 'CROSS' as const,
      leverage: 1,
    },
    symbols: [{
      symbol: 'BTCUSDT',
      productType: 'CRYPTO_SPOT' as const,
    }],
    marketPath: marketPath(),
  }

  const first = createDefaultScenario(input)
  const second = createDefaultScenario(input)
  assert.deepEqual(first, second)
  assert.deepEqual(first.timeline, [])
  assert.equal(first.modelVersion, 'model-1.0')
  assert.deepEqual(first.executionPolicy, {
    matchingMode: 'SIMPLE',
    makerFeeRate: '0.001',
    takerFeeRate: '0.002',
    liquidationFeeRate: '0.003',
    slippageRate: '0',
    maxFillQuantityPerTick: '100',
  })
  assert.deepEqual(first.marketPath, JSON.parse(canonicalJson(input.marketPath)))
  assert.notEqual(first.executionPolicy, input.configSnapshot.executionPolicy)
  assert.notEqual(first.marketPath, input.marketPath)
})

test('normalization production source stays browser-native and backend-independent', () => {
  const source = readFileSync(new URL('./normalization.ts', import.meta.url), 'utf8')
  for (const token of ['node:crypto', 'createHash', 'BigDecimal', '/backend/', '\\backend\\']) {
    assert.equal(source.includes(token), false, `forbidden token: ${token}`)
  }
  assert.match(source, /crypto\.subtle\.digest/)
  assert.match(source, /TextEncoder/)
})
