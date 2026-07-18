import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { buildChartTradeMarkers, buildTradeMarkerOverlays } from './chartTradeMarkers.ts'
import type { OrderResponse, PositionResponse } from '@fx-platform/frontend-core'

describe('chart trade markers', () => {
  it('builds symbol-scoped marker rows from active orders and positions', () => {
    const markers = buildChartTradeMarkers(
      'BTCUSDT',
      [
        makeOrder({ id: 'order_1', price: '62100', createdAt: '2026-06-15T08:00:00.000Z' }),
        makeOrder({ id: 'order_2', symbol: 'ETHUSDT', price: '3200' })
      ],
      [
        makePosition({
          id: 'pos_1',
          openPrice: '62000',
          stopLoss: '61000',
          takeProfit: '64000',
          liquidationPrice: '54000',
          openedAt: '2026-06-15T07:00:00.000Z'
        })
      ]
    )

    assert.deepEqual(
      markers.map((marker) => `${marker.kind}:${marker.id}:${marker.price}:${marker.timestamp ?? ''}`),
      [
        'positionEntry:position:pos_1:entry:62000:1781506800000',
        'takeProfit:position:pos_1:takeProfit:64000:',
        'stopLoss:position:pos_1:stopLoss:61000:',
        'liquidation:position:pos_1:liquidation:54000:',
        'order:order:order_1:62100:1781510400000'
      ]
    )
  })

  it('maps marker rows to locked KLineCharts overlays', () => {
    const overlays = buildTradeMarkerOverlays(
      [
        {
          id: 'position:pos_1:entry',
          kind: 'positionEntry',
          label: 'BUY Entry',
          price: 62000,
          timestamp: 1781506800000,
          tone: 'buy'
        }
      ],
      '1m'
    )

    assert.equal(overlays.length, 2)
    assert.equal(overlays[0].name, 'simpleTag')
    assert.equal(overlays[0].groupId, 'trading-page-trade-markers')
    assert.equal(overlays[0].lock, true)
    assert.deepEqual(overlays[0].points, [
      { timestamp: 1781506800000, value: 62000 },
      { timestamp: 1781506860000, value: 62000 }
    ])
    assert.equal(overlays[1].name, 'simpleAnnotation')
  })
})

function makeOrder(patch: Partial<OrderResponse>): OrderResponse {
  return {
    id: 'order',
    symbol: 'BTCUSDT',
    side: 'BUY',
    orderType: 'LIMIT',
    status: 'NEW',
    lots: '0.1',
    price: '62000',
    executionPrice: null,
    createdAt: '2026-06-15T00:00:00.000Z',
    ...patch
  }
}

function makePosition(patch: Partial<PositionResponse>): PositionResponse {
  return {
    id: 'pos',
    symbol: 'BTCUSDT',
    side: 'BUY',
    lots: '0.1',
    openPrice: '62000',
    currentPrice: '62100',
    floatingPnl: '10',
    realizedPnl: '0',
    marginHeld: '100',
    status: 'OPEN',
    ...patch
  }
}
