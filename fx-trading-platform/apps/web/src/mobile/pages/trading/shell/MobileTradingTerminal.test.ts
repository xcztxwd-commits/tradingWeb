import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const mobilePageDir = resolve(currentDir, '..')
const webSrcDir = resolve(currentDir, '..', '..', '..', '..')
const projectRoot = resolve(webSrcDir, '..', '..', '..')
const terminalSourcePath = join(currentDir, 'MobileTradingTerminal.tsx')
const terminalStylesPath = join(currentDir, 'MobileTradingTerminal.module.css')
const routeSource = readFileSync(join(webSrcDir, 'routes', 'trading', 'TradingRoute.tsx'), 'utf8')
const routeStyles = readFileSync(join(webSrcDir, 'routes', 'trading', 'TradingRoute.module.css'), 'utf8')
const mobileViewSource = readFileSync(join(mobilePageDir, 'MobileTradingTerminal.tsx'), 'utf8')
const mobilePanelsSource = readFileSync(join(mobilePageDir, 'MobilePanels.tsx'), 'utf8')
const dialogSource = readFileSync(join(projectRoot, 'packages', 'ui', 'src', 'dialog', 'Dialog.tsx'), 'utf8')
const deviceClassSource = readFileSync(join(webSrcDir, 'app', 'device', 'deviceClass.ts'), 'utf8')
const themeCss = readFileSync(join(projectRoot, 'packages', 'ui', 'src', 'theme', 'theme.css'), 'utf8')
const mobilePanelsStyles = readFileSync(join(mobilePageDir, 'MobilePanels.module.css'), 'utf8')

describe('mobile trading terminal redesign', () => {
  it('owns a separately lazy Mobile terminal instead of compressing the PC workspace', () => {
    assert.equal(existsSync(terminalSourcePath), true)
    assert.equal(existsSync(terminalStylesPath), true)
    assert.match(routeSource, /const MobileTradingTerminal = lazy\(/)
    assert.match(routeSource, /import\('\.\.\/\.\.\/mobile\/pages\/trading\/MobileTradingTerminal'\)/)
    assert.equal(existsSync(join(webSrcDir, 'pages', 'trading', 'useMobileTerminalViewport.ts')), false)
    assert.match(deviceClassSource, /mobileViewportQuery = '\(max-width: 900px\)'/)
    assert.match(routeSource, /<PlatformView[\s\S]*mobile=\{MobileTradingTerminal\}/)
    assert.doesNotMatch(routeSource, /useDeviceClass|window\.innerWidth|matchMedia/)
    assert.match(mobileViewSource, /<MobileTerminalShell[\s\S]*chart=\{[\s\S]*<ChartWorkspace/)
    assert.match(mobileViewSource, /<MobileTerminalShell[\s\S]*accountPanel=\{[\s\S]*<BottomAccountPanel/)
    assert.match(mobileViewSource, /<MobileDrawer/)
    assert.match(mobileViewSource, /<TradingOrderSheet/)
    assert.match(mobileViewSource, /data-platform-view="mobile"/)
    assert.doesNotMatch(mobileViewSource, /useResizableLayout|TradingWorkspace|layoutStore/)
    assert.doesNotMatch(routeStyles, /max-width:\s*768px/)
    assert.match(routeStyles, /max-width:\s*900px/)
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
    assert.doesNotMatch(terminalSource, /onOpenSettings|TerminalIconButton|SlidersHorizontal|MoreVertical/)
    assert.match(terminalSource, /formatMarketPrice\(market\.symbol, quote\.mid\)/)
    assert.match(terminalSource, /formatMarketPrice\(market\.symbol, quote\.high24h\)/)
    assert.match(terminalSource, /formatMarketPrice\(market\.symbol, quote\.low24h\)/)
    assert.match(terminalSource, /aria-label=\{t\('trading\.mobileTerminal'\)\}/)
  })

  it('keeps drawers and order sheets interactive across the full Mobile platform range', () => {
    assert.match(mobilePanelsSource, /import \{ Dialog, Drawer \} from '@fx-platform\/ui'/)
    assert.match(mobilePanelsSource, /return \(\s*<Drawer/)
    assert.ok((mobileViewSource.match(/<MobileDrawer/g) ?? []).length >= 2)
    assert.doesNotMatch(mobilePanelsSource, /styles\.drawerLayer/)
    assert.match(mobilePanelsStyles, /@media \(max-width:\s*900px\)\s*\{[\s\S]*?\.drawerViewport,[\s\S]*?\.sheetLayer\s*\{[\s\S]*?display:\s*block/)
    assert.doesNotMatch(mobilePanelsStyles, /max-width:\s*760px/)
  })

  it('uses the focus-managed Dialog primitive for the mobile Trade sheet', () => {
    assert.match(mobilePanelsSource, /import \{ Dialog, Drawer \} from '@fx-platform\/ui'/)
    assert.match(mobilePanelsSource, /const titleId = useId\(\)/)
    assert.match(mobilePanelsSource, /<Dialog[\s\S]*open=\{open\}[\s\S]*onClose=\{onClose\}/)
    assert.match(mobilePanelsSource, /labelledBy=\{titleId\}/)
    assert.match(mobilePanelsSource, /closeLabel=\{`Close \$\{title\}`\}/)
    assert.match(mobilePanelsSource, /<h2 id=\{titleId\}>\{title\}<\/h2>/)
    assert.match(mobilePanelsSource, /aria-label=\{`Close \$\{title\}`\}/)
    assert.match(mobilePanelsSource, /<X size=\{17\} aria-hidden="true" \/>/)

    assert.equal((dialogSource.match(/role="dialog"/g) ?? []).length, 1)
    assert.match(dialogSource, /aria-modal="true"/)
    assert.match(dialogSource, /if \(!open\) return null/)
    assert.match(dialogSource, /event\.key === 'Escape'/)
    assert.match(dialogSource, /document\.activeElement/)
    assert.match(dialogSource, /firstFocusable/)
    assert.match(dialogSource, /previousFocusRef\.current\?\.focus\(\)/)
    assert.match(dialogSource, /source: 'backdrop'/)
  })

  it('renders the product label from the selected market without inventing a contract state', () => {
    const terminalSource = readFileSync(terminalSourcePath, 'utf8')
    const terminalStyles = readFileSync(terminalStylesPath, 'utf8')

    assert.match(terminalSource, /market\.productType === 'LINEAR_PERP'/)
    assert.match(terminalSource, /t\('trading\.perpetual'\)/)
    assert.match(terminalSource, /t\('trading\.spot'\)/)
    assert.match(terminalSource, /<small>\{productLabel\}<\/small>/)
    assert.match(terminalSource, /styles\.contractHeader/)
    assert.match(terminalSource, /onClick=\{onOpenTrade\}/)
    assert.doesNotMatch(terminalSource, /7\.34|100x|styles\.marketTabs|styles\.orderSurface|styles\.orderControls/)
    assert.doesNotMatch(terminalStyles, /\.marketTabs\s*\{|\.orderSurface\s*\{|\.longButton\s*\{|\.shortButton\s*\{|\.bidAskRatio\s*\{/)
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

  it('keeps the mobile sample styles on semantic tokens and free of decorative effects', () => {
    const terminalStyles = readFileSync(terminalStylesPath, 'utf8')
    const sampleStyles = `${terminalStyles}\n${mobilePanelsStyles}`

    assert.doesNotMatch(sampleStyles, /#[0-9a-fA-F]{3,8}/)
    assert.doesNotMatch(sampleStyles, /--theme-reference-/)
    assert.doesNotMatch(sampleStyles, /rgba\(|backdrop-filter|radial-gradient/)
    assert.match(terminalStyles, /var\(--space-2\)/)
    assert.match(terminalStyles, /var\(--radius-4\)/)
    assert.match(terminalStyles, /var\(--motion-base\)/)
  })

  it('reserves enough mobile scroll inset for the fixed bottom action bar', () => {
    const terminalStyles = readFileSync(terminalStylesPath, 'utf8')

    assert.match(terminalStyles, /max-width:\s*100%/)
    assert.match(terminalStyles, /overflow-x:\s*hidden/)
    assert.match(terminalStyles, /padding:[\s\S]*calc\(env\(safe-area-inset-bottom\) \+ var\(--mobile-tabs-height\) \+ var\(--space-8\) \+ var\(--space-8\) \+ var\(--space-4\)\)/)
    assert.match(terminalStyles, /\.actionBar\s*\{[\s\S]*max-width:\s*calc\(100% - var\(--space-4\)\)/)
    assert.match(terminalStyles, /\.actionBar\s*\{[\s\S]*bottom:\s*calc\(env\(safe-area-inset-bottom\) \+ var\(--mobile-tabs-height\) \+ var\(--space-2\)\)/)
  })

  it('keeps the account section clear of the fixed mobile action bar on first paint', () => {
    const terminalStyles = readFileSync(terminalStylesPath, 'utf8')
    assert.match(terminalStyles, /\.accountSection\s*\{[\s\S]*margin-top:\s*var\(--space-8\)/)
  })

  it('keeps every fixed mobile trading action at least 44px tall', () => {
    const terminalStyles = readFileSync(terminalStylesPath, 'utf8')

    assert.match(terminalStyles, /\.actionBar button\s*\{[\s\S]*min-height:\s*44px/)
    assert.doesNotMatch(terminalStyles, /\.actionBar button\s*\{[\s\S]*min-height:\s*40px/)
  })
})
