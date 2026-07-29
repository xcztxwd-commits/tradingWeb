import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import {
  TradingLabChartMarkerError,
  buildTradingLabChartMarkerOverlays,
  tradingLabChartMarkerGroupId,
  tradingLabChartMarkerLabel,
  type TradingLabChartMarker,
  type TradingLabChartMarkerKind,
  type TradingLabChartMarkerOverlayInput,
} from './chartMarkers.ts'

const EPOCH = Date.parse('2026-07-25T00:00:00.000Z')
const kinds: readonly TradingLabChartMarkerKind[] = [
  'EXECUTION',
  'OPEN',
  'ADD',
  'REDUCE',
  'TAKE_PROFIT',
  'STOP_LOSS',
  'FUNDING',
  'LIQUIDATION',
]
const labels = ['成交', '开仓', '加仓', '减仓', '止盈', '止损', '资金费', '强平']

function marker(
  id: string,
  kind: TradingLabChartMarkerKind,
  overrides: Partial<TradingLabChartMarker> = {},
): TradingLabChartMarker {
  return {
    id,
    source: 'LOCAL',
    kind,
    productType: 'LINEAR_PERP',
    symbol: 'BTC-USDT-LAB',
    tickSequence: 1,
    virtualTime: '2026-07-25T00:00:01.000Z',
    price: '60000.1250',
    side: 'BUY',
    actionId: `action-${id}`,
    ...overrides,
  }
}

function input(
  markers: readonly TradingLabChartMarker[],
  overrides: Partial<TradingLabChartMarkerOverlayInput> = {},
): TradingLabChartMarkerOverlayInput {
  return {
    source: 'LOCAL',
    productType: 'LINEAR_PERP',
    symbol: 'BTC-USDT-LAB',
    markers,
    firstTimestamp: EPOCH,
    lastTimestamp: EPOCH + 60_000,
    ...overrides,
  }
}

function expectCode(code: string, operation: () => unknown): void {
  assert.throws(
    operation,
    (error: unknown) => (
      error instanceof TradingLabChartMarkerError
      && error.code === code
    ),
  )
}

describe('Trading Lab chart marker mapping', () => {
  it('maps all executed marker kinds to fixed Chinese labels and stable overlays', () => {
    const markers = kinds.map((kind, index) => marker(`marker-${index}`, kind))
    const overlays = buildTradingLabChartMarkerOverlays(input(markers))
    const groupId = tradingLabChartMarkerGroupId({
      source: 'LOCAL',
      productType: 'LINEAR_PERP',
      symbol: 'BTC-USDT-LAB',
    })

    assert.deepEqual(kinds.map(tradingLabChartMarkerLabel), labels)
    assert.deepEqual(overlays.map((overlay) => overlay.label), labels)
    assert.ok(overlays.every((overlay) => overlay.groupId === groupId))
    assert.deepEqual(
      overlays.map((overlay) => overlay.id),
      markers.map((value) => `${groupId}:${encodeURIComponent(value.id)}`),
    )
  })

  it('uses simpleAnnotation for a real decimal price without converting authority', () => {
    const [overlay] = buildTradingLabChartMarkerOverlays(input([
      marker('priced', 'OPEN', { price: '60000.1250' }),
    ]))

    assert.equal(overlay?.name, 'simpleAnnotation')
    assert.equal(overlay?.price, '60000.1250')
    assert.deepEqual(overlay?.points, [
      { timestamp: EPOCH + 1000, value: '60000.1250' },
      { timestamp: EPOCH + 1000, value: '60000.1250' },
    ])
  })

  it('uses a time-only vertical line for null price and never borrows candle close', () => {
    const [overlay] = buildTradingLabChartMarkerOverlays(input([
      marker('funding', 'FUNDING', { price: null }),
    ]))

    assert.equal(overlay?.name, 'verticalStraightLine')
    assert.equal(overlay?.price, null)
    assert.deepEqual(overlay?.points, [
      { timestamp: EPOCH + 1000 },
      { timestamp: EPOCH + 1000 },
    ])
    assert.equal(
      overlay?.points.some((point) => 'value' in point),
      false,
    )
  })

  it('filters source, composite identity, and out-of-window markers', () => {
    const overlays = buildTradingLabChartMarkerOverlays(input([
      marker('accepted', 'OPEN'),
      marker('actual', 'OPEN', { source: 'ACTUAL' }),
      marker('spot', 'OPEN', { productType: 'CRYPTO_SPOT' }),
      marker('other-symbol', 'OPEN', { symbol: 'ETH-USDT-LAB' }),
      marker('too-old', 'OPEN', {
        virtualTime: '2026-07-24T23:59:59.999Z',
      }),
      marker('too-new', 'OPEN', {
        virtualTime: '2026-07-25T00:01:00.001Z',
      }),
    ]))

    assert.deepEqual(overlays.map((overlay) => overlay.markerId), ['accepted'])
  })

  it('returns no overlays when the bounded chart window is empty', () => {
    assert.deepEqual(buildTradingLabChartMarkerOverlays(input(
      [marker('one', 'OPEN')],
      { firstTimestamp: null, lastTimestamp: null },
    )), [])
  })

  it('deduplicates exact marker replay and rejects conflicting ids', () => {
    const replay = marker('same', 'OPEN')
    const reorderedReplay: TradingLabChartMarker = {
      actionId: replay.actionId,
      side: replay.side,
      price: replay.price,
      virtualTime: replay.virtualTime,
      tickSequence: replay.tickSequence,
      symbol: replay.symbol,
      productType: replay.productType,
      kind: replay.kind,
      source: replay.source,
      id: replay.id,
    }
    assert.equal(
      buildTradingLabChartMarkerOverlays(input([replay, reorderedReplay])).length,
      1,
    )
    expectCode('CHART_MARKER_ID_CONFLICT', () => (
      buildTradingLabChartMarkerOverlays(input([
        replay,
        marker('same', 'LIQUIDATION'),
      ]))
    ))
  })

  it('fails closed for malformed id, sequence, UTC time, price, and window', () => {
    expectCode('CHART_MARKER_INVALID', () => (
      buildTradingLabChartMarkerOverlays(input([marker('', 'OPEN')]))
    ))
    expectCode('CHART_MARKER_INVALID', () => (
      buildTradingLabChartMarkerOverlays(input([
        marker('sequence', 'OPEN', { tickSequence: 0 }),
      ]))
    ))
    expectCode('CHART_MARKER_TIME_INVALID', () => (
      buildTradingLabChartMarkerOverlays(input([
        marker('time', 'OPEN', { virtualTime: '2026-02-30T00:00:00Z' }),
      ]))
    ))
    expectCode('CHART_MARKER_PRICE_INVALID', () => (
      buildTradingLabChartMarkerOverlays(input([
        marker('price', 'OPEN', { price: 'Infinity' }),
      ]))
    ))
    expectCode('CHART_MARKER_WINDOW_INVALID', () => (
      buildTradingLabChartMarkerOverlays(input(
        [marker('window', 'OPEN')],
        { firstTimestamp: EPOCH + 1, lastTimestamp: EPOCH },
      ))
    ))
  })

  it('does not mutate marker rows or the caller array', () => {
    const markers = [
      marker('one', 'OPEN'),
      marker('two', 'FUNDING', { price: null }),
    ]
    const before = structuredClone(markers)

    buildTradingLabChartMarkerOverlays(input(markers))

    assert.deepEqual(markers, before)
  })
})
