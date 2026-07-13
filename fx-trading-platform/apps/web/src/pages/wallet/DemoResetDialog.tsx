import type { FormEvent } from 'react'

type DemoResetDialogProps = {
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
  if (!open) return null

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (pending) return
    onConfirm()
  }

  return (
    <div
      className="confirm-dialog"
      role="presentation"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget && !pending) onClose()
      }}
    >
      <form
        className="confirm-dialog__panel"
        role="dialog"
        aria-modal="true"
        aria-labelledby="wallet-reset-title"
        aria-busy={pending}
        onSubmit={handleSubmit}
      >
        <h2 id="wallet-reset-title">Reset Demo account</h2>
        <p>
          This restores the Demo Spot and Perpetual balances and settings. Active orders, protections or positions block reset.
        </p>
        <p className="confirm-dialog__risk">This action affects Demo data only and cannot be used for real-money trading.</p>
        {error ? <p className="confirm-dialog__risk" role="alert">{error}</p> : null}
        <small>Request ID: {requestId}</small>
        <div className="user-page__actions">
          <button type="submit" className="table-action table-action--danger" disabled={pending}>
            {pending ? 'Resetting…' : 'Confirm reset'}
          </button>
          <button type="button" className="table-action table-action--secondary" disabled={pending} onClick={onClose}>
            Cancel
          </button>
        </div>
      </form>
    </div>
  )
}
