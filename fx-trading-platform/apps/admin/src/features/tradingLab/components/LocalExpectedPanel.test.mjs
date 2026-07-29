import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const panelSource = readFileSync(
  new URL('./LocalExpectedPanel.tsx', import.meta.url),
  'utf8',
)
const bannerSource = readFileSync(
  new URL('./NegativeModeBanner.tsx', import.meta.url),
  'utf8',
)
const hookSource = readFileSync(
  new URL('../localExpected/useLocalExpectedScenario.ts', import.meta.url),
  'utf8',
)

test('LocalExpectedPanel wires all five scheduler states and bounded Oracle summaries', () => {
  for (const state of [
    'IDLE',
    'CALCULATING',
    'CALCULATED',
    'BLOCKED',
    'FAILED',
  ]) {
    assert.equal(panelSource.includes(`'${state}'`), true, state)
  }
  for (const field of [
    'runnerIssues',
    'accountSummary',
    'risk',
    'warnings',
  ]) {
    assert.equal(panelSource.includes(field), true, field)
  }
  assert.equal(panelSource.includes('.ticks'), false)
  assert.equal(/>\s*(?:Pass|Fail)\s*</.test(panelSource), false)
})

test('negative mode is an explicit warning and never turns BLOCKED into success', () => {
  assert.equal(bannerSource.includes('role="alert"'), true)
  assert.equal(bannerSource.includes('负向模式'), true)
  assert.equal(bannerSource.includes('expectedError'), true)
  assert.equal(panelSource.includes('负向模式通过'), false)
  assert.equal(panelSource.includes('BLOCKED'), true)
})

test('React hook subscribes once, schedules scenarios, and disposes its scheduler', () => {
  assert.equal(hookSource.includes('useSyncExternalStore'), true)
  assert.equal(hookSource.includes('schedule(scenario)'), true)
  assert.match(
    hookSource,
    /useLayoutEffect\(\(\) => \{\s*scheduler\.schedule\(scenario\)/,
  )
  assert.equal(hookSource.includes('.dispose()'), true)
  assert.equal(hookSource.includes('useLocalExpectedScenario'), true)
})
