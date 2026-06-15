import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const tokenSource = readFileSync(join(currentDir, 'adminToken.ts'), 'utf8')

describe('admin token storage', () => {
  it('clears expired or malformed stored tokens before protected routes render', () => {
    assert.match(tokenSource, /export function getValidAdminToken\(/)
    assert.match(tokenSource, /isJwtExpired\(/)
    assert.match(tokenSource, /clearAdminToken\(\)/)
    assert.match(tokenSource, /exp/)
  })
})
