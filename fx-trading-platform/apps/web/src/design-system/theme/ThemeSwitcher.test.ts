import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const switcherPath = join(currentDir, 'ThemeSwitcher.tsx')
const switcherStylesPath = join(currentDir, 'ThemeSwitcher.module.css')
const tradingPagePath = join(currentDir, '..', '..', 'pages', 'trading', 'TradingPage.tsx')
const tradingSettingsDialogPath = join(currentDir, '..', '..', 'pages', 'trading', 'components', 'TradingSettingsDialog.tsx')

describe('ThemeSwitcher', () => {
  it('renders all available themes as accessible swatches', () => {
    assert.equal(existsSync(switcherPath), true, 'ThemeSwitcher.tsx should exist')
    const source = readFileSync(switcherPath, 'utf8')

    assert.match(source, /tradingThemes\.map/)
    assert.match(source, /aria-label=\{t\('settings\.switchTheme', \{ name: theme\.name \}\)\}/)
    assert.match(source, /aria-pressed=\{theme\.id === themeId\}/)
    assert.match(source, /style=\{\{ '--theme-swatch-primary': theme\.tokens\.primary/)
    assert.match(source, /setThemeId\(theme\.id\)/)
    assert.doesNotMatch(source, /design-system\/MASTER\.md/)
  })

  it('uses tokenized compact controls without large decorative effects', () => {
    assert.equal(existsSync(switcherStylesPath), true, 'ThemeSwitcher.module.css should exist')
    const styles = readFileSync(switcherStylesPath, 'utf8')

    assert.match(styles, /var\(--trading-border/)
    assert.match(styles, /var\(--trading-surface/)
    assert.match(styles, /min-height:\s*44px/)
    assert.doesNotMatch(styles, /radial-gradient/)
    assert.doesNotMatch(styles, /backdrop-filter/)
  })

  it('is mounted inside the existing trading settings dialog', () => {
    const tradingPageSource = readFileSync(tradingPagePath, 'utf8')
    const settingsDialogSource = readFileSync(tradingSettingsDialogPath, 'utf8')

    assert.match(tradingPageSource, /<TradingSettingsDialog[\s\S]*open=\{settingsOpen\}/)
    assert.match(settingsDialogSource, /import \{ ThemeSwitcher \} from '..\/..\/..\/design-system\/theme\/ThemeSwitcher'/)
    assert.match(settingsDialogSource, /<ThemeSwitcher \/>/)
    assert.doesNotMatch(`${tradingPageSource}\n${settingsDialogSource}`, /saveTradingThemeMode/)
    assert.doesNotMatch(`${tradingPageSource}\n${settingsDialogSource}`, /tradingThemeStorageKey/)
  })
})
