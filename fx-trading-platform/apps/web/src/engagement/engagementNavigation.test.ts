import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import {
  prepareEngagementHtml,
  resolveEngagementPageKey,
  resolveEngagementRoute
} from './engagementNavigation.ts'

describe('engagement internal navigation', () => {
  it('maps business URLs to the frozen popup page keys and excludes auth routes', () => {
    const cases = [
      ['/', 'HOME'],
      ['/dashboard', 'DASHBOARD'],
      ['/markets', 'MARKETS'],
      ['/orders', 'ORDERS'],
      ['/positions', 'POSITIONS'],
      ['/wallet', 'WALLET'],
      ['/account/overview', 'ACCOUNT_OVERVIEW'],
      ['/account/assets', 'ACCOUNT_ASSETS'],
      ['/account/orders/funding', 'FUNDING_RECORDS'],
      ['/account/orders/trades', 'TRADE_RECORDS'],
      ['/account/security/kyc', 'KYC'],
      ['/account/settings', 'ACCOUNT_SETTINGS'],
      ['/security', 'SECURITY'],
      ['/settings', 'SETTINGS'],
      ['/trade/spot/BTCUSDT', 'TRADE_SPOT'],
      ['/trade/perpetual/BTCUSDT-PERP', 'TRADE_PERPETUAL'],
      ['/messages', 'MESSAGES']
    ] as const

    for (const [pathname, pageKey] of cases) {
      assert.equal(resolveEngagementPageKey(pathname), pageKey)
    }
    for (const pathname of ['/login', '/register', '/forgot-password', '/two-factor-help', '/unknown']) {
      assert.equal(resolveEngagementPageKey(pathname), null)
    }
  })

  it('maps every frozen route key to an implemented canonical route', () => {
    const routes = {
      HOME: '/',
      DASHBOARD: '/dashboard',
      MARKETS: '/markets',
      ORDERS: '/orders',
      POSITIONS: '/positions',
      WALLET: '/wallet',
      ACCOUNT_OVERVIEW: '/account/overview',
      ACCOUNT_ASSETS: '/account/assets',
      FUNDING_RECORDS: '/account/orders/funding',
      TRADE_RECORDS: '/account/orders/trades',
      KYC: '/account/security/kyc',
      ACCOUNT_SETTINGS: '/account/settings',
      SECURITY: '/security',
      SETTINGS: '/settings'
    } as const

    for (const [routeKey, path] of Object.entries(routes)) {
      assert.equal(resolveEngagementRoute(routeKey, {}), path)
    }
    assert.equal(resolveEngagementRoute('TRADE_SPOT', {}), '/trade/spot/BTCUSDT')
    assert.equal(resolveEngagementRoute('TRADE_SPOT', { symbol: 'ETHUSDT' }), '/trade/spot/ETHUSDT')
    assert.equal(resolveEngagementRoute('TRADE_PERPETUAL', {}), '/trade/perpetual/BTCUSDT-PERP')
    assert.equal(
      resolveEngagementRoute('TRADE_PERPETUAL', { symbol: 'ETHUSDT-PERP' }),
      '/trade/perpetual/ETHUSDT-PERP'
    )
    assert.equal(resolveEngagementRoute('MESSAGE_CENTER', {}), '/messages')
    assert.equal(resolveEngagementRoute('MESSAGE_CENTER', { filter: 'UNREAD' }), '/messages?filter=UNREAD')
    assert.equal(resolveEngagementRoute('MESSAGE_CENTER', { filter: 'ALL' }), '/messages?filter=ALL')
  })

  it('fails closed for unknown keys, arbitrary URLs and malformed route parameters', () => {
    for (const routeKey of [
      'https://evil.example', 'javascript:alert(1)', 'EXTERNAL', '',
      'toString', 'constructor', '__proto__'
    ]) {
      assert.equal(resolveEngagementRoute(routeKey, {}), null)
    }
    assert.equal(resolveEngagementRoute('HOME', { redirect: 'https://evil.example' }), null)
    assert.equal(resolveEngagementRoute('TRADE_SPOT', { symbol: 'javascript:alert(1)' }), null)
    assert.equal(resolveEngagementRoute('TRADE_PERPETUAL', { symbol: 'BTCUSDT' }), null)
    assert.equal(resolveEngagementRoute('MESSAGE_CENTER', { filter: 'DELETED' }), null)
    assert.equal(resolveEngagementRoute('MESSAGE_CENTER', { filter: 'ALL', next: '/admin' }), null)
  })
})

describe('engagement sanitized HTML adapter', () => {
  const assetId = '4e947142-81b6-4641-8d0d-78ca306f9f32'

  it('hydrates only UUID-backed platform images and preserves their asset identity', () => {
    assert.equal(
      prepareEngagementHtml(`<p>Hello</p><img data-asset-id="${assetId}" alt="Cover">`),
      `<p>Hello</p><img src="/api/public/engagement/assets/${assetId}" data-asset-id="${assetId}" alt="Cover">`
    )
  })

  it('rejects executable markup and external or data image sources', () => {
    const unsafe = [
      '<script>alert(1)</script>',
      '<img src="https://evil.example/track.png">',
      '<img src="data:image/png;base64,AAAA">',
      `<img data-asset-id="${assetId}" onerror="alert(1)">`,
      '<a href="https://evil.example">leave</a>',
      '<iframe src="/api/public/engagement/assets/file"></iframe>',
      '<img data-asset-id="not-a-uuid" alt="bad">'
    ]

    for (const html of unsafe) assert.equal(prepareEngagementHtml(html), null)
  })

  it('keeps backend internal-link metadata inert for delegated whitelist navigation', () => {
    const html = '<p><a data-route-key="MESSAGE_CENTER" data-route-params="{&quot;filter&quot;:&quot;UNREAD&quot;}">Unread</a></p>'
    assert.equal(prepareEngagementHtml(html), html)
  })
})
