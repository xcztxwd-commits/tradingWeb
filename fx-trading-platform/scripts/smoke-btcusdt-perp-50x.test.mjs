import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const scriptsDir = dirname(fileURLToPath(import.meta.url))
const projectRoot = join(scriptsDir, '..')
const packageJson = JSON.parse(readFileSync(join(projectRoot, 'package.json'), 'utf8'))
const legacySource = readFileSync(join(scriptsDir, 'smoke-btcusdt-perp-50x.mjs'), 'utf8')

describe('retired BTCUSDT 50x smoke compatibility', () => {
  it('routes the old npm command to the canonical real USDT demo browser smoke', () => {
    assert.equal(packageJson.scripts['smoke:btcusdt-perp-50x'], 'node scripts/smoke-usdt-demo-browser.mjs')
    assert.match(legacySource, /smoke-usdt-demo-browser\.mjs/)
  })

  it('cannot mutate the canonical Spot symbol into a Perpetual fixture', () => {
    assert.doesNotMatch(legacySource, /ensureBtcusdtLinearPerpFixture/)
    assert.doesNotMatch(legacySource, /market\.symbols/)
    assert.doesNotMatch(legacySource, /runDbSql/)
  })
})
