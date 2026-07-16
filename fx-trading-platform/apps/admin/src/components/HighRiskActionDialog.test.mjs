import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const componentPath = join(currentDir, 'HighRiskActionDialog.tsx')
const stylesPath = join(currentDir, 'HighRiskActionDialog.css')
const componentSource = existsSync(componentPath) ? readFileSync(componentPath, 'utf8') : ''
const stylesSource = existsSync(stylesPath) ? readFileSync(stylesPath, 'utf8') : ''

describe('HighRiskActionDialog', () => {
  it('uses an explicit details, confirmation, and completion flow', () => {
    assert.match(componentSource, /type DialogStage = 'details' \| 'confirm' \| 'completed'/)
    assert.match(componentSource, /setStage\('confirm'\)/)
    assert.match(componentSource, /stage === 'completed'/)
  })

  it('requires a trimmed reason before the second confirmation', () => {
    assert.match(componentSource, /const trimmedReason = reason\.trim\(\)/)
    assert.match(componentSource, /disabled=\{[^}]*!trimmedReason/)
    assert.match(componentSource, /aria-required="true"/)
  })

  it('generates and displays one request id for the operation', () => {
    assert.match(componentSource, /function createRequestId\(\)/)
    assert.match(componentSource, /crypto\.randomUUID\(\)/)
    assert.match(componentSource, /data-testid="high-risk-request-id"/)
    assert.match(componentSource, /requestId/)
  })

  it('prevents double submission while the operation is pending', () => {
    assert.match(componentSource, /const pendingRef = useRef\(false\)/)
    assert.match(componentSource, /if \(pendingRef\.current\) return/)
    assert.match(componentSource, /pendingRef\.current = true/)
    assert.match(componentSource, /disabled=\{pending/)
  })

  it('passes reason and request id to the operation and displays its audit id', () => {
    assert.match(componentSource, /onExecute\(\{ reason: trimmedReason, requestId \}\)/)
    assert.match(componentSource, /setAuditId\(result\.auditId\)/)
    assert.match(componentSource, /data-testid="high-risk-audit-id"/)
  })

  it('disables reset and explains every cleanup blocker', () => {
    assert.match(componentSource, /action === 'reset' && cleanupBlockers\.length > 0/)
    assert.match(componentSource, /cleanupBlockers\.map/)
    assert.match(componentSource, /resetBlocked/)
  })

  it('keeps context scrollable and actions reachable at 390px', () => {
    assert.match(stylesSource, /@media \(max-width: 390px\)/)
    assert.match(stylesSource, /max-height: 100dvh/)
    assert.match(stylesSource, /overflow-y: auto/)
    assert.match(stylesSource, /position: sticky/)
  })
})
