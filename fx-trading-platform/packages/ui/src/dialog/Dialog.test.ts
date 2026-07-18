import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { describe, it } from 'node:test'
import { fileURLToPath } from 'node:url'

import { shouldCloseOverlay } from './overlayState.ts'

const currentDir = dirname(fileURLToPath(import.meta.url))
const projectRoot = resolve(currentDir, '../../../..')
const componentSource = readFileSync(resolve(currentDir, 'Dialog.tsx'), 'utf8')
const styles = readFileSync(resolve(currentDir, 'Dialog.module.css'), 'utf8')
const transferSource = readFileSync(resolve(projectRoot, 'apps/web/src/shared-widgets/wallet/TransferDialog.tsx'), 'utf8')
const orderSource = readFileSync(resolve(projectRoot, 'apps/web/src/shared-widgets/trading/order-form/OrderConfirmationDialog.tsx'), 'utf8')

describe('overlay close decisions', () => {
  it('allows configured Escape and backdrop closes', () => {
    assert.equal(shouldCloseOverlay({ source: 'escape' }), true)
    assert.equal(shouldCloseOverlay({ source: 'backdrop' }), true)
    assert.equal(shouldCloseOverlay({ source: 'escape', closeOnEscape: false }), false)
    assert.equal(shouldCloseOverlay({ source: 'backdrop', closeOnBackdrop: false }), false)
  })

  it('blocks every close source while a domain action is pending', () => {
    assert.equal(shouldCloseOverlay({ source: 'escape', pending: true }), false)
    assert.equal(shouldCloseOverlay({ source: 'backdrop', pending: true }), false)
  })
})

describe('Dialog component contract', () => {
  it('owns modal semantics, Escape, backdrop policy and focus restoration', () => {
    assert.match(componentSource, /export type DialogProps/u)
    assert.match(componentSource, /role="dialog"/u)
    assert.match(componentSource, /aria-modal="true"/u)
    assert.match(componentSource, /aria-labelledby=\{labelledBy\}/u)
    assert.match(componentSource, /event\.key === 'Escape'/u)
    assert.match(componentSource, /shouldCloseOverlay/u)
    assert.match(componentSource, /document\.activeElement/u)
    assert.match(componentSource, /\.focus\(\)/u)
    assert.doesNotMatch(componentSource, /react-i18next/u)
  })

  it('owns only generic overlay and panel visuals', () => {
    assert.match(styles, /\.layer/u)
    assert.match(styles, /\.backdrop/u)
    assert.match(styles, /\.panel/u)
    assert.match(styles, /@media \(prefers-reduced-motion:\s*reduce\)/u)
  })

  it('replaces duplicated modal behavior in transfer and order confirmation consumers', () => {
    for (const source of [transferSource, orderSource]) {
      assert.match(source, /import \{ Dialog \} from '@fx-platform\/ui'/u)
      assert.match(source, /<Dialog/u)
    }
    assert.doesNotMatch(orderSource, /addEventListener\('keydown'/u)
    assert.match(transferSource, /pending=\{pending\}/u)
  })
})
