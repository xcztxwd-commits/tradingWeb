import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { describe, it } from 'node:test'

const source = readFileSync(new URL('./usePerpetualReference.ts', import.meta.url), 'utf8')

describe('usePerpetualReference request identity', () => {
  it('depends on stable provider/source primitives instead of the per-tick source object', () => {
    assert.match(source, /const expectedSourceRef = useRef\(expectedSource\)/)
    assert.match(source, /expectedSourceRef\.current = expectedSource/)
    assert.match(source, /const expectedSourceKey = [^\n]+providerCode[^\n]+providerSymbol[^\n]+sourceMode/)
    assert.match(source, /toPerpetualReferenceView\(response, expectedSourceRef\.current\)/)
    assert.match(source, /\[enabled, expectedSourceKey, symbol\]/)
    assert.doesNotMatch(source, /\[enabled, expectedSource, symbol\]/)
  })
})
