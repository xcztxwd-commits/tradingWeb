import type { FormEvent } from 'react'
import { Dialog } from '@fx-platform/ui'
import type { QuantityUnit } from '@fx-platform/shared-types'

import { MultiLevelProtectionEditor } from './MultiLevelProtectionEditor'
import type { ProtectionLevel, ProtectionType } from './MultiLevelProtectionEditor'
import styles from './TradingControls.module.css'

export type PositionAction = 'PARTIAL_CLOSE' | 'FULL_CLOSE' | 'ADJUST_MARGIN' | 'CREATE_PROTECTIONS'
export type MarginAdjustmentDirection = 'ADD' | 'REDUCE'

export type MarginAdjustmentValue = {
  direction: MarginAdjustmentDirection
  amount: string
}

type Props = {
  open: boolean
  action: PositionAction
  positionQuantity: string
  positionQuantityUnit: QuantityUnit
  quantity: string
  quantityUnit: QuantityUnit
  marginMode: 'CROSS' | 'ISOLATED'
  marginAdjustment: MarginAdjustmentValue
  protectionLevels: readonly ProtectionLevel[]
  partialCloseSupported?: boolean
  marginAdjustmentSupported?: boolean
  marginAdjustmentUnavailableReason?: string
  protectionSupported?: boolean
  disabled?: boolean
  pending?: boolean
  error?: string | null
  onActionChange: (action: PositionAction) => void
  onQuantityChange: (quantity: string) => void
  onQuantityUnitChange: (quantityUnit: QuantityUnit) => void
  onMarginAdjustmentChange: (value: MarginAdjustmentValue) => void
  onProtectionLevelChange: (id: string, patch: Partial<Omit<ProtectionLevel, 'id'>>) => void
  onProtectionAdd: (type: ProtectionType) => void
  onProtectionRemove: (id: string) => void
  onConfirm: () => void
  onClose: () => void
}

function isPositive(value: string) {
  const number = Number(value)
  return Number.isFinite(number) && number > 0
}

function isProtectionLevelValid(level: ProtectionLevel) {
  return isPositive(level.triggerPrice)
    && isPositive(level.protectedQuantity)
    && (level.executionType === 'MARKET' || isPositive(level.limitPrice))
}

export function PositionActionDialog({
  open,
  action,
  positionQuantity,
  positionQuantityUnit,
  quantity,
  quantityUnit,
  marginMode,
  marginAdjustment,
  protectionLevels,
  partialCloseSupported = false,
  marginAdjustmentSupported = false,
  marginAdjustmentUnavailableReason = 'Position version is unavailable from the current positions contract.',
  protectionSupported = false,
  disabled = false,
  pending = false,
  error,
  onActionChange,
  onQuantityChange,
  onQuantityUnitChange,
  onMarginAdjustmentChange,
  onProtectionLevelChange,
  onProtectionAdd,
  onProtectionRemove,
  onConfirm,
  onClose
}: Props) {
  if (!open) return null

  const isolated = marginMode === 'ISOLATED'
  const actionControlsDisabled = disabled || pending
  const nativeUnitSelected = quantityUnit === positionQuantityUnit
  const hasRequiredInput =
    action === 'FULL_CLOSE'
    || (action === 'PARTIAL_CLOSE'
      ? partialCloseSupported
        && isPositive(quantity)
        && (!nativeUnitSelected || Number(quantity) <= Number(positionQuantity))
      : action === 'ADJUST_MARGIN'
        ? isolated && marginAdjustmentSupported && isPositive(marginAdjustment.amount)
        : protectionSupported && protectionLevels.length > 0 && protectionLevels.every(isProtectionLevelValid))
  const submitDisabled = disabled || pending || !hasRequiredInput

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!submitDisabled) onConfirm()
  }

  return (
    <Dialog
      open={open}
      onClose={onClose}
      ariaLabel="Position action"
      closeLabel="Close position action dialog"
      pending={pending}
      priority="critical"
      className={styles.dialogLayer}
      backdropClassName={styles.dialogBackdrop}
      panelClassName={styles.dialog}
    >
        <header className={styles.dialogHeader}>
          <strong>Position action</strong>
          <button type="button" aria-label="Close" disabled={pending} onClick={onClose}>
            ×
          </button>
        </header>

        <div className={styles.actionTabs} role="tablist" aria-label="Position action type">
          <button
            type="button"
            role="tab"
            aria-selected={action === 'PARTIAL_CLOSE'}
            disabled={actionControlsDisabled || !partialCloseSupported}
            onClick={() => onActionChange('PARTIAL_CLOSE')}
          >
            Partial close
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={action === 'FULL_CLOSE'}
            disabled={actionControlsDisabled}
            onClick={() => onActionChange('FULL_CLOSE')}
          >
            Close all
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={action === 'ADJUST_MARGIN'}
            disabled={actionControlsDisabled || !isolated || !marginAdjustmentSupported}
            title={!isolated ? 'Margin adjustment is available for Isolated positions only.' : marginAdjustmentSupported ? undefined : marginAdjustmentUnavailableReason}
            onClick={() => onActionChange('ADJUST_MARGIN')}
          >
            Adjust margin
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={action === 'CREATE_PROTECTIONS'}
            disabled={actionControlsDisabled || !protectionSupported}
            onClick={() => onActionChange('CREATE_PROTECTIONS')}
          >
            TP / SL
          </button>
        </div>

        <form className={styles.dialogForm} onSubmit={handleSubmit}>
          {action === 'PARTIAL_CLOSE' || action === 'CREATE_PROTECTIONS' ? (
            <label>
              <span>Quantity unit</span>
              <select
                value={quantityUnit}
                disabled={actionControlsDisabled}
                onChange={(event) => onQuantityUnitChange(event.target.value as QuantityUnit)}
              >
                <option value="BASE">Base asset</option>
                <option value="QUOTE">USDT notional</option>
                <option value="CONTRACTS">Contracts</option>
              </select>
            </label>
          ) : null}

          {action === 'PARTIAL_CLOSE' ? (
            <label>
              <span>Close quantity ({quantityUnit})</span>
              <input
                inputMode="decimal"
                value={quantity}
                max={nativeUnitSelected ? positionQuantity : undefined}
                disabled={actionControlsDisabled}
                onChange={(event) => onQuantityChange(event.target.value)}
              />
              <small>Open quantity: {positionQuantity} {positionQuantityUnit}</small>
            </label>
          ) : null}

          {action === 'FULL_CLOSE' ? <p>Close the entire open quantity: {positionQuantity}</p> : null}

          {action === 'ADJUST_MARGIN' && isolated ? (
            <>
              <div className={styles.segmentedControl} role="group" aria-label="Margin adjustment direction">
                <button
                  type="button"
                  aria-pressed={marginAdjustment.direction === 'ADD'}
                  disabled={actionControlsDisabled}
                  onClick={() => onMarginAdjustmentChange({ ...marginAdjustment, direction: 'ADD' })}
                >
                  Add
                </button>
                <button
                  type="button"
                  aria-pressed={marginAdjustment.direction === 'REDUCE'}
                  disabled={actionControlsDisabled}
                  onClick={() => onMarginAdjustmentChange({ ...marginAdjustment, direction: 'REDUCE' })}
                >
                  Reduce
                </button>
              </div>
              <label>
                <span>Margin amount</span>
                <input
                  inputMode="decimal"
                  value={marginAdjustment.amount}
                  disabled={actionControlsDisabled}
                  onChange={(event) => onMarginAdjustmentChange({ ...marginAdjustment, amount: event.target.value })}
                />
              </label>
            </>
          ) : null}

          {action === 'CREATE_PROTECTIONS' ? (
            <MultiLevelProtectionEditor
              disabled={!protectionSupported || disabled}
              error={null}
              levels={protectionLevels}
              pending={pending}
              quantityUnit={quantityUnit}
              onAdd={onProtectionAdd}
              onLevelChange={onProtectionLevelChange}
              onRemove={onProtectionRemove}
            />
          ) : null}

          {error ? (
            <span className={styles.controlError} role="alert">
              {error}
            </span>
          ) : null}

          <footer className={styles.dialogActions}>
            <button type="button" disabled={pending} onClick={onClose}>
              Cancel
            </button>
            <button type="submit" disabled={submitDisabled}>
              {pending ? 'Submitting…' : 'Confirm'}
            </button>
          </footer>
        </form>
    </Dialog>
  )
}
