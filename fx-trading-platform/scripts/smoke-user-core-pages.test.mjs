import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const scriptsDir = dirname(fileURLToPath(import.meta.url))
const projectRoot = join(scriptsDir, '..')
const packageJson = JSON.parse(readFileSync(join(projectRoot, 'package.json'), 'utf8'))
const scriptPath = join(scriptsDir, 'smoke-user-core-pages.mjs')

describe('user core pages smoke command', () => {
  it('exposes a runnable smoke script', () => {
    assert.equal(packageJson.scripts['smoke:user-core-pages'], 'node scripts/smoke-user-core-pages.mjs')
    assert.equal(existsSync(scriptPath), true)
  })

  it('starts Vite on the configured web base URL instead of hard-coding the default port', () => {
    const source = readFileSync(scriptPath, 'utf8')

    assert.match(source, /const webUrl = new URL\(webBaseUrl\)/)
    assert.match(source, /const webHost = webUrl\.hostname \|\| '127\.0\.0\.1'/)
    assert.match(source, /const webPort = webUrl\.port \|\| '5173'/)
    assert.doesNotMatch(source, /--port 5173/)
  })

  it('writes the complete frontend auth session before visiting protected pages', () => {
    const source = readFileSync(scriptPath, 'utf8')

    assert.match(source, /refreshToken: auth\.refreshToken/)
    assert.match(source, /setAuthToken\(page, context\.accessToken, context\.refreshToken\)/)
    assert.match(source, /async function setAuthToken\(page, token, refreshToken\)/)
    assert.match(source, /localStorage\.setItem\('fx-platform-auth-refresh-token', refreshToken\)/)
    assert.match(source, /window\.dispatchEvent\(new Event\('fx-platform-auth-session-changed'\)\)/)
  })

  it('points the spawned frontend at the same backend API used for data seeding', () => {
    const source = readFileSync(scriptPath, 'utf8')

    assert.match(source, /VITE_API_BASE_URL: apiBaseUrl/)
  })

  it('fails both market data sources when checking the markets error state', () => {
    const source = readFileSync(scriptPath, 'utf8')

    assert.match(source, /\{ route: '\/markets', fail: \['\/api\/market\/symbols', '\/api\/market\/binance\/overview-source'\] \}/)
    assert.match(source, /const failPaths = Array\.isArray\(check\.fail\) \? check\.fail : \[check\.fail\]/)
    assert.match(source, /failPaths\.find\(\(path\) => event\.request\.url\.includes\(path\)\)/)
  })

  it('opens the overview tab before checking the markets table', () => {
    const source = readFileSync(scriptPath, 'utf8')

    assert.match(source, /await openMarketsOverviewTab\(page\)/)
    assert.match(source, /async function openMarketsOverviewTab\(page\)/)
    assert.match(source, /await page\.waitForFunction\(\(\) => Boolean\(document\.querySelector\('\.market-shell__tabs button'\)\), 'markets tabs'\)/)
    assert.match(source, /button\.click\(\)/)
  })

  it('asserts the batched market quote request used by the web app', () => {
    const source = readFileSync(scriptPath, 'utf8')

    assert.match(source, /assertNetwork\(network, \['\/api\/market\/symbols', '\/api\/market\/quotes'\]\)/)
    assert.doesNotMatch(source, /'\/api\/market\/quotes\/'/)
  })

  it('uses a unique markets URL when collecting real market requests', () => {
    const source = readFileSync(scriptPath, 'utf8')

    assert.match(source, /await page\.navigate\(`\$\{webBaseUrl\}\/markets\?real=\$\{runId\}`\)/)
  })

  it('waits for the asynchronous market quote request before asserting network coverage', () => {
    const source = readFileSync(scriptPath, 'utf8')

    assert.match(source, /await waitFor\(\(\) => network\.requests\.some\(\(url\) => url\.includes\('\/api\/market\/quotes'\)\), 'markets quote request', 30000\)/)
  })

  it('waits for wallet fund order loading before asserting wallet network coverage', () => {
    const source = readFileSync(scriptPath, 'utf8')

    assert.match(source, /await waitFor\(\(\) => network\.requests\.some\(\(url\) => url\.includes\('\/api\/finance\/fund-orders'\)\), 'wallet fund orders request', 30000\)/)
  })

  it('submits the wallet funding form rather than another wallet form', () => {
    const source = readFileSync(scriptPath, 'utf8')

    assert.match(source, /const form = document\.querySelector\('#wallet-funding form'\)/)
    assert.match(source, /const submit = form\.querySelector\('button\[type="submit"\]'\)/)
  })
})
