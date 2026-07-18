import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

import { selectPlatformComponent } from './platformSelection.ts'

const currentDir = dirname(fileURLToPath(import.meta.url))
const appDir = join(currentDir, '..')
const appShellSource = readFileSync(join(appDir, 'AppShell.tsx'), 'utf8')
const platformViewSource = readFileSync(join(currentDir, 'PlatformView.tsx'), 'utf8')
const tradingPageSource = readFileSync(join(appDir, '..', 'pages', 'trading', 'TradingPage.tsx'), 'utf8')
const pcShellSource = readFileSync(join(appDir, '..', 'pc', 'shell', 'PcShellChrome.tsx'), 'utf8')
const mobileShellSource = readFileSync(join(appDir, '..', 'mobile', 'shell', 'MobileShellChrome.tsx'), 'utf8')

describe('PlatformView', () => {
  it('selects and renders only the active platform component', () => {
    assert.equal(selectPlatformComponent('pc', PcProbe, MobileProbe), PcProbe)
    assert.equal(selectPlatformComponent('mobile', PcProbe, MobileProbe), MobileProbe)
    assert.match(platformViewSource, /const ActiveView = selectPlatformComponent\(deviceClass, pc, mobile\)/)
    assert.match(platformViewSource, /data-platform-view=\{deviceClass\}/)
    assert.match(platformViewSource, /<Suspense fallback=\{fallback\}>[\s\S]*<ActiveView model=\{model\} \/>/)
    assert.doesNotMatch(platformViewSource, /<pc\b|<mobile\b|display:\s*none/)
  })

  it('keeps shell session and route content above or outside the platform switch', () => {
    const platformCall = appShellSource.slice(
      appShellSource.indexOf('<PlatformView'),
      appShellSource.indexOf('/>', appShellSource.indexOf('<PlatformView')) + 2
    )

    assert.ok(appShellSource.indexOf('const [session, setSession]') < appShellSource.indexOf('<PlatformView'))
    assert.match(appShellSource, /<PlatformView[\s\S]*\/>[\s\S]*<main className="main-region">\{children\}<\/main>/)
    assert.doesNotMatch(platformCall, /main-region|children/)
    assert.match(pcShellSource, /data-platform-view="pc"/)
    assert.match(mobileShellSource, /data-platform-view="mobile"/)
  })

  it('keeps the trading controller stable while only its current view changes', () => {
    const controllerIndex = tradingPageSource.indexOf('useTradingRouteController({ product })')
    const deviceIndex = tradingPageSource.indexOf('useDeviceClass()')

    assert.ok(controllerIndex >= 0 && controllerIndex < deviceIndex)
    assert.match(tradingPageSource, /deviceClass === 'pc' \? <TradingDesktopView/)
    assert.match(tradingPageSource, /deviceClass === 'mobile' \? <TradingMobileView/)
    assert.doesNotMatch(tradingPageSource, /useMobileTerminalViewport|window\.innerWidth|matchMedia/)
  })
})

function PcProbe() { return null }
function MobileProbe() { return null }
