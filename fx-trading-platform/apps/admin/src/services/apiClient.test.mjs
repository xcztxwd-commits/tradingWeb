import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const clientSource = readFileSync(join(currentDir, 'apiClient.ts'), 'utf8')

describe('admin api client error handling', () => {
  it('handles empty auth failures without surfacing JSON parser errors', () => {
    assert.match(clientSource, /response\.text\(\)/)
    assert.doesNotMatch(clientSource, /await response\.json\(\)/)
    assert.match(clientSource, /status === 401 \|\| status === 403/)
    assert.match(clientSource, /登录已过期或无管理员权限，请重新登录/)
  })

  it('keeps status and code on thrown API errors', () => {
    assert.match(clientSource, /class ApiClientError extends Error/)
    assert.match(clientSource, /readonly status: number/)
    assert.match(clientSource, /readonly code: string/)
    assert.match(clientSource, /FORBIDDEN/)
    assert.match(clientSource, /UNAUTHORIZED/)
  })
})
