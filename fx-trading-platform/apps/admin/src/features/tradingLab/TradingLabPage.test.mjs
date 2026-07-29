import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const pageSource = readFileSync(join(currentDir, 'TradingLabPage.tsx'), 'utf8')
const stylesSource = readFileSync(join(currentDir, 'TradingLabPage.css'), 'utf8')

describe('Trading Lab Task 9 page composition', () => {
  it('places the desktop workspace behind the runtime mount guard', () => {
    assert.match(pageSource, /TradingLabDesktopGuard/)
    assert.match(pageSource, /TradingLabDesktopWorkspace/)
    assert.match(
      pageSource,
      /<TradingLabDesktopGuard>\s*<TradingLabDesktopRuntime\s*\/>\s*<\/TradingLabDesktopGuard>/,
    )
    assert.match(
      pageSource,
      /function TradingLabDesktopRuntime\(\)[\s\S]*<TradingLabDesktopWorkspace/,
    )
  })

  it('does not mount the durable session owner below the desktop-width guard', () => {
    const pageEntryStart = pageSource.indexOf('export function TradingLabPage()')
    const desktopRuntimeStart = pageSource.indexOf(
      'function TradingLabDesktopRuntime()',
    )
    assert.notEqual(pageEntryStart, -1)
    assert.notEqual(desktopRuntimeStart, -1)
    const pageEntrySource = pageSource.slice(
      pageEntryStart,
      desktopRuntimeStart,
    )

    assert.doesNotMatch(pageEntrySource, /useTradingLabRunSession\(/)
    assert.match(
      pageEntrySource,
      /<TradingLabDesktopGuard>\s*<TradingLabDesktopRuntime\s*\/>\s*<\/TradingLabDesktopGuard>/,
    )
    assert.match(
      pageSource,
      /function TradingLabDesktopRuntime\(\)[\s\S]*useTradingLabRunSession\(/,
    )
  })

  it('owns the durable run session, controls and environment without direct transport calls', () => {
    assert.match(pageSource, /useTradingLabRunSession/)
    assert.match(pageSource, /<RunProgress/)
    assert.match(pageSource, /<EnvironmentStatus/)
    assert.match(pageSource, /hasAdminAuthority\('TRADING_LAB_EXECUTE'\)/)
    assert.match(pageSource, /hasAdminAuthority\('SUPER_ADMIN'\)/)
    assert.match(pageSource, /scenarioOverride=/)
    assert.match(
      pageSource,
      /chartEvidence=\{runSession\.session\?\.chartEvidence\}/,
    )
    assert.match(pageSource, /onRunRequest=/)
    assert.doesNotMatch(pageSource, /session\?\.scenario !== null/)
    assert.doesNotMatch(pageSource, /\bfetch\s*\(|EventSource|validation-backend|18087|18088/)
  })

  it('keeps runId URL restore inside the session owner and never guesses a latest run', () => {
    assert.match(pageSource, /runId/)
    assert.doesNotMatch(pageSource, /latestRun|latest-run|recentRun|lastRun/)
  })

  it('does not use a CSS-only narrow-screen shell', () => {
    assert.doesNotMatch(pageSource, /trading-lab-narrow-state/)
    assert.doesNotMatch(stylesSource, /\.trading-lab-narrow-state/)
    assert.doesNotMatch(
      stylesSource,
      /@media\s*\(max-width:\s*1279px\)[\s\S]*display:\s*none/,
    )
  })

  it('keeps the workspace layout focused into three Task 7 columns', () => {
    assert.match(stylesSource, /\.trading-lab-workspace-grid/)
    assert.match(
      stylesSource,
      /grid-template-columns:\s*minmax\([^;]+minmax\([^;]+minmax\(/,
    )
  })
})

describe('Trading Lab Task 10 evidence composition', () => {
  it('mounts authoritative actual state and report panels outside the local editor workspace', () => {
    assert.match(pageSource, /ActualStatePanel/)
    assert.match(pageSource, /ReportPanel/)
    assert.match(pageSource, /actualState=/)
    assert.match(pageSource, /onRunRefresh=/)

    const workspaceClose = pageSource.indexOf('/>', pageSource.indexOf(
      '<TradingLabDesktopWorkspace',
    ))
    const actualPanel = pageSource.indexOf('<ActualStatePanel')
    const reportPanel = pageSource.indexOf('<ReportPanel')
    assert.ok(workspaceClose > 0)
    assert.ok(actualPanel > workspaceClose)
    assert.ok(reportPanel > actualPanel)
  })

  it('passes all three independent authorities without adding direct report transport', () => {
    assert.match(pageSource, /canView=\{canView\}/)
    assert.match(pageSource, /canExecute=\{canExecute\}/)
    assert.match(pageSource, /canSuperAdmin=\{canSuperAdmin\}/)
    assert.doesNotMatch(
      pageSource,
      /downloadReport|printRawReport|getTradingLabReport|\bfetch\s*\(/,
    )
  })
})
