import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const source = readFileSync(join(currentDir, 'useTradingLabRunSession.ts'), 'utf8')
const controllerSource = readFileSync(
  join(currentDir, 'tradingLabRunSessionController.ts'),
  'utf8',
)

describe('Trading Lab run session React adapter', () => {
  it('wires the exact production APIs into the single controller seam', () => {
    assert.match(source, /createTradingLabRunSessionController/)
    assert.match(source, /getRun:\s*getTradingLabRun/)
    assert.match(source, /getScenario:\s*getTradingLabScenario/)
    assert.match(source, /createScenario:\s*createTradingLabScenario/)
    assert.match(source, /createRun:\s*createTradingLabRun/)
    assert.match(source, /controlRun:\s*controlTradingLabRun/)
    assert.match(source, /streamRun:\s*streamTradingLabRun/)
    assert.match(
      source,
      /normalizeScenario:\s*normalizeTradingLabScenarioDocument/,
    )
  })

  it('keeps URL history and popstate as a narrow browser adapter', () => {
    assert.match(source, /writeTradingLabRunLocation/)
    assert.match(source, /window\.history\.pushState/)
    assert.match(source, /readTradingLabRunLocation/)
    assert.match(source, /addEventListener\('popstate'/)
    assert.match(source, /removeEventListener\('popstate'/)
    assert.match(source, /controller\.setLocation\(currentRunLocation\(\)\)/)
    assert.match(
      source,
      /addEventListener\('popstate'[\s\S]*removeEventListener\('popstate'[\s\S]*\}, \[\s*controller,\s*\]\)/,
    )
  })

  it('subscribes to one inert controller and delegates all commands', () => {
    assert.match(source, /useState\(\(\)\s*=>/)
    assert.match(source, /useSyncExternalStore\(/)
    assert.match(source, /controller\.setAccess\(\{ canView, canExecute \}\)/)
    assert.match(
      source,
      /useEffect\(\(\) => \{\s*controller\.setAccess\(\{ canView, canExecute \}\)\s*\}, \[\s*canExecute,\s*canView,\s*controller,\s*\]\)/,
    )
    assert.match(source, /controller\.requestRun\(request\)/)
    assert.match(source, /controller\.controlRun\(action\)/)
    assert.match(source, /controller\.acceptAuthoritativeRun\(run\)/)
    assert.doesNotMatch(
      source,
      /ownerGeneration|AbortController|retryAttempt|FINAL_RUN_REFRESH|CREATE_UNKNOWN/,
    )
  })

  it('delays disposal so the React Strict Mode probe can retain the controller', () => {
    assert.match(source, /lifecycleGenerationRef/)
    assert.match(source, /globalThis\.queueMicrotask/)
    assert.match(
      source,
      /useEffect\(\(\) => \{[\s\S]*return \(\) => \{[\s\S]*controller\.setAccess\(\{\s*canView: false,\s*canExecute: false,\s*\}\)[\s\S]*\}, \[\s*controller,\s*\]\)/,
    )
    assert.match(
      source,
      /lifecycleGenerationRef\.current === cleanupGeneration[\s\S]*controller\.dispose\(\)/,
    )
  })

  it('keeps all fetch, stream, timer, and owner orchestration in the controller', () => {
    assert.match(controllerSource, /ownerGeneration/)
    assert.match(controllerSource, /AbortController/)
    assert.match(controllerSource, /retryTimer/)
    assert.match(controllerSource, /CREATE_UNKNOWN/)
    assert.match(controllerSource, /FINAL_RUN_REFRESH/)
    assert.doesNotMatch(controllerSource, /EventSource/)

    const controlStart = controllerSource.indexOf('const controlRun =')
    const controlEnd = controllerSource.indexOf('\n\n  return Object.freeze', controlStart)
    assert.notEqual(controlStart, -1)
    assert.notEqual(controlEnd, -1)
    const controlSource = controllerSource.slice(controlStart, controlEnd)

    assert.ok(
      controlSource.indexOf('dependencies.controlRun(')
        < controlSource.indexOf('dependencies.getRun('),
    )
    assert.doesNotMatch(controlSource, /state:\s*result\.state/)
    assert.doesNotMatch(controlSource, /pauseRequested:\s*result\.pauseRequested/)
    assert.doesNotMatch(controlSource, /cancelRequested:\s*result\.cancelRequested/)
  })
})
