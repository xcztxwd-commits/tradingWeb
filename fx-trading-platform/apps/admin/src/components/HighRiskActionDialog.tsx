import { useEffect, useId, useRef, useState, type ReactNode } from 'react'

import './HighRiskActionDialog.css'

export type HighRiskAction = 'force-cleanup' | 'reset'

export interface HighRiskActionRequest {
  reason: string
  requestId: string
}

export interface HighRiskActionResult {
  auditId: string
}

export interface HighRiskActionDialogProps {
  open: boolean
  action: HighRiskAction
  title: string
  description: string
  context?: ReactNode
  cleanupBlockers?: readonly string[]
  confirmLabel?: string
  onExecute: (request: HighRiskActionRequest) => Promise<HighRiskActionResult>
  onClose: () => void
}

type DialogStage = 'details' | 'confirm' | 'completed'

export function HighRiskActionDialog({
  open,
  action,
  title,
  description,
  context,
  cleanupBlockers = [],
  confirmLabel,
  onExecute,
  onClose
}: HighRiskActionDialogProps) {
  const titleId = useId()
  const descriptionId = useId()
  const reasonId = useId()
  const pendingRef = useRef(false)
  const [stage, setStage] = useState<DialogStage>('details')
  const [reason, setReason] = useState('')
  const [requestId, setRequestId] = useState(createRequestId)
  const [pending, setPending] = useState(false)
  const [auditId, setAuditId] = useState('')
  const [error, setError] = useState('')

  useEffect(() => {
    if (!open) return
    setStage('details')
    setReason('')
    setRequestId(createRequestId())
    setPending(false)
    pendingRef.current = false
    setAuditId('')
    setError('')
  }, [action, open])

  if (!open) return null

  const trimmedReason = reason.trim()
  const resetBlocked = action === 'reset' && cleanupBlockers.length > 0
  const actionLabel = action === 'reset' ? 'Reset demo account' : 'Force cleanup'

  const moveToConfirmation = () => {
    if (!trimmedReason || resetBlocked) return
    setError('')
    setStage('confirm')
  }

  const execute = async () => {
    if (pendingRef.current) return
    if (!trimmedReason || resetBlocked) return
    pendingRef.current = true
    setPending(true)
    setError('')
    try {
      const result = await onExecute({ reason: trimmedReason, requestId })
      setAuditId(result.auditId)
      setStage('completed')
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : 'The operation failed. Try again with the same request ID.')
    } finally {
      pendingRef.current = false
      setPending(false)
    }
  }

  const close = () => {
    if (!pending) onClose()
  }

  return (
    <div className="high-risk-action-mask">
      <section
        aria-describedby={descriptionId}
        aria-labelledby={titleId}
        aria-modal="true"
        className="high-risk-action-dialog"
        role="dialog"
      >
        <header className="high-risk-action-header">
          <div>
            <span className="high-risk-action-eyebrow">High-risk operation</span>
            <h2 id={titleId}>{title}</h2>
          </div>
          <button aria-label="Close" className="high-risk-action-close" disabled={pending} onClick={close} type="button">
            ×
          </button>
        </header>

        <div className="high-risk-action-body">
          <p id={descriptionId}>{description}</p>
          {context ? <div className="high-risk-action-context">{context}</div> : null}

          <dl className="high-risk-action-identifiers">
            <div>
              <dt>Operation</dt>
              <dd>{actionLabel}</dd>
            </div>
            <div>
              <dt>Request ID</dt>
              <dd data-testid="high-risk-request-id">{requestId}</dd>
            </div>
          </dl>

          {resetBlocked ? (
            <div className="high-risk-action-blockers" role="alert">
              <strong>Reset is unavailable until cleanup blockers are resolved:</strong>
              <ul>
                {cleanupBlockers.map((blocker) => (
                  <li key={blocker}>{blocker}</li>
                ))}
              </ul>
            </div>
          ) : null}

          {stage === 'details' ? (
            <label className="high-risk-action-reason" htmlFor={reasonId}>
              <span>Reason</span>
              <textarea
                aria-required="true"
                autoFocus
                id={reasonId}
                onChange={(event) => setReason(event.target.value)}
                placeholder="Explain why this operation is necessary"
                rows={4}
                value={reason}
              />
              <small>This reason is required and will be written to the audit log.</small>
            </label>
          ) : null}

          {stage === 'confirm' ? (
            <div className="high-risk-action-review">
              <strong>Second confirmation</strong>
              <p>Review the exact operation, request ID, and reason before executing.</p>
              <dl>
                <div>
                  <dt>Reason</dt>
                  <dd>{trimmedReason}</dd>
                </div>
              </dl>
            </div>
          ) : null}

          {stage === 'completed' ? (
            <div className="high-risk-action-completed" role="status">
              <strong>Operation accepted</strong>
              <span>Audit ID</span>
              <code data-testid="high-risk-audit-id">{auditId}</code>
            </div>
          ) : null}

          {error ? <p className="high-risk-action-error" role="alert">{error}</p> : null}
        </div>

        <footer className="high-risk-action-footer">
          {stage === 'details' ? (
            <>
              <button className="high-risk-action-secondary" onClick={close} type="button">Cancel</button>
              <button
                className="high-risk-action-danger"
                disabled={resetBlocked || !trimmedReason}
                onClick={moveToConfirmation}
                type="button"
              >
                Continue to confirmation
              </button>
            </>
          ) : null}
          {stage === 'confirm' ? (
            <>
              <button
                className="high-risk-action-secondary"
                disabled={pending}
                onClick={() => setStage('details')}
                type="button"
              >
                Back
              </button>
              <button
                className="high-risk-action-danger"
                disabled={pending || resetBlocked}
                onClick={execute}
                type="button"
              >
                {pending ? 'Executing…' : (confirmLabel ?? `Confirm ${actionLabel}`)}
              </button>
            </>
          ) : null}
          {stage === 'completed' ? (
            <button className="high-risk-action-secondary" onClick={close} type="button">Close</button>
          ) : null}
        </footer>
      </section>
    </div>
  )
}

function createRequestId() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `admin-${Date.now()}-${Math.random().toString(16).slice(2)}`
}
