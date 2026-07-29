import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

import { resolveHomeAuthVariant } from './homeRouteModel.ts'

const currentDir = dirname(fileURLToPath(import.meta.url))
const webSrc = resolve(currentDir, '../..')
const routeSource = readFileSync(join(currentDir, 'HomeRoute.tsx'), 'utf8')
const controllerSource = readFileSync(join(currentDir, 'useHomeRouteController.ts'), 'utf8')
const pcSource = readFileSync(join(webSrc, 'pc', 'pages', 'home', 'PcHomePage.tsx'), 'utf8')
const mobileSource = readFileSync(join(webSrc, 'mobile', 'pages', 'home', 'MobileHomePage.tsx'), 'utf8')
const contentSource = readFileSync(join(webSrc, 'shared-widgets', 'home', 'HomeContent.tsx'), 'utf8')
const contentStyles = readFileSync(join(webSrc, 'shared-widgets', 'home', 'HomeContent.module.css'), 'utf8')

describe('home route platform contract', () => {
  it('keeps auth variants deterministic without reading storage in either view', () => {
    assert.equal(resolveHomeAuthVariant(null, null), 'guest')
    assert.equal(resolveHomeAuthVariant('token', null), 'authenticated_unverified')
    assert.equal(resolveHomeAuthVariant('token', 'PENDING'), 'authenticated_unverified')
    assert.equal(resolveHomeAuthVariant('token', 'APPROVED'), 'authenticated_verified')

    assert.doesNotMatch(`${pcSource}\n${mobileSource}`, /localStorage|readStoredAuthToken|getHomeCounters/)
  })

  it('owns one auth and counter controller above independently lazy platform views', () => {
    assert.match(routeSource, /const PcHomePage = lazy\(\(\) =>\s*import\('\.\.\/\.\.\/pc\/pages\/home\/PcHomePage'\)/)
    assert.match(routeSource, /const MobileHomePage = lazy\(\(\) =>\s*import\('\.\.\/\.\.\/mobile\/pages\/home\/MobileHomePage'\)/)
    assert.match(routeSource, /const model = useHomeRouteController\(\)/)
    assert.match(routeSource, /<PlatformView[\s\S]*model=\{model\}[\s\S]*pc=\{PcHomePage\}[\s\S]*mobile=\{MobileHomePage\}/)
    assert.doesNotMatch(routeSource, /^import .*\/(?:pc|mobile)\//m)

    assert.match(controllerSource, /getHomeCounters/)
    assert.match(controllerSource, /authSessionChangedEvent/)
    assert.match(controllerSource, /readStoredAuthToken/)
    assert.match(controllerSource, /setInterval/)
    assert.match(controllerSource, /clearInterval/)
    assert.match(controllerSource, /status:\s*'loading'/)
    assert.match(controllerSource, /status:\s*'error'/)
  })

  it('renders the preserved content contract through distinct PC and Mobile roots', () => {
    assert.match(pcSource, /data-platform-view="pc"/)
    assert.match(mobileSource, /data-platform-view="mobile"/)
    assert.match(pcSource, /<HomeContent model=\{model\} platform="pc"/)
    assert.match(mobileSource, /<HomeContent model=\{model\} platform="mobile"/)

    for (const widget of [
      'HomeHeroGuest',
      'HomeHeroUnverified',
      'HomeHeroVerified',
      'MarketPreviewPanel',
      'NewsPreviewPanel',
      'TrustAwardsStrip',
      'HomeSupportSections'
    ]) {
      assert.match(contentSource, new RegExp(widget))
    }
    for (const target of ['/register', '/markets', '/trading', '/account/security/kyc', '/account/assets']) {
      const widgetSources = readHomeWidgetSources()
      assert.match(widgetSources, new RegExp(escapeRegExp(target)), `missing ${target}`)
    }
    assert.match(contentStyles, /\.mobile\s+/)
    assert.doesNotMatch(contentStyles, /@media\s*\(max-width:\s*(?:760|768)px\)/)
  })

  it('moves AssetMark once to shared widget ownership', () => {
    assert.equal(existsSync(join(webSrc, 'shared-widgets', 'asset', 'AssetMark.tsx')), true)
    assert.equal(existsSync(join(webSrc, 'shared-widgets', 'asset', 'assetMarkModel.ts')), true)
    assert.equal(existsSync(join(webSrc, 'components', 'asset', 'AssetMark.tsx')), false)
  })
})

function readHomeWidgetSources() {
  const directory = join(webSrc, 'shared-widgets', 'home')
  return [
    'HomeHeroGuest.tsx',
    'HomeHeroUnverified.tsx',
    'HomeHeroVerified.tsx',
    'HomeSupportSections.tsx'
  ].map((file) => readFileSync(join(directory, file), 'utf8')).join('\n')
}

function escapeRegExp(value: string) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}
