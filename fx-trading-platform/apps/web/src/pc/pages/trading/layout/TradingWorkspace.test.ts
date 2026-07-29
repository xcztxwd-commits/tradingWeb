import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const source = readFileSync(join(currentDir, 'TradingWorkspace.tsx'), 'utf8')
const styles = readFileSync(join(currentDir, 'TradingWorkspace.module.css'), 'utf8')
const draggedPanelSource = source.slice(source.indexOf('function getDraggedPanelId'))

describe('TradingWorkspace OKX-style terminal structure', () => {
  it('renders the workspace from a recursive split-tree layout', () => {
    assert.match(source, /renderLayoutNode/)
    assert.match(source, /node\.type === 'split'/)
    assert.match(source, /ResizeHandle[\s\S]*beginSplitResize/)
  })

  it('adds a top-right drag handle and four-direction drop targeting to every movable panel', () => {
    assert.match(source, /draggable/)
    assert.match(source, /onDrop/)
    assert.match(source, /detectDropSide/)
    assert.match(source, /movePanel\(sourceId, panel\.id, detectDropSide\(event\)\)/)
    assert.match(source, /dropIndicator/)
    assert.match(source, /data-panel-id/)
    assert.match(source, /watchlist/)
  })

  it('keeps panel-specific class hooks for watchlist, header, chart, market, trade, and account areas', () => {
    assert.match(source, /watchlistPanel/)
    assert.match(source, /headerPanel/)
    assert.match(source, /chartPanel/)
    assert.match(source, /marketPanel/)
    assert.match(source, /tradePanel/)
    assert.match(source, /accountPanel/)
  })

  it('renders the desktop market watchlist and symbol header through the workspace', () => {
    assert.match(source, /watchlist:\s*ReactNode/)
    assert.match(source, /header:\s*ReactNode/)
    assert.match(source, /const panelContent = \{ watchlist, header, chart, market, trade, bottom \}/)
    assert.match(source, /value === 'watchlist'/)
  })

  it('keeps the symbol header fixed instead of rendering a draggable handle or drop target for it', () => {
    assert.match(source, /const lockedPanelIds = new Set<TradingPanelId>\(\['header'\]\)/)
    assert.match(source, /const isLockedPanel = lockedPanelIds\.has\(node\.id\)/)
    assert.match(source, /if \(isLockedPanel\) return/)
    assert.match(source, /!\s*isLockedPanel\s*\?\s*\(/)
    assert.doesNotMatch(draggedPanelSource, /value === 'header'/)
  })

  it('keeps the top/bottom connection resize handle free to shrink the bottom panel to a compact height', () => {
    assert.match(source, /BOTTOM_PANEL_MIN_HEIGHT\s*=\s*80/)
    assert.match(source, /isTerminalBottomSplit\(node\)[\s\S]*gridTemplateRows/)
    assert.match(source, /minmax\(\$\{BOTTOM_PANEL_MIN_HEIGHT\}px,\s*\$\{node\.sizes\[1\]\}fr\)/)
    assert.doesNotMatch(source, /terminalBottomSplit\s*\?\s*null\s*:\s*\(/)
    assert.match(source, /bottomPanelTargetHeight,\s*setBottomPanelTargetHeight/)
    assert.match(source, /bottomPanelHeightDragStartRef/)
    assert.match(source, /className=\{styles\.bottomTerminal\}/)
    assert.match(source, /BOTTOM_PANEL_RESIZE_HANDLE_HEIGHT\s*=\s*8/)
    assert.match(source, /gridTemplateRows:\s*`minmax\(0, 1fr\) 8px \$\{Math\.max\(BOTTOM_PANEL_MIN_HEIGHT,\s*bottomPanelTargetHeight\) \+ BOTTOM_PANEL_RESIZE_HANDLE_HEIGHT\}px`/)
    assert.match(source, /ariaLabel=\{t\('trading\.extendBottomPanel'\)\}/)
    assert.match(styles, /\.bottomTerminal\s*{[\s\S]*min-height:\s*80px/)
    assert.match(styles, /\.bottomTerminal\s*{[\s\S]*grid-template-rows:\s*minmax\(0,\s*1fr\)\s*8px/)
    assert.match(styles, /\.grid\s*{[\s\S]*overflow:\s*visible/)
  })

  it('keeps growing the bottom panel while the bottom drag handle is held near the viewport bottom', () => {
    assert.doesNotMatch(source, /BOTTOM_PANEL_MAX_EXTRA_HEIGHT/)
    assert.match(source, /BOTTOM_PANEL_AUTO_GROW_EDGE_DISTANCE\s*=\s*24/)
    assert.match(source, /BOTTOM_PANEL_AUTO_GROW_STEP\s*=\s*8/)
    assert.match(source, /bottomEdgeAutoGrowFrameRef/)
    assert.match(source, /bottomEdgeAutoGrowExtraRef/)
    assert.match(source, /window\.innerHeight\s*-\s*bottomEdgePointerYRef\.current\s*<=\s*BOTTOM_PANEL_AUTO_GROW_EDGE_DISTANCE/)
    assert.match(source, /bottomEdgeAutoGrowExtraRef\.current\s*\+=\s*BOTTOM_PANEL_AUTO_GROW_STEP/)
    assert.match(source, /setBottomPanelTargetHeight\(\(current\)\s*=>/)
    assert.match(source, /\(current\s*\?\?\s*bottomPanelHeightDragStartRef\.current\)\s*\+\s*BOTTOM_PANEL_AUTO_GROW_STEP/)
    assert.match(source, /bottomPanelHeightDragStartRef\.current\s*\+\s*deltaY\s*\+\s*bottomEdgeAutoGrowExtraRef\.current/)
  })

  it('initially keeps the bottom current-order area compact enough for the first screen', () => {
    assert.match(source, /useLayoutEffect/)
    assert.match(source, /BOTTOM_PANEL_VISIBLE_TABLE_ROWS\s*=\s*3/)
    assert.match(source, /BOTTOM_PANEL_TABLE_ROW_HEIGHT\s*=\s*34/)
    assert.match(source, /BOTTOM_PANEL_TABS_HEIGHT\s*=\s*38/)
    assert.match(source, /BOTTOM_PANEL_TOOLBAR_HEIGHT\s*=\s*34/)
    assert.match(source, /BOTTOM_PANEL_INITIAL_HEIGHT\s*=[\s\S]*BOTTOM_PANEL_VISIBLE_TABLE_ROWS\s*\+\s*1/)
    assert.match(source, /initialSizingAppliedRef/)
    assert.match(source, /applyInitialPanelSizing/)
    assert.match(source, /const bottomPanelHeight\s*=\s*panelShellRefs\.current\.bottom\?\.getBoundingClientRect\(\)\.height\s*\?\?\s*0/)
    assert.match(source, /setBottomPanelTargetHeight\(BOTTOM_PANEL_INITIAL_HEIGHT\)/)
    assert.match(source, /if \(bottomPanelHeight <= 0\) return false/)
    assert.match(source, /return true/)
    assert.match(source, /window\.requestAnimationFrame\(applyWhenReady\)/)
    assert.match(source, /useLayoutEffect\(\(\)\s*=>\s*{\s*applyInitialPanelSizing\(\)/)
    assert.doesNotMatch(source, /growTopWorkspaceUntilTradePanelFits/)
    assert.doesNotMatch(source, /setTopWorkspaceExtraHeight/)
  })

  it('lets the default desktop workspace exceed one viewport for a long-page terminal', () => {
    assert.doesNotMatch(source, /WORKSPACE_AUTO_GROW_EDGE_DISTANCE/)
    assert.doesNotMatch(source, /workspaceAutoGrowFrameRef/)
    assert.doesNotMatch(source, /topWorkspaceExtraHeight/)
    assert.doesNotMatch(source, /workspaceExtraHeightDragStartRef/)
    assert.doesNotMatch(source, /workspaceTerminalShrinkCapacityRef/)
    assert.doesNotMatch(source, /workspaceTradeGrowthShareRef/)
    assert.doesNotMatch(source, /topWorkspaceMaxExtraHeightRef/)
    assert.doesNotMatch(source, /updateTopWorkspaceExtraHeightFromDrag/)
    assert.match(source, /setBottomPanelTargetHeight\(Math\.max\(BOTTOM_PANEL_MIN_HEIGHT,\s*Math\.round\(terminalHeight\)\)\)/)
    assert.match(source, /setBottomPanelTargetHeight\(Math\.max\(BOTTOM_PANEL_MIN_HEIGHT,\s*Math\.round\(bottomPanelHeightDragStartRef\.current\s*-\s*delta\.deltaY\)\)\)/)
    assert.doesNotMatch(source, /overflowAfterTerminalShrink/)
    assert.doesNotMatch(source, /Math\.min\(targetExtraHeight,\s*current\s*\+\s*tradePanelOverflow\)/)
    assert.doesNotMatch(source, /querySelector\('\.trade-panel'\)/)
    assert.doesNotMatch(source, /function getPanelVerticalGrowthShare/)
    assert.match(source, /className=\{styles\.grid\}/)
    assert.doesNotMatch(source, /style=\{\{ height:/)
    assert.match(styles, /\.workspace\s*{[\s\S]*height:\s*max\(1120px,\s*calc\(100vh \+ 440px\)\)/)
  })

  it('keeps mobile breakpoint layout out of the desktop workspace styles', () => {
    assert.doesNotMatch(source, /mobileChart|Chevron(?:Down|Up)|chartTitle|chartContent/)
    assert.doesNotMatch(styles, /@media/)
    assert.doesNotMatch(styles, /display:\s*contents/)
    assert.doesNotMatch(styles, /\.mobileChart(?:Collapsed|Expanded)/)
    assert.doesNotMatch(styles, /\.mobileChartToggle/)
    assert.doesNotMatch(styles, /:global\(\.trade-panel/)
  })

  it('keeps layout actions external so workspace controls do not take a chart row', () => {
    assert.match(source, /layoutControls:\s*TradingWorkspaceLayoutControls/)
    assert.match(source, /\{ layout, beginSplitResize, resizeByDelta, endResize, movePanel, resetLayout, resetSignal \} = layoutControls/)
    assert.doesNotMatch(source, /className=\{styles\.layoutControls\}/)
    assert.doesNotMatch(source, /presetBar/)
    assert.doesNotMatch(styles, /\.layoutControls/)
    assert.doesNotMatch(styles, /\.presetButton/)
  })

})
