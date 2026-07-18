import type { TFunction } from 'i18next'
import type { CoreMessage } from '@fx-platform/frontend-core'

export function translateCoreMessage(message: CoreMessage | null | undefined, t: TFunction, fallback = '') {
  if (!message) return fallback
  const defaultValue = typeof message.values?.message === 'string'
    ? message.values.message
    : fallback || message.key
  return t(message.key, { ...message.values, defaultValue })
}
