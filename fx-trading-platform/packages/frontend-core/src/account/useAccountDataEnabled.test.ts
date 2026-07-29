import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const source = readFileSync(fileURLToPath(new URL('./useAccountData.ts', import.meta.url)), 'utf8')

describe('useAccountData enabled boundary', () => {
  it('does not start or retain a session subscription for disabled utility routes', () => {
    assert.match(source, /enabled\?: boolean/)
    assert.match(source, /enabled = true/)
    assert.match(source, /if \(!enabled\) return/)
    assert.match(source, /\}, \[controller, enabled\]\)/)
  })

  it('recreates a disposed controller when a reused route becomes enabled again', () => {
    assert.match(source, /useMemo/)
    assert.match(source, /createAccountDataController\(dependencies, \{ refreshMs \}\)[\s\S]*\[dependencies, enabled, refreshMs\]/)
  })
})
