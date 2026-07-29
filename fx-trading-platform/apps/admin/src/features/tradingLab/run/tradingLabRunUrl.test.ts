import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import {
  readTradingLabRunLocation,
  writeTradingLabRunLocation,
} from './tradingLabRunUrl.ts'

describe('Trading Lab run URL', () => {
  it('accepts exactly one canonical lowercase UUID', () => {
    assert.deepEqual(
      readTradingLabRunLocation(
        '?runId=00000000-0000-0000-0000-000000000751',
      ),
      {
        runId: '00000000-0000-0000-0000-000000000751',
        error: null,
      },
    )
  })

  it('keeps an absent runId in local draft mode', () => {
    assert.deepEqual(readTradingLabRunLocation('?tab=local'), {
      runId: null,
      error: null,
    })
  })

  it('rejects empty, duplicate, uppercase and malformed run ids', () => {
    for (const search of [
      '?runId=',
      '?runId=00000000-0000-0000-0000-000000000751&runId=00000000-0000-0000-0000-000000000752',
      '?runId=00000000-0000-0000-0000-000000000ABC',
      '?runId=not-a-uuid',
    ]) {
      const result = readTradingLabRunLocation(search)
      assert.equal(result.runId, null)
      assert.match(result.error ?? '', /runId/)
    }
  })

  it('writes one runId while preserving unrelated query fields', () => {
    assert.equal(
      writeTradingLabRunLocation(
        'https://admin.example/trading/lab?tab=local&runId=old#view',
        '00000000-0000-0000-0000-000000000751',
      ),
      '/trading/lab?tab=local&runId=00000000-0000-0000-0000-000000000751#view',
    )
  })
})
