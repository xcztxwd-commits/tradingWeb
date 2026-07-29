import assert from 'node:assert/strict'
import test from 'node:test'
import {
  P0_CASES,
  P0_REGISTRY_FINGERPRINT
} from './p0-user-trading-cases.mjs'
import {
  aggregateReport,
  planResume
} from './p0-user-trading-artifacts.mjs'

function selectedState(caseId) {
  return {
    definitions: P0_CASES,
    registryFingerprint: P0_REGISTRY_FINGERPRINT,
    selection: {
      caseIds: [caseId],
      phases: [],
      profiles: [],
      viewports: []
    }
  }
}

function passingResult(caseId, evidence = {}) {
  const definition = P0_CASES.find(({ id }) => id === caseId)
  return {
    id: caseId,
    status: 'PASS',
    scopeComplete: true,
    subruns: definition.requiredSubruns.map((subrun) => ({
      ...subrun,
      status: 'PASS'
    })),
    ...evidence
  }
}

test('aggregate rejects PASS without cleanup and financial evidence', () => {
  const report = aggregateReport(
    selectedState('SPOT-01'),
    [passingResult('SPOT-01')]
  )

  assert.equal(report.verdict, 'FAIL')
  assert.deepEqual(report.issues, ['INCOMPLETE_EVIDENCE: SPOT-01'])
  assert.equal(report.counts.PASS, 0)
})

test('aggregate accepts complete non-empty financial evidence', () => {
  const report = aggregateReport(
    selectedState('SPOT-01'),
    [passingResult('SPOT-01', {
      cleanup: { status: 'PASS' },
      financialCalculation: {
        status: 'PASS',
        checks: [{ kind: 'SPOT_SETTLEMENT' }]
      }
    })]
  )

  assert.equal(report.verdict, 'PARTIAL_PASS')
  assert.deepEqual(report.issues, [])
  assert.equal(report.counts.PASS, 1)
})

test('resume reruns a nominal PASS that lacks required evidence', () => {
  const state = {
    ...selectedState('SPOT-01'),
    cases: {
      'SPOT-01': passingResult('SPOT-01')
    }
  }
  const plan = planResume(state, P0_CASES, state.selection)

  assert.deepEqual(plan.entries, [{
    id: 'SPOT-01',
    action: 'RUN',
    reason: 'INCOMPLETE_SUBRUNS',
    scopeComplete: false,
    subruns: P0_CASES.find(({ id }) => id === 'SPOT-01').requiredSubruns
  }])
})
