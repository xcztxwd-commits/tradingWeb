import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const workspaceSource = readFileSync(join(currentDir, 'ChartWorkspace.tsx'), 'utf8')
const panelSource = readFileSync(join(currentDir, 'KLineChartPanel.tsx'), 'utf8')
const workspaceStyles = readFileSync(join(currentDir, 'ChartWorkspace.module.css'), 'utf8')

describe('ChartWorkspace fullscreen target', () => {
  it('makes the whole chart workspace the fullscreen target for the toolbar button', () => {
    assert.match(workspaceSource, /const workspaceRef = useRef<HTMLElement \| null>\(null\)/)
    assert.match(workspaceSource, /<section ref={workspaceRef} className={workspaceClassName}>/)
    assert.match(workspaceSource, /<ChartTopToolbar[\s\S]*fullscreenActive=\{fullscreen \|\| fallbackFullscreen\}[\s\S]*onToggleFullscreen=\{handleToggleFullscreen\}/)
    assert.match(workspaceSource, /<KLineChartPanel[\s\S]*fullscreenActive=\{fullscreen \|\| fallbackFullscreen\}/)
    assert.match(panelSource, /fullscreenActive:\s*boolean/)
    assert.doesNotMatch(panelSource, /onToggleFullscreen:\s*\(\)\s*=>\s*void/)
    assert.doesNotMatch(panelSource, /requestFullscreen\(\)/)
  })

  it('expands the whole chart workspace for native and fallback fullscreen', () => {
    assert.match(workspaceStyles, /\.workspace:fullscreen\s*{[^}]*width:\s*100vw[^}]*height:\s*100vh[^}]*}/s)
    assert.match(workspaceStyles, /\.workspaceExpanded\s*{[^}]*position:\s*fixed[^}]*inset:\s*0[^}]*z-index:\s*1000[^}]*}/s)
  })

  it('lazy loads the indicator settings modal with an accessible fallback', () => {
    assert.match(workspaceSource, /const IndicatorSettingsModal = lazy\(/)
    assert.match(workspaceSource, /import\('\.\/IndicatorSettingsModal'\)/)
    assert.match(workspaceSource, /<Suspense fallback=\{<div className=\{styles\.modalLoading\} role="status">\{t\('chart\.loadingIndicatorSettings'\)\}<\/div>}/)
    assert.match(workspaceStyles, /\.modalLoading\s*{/)
  })

  it('lazy loads the drawing toolbar with a stable rail fallback', () => {
    assert.doesNotMatch(workspaceSource, /import \{ ChartDrawingToolbar \} from '\.\/ChartDrawingToolbar'/)
    assert.match(workspaceSource, /const ChartDrawingToolbar = lazy\(/)
    assert.match(workspaceSource, /import\('\.\/ChartDrawingToolbar'\)/)
    assert.match(workspaceSource, /<Suspense fallback=\{<DrawingToolbarFallback \/>}>[\s\S]*<ChartDrawingToolbar[\s\S]*<\/Suspense>/)
    assert.match(workspaceSource, /function DrawingToolbarFallback\(\)/)
    assert.match(workspaceSource, /className=\{styles\.drawingToolbarLoading\}/)
    assert.match(workspaceStyles, /\.drawingToolbarLoading\s*{[\s\S]*width:\s*46px/)
    assert.match(workspaceStyles, /\.drawingToolbarLoading\s*{[\s\S]*min-width:\s*46px/)
    assert.match(workspaceStyles, /\.drawingToolbarLoading\s*{[\s\S]*min-height:\s*320px/)
    assert.match(workspaceStyles, /@media\s*\(max-width:\s*768px\)\s*{[\s\S]*\.drawingToolbarLoading\s*{[\s\S]*display:\s*none/)
  })
})
