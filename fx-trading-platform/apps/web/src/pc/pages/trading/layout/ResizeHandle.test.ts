import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const source = readFileSync(join(currentDir, 'ResizeHandle.tsx'), 'utf8')

describe('ResizeHandle drag behavior', () => {
  it('keeps dragging through window pointer events after the pointer leaves the 8px handle', () => {
    assert.match(source, /window\.addEventListener\('pointermove'/)
    assert.match(source, /window\.addEventListener\('pointerup'/)
    assert.match(source, /window\.addEventListener\('pointercancel'/)
    assert.match(source, /window\.removeEventListener\('pointermove'/)
    assert.match(source, /window\.removeEventListener\('pointerup'/)
    assert.match(source, /window\.removeEventListener\('pointercancel'/)
  })
})
