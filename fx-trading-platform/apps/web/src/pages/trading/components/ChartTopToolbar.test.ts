import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const source = readFileSync(join(currentDir, 'ChartTopToolbar.tsx'), 'utf8')
const styles = readFileSync(join(currentDir, 'ChartTopToolbar.module.css'), 'utf8')

describe('ChartTopToolbar indicator menu', () => {
  it('opens a chart settings dialog with display toggles and timezone selection', () => {
    assert.match(source, /Settings\b/)
    assert.match(source, /chartSettingsOpen/)
    assert.match(source, /chartTimezoneOptions/)
    assert.match(source, /aria-label=\{t\('chart\.chartSettings'\)\}/)
    assert.match(source, /role="dialog"[\s\S]*t\('chart\.chartSettings'\)/)
    assert.match(source, /settings\.axisSettings\.latestPrice/)
    assert.match(source, /settings\.axisSettings\.highPriceMark/)
    assert.match(source, /settings\.axisSettings\.lowPriceMark/)
    assert.match(source, /settings\.axisSettings\.countdown/)
    assert.match(source, /settings\.layoutSettings\.gridLines/)
    assert.match(source, /settings\.timezone/)
    assert.match(source, /onChartSettingsChange/)
    assert.match(source, /onResetChartSettings/)
    assert.match(styles, /\.settingsDialog\s*{/)
    assert.match(styles, /\.settingsGrid\s*{/)
    assert.match(styles, /\.switchInput\s*{/)
  })

  it('places the fullscreen control in the fixed right toolbar actions', () => {
    assert.match(source, /fullscreenActive:\s*boolean/)
    assert.match(source, /onToggleFullscreen:\s*\(\)\s*=>\s*void/)
    assert.match(source, /<div className=\{styles\.toolbarActions\}>[\s\S]*styles\.fullscreenButton/)
    assert.match(source, /aria-pressed=\{fullscreenActive\}/)
    assert.match(styles, /\.toolbarActions\s*{[\s\S]*flex:\s*0\s+0\s+auto/)
    assert.match(styles, /\.toolbarActions\s*{[\s\S]*margin-right:\s*28px/)
    assert.match(styles, /\.fullscreenButton\s*{[\s\S]*width:\s*28px[\s\S]*height:\s*28px/)
    assert.doesNotMatch(styles, /\.fullscreenButton\s*{[^}]*position:\s*absolute/s)
  })

  it('keeps KLineCharts price scale, realtime, and camera image-save controls', () => {
    assert.match(source, /priceScaleModeOptions/)
    assert.match(source, /onPriceScaleModeChange:\s*\(mode: ChartSettings\['axisSettings'\]\['priceScaleMode'\]\) => void/)
    assert.match(source, /onScrollToRealtime:\s*\(\) => void/)
    assert.match(source, /onExportChart:\s*\(\) => void/)
    assert.match(source, /chart\.priceScaleMode/)
    assert.match(source, /settings\.axisSettings\.priceScaleMode/)
    assert.match(source, /onPriceScaleModeChange\(event\.target\.value as ChartSettings\['axisSettings'\]\['priceScaleMode'\]\)/)
    assert.match(source, /chart\.scrollToRealtime/)
    assert.match(source, /\bCamera\b/)
    assert.match(source, /<Camera size=\{15\} \/>/)
    assert.match(source, /chart\.exportImage/)
    assert.doesNotMatch(source, /\bDownload\b/)
    assert.match(styles, /\.actionButton\s*{[\s\S]*width:\s*28px[\s\S]*height:\s*28px/)
  })

  it('uses a custom chart type dropdown instead of the native select menu', () => {
    assert.match(source, /chartTypeMenuOpen/)
    assert.match(source, /chartTypeMenuRef/)
    assert.match(source, /className=\{styles\.chartTypeButton\}/)
    assert.match(source, /role="menu"[\s\S]*className=\{styles\.chartTypeDropdown\}/)
    assert.match(source, /role="menuitemradio"/)
    assert.match(source, /aria-checked=\{item\.value === settings\.chartType\}/)
    assert.match(styles, /\.chartTypeButton,\s*\.priceScaleButton\s*{/)
    assert.match(styles, /\.chartTypeDropdown,\s*\.priceScaleDropdown\s*{/)
    assert.match(styles, /\.chartTypeOption\[aria-checked='true'\],\s*\.priceScaleOption\[aria-checked='true'\]\s*{/)
    assert.doesNotMatch(source, /<select value=\{settings\.chartType\}/)
  })

  it('uses a custom price scale dropdown instead of the native select menu', () => {
    assert.match(source, /priceScaleMenuOpen/)
    assert.match(source, /priceScaleMenuRef/)
    assert.match(source, /activePriceScaleMode/)
    assert.match(source, /className=\{styles\.priceScaleButton\}/)
    assert.match(source, /role="menu"[\s\S]*className=\{styles\.priceScaleDropdown\}/)
    assert.match(source, /aria-checked=\{item\.value === settings\.axisSettings\.priceScaleMode\}/)
    assert.match(styles, /\.chartTypeButton,\s*\.priceScaleButton\s*{/)
    assert.match(styles, /\.chartTypeDropdown,\s*\.priceScaleDropdown\s*{/)
    assert.match(styles, /\.chartTypeOption\[aria-checked='true'\],\s*\.priceScaleOption\[aria-checked='true'\]\s*{/)
    assert.doesNotMatch(source, /<label className=\{styles\.selectLabel\}>[\s\S]*<Scale size=\{15\} \/>[\s\S]*aria-label=\{t\('chart\.priceScaleMode'\)\}/)
  })

  it('blocks interval shortcuts while toolbar popovers are open', () => {
    assert.match(source, /intervalDropdownOpen \|\| chartTypeMenuOpen \|\| priceScaleMenuOpen \|\| indicatorMenuOpen \|\| chartSettingsOpen/)
    assert.match(source, /\[\s*favoriteIntervals,\s*intervalDropdownOpen,\s*chartTypeMenuOpen,\s*priceScaleMenuOpen,\s*indicatorMenuOpen,\s*chartSettingsOpen/)
  })

  it('exposes KLineCharts mark and jump controls without tooltip style or zoom controls', () => {
    assert.match(source, /onHighLowPriceMarksChange:\s*\(enabled: boolean\) => void/)
    assert.match(source, /onJumpToTimestamp:\s*\(timestamp: number\) => void/)
    assert.match(source, /settings\.axisSettings\.highLowPriceMarks/)
    assert.match(source, /type="datetime-local"/)
    assert.match(styles, /\.jumpForm\s*{/)
    assert.doesNotMatch(source, /onTooltipStyleChange/)
    assert.doesNotMatch(source, /chart\.tooltipStyle/)
    assert.doesNotMatch(styles, /\.selectLabel\b/)
    assert.doesNotMatch(source, /\bZoomIn\b/)
    assert.doesNotMatch(source, /onBarSpaceChange/)
    assert.doesNotMatch(source, /onResetZoom/)
    assert.doesNotMatch(source, /onZoomAtTimestamp/)
    assert.doesNotMatch(source, /settings\.axisSettings\.barSpace/)
    assert.doesNotMatch(source, /type="range"/)
    assert.doesNotMatch(styles, /\.zoomControl\s*{/)
  })

  it('does not expose a chart shortcut settings panel in the toolbar', () => {
    assert.match(source, /shortcutSettings/)
    assert.doesNotMatch(source, /\bKeyboard\b/)
    assert.doesNotMatch(source, /shortcutPanelOpen/)
    assert.doesNotMatch(source, /onShortcutSettingChange/)
    assert.doesNotMatch(styles, /\.shortcutPanel\s*{/)
  })

  it('shows only starred intervals as quick period buttons', () => {
    assert.match(source, /symbol:\s*string/)
    assert.match(source, /getChartIntervalOptions\(symbol\)/)
    assert.match(source, /quickChartIntervals\(settings,\s*intervalOptions\)/)
    assert.match(source, /favoriteIntervals\.map/)
    assert.match(source, /favoriteIntervals=\{visibleFavoriteIntervals\}/)
    assert.match(source, /onFavoriteIntervalToggle=\{onFavoriteIntervalToggle\}/)
    assert.match(source, /onFavoriteIntervalToggle:\s*\(interval: TradingPeriod\) => void/)
  })

  it('uses the same symbol-specific options for the dropdown and quick favorites', () => {
    const dropdownSource = readFileSync(join(currentDir, 'IntervalDropdown.tsx'), 'utf8')

    assert.match(source, /<IntervalDropdown[\s\S]*options=\{intervalOptions\}/)
    assert.match(source, /const visibleFavoriteIntervals = favoriteIntervals\.map\(\(item\) => item\.value\)/)
    assert.match(source, /favoriteIntervals=\{visibleFavoriteIntervals\}/)
    assert.match(dropdownSource, /options:\s*ChartIntervalOption\[\]/)
    assert.match(dropdownSource, /favoriteIntervals:\s*TradingPeriod\[\]/)
    assert.match(dropdownSource, /onFavoriteIntervalToggle:\s*\(interval: TradingPeriod\) => void/)
    assert.match(dropdownSource, /Star/)
    assert.match(dropdownSource, /options\.map/)
    assert.doesNotMatch(dropdownSource, /allChartIntervals/)
    assert.match(dropdownSource, /lockedFavorite = favorite && favoriteIntervals\.length <= 1/)
    assert.match(dropdownSource, /disabled=\{lockedFavorite\}/)
    assert.doesNotMatch(dropdownSource, /Keyboard,\s*/)
    assert.doesNotMatch(dropdownSource, /<Keyboard/)
    assert.doesNotMatch(dropdownSource, /Pencil/)
    assert.doesNotMatch(dropdownSource, /Plus/)
    assert.doesNotMatch(dropdownSource, /intervalHelpTitle/)
    assert.doesNotMatch(dropdownSource, /intervalHelpDescription/)
    assert.doesNotMatch(dropdownSource, /customInterval/)
    assert.doesNotMatch(dropdownSource, /common\.edit/)
  })

  it('fits every interval option without horizontal scrolling', () => {
    assert.match(styles, /\.intervalDropdown\s*{[\s\S]*overflow:\s*visible/)
    assert.match(styles, /\.intervalGrid\s*{[\s\S]*grid-template-columns:\s*repeat\(4,\s*minmax\(0,\s*1fr\)\)/)
    assert.match(styles, /@media\s*\(max-width:\s*760px\)\s*{[\s\S]*\.toolbarScroller\s*{[\s\S]*overflow:\s*visible/)
    assert.doesNotMatch(styles, /overflow-x:\s*auto/)
  })

  it('keeps indicator choices folded behind one toolbar button', () => {
    assert.match(source, /indicatorMenuOpen/)
    assert.match(source, /indicatorConfigOptions/)
    assert.match(source, /chart\.technicalIndicators/)
    assert.doesNotMatch(source, /indicatorNames\.map/)
  })

  it('gives toolbar buttons and dropdowns visible motion-safe interaction states', () => {
    assert.match(styles, /\.group button:focus-visible/)
    assert.match(styles, /\.intervalDropdown,\s*\.chartTypeDropdown,\s*\.priceScaleDropdown,\s*\.indicatorDropdown\s*{[\s\S]*animation:\s*toolbarDropdownIn/)
    assert.match(styles, /@keyframes toolbarDropdownIn/)
    assert.match(styles, /@media\s*\(prefers-reduced-motion:\s*reduce\)\s*{[\s\S]*\.intervalDropdown,\s*\.chartTypeDropdown,\s*\.priceScaleDropdown,\s*\.indicatorDropdown\s*{[\s\S]*animation:\s*none/)
  })

  it('keeps desktop dropdowns outside the toolbar scroll clipping context', () => {
    assert.match(styles, /\.toolbarScroller\s*{[\s\S]*overflow:\s*visible/)
    assert.match(styles, /@media\s*\(max-width:\s*760px\)\s*{[\s\S]*\.toolbarScroller\s*{[\s\S]*overflow:\s*visible/)
  })

  it('keeps the right-edge price scale dropdown inside narrow viewports', () => {
    assert.match(styles, /\.priceScaleDropdown\s*{[\s\S]*left:\s*auto[\s\S]*right:\s*0/)
  })

  it('does not render the KLineCharts brand text in the chart toolbar', () => {
    assert.doesNotMatch(source, /KLineCharts/)
    assert.doesNotMatch(source, /styles\.status/)
  })

  it('closes the indicator dropdown when the user clicks outside it', () => {
    assert.match(source, /indicatorMenuRef/)
    assert.match(source, /pointerdown/)
    assert.match(source, /setIndicatorMenuOpen\(false\)/)
    assert.match(source, /indicatorMenuRef\.current\.contains\(event\.target\)/)
  })
})
