import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const scriptsDir = dirname(fileURLToPath(import.meta.url))
const projectRoot = join(scriptsDir, '..')
const packageJson = JSON.parse(readFileSync(join(projectRoot, 'package.json'), 'utf8'))
const scriptPath = join(scriptsDir, 'smoke-trading-login-gate.mjs')

describe('trading login gate smoke command', () => {
  it('exposes a runnable smoke script', () => {
    assert.equal(packageJson.scripts['smoke:trading-login-gate'], 'node scripts/smoke-trading-login-gate.mjs')
    assert.equal(existsSync(scriptPath), true)
  })

  it('starts Vite on the configured web base URL instead of hard-coding the default port', () => {
    const source = readFileSync(scriptPath, 'utf8')

    assert.match(source, /const webUrl = new URL\(webBaseUrl\)/)
    assert.match(source, /const webHost = webUrl\.hostname \|\| '127\.0\.0\.1'/)
    assert.match(source, /const webPort = webUrl\.port \|\| '5173'/)
    assert.doesNotMatch(source, /--port 5173/)
  })

  it('points the spawned frontend at the same backend API used for session probes', () => {
    const source = readFileSync(scriptPath, 'utf8')

    assert.match(source, /VITE_API_BASE_URL: apiBaseUrl/)
  })

  it('accepts localized trading login gate text in headless browsers', () => {
    const source = readFileSync(scriptPath, 'utf8')

    assert.match(source, /Log in to enable trading execution/)
    assert.match(source, /Public market mode/)
    assert.match(source, /Order book/)
    assert.match(source, /Go to login/)
    assert.match(source, /Close login prompt/)
    assert.match(source, /EURUSD/)
    assert.match(source, /button\.textContent\?\.trim\(\) === '登录'/)
  })
})
