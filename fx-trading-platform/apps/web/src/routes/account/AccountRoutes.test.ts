import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

import { getAccountRoutePath, needsAccountSession } from './accountRouteModel.ts'
import { defaultSettingsPreferences, normalizeSettingsPreferences } from './settingsPreferences.ts'

const currentDir = dirname(fileURLToPath(import.meta.url))
const webSrc = resolve(currentDir, '../..')
const routeSource = readFileSync(join(currentDir, 'AccountRoutes.tsx'), 'utf8')
const controllerSource = readFileSync(join(currentDir, 'useAccountRouteController.ts'), 'utf8')
const contentSource = readFileSync(join(webSrc, 'shared-widgets', 'account', 'AccountRouteContent.tsx'), 'utf8')
const pcDataSource = readFileSync(join(webSrc, 'pc', 'pages', 'account', 'PcAccountDataCollection.tsx'), 'utf8')
const mobileDataSource = readFileSync(join(webSrc, 'mobile', 'pages', 'account', 'MobileAccountDataCollection.tsx'), 'utf8')

const contracts = [
  ['dashboard', '/dashboard', 'DashboardPage'],
  ['overview', '/account/overview', 'AccountOverviewPage'],
  ['assets', '/account/assets', 'AccountAssetsPage'],
  ['funding-records', '/account/orders/funding', 'FundingRecordsPage'],
  ['trade-records', '/account/orders/trades', 'TradeRecordsPage'],
  ['kyc', '/account/security/kyc', 'KycPage'],
  ['account-settings', '/account/settings', 'AccountSettingsPage'],
  ['security', '/security', 'SecurityCenterPage'],
  ['settings', '/settings', 'SettingsPage']
] as const
const platformSources = readPlatformSources()

describe('account route platform contract', () => {
  it('keeps all nine URL contracts deterministic', () => {
    for (const [mode, path] of contracts) assert.equal(getAccountRoutePath(mode), path)
    assert.equal(needsAccountSession('dashboard'), true)
    assert.equal(needsAccountSession('overview'), true)
    assert.equal(needsAccountSession('settings'), false)
    assert.equal(needsAccountSession('security'), false)
  })

  it('declares independent lazy PC and Mobile modules for every route', () => {
    for (const [mode, , suffix] of contracts) {
      const pcName = `Pc${suffix}`
      const mobileName = `Mobile${suffix}`
      assert.match(routeSource, new RegExp(`const ${pcName} = lazy`))
      assert.match(routeSource, new RegExp(`const ${mobileName} = lazy`))
      assert.match(routeSource, new RegExp(`'${mode}'`))
      assert.match(platformSources, new RegExp(`data-platform-view="pc"[\\s\\S]*expectedMode="${mode}"`))
      assert.match(platformSources, new RegExp(`data-platform-view="mobile"[\\s\\S]*expectedMode="${mode}"`))
    }
    assert.match(routeSource, /const model = useAccountRouteController\(mode\)/)
    assert.match(routeSource, /<PlatformView[\s\S]*model=\{model\}/)
    assert.doesNotMatch(routeSource, /^import .*\/(?:pc|mobile)\//m)
  })

  it('owns session, funding, filter and settings state above both platform views', () => {
    assert.match(controllerSource, /useTranslatedAccountData\(needsAccountSession\(mode\)\)/)
    assert.match(controllerSource, /getFundOrders/)
    assert.match(controllerSource, /createFundOrder/)
    assert.match(controllerSource, /submissionRef\.current/)
    assert.match(controllerSource, /statusFilter/)
    assert.match(controllerSource, /tradeOrderTab/)
    assert.match(controllerSource, /loadSettingsPreferences/)
    assert.match(controllerSource, /saveSettingsPreferences/)
    assert.match(controllerSource, /loginPath:\s*`\/login\?redirect=\$\{getAccountRoutePath\(mode\)\}`/)

    assert.doesNotMatch(`${contentSource}\n${platformSources}`, /getFundOrders|createFundOrder|localStorage|useTranslatedAccountData/)
  })

  it('uses a table only in PC and a card list only in Mobile', () => {
    assert.match(pcDataSource, /DataTable/)
    assert.doesNotMatch(pcDataSource, /DataCardList/)
    assert.match(mobileDataSource, /DataCardList/)
    assert.doesNotMatch(mobileDataSource, /<DataTable/)
    assert.match(contentSource, /renderDataCollection/)
  })

  it('normalizes persisted settings without changing the storage contract', () => {
    const normalized = normalizeSettingsPreferences({
      tableDensity: 'compact',
      requireOrderConfirm: false,
      terminalLayout: 'unsupported'
    })
    assert.equal(normalized.tableDensity, 'compact')
    assert.equal(normalized.requireOrderConfirm, false)
    assert.equal(normalized.terminalLayout, defaultSettingsPreferences.terminalLayout)
    assert.equal(normalized.mobileTableMode, defaultSettingsPreferences.mobileTableMode)
  })
})

function readPlatformSources() {
  return contracts.flatMap(([, , suffix]) => [
    readFileSync(join(webSrc, 'pc', 'pages', 'account', `Pc${suffix}.tsx`), 'utf8'),
    readFileSync(join(webSrc, 'mobile', 'pages', 'account', `Mobile${suffix}.tsx`), 'utf8')
  ]).join('\n')
}
