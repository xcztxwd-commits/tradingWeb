import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const workspacePath = join(currentDir, 'TradingLabDesktopWorkspace.tsx')
const source = existsSync(workspacePath)
  ? readFileSync(workspacePath, 'utf8')
  : ''

describe('Trading Lab desktop workspace composition', () => {
  it('loads only existing local drafts or imported canonical JSON', () => {
    assert.match(source, /createTradingLabDraftStore/)
    assert.match(source, /\.list\(\)/)
    assert.match(source, /\.get\(/)
    assert.match(source, /importTradingLabScenarioJson/)
    assert.match(source, /\.text\(\)/)
    assert.match(source, /\.put\(/)
    assert.doesNotMatch(source, /createDefaultScenario/)
    assert.doesNotMatch(source, /adminApi|fetch\(|\/api\//)
  })

  it('exposes the scenario import file input through the stable smoke selector', () => {
    const importInput = source.match(
      /<input\s+[\s\S]*?ref=\{importInputRef\}[\s\S]*?\/>/,
    )?.[0] ?? ''

    assert.match(importInput, /type="file"/)
    assert.match(importInput, /accept="application\/json,\.json"/)
    assert.match(importInput, /onChange=\{handleImport\}/)
    assert.match(
      importInput,
      /data-testid="trading-lab-scenario-import-input"/,
    )
  })

  it('autosaves immutable timeline edits and recalculates current issues', () => {
    assert.match(source, /createTradingLabDraftAutosave/)
    assert.match(source, /autosave\.schedule\(/)
    assert.match(source, /validateScenario\(activeScenario\)/)
    assert.match(source, /<TimelineEditor/)
    assert.match(source, /actions=\{activeScenario\.timeline\}/)
    assert.match(source, /onChange=\{handleTimelineChange\}/)
  })

  it('fences stale async draft work across lock transitions and unmount', () => {
    assert.match(source, /createTradingLabWorkspaceAsyncGate/)
    assert.match(
      source,
      /useLayoutEffect\(\(\) => \{\s*asyncGate\.setLocked\(locked\)/,
    )
    assert.match(source, /asyncGate\.isCurrent\(operation/)
    assert.match(source, /asyncGate\.dispose\(\)/)
    assert.match(source, /gateLifecycleRef/)
    assert.match(source, /queueMicrotask\(/)
    assert.match(source, /disabled=\{locked \|\| 'error' in runtime\}/)
  })

  it('composes negative and local expected panels without pass-fail judgment', () => {
    assert.match(source, /<NegativeModeBanner/)
    assert.match(source, /<LocalExpectedPanel/)
    assert.match(source, /useLocalExpectedScenario/)
    assert.doesNotMatch(source, />\s*(?:Pass|Fail|通过|失败)\s*</)
  })

  it('delegates the bounded chart and emits one narrow validated run request', () => {
    assert.match(source, /import \{ TradingLabChartSection \}/)
    assert.match(source, /<TradingLabChartSection/)
    assert.match(source, /chartEvidence/)
    assert.match(source, /actualTicks=\{chartEvidence\.actualTicks\}/)
    assert.match(source, /markers=\{chartEvidence\.markers\}/)
    assert.match(source, /onRunRequest/)
    assert.match(source, /localExpected\.status === 'CALCULATED'/)
    assert.match(source, /localExpected\.status === 'BLOCKED'/)
    assert.match(source, /保存并运行/)
    assert.doesNotMatch(
      source,
      /EventSource|streamTradingLabRun|ReportPanel|RunProgress|generateMarketTicks|\bfetch\s*\(/,
    )
    assert.doesNotMatch(source, /pauseRun|resumeRun|cancelRun/)
  })

  it('renders an explicit frozen server scenario without mutating local drafts', () => {
    assert.match(source, /scenarioOverride/)
    assert.match(source, /scenarioOverride === undefined/)
    assert.match(source, /activeScenario/)
    assert.match(source, /locked/)
  })

  it('generates editable deterministic scenarios without starting a Run', () => {
    assert.match(source, /generateRandomScenario/)
    assert.match(source, /scenarioFingerprint/)
    assert.match(source, /data-testid="trading-lab-random-seed"/)
    assert.match(source, /data-testid="trading-lab-generate-random"/)
    assert.match(source, /data-testid="trading-lab-rerandomize"/)
    assert.match(source, /data-testid="trading-lab-scenario-hash"/)
    assert.match(source, /replaceScenario\(\(\) => generated\)/)

    const generationHandlers = source.match(
      /const generateFromSeed[\s\S]*?const handleRunRequest/,
    )?.[0] ?? ''
    assert.match(generationHandlers, /generateRandomScenario/)
    assert.doesNotMatch(generationHandlers, /onRunRequest/)
  })
})
