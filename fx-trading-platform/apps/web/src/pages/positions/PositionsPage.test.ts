import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const source = readFileSync(join(currentDir, 'PositionsPage.tsx'), 'utf8')

describe('positions close confirmation dialog', () => {
  it('supports keyboard dismissal and predictable focus lifecycle', () => {
    assert.match(source, /useRef<HTMLButtonElement \| null>\(null\)/)
    assert.match(source, /closeDialogCancelButtonRef\.current\?\.focus\(\)/)
    assert.match(source, /event\.key === 'Escape'/)
    assert.match(source, /closeDialogTriggerRef\.current\?\.focus\(\)/)
    assert.match(source, /event\.currentTarget/)
  })

  it('keeps outside click dismissal on the dialog layer', () => {
    assert.match(source, /onMouseDown=\{\(event\) => \{[\s\S]*event\.target === event\.currentTarget[\s\S]*dismissCloseDialog\(\)/)
  })
})
