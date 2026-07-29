import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

import * as chartSettings from './chartSettings.ts'
import { buildMarketCandlesPath, type TradingPeriod } from '@fx-platform/frontend-core'

type ChartIntervalContract = {
  getChartIntervalOptions: (symbol: string) => typeof chartSettings.allChartIntervals
  isP0ChartSymbol: (symbol: string) => boolean
  normalizeChartInterval: (symbol: string, interval: TradingPeriod) => TradingPeriod
}

type PerSymbolChartSettingsLoader = (
  symbol: string,
  storage: Pick<Storage, 'getItem' | 'setItem'>
) => chartSettings.ChartSettings

type OwnedChartSettingsState = {
  ownerSymbol: string
  settings: chartSettings.ChartSettings
}

type ChartSettingsStateSelector = (
  state: OwnedChartSettingsState,
  selectedSymbol: string,
  loadSettings: (symbol: string) => chartSettings.ChartSettings
) => OwnedChartSettingsState

const p0Symbols = [
  'BTCUSDT', 'ETHUSDT', 'BNBUSDT', 'SOLUSDT', 'XRPUSDT',
  'BTCUSDT-PERP', 'ETHUSDT-PERP', 'BNBUSDT-PERP', 'SOLUSDT-PERP', 'XRPUSDT-PERP'
]
const expectedP0Options: TradingPeriod[] = ['time', '1s', '1m', '5m', '15m', '1h', '4h', '1d']

describe('P0 chart interval contract', () => {
  it('exposes only backend-compatible options for the exact ten P0 symbols', () => {
    const contract = requireIntervalContract()

    p0Symbols.forEach((symbol) => {
      assert.equal(contract.isP0ChartSymbol(symbol), true)
      assert.deepEqual(contract.getChartIntervalOptions(symbol).map((item) => item.value), expectedP0Options)
    })
    assert.equal(contract.isP0ChartSymbol('btc-usdt'), true)
    assert.equal(contract.isP0ChartSymbol('BTC_USDT_PERP'), true)
    assert.equal(contract.isP0ChartSymbol('DOGEUSDT'), false)
    const p0Options = contract.getChartIntervalOptions('BTCUSDT')
    const quickOptions = chartSettings.quickChartIntervals(
      { favoriteIntervals: ['30m', '1h', '2h'] },
      p0Options
    )
    assert.deepEqual(quickOptions.map((item) => item.value), ['1h'])
    assert.equal(chartSettings.getIntervalByShortcutKey('1', quickOptions), '1h')
    assert.equal(chartSettings.getIntervalByShortcutKey('2', quickOptions), null)

    const visibleAfterAdding = chartSettings.quickChartIntervals(
      { favoriteIntervals: chartSettings.toggleFavoriteInterval(['30m'], '1m') },
      p0Options
    )
    assert.deepEqual(visibleAfterAdding.map((item) => item.value), ['1m'])
    assert.equal(visibleAfterAdding.length, 1)
  })

  it('preserves the complete legacy interval list for non-P0 symbols', () => {
    const contract = requireIntervalContract()
    const allValues = chartSettings.allChartIntervals.map((item) => item.value)

    assert.deepEqual(contract.getChartIntervalOptions('EURUSD').map((item) => item.value), allValues)
    assert.deepEqual(contract.getChartIntervalOptions('DOGEUSDT').map((item) => item.value), allValues)
  })

  it('normalizes a persisted unsupported P0 interval to 1m while preserving time and legacy intervals', () => {
    const contract = requireIntervalContract()
    const storage = new MemoryStorage()
    storage.setItem(chartSettings.getChartSettingsStorageKey('BTCUSDT'), JSON.stringify({ interval: '30m' }))
    storage.setItem(chartSettings.getChartSettingsStorageKey('ETHUSDT'), JSON.stringify({ interval: 'time' }))
    storage.setItem(chartSettings.getChartSettingsStorageKey('EURUSD'), JSON.stringify({ interval: '30m' }))

    assert.equal(contract.normalizeChartInterval(
      'BTCUSDT', chartSettings.loadChartSettings('BTCUSDT', storage).interval
    ), '1m')
    assert.equal(contract.normalizeChartInterval(
      'ETHUSDT', chartSettings.loadChartSettings('ETHUSDT', storage).interval
    ), 'time')
    assert.equal(contract.normalizeChartInterval(
      'EURUSD', chartSettings.loadChartSettings('EURUSD', storage).interval
    ), '30m')
  })

  it('keeps every P0 timeframe actually sent by Web aligned with the backend whitelist', () => {
    const contract = requireIntervalContract()
    const currentDir = dirname(fileURLToPath(import.meta.url))
    const backendPolicy = readFileSync(join(
      currentDir,
      '../../../../../backend/src/main/java/com/fxplatform/market/provider/CandleRequestPolicy.java'
    ), 'utf8')
    const whitelistBody = backendPolicy.match(/ALLOWED_TIMEFRAMES\s*=\s*Set\.of\(([\s\S]*?)\);/)?.[1]
    assert.ok(whitelistBody, 'backend CandleRequestPolicy whitelist must remain parseable')
    const backendTimeframes = Array.from(whitelistBody.matchAll(/"([^"]+)"/g), (match) => match[1]).sort()
    const sentTimeframes = Array.from(new Set(
      contract.getChartIntervalOptions('BTCUSDT').map((item) => {
        const path = buildMarketCandlesPath('BTCUSDT', item.value, Date.UTC(2026, 6, 12), 1)
        return new URL(path, 'http://localhost').searchParams.get('timeframe')
      })
    )).sort()

    assert.deepEqual(sentTimeframes, backendTimeframes)
  })

  it('loads each symbol own settings without carrying the previous symbol interval into it', () => {
    const loadForSymbol = requirePerSymbolLoader()
    const storage = new MemoryStorage()
    storage.seed(chartSettings.getChartSettingsStorageKey('EURUSD'), JSON.stringify({ interval: '30m' }))
    storage.seed(chartSettings.getChartSettingsStorageKey('BTCUSDT'), JSON.stringify({ interval: '4h' }))

    const eurSettings = loadForSymbol('EURUSD', storage)
    const btcSettings = loadForSymbol('BTCUSDT', storage)

    assert.equal(eurSettings.interval, '30m')
    assert.equal(btcSettings.interval, '4h')
    assert.equal(storage.readInterval('EURUSD'), '30m')
    assert.equal(storage.readInterval('BTCUSDT'), '4h')
    assert.deepEqual(storage.writtenKeys, [])
  })

  it('migrates only the target P0 symbol own unsupported stored interval to 1m', () => {
    const loadForSymbol = requirePerSymbolLoader()
    const storage = new MemoryStorage()
    storage.seed(chartSettings.getChartSettingsStorageKey('EURUSD'), JSON.stringify({ interval: '30m' }))
    storage.seed(chartSettings.getChartSettingsStorageKey('BTCUSDT'), JSON.stringify({ interval: '30m' }))

    const btcSettings = loadForSymbol('BTCUSDT', storage)

    assert.equal(btcSettings.interval, '1m')
    assert.equal(storage.readInterval('BTCUSDT'), '1m')
    assert.equal(storage.readInterval('EURUSD'), '30m')
    assert.deepEqual(storage.writtenKeys, [chartSettings.getChartSettingsStorageKey('BTCUSDT')])
  })

  it('selects the target symbol own settings before render when the state owner is stale', () => {
    const loadForSymbol = requirePerSymbolLoader()
    const selectForSymbol = requireChartSettingsStateSelector()
    const storage = new MemoryStorage()
    storage.seed(chartSettings.getChartSettingsStorageKey('EURUSD'), JSON.stringify({ interval: '30m' }))
    storage.seed(chartSettings.getChartSettingsStorageKey('BTCUSDT'), JSON.stringify({ interval: '4h' }))
    const staleState = {
      ownerSymbol: 'EURUSD',
      settings: loadForSymbol('EURUSD', storage)
    }
    storage.clearAccesses()

    const selectedState = selectForSymbol(
      staleState,
      'BTCUSDT',
      (symbol) => loadForSymbol(symbol, storage)
    )

    assert.equal(selectedState.ownerSymbol, 'BTCUSDT')
    assert.equal(selectedState.settings.interval, '4h')
    assert.deepEqual(storage.readKeys, [chartSettings.getChartSettingsStorageKey('BTCUSDT')])
    assert.deepEqual(storage.writtenKeys, [])
    assert.equal(storage.readIntervalWithoutTracking('EURUSD'), '30m')
    assert.equal(storage.readIntervalWithoutTracking('BTCUSDT'), '4h')
  })

  it('returns a safe normalized P0 interval when migration storage writes fail', () => {
    const loadForSymbol = requirePerSymbolLoader()
    const key = chartSettings.getChartSettingsStorageKey('BTCUSDT')
    const storage = new ThrowingWriteStorage(key, JSON.stringify({ interval: '30m' }))

    const settings = loadForSymbol('BTCUSDT', storage)

    assert.equal(settings.interval, '1m')
    assert.deepEqual(storage.readKeys, [key])
    assert.deepEqual(storage.attemptedWriteKeys, [key])
  })
})

function requireIntervalContract(): ChartIntervalContract {
  const candidate = chartSettings as typeof chartSettings & Partial<ChartIntervalContract>
  assert.equal(typeof candidate.getChartIntervalOptions, 'function')
  assert.equal(typeof candidate.isP0ChartSymbol, 'function')
  assert.equal(typeof candidate.normalizeChartInterval, 'function')
  return candidate as ChartIntervalContract
}

function requirePerSymbolLoader(): PerSymbolChartSettingsLoader {
  const candidate = chartSettings as typeof chartSettings & {
    loadChartSettingsForSymbol?: PerSymbolChartSettingsLoader
  }
  assert.equal(typeof candidate.loadChartSettingsForSymbol, 'function')
  return candidate.loadChartSettingsForSymbol as PerSymbolChartSettingsLoader
}

function requireChartSettingsStateSelector(): ChartSettingsStateSelector {
  const candidate = chartSettings as typeof chartSettings & {
    selectChartSettingsForSymbol?: ChartSettingsStateSelector
  }
  assert.equal(typeof candidate.selectChartSettingsForSymbol, 'function')
  return candidate.selectChartSettingsForSymbol as ChartSettingsStateSelector
}

class MemoryStorage {
  private readonly values = new Map<string, string>()
  readonly readKeys: string[] = []
  readonly writtenKeys: string[] = []

  seed(key: string, value: string) {
    this.values.set(key, value)
  }

  getItem(key: string) {
    this.readKeys.push(key)
    return this.values.get(key) ?? null
  }

  setItem(key: string, value: string) {
    this.writtenKeys.push(key)
    this.values.set(key, value)
  }

  readInterval(symbol: string) {
    const raw = this.getItem(chartSettings.getChartSettingsStorageKey(symbol))
    return raw ? (JSON.parse(raw) as { interval?: string }).interval : undefined
  }

  readIntervalWithoutTracking(symbol: string) {
    const raw = this.values.get(chartSettings.getChartSettingsStorageKey(symbol))
    return raw ? (JSON.parse(raw) as { interval?: string }).interval : undefined
  }

  clearAccesses() {
    this.readKeys.length = 0
    this.writtenKeys.length = 0
  }
}

class ThrowingWriteStorage {
  readonly readKeys: string[] = []
  readonly attemptedWriteKeys: string[] = []
  private readonly storedKey: string
  private readonly storedValue: string

  constructor(storedKey: string, storedValue: string) {
    this.storedKey = storedKey
    this.storedValue = storedValue
  }

  getItem(key: string) {
    this.readKeys.push(key)
    return key === this.storedKey ? this.storedValue : null
  }

  setItem(key: string, _value: string): never {
    this.attemptedWriteKeys.push(key)
    throw new Error('storage quota exceeded')
  }
}
