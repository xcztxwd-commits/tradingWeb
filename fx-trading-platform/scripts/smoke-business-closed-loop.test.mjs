import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const scriptsDir = dirname(fileURLToPath(import.meta.url))
const projectRoot = join(scriptsDir, '..')
const packageJson = JSON.parse(readFileSync(join(projectRoot, 'package.json'), 'utf8'))
const scriptPath = join(scriptsDir, 'smoke-business-closed-loop.mjs')

describe('business closed-loop smoke command', () => {
  it('exposes a runnable smoke script', () => {
    assert.equal(packageJson.scripts['smoke:business-closed-loop'], 'node scripts/smoke-business-closed-loop.mjs')
    assert.equal(existsSync(scriptPath), true)
  })

  it('covers the complete register-to-marker business chain', () => {
    const source = readFileSync(scriptPath, 'utf8')
    const requiredSteps = [
      'backend health is up',
      'user can register and then log in',
      'wallet balance is available',
      'market order opens a current position',
      'current position closes into history',
      'ledger records hold release and pnl',
      'trading page renders a KLine marker for the traded order'
    ]

    for (const step of requiredSteps) {
      assert.match(source, new RegExp(`step\\('${escapeRegExp(step)}'`))
    }
    assert.match(source, /\/api\/auth\/register/)
    assert.match(source, /\/api\/auth\/login/)
    assert.match(source, /\/api\/accounts\/\$\{encodeURIComponent\(account\.accountId\)\}\/wallet-balances/)
    assert.match(source, /\/api\/trading\/positions\/\$\{encodeURIComponent\(openPosition\.positionId\)\}\/close/)
    assert.match(source, /\/api\/trading\/positions\/history/)
    assert.match(source, /data-trade-marker-overlay-count/)
  })

  it('starts Vite on the configured web base URL instead of hard-coding the default port', () => {
    const source = readFileSync(scriptPath, 'utf8')

    assert.match(source, /const webUrl = new URL\(webBaseUrl\)/)
    assert.match(source, /const webPort = webUrl\.port \|\| '5173'/)
    assert.doesNotMatch(source, /--port 5173/)
  })

  it('selects a tradable FX margin symbol with quote capability before requesting live quotes', () => {
    const source = readFileSync(scriptPath, 'utf8')

    assert.match(source, /item\.quoteEnabled/)
    assert.match(source, /item\.tradable/)
    assert.match(source, /item\.productType === 'FX_MARGIN'/)
    assert.doesNotMatch(source, /item\.enabled && item\.symbol === 'EURUSD'/)
  })

  it('runs the browser marker check through retryable browser candidates with diagnostics', () => {
    const source = readFileSync(scriptPath, 'utf8')

    assert.match(source, /withBrowserPage\('KLine marker browser verification'/)
    assert.match(source, /function browserCandidates\(\)/)
    assert.match(source, /BUSINESS_CLOSED_LOOP_BROWSER_ATTEMPTS/)
    assert.match(source, /Headless browser exited before CDP became ready \(executable=/)
    assert.match(source, /browser candidates failed/)
    assert.doesNotMatch(source, /const chrome = await launchChrome\(\)\s*\n\s*const page = await createCdpPage/)
  })
})

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}
