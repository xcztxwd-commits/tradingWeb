import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import type {
  TimelineAction,
  TimelineActionType,
} from '../model/types.ts'
import {
  TimelineEditError,
  createTimelineAction,
  editTimeline,
  switchTimelineActionType,
  type TimelineCommand,
} from './timelineCommands.ts'

const ACTION_TYPES: readonly TimelineActionType[] = [
  'PLACE_ORDER',
  'CANCEL_ORDER',
  'CANCEL_ALL',
  'SET_POSITION_MODE',
  'SET_MARGIN_MODE',
  'SET_LEVERAGE',
  'ADD_MARGIN',
  'REMOVE_MARGIN',
  'APPLY_FUNDING',
  'PLACE_OCO',
]

const PARAMETER_SKELETONS: Readonly<
  Record<TimelineActionType, Record<string, unknown>>
> = {
  PLACE_ORDER: {
    side: 'BUY',
    orderType: 'MARKET',
    quantity: '',
    quantityUnit: 'BASE',
  },
  CANCEL_ORDER: { clientOrderId: '' },
  CANCEL_ALL: {},
  SET_POSITION_MODE: { positionMode: 'ONE_WAY' },
  SET_MARGIN_MODE: { marginMode: 'CROSS' },
  SET_LEVERAGE: { leverage: 1 },
  ADD_MARGIN: { amount: '', positionSide: 'BOTH' },
  REMOVE_MARGIN: { amount: '', positionSide: 'BOTH' },
  APPLY_FUNDING: { positionSide: 'BOTH' },
  PLACE_OCO: {
    side: 'SELL',
    quantity: '',
    quantityUnit: 'BASE',
    limitPrice: '',
    stopTriggerPrice: '',
    triggerPriceType: 'LAST_PRICE',
  },
}

function action(
  id: string,
  sequence: number,
  overrides: Partial<TimelineAction> = {},
): TimelineAction {
  return {
    id,
    sequence,
    type: 'PLACE_ORDER',
    symbol: 'BTCUSDT',
    productType: 'CRYPTO_SPOT',
    trigger: {
      type: 'GROUP',
      operator: 'ALL',
      items: [
        { type: 'VIRTUAL_TIME', atSecond: 7 },
        {
          type: 'PRICE',
          priceType: 'LAST',
          operator: 'GTE',
          value: '101.',
        },
      ],
    },
    parameters: {
      side: 'BUY',
      orderType: 'LIMIT',
      quantity: '1.',
      price: '101.',
      attachedProtections: [{
        protectionType: 'STOP_LOSS',
        triggerPrice: '90',
      }],
    },
    overrides: {
      nested: { slippageRate: '0.001' },
    },
    expectedError: {
      status: 400,
      code: 'EXPECTED_REJECTION',
    },
    ...overrides,
  }
}

function assertDeepDetached(
  input: TimelineAction,
  output: TimelineAction,
): void {
  assert.notEqual(output, input)
  assert.notEqual(output.trigger, input.trigger)
  assert.notEqual(output.parameters, input.parameters)
  assert.notEqual(output.overrides, input.overrides)
  assert.notEqual(output.expectedError, input.expectedError)

  assert.equal(input.trigger.type, 'GROUP')
  assert.equal(output.trigger.type, 'GROUP')
  assert.notEqual(output.trigger.items, input.trigger.items)
  assert.notEqual(output.trigger.items[0], input.trigger.items[0])
  assert.notEqual(
    output.parameters.attachedProtections,
    input.parameters.attachedProtections,
  )
  assert.notEqual(
    (output.overrides?.nested as Record<string, unknown> | undefined),
    (input.overrides?.nested as Record<string, unknown> | undefined),
  )
}

describe('timeline action skeletons', () => {
  it('creates every declared action type as a typed but editable skeleton', () => {
    for (const type of ACTION_TYPES) {
      const created = createTimelineAction(type, `new-${type}`)

      assert.equal(created.id, `new-${type}`)
      assert.equal(created.sequence, 1)
      assert.equal(created.type, type)
      assert.equal(created.symbol, '')
      assert.equal(
        created.productType,
        ['SET_POSITION_MODE', 'SET_MARGIN_MODE', 'SET_LEVERAGE',
          'ADD_MARGIN', 'REMOVE_MARGIN', 'APPLY_FUNDING'].includes(type)
          ? 'LINEAR_PERP'
          : 'CRYPTO_SPOT',
      )
      assert.deepEqual(created.trigger, {
        type: 'VIRTUAL_TIME',
        atSecond: 1,
      })
      assert.deepEqual(created.parameters, PARAMETER_SKELETONS[type])
      assert.equal(created.overrides, undefined)
      assert.equal(created.expectedError, undefined)
    }
  })

  it('switches type without leaking stale parameters, overrides or expected errors', () => {
    const input = action('switch-me', 9)

    const switched = switchTimelineActionType(input, 'SET_LEVERAGE')

    assert.deepEqual(switched, {
      id: 'switch-me',
      sequence: 9,
      type: 'SET_LEVERAGE',
      symbol: 'BTCUSDT',
      productType: 'CRYPTO_SPOT',
      trigger: input.trigger,
      parameters: { leverage: 1 },
    })
    assert.notEqual(switched, input)
    assert.notEqual(switched.trigger, input.trigger)
    assert.equal(switched.overrides, undefined)
    assert.equal(switched.expectedError, undefined)
    assert.equal('price' in switched.parameters, false)
  })
})

describe('editTimeline', () => {
  it('appends and continuously resequences while deeply detaching every action', () => {
    const first = action('first', 40)
    const added = action('added', 99, {
      trigger: { type: 'VIRTUAL_TIME', atSecond: 7 },
    })
    const before = structuredClone([first, added])

    const result = editTimeline(
      [first],
      { type: 'ADD', action: added },
      false,
    )

    assert.deepEqual(result.map(({ id, sequence }) => ({ id, sequence })), [
      { id: 'first', sequence: 1 },
      { id: 'added', sequence: 2 },
    ])
    assertDeepDetached(first, result[0]!)
    assert.notEqual(result[1], added)
    assert.notEqual(result[1]?.trigger, added.trigger)
    assert.deepEqual([first, added], before)
  })

  it('rejects blank and duplicate ids for add and duplicate', () => {
    const input = [action('first', 1)]

    for (const command of [
      { type: 'ADD', action: action(' ', 2) },
      { type: 'ADD', action: action('first', 2) },
      { type: 'DUPLICATE', actionId: 'first', newId: '' },
      { type: 'DUPLICATE', actionId: 'first', newId: 'first' },
    ] satisfies TimelineCommand[]) {
      assert.throws(
        () => editTimeline(input, command, false),
        (error) => error instanceof TimelineEditError
          && (error.code === 'ACTION_ID_BLANK'
            || error.code === 'ACTION_ID_DUPLICATE'),
      )
    }
  })

  it('duplicates immediately after the source without sharing nested data', () => {
    const source = action('source', 8)
    const trailing = action('trailing', 3)

    const result = editTimeline(
      [source, trailing],
      {
        type: 'DUPLICATE',
        actionId: 'source',
        newId: 'source-copy',
      },
      false,
    )

    assert.deepEqual(result.map(({ id, sequence }) => [id, sequence]), [
      ['source', 1],
      ['source-copy', 2],
      ['trailing', 3],
    ])
    assertDeepDetached(source, result[0]!)
    assertDeepDetached(source, result[1]!)
    assert.notEqual(result[0]?.trigger, result[1]?.trigger)
    assert.notEqual(result[0]?.parameters, result[1]?.parameters)
  })

  it('deletes without cascading or repairing AFTER_ACTION references', () => {
    const source = action('source', 1)
    const dependent = action('dependent', 2, {
      trigger: {
        type: 'GROUP',
        operator: 'ANY',
        items: [{
          type: 'AFTER_ACTION',
          actionId: 'source',
          delaySeconds: 0,
        }],
      },
    })

    const result = editTimeline(
      [source, dependent],
      { type: 'DELETE', actionId: 'source' },
      false,
    )

    assert.equal(result.length, 1)
    assert.equal(result[0]?.sequence, 1)
    assert.deepEqual(result[0]?.trigger, dependent.trigger)
    assert.notEqual(result[0]?.trigger, dependent.trigger)
  })

  it('replaces one action and detaches the entire successful result', () => {
    const first = action('first', 8)
    const second = action('second', 9)
    const replacement = action('second', 100, {
      parameters: { side: 'SELL', orderType: 'MARKET', quantity: '-' },
    })

    const result = editTimeline(
      [first, second],
      { type: 'REPLACE', action: replacement },
      false,
    )

    assert.deepEqual(result.map(({ id, sequence }) => [id, sequence]), [
      ['first', 1],
      ['second', 2],
    ])
    assert.equal(result[1]?.parameters.quantity, '-')
    assertDeepDetached(first, result[0]!)
    assert.notEqual(result[1], replacement)
    assert.notEqual(result[1]?.parameters, replacement.parameters)
  })

  it('moves the dragged source immediately before the drop target', () => {
    const one = action('one', 20, {
      trigger: { type: 'VIRTUAL_TIME', atSecond: 5 },
    })
    const two = action('two', 10, {
      trigger: { type: 'VIRTUAL_TIME', atSecond: 5 },
    })
    const three = action('three', 30, {
      trigger: { type: 'VIRTUAL_TIME', atSecond: 5 },
    })

    const result = editTimeline(
      [one, two, three],
      { type: 'MOVE_BEFORE', actionId: 'three', targetId: 'one' },
      false,
    )

    assert.deepEqual(result.map(({ id, sequence }) => [id, sequence]), [
      ['three', 1],
      ['one', 2],
      ['two', 3],
    ])
    assert.deepEqual(
      result.map(({ trigger }) => trigger),
      [
        { type: 'VIRTUAL_TIME', atSecond: 5 },
        { type: 'VIRTUAL_TIME', atSecond: 5 },
        { type: 'VIRTUAL_TIME', atSecond: 5 },
      ],
    )
  })

  it('moves one slot up or down and treats boundaries as reference-stable no-ops', () => {
    const input = [action('one', 1), action('two', 2), action('three', 3)]

    const up = editTimeline(
      input,
      { type: 'MOVE_BY', actionId: 'three', offset: -1 },
      false,
    )
    const down = editTimeline(
      input,
      { type: 'MOVE_BY', actionId: 'one', offset: 1 },
      false,
    )

    assert.deepEqual(up.map(({ id }) => id), ['one', 'three', 'two'])
    assert.deepEqual(down.map(({ id }) => id), ['two', 'one', 'three'])
    assert.equal(
      editTimeline(
        input,
        { type: 'MOVE_BY', actionId: 'one', offset: -1 },
        false,
      ),
      input,
    )
    assert.equal(
      editTimeline(
        input,
        { type: 'MOVE_BY', actionId: 'three', offset: 1 },
        false,
      ),
      input,
    )
  })

  it('keeps stale and already-satisfied move commands as reference-stable no-ops', () => {
    const input = [action('one', 1), action('two', 2)]

    for (const command of [
      { type: 'DELETE', actionId: 'stale' },
      { type: 'REPLACE', action: action('stale', 7) },
      { type: 'DUPLICATE', actionId: 'stale', newId: 'copy' },
      { type: 'MOVE_BEFORE', actionId: 'stale', targetId: 'one' },
      { type: 'MOVE_BEFORE', actionId: 'one', targetId: 'stale' },
      { type: 'MOVE_BEFORE', actionId: 'one', targetId: 'one' },
      { type: 'MOVE_BEFORE', actionId: 'one', targetId: 'two' },
      { type: 'MOVE_BY', actionId: 'stale', offset: 1 },
    ] satisfies TimelineCommand[]) {
      assert.equal(editTimeline(input, command, false), input)
    }
  })

  it('returns the original reference for every locked command before validation', () => {
    const input = [action('one', 1), action('two', 2)]
    const commands: TimelineCommand[] = [
      { type: 'ADD', action: action('', 3) },
      { type: 'DELETE', actionId: 'one' },
      { type: 'DUPLICATE', actionId: 'one', newId: '' },
      { type: 'REPLACE', action: action('one', 9) },
      { type: 'MOVE_BEFORE', actionId: 'two', targetId: 'one' },
      { type: 'MOVE_BY', actionId: 'one', offset: 1 },
    ]

    for (const command of commands) {
      assert.equal(editTimeline(input, command, true), input)
    }
  })
})
