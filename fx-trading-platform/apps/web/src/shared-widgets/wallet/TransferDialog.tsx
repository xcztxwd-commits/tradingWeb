import { Dialog } from '@fx-platform/ui'
import type { FormEvent } from 'react'
import type { AccountTransferDirection } from '@fx-platform/shared-types'

import type { Amount } from '@fx-platform/frontend-core'

import { cssModuleClasses as css } from '../data/cssModuleClasses'
import styles from '../data/UserPageSurface.module.css'

export type TransferDialogProps = {
  open: boolean
  direction: AccountTransferDirection
  amount: string
  available: Amount
  requestId: string
  pending: boolean
  error: string | null
  onDirectionChange: (direction: AccountTransferDirection) => void
  onAmountChange: (amount: string) => void
  onClose: () => void
  onConfirm: () => void
}

export function TransferDialog({
  open,
  direction,
  amount,
  available,
  requestId,
  pending,
  error,
  onDirectionChange,
  onAmountChange,
  onClose,
  onConfirm
}: TransferDialogProps) {
  const parsedAvailable = Number(available)
  const availableNumber = Number.isFinite(parsedAvailable) ? Math.max(0, parsedAvailable) : 0
  const amountNumber = Number(amount)
  const amountInvalid = !Number.isFinite(amountNumber) || amountNumber <= 0 || amountNumber > availableNumber
  const fromLabel = direction === 'SPOT_TO_PERP' ? 'Spot' : 'Perpetual'
  const toLabel = direction === 'SPOT_TO_PERP' ? 'Perpetual' : 'Spot'

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (pending || amountInvalid) return
    onConfirm()
  }

  return (
    <Dialog
      open={open}
      onClose={onClose}
      labelledBy="wallet-transfer-title"
      closeLabel="Close transfer dialog"
      pending={pending}
      panelClassName={css(styles, "confirm-dialog__panel")}
    >
      <form
        className={css(styles, "user-page__form")}
        onSubmit={handleSubmit}
      >
        <h2 id="wallet-transfer-title">Transfer Demo USDT</h2>
        <p>Move Demo USDT between Spot and Perpetual. This does not transfer real funds.</p>

        <label>
          <span>Direction</span>
          <select
            value={direction}
            disabled={pending}
            onChange={(event) => onDirectionChange(event.target.value as AccountTransferDirection)}
          >
            <option value="SPOT_TO_PERP">Spot → Perpetual</option>
            <option value="PERP_TO_SPOT">Perpetual → Spot</option>
          </select>
        </label>
        <label>
          <span>From</span>
          <input value={`${fromLabel} · USDT`} readOnly />
        </label>
        <label>
          <span>To</span>
          <input value={`${toLabel} · USDT`} readOnly />
        </label>
        <label>
          <span>Amount</span>
          <input
            value={amount}
            type="number"
            min="0.00000001"
            max={availableNumber}
            step="0.00000001"
            inputMode="decimal"
            disabled={pending}
            onChange={(event) => onAmountChange(event.target.value)}
          />
        </label>
        <small>Available: {availableNumber} USDT</small>
        {amountNumber > availableNumber ? <p role="alert">Amount exceeds the available {fromLabel} balance.</p> : null}
        {error ? <p className={css(styles, "confirm-dialog__risk")} role="alert">{error}</p> : null}
        <small>Request ID: {requestId}</small>

        <div className={css(styles, "user-page__actions")}>
          <button type="submit" className={css(styles, "table-action", "table-action--primary")} disabled={pending || amountInvalid}>
            {pending ? 'Transferring…' : 'Confirm transfer'}
          </button>
          <button type="button" className={css(styles, "table-action", "table-action--secondary")} disabled={pending} onClick={onClose}>
            Cancel
          </button>
        </div>
      </form>
    </Dialog>
  )
}
