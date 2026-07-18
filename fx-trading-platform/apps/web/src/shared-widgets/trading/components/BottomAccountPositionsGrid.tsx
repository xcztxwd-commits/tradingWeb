import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { QuantityUnit } from '@fx-platform/shared-types'

import type { PositionResponse } from '@fx-platform/frontend-core'
import type { PositionMutation } from '@fx-platform/frontend-core'
import { PositionActionDialog } from '../order-form/PositionActionDialog'
import type { MarginAdjustmentValue, PositionAction } from '../order-form/PositionActionDialog'
import type { ProtectionLevel, ProtectionType } from '../order-form/MultiLevelProtectionEditor'
import { EmptyState } from './BottomAccountEmptyState'
import { createPositionDisplayRow, resolvePositionQuantityUnit } from './positionDisplayModel'
import styles from './BottomAccountPanel.module.css'

export type PositionMutationHandler = (
  position: PositionResponse,
  mutation?: PositionMutation
) => Promise<unknown> | void

type Props = {
  positions: PositionResponse[]
  emptyLabel: string
  mode?: 'current' | 'history'
  onClosePosition?: PositionMutationHandler
}

const initialMarginAdjustment: MarginAdjustmentValue = { direction: 'ADD', amount: '' }

export function PositionsGrid({
  positions,
  emptyLabel,
  mode = 'current',
  onClosePosition
}: Props) {
  const { t } = useTranslation()
  const [selectedPosition, setSelectedPosition] = useState<PositionResponse | null>(null)
  const [action, setAction] = useState<PositionAction>('FULL_CLOSE')
  const [quantity, setQuantity] = useState('')
  const [quantityUnit, setQuantityUnit] = useState<QuantityUnit>('CONTRACTS')
  const [partialCloseClientOrderId, setPartialCloseClientOrderId] = useState('')
  const [marginAdjustment, setMarginAdjustment] = useState(initialMarginAdjustment)
  const [protectionLevels, setProtectionLevels] = useState<ProtectionLevel[]>([])
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)

  if (positions.length === 0) return <EmptyState label={emptyLabel} />

  const openPositionActions = (position: PositionResponse) => {
    setSelectedPosition(position)
    setAction('FULL_CLOSE')
    setQuantity('')
    setQuantityUnit(resolvePositionQuantityUnit(position))
    setPartialCloseClientOrderId(createClientOrderId('partial-close'))
    setMarginAdjustment(initialMarginAdjustment)
    setProtectionLevels([])
    setError(null)
  }

  const closePositionActions = () => {
    if (!pending) setSelectedPosition(null)
  }

  const addProtection = (protectionType: ProtectionType) => {
    setProtectionLevels((current) => current.length >= 10 ? current : [
      ...current,
      {
        id: createClientOrderId('protection-level'),
        protectionType,
        triggerPrice: '',
        protectedQuantity: '',
        executionType: 'MARKET',
        limitPrice: ''
      }
    ])
  }

  const updateProtection = (id: string, patch: Partial<Omit<ProtectionLevel, 'id'>>) => {
    setProtectionLevels((current) => current.map((level) => level.id === id ? { ...level, ...patch } : level))
  }

  const removeProtection = (id: string) => {
    setProtectionLevels((current) => current.filter((level) => level.id !== id))
  }

  const changeQuantityUnit = (nextUnit: QuantityUnit) => {
    setQuantityUnit(nextUnit)
    setQuantity('')
    setProtectionLevels((current) => current.map((level) => ({ ...level, protectedQuantity: '' })))
  }

  const confirmPositionAction = async () => {
    if (!selectedPosition || !onClosePosition) return
    const version = selectedPosition.version ?? undefined
    try {
      setPending(true)
      setError(null)
      const mutation = buildPositionMutation(
        action,
        quantity,
        quantityUnit,
        partialCloseClientOrderId,
        marginAdjustment,
        protectionLevels,
        version
      )
      await onClosePosition(selectedPosition, mutation)
      setSelectedPosition(null)
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : 'Position action failed')
    } finally {
      setPending(false)
    }
  }

  const selectedIsLinearPerpetual = selectedPosition ? isLinearPerpetualPosition(selectedPosition) : false
  const selectedMarginMode = selectedPosition?.marginMode?.toUpperCase() === 'ISOLATED' ? 'ISOLATED' : 'CROSS'
  const selectedVersion = selectedPosition?.version ?? undefined
  const marginAdjustmentSupported = selectedIsLinearPerpetual
    && selectedMarginMode === 'ISOLATED'
    && Number.isInteger(selectedVersion)
    && Number(selectedVersion) >= 0

  return (
    <>
      <table className={styles.table}>
        <thead>
          <tr>
            <th>{t('positions.instrument')}</th>
            <th>{t('positions.positionSide')}</th>
            <th>{t('positions.positionSize')}</th>
            <th>{t('positions.markPrice')}</th>
            <th>{t('positions.openAveragePrice')}</th>
            <th>{t('positions.notional')}</th>
            <th>{t('positions.estimatedLiquidationPrice')}</th>
            <th>{t('positions.breakEvenPrice')}</th>
            <th>{mode === 'history' ? t('positions.realizedPnl') : t('positions.floatingPnl')}</th>
            <th>{t('positions.maintenanceMargin')}</th>
            <th>{t('positions.margin')}</th>
            <th>{t('positions.takeProfitStopLoss')}</th>
            <th>{t('common.action')}</th>
          </tr>
        </thead>
        <tbody>
          {positions.map((position) => {
            const row = createPositionDisplayRow(position, t)
            const pnlValue = mode === 'history' ? row.realizedPnl : row.floatingPnl
            const pnlTone = mode === 'history' ? row.realizedPnlTone : row.floatingPnlTone
            const pnlClass = pnlTone === 'positive'
              ? styles.positiveValue
              : pnlTone === 'negative'
                ? styles.negativeValue
                : undefined

            return (
              <tr key={position.id}>
                <td>
                  <span className={styles.positionSymbol}>{row.instrument}</span>
                  <span className={styles.positionMeta}>{row.leverage}</span>
                </td>
                <td>{row.positionSide}</td>
                <td>{row.quantity}</td>
                <td>{row.markPrice}</td>
                <td>{row.openPrice}</td>
                <td>{row.notional}</td>
                <td>{row.liquidationPrice}</td>
                <td>{row.breakEvenPrice}</td>
                <td className={pnlClass}>{pnlValue}</td>
                <td>{row.showMaintenanceMargin ? row.maintenanceMargin : null}</td>
                <td>
                  <span>{row.margin}</span>
                  <span className={styles.positionMeta}>{row.marginMode}</span>
                </td>
                <td>
                  {row.takeProfit !== '--' || row.stopLoss !== '--' ? (
                    <span className={styles.protectionValues}>
                      <span className={styles.positiveValue}>{row.takeProfit}</span>
                      <span className={styles.negativeValue}>{row.stopLoss}</span>
                    </span>
                  ) : '--'}
                </td>
                <td>
                  {onClosePosition && row.canClose ? (
                    <button
                      type="button"
                      className={styles.closeButton}
                      title={t('positions.closePosition')}
                      onClick={() => openPositionActions(position)}
                    >
                      {t('positions.closePosition')}
                    </button>
                  ) : '-'}
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>

      <PositionActionDialog
        action={action}
        error={error}
        marginAdjustment={marginAdjustment}
        marginAdjustmentSupported={marginAdjustmentSupported}
        marginMode={selectedMarginMode}
        open={selectedPosition !== null}
        partialCloseSupported={selectedIsLinearPerpetual}
        pending={pending}
        positionQuantity={String(selectedPosition?.lots ?? '')}
        positionQuantityUnit={selectedPosition ? resolvePositionQuantityUnit(selectedPosition) : 'CONTRACTS'}
        protectionLevels={protectionLevels}
        protectionSupported={selectedIsLinearPerpetual}
        quantity={quantity}
        quantityUnit={quantityUnit}
        onActionChange={setAction}
        onClose={closePositionActions}
        onConfirm={() => void confirmPositionAction()}
        onMarginAdjustmentChange={setMarginAdjustment}
        onProtectionAdd={addProtection}
        onProtectionLevelChange={updateProtection}
        onProtectionRemove={removeProtection}
        onQuantityChange={setQuantity}
        onQuantityUnitChange={changeQuantityUnit}
      />
    </>
  )
}

function isLinearPerpetualPosition(position: PositionResponse) {
  return position.instrumentType?.toUpperCase() === 'LINEAR_PERP'
    || position.symbol.toUpperCase().endsWith('USDT-PERP')
}

function buildPositionMutation(
  action: PositionAction,
  quantity: string,
  quantityUnit: QuantityUnit,
  partialCloseClientOrderId: string,
  marginAdjustment: MarginAdjustmentValue,
  protectionLevels: readonly ProtectionLevel[],
  version?: number
): PositionMutation {
  switch (action) {
    case 'FULL_CLOSE':
      return { type: 'FULL_CLOSE' }
    case 'PARTIAL_CLOSE':
      return {
        type: 'PARTIAL_CLOSE',
        payload: {
          quantity: Number(quantity),
          quantityUnit,
          clientOrderId: partialCloseClientOrderId
        }
      }
    case 'ADJUST_MARGIN':
      if (!Number.isInteger(version) || Number(version) < 0) {
        throw new Error('Position version is unavailable from the current positions contract.')
      }
      return {
        type: 'ADJUST_MARGIN',
        payload: {
          action: marginAdjustment.direction,
          amount: Number(marginAdjustment.amount),
          expectedVersion: Number(version)
        }
      }
    case 'CREATE_PROTECTIONS':
      return {
        type: 'CREATE_PROTECTIONS',
        payloads: protectionLevels.map((level) => ({
          protectionType: level.protectionType,
          quantity: Number(level.protectedQuantity),
          quantityUnit,
          triggerPrice: Number(level.triggerPrice),
          triggerExecutionType: level.executionType,
          ...(level.executionType === 'LIMIT' ? { price: Number(level.limitPrice) } : {}),
          clientOrderId: level.id
        }))
      }
  }
}

function createClientOrderId(prefix: string) {
  const randomId = globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`
  return `${prefix}-${randomId}`
}
