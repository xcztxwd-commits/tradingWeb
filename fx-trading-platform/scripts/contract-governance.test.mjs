import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { describe, it } from 'node:test'

const fromRoot = (path) => new URL(`../${path}`, import.meta.url)
const text = (path) => readFileSync(fromRoot(path), 'utf8')
const json = (path) => JSON.parse(text(path))

describe('contract governance workspace wiring', () => {
  it('defines contract export, generation, check, and CI scripts', () => {
    const pkg = json('package.json')

    assert.equal(pkg.scripts['contract:export'], 'node scripts/export-openapi.mjs')
    assert.equal(pkg.scripts['contract:generate'], 'node scripts/generate-openapi-types.mjs')
    assert.equal(pkg.scripts['contract:check'], 'node scripts/generate-openapi-types.mjs --check')
    assert.match(pkg.scripts['contract:ci'], /contract:export/)
    assert.match(pkg.scripts['contract:ci'], /contract:check/)
    assert.match(pkg.scripts['contract:ci'], /web:build/)
    assert.match(pkg.scripts['contract:ci'], /admin:build/)
  })

  it('shares generated OpenAPI types and error-code helpers with both frontends', () => {
    const sharedIndex = text('packages/shared-types/src/index.ts')

    assert.match(sharedIndex, /generated\/openapi/)
    assert.match(sharedIndex, /ApiErrorCode/)
    assert.match(text('apps/web/src/services/authApi.ts'), /@fx-platform\/shared-types/)
    assert.match(text('apps/admin/src/types.ts'), /@fx-platform\/shared-types/)
    assert.match(text('apps/web/src/services/apiClient.ts'), /friendlyApiErrorMessage/)
    assert.match(text('apps/admin/src/services/apiClient.ts'), /friendlyApiErrorMessage/)
  })

  it('has a CI workflow for the full API contract chain', () => {
    assert.equal(existsSync(fromRoot('../.github/workflows/fx-contract.yml')), true)

    const workflow = text('../.github/workflows/fx-contract.yml')
    for (const command of [
      'npm run contract:export',
      'npm run contract:check',
      'npm run web:test',
      'npm run web:build',
      'npm run admin:build',
      'npm run smoke:backend'
    ]) {
      assert.match(workflow, new RegExp(command.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')))
    }
  })
})
