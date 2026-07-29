import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const engagementDir = dirname(fileURLToPath(import.meta.url))
const srcDir = join(engagementDir, '..')
const appSource = readFileSync(join(srcDir, 'app', 'App.tsx'), 'utf8')
const shellSource = readFileSync(join(srcDir, 'app', 'AppShell.tsx'), 'utf8')
const shellModelSource = readFileSync(join(srcDir, 'app', 'shell', 'shellChromeModel.ts'), 'utf8')
const navigationSource = readFileSync(join(srcDir, 'app', 'navigation.ts'), 'utf8')
const pcSource = readFileSync(join(srcDir, 'pc', 'shell', 'PcShellChrome.tsx'), 'utf8')
const mobileSource = readFileSync(join(srcDir, 'mobile', 'shell', 'MobileShellChrome.tsx'), 'utf8')
const bellPath = join(srcDir, 'app', 'components', 'NotificationBell.tsx')
const bellSource = existsSync(bellPath) ? readFileSync(bellPath, 'utf8') : ''

describe('persistent engagement application wiring', () => {
  it('owns one runtime above PlatformView and exposes its stores with React subscriptions', () => {
    assert.match(shellSource, /createAppEngagementRuntime/)
    assert.match(shellSource, /useState\(\(\) => createAppEngagementRuntime/)
    assert.match(shellSource, /useSyncExternalStore\(\s*engagement\.popup\.subscribe/)
    assert.match(shellSource, /useSyncExternalStore\(\s*engagement\.messages\.subscribe/)
    assert.ok(shellSource.indexOf('createAppEngagementRuntime') < shellSource.indexOf('<PlatformView'))
    assert.match(shellSource, /<MessageCenterRuntimeProvider[\s\S]*runtime=\{engagement\.messages\}[\s\S]*authenticated=\{session\.authenticated\}/)
  })

  it('separates login, route, device, focus and critical-overlay signals', () => {
    assert.match(shellSource, /resolveEngagementPageKey\(location\.pathname\)/)
    assert.match(shellSource, /!isAuthRoute/)
    assert.match(shellSource, /engagement\.connect\(/)
    assert.match(shellSource, /engagement\.routeChanged\(pageKey\)/)
    assert.match(shellSource, /engagement\.resize\(deviceClass === 'pc' \? 'PC' : 'MOBILE'\)/)
    assert.match(shellSource, /window\.addEventListener\('focus'/)
    assert.match(shellSource, /subscribeDialogOverlay/)
    assert.match(shellSource, /getCriticalDialogOpen/)
    assert.match(shellSource, /useLayoutEffect\(\(\) => \{[\s\S]*engagement\.criticalModalChanged/)
    assert.match(shellSource, /engagement\.criticalModalChanged\(engagementEnabled && getCriticalDialogOpen\(\)\)/)
  })

  it('keeps auth pages popup-free and renders the one responsive shared popup elsewhere', () => {
    assert.match(shellSource, /engagementEnabled\s*=\s*Boolean\([\s\S]*!isAuthRoute/)
    assert.match(shellSource, /engagementEnabled\s*&&\s*<EngagementPopup/)
    assert.doesNotMatch(shellSource, /PcEngagementPopup|MobileEngagementPopup/)
  })

  it('registers the canonical messages route and keeps mobile primary navigation at five items', () => {
    assert.match(appSource, /import \{ MessagesRoute \} from '\.\.\/routes\/messages\/MessagesRoute'/)
    assert.match(appSource, /<Route path="\/messages" element=\{<MessagesRoute \/>\} \/>/)
    const mobileItems = navigationSource.slice(navigationSource.indexOf('export const mobileNavItems'))
    assert.equal([...mobileItems.matchAll(/to:\s*'/g)].length, 5)
  })

  it('shows a recent-message desktop menu and a direct mobile messages link', () => {
    assert.equal(existsSync(bellPath), true)
    assert.match(shellModelSource, /unreadCount:\s*number/)
    assert.match(shellModelSource, /recentMessages:/)
    assert.match(pcSource, /<NotificationBell[\s\S]*mode="menu"/)
    assert.match(mobileSource, /<NotificationBell[\s\S]*mode="link"/)
    assert.match(bellSource, /to="\/messages"/)
    assert.match(bellSource, /model\.recentMessages\.map/)
    assert.match(bellSource, /model\.unreadCount/)
  })
})
