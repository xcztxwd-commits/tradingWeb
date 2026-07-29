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

  it('clears an existing same-origin session without starting a duplicate markets load', () => {
    const source = readFileSync(scriptPath, 'utf8')
    const clearSessionSource = source.slice(
      source.indexOf('async function clearBrowserSession'),
      source.indexOf('async function setAuthToken')
    )

    assert.match(clearSessionSource, /const currentOrigin = await page\.evaluate\(\(\) => window\.location\.origin\)/)
    assert.match(clearSessionSource, /if \(currentOrigin !== webUrl\.origin\)/)
    assert.match(clearSessionSource, /\/login\?clear=\$\{runId\}/)
    assert.doesNotMatch(clearSessionSource, /\/markets\?clear=/)
  })

  it('fails both market data sources when checking the markets error state', () => {
    const source = readFileSync(scriptPath, 'utf8')

    assert.match(source, /\{ route: '\/markets', fail: \['\/api\/market\/symbols', '\/api\/market\/binance\/overview-source'\] \}/)
    assert.match(
      source,
      /await step\('error state is rendered when core API requests fail',[\s\S]*?for \(const check of checks\) \{\s*const failPaths = Array\.isArray\(check\.fail\) \? check\.fail : \[check\.fail\]\s*const failedCounts = Object\.fromEntries\(failPaths\.map\(\(path\) => \[path, 0\]\)\)\s*await setAuthToken\(page, context\.accessToken, context\.refreshToken\)\s*await withFetchHandler/u
    )
    assert.match(source, /failPaths\.find\(\(path\) => event\.request\.url\.includes\(path\)\)/)
    assert.match(source, /failedCounts\[matchedFailPath\] \+= 1/u)
    assert.match(source, /for \(const failPath of failPaths\) \{\s*assert\(failedCounts\[failPath\] > 0,/u)
    assert.doesNotMatch(source, /let failedCount = 0/u)
  })

  it('opens the overview tab before checking the markets table', () => {
    const source = readFileSync(scriptPath, 'utf8')

    assert.match(source, /await openMarketsOverviewTab\(page\)/)
    assert.match(source, /async function openMarketsOverviewTab\(page\)/)
    assert.match(source, /\[data-market-page-tab="overview"\]/)
    assert.doesNotMatch(source, /\[role="tablist"\]\[aria-label\] button\[aria-selected\]/)
    assert.match(source, /button\.click\(\)/)
  })

  it('waits for market data loading to settle before rendering the overview deck', () => {
    const source = readFileSync(scriptPath, 'utf8')

    assert.match(
      source,
      /async function openMarketsOverviewTab\(page\)[\s\S]*!document\.querySelector\('\[data-state-variant="loading"\]\[role="status"\]'\)/u
    )
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

  it('expects market actions to navigate to the canonical product trading route', () => {
    const source = readFileSync(scriptPath, 'utf8')

    assert.match(source, /\['spot', 'perpetual'\]\.includes\(routeParts\[2\]\)/u)
    assert.match(source, /Boolean\(routeParts\[3\]\)/u)
    assert.doesNotMatch(source, /window\.location\.pathname === '\/trading'/u)
  })

  it('opens a market through an enabled trading action', () => {
    const source = readFileSync(scriptPath, 'utf8')
    const marketsStepSource = source.slice(
      source.indexOf("await step('markets loads real symbols"),
      source.indexOf("await step('orders page shows real orders")
    )

    assert.match(marketsStepSource, /tbody button\[type="button"\]:not\(\[aria-pressed\]\):not\(\[disabled\]\)/u)
    assert.doesNotMatch(marketsStepSource, /find\(\(button\) => !button\.hasAttribute\('aria-pressed'\)\)/u)
  })

  it('waits for wallet fund order loading before asserting wallet network coverage', () => {
    const source = readFileSync(scriptPath, 'utf8')

    assert.match(source, /await waitFor\(\(\) => network\.requests\.some\(\(url\) => url\.includes\('\/api\/finance\/fund-orders'\)\), 'wallet fund orders request', 30000\)/)
  })

  it('submits the wallet funding form rather than another wallet form', () => {
    const source = readFileSync(scriptPath, 'utf8')

    assert.match(source, /const form = document\.querySelector\('#wallet-funding form'\)/)
    assert.match(source, /const amount = form\.querySelector\('input\[type="number"\]'\)/u)
    assert.match(source, /const noteInput = form\.querySelector\('input:not\(\[type\]\)'\)/u)
    assert.match(source, /const submit = form\.querySelector\('button\[type="submit"\]'\)/)
    assert.doesNotMatch(source, /input\[name="(?:amount|note)"\]/u)
  })

  it('edits an order through its labelled form without relying on removed input classes or names', () => {
    const source = readFileSync(scriptPath, 'utf8')

    assert.match(source, /const submit = document\.querySelector\('section\[aria-label\] form button\[type="submit"\]'\)/u)
    assert.match(source, /const form = submit\?\.form/u)
    assert.match(source, /const price = form\?\.querySelectorAll\('input'\)\[1\]/u)
    assert.match(source, /\}, context\.modifyPrice\)/u)
    assert.doesNotMatch(source, /form input\[name="price"\]/u)
  })

  it('confirms order cancellation through the accessible dialog', () => {
    const source = readFileSync(scriptPath, 'utf8')

    assert.match(source, /\[role="dialog"\]\[aria-modal="true"\]\[aria-labelledby="order-cancel-title"\]/u)
    assert.match(source, /const confirmCancel = dialog\?\.querySelector\('button\[type="button"\]'\)/u)
    assert.match(source, /confirmCancel\.click\(\)/u)
  })

  it('manages Perpetual protection through the canonical position action dialog', () => {
    const source = readFileSync(scriptPath, 'utf8')

    assert.match(source, /window\.location\.pathname === `\/trade\/perpetual\/\$\{symbol\}`/u)
    assert.match(source, /\[role="tablist"\]\[aria-label\] button\[role="tab"\]\[aria-controls\]/u)
    assert.match(source, /\[role="dialog"\]\[aria-modal="true"\]\[aria-labelledby\]/u)
    assert.match(source, /\[aria-label="Position take-profit and stop-loss protections"\]/u)
    assert.match(source, /const editor = dialog\?\.querySelector\('\[aria-label="Position take-profit and stop-loss protections"\]'\)/u)
    assert.match(source, /const quantityUnit = dialog\?\.querySelector\('form select'\)/u)
    assert.match(source, /Object\.getOwnPropertyDescriptor\(HTMLSelectElement\.prototype, 'value'\)\?\.set/u)
    assert.match(source, /setter\.call\(quantityUnit, 'BASE'\)/u)
    assert.match(source, /fieldset input\[inputmode="decimal"\]/u)
    assert.match(source, /addTakeProfit\.click\(\)[\s\S]*addStopLoss\.click\(\)/u)
    assert.match(source, /length >= 4/u)
    assert.match(source, /context\.protectionTakeProfitPrice, context\.protectionStopLossPrice, context\.protectionQuantity/u)
    assert.match(source, /response\.url\.includes\(`\/api\/trading\/positions\/\$\{context\.openPositionId\}\/protections`\)/u)
    assert.match(source, /\.filter\([\s\S]*\)\.length >= 2/u)
    assert.doesNotMatch(source, /form input\[name="stopLoss"\]/u)
  })

  it('follows the live labelled position action dialog contract', () => {
    const source = readFileSync(scriptPath, 'utf8')
    const positionStepSource = source.slice(
      source.indexOf("await step('positions page reaches canonical protection controls"),
      source.indexOf("await step('wallet page loads real balances")
    )

    assert.match(positionStepSource, /\[role="dialog"\]\[aria-modal="true"\]\[aria-labelledby\]/u)
    assert.doesNotMatch(positionStepSource, /aria-label="Position action"/u)
  })

  it('targets rendered UI by semantic state, identity, role, and form selectors', () => {
    const source = readFileSync(scriptPath, 'utf8')
    const selectors = [...source.matchAll(/querySelector(?:All)?\(\s*(['"])(.*?)\1\s*\)/gu)].map((match) => match[2])

    for (const selector of selectors) {
      assert.doesNotMatch(selector, /(^|[\s>+~])\.[_a-zA-Z]/u, `class selector is not stable in a CSS Modules UI: ${selector}`)
    }
    assert.match(source, /\[data-state-variant="login"\]/u)
    assert.match(source, /\[data-state-variant="loading"\]\[role="status"\]/u)
    assert.match(source, /\[data-state-variant="error"\]\[role="alert"\]/u)
    assert.match(source, /\[data-order-id\]/u)
    assert.match(source, /\[data-position-id\]/u)
    assert.match(source, /\[role="dialog"\]\[aria-modal="true"\]/u)
    assert.match(source, /\[data-market-page-tab="overview"\]/u)
  })

  it('seeds trades with a product allowed by demo execution', () => {
    const source = readFileSync(scriptPath, 'utf8')

    assert.match(source, /const perpetualSymbol = symbols\.find\(\(item\) => item\.tradable && item\.symbol === 'BTCUSDT-PERP'\)/u)
    assert.match(source, /const spotSymbol = symbols\.find\(\(item\) => item\.tradable && item\.symbol === 'BTCUSDT'\)/u)
    assert.doesNotMatch(source, /item\.enabled && item\.symbol === 'EURUSD'/)
    assert.match(source, /orderPayload\(account\.id, perpetualSymbol\.symbol, 'MARKET', 'seed-position', undefined, 'perpetual'\)/u)
    assert.match(source, /orderPayload\(account\.id, spotSymbol\.symbol, 'LIMIT', 'seed-pending', pendingPrice, 'spot'\)/u)
    assert.match(source, /quantityUnit:\s*'BASE'/)
    assert.match(source, /marginMode: product === 'spot' \? 'CASH' : 'CROSS'/u)
    assert.match(source, /\.\.\.\(product === 'spot' \? \{\} : \{ leverage: 10 \}\)/u)
    assert.match(source, /positionsPage\.items\.find/)
    assert.match(source, /modifyPrice:\s*\(Number\(pendingPrice\) - 0\.1\)\.toFixed\(1\)/u)
  })
})
