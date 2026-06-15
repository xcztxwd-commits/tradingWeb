import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const srcDir = join(currentDir, '..')
const i18nSource = () => readFileSync(join(currentDir, 'index.ts'), 'utf8')

describe('web i18n setup', () => {
  it('defines a standard i18n module with three supported languages', () => {
    assert.equal(existsSync(join(currentDir, 'index.ts')), true)
    assert.equal(existsSync(join(currentDir, 'locales', 'zh-CN.ts')), true)
    assert.equal(existsSync(join(currentDir, 'locales', 'en-US.ts')), true)
    assert.equal(existsSync(join(currentDir, 'locales', 'ja-JP.ts')), true)

    const source = i18nSource()
    assert.match(source, /i18next/)
    assert.match(source, /initReactI18next/)
    assert.match(source, /zh-CN/)
    assert.match(source, /en-US/)
    assert.match(source, /ja-JP/)
  })

  it('persists language selection and synchronizes the document lang attribute', () => {
    const source = i18nSource()

    assert.match(source, /localStorage/)
    assert.match(source, /navigator\.language/)
    assert.match(source, /document\.documentElement\.lang/)
    assert.match(source, /fallbackLng:\s*'en-US'/)
    assert.match(source, /languageChanged/)
  })

  it('mounts the language switcher in the app shell', () => {
    const shellSource = readFileSync(join(srcDir, 'app', 'AppShell.tsx'), 'utf8')
    const navigationSource = readFileSync(join(srcDir, 'app', 'navigation.ts'), 'utf8')
    const switcherSource = readFileSync(join(srcDir, 'components', 'LanguageSwitcher.tsx'), 'utf8')

    assert.match(shellSource, /LanguageSwitcher/)
    assert.match(shellSource, /useTranslation/)
    assert.match(navigationSource, /export const guestNavItems/)
    assert.match(navigationSource, /export const authenticatedNavItems/)
    assert.match(navigationSource, /labelKey:\s*'nav\.markets'/)
    assert.match(shellSource, /t\(item\.labelKey\)/)
    assert.match(switcherSource, /useTranslation/)
    assert.match(switcherSource, /changeLanguage/)
    assert.match(switcherSource, /SelectField/)
    assert.doesNotMatch(switcherSource, /<select/)
  })

  it('initializes i18n before rendering React', () => {
    const mainSource = readFileSync(join(srcDir, 'main.tsx'), 'utf8')

    assert.match(mainSource, /import '\.\/i18n'/)
  })
})
