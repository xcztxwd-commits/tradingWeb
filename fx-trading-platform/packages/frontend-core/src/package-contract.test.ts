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

  it('adds only the market runtime dependencies required by the extracted implementation', () => {
    assert.deepEqual(packageJson.dependencies, {
      '@fx-platform/shared-types': '0.1.0',
      '@stomp/stompjs': '^7.3.0'
    })
    assert.equal(packageJson.peerDependencies.react, '^19.0.0')
    assert.equal(packageJson.devDependencies.react, '^19.0.0')
    assert.equal(packageJson.devDependencies['@types/react'], '^19.0.0')
  })

  it('has a public source entry point', () => {
    const indexSource = readFileSync(resolve(packageRoot, 'src/index.ts'), 'utf8')
    assert.match(indexSource, /export \* from '\.\/api\/index\.ts'/u)
    assert.match(indexSource, /export \* from '\.\/auth\/index\.ts'/u)
    assert.match(indexSource, /export \* from '\.\/models\/index\.ts'/u)
    assert.match(indexSource, /export \* from '\.\/storage\/index\.ts'/u)
    assert.match(indexSource, /export \* from '\.\/market\/index\.ts'/u)
    assert.match(indexSource, /export \* from '\.\/account\/index\.ts'/u)
  })
})
