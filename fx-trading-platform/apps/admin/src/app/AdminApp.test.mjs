import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const appSource = readSource('AdminApp.tsx')
const mainSource = readSource('../main.tsx')
const layoutSource = readSource('AdminLayout.tsx')
const guardSource = readSource('RequireAdmin.tsx')
const tradingLabIndexSource = readSource('../features/tradingLab/index.ts')

function readSource(relativePath) {
  const absolutePath = join(currentDir, relativePath)
  return existsSync(absolutePath) ? readFileSync(absolutePath, 'utf8') : ''
}

describe('admin app routing shell', () => {
  it('wraps the standalone admin app in BrowserRouter', () => {
    assert.match(mainSource, /import \{ BrowserRouter \} from 'react-router-dom'/)
    assert.match(mainSource, /<BrowserRouter>/)
    assert.match(mainSource, /<\/BrowserRouter>/)
  })

  it('declares protected WH screenshot routes and redirects the root route to dashboard', () => {
    assert.match(appSource, /import \{ Navigate, Route, Routes \} from 'react-router-dom'/)
    assert.match(appSource, /<Route path="\/login" element=\{<LoginPage \/>\} \/>/)
    assert.match(appSource, /<Route element=\{<RequireAdmin \/>\}>/)
    assert.match(appSource, /<Route path="\/" element=\{<Navigate to="\/dashboard" replace \/>\} \/>/)
    assert.match(appSource, /<Route path="\/dashboard" element=\{<DashboardPage \/>\} \/>/)

    for (const route of [
      ['system/users', 'system-users'],
      ['system/roles', 'system-roles'],
      ['system/departments', 'system-departments'],
      ['system/menus', 'system-menus'],
      ['system/posts', 'system-posts'],
      ['products/list', 'products'],
      ['products/categories', 'product-categories'],
      ['products/price-schedules', 'price-schedules'],
      ['finance/ledger', 'finance-ledger'],
      ['finance/recharge-orders', 'recharge-orders'],
      ['finance/withdrawal-orders', 'withdrawal-orders'],
      ['finance/payment-methods', 'payment-methods'],
      ['members/list', 'members'],
      ['members/payment-accounts', 'member-payment-accounts'],
      ['orders/history', 'order-history'],
      ['logs/verification-codes', 'verification-codes'],
      ['logs/request-logs', 'request-logs'],
      ['content/notices', 'notices'],
      ['content/news', 'news'],
      ['config/settings/site', 'settings-site'],
      ['config/settings/upload', 'settings-upload'],
      ['config/settings/sms', 'settings-sms'],
      ['config/settings/email', 'settings-email'],
      ['config/settings/footer', 'settings-footer']
    ]) {
      assert.ok(appSource.includes(`<Route path="/${route[0]}" element={<FeatureCrudPage pageKey="${route[1]}" />} />`))
    }
  })

  it('routes normal messages through their dedicated list and editor pages', () => {
    assert.match(appSource, /import \{ MemberNoticePage \} from '\.\.\/pages\/MemberNoticePage'/)
    assert.match(appSource, /import \{ MemberNoticeEditorPage \} from '\.\.\/pages\/MemberNoticeEditorPage'/)
    assert.match(appSource, /<Route path="\/content\/member-notices" element=\{<MemberNoticePage \/>\} \/>/)
    assert.match(appSource, /<Route path="\/content\/member-notices\/new" element=\{<MemberNoticeEditorPage \/>\} \/>/)
    assert.match(appSource, /<Route path="\/content\/member-notices\/:id\/edit" element=\{<MemberNoticeEditorPage \/>\} \/>/)
    assert.match(appSource, /<Route path="\/content\/messages" element=\{<Navigate to="\/content\/member-notices" replace \/>\} \/>/)
    assert.doesNotMatch(appSource, /import \{ MessagesPage \}/)
    assert.doesNotMatch(appSource, /pageKey="member-notices"/)
  })

  it('keeps legacy data pages reachable without making them the screenshot menu source', () => {
    assert.match(appSource, /<Route path="\/users" element=\{<Navigate to="\/system\/users" replace \/>\} \/>/)
    assert.match(appSource, /<Route path="\/accounts" element=\{<AccountsPage \/>\} \/>/)
    assert.match(appSource, /<Route path="\/trading\/orders" element=\{<OrdersPage \/>\} \/>/)
    assert.match(appSource, /<Route path="\/trading\/positions" element=\{<PositionsPage \/>\} \/>/)
    assert.match(appSource, /<Route path="\/trading\/trades" element=\{<TradesPage \/>\} \/>/)
    assert.match(appSource, /<Route path="\/legacy\/finance\/ledger" element=\{<LedgerPage \/>\} \/>/)
  })

  it('declares custom market data source routes for provider configuration', () => {
    assert.match(appSource, /import \{ DataProvidersPage \} from '..\/pages\/DataProvidersPage'/)
    assert.match(appSource, /import \{ ProviderInstrumentsPage \} from '..\/pages\/ProviderInstrumentsPage'/)
    assert.match(appSource, /import \{ SymbolDataBindingsPage \} from '..\/pages\/SymbolDataBindingsPage'/)
    assert.match(appSource, /<Route path="\/products\/data-providers" element=\{<DataProvidersPage \/>\} \/>/)
    assert.match(appSource, /<Route path="\/products\/provider-instruments" element=\{<ProviderInstrumentsPage \/>\} \/>/)
    assert.match(appSource, /<Route path="\/products\/symbol-bindings" element=\{<SymbolDataBindingsPage \/>\} \/>/)
  })

  it('uses grouped NavLink navigation, breadcrumb and tabs in a shared admin layout', () => {
    assert.match(layoutSource, /import \{ NavLink, Outlet, useNavigate \} from 'react-router-dom'/)
    assert.match(layoutSource, /adminMenuGroups/)
    assert.match(layoutSource, /<NavLink/)
    assert.match(layoutSource, /<Outlet \/>/)
    assert.match(layoutSource, /RouteTabs/)
    assert.match(layoutSource, /BreadcrumbTrail/)
  })

  it('protects admin routes with token-based redirects', () => {
    assert.match(guardSource, /import \{ Navigate, Outlet, useLocation \} from 'react-router-dom'/)
    assert.match(guardSource, /getValidAdminToken/)
    assert.match(guardSource, /<Navigate to="\/login"/)
    assert.match(guardSource, /<Outlet \/>/)
  })

  it('lazy-loads the Trading Lab route behind Suspense and the VIEW authority gate', () => {
    assert.match(appSource, /import \{[^}]*\blazy\b[^}]*\} from 'react'/)
    assert.match(appSource, /import \{[^}]*\bSuspense\b[^}]*\} from 'react'/)
    assert.match(appSource, /lazy\(\(\)\s*=>\s*import\('\.\.\/features\/tradingLab'\)/)
    assert.match(appSource, /default:\s*module\.TradingLabPage/)
    assert.doesNotMatch(appSource, /^import\s+.*\bTradingLabPage\b.*from/m)
    assert.match(appSource, /<Route path="\/trading\/lab"/)
    assert.match(appSource, /hasAdminAuthority\(['"]TRADING_LAB_VIEW['"]\)/)
    assert.match(appSource, /<Suspense\b/)
    assert.match(appSource, /<TradingLabPage\s*\/>/)
    assert.match(tradingLabIndexSource, /export \{\s*TradingLabPage\s*\} from ['"]\.\/TradingLabPage['"]/)
  })
})
