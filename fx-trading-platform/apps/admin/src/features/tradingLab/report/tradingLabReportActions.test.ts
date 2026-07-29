import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import {
  currentTradingLabReport,
  reconcileTradingLabReportDeletion,
  reconcileTradingLabReportPermanent,
  type TradingLabReportActionDependencies,
} from './tradingLabReportActions.ts'

const REPORT_ID = '00000000-0000-0000-0000-000000000a10'
const RUN_ID = '00000000-0000-0000-0000-000000000a11'
const TOKEN = 'task-10-admin-token'
const SCENARIO_ID = '00000000-0000-0000-0000-000000000a12'
const OTHER_SCENARIO_ID = '99999999-9999-4999-8999-999999999998'

const report = (permanent: boolean) => ({
  id: REPORT_ID,
  runId: RUN_ID,
  scenarioId: SCENARIO_ID,
  status: 'COMPLETED',
  modelVersion: 'browser-oracle-v1',
  configSnapshotHash: 'a'.repeat(64),
  codeVersion: 'task-10',
  uncompressedBytes: 1024,
  compressedBytes: 512,
  chunkCount: 1,
  retainedUntil: '2026-08-24T00:00:00Z',
  permanent,
  failureCode: null,
  failureMessage: null,
  createdAt: '2026-07-25T00:00:00Z',
  completedAt: '2026-07-25T00:00:01Z',
  version: permanent ? 2 : 1,
} as const)

const run = (reportId: string | null) => ({
  id: RUN_ID,
  scenarioId: SCENARIO_ID,
  reportId,
  state: 'COMPLETED',
  totalTicks: 1,
  pauseRequested: false,
  cancelRequested: false,
  version: 3,
} as const)

function notFound(): Error & { status: number } {
  return Object.assign(new Error('not found'), { status: 404 })
}

function networkLoss(): Error {
  return new Error('connection lost')
}

function dependencies(
  overrides: Partial<TradingLabReportActionDependencies> = {},
): TradingLabReportActionDependencies {
  return {
    deleteReport: async () => undefined,
    getReport: async () => {
      throw notFound()
    },
    getRun: async () => run(null),
    setPermanent: async () => ({
      reportId: REPORT_ID,
      permanent: true,
      version: 2,
    }),
    isNotFound: (error) => (
      typeof error === 'object'
      && error !== null
      && 'status' in error
      && error.status === 404
    ),
    ...overrides,
  }
}

describe('Trading Lab report mutation reconciliation', () => {
  it('exposes a report only when it matches the current terminal Run identity', () => {
    const current = report(false)
    assert.equal(currentTradingLabReport(run(REPORT_ID), current), current)
    assert.equal(currentTradingLabReport({
      ...run(REPORT_ID),
      id: '99999999-9999-4999-8999-999999999997',
    }, current), null)
    assert.equal(currentTradingLabReport({
      ...run(REPORT_ID),
      scenarioId: OTHER_SCENARIO_ID,
    }, current), null)
    assert.equal(currentTradingLabReport({
      ...run(REPORT_ID),
      state: 'RUNNING',
    }, current), null)
  })

  it('accepts deletion only after detail is 404 and the authoritative run clears reportId', async () => {
    const calls: string[] = []
    const result = await reconcileTradingLabReportDeletion(
      {
        reportId: REPORT_ID,
        runId: RUN_ID,
        scenarioId: SCENARIO_ID,
        runState: 'COMPLETED',
        token: TOKEN,
      },
      dependencies({
        deleteReport: async () => {
          calls.push('delete')
        },
        getReport: async () => {
          calls.push('detail')
          throw notFound()
        },
        getRun: async () => {
          calls.push('run')
          return run(null)
        },
      }),
    )

    assert.deepEqual(calls, ['delete', 'detail', 'run'])
    assert.deepEqual(result, {
      kind: 'DELETED',
      run: run(null),
    })
  })

  it('reconciles an ambiguous delete response without blindly retrying the mutation', async () => {
    let deletes = 0
    const result = await reconcileTradingLabReportDeletion(
      {
        reportId: REPORT_ID,
        runId: RUN_ID,
        scenarioId: SCENARIO_ID,
        runState: 'COMPLETED',
        token: TOKEN,
      },
      dependencies({
        deleteReport: async () => {
          deletes += 1
          throw networkLoss()
        },
      }),
    )

    assert.equal(deletes, 1)
    assert.equal(result.kind, 'DELETED')
  })

  it('reports PRESENT when the authoritative detail still exists', async () => {
    const current = report(false)
    const result = await reconcileTradingLabReportDeletion(
      {
        reportId: REPORT_ID,
        runId: RUN_ID,
        scenarioId: SCENARIO_ID,
        runState: 'COMPLETED',
        token: TOKEN,
      },
      dependencies({
        deleteReport: async () => {
          throw networkLoss()
        },
        getReport: async () => current,
      }),
    )

    assert.deepEqual(result, {
      kind: 'PRESENT',
      report: current,
    })
  })

  it('reports UNKNOWN when deletion cannot be reconciled', async () => {
    const result = await reconcileTradingLabReportDeletion(
      {
        reportId: REPORT_ID,
        runId: RUN_ID,
        scenarioId: SCENARIO_ID,
        runState: 'COMPLETED',
        token: TOKEN,
      },
      dependencies({
        deleteReport: async () => {
          throw networkLoss()
        },
        getReport: async () => {
          throw networkLoss()
        },
      }),
    )

    assert.deepEqual(result, { kind: 'UNKNOWN' })
  })

  it('confirms permanent state from the authoritative detail even after response loss', async () => {
    let writes = 0
    const current = report(true)
    const result = await reconcileTradingLabReportPermanent(
      {
        reportId: REPORT_ID,
        runId: RUN_ID,
        scenarioId: SCENARIO_ID,
        runState: 'COMPLETED',
        permanent: true,
        token: TOKEN,
      },
      dependencies({
        setPermanent: async () => {
          writes += 1
          throw networkLoss()
        },
        getReport: async () => current,
      }),
    )

    assert.equal(writes, 1)
    assert.deepEqual(result, {
      kind: 'CONFIRMED',
      report: current,
    })
  })

  it('does not claim success when the authoritative permanent flag differs', async () => {
    const current = report(false)
    const result = await reconcileTradingLabReportPermanent(
      {
        reportId: REPORT_ID,
        runId: RUN_ID,
        scenarioId: SCENARIO_ID,
        runState: 'COMPLETED',
        permanent: true,
        token: TOKEN,
      },
      dependencies({
        getReport: async () => current,
      }),
    )

    assert.deepEqual(result, {
      kind: 'NOT_APPLIED',
      report: current,
    })
  })

  it('reports UNKNOWN when the permanent action cannot be reconciled', async () => {
    const result = await reconcileTradingLabReportPermanent(
      {
        reportId: REPORT_ID,
        runId: RUN_ID,
        scenarioId: SCENARIO_ID,
        runState: 'COMPLETED',
        permanent: false,
        token: TOKEN,
      },
      dependencies({
        getReport: async () => {
          throw networkLoss()
        },
      }),
    )

    assert.deepEqual(result, { kind: 'UNKNOWN' })
  })

  it('rejects detail or run identities that do not match the initiating terminal Run', async () => {
    const mismatchedReport = {
      ...report(true),
      runId: '99999999-9999-4999-8999-999999999999',
    }
    const permanent = await reconcileTradingLabReportPermanent(
      {
        reportId: REPORT_ID,
        runId: RUN_ID,
        scenarioId: SCENARIO_ID,
        runState: 'COMPLETED',
        permanent: true,
        token: TOKEN,
      },
      dependencies({
        getReport: async () => mismatchedReport,
      }),
    )
    const deletion = await reconcileTradingLabReportDeletion(
      {
        reportId: REPORT_ID,
        runId: RUN_ID,
        scenarioId: SCENARIO_ID,
        runState: 'COMPLETED',
        token: TOKEN,
      },
      dependencies({
        getRun: async () => ({
          ...run(null),
          state: 'FAILED',
        }),
      }),
    )

    assert.deepEqual(permanent, { kind: 'UNKNOWN' })
    assert.deepEqual(deletion, { kind: 'UNKNOWN' })
  })

  it('rejects report and Run reconciliation with the wrong scenario identity', async () => {
    const permanent = await reconcileTradingLabReportPermanent(
      {
        reportId: REPORT_ID,
        runId: RUN_ID,
        scenarioId: SCENARIO_ID,
        runState: 'COMPLETED',
        permanent: true,
        token: TOKEN,
      },
      dependencies({
        getReport: async () => ({
          ...report(true),
          scenarioId: OTHER_SCENARIO_ID,
        }),
      }),
    )
    const deletion = await reconcileTradingLabReportDeletion(
      {
        reportId: REPORT_ID,
        runId: RUN_ID,
        scenarioId: SCENARIO_ID,
        runState: 'COMPLETED',
        token: TOKEN,
      },
      dependencies({
        getRun: async () => ({
          ...run(null),
          scenarioId: OTHER_SCENARIO_ID,
        }),
      }),
    )

    assert.deepEqual(permanent, { kind: 'UNKNOWN' })
    assert.deepEqual(deletion, { kind: 'UNKNOWN' })
  })
})
