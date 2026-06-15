import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const tradingDir = resolve(currentDir, '..')
const webSrcDir = resolve(tradingDir, '..', '..')
const terminalSourcePath = join(currentDir, 'MobileTradingTerminal.tsx')
const terminalStylesPath = join(currentDir, 'MobileTradingTerminal.module.css')
const tradingPageSource = readFileSync(join(tradingDir, 'TradingPage.tsx'), 'utf8')
const tradingPageStyles = readFileSync(join(tradingDir, 'TradingPage.module.css'), 'utf8')
const mobileViewSource = readFileSync(join(tradingDir, 'components', 'TradingMobileView.tsx'), 'utf8')
const mobileViewportSource = readFileSync(join(tradingDir, 'useMobileTerminalViewport.ts'), 'utf8')
const themeCss = readFileSync(join(webSrcDir, 'design-system', 'theme', 'theme.css'), 'utf8')
const mobilePanelsStyles = readFileSync(join(tradingDir, 'components', 'MobilePanels.module.css'), 'utf8')

describe('mobile trading terminal redesign', () => {
  it('adds a mobile-only terminal shell instead of compressing the desktop workspace', () => {
    assert.equal(existsSync(terminalSourcePath), true)
    assert.equal(existsSync(terminalStylesPath), true)

    assert.match(mobileViewSource, /const MobileTradingTerminal = lazy\(/)
    assert.match(mobileViewSource, /import\('..\/mobile\/MobileTradingTerminal'\)/)
    assert.match(tradingPageSource, /import \{ useMobileTerminalViewport \} from '\.\/useMobileTerminalViewport'/)
    assert.match(mobileViewportSource, /function useMobileTerminalViewport\(\)/)
    assert.match(tradingPageSource, /const isMobileTerminal = useMobileTerminalViewport\(\)/)
    assert.match(tradingPageSource, /\{!isMobileTerminal \? <TradingDesktopView \{\.\.\.viewProps\} \/> : null\}/)
    assert.match(tradingPageSource, /\{isMobileTerminal \? <TradingMobileView \{\.\.\.viewProps\} \/> : null\}/)
    assert.match(tradingPageSource, /<TradingMobileView/)
    assert.match(mobileViewSource, /<MobileTradingTerminal[\s\S]*chart=\{[\s\S]*<ChartWorkspace/)
    assert.match(mobileViewSource, /<MobileTradingTerminal[\s\S]*accountPanel=\{[\s\S]*<BottomAccountPanel/)
    assert.match(mobileViewSource, /<Suspense fallback=\{<MobileTerminalFallback \/>}/)
    assert.match(mobileViewSource, /function MobileTerminalFallback\(\)/)
    assert.match(tradingPageStyles, /\.desktopTerminal/)
    assert.match(tradingPageStyles, /\.mobileTerminal/)
    assert.match(tradingPageStyles, /\.mobileTerminalFallback/)
  })

  it('keeps mobile trading wired to the existing business components', () => {
    const terminalSource = readFileSync(terminalSourcePath, 'utf8')

    assert.match(terminalSource, /type MobileTradingTerminalProps = \{[\s\S]*market: TradingMarket/)
    assert.match(terminalSource, /quote: TradingQuote/)
    assert.match(terminalSource, /chart: ReactNode/)
    assert.match(terminalSource, /accountPanel: ReactNode/)
    assert.match(terminalSource, /onOpenMarkets: \(\) => void/)
    assert.match(terminalSource, /onOpenQuote: \(\) => void/)
    assert.match(terminalSource, /onOpenTrade: \(\) => void/)
    assert.match(terminalSource, /onOpenSettings: \(\) => void/)
    assert.match(terminalSource, /formatMarketPrice\(market\.symbol, quote\.mid\)/)
    assert.match(terminalSource, /formatMarketPrice\(market\.symbol, quote\.high24h\)/)
    assert.match(terminalSource, /formatMarketPrice\(market\.symbol, quote\.low24h\)/)
    assert.match(terminalSource, /aria-label=\{t\('trading\.mobileTerminal'\)\}/)
  })

  it('matches the Binance-like mobile contract surface with market tabs, long short actions and bid ask ratio', () => {
    const terminalSource = readFileSync(terminalSourcePath, 'utf8')
    const terminalStyles = readFileSync(terminalStylesPath, 'utf8')

    assert.match(terminalSource, /styles\.marketTabs/)
    assert.match(terminalSource, /styles\.contractHeader/)
    assert.match(terminalSource, /styles\.orderSurface/)
    assert.match(terminalSource, /styles\.longButton/)
    assert.match(terminalSource, /styles\.shortButton/)
    assert.match(terminalSource, /styles\.bidAskRatio/)
    assert.match(terminalSource, /onClick=\{onOpenTrade\}/)
    assert.match(terminalStyles, /\.marketTabs\s*{[\s\S]*grid-template-columns:\s*repeat\(5,\s*minmax\(0,\s*1fr\)\)/)
    assert.match(terminalStyles, /\.longButton\s*{[\s\S]*background:\s*#2ebd85/)
    assert.match(terminalStyles, /\.shortButton\s*{[\s\S]*background:\s*#f6465d/)
    assert.match(terminalStyles, /\.bidAskRatio\s*{[\s\S]*#2ebd85/)
  })

  it('uses design tokens for mobile density, radius, typography and motion', () => {
    assert.match(themeCss, /--font-ui:/)
    assert.match(themeCss, /--font-data:/)
    assert.match(themeCss, /--space-1:\s*4px/)
    assert.match(themeCss, /--space-2:\s*8px/)
    assert.match(themeCss, /--radius-4:\s*8px/)
    assert.match(themeCss, /--motion-panel:\s*220ms/)
    assert.match(themeCss, /--z-bottom-nav:\s*300/)
  })

  it('keeps the mobile sample styles tokenized and free of decorative effects outside the OKX reference colors', () => {
    const terminalStyles = readFileSync(terminalStylesPath, 'utf8')
    const sampleStyles = `${terminalStyles}\n${mobilePanelsStyles}`
    const sampleStylesWithoutReferenceColors = sampleStyles.replace(
      /#(?:0b0e11|181a20|1e2329|2b3139|2ebd85|333b47|434c5a|848e9c|eaecef|f0b90b|f6465d|fcd535|ffffff)/gi,
      ''
    )

    assert.doesNotMatch(sampleStylesWithoutReferenceColors, /#[0-9a-fA-F]{3,8}/)
    assert.doesNotMatch(sampleStyles, /rgba\(/)
    assert.doesNotMatch(sampleStyles, /backdrop-filter/)
    assert.doesNotMatch(sampleStyles, /radial-gradient/)
    assert.match(terminalStyles, /var\(--space-2\)/)
    assert.match(terminalStyles, /var\(--radius-4\)/)
    assert.match(terminalStyles, /var\(--motion-base\)/)
  })

  it('reserves enough mobile scroll inset for the fixed bottom action bar', () => {
    const terminalStyles = readFileSync(terminalStylesPath, 'utf8')

    assert.match(terminalStyles, /max-width:\s*100%/)
    assert.match(terminalStyles, /overflow-x:\s*hidden/)
    assert.match(terminalStyles, /padding:[\s\S]*calc\(env\(safe-area-inset-bottom\) \+ var\(--mobile-tabs-height\) \+ var\(--space-8\) \+ var\(--space-8\) \+ var\(--space-4\)\)/)
    assert.match(terminalStyles, /\.actionBar\s*{[\s\S]*max-width:\s*calc\(100% - var\(--space-4\)\)/)
    assert.match(terminalStyles, /\.actionBar\s*{[\s\S]*bottom:\s*calc\(env\(safe-area-inset-bottom\) \+ var\(--mobile-tabs-height\) \+ var\(--space-2\)\)/)
  })

  it('keeps the account section clear of the fixed mobile action bar on first paint', () => {
    const terminalStyles = readFileSync(terminalStylesPath, 'utf8')

    assert.match(terminalStyles, /\.accountSection\s*{[\s\S]*margin-top:\s*var\(--space-8\)/)
  })
})
