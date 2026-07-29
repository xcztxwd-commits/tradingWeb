import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

import {
  canonicalJson,
  hashScenarioConfig,
  normalizeScenario,
} from '../model/normalization.ts'
import type {
  TradingLabConfigSnapshot,
  TradingLabScenario,
} from '../model/types.ts'
import {
  downloadTradingLabScenarioJson,
  exportTradingLabScenarioJson,
  importTradingLabScenarioJson,
} from './scenarioJson.ts'

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
  id = 'draft-001',
): Promise<TradingLabScenario> {
  const snapshot = configSnapshot()
  return {
    id,
    name: 'JSON 草稿',
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
    timeline: [],
  }
}

test('imports raw scenario JSON through normalization before returning it', async () => {
  const input = await scenario()
  const runtimeInput = input as TradingLabScenario & {
    uiState: { dirty: boolean }
  }
  runtimeInput.uiState = { dirty: true }

  const imported = await importTradingLabScenarioJson(
    JSON.stringify(runtimeInput),
  )

  assert.notEqual(imported, runtimeInput)
  assert.equal(imported.initialBalances.USDT, '100000')
  assert.equal(imported.executionPolicy.makerFeeRate, '0.001')
  assert.deepEqual(imported.timeline, [])
  assert.equal('uiState' in imported, false)
})

test('rejects malformed JSON, foreign official matrices, and hash mismatch', async () => {
  await assert.rejects(
    importTradingLabScenarioJson('{'),
    /JSON/,
  )
  await assert.rejects(
    importTradingLabScenarioJson(JSON.stringify([{
      caseId: 'PERP_ADMIN_FORCE_CLOSE',
      actions: [],
      priceSteps: [],
    }])),
    /场景文档/,
  )

  const mismatched = await scenario()
  mismatched.configSnapshotHash = 'f'.repeat(64)
  await assert.rejects(
    importTradingLabScenarioJson(JSON.stringify(mismatched)),
    /配置快照哈希/,
  )
})

test('exports exactly one raw canonical unfinished scenario document', async () => {
  const input = await scenario()
  const runtimeInput = input as TradingLabScenario & {
    uiState: { expanded: boolean }
    updatedAt: string
    schemaVersion: number
  }
  runtimeInput.uiState = { expanded: true }
  runtimeInput.updatedAt = '2026-01-01T00:00:00.000Z'
  runtimeInput.schemaVersion = 1

  const exported = await exportTradingLabScenarioJson(runtimeInput)

  assert.equal(exported, canonicalJson(normalizeScenario(input)))
  assert.equal(exported.includes('updatedAt'), false)
  assert.equal(exported.includes('schemaVersion'), false)
  assert.equal(exported.includes('uiState'), false)
  assert.deepEqual(
    await importTradingLabScenarioJson(exported),
    normalizeScenario(input),
  )
})

test('downloads a canonical JSON Blob with a sanitized id-derived filename', async () => {
  const input = await scenario('draft:btc/01?*')
  const saves: Array<{ blob: Blob; fileName: string }> = []

  await downloadTradingLabScenarioJson(input, (blob, fileName) => {
    saves.push({ blob, fileName })
  })

  assert.equal(saves.length, 1)
  assert.equal(saves[0].blob.type, 'application/json;charset=utf-8')
  assert.equal(saves[0].fileName, 'draft_btc_01__.json')
  assert.equal(
    await saves[0].blob.text(),
    await exportTradingLabScenarioJson(input),
  )
})

test('JSON transfer production source has no backend, fetch, or storage escape hatch', () => {
  const source = readFileSync(new URL('./scenarioJson.ts', import.meta.url), 'utf8')
  for (const token of [
    'fetch(',
    'adminApi',
    '/api/',
    'localStorage',
    'EventSource',
    '/backend/',
    '\\backend\\',
  ]) {
    assert.equal(source.includes(token), false, `forbidden token: ${token}`)
  }
  assert.match(source, /new Blob/)
  assert.match(source, /createObjectURL/)
  assert.match(source, /revokeObjectURL/)
})
