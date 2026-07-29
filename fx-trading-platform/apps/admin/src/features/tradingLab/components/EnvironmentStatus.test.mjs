import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const source = readFileSync(join(currentDir, 'EnvironmentStatus.tsx'), 'utf8')

describe('Trading Lab environment status', () => {
  it('reads status for VIEW while gating mutations with exact SUPER_ADMIN input', () => {
    assert.match(source, /getTradingLabEnvironment/)
    assert.match(source, /canSuperAdmin/)
    assert.match(source, /controlTradingLabEnvironment/)
    assert.match(source, /'start'/)
    assert.match(source, /'stop'/)
    assert.match(source, /'restart'/)
  })

  it('does not contain validation or Supervisor transport addresses', () => {
    assert.doesNotMatch(source, /validation-backend|Supervisor|18087|18088|\bfetch\s*\(/)
  })
})
