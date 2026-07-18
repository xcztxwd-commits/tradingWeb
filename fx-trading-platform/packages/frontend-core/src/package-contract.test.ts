import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { describe, it } from 'node:test'
import { fileURLToPath } from 'node:url'

const packageRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const packageJson = JSON.parse(readFileSync(resolve(packageRoot, 'package.json'), 'utf8'))

describe('@fx-platform/frontend-core package contract', () => {
  it('exposes a private source workspace with independent gates', () => {
    assert.equal(packageJson.name, '@fx-platform/frontend-core')
    assert.equal(packageJson.private, true)
    assert.equal(packageJson.type, 'module')
    assert.deepEqual(packageJson.exports['.'], {
      types: './src/index.ts',
      import: './src/index.ts'
    })
    assert.equal(packageJson.scripts.test, 'node --test "src/**/*.test.ts"')
    assert.equal(packageJson.scripts.typecheck, 'tsc --noEmit -p tsconfig.json')
  })

  it('starts with shared-types as its only runtime dependency', () => {
    assert.deepEqual(packageJson.dependencies, {
      '@fx-platform/shared-types': '0.1.0'
    })
  })

  it('has a public source entry point', () => {
    const indexSource = readFileSync(resolve(packageRoot, 'src/index.ts'), 'utf8')
    assert.match(indexSource, /export\s*\{\s*\}/u)
  })
})
