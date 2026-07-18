import { ApiClientError } from '@fx-platform/frontend-core'
import { t } from '../../i18n/index.ts'

export type ApiErrorView = {
  title: string
  message: string
  requestId?: string
}

export function formatApiError(error: unknown): ApiErrorView {
  if (error instanceof ApiClientError) {
    return {
      title: error.code,
      message: error.message,
      requestId: error.requestId
    }
  }
  if (error instanceof Error) {
    return { title: t('errors.requestFailedTitle'), message: error.message }
  }
  return { title: t('errors.requestFailedTitle'), message: t('errors.requestFailedMessage') }
}
