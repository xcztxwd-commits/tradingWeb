import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

import {
  canUseLegacyPositionProtection,
  executePositionProtectionUpdate,
  resolvePositionProtectionPath
} from './positionProtectionPolicy.ts'
import type { PositionResponse } from '@fx-platform/frontend-core'

const currentDir = dirname(fileURLToPath(import.meta.url))
const webSrc = resolve(currentDir, '../..')
const source = [
  readFileSync(join(webSrc, 'shared-widgets', 'positions', 'PositionsRouteContent.tsx'), 'utf8'),
  readFileSync(join(currentDir, 'usePositionsRouteController.ts'), 'utf8')
].join('\n')

describe('positions close confirmation dialog', () => {
  it('uses the shared critical Dialog for keyboard, focus, and overlay lifecycle', () => {
    assert.match(source, /import \{ Dialog,/)
    assert.match(source, /useRef<HTMLButtonElement \| null>\(null\)/)
    assert.match(source, /<Dialog[\s\S]*priority="critical"/)
    assert.match(source, /initialFocusRef=\{closeDialogCancelButtonRef\}/)
    assert.match(source, /pending=\{closeDialogIsBusy\}/)
    assert.doesNotMatch(source, /role="dialog"/)
    assert.doesNotMatch(source, /event\.key === 'Escape'/)
  })
})

describe('position protection routing', () => {
  it('routes Linear Perp positions to the canonical terminal and never invokes legacy scalar PATCH', async () => {
    const linearPosition = makePosition({
      id: 'linear-eth',
      symbol: 'ETHUSDT',
      productType: 'LINEAR_PERP',
      instrumentType: 'LINEAR_PERP'
    })
    let legacyCalls = 0

    const result = await executePositionProtectionUpdate(
      linearPosition,
      { stopLoss: '3000' },
      async () => { legacyCalls += 1 }
    )

    assert.equal(canUseLegacyPositionProtection(linearPosition), false)
    assert.equal(resolvePositionProtectionPath(linearPosition), '/trade/perpetual/ETHUSDT-PERP')
    assert.deepEqual(result, { kind: 'canonical', path: '/trade/perpetual/ETHUSDT-PERP' })
    assert.equal(legacyCalls, 0)
  })

  it('keeps the legacy scalar update only for non-P0 positions', async () => {
    const fxPosition = makePosition({ id: 'fx-eurusd', symbol: 'EURUSD', instrumentType: 'FOREX' })
    let legacyCalls = 0

    const result = await executePositionProtectionUpdate(
      fxPosition,
      { stopLoss: '1.08' },
      async () => { legacyCalls += 1 }
    )

    assert.equal(canUseLegacyPositionProtection(fxPosition), true)
    assert.equal(resolvePositionProtectionPath(fxPosition), null)
    assert.deepEqual(result, { kind: 'legacy' })
    assert.equal(legacyCalls, 1)
  })

  it('wires Linear Perp protection actions to the canonical workflow without a success lie', () => {
    assert.match(source, /resolvePositionProtectionPath/)
    assert.match(source, /executePositionProtectionUpdate/)
    assert.match(source, /positions\.manageProtectionOrders/)
    assert.match(source, /canUseLegacyPositionProtection\(position\)/)
    assert.doesNotMatch(source, /await updatePositionProtection\(editingPosition, result\.payload\)/)
  })
})

function makePosition(patch: Partial<PositionResponse> = {}): PositionResponse {
  return {
    id: 'position-1',
    symbol: 'EURUSD',
    side: 'BUY',
    lots: 1,
    openPrice: 1.1,
    currentPrice: 1.11,
    floatingPnl: 100,
    realizedPnl: 0,
    marginHeld: 1000,
    status: 'OPEN',
    ...patch
  }
}
