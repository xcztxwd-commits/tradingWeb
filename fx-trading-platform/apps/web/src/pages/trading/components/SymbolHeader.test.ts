import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const source = readFileSync(join(currentDir, 'SymbolHeader.tsx'), 'utf8')
const styles = readFileSync(join(currentDir, 'SymbolHeader.module.css'), 'utf8')

describe('OKX-style symbol header', () => {
  it('shows spot trading mode, cash mode, and richer 24h market metrics', () => {
    assert.match(source, /modeStrip/)
    assert.match(source, /trading\.spot/)
    assert.match(source, /trading\.cash/)
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
