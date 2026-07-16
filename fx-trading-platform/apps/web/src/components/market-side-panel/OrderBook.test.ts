import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const source = readFileSync(join(currentDir, 'OrderBook.tsx'), 'utf8')
const sidePanelSource = readFileSync(join(currentDir, 'MarketSidePanel.tsx'), 'utf8')
const orderBookRowSource = readFileSync(join(currentDir, 'OrderBookRow.tsx'), 'utf8')
const recentTradesSource = readFileSync(join(currentDir, 'RecentTrades.tsx'), 'utf8')
const tradeRowSource = readFileSync(join(currentDir, 'TradeRow.tsx'), 'utf8')
const styles = readFileSync(join(currentDir, 'MarketSidePanel.module.css'), 'utf8')

describe('OKX-style order book panel', () => {
  it('shows a market-owned skeleton while the first market snapshot is empty', () => {
    assert.match(sidePanelSource, /import \{ OrderBookSkeleton \} from '\.\.\/loading\/TerminalSkeleton'/)
    assert.match(sidePanelSource, /OrderBookSkeleton/)
    assert.match(sidePanelSource, /isInitialMarketSnapshot/)
    assert.doesNotMatch(sidePanelSource, /\.\.\/\.\.\/pages\/trading/)
  })

  it('renders explicit unavailable/stale and empty-trade states instead of an endless ready-looking book', () => {
    assert.match(sidePanelSource, /snapshot\.status === 'loading'/)
    assert.match(sidePanelSource, /snapshot\.status === 'stale'/)
    assert.match(sidePanelSource, /trading\.marketDataUnavailable/)
    assert.match(sidePanelSource, /trading\.marketDataStale/)
    assert.match(sidePanelSource, /role="status"/)
    assert.match(recentTradesSource, /snapshot\.recentTrades\.length === 0/)
    assert.match(recentTradesSource, /trading\.noRecentTrades/)
  })

  it('shows the one authoritative source shared by quote, depth and trades', () => {
    assert.match(sidePanelSource, /MarketSourceBadge/)
    assert.match(sidePanelSource, /snapshot\.source\.sourceMode/)
    assert.match(sidePanelSource, /providerCode=\{snapshot\.source\.providerCode\}/)
    assert.match(sidePanelSource, /stale=\{snapshot\.status === 'stale'\}/)
  })

  it('does not import loading skeletons from page-owned trading modules', () => {
    assert.doesNotMatch(sidePanelSource, /pages\/trading/)
    assert.doesNotMatch(sidePanelSource, /\.\.\/\.\.\/pages\/trading/)
    assert.match(sidePanelSource, /\.\.\/loading\/TerminalSkeleton/)
  })

  it('lets the trading page hold the right quote and order book area in startup loading', () => {
    assert.match(sidePanelSource, /loading\?: boolean/)
    assert.match(sidePanelSource, /loading = false/)
    assert.match(sidePanelSource, /const marketLoading = loading \|\| snapshot\.status === 'loading'/)
    assert.match(sidePanelSource, /activeTab === 'orderbook' && marketLoading/)
  })

  it('renders spread and buy/sell pressure labels near the book footer', () => {
    assert.match(source, /spreadLine/)
    assert.match(source, /trading\.bidPressure/)
    assert.match(source, /trading\.askPressure/)
    assert.match(source, /spreadValue/)
  })

  it('lets book rows and recent trades return clicked prices to the trading form', () => {
    assert.match(sidePanelSource, /onSelectPrice\?: \(price: number\) => void/)
    assert.match(source, /onSelectPrice=\{onSelectPrice\}/)
    assert.match(recentTradesSource, /onSelectPrice=\{onSelectPrice\}/)
    assert.match(orderBookRowSource, /type="button"/)
    assert.match(orderBookRowSource, /trading\.fillPrice/)
    assert.match(orderBookRowSource, /onSelectPrice\?\.\(row\.price\)/)
    assert.match(tradeRowSource, /trading\.fillTradePrice/)
    assert.match(tradeRowSource, /onSelectPrice\?\.\(trade\.price\)/)
    assert.match(styles, /\.clickableMarketRow/)
    assert.match(styles, /\.clickableMarketRow:focus-visible/)
  })

  it('uses a denser terminal row rhythm for order book readability', () => {
    assert.match(styles, /\.spreadLine\s*{[\s\S]*display:\s*flex/)
    assert.match(styles, /\.pressureLabels\s*{[\s\S]*display:\s*flex/)
    assert.match(styles, /\.marketRow\s*{[\s\S]*height:\s*20px/)
  })

  it('compresses mobile order book columns for a narrow side-by-side viewport', () => {
    assert.match(styles, /@media\s*\(max-width:\s*760px\)\s*{[\s\S]*\.columnHeader,\s*\.marketRow\s*{[\s\S]*grid-template-columns:\s*minmax\(64px,\s*1fr\)\s*minmax\(54px,\s*0\.9fr\)/)
    assert.match(styles, /@media\s*\(max-width:\s*760px\)\s*{[\s\S]*\.columnHeader span:nth-child\(3\),\s*\.marketRow span:nth-child\(3\)\s*{[\s\S]*display:\s*none/)
  })

  it('keeps split mode centered without an order book scrollbar', () => {
    assert.match(source, /bookScrollerSplit/)
    assert.match(source, /askRows/)
    assert.match(source, /bidRows/)
    assert.doesNotMatch(source, /bothSideRows/)
    assert.doesNotMatch(source, /singleSideRows/)
    assert.doesNotMatch(source, /\.slice\(displayMode === 'both'/)
    assert.match(styles, /\.bookScroller\s*{[\s\S]*overflow:\s*hidden/)
    assert.match(styles, /\.tradeRows\s*{[\s\S]*overflow-y:\s*auto/)
    assert.match(styles, /\.bookScrollerSingle\s*{[\s\S]*overflow-y:\s*hidden/)
    assert.match(styles, /\.bookScrollerSplit\s*{[\s\S]*display:\s*grid/)
    assert.match(styles, /\.bookScrollerSplit\s*{[\s\S]*grid-template-rows:\s*minmax\(0,\s*1fr\)\s+auto\s+minmax\(0,\s*1fr\)/)
    assert.match(styles, /\.bookScrollerSplit\s*{[\s\S]*overflow-y:\s*hidden/)
    assert.match(styles, /\.bookScrollerSplit\s+\.sideRows\s*{[\s\S]*min-height:\s*0/)
    assert.match(styles, /\.bookScrollerSplit\s+\.sideRows\s*{[\s\S]*overflow:\s*hidden/)
    assert.match(styles, /\.askRows\s*{[\s\S]*align-content:\s*end/)
    assert.match(styles, /\.bidRows\s*{[\s\S]*align-content:\s*start/)
  })

  it('does not use imperative scroll positioning for split mode centering', () => {
    assert.doesNotMatch(source, /useLayoutEffect/)
    assert.doesNotMatch(source, /scrollerRef/)
    assert.doesNotMatch(source, /lastPriceRef/)
    assert.doesNotMatch(source, /scrollTop/)
  })

  it('keeps order book toolbar controls and settings popover visually responsive', () => {
    assert.match(styles, /\.toolbar button:focus-visible,\s*\.stepSelect:focus-visible,\s*\.popover button:focus-visible/)
    assert.match(styles, /\.popover\s*{[\s\S]*animation:\s*marketPopoverIn/)
    assert.match(styles, /@keyframes marketPopoverIn/)
    assert.match(styles, /@media\s*\(prefers-reduced-motion:\s*reduce\)\s*{[\s\S]*\.popover\s*{[\s\S]*animation:\s*none/)
  })
})
