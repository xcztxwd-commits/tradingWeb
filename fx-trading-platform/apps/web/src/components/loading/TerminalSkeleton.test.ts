import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const componentPath = join(currentDir, 'TerminalSkeleton.tsx')
const stylesPath = join(currentDir, 'TerminalSkeleton.module.css')

describe('shared terminal skeleton components', () => {
  it('exports order book and table skeleton surfaces from shared components', () => {
    assert.equal(existsSync(componentPath), true)

    const source = readFileSync(componentPath, 'utf8')
    assert.match(source, /export function OrderBookSkeleton/)
    assert.match(source, /export function TableSkeleton/)
    assert.match(source, /role="status"/)
    assert.match(source, /aria-live="polite"/)
  })

  it('keeps skeleton styling outside page-owned trading modules', () => {
    assert.equal(existsSync(stylesPath), true)

    const styles = readFileSync(stylesPath, 'utf8')
    assert.match(styles, /prefers-reduced-motion:\s*reduce/)
    assert.match(styles, /--terminal-skeleton-surface/)
    assert.match(styles, /min-height/)
    assert.match(styles, /@keyframes terminal-skeleton-scan/)
  })
})
