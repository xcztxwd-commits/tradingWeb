import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { describe, it } from 'node:test'
import { fileURLToPath } from 'node:url'

const currentDir = dirname(fileURLToPath(import.meta.url))
const projectRoot = resolve(currentDir, '../../../..')
const componentSource = readFileSync(resolve(currentDir, 'Drawer.tsx'), 'utf8')
const styles = readFileSync(resolve(currentDir, 'Drawer.module.css'), 'utf8')
const adapterSource = readFileSync(resolve(projectRoot, 'apps/web/src/pages/trading/components/MobilePanels.tsx'), 'utf8')
const tradingSource = readFileSync(resolve(projectRoot, 'apps/web/src/pages/trading/TradingPage.tsx'), 'utf8')

describe('Drawer component contract', () => {
  it('owns open state, title semantics, side, backdrop, Escape and focus restoration', () => {
    assert.match(componentSource, /export type DrawerSide = 'left' \| 'right'/u)
    assert.match(componentSource, /open:\s*boolean/u)
    assert.match(componentSource, /title:\s*string/u)
    assert.match(componentSource, /side:\s*DrawerSide/u)
    assert.match(componentSource, /role="dialog"/u)
    assert.match(componentSource, /aria-labelledby=/u)
    assert.match(componentSource, /event\.key === 'Escape'/u)
    assert.match(componentSource, /shouldCloseOverlay/u)
    assert.match(componentSource, /document\.activeElement/u)
    assert.match(componentSource, /\.focus\(\)/u)
    assert.doesNotMatch(componentSource, /react-i18next/u)
  })

  it('owns left/right positioning, open transitions and reduced-motion behavior', () => {
    assert.match(styles, /\.left/u)
    assert.match(styles, /\.right/u)
    assert.match(styles, /\.layer\[data-open='true'\]/u)
    assert.match(styles, /@media \(prefers-reduced-motion:\s*reduce\)/u)
  })

  it('is used through the temporary trading adapter for both current drawers', () => {
    assert.match(adapterSource, /import \{ Drawer \} from '@fx-platform\/ui'/u)
    assert.match(adapterSource, /return \(\s*<Drawer/u)
    assert.ok((tradingSource.match(/<MobileDrawer/gu) ?? []).length >= 2)
    assert.doesNotMatch(adapterSource, /styles\.drawerLayer/u)
  })
})
