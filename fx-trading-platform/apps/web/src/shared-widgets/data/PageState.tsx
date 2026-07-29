import { StateSurface } from '@fx-platform/ui'
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
  return <StateSurface title={title} message={message} actionLabel={action} onAction={onAction} variant={variant} />
}

export function LoadingState({ message }: { message?: string }) {
  const { t } = useTranslation()

  return <StateSurface title={t('common.loading')} message={message ?? t('common.loadingData')} variant="loading" />
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
    <StateSurface
      title={getReadableErrorTitle(error, t('common.loadFailed'))}
      message={error.message}
      detail={error.requestId ? t('common.issueId', { requestId: error.requestId }) : undefined}
      actionLabel={onAction ? (action ?? t('common.retry')) : undefined}
      onAction={onAction}
      variant="error"
    />
  )
}

function getReadableErrorTitle(error: ApiErrorView, fallback: string) {
  const title = error.title.trim()
  if (!title || /^[A-Z0-9_]+$/.test(title)) return fallback
  return title
}
