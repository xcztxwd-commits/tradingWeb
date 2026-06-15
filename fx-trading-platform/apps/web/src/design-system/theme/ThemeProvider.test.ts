import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const providerPath = join(currentDir, 'ThemeProvider.tsx')
const mainPath = join(currentDir, '..', '..', 'main.tsx')

describe('ThemeProvider', () => {
  it('owns global theme state and persists the selected theme id', () => {
    assert.equal(existsSync(providerPath), true, 'ThemeProvider.tsx should exist')
    const source = readFileSync(providerPath, 'utf8')

    assert.match(source, /export const themeStorageKey = 'fx-ui-theme'/)
    assert.match(source, /export function loadThemeId/)
    assert.match(source, /export function saveThemeId/)
    assert.match(source, /export function applyThemeToDocument/)
    assert.match(source, /document\.documentElement\.dataset\.theme/)
    assert.match(source, /document\.documentElement\.style\.setProperty/)
    assert.match(source, /window\.localStorage\.setItem\(themeStorageKey,\s*themeId\)/)
    assert.doesNotMatch(source, /fx-trading-theme-mode/)
  })

  it('exports a provider and hook for page-level controls', () => {
    assert.equal(existsSync(providerPath), true, 'ThemeProvider.tsx should exist')
    const source = readFileSync(providerPath, 'utf8')

    assert.match(source, /export function ThemeProvider/)
    assert.match(source, /export function useTheme/)
    assert.match(source, /setThemeId/)
    assert.match(source, /currentTheme/)
  })

  it('wraps the app once at the root', () => {
    const source = readFileSync(mainPath, 'utf8')

    assert.match(source, /import '\.\/design-system\/theme\/theme\.css'/)
    assert.match(source, /import \{ ThemeProvider \} from '\.\/design-system\/theme\/ThemeProvider'/)
    assert.match(source, /<ThemeProvider>[\s\S]*<App \/>[\s\S]*<\/ThemeProvider>/)
  })
})
