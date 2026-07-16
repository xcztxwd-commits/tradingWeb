import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const readSource = (relativePath) => readFileSync(join(currentDir, relativePath), 'utf8')
const appSource = readSource('AdminApp.tsx')
const menuSource = readSource('adminMenu.ts')
const accountsSource = readSource('../pages/AccountsPage.tsx')
const marketStatusSource = readSource('../pages/MarketStatusPage.tsx')
const pageUtilsSource = readSource('../pages/adminPageUtils.tsx')
const stylesSource = `${readSource('../styles.css')}\n${readSource('../components/HighRiskActionDialog.css')}`

describe('admin trading operations navigation', () => {
  it('routes account detail, funding settlements and funding configuration through protected admin pages', () => {
    for (const route of [
      '<Route path="/accounts/:accountId" element={<AccountDetailPage />} />',
      '<Route path="/trading/funding-settlements" element={<FundingSettlementsPage />} />',
      '<Route path="/market/funding-config" element={<FundingConfigPage />} />'
    ]) {
      assert.ok(appSource.includes(route), `${route} is missing`)
    }
  })

  it('keeps every P0 operations surface explicit in the primary admin menu', () => {
    for (const route of [
      '/accounts',
      '/trading/orders',
      '/trading/positions',
      '/trading/trades',
      '/trading/funding-settlements',
      '/market/funding-config',
      '/market/status',
      '/risk',
      '/audit-logs'
    ]) {
      assert.ok(menuSource.includes(`to: '${route}'`), `${route} is missing from adminMenu.ts`)
    }
    assert.match(menuSource, /pathname\.startsWith\(`\$\{entry\.to\}\/`\)/)
  })

  it('links account rows and market status to their operational detail surfaces', () => {
    assert.match(accountsSource, /Link/)
    assert.match(accountsSource, /\/accounts\/\$\{encodeURIComponent\(row\.id\)\}/)
    assert.match(marketStatusSource, /\/market\/funding-config/)
    assert.match(marketStatusSource, /sourceMode/)
    assert.match(marketStatusSource, /providerStatus/)
  })

  it('keeps wide operation tables and high-risk context reachable at 390px', () => {
    assert.match(stylesSource, /\.operation-table-scroll\s*\{[\s\S]*overflow-x:\s*auto/)
    assert.match(stylesSource, /@media \(max-width: 620px\)[\s\S]*\.operations-page/)
    assert.match(stylesSource, /@media \(max-width: 390px\)[\s\S]*\.high-risk-action-body[\s\S]*overflow-y:\s*auto/)
  })

  it('drops obsolete async page results when a route scope changes', () => {
    assert.match(pageUtilsSource, /generationRef/)
    assert.match(pageUtilsSource, /generation !== generationRef\.current/)
    assert.match(pageUtilsSource, /setData\(undefined\)/)
  })
})
