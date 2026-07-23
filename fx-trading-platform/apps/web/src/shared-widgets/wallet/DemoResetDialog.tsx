import type { FormEvent } from 'react'
import { Dialog } from '@fx-platform/ui'

import { cssModuleClasses as css } from '../data/cssModuleClasses'
import styles from '../data/UserPageSurface.module.css'

export type DemoResetDialogProps = {
  open: boolean
  requestId: string
  pending: boolean
  error: string | null
  onClose: () => void
  onConfirm: () => void
}

export function DemoResetDialog({
  open,
  requestId,
  pending,
  error,
  onClose,
  onConfirm
}: DemoResetDialogProps) {
  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (pending) return
    onConfirm()
  }

  return (
    <Dialog
      open={open}
      onClose={onClose}
      labelledBy="wallet-reset-title"
      closeLabel="Close reset dialog"
      pending={pending}
      panelClassName={css(styles, "confirm-dialog__panel")}
    >
      <form
        aria-labelledby="wallet-reset-title"
        aria-busy={pending}
        onSubmit={handleSubmit}
      >
        <h2 id="wallet-reset-title">Reset Demo account</h2>
        <p>
          This restores the Demo Spot and Perpetual balances and settings. Active orders, protections or positions block reset.
        </p>
        <p className={css(styles, "confirm-dialog__risk")}>This action affects Demo data only and cannot be used for real-money trading.</p>
        {error ? <p className={css(styles, "confirm-dialog__risk")} role="alert">{error}</p> : null}
        <small>Request ID: {requestId}</small>
        <div className={css(styles, "user-page__actions")}>
          <button type="submit" className={css(styles, "table-action", "table-action--danger")} disabled={pending}>
            {pending ? 'Resetting…' : 'Confirm reset'}
          </button>
          <button type="button" className={css(styles, "table-action", "table-action--secondary")} disabled={pending} onClick={onClose}>
            Cancel
          </button>
        </div>
      </form>
    </Dialog>
  )
}
