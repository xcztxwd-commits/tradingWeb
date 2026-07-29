import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const source = readFileSync(join(currentDir, 'RunProgress.tsx'), 'utf8')

describe('Trading Lab run progress controls', () => {
  it('separates observed Tick from completed checkpoint and never displays stale GET counters', () => {
    assert.match(source, /已观察 Tick/)
    assert.match(source, /已完成 checkpoint/)
    assert.doesNotMatch(source, /processedTicks|currentStep|virtualCurrentAt|权威实时进度/)
  })

  it('derives controls from authoritative state and persisted request flags', () => {
    assert.match(source, /state === 'RUNNING'/)
    assert.match(source, /state === 'PAUSED'/)
    assert.match(source, /pauseRequested/)
    assert.match(source, /cancelRequested/)
    assert.match(
      source,
      /resumeEnabled[\s\S]+state === 'PAUSED'[\s\S]+pauseRequested === true/,
    )
    assert.match(source, /onControl\('pause'\)/)
    assert.match(source, /onControl\('resume'\)/)
    assert.match(source, /onControl\('cancel'\)/)
  })

  it('visually disables controls outside an active transport connection', () => {
    assert.match(source, /CONTROL_CONNECTION_STATES/)
    assert.match(source, /'CONNECTING'/)
    assert.match(source, /'OPEN'/)
    assert.match(source, /'RETRY_WAIT'/)
    assert.match(source, /controlConnectionReady[\s\S]+pauseEnabled/)
    assert.match(source, /pauseEnabled[\s\S]+controlConnectionReady/)
    assert.match(source, /resumeEnabled[\s\S]+controlConnectionReady/)
    assert.match(source, /cancelEnabled[\s\S]+controlConnectionReady/)
  })

  it('keeps validation state and transport state visibly separate', () => {
    assert.match(source, /主 Run 状态/)
    assert.match(source, /Validation 状态/)
    assert.match(source, /连接状态/)
  })

  it('exposes canonical run ownership before the terminal report is ready', () => {
    assert.match(source, /scenarioId:\s*string/)
    assert.match(source, /reportId:\s*string \| null/)
    assert.match(source, /<dt>Scenario ID<\/dt>/)
    assert.match(source, /<dd>\{run\?\.scenarioId \?\? '未附加'\}<\/dd>/)
    assert.match(source, /<dt>Run ID<\/dt>/)
    assert.match(source, /<dd>\{run\?\.id \?\? '未附加'\}<\/dd>/)
    assert.match(source, /<dt>Report ID<\/dt>/)
    assert.match(source, /<dd>\{run\?\.reportId \?\? '未附加'\}<\/dd>/)
  })
})
