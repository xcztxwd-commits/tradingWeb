import { useTranslation } from 'react-i18next'

import type { ApiErrorView } from './userPageModels'

type StatePanelProps = {
  title: string
  message: string
  action?: string
  onAction?: () => void
  variant?: 'default' | 'empty' | 'login' | 'error'
}

export function StatePanel({ title, message, action, onAction, variant = 'default' }: StatePanelProps) {
  return (
    <div className={`state-panel state-panel--${variant}`}>
      <div>
        <strong>{title}</strong>
        <span>{message}</span>
      </div>
      {action ? (
        <button type="button" className="table-action table-action--primary" onClick={onAction}>
          {action}
        </button>
      ) : null}
    </div>
  )
}

export function LoadingState({ message }: { message?: string }) {
  const { t } = useTranslation()

  return (
    <div className="state-panel state-panel--loading" role="status" aria-live="polite">
      <div>
        <strong>{t('common.loading')}</strong>
        <span>{message ?? t('common.loadingData')}</span>
      </div>
      <div className="state-panel__skeleton" aria-hidden="true">
        <span />
        <span />
        <span />
      </div>
    </div>
  )
}

export function EmptyState({
  message,
  action,
  onAction
}: {
  message?: string
  action?: string
  onAction?: () => void
}) {
  const { t } = useTranslation()

  return <StatePanel title={t('common.empty')} message={message ?? t('common.empty')} action={action} onAction={onAction} variant="empty" />
}

export function LoginRequiredState({ message, onLogin }: { message: string; onLogin: () => void }) {
  const { t } = useTranslation()

  return <StatePanel title={t('common.loginRequired')} message={message} action={t('common.goLogin')} onAction={onLogin} variant="login" />
}

export function ApiErrorState({
  error,
  action,
  onAction
}: {
  error: ApiErrorView
  action?: string
  onAction?: () => void
}) {
  const { t } = useTranslation()

  return (
    <div className="state-panel state-panel--error" role="alert">
      <div>
        <strong>{getReadableErrorTitle(error, t('common.loadFailed'))}</strong>
        <span>{error.message}</span>
        {error.requestId ? <small>{t('common.issueId', { requestId: error.requestId })}</small> : null}
      </div>
      {onAction ? (
        <button type="button" className="table-action table-action--secondary" onClick={onAction}>
          {action ?? t('common.retry')}
        </button>
      ) : null}
    </div>
  )
}

function getReadableErrorTitle(error: ApiErrorView, fallback: string) {
  const title = error.title.trim()
  if (!title || /^[A-Z0-9_]+$/.test(title)) return fallback
  return title
}
