import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const webSrc = resolve(currentDir, '../..')
const coreAccountDir = join(currentDir, '../../../../../packages/frontend-core/src/account')
const sharedWalletDir = join(webSrc, 'shared-widgets', 'wallet')

function readRequiredSource(name: string) {
  const path = join(sharedWalletDir, name)
  assert.equal(existsSync(path), true, `${name} must exist`)
  return readFileSync(path, 'utf8')
}

describe('wallet Spot and Perpetual operations', () => {
  it('keeps transfer direction, amount and source available balance parent-controlled', () => {
    const source = readRequiredSource('TransferDialog.tsx')

    assert.match(source, /direction: AccountTransferDirection/)
    assert.match(source, /amount: string/)
    assert.match(source, /available: Amount/)
    assert.match(source, /requestId: string/)
    assert.match(source, /onDirectionChange:/)
    assert.match(source, /onAmountChange:/)
    assert.match(source, /onConfirm:/)
    assert.match(source, /SPOT_TO_PERP/)
    assert.match(source, /PERP_TO_SPOT/)
    assert.match(source, /max=\{availableNumber\}/)
    assert.match(source, /amountNumber > availableNumber/)
  })

  it('prevents duplicate submissions and exposes operation errors without rewriting them', () => {
    const transfer = readRequiredSource('TransferDialog.tsx')
    const reset = readRequiredSource('DemoResetDialog.tsx')

    for (const source of [transfer, reset]) {
      assert.match(source, /pending: boolean/)
      assert.match(source, /disabled=\{[^}]*pending[^}]*\}/)
      assert.match(source, /role="alert"/)
      assert.match(source, /\{error\}/)
    }
    assert.match(transfer, /import \{ Dialog \} from '@fx-platform\/ui'/)
    assert.match(transfer, /pending=\{pending\}/)
    assert.match(reset, /aria-busy=\{pending\}/)
    assert.match(reset, /requestId: string/)
    assert.match(reset, /onConfirm:/)
  })

  it('uses Task 7 APIs, refreshes successful mutations and never creates a USDT_PERP mirror', () => {
    const wallet = [
      readFileSync(join(currentDir, 'useWalletRouteController.ts'), 'utf8'),
      readFileSync(join(sharedWalletDir, 'WalletRouteContent.tsx'), 'utf8')
    ].join('\n')
    const controller = readFileSync(join(coreAccountDir, 'useWalletController.ts'), 'utf8')
    const operations = readFileSync(join(coreAccountDir, 'accountOperations.ts'), 'utf8')

    assert.match(wallet, /useWalletController/)
    assert.match(controller, /runWalletTransfer/)
    assert.match(controller, /runWalletReset/)
    assert.match(controller, /transferInFlight/)
    assert.match(controller, /resetInFlight/)
    assert.match(operations, /transferDemoFunds/)
    assert.match(operations, /resetDemoAccount/)
    assert.match(wallet, /<TransferDialog/)
    assert.match(wallet, /<DemoResetDialog/)
    assert.match(controller, /crypto\.randomUUID\(\)/)
    assert.match(operations, /await dependencies\.refresh\(\)/)
    assert.doesNotMatch(wallet, /USDT_PERP/)
    assert.doesNotMatch(wallet, /AssetConversionPanel/)
    assert.doesNotMatch(wallet, /convertAsset/)
  })
})
