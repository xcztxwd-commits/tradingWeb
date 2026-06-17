import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const scriptsDir = dirname(fileURLToPath(import.meta.url))
const projectRoot = join(scriptsDir, '..')
const packageJson = JSON.parse(readFileSync(join(projectRoot, 'package.json'), 'utf8'))
const scriptPath = join(scriptsDir, 'smoke-real-trading-loop.mjs')

describe('real trading loop smoke command', () => {
  it('exposes a runnable smoke script', () => {
    assert.equal(packageJson.scripts['smoke:real-trading-loop'], 'node scripts/smoke-real-trading-loop.mjs')
    assert.equal(existsSync(scriptPath), true)
  })

  it('isolates TP/SL executor coverage from earlier net-position smoke state', () => {
    const source = readFileSync(scriptPath, 'utf8')

    assert.match(source, /step\('TP\/SL executor closes a protected position and writes ledger'/)
    assert.match(source, /const protectedAccount = await api\('\/api\/accounts\/demo'/)
    assert.match(source, /accountId: protectedAccount\.id/)
    assert.match(source, /positions\?accountId=\$\{encodeURIComponent\(protectedAccount\.id\)\}/)
    assert.match(source, /ledger\?accountId=\$\{encodeURIComponent\(protectedAccount\.id\)\}/)
    assert.match(source, /function orderPayload\(\{ accountId = account\.accountId,/)
    assert.doesNotMatch(source, /find\(\(item\) => item\.takeProfit !== null && item\.status === 'OPEN'\)/)
  })

  it('sends required confirmation text for admin force close', () => {
    const source = readFileSync(scriptPath, 'utf8')

    assert.match(source, /confirmationText: 'CONFIRM_FORCE_CLOSE'/)
  })
})
