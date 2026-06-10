import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const layoutSource = readSource('AdminLayout.tsx')
const featurePageSource = readSource('../pages/FeatureCrudPage.tsx')
const loginSource = readSource('../pages/LoginPage.tsx')
const dashboardSource = readSource('../pages/DashboardPage.tsx')
const pageUtilsSource = readSource('../pages/adminPageUtils.tsx')
const stylesSource = readSource('../styles.css')

function readSource(relativePath) {
  return readFileSync(join(currentDir, relativePath), 'utf8')
}

describe('professional admin UI redesign', () => {
  it('adds a more navigable admin shell without changing the route layout', () => {
    for (const token of [
      'admin-skip-link',
      'brand-copy',
      'brand-title',
      'brand-subtitle',
      'topbar-icon-button',
      'sidebar-collapse is-active'
    ]) {
      assert.ok(layoutSource.includes(token), `${token} is missing from AdminLayout.tsx`)
    }

    assert.ok(layoutSource.includes('<Outlet />'), 'shared admin layout must continue rendering child routes')
    assert.ok(layoutSource.includes('RouteTabs'), 'route tabs must remain in the shared shell')
  })

  it('adds professional CRUD metadata and semantic shared states', () => {
    for (const token of [
      'feature-page-header',
      'feature-page-kicker',
      'feature-page-metrics',
      'visibleColumns.length',
      'totalRows.toLocaleString()'
    ]) {
      assert.ok(featurePageSource.includes(token), `${token} is missing from FeatureCrudPage.tsx`)
    }

    for (const token of ['state-block loading', 'state-block error', 'state-block empty']) {
      assert.ok(pageUtilsSource.includes(token), `${token} is missing from adminPageUtils.tsx`)
    }
  })

  it('keeps login and dashboard UI ready for the new visual system', () => {
    for (const token of ['login-hero', 'login-brand-line', 'login-submit']) {
      assert.ok(loginSource.includes(token), `${token} is missing from LoginPage.tsx`)
    }

    for (const token of ['stat-icon', 'dashboard-status-strip']) {
      assert.ok(dashboardSource.includes(token), `${token} is missing from DashboardPage.tsx`)
    }
  })

  it('defines token-driven styling motion and accessibility safeguards', () => {
    for (const token of [
      '--admin-primary',
      '--admin-sidebar-bg',
      '--admin-motion-fast',
      '.admin-nav-link::before',
      '.topbar-icon-button:active',
      '.feature-table tbody tr:hover',
      '@keyframes adminModalIn',
      '@media (prefers-reduced-motion: reduce)'
    ]) {
      assert.ok(stylesSource.includes(token), `${token} is missing from styles.css`)
    }
  })
})
