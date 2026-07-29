import type { ReactNode } from 'react'

import type {
  PriceType,
  ScenarioValidationIssue,
  TimelineAction,
  TimelineActionType,
  TimelineTrigger,
} from '../model/types.ts'
import {
  PRICE_FIELD_LABELS,
  PRICE_TYPE_LABELS,
} from '../timeline/priceLabels.ts'
import {
  TIMELINE_ACTION_TYPES,
  editTimeline,
  switchTimelineActionType,
} from '../timeline/timelineCommands.ts'

export type TimelineActionEditorProps = {
  action: TimelineAction
  locked: boolean
  issues: ScenarioValidationIssue[]
  onChange(action: TimelineAction): void
}

type FieldProps = {
  label: string
  value: string
  locked: boolean
  onChange(value: string): void
}

function TextField({
  label,
  value,
  locked,
  onChange,
}: FieldProps) {
  return (
    <label>
      <span>{label}</span>
      <input
        type="text"
        value={value}
        disabled={locked}
        onChange={(event) => {
          if (locked) {
            return
          }
          onChange(event.target.value)
        }}
      />
    </label>
  )
}

type SelectFieldProps = FieldProps & {
  children: ReactNode
}

function SelectField({
  label,
  value,
  locked,
  onChange,
  children,
}: SelectFieldProps) {
  return (
    <label>
      <span>{label}</span>
      <select
        value={value}
        disabled={locked}
        onChange={(event) => {
          if (locked) {
            return
          }
          onChange(event.target.value)
        }}
      >
        {children}
      </select>
    </label>
  )
}

type NumberFieldProps = {
  label: string
  value: number
  min?: number
  locked: boolean
  onChange(value: number): void
}

function NumberField({
  label,
  value,
  min,
  locked,
  onChange,
}: NumberFieldProps) {
  return (
    <label>
      <span>{label}</span>
      <input
        type="number"
        value={value}
        min={min}
        disabled={locked}
        onChange={(event) => {
          if (locked) {
            return
          }
          onChange(Number(event.target.value))
        }}
      />
    </label>
  )
}

function textValue(value: unknown): string {
  if (value === undefined || value === null) {
    return ''
  }
  return typeof value === 'object' ? JSON.stringify(value) : String(value)
}

function numberValue(value: unknown): number {
  return typeof value === 'number' ? value : Number(value)
}

function triggerSkeleton(type: TimelineTrigger['type']): TimelineTrigger {
  switch (type) {
    case 'VIRTUAL_TIME':
      return { type, atSecond: 1 }
    case 'PRICE':
      return {
        type,
        priceType: 'LAST',
        operator: 'GTE',
        value: '',
      }
    case 'AFTER_ACTION':
      return {
        type,
        actionId: '',
        delaySeconds: 0,
      }
    case 'GROUP':
      return {
        type,
        operator: 'ALL',
        items: [],
      }
  }
}

const HANDLED_PARAMETER_KEYS: Readonly<
  Record<TimelineActionType, readonly string[]>
> = {
  PLACE_ORDER: [
    'side',
    'orderType',
    'quantity',
    'quantityUnit',
    'price',
    'requestedPrice',
    'triggerPrice',
    'activationPrice',
    'stopLoss',
    'takeProfit',
    'reduceOnly',
  ],
  CANCEL_ORDER: ['clientOrderId'],
  CANCEL_ALL: [],
  SET_POSITION_MODE: ['positionMode'],
  SET_MARGIN_MODE: ['marginMode'],
  SET_LEVERAGE: ['leverage'],
  ADD_MARGIN: ['amount', 'positionSide'],
  REMOVE_MARGIN: ['amount', 'positionSide'],
  APPLY_FUNDING: ['positionSide'],
  PLACE_OCO: [
    'side',
    'quantity',
    'quantityUnit',
    'limitPrice',
    'stopTriggerPrice',
    'triggerPriceType',
  ],
}

function unhandledParameters(action: TimelineAction): Record<string, unknown> {
  const handled = new Set(HANDLED_PARAMETER_KEYS[action.type])
  return Object.fromEntries(
    Object.entries(action.parameters).filter(([key]) => !handled.has(key)),
  )
}

type ParameterFieldsProps = {
  action: TimelineAction
  locked: boolean
  onParameter(key: string, value: unknown): void
}

function PositionSideField({
  value,
  locked,
  onChange,
}: Omit<FieldProps, 'label'>) {
  return (
    <SelectField
      label="positionSide（持仓方向）"
      value={value}
      locked={locked}
      onChange={onChange}
    >
      <option value="BOTH">BOTH</option>
      <option value="LONG">LONG</option>
      <option value="SHORT">SHORT</option>
    </SelectField>
  )
}

function ParameterFields({
  action,
  locked,
  onParameter,
}: ParameterFieldsProps) {
  const parameter = (key: string) => textValue(action.parameters[key])
  switch (action.type) {
    case 'PLACE_ORDER':
      return (
        <>
          <SelectField
            label="side（买卖方向）"
            value={parameter('side')}
            locked={locked}
            onChange={(value) => onParameter('side', value)}
          >
            <option value="BUY">BUY</option>
            <option value="SELL">SELL</option>
          </SelectField>
          <SelectField
            label="orderType（订单类型）"
            value={parameter('orderType')}
            locked={locked}
            onChange={(value) => onParameter('orderType', value)}
          >
            {['MARKET', 'LIMIT', 'STOP_MARKET', 'STOP_LIMIT',
              'TRAILING_STOP_MARKET'].map((value) => (
                <option key={value} value={value}>{value}</option>
              ))}
          </SelectField>
          <TextField
            label="quantity（数量）"
            value={parameter('quantity')}
            locked={locked}
            onChange={(value) => onParameter('quantity', value)}
          />
          <SelectField
            label="quantityUnit（数量单位）"
            value={parameter('quantityUnit')}
            locked={locked}
            onChange={(value) => onParameter('quantityUnit', value)}
          >
            <option value="BASE">BASE</option>
            <option value="QUOTE">QUOTE</option>
            <option value="CONTRACTS">CONTRACTS</option>
          </SelectField>
          <TextField
            label={PRICE_FIELD_LABELS.price}
            value={parameter('price')}
            locked={locked}
            onChange={(value) => onParameter('price', value)}
          />
          <TextField
            label={PRICE_FIELD_LABELS.requestedPrice}
            value={parameter('requestedPrice')}
            locked={locked}
            onChange={(value) => onParameter('requestedPrice', value)}
          />
          <TextField
            label={PRICE_FIELD_LABELS.triggerPrice}
            value={parameter('triggerPrice')}
            locked={locked}
            onChange={(value) => onParameter('triggerPrice', value)}
          />
          <TextField
            label={PRICE_FIELD_LABELS.activationPrice}
            value={parameter('activationPrice')}
            locked={locked}
            onChange={(value) => onParameter('activationPrice', value)}
          />
          <TextField
            label={PRICE_FIELD_LABELS.stopLoss}
            value={parameter('stopLoss')}
            locked={locked}
            onChange={(value) => onParameter('stopLoss', value)}
          />
          <TextField
            label={PRICE_FIELD_LABELS.takeProfit}
            value={parameter('takeProfit')}
            locked={locked}
            onChange={(value) => onParameter('takeProfit', value)}
          />
          <label>
            <input
              type="checkbox"
              checked={action.parameters.reduceOnly === true}
              disabled={locked}
              onChange={(event) => {
                if (locked) {
                  return
                }
                onParameter('reduceOnly', event.target.checked)
              }}
            />
            reduceOnly（只减仓）
          </label>
        </>
      )
    case 'CANCEL_ORDER':
      return (
        <TextField
          label="clientOrderId（前置下单动作 ID）"
          value={parameter('clientOrderId')}
          locked={locked}
          onChange={(value) => onParameter('clientOrderId', value)}
        />
      )
    case 'CANCEL_ALL':
      return <p>该动作没有专用参数。</p>
    case 'SET_POSITION_MODE':
      return (
        <SelectField
          label="positionMode（持仓模式）"
          value={parameter('positionMode')}
          locked={locked}
          onChange={(value) => onParameter('positionMode', value)}
        >
          <option value="ONE_WAY">ONE_WAY</option>
          <option value="HEDGE">HEDGE</option>
        </SelectField>
      )
    case 'SET_MARGIN_MODE':
      return (
        <SelectField
          label="marginMode（保证金模式）"
          value={parameter('marginMode')}
          locked={locked}
          onChange={(value) => onParameter('marginMode', value)}
        >
          <option value="CROSS">CROSS</option>
          <option value="ISOLATED">ISOLATED</option>
        </SelectField>
      )
    case 'SET_LEVERAGE':
      return (
        <NumberField
          label="leverage（杠杆）"
          value={numberValue(action.parameters.leverage)}
          min={1}
          locked={locked}
          onChange={(value) => onParameter('leverage', value)}
        />
      )
    case 'ADD_MARGIN':
    case 'REMOVE_MARGIN':
      return (
        <>
          <TextField
            label="amount（保证金金额）"
            value={parameter('amount')}
            locked={locked}
            onChange={(value) => onParameter('amount', value)}
          />
          <PositionSideField
            value={parameter('positionSide')}
            locked={locked}
            onChange={(value) => onParameter('positionSide', value)}
          />
        </>
      )
    case 'APPLY_FUNDING':
      return (
        <PositionSideField
          value={parameter('positionSide')}
          locked={locked}
          onChange={(value) => onParameter('positionSide', value)}
        />
      )
    case 'PLACE_OCO':
      return (
        <>
          <SelectField
            label="side（买卖方向）"
            value={parameter('side')}
            locked={locked}
            onChange={(value) => onParameter('side', value)}
          >
            <option value="BUY">BUY</option>
            <option value="SELL">SELL</option>
          </SelectField>
          <TextField
            label="quantity（数量）"
            value={parameter('quantity')}
            locked={locked}
            onChange={(value) => onParameter('quantity', value)}
          />
          <SelectField
            label="quantityUnit（数量单位）"
            value={parameter('quantityUnit')}
            locked={locked}
            onChange={(value) => onParameter('quantityUnit', value)}
          >
            <option value="BASE">BASE</option>
            <option value="QUOTE">QUOTE</option>
            <option value="CONTRACTS">CONTRACTS</option>
          </SelectField>
          <TextField
            label="limitPrice（限价）"
            value={parameter('limitPrice')}
            locked={locked}
            onChange={(value) => onParameter('limitPrice', value)}
          />
          <TextField
            label="stopTriggerPrice（止损触发价）"
            value={parameter('stopTriggerPrice')}
            locked={locked}
            onChange={(value) => onParameter('stopTriggerPrice', value)}
          />
          <SelectField
            label="triggerPriceType（触发价格类型）"
            value={parameter('triggerPriceType')}
            locked={locked}
            onChange={(value) => onParameter('triggerPriceType', value)}
          >
            <option value="LAST_PRICE">LAST_PRICE</option>
            <option value="MARK_PRICE">MARK_PRICE</option>
          </SelectField>
        </>
      )
  }
}

export function TimelineActionEditor({
  action,
  locked,
  issues,
  onChange,
}: TimelineActionEditorProps) {
  function emit(nextAction: TimelineAction): void {
    if (locked) {
      return
    }
    const detached = editTimeline(
      [action],
      { type: 'REPLACE', action: nextAction },
      false,
    )[0]!
    onChange({
      ...detached,
      sequence: nextAction.sequence,
    })
  }

  function setParameter(key: string, value: unknown): void {
    if (locked) {
      return
    }
    emit({
      ...action,
      parameters: {
        ...action.parameters,
        [key]: value,
      },
    })
  }

  function setTrigger(trigger: TimelineTrigger): void {
    if (locked) {
      return
    }
    emit({ ...action, trigger })
  }

  const extraParameters = unhandledParameters(action)
  const trigger = action.trigger

  return (
    <fieldset className="trading-lab-action-editor" disabled={locked}>
      <legend>{action.id}</legend>
      <div className="trading-lab-action-fields">
        <SelectField
          label="type（动作类型）"
          value={action.type}
          locked={locked}
          onChange={(value) => {
            if (locked) {
              return
            }
            emit(switchTimelineActionType(
              action,
              value as TimelineActionType,
            ))
          }}
        >
          {TIMELINE_ACTION_TYPES.map((type) => (
            <option key={type} value={type}>{type}</option>
          ))}
        </SelectField>
        <TextField
          label="symbol（品种）"
          value={action.symbol}
          locked={locked}
          onChange={(symbol) => {
            if (locked) {
              return
            }
            emit({ ...action, symbol })
          }}
        />
        <SelectField
          label="productType（产品类型）"
          value={action.productType}
          locked={locked}
          onChange={(productType) => {
            if (locked) {
              return
            }
            emit({
              ...action,
              productType: productType as TimelineAction['productType'],
            })
          }}
        >
          <option value="CRYPTO_SPOT">CRYPTO_SPOT</option>
          <option value="LINEAR_PERP">LINEAR_PERP</option>
        </SelectField>

        <SelectField
          label="trigger.type（触发类型）"
          value={trigger.type}
          locked={locked}
          onChange={(type) => {
            if (locked) {
              return
            }
            setTrigger(triggerSkeleton(type as TimelineTrigger['type']))
          }}
        >
          <option value="VIRTUAL_TIME">VIRTUAL_TIME</option>
          <option value="PRICE">PRICE</option>
          <option value="AFTER_ACTION">AFTER_ACTION</option>
          <option value="GROUP">GROUP</option>
        </SelectField>

        {trigger.type === 'VIRTUAL_TIME' && (
          <NumberField
            label="atSecond（虚拟秒）"
            value={trigger.atSecond}
            min={1}
            locked={locked}
            onChange={(atSecond) => setTrigger({
              ...trigger,
              atSecond,
            })}
          />
        )}
        {trigger.type === 'PRICE' && (
          <>
            <SelectField
              label="priceType（价格类型）"
              value={trigger.priceType}
              locked={locked}
              onChange={(priceType) => setTrigger({
                ...trigger,
                priceType: priceType as PriceType,
              })}
            >
              {(Object.entries(PRICE_TYPE_LABELS) as [PriceType, string][])
                .map(([value, label]) => (
                  <option key={value} value={value}>{label}</option>
                ))}
            </SelectField>
            <SelectField
              label="operator（比较方式）"
              value={trigger.operator}
              locked={locked}
              onChange={(operator) => setTrigger({
                ...trigger,
                operator: operator as 'GTE' | 'LTE',
              })}
            >
              <option value="GTE">GTE</option>
              <option value="LTE">LTE</option>
            </SelectField>
            <TextField
              label={PRICE_FIELD_LABELS.triggerPrice}
              value={trigger.value}
              locked={locked}
              onChange={(value) => setTrigger({
                ...trigger,
                value,
              })}
            />
          </>
        )}
        {trigger.type === 'AFTER_ACTION' && (
          <>
            <TextField
              label="actionId（前置动作 ID）"
              value={trigger.actionId}
              locked={locked}
              onChange={(actionId) => setTrigger({
                ...trigger,
                actionId,
              })}
            />
            <NumberField
              label="delaySeconds（延迟秒数）"
              value={trigger.delaySeconds}
              min={0}
              locked={locked}
              onChange={(delaySeconds) => setTrigger({
                ...trigger,
                delaySeconds,
              })}
            />
          </>
        )}
      </div>

      {trigger.type === 'GROUP' && (
        <details>
          <summary>GROUP 递归触发器（由 JSON 导入管理）</summary>
          <pre>{JSON.stringify(trigger, null, 2)}</pre>
        </details>
      )}

      <div className="trading-lab-action-parameters">
        <ParameterFields
          action={action}
          locked={locked}
          onParameter={setParameter}
        />
      </div>

      {Object.keys(extraParameters).length > 0 && (
        <details>
          <summary>其他参数（只读 JSON）</summary>
          <pre>{JSON.stringify(extraParameters, null, 2)}</pre>
        </details>
      )}
      {action.overrides !== undefined && (
        <details>
          <summary>overrides（由 JSON 导入管理）</summary>
          <pre>{JSON.stringify(action.overrides, null, 2)}</pre>
        </details>
      )}

      <div className="trading-lab-expected-error">
        <label>
          <input
            type="checkbox"
            checked={action.expectedError !== undefined}
            disabled={locked}
            onChange={(event) => {
              if (locked) {
                return
              }
              if (event.target.checked) {
                emit({
                  ...action,
                  expectedError: { status: 400, code: '' },
                })
                return
              }
              const { expectedError: _removed, ...withoutExpectedError } = action
              emit(withoutExpectedError)
            }}
          />
          声明预期错误（仅负向模式）
        </label>
        {action.expectedError !== undefined && (
          <>
            <NumberField
              label="预期 HTTP 状态（仅负向模式）"
              value={action.expectedError.status}
              min={400}
              locked={locked}
              onChange={(status) => emit({
                ...action,
                expectedError: {
                  ...action.expectedError!,
                  status,
                },
              })}
            />
            <TextField
              label="预期业务代码（仅负向模式）"
              value={action.expectedError.code}
              locked={locked}
              onChange={(code) => emit({
                ...action,
                expectedError: {
                  ...action.expectedError!,
                  code,
                },
              })}
            />
          </>
        )}
        <p role="note">预期错误字段仅负向模式使用，普通模式由场景校验提示。</p>
      </div>

      {issues.length > 0 && (
        <ul className="trading-lab-action-issues">
          {issues.map((issue) => (
            <li key={`${issue.path}:${issue.code}:${issue.message}`}>
              <strong>{issue.severity}</strong>
              {' '}
              {issue.message}
            </li>
          ))}
        </ul>
      )}
    </fieldset>
  )
}
