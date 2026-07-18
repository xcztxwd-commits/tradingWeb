import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { getShellRouteFlags } from './shellRouteModel.ts'

describe('shell route stability', () => {
  it('classifies direct deep links without depending on device class or query strings', () => {
    const cases = [
      ['https://example.test/', { isTerminalRoute: false, isAuthRoute: false }],
      ['https://example.test/login?redirect=%2Ftrade%2Fspot%2FBTCUSDT', { isTerminalRoute: false, isAuthRoute: true }],
      ['https://example.test/trade/spot/ETHUSDT?panel=orders', { isTerminalRoute: true, isAuthRoute: false }],
      ['https://example.test/trade/perpetual/BTCUSDT-PERP', { isTerminalRoute: true, isAuthRoute: false }],
      ['https://example.test/account/overview?tab=assets', { isTerminalRoute: false, isAuthRoute: false }]
    ] as const

    for (const [href, expected] of cases) {
      assert.deepEqual(getShellRouteFlags(new URL(href).pathname), expected)
    }
  })

  it('remains deterministic across a back/forward-equivalent pathname sequence', () => {
    const history = ['/markets', '/trade/spot/BTCUSDT', '/account/overview', '/trade/spot/BTCUSDT', '/markets']
    assert.deepEqual(history.map((pathname) => getShellRouteFlags(pathname).isTerminalRoute), [
      false,
      true,
      false,
      true,
      false
    ])
  })
})
