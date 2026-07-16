import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import type { TradingSettingsResponse } from '@fx-platform/shared-types'

import { ApiClientError } from '../../services/apiClient.ts'
import {
  buildSymbolSettingsPayload,
  clampTradingLeverage,
  createTradingSettingsRequestGate,
  resolveActiveTradingSettings,
  resolveSymbolSettings,
  runTradingSettingsMutation
} from './useTradingSettings.ts'

const settings = {
  accountId: 'acct_1',
  positionMode: 'ONE_WAY',
  symbols: [
    {
      symbol: 'BTCUSDT-PERP',
      leverage: 10,
      marginMode: 'CROSS',
      quantityUnit: 'BASE',
      version: 3,
      maxLeverage: 50
    }
  ]
} satisfies TradingSettingsResponse

describe('trading settings hook helpers', () => {
  it('resolves symbol settings without stripping the Perpetual suffix', () => {
    assert.equal(resolveSymbolSettings(settings, 'BTCUSDT-PERP')?.version, 3)
    assert.equal(resolveSymbolSettings(settings, 'BTCUSDTPERP'), null)
  })

  it('never exposes settings from a previous account', () => {
    assert.equal(resolveActiveTradingSettings(settings, 'acct_1'), settings)
    assert.equal(resolveActiveTradingSettings(settings, 'acct_2'), null)
    assert.equal(resolveActiveTradingSettings(settings, null), null)
  })

  it('invalidates stale and unmounted settings requests by generation', () => {
    const gate = createTradingSettingsRequestGate()
    const accountARequest = gate.begin()
    const accountBRequest = gate.begin()

    assert.equal(gate.isCurrent(accountARequest), false)
    assert.equal(gate.isCurrent(accountBRequest), true)
    gate.invalidate()
    assert.equal(gate.isCurrent(accountBRequest), false)
  })

  it('clamps leverage to 1..min(100, backend max)', () => {
    assert.equal(clampTradingLeverage(0, 50), 1)
    assert.equal(clampTradingLeverage(20.6, 50), 21)
    assert.equal(clampTradingLeverage(75, 50), 50)
    assert.equal(clampTradingLeverage(200, 125), 100)
  })

  it('builds an optimistic-lock PATCH for margin mode, leverage and quantity unit', () => {
    const symbolSettings = resolveSymbolSettings(settings, 'BTCUSDT-PERP')
    assert.ok(symbolSettings)

    assert.deepEqual(
      buildSymbolSettingsPayload(symbolSettings, {
        leverage: 75,
        marginMode: 'ISOLATED',
        quantityUnit: 'CONTRACTS'
      }),
      {
        leverage: 50,
        marginMode: 'ISOLATED',
        quantityUnit: 'CONTRACTS',
        expectedVersion: 3
      }
    )
  })

  it('keeps blocked position or margin switch errors intact for callers', async () => {
    const blocked = new ApiClientError({
      status: 409,
      code: 'POSITION_MODE_SWITCH_BLOCKED',
      message: 'Active orders block this switch',
      requestId: 'req_blocked'
    })
    let observed: unknown

    await assert.rejects(
      runTradingSettingsMutation(
        () => Promise.reject(blocked),
        () => assert.fail('blocked mutations must not publish a success value'),
        (error) => {
          observed = error
        }
      ),
      (error: unknown) => error === blocked
    )
    assert.equal(observed, blocked)
  })
})
