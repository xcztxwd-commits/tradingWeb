import assert from 'node:assert/strict'
import test from 'node:test'

import {
  canonicalJson,
  hashScenarioConfig,
} from '../model/normalization.ts'
import type {
  TradingLabConfigSnapshot,
  TradingLabScenario,
} from '../model/types.ts'
import { validateScenario } from '../model/validation.ts'
import { normalizeTradingLabScenarioDocument } from './scenarioDocument.ts'

function configSnapshot(): TradingLabConfigSnapshot {
  return {
    modelVersion: 'model-v1',
    symbolConfigVersion: 'symbols-v1',
    codeVersion: 'code-v1',
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
      liquidationFeeRate: '0.0030',
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

async function scenario(
  withAction = false,
): Promise<TradingLabScenario> {
  const snapshot = configSnapshot()
  return {
    id: 'draft-001',
    name: '本地草稿',
    description: '',
    negativeMode: false,
    seed: 'seed-001',
    modelVersion: snapshot.modelVersion,
    configSnapshot: snapshot,
    configSnapshotHash: await hashScenarioConfig(snapshot),
    executionPolicy: { ...snapshot.executionPolicy },
    marketPath: {
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
    },
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
    timeline: withAction
      ? [{
          id: 'action-001',
          sequence: 1,
          type: 'PLACE_ORDER',
          symbol: 'BTCUSDT',
          productType: 'CRYPTO_SPOT',
          trigger: { type: 'VIRTUAL_TIME', atSecond: 1 },
          parameters: {
            side: 'BUY',
            orderType: 'LIMIT',
            quantity: '0.0100',
            price: '60000.00',
          },
        }]
      : [],
  }
}

test('normalizes and detaches a structurally valid unfinished draft', async () => {
  const input = await scenario()
  input.name = ''
  input.defaults.leverage = 0
  input.initialBalances.USDT = ''
  const runtimeInput = input as TradingLabScenario & {
    uiState: { selectedActionId: string | null }
    schemaVersion: number
    updatedAt: string
  }
  runtimeInput.uiState = { selectedActionId: null }
  runtimeInput.schemaVersion = 1
  runtimeInput.updatedAt = '2026-07-25T01:02:03.004Z'
  const before = canonicalJson(runtimeInput)

  const normalized = await normalizeTradingLabScenarioDocument(runtimeInput)

  assert.notEqual(normalized, runtimeInput)
  assert.notEqual(normalized.configSnapshot, runtimeInput.configSnapshot)
  assert.deepEqual(normalized.timeline, [])
  assert.equal(normalized.name, '')
  assert.equal(normalized.initialBalances.USDT, '')
  assert.equal('uiState' in normalized, false)
  assert.equal('schemaVersion' in normalized, false)
  assert.equal('updatedAt' in normalized, false)
  assert.equal(canonicalJson(runtimeInput), before)

  const issueCodes = validateScenario(normalized).map((issue) => issue.code)
  assert.equal(issueCodes.includes('SCENARIO_NAME_REQUIRED'), true)
  assert.equal(issueCodes.includes('TIMELINE_REQUIRED'), true)
  assert.equal(issueCodes.includes('INITIAL_BALANCE_INVALID'), true)
  assert.equal(issueCodes.includes('DEFAULT_LEVERAGE_OUT_OF_RANGE'), true)
})

test('round-trips explicit null notional bounds without inventing authority', async () => {
  const input = await scenario()
  const instrument = input.configSnapshot.instruments[0]
  instrument.minNotional = null
  instrument.maxNotional = null
  input.configSnapshotHash = await hashScenarioConfig(input.configSnapshot)

  const normalized = await normalizeTradingLabScenarioDocument(input)

  assert.equal(normalized.configSnapshot.instruments[0].minNotional, null)
  assert.equal(normalized.configSnapshot.instruments[0].maxNotional, null)
  assert.equal(
    normalized.configSnapshotHash,
    await hashScenarioConfig(normalized.configSnapshot),
  )
})

test('round-trips an explicit null max-fill cap without inventing authority', async () => {
  const input = await scenario()
  input.executionPolicy.maxFillQuantityPerTick = null
  input.configSnapshot.executionPolicy.maxFillQuantityPerTick = null
  input.configSnapshotHash = await hashScenarioConfig(input.configSnapshot)

  const normalized = await normalizeTradingLabScenarioDocument(input)

  assert.equal(normalized.executionPolicy.maxFillQuantityPerTick, null)
  assert.equal(
    normalized.configSnapshot.executionPolicy.maxFillQuantityPerTick,
    null,
  )
  assert.equal(
    normalized.configSnapshotHash,
    await hashScenarioConfig(normalized.configSnapshot),
  )
})

test('preserves incomplete action business fields for the editor to repair', async () => {
  const input = await scenario(true)
  input.timeline[0].parameters = {
    side: '',
    orderType: '',
    quantity: '',
  }

  const normalized = await normalizeTradingLabScenarioDocument(input)

  assert.deepEqual(normalized.timeline[0].parameters, {
    orderType: '',
    quantity: '',
    side: '',
  })
  const issueCodes = validateScenario(normalized).map((issue) => issue.code)
  assert.equal(issueCodes.includes('ACTION_TEXT_PARAMETER_INVALID'), true)
  assert.equal(issueCodes.includes('QUANTITY_INVALID'), true)
})

test('rejects structural corruption instead of casting or repairing it', async () => {
  const base = await scenario(true)
  const corruptions: ReadonlyArray<readonly [
    string,
    (input: Record<string, unknown>) => void,
  ]> = [
    ['wrong top-level scalar', (input) => {
      input.negativeMode = 'false'
    }],
    ['execution policy container', (input) => {
      input.executionPolicy = []
    }],
    ['instrument row', (input) => {
      const snapshot = input.configSnapshot as { instruments: unknown[] }
      snapshot.instruments = [null]
    }],
    ['symbol row', (input) => {
      input.symbols = [null]
    }],
    ['market path discriminant', (input) => {
      const path = input.marketPath as {
        instruments: Array<Record<string, unknown>>
      }
      path.instruments[0].mode = 'UNKNOWN'
    }],
    ['action row', (input) => {
      input.timeline = [null]
    }],
    ['action type discriminant', (input) => {
      const timeline = input.timeline as Array<Record<string, unknown>>
      timeline[0].type = 'UNKNOWN'
    }],
    ['trigger container', (input) => {
      const timeline = input.timeline as Array<Record<string, unknown>>
      timeline[0].trigger = null
    }],
    ['parameter container', (input) => {
      const timeline = input.timeline as Array<Record<string, unknown>>
      timeline[0].parameters = []
    }],
  ]

  await assert.rejects(
    normalizeTradingLabScenarioDocument(null),
    /场景文档/,
  )
  await assert.rejects(
    normalizeTradingLabScenarioDocument([]),
    /场景文档/,
  )

  for (const [name, corrupt] of corruptions) {
    const input = structuredClone(base) as unknown as Record<string, unknown>
    corrupt(input)
    await assert.rejects(
      normalizeTradingLabScenarioDocument(input),
      /场景文档/,
      name,
    )
  }
})

test('rejects a config snapshot hash mismatch without inventing authority', async () => {
  const input = await scenario()
  input.configSnapshotHash = '0'.repeat(64)

  await assert.rejects(
    normalizeTradingLabScenarioDocument(input),
    /配置快照哈希/,
  )
  assert.equal(input.configSnapshotHash, '0'.repeat(64))
})

test('rejects nested runtime-shape corruption even when its config hash is recomputed', async (t) => {
  await t.test('missing config snapshot version', async () => {
    const input = await scenario()
    delete (
      input.configSnapshot as unknown as Record<string, unknown>
    ).symbolConfigVersion
    input.configSnapshotHash = await hashScenarioConfig(input.configSnapshot)

    await assert.rejects(normalizeTradingLabScenarioDocument(input))
  })

  await t.test('missing instrument authority field', async () => {
    const input = await scenario()
    delete (
      input.configSnapshot.instruments[0] as unknown as Record<string, unknown>
    ).baseAsset
    input.configSnapshotHash = await hashScenarioConfig(input.configSnapshot)

    await assert.rejects(normalizeTradingLabScenarioDocument(input))
  })

  await t.test('non-string virtual start', async () => {
    const input = await scenario()
    ;(
      input.marketPath as unknown as Record<string, unknown>
    ).virtualStart = 123

    await assert.rejects(normalizeTradingLabScenarioDocument(input))
  })

  await t.test('non-integer path duration', async () => {
    const input = await scenario()
    const simple = input.marketPath.instruments[0]
    assert.equal(simple.mode, 'SIMPLE')
    if (simple.mode === 'SIMPLE') {
      ;(
        simple.last.segments[0] as unknown as Record<string, unknown>
      ).durationSeconds = '300'
    }

    await assert.rejects(normalizeTradingLabScenarioDocument(input))
  })

  await t.test('non-string balance value', async () => {
    const input = await scenario()
    ;(
      input.initialBalances as unknown as Record<string, unknown>
    ).USDT = { amount: '100000' }

    await assert.rejects(normalizeTradingLabScenarioDocument(input))
  })
})

test('rejects non-JSON object semantics and non-contract nested fields', async (t) => {
  await t.test('does not read an accessor-backed field', async () => {
    const input = await scenario(true)
    let getterCalls = 0
    Object.defineProperty(input, 'id', {
      enumerable: true,
      get() {
        getterCalls += 1
        return 'draft-001'
      },
    })

    await assert.rejects(normalizeTradingLabScenarioDocument(input))
    assert.equal(getterCalls, 0)
  })

  await t.test('rejects an object that inherits the scenario fields', async () => {
    const prototype = await scenario(true)
    const input = Object.create(prototype) as unknown

    await assert.rejects(normalizeTradingLabScenarioDocument(input))
  })

  await t.test('rejects an unknown defaults field', async () => {
    const input = await scenario(true)
    ;(
      input.defaults as unknown as Record<string, unknown>
    ).untrusted = 'kept'

    await assert.rejects(normalizeTradingLabScenarioDocument(input))
  })

  await t.test('rejects an unknown trigger field', async () => {
    const input = await scenario(true)
    ;(
      input.timeline[0].trigger as unknown as Record<string, unknown>
    ).untrusted = 'kept'

    await assert.rejects(normalizeTradingLabScenarioDocument(input))
  })

  await t.test('rejects an unknown expected-error field', async () => {
    const input = await scenario(true)
    input.timeline[0].expectedError = {
      status: 400,
      code: 'EXPECTED_FAILURE',
    }
    ;(
      input.timeline[0].expectedError as unknown as Record<string, unknown>
    ).untrusted = 'kept'

    await assert.rejects(normalizeTradingLabScenarioDocument(input))
  })

  await t.test('rejects an unknown config field even with its hash recomputed', async () => {
    const input = await scenario(true)
    ;(
      input.configSnapshot.instruments[0] as unknown as Record<string, unknown>
    ).untrusted = 'kept'
    input.configSnapshotHash = await hashScenarioConfig(input.configSnapshot)

    await assert.rejects(normalizeTradingLabScenarioDocument(input))
  })

  await t.test('rejects an unknown symbol field before projection', async () => {
    const input = await scenario(true)
    ;(
      input.symbols[0] as unknown as Record<string, unknown>
    ).untrusted = 'discarded'

    await assert.rejects(normalizeTradingLabScenarioDocument(input))
  })

  await t.test('rejects an unknown action field before projection', async () => {
    const input = await scenario(true)
    ;(
      input.timeline[0] as unknown as Record<string, unknown>
    ).untrusted = 'discarded'

    await assert.rejects(normalizeTradingLabScenarioDocument(input))
  })

  await t.test('rejects an unknown top-level field before projection', async () => {
    const input = await scenario(true)
    ;(
      input as unknown as Record<string, unknown>
    ).untrusted = 'discarded'

    await assert.rejects(normalizeTradingLabScenarioDocument(input))
  })

  const transientLookingCorruptions: ReadonlyArray<readonly [
    string,
    (input: TradingLabScenario) => void,
  ]> = [
    ['top-level selected', (input) => {
      ;(
        input as unknown as Record<string, unknown>
      ).selected = true
    }],
    ['symbol expanded', (input) => {
      ;(
        input.symbols[0] as unknown as Record<string, unknown>
      ).expanded = true
    }],
    ['action dragging', (input) => {
      ;(
        input.timeline[0] as unknown as Record<string, unknown>
      ).dragging = true
    }],
    ['defaults validation errors', (input) => {
      ;(
        input.defaults as unknown as Record<string, unknown>
      ).validationErrors = []
    }],
    ['trigger transient', (input) => {
      ;(
        input.timeline[0].trigger as unknown as Record<string, unknown>
      ).transient = true
    }],
    ['instrument UI state', (input) => {
      ;(
        input.configSnapshot.instruments[0] as unknown as Record<
          string,
          unknown
        >
      ).uiState = {}
    }],
    ['action parameter selected', (input) => {
      input.timeline[0].parameters.selected = true
    }],
    ['action override dragging', (input) => {
      input.timeline[0].overrides = { dragging: true }
    }],
    ['balance UI state', (input) => {
      ;(
        input.initialBalances as unknown as Record<string, unknown>
      ).uiState = '1'
    }],
  ]

  for (const [name, corrupt] of transientLookingCorruptions) {
    await t.test(`rejects ${name} before transient stripping`, async () => {
      const input = await scenario(true)
      corrupt(input)

      await assert.rejects(normalizeTradingLabScenarioDocument(input))
    })
  }
})
