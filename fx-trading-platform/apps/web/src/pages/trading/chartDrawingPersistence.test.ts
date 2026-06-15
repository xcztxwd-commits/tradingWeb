import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import {
  buildRiskTemplateDrawings,
  clonePersistedDrawing,
  getChartDrawingStorageKey,
  loadPersistedChartDrawings,
  savePersistedChartDrawings
} from './chartDrawingPersistence.ts'

class MemoryStorage {
  private readonly values = new Map<string, string>()

  getItem(key: string) {
    return this.values.get(key) ?? null
  }

  setItem(key: string, value: string) {
    this.values.set(key, value)
  }

  removeItem(key: string) {
    this.values.delete(key)
  }
}

describe('chart drawing persistence', () => {
  it('persists serializable drawing overlays per symbol', () => {
    const storage = new MemoryStorage()
    savePersistedChartDrawings(
      'BTCUSDT',
      [
        {
          id: 'drawing_1',
          name: 'segment',
          groupId: 'trading-page-drawings',
          points: [{ timestamp: 1781506800000, value: 62000 }],
          styles: { line: { color: '#fcd535', size: 2 } },
          lock: true,
          visible: true
        },
        { id: 'bad' },
        null
      ],
      storage
    )

    assert.equal(getChartDrawingStorageKey('btcusdt'), 'fx-trading-platform:chart-drawings:v1:BTCUSDT')
    assert.deepEqual(loadPersistedChartDrawings('BTCUSDT', storage), [
      {
        id: 'drawing_1',
        name: 'segment',
        groupId: 'trading-page-drawings',
        points: [{ timestamp: 1781506800000, value: 62000 }],
        styles: { line: { color: '#fcd535', size: 2 } },
        lock: true,
        visible: true
      }
    ])
  })

  it('clones drawings and builds a simple risk template around the last price', () => {
    const clone = clonePersistedDrawing({
      id: 'drawing_1',
      name: 'segment',
      groupId: 'trading-page-drawings',
      points: [{ timestamp: 1781506800000, value: 62000 }]
    })

    assert.equal(clone.id, undefined)
    assert.equal(clone.name, 'segment')
    assert.deepEqual(clone.points, [{ timestamp: 1781506800000, value: 62000 }])

    const template = buildRiskTemplateDrawings(62000)
    assert.deepEqual(template.map((item) => item.extendData), ['Entry', 'Take profit', 'Stop loss'])
    assert.deepEqual(template.map((item) => item.points?.[0]?.value), [62000, 63240, 61380])
  })
})
