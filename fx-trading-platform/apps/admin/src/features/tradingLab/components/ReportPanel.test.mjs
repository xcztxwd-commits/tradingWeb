import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const source = readFileSync(join(currentDir, 'ReportPanel.tsx'), 'utf8')

describe('Trading Lab Task 10 report panel boundary', () => {
  it('keeps independent report authorities explicit', () => {
    assert.match(source, /canView:\s*boolean/)
    assert.match(source, /canExecute:\s*boolean/)
    assert.match(source, /canSuperAdmin:\s*boolean/)
    assert.match(source, /!canView/)
    assert.match(source, /!canExecute/)
    assert.match(source, /canSuperAdmin/)
  })

  it('does not query report detail until the authoritative run is terminal', () => {
    assert.match(source, /TERMINAL_RUN_STATES/)
    assert.match(source, /expectedReportId/)
    assert.match(source, /报告生成中/)
    assert.match(source, /getTradingLabReport/)
    assert.match(source, /loaded\.scenarioId\s*!==\s*run\.scenarioId/)
  })

  it('generation-fences late metadata and mutation results', () => {
    assert.match(source, /generationRef/)
    assert.match(source, /expectedGeneration/)
    assert.match(source, /expectedReportId/)
    assert.match(source, /busy/)
    assert.match(source, /currentTradingLabReport/)
    assert.match(source, /readyReport/)
  })

  it('uses explicit response-loss reconciliation for retention and deletion', () => {
    assert.match(source, /reconcileTradingLabReportDeletion/)
    assert.match(source, /reconcileTradingLabReportPermanent/)
    assert.match(source, /onRunRefresh/)
    assert.match(source, /UNKNOWN/)
  })

  it('lists schema names honestly without implementing a raw chunk preview', () => {
    for (const key of [
      'metadata',
      'actor',
      'environment',
      'scenario',
      'modelVersion',
      'configSnapshot',
      'localCalculation',
      'lifecycle',
      'apiTrace',
      'marketTicks',
      'checkpoints',
      'actualState',
      'errors',
      'cleanup',
    ]) {
      assert.match(source, new RegExp(`['"]${key}['"]`))
    }
    assert.match(source, /仅列出固定 Schema/)
    assert.match(source, /下载或打印/)
    assert.doesNotMatch(source, /loadRaw|loadChunk|chunkCursor|physicalChunk/)
  })

  it('shows bounded report identity, version, model, hashes, and timestamps', () => {
    for (const field of [
      'runId',
      'scenarioId',
      'modelVersion',
      'configSnapshotHash',
      'codeVersion',
      'createdAt',
      'completedAt',
      'version',
    ]) {
      assert.match(source, new RegExp(`readyReport\\.${field}`))
    }
  })

  it('delegates raw transfer and never materializes report content', () => {
    assert.match(source, /downloadReport/)
    assert.match(source, /printRawReport/)
    assert.doesNotMatch(
      source,
      /response\.(?:text|json|arrayBuffer|blob)\s*\(/,
    )
    assert.doesNotMatch(source, /\bfetch\s*\(|innerHTML|document\.write/)
  })
})
