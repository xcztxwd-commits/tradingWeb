import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const source = readFileSync(join(currentDir, 'ChartTopToolbar.tsx'), 'utf8')
const styles = readFileSync(join(currentDir, 'ChartTopToolbar.module.css'), 'utf8')

describe('ChartTopToolbar indicator menu', () => {
  it('places the fullscreen control in the fixed right toolbar actions', () => {
    assert.match(source, /fullscreenActive:\s*boolean/)
    assert.match(source, /onToggleFullscreen:\s*\(\)\s*=>\s*void/)
    assert.match(source, /<div className=\{styles\.toolbarActions\}>[\s\S]*styles\.fullscreenButton/)
    assert.match(source, /aria-pressed=\{fullscreenActive\}/)
    assert.match(styles, /\.toolbarActions\s*{[\s\S]*flex:\s*0\s+0\s+auto/)
    assert.match(styles, /\.toolbarActions\s*{[\s\S]*margin-right:\s*28px/)
    assert.match(styles, /\.fullscreenButton\s*{[\s\S]*width:\s*28px[\s\S]*height:\s*28px/)
    assert.doesNotMatch(styles, /\.fullscreenButton\s*{[^}]*position:\s*absolute/s)
  })

  it('keeps indicator choices folded behind one toolbar button', () => {
    assert.match(source, /indicatorMenuOpen/)
    assert.match(source, /indicatorConfigOptions/)
    assert.match(source, /chart\.technicalIndicators/)
    assert.doesNotMatch(source, /indicatorNames\.map/)
  })

  it('gives toolbar buttons and dropdowns visible motion-safe interaction states', () => {
    assert.match(styles, /\.group button:focus-visible/)
    assert.match(styles, /\.selectLabel:focus-within/)
    assert.match(styles, /\.intervalDropdown,\s*\.indicatorDropdown\s*{[\s\S]*animation:\s*toolbarDropdownIn/)
    assert.match(styles, /@keyframes toolbarDropdownIn/)
    assert.match(styles, /@media\s*\(prefers-reduced-motion:\s*reduce\)\s*{[\s\S]*\.intervalDropdown,\s*\.indicatorDropdown\s*{[\s\S]*animation:\s*none/)
  })

  it('keeps desktop dropdowns outside the toolbar scroll clipping context', () => {
    assert.match(styles, /\.toolbarScroller\s*{[\s\S]*overflow:\s*visible/)
    assert.match(styles, /@media\s*\(max-width:\s*760px\)\s*{[\s\S]*\.toolbarScroller\s*{[\s\S]*overflow-x:\s*auto/)
  })

  it('does not render the KLineCharts brand text in the chart toolbar', () => {
    assert.doesNotMatch(source, /KLineCharts/)
    assert.doesNotMatch(source, /styles\.status/)
  })

  it('closes the indicator dropdown when the user clicks outside it', () => {
    assert.match(source, /indicatorMenuRef/)
    assert.match(source, /pointerdown/)
    assert.match(source, /setIndicatorMenuOpen\(false\)/)
    assert.match(source, /indicatorMenuRef\.current\.contains\(event\.target as Node\)/)
  })
})
