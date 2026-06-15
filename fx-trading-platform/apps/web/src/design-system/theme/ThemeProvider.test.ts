import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const providerPath = join(currentDir, 'ThemeProvider.tsx')
const mainPath = join(currentDir, '..', '..', 'main.tsx')

describe('ThemeProvider', () => {
  it('applies, persists, and toggles the global trading theme', () => {
    assert.equal(existsSync(providerPath), true, 'ThemeProvider.tsx should exist')
    const source = readFileSync(providerPath, 'utf8')

    assert.match(source, /export function applyThemeToDocument/)
    assert.match(source, /const themeStorageKey = 'fx-trading-theme-mode'/)
    assert.match(source, /function loadStoredThemeId\(\): TradingThemeId/)
    assert.match(source, /function saveThemeId\(themeId: TradingThemeId\)/)
    assert.match(source, /window\.localStorage\.getItem\(themeStorageKey\)/)
    assert.match(source, /window\.localStorage\.setItem\(themeStorageKey,\s*themeId\)/)
    assert.match(source, /const \[themeId,\s*setThemeIdState\] = useState<TradingThemeId>\(\(\) => loadStoredThemeId\(\)\)/)
    assert.match(source, /const currentTheme = useMemo\(\(\) => getTradingTheme\(themeId\), \[themeId\]\)/)
    assert.match(source, /document\.documentElement\.dataset\.theme/)
    assert.match(source, /document\.documentElement\.dataset\.colorScheme/)
    assert.match(source, /document\.documentElement\.style\.colorScheme/)
    assert.match(source, /document\.documentElement\.style\.setProperty/)
    assert.match(source, /setThemeId:\s*\(themeId: TradingThemeId\) => void/)
    assert.match(source, /toggleTheme:\s*\(\) => void/)
    assert.match(source, /currentTheme\.colorScheme === 'light' \? defaultThemeId : 'minimal-white'/)
  })

  it('exports a provider and hook for reading and changing the current theme', () => {
    assert.equal(existsSync(providerPath), true, 'ThemeProvider.tsx should exist')
    const source = readFileSync(providerPath, 'utf8')

    assert.match(source, /export function ThemeProvider/)
    assert.match(source, /export function useTheme/)
    assert.match(source, /currentTheme/)
    assert.match(source, /setThemeId/)
    assert.match(source, /toggleTheme/)
  })

  it('wraps the app once at the root', () => {
    const source = readFileSync(mainPath, 'utf8')

    assert.match(source, /import '\.\/design-system\/theme\/theme\.css'/)
    assert.match(source, /import \{ ThemeProvider \} from '\.\/design-system\/theme\/ThemeProvider'/)
    assert.match(source, /<ThemeProvider>[\s\S]*<App \/>[\s\S]*<\/ThemeProvider>/)
  })
})
