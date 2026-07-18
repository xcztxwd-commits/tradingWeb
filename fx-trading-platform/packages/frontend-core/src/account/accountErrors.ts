import { ApiClientError } from '../api/apiClient.ts'
import type { CoreMessage } from '../coreMessage.ts'

export type AccountDataState<T> =
  | { status: 'loading'; data?: T }
  | { status: 'login-required' }
  | { status: 'ready'; data: T }
  | { status: 'error'; error: ApiClientError; message: CoreMessage }

export type AccountDataStatus = AccountDataState<unknown>['status']

export function isAuthSessionFailure(error: unknown) {
  return error instanceof ApiClientError && (error.status === 401 || error.status === 403)
}

export function toAccountApiError(error: unknown) {
  if (error instanceof ApiClientError) return error
  const message = error instanceof Error && error.message
    ? error.message
    : typeof error === 'string' && error
      ? error
      : 'Account data is unavailable.'
  return new ApiClientError({ status: 0, code: 'ACCOUNT_DATA_FAILED', message })
}

export function toAccountErrorMessage(error: ApiClientError): CoreMessage {
  return {
    key: 'account.dataUnavailable',
    values: {
      message: error.message,
      code: error.code,
      ...(error.requestId ? { requestId: error.requestId } : {})
    }
  }
}
