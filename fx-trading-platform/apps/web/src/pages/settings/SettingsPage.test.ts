import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const settingsPagePath = join(currentDir, 'SettingsPage.tsx')
const settingsStylesPath = join(currentDir, 'SettingsPage.module.css')
const appPath = join(currentDir, '..', '..', 'app', 'App.tsx')

describe('SettingsPage theme controls', () => {
  it('mounts a real settings page instead of a placeholder route', () => {
    assert.equal(existsSync(settingsPagePath), true, 'SettingsPage.tsx should exist')
    const appSource = readFileSync(appPath, 'utf8')

    assert.match(appSource, /const SettingsPage = lazy/)
    assert.match(appSource, /<Route path="\/settings" element=\{<SettingsPage \/>\} \/>/)
    assert.doesNotMatch(appSource, /<Route path="\/settings" element=\{<PlaceholderPage title="设置" \/>\}/)
  })

  it('lets users switch themes from the settings page', () => {
    assert.equal(existsSync(settingsPagePath), true, 'SettingsPage.tsx should exist')
    const source = readFileSync(settingsPagePath, 'utf8')

    assert.match(source, /import \{ ThemeSwitcher \} from '..\/..\/design-system\/theme\/ThemeSwitcher'/)
    assert.match(source, /<ThemeSwitcher \/>/)
    assert.match(source, /settings\.displayPreference/)
  })

  it('presents the complete settings center structure', () => {
    const source = readFileSync(settingsPagePath, 'utf8')

    assert.match(source, /settings\.tradingPreference/)
    assert.match(source, /settings\.displayPreference/)
    assert.match(source, /settings\.notificationPreference/)
    assert.match(source, /settings\.securityEntry/)
    assert.match(source, /settings\.layoutPreference/)
    assert.match(source, /to="\/security"/)
    assert.match(source, /to="\/register"/)
    assert.match(source, /to="\/forgot-password"/)
    assert.match(source, /to="\/two-factor-help"/)
  })

  it('turns static preferences into persisted controls', () => {
    const source = readFileSync(settingsPagePath, 'utf8')
    const styles = readFileSync(settingsStylesPath, 'utf8')

    assert.match(source, /settingsStorageKey = 'fx\.settings\.preferences\.v1'/)
    assert.match(source, /useState\(\(\) => loadSettingsPreferences\(\)\)/)
    assert.match(source, /window\.localStorage\.getItem\(settingsStorageKey\)/)
    assert.match(source, /window\.localStorage\.setItem\(settingsStorageKey, JSON\.stringify\(nextPreferences\)\)/)
    assert.match(source, /PreferenceToggle/)
    assert.match(source, /PreferenceSelect/)
    assert.match(source, /PreferenceSegmentedControl/)
    assert.match(source, /settings\.preferencesSaved/)
    assert.match(styles, /\.preferenceControls/)
    assert.match(styles, /\.switchControl/)
    assert.match(styles, /\.segmentGroup/)
  })

  it('uses the shared user-page surface rhythm', () => {
    const source = readFileSync(settingsPagePath, 'utf8')
    const styles = readFileSync(settingsStylesPath, 'utf8')

    assert.match(source, /user-page/)
    assert.match(source, /user-page__header/)
    assert.match(styles, /\.settingsGrid/)
    assert.match(styles, /\.surfaceHeader/)
  })

  it('uses tokenized page styling', () => {
    assert.equal(existsSync(settingsStylesPath), true, 'SettingsPage.module.css should exist')
    const styles = readFileSync(settingsStylesPath, 'utf8')

    assert.match(styles, /var\(--user-page-bg(?:,|\))/)
    assert.match(styles, /var\(--user-surface(?:,|\))/)
    assert.match(styles, /var\(--user-border(?:,|\))/)
    assert.doesNotMatch(styles, /var\(--trading-page-bg(?:,|\))/)
    assert.doesNotMatch(styles, /var\(--trading-surface(?:,|\))/)
    assert.doesNotMatch(styles, /var\(--trading-border(?:,|\))/)
    assert.doesNotMatch(styles, /#[0-9a-fA-F]{3,8}/)
  })
})
