import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

import {
  getAuthSuccessPath,
  normalizeRegistrationIdentifier,
  resolveSafeAuthRedirect
} from './authRouteModel.ts'

const currentDir = dirname(fileURLToPath(import.meta.url))
const webSrc = resolve(currentDir, '../..')
const routeSource = readFileSync(join(currentDir, 'AuthRoute.tsx'), 'utf8')
const controllerSource = readFileSync(join(currentDir, 'useAuthRouteController.ts'), 'utf8')
const contentSource = readFileSync(join(webSrc, 'shared-widgets', 'auth', 'AuthPageContent.tsx'), 'utf8')
const viewSources = readPlatformViewSources()

describe('auth route platform contract', () => {
  it('preserves safe login redirects and registration identity normalization', () => {
    assert.equal(resolveSafeAuthRedirect(null), '/account/overview')
    assert.equal(resolveSafeAuthRedirect('https://example.test'), '/account/overview')
    assert.equal(resolveSafeAuthRedirect('//example.test'), '/account/overview')
    assert.equal(resolveSafeAuthRedirect('/wallet?asset=USDT'), '/wallet?asset=USDT')
    assert.equal(normalizeRegistrationIdentifier('email', '+60', ' user@example.test '), 'user@example.test')
    assert.equal(normalizeRegistrationIdentifier('phone', '+60', ' 123456 '), '+60123456')
    assert.equal(getAuthSuccessPath('login', '/positions'), '/positions')
    assert.equal(getAuthSuccessPath('register', '/positions'), '/account/overview')
  })

  it('declares an independent lazy PC and Mobile view for every auth URL', () => {
    const contracts = [
      ['login', 'PcLoginPage', 'MobileLoginPage'],
      ['register', 'PcRegisterPage', 'MobileRegisterPage'],
      ['forgot-password', 'PcForgotPasswordPage', 'MobileForgotPasswordPage'],
      ['two-factor-help', 'PcTwoFactorHelpPage', 'MobileTwoFactorHelpPage']
    ] as const

    for (const [mode, pcName, mobileName] of contracts) {
      assert.match(routeSource, new RegExp(`const ${pcName} = lazy`))
      assert.match(routeSource, new RegExp(`const ${mobileName} = lazy`))
      assert.match(routeSource, new RegExp(`'${mode}'`))
      assert.match(viewSources, new RegExp(`data-platform-view="pc"[\\s\\S]*expectedMode="${mode}"`))
      assert.match(viewSources, new RegExp(`data-platform-view="mobile"[\\s\\S]*expectedMode="${mode}"`))
    }

    assert.match(routeSource, /const model = useAuthRouteController\(mode\)/)
    assert.match(routeSource, /<PlatformView[\s\S]*model=\{model\}/)
    assert.doesNotMatch(routeSource, /^import .*\/(?:pc|mobile)\//m)
  })

  it('keeps API, token storage and redirect commands in one controller', () => {
    assert.match(controllerSource, /await login\(/)
    assert.match(controllerSource, /await register\(/)
    assert.match(controllerSource, /writeStoredAuthTokens/)
    assert.match(controllerSource, /fx-platform-user-email/)
    assert.match(controllerSource, /navigate\(successPath,\s*\{ replace: true \}\)/)
    assert.match(controllerSource, /if \(submitting\) return/)

    assert.doesNotMatch(`${viewSources}\n${contentSource}`, /@fx-platform\/frontend-core|localStorage|sessionStorage/)
  })

  it('keeps labels, links and form commands in the presentation contract', () => {
    assert.match(contentSource, /useTranslation/)
    assert.match(contentSource, /to="\/register"/)
    assert.match(contentSource, /to="\/forgot-password"/)
    assert.match(contentSource, /to="\/two-factor-help"/)
    assert.match(contentSource, /to="\/login"/)
    assert.match(contentSource, /model\.submit\(\)/)
    assert.match(contentSource, /model\.setEmail/)
    assert.match(contentSource, /model\.setPassword/)
    assert.match(contentSource, /model\.setIdentifier/)
    assert.match(contentSource, /model\.confirmSupport\(\)/)
  })
})

function readPlatformViewSources() {
  const files = [
    ['pc', 'PcLoginPage.tsx'],
    ['pc', 'PcRegisterPage.tsx'],
    ['pc', 'PcForgotPasswordPage.tsx'],
    ['pc', 'PcTwoFactorHelpPage.tsx'],
    ['mobile', 'MobileLoginPage.tsx'],
    ['mobile', 'MobileRegisterPage.tsx'],
    ['mobile', 'MobileForgotPasswordPage.tsx'],
    ['mobile', 'MobileTwoFactorHelpPage.tsx']
  ] as const

  return files.map(([platform, file]) => readFileSync(
    join(webSrc, platform, 'pages', 'auth', file),
    'utf8'
  )).join('\n')
}
