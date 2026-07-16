import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const source = readFileSync(join(currentDir, 'SymbolHeader.tsx'), 'utf8')
const styles = readFileSync(join(currentDir, 'SymbolHeader.module.css'), 'utf8')
const desktopSource = readFileSync(join(currentDir, 'TradingDesktopView.tsx'), 'utf8')
const pageSource = readFileSync(join(currentDir, '..', 'TradingPage.tsx'), 'utf8')

describe('OKX-style symbol header', () => {
  it('shows product-aware trading and margin modes with richer 24h market metrics', () => {
    assert.match(source, /modeStrip/)
    assert.match(source, /product: TradingProduct/)
    assert.match(source, /product === 'perpetual'/)
    assert.match(source, /const productModeKey = perpetual \? 'trading\.perpetual' : 'trading\.spot'/)
    assert.match(source, /const marginModeKey = perpetual\s*\? marginMode === 'ISOLATED' \? 'trading\.isolatedMargin' : 'trading\.crossMargin'\s*: 'trading\.cash'/)
    assert.match(source, /<span>\{t\(productModeKey\)\}<\/span>/)
    assert.match(source, /<span>\{t\(marginModeKey\)\}<\/span>/)
    assert.match(pageSource, /const viewProps: TradingTerminalViewProps = \{[\s\S]*?\n    product,/)
    assert.match(desktopSource, /product=\{product\}/)
    assert.match(desktopSource, /marginMode=\{perpetualControls\.marginMode\}/)
    assert.match(source, /markets\.volume24h/)
    assert.match(source, /markets\.estimatedValue/)
  })

  it('keeps the dense metric row responsive without turning into a card stack', () => {
    assert.match(styles, /\.header\s*{[\s\S]*min-height:\s*58px/)
    assert.match(styles, /\.modeStrip\s*{[\s\S]*display:\s*inline-flex/)
    assert.match(styles, /\.metrics\s*{[\s\S]*grid-template-columns:\s*repeat\(6/)
    assert.match(styles, /@media \(max-width:\s*760px\)[\s\S]*\.modeStrip\s*{[\s\S]*display:\s*none/)
  })
})
