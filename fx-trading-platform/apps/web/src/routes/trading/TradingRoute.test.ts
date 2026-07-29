import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { describe, it } from 'node:test'
import { fileURLToPath } from 'node:url'

const currentDir = dirname(fileURLToPath(import.meta.url))
const webSrc = resolve(currentDir, '..', '..')
const routePath = join(currentDir, 'TradingRoute.tsx')
const pcPath = join(webSrc, 'pc', 'pages', 'trading', 'PcTradingTerminal.tsx')
const mobilePath = join(webSrc, 'mobile', 'pages', 'trading', 'MobileTradingTerminal.tsx')
const oldPagePath = join(webSrc, 'pages', 'trading', 'TradingPage.tsx')
const oldMobileViewportPath = join(webSrc, 'pages', 'trading', 'useMobileTerminalViewport.ts')

describe('trading terminal platform route', () => {
  it('keeps one route controller above independently lazy PC and Mobile views', () => {
    const routeSource = readRequired(routePath)

    assert.match(routeSource, /const PcTradingTerminal = lazy\(\(\) =>/)
    assert.match(routeSource, /import\('\.\.\/\.\.\/pc\/pages\/trading\/PcTradingTerminal'\)/)
    assert.match(routeSource, /const MobileTradingTerminal = lazy\(\(\) =>/)
    assert.match(routeSource, /import\('\.\.\/\.\.\/mobile\/pages\/trading\/MobileTradingTerminal'\)/)
    assert.match(routeSource, /const controller = useTradingRouteController\(\{ product \}\)/)
    assert.match(routeSource, /<PlatformView[\s\S]*model=\{controller\.model\}[\s\S]*pc=\{PcTradingTerminal\}[\s\S]*mobile=\{MobileTradingTerminal\}/)
    assert.equal((routeSource.match(/useTradingRouteController\(/g) ?? []).length, 1)
  })

  it('keeps session, subscription, form and overlay state outside both platform views', () => {
    const controllerSource = readRequired(join(currentDir, 'useTradingRouteController.ts'))
    const pcSource = readRequired(pcPath)
    const mobileSource = readRequired(mobilePath)
    const views = `${pcSource}\n${mobileSource}`

    assert.equal((controllerSource.match(/useTradingSession\(\)/g) ?? []).length, 1)
    assert.equal((controllerSource.match(/startQuoteMarketDataAdapter\(selectedSymbol, token\)/g) ?? []).length, 1)
    assert.equal((controllerSource.match(/useTradePanelController\(/g) ?? []).length, 1)
    assert.match(controllerSource, /marketDrawerOpen/)
    assert.match(controllerSource, /quoteDrawerOpen/)
    assert.match(controllerSource, /orderSheetOpen/)
    assert.doesNotMatch(views, /useTradingSession|startQuoteMarketDataAdapter|useTradePanelController/)
    assert.match(pcSource, /model\.tradePanel/)
    assert.match(mobileSource, /model\.tradePanel/)
  })

  it('isolates resizable workspace ownership to PC and removes the old 768px switch', () => {
    const pcSource = readRequired(pcPath)
    const mobileSource = readRequired(mobilePath)

    assert.match(pcSource, /useResizableLayout/)
    assert.match(pcSource, /TradingWorkspace/)
    assert.doesNotMatch(pcSource, /mobile\/|MobileTradingTerminal|MobileOrderSheet/)
    assert.doesNotMatch(mobileSource, /pc\/|useResizableLayout|layoutStore|TradingWorkspace/)
    assert.equal(existsSync(oldPagePath), false)
    assert.equal(existsSync(oldMobileViewportPath), false)
  })

  it('preserves canonical Spot and Perpetual route products through TradingRoute', () => {
    const appSource = readRequired(join(webSrc, 'app', 'App.tsx'))

    assert.match(appSource, /path="\/trade\/spot\/:symbol\?" element=\{<TradingRoute product="spot" \/>\}/)
    assert.match(appSource, /path="\/trade\/perpetual\/:symbol\?" element=\{<TradingRoute product="perpetual" \/>\}/)
    assert.doesNotMatch(appSource, /TradingPage/)
  })
})

function readRequired(path: string) {
  assert.equal(existsSync(path), true, `Missing required migrated file: ${path}`)
  return readFileSync(path, 'utf8')
}
