import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { describe, it } from 'node:test'

const fromRoot = (path) => new URL(`../${path}`, import.meta.url)
const text = (path) => readFileSync(fromRoot(path), 'utf8')

describe('admin smoke command action-permission contract', () => {
  it('is exposed as the repo admin smoke command', () => {
    const pkg = JSON.parse(text('package.json'))

    assert.equal(pkg.scripts['smoke:admin'], 'node scripts/smoke-admin.mjs')
    assert.equal(existsSync(fromRoot('scripts/smoke-admin.mjs')), true)
  })

  it('seeds action authorities through RBAC buttons before protected admin writes', () => {
    const source = text('scripts/smoke-admin.mjs')
    const seedStep = "step('admin action authorities are seeded through RBAC buttons'"
    const firstProtectedWrite = "step('admin user management commands write and audit'"

    assert.match(source, /const ADMIN_ACTION_AUTHORITIES = \[/)
    for (const authority of [
      'market:symbol:create',
      'market:symbol:update',
      'market:symbol:disable',
      'market:data-provider:update',
      'finance:fund-order:approve',
      'finance:fund-order:reject',
      'finance:adjustment:create',
      'trading:order:cancel',
      'trading:position:force-close',
      'user:update',
      'user:disable',
      'user:force-logout'
    ]) {
      assert.match(source, new RegExp(`'${authority}'`))
    }
    assert(source.indexOf(seedStep) > -1, 'smoke must seed RBAC action authorities')
    assert(
      source.indexOf(seedStep) < source.indexOf(firstProtectedWrite),
      'action authorities must be seeded before protected admin writes'
    )
    assert.match(source, /for \(const authority of ADMIN_ACTION_AUTHORITIES\)/)
  })

  it('sends required confirmation text for high-risk admin operations', () => {
    const source = text('scripts/smoke-admin.mjs')

    assert.match(source, /confirmationText: 'CONFIRM_ADJUSTMENT'/)
  })

  it('selects a symbol that can serve order book data for market smoke', () => {
    const source = text('scripts/smoke-admin.mjs')

    assert.match(source, /item\.orderBookEnabled === true/)
    assert.match(source, /item\.quoteCurrency === 'USD'/)
    assert.doesNotMatch(source, /symbols\.find\(\(item\) => item\.enabled\) \?\? symbols\[0\]/)
  })
})
