import type {
  TimelineAction,
  TimelineActionType,
  TimelineTrigger,
} from '../model/types.ts'

export const TIMELINE_ACTION_TYPES: readonly TimelineActionType[] = [
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

export type TimelineCommand =
  | { type: 'ADD'; action: TimelineAction }
  | { type: 'DELETE'; actionId: string }
  | { type: 'DUPLICATE'; actionId: string; newId: string }
  | { type: 'REPLACE'; action: TimelineAction }
  | { type: 'MOVE_BEFORE'; actionId: string; targetId: string }
  | { type: 'MOVE_BY'; actionId: string; offset: -1 | 1 }

export type TimelineEditErrorCode =
  | 'ACTION_ID_BLANK'
  | 'ACTION_ID_DUPLICATE'

export class TimelineEditError extends Error {
  readonly code: TimelineEditErrorCode

  constructor(
    code: TimelineEditErrorCode,
    message: string,
  ) {
    super(message)
    this.name = 'TimelineEditError'
    this.code = code
  }
}

function parameterSkeleton(
  type: TimelineActionType,
): Record<string, unknown> {
  switch (type) {
    case 'PLACE_ORDER':
      return {
        side: 'BUY',
        orderType: 'MARKET',
        quantity: '',
        quantityUnit: 'BASE',
      }
    case 'CANCEL_ORDER':
      return { clientOrderId: '' }
    case 'CANCEL_ALL':
      return {}
    case 'SET_POSITION_MODE':
      return { positionMode: 'ONE_WAY' }
    case 'SET_MARGIN_MODE':
      return { marginMode: 'CROSS' }
    case 'SET_LEVERAGE':
      return { leverage: 1 }
    case 'ADD_MARGIN':
    case 'REMOVE_MARGIN':
      return { amount: '', positionSide: 'BOTH' }
    case 'APPLY_FUNDING':
      return { positionSide: 'BOTH' }
    case 'PLACE_OCO':
      return {
        side: 'SELL',
        quantity: '',
        quantityUnit: 'BASE',
        limitPrice: '',
        stopTriggerPrice: '',
        triggerPriceType: 'LAST_PRICE',
      }
  }
}

function defaultProductType(
  type: TimelineActionType,
): TimelineAction['productType'] {
  switch (type) {
    case 'SET_POSITION_MODE':
    case 'SET_MARGIN_MODE':
    case 'SET_LEVERAGE':
    case 'ADD_MARGIN':
    case 'REMOVE_MARGIN':
    case 'APPLY_FUNDING':
      return 'LINEAR_PERP'
    default:
      return 'CRYPTO_SPOT'
  }
}

function cloneValue(value: unknown): unknown {
  if (Array.isArray(value)) {
    return value.map(cloneValue)
  }
  if (value !== null && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value).map(([key, child]) => [key, cloneValue(child)]),
    )
  }
  return value
}

function cloneAction(
  action: TimelineAction,
  sequence = action.sequence,
): TimelineAction {
  return {
    id: action.id,
    sequence,
    type: action.type,
    symbol: action.symbol,
    productType: action.productType,
    trigger: cloneValue(action.trigger) as TimelineTrigger,
    parameters: cloneValue(action.parameters) as Record<string, unknown>,
    ...(action.overrides === undefined
      ? {}
      : {
          overrides: cloneValue(action.overrides) as Record<string, unknown>,
        }),
    ...(action.expectedError === undefined
      ? {}
      : {
          expectedError: {
            status: action.expectedError.status,
            code: action.expectedError.code,
          },
        }),
  }
}

function resequence(actions: readonly TimelineAction[]): TimelineAction[] {
  return actions.map((action, index) => cloneAction(action, index + 1))
}

function requireAvailableId(
  actions: readonly TimelineAction[],
  id: string,
): void {
  if (id.trim().length === 0) {
    throw new TimelineEditError('ACTION_ID_BLANK', 'Action id cannot be blank')
  }
  if (actions.some((action) => action.id === id)) {
    throw new TimelineEditError(
      'ACTION_ID_DUPLICATE',
      `Action id already exists: ${id}`,
    )
  }
}

export function createTimelineAction(
  type: TimelineActionType,
  id: string,
): TimelineAction {
  return {
    id,
    sequence: 1,
    type,
    symbol: '',
    productType: defaultProductType(type),
    trigger: {
      type: 'VIRTUAL_TIME',
      atSecond: 1,
    },
    parameters: parameterSkeleton(type),
  }
}

export function switchTimelineActionType(
  action: TimelineAction,
  type: TimelineActionType,
): TimelineAction {
  return {
    id: action.id,
    sequence: action.sequence,
    type,
    symbol: action.symbol,
    productType: action.productType,
    trigger: cloneValue(action.trigger) as TimelineTrigger,
    parameters: parameterSkeleton(type),
  }
}

export function editTimeline(
  actions: readonly TimelineAction[],
  command: TimelineCommand,
  locked: boolean,
): TimelineAction[] {
  if (locked) {
    return actions as TimelineAction[]
  }

  switch (command.type) {
    case 'ADD':
      requireAvailableId(actions, command.action.id)
      return resequence([...actions, command.action])
    case 'DELETE': {
      const index = actions.findIndex((action) => action.id === command.actionId)
      if (index < 0) {
        return actions as TimelineAction[]
      }
      return resequence([
        ...actions.slice(0, index),
        ...actions.slice(index + 1),
      ])
    }
    case 'DUPLICATE': {
      requireAvailableId(actions, command.newId)
      const index = actions.findIndex((action) => action.id === command.actionId)
      if (index < 0) {
        return actions as TimelineAction[]
      }
      const source = actions[index]!
      return resequence([
        ...actions.slice(0, index + 1),
        { ...source, id: command.newId },
        ...actions.slice(index + 1),
      ])
    }
    case 'REPLACE': {
      const index = actions.findIndex(
        (action) => action.id === command.action.id,
      )
      if (index < 0) {
        return actions as TimelineAction[]
      }
      return resequence(actions.map((action, actionIndex) =>
        actionIndex === index ? command.action : action))
    }
    case 'MOVE_BEFORE': {
      const sourceIndex = actions.findIndex(
        (action) => action.id === command.actionId,
      )
      const targetIndex = actions.findIndex(
        (action) => action.id === command.targetId,
      )
      if (
        sourceIndex < 0
        || targetIndex < 0
        || sourceIndex === targetIndex
        || sourceIndex + 1 === targetIndex
      ) {
        return actions as TimelineAction[]
      }
      const reordered = [...actions]
      const [source] = reordered.splice(sourceIndex, 1)
      const adjustedTargetIndex = reordered.findIndex(
        (action) => action.id === command.targetId,
      )
      reordered.splice(adjustedTargetIndex, 0, source!)
      return resequence(reordered)
    }
    case 'MOVE_BY': {
      const sourceIndex = actions.findIndex(
        (action) => action.id === command.actionId,
      )
      const targetIndex = sourceIndex + command.offset
      if (
        sourceIndex < 0
        || targetIndex < 0
        || targetIndex >= actions.length
      ) {
        return actions as TimelineAction[]
      }
      const reordered = [...actions]
      const target = reordered[targetIndex]!
      reordered[targetIndex] = reordered[sourceIndex]!
      reordered[sourceIndex] = target
      return resequence(reordered)
    }
  }
}
