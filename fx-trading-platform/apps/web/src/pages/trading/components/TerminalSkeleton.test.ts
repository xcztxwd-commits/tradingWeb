import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const componentPath = join(currentDir, 'TerminalSkeleton.tsx')

describe('terminal skeleton components', () => {
  it('keeps page skeleton imports as compatibility re-exports', () => {
    const source = readFileSync(componentPath, 'utf8')

    assert.match(source, /export \{ OrderBookSkeleton, TableSkeleton \}/)
    assert.match(source, /components\/loading\/TerminalSkeleton/)
  })
})
