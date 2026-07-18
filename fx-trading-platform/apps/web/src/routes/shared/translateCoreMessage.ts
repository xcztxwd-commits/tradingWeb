import type { TFunction } from 'i18next'
import type { CoreMessage } from '@fx-platform/frontend-core'

export function translateCoreMessage(message: CoreMessage | null | undefined, t: TFunction, fallback = '') {
  if (!message) return fallback
  const values = { ...message.values }
  translateNestedValue(values, 'statusKey', 'status', t)
  translateNestedValue(values, 'errorKey', 'error', t)
  const defaultValue = typeof values.message === 'string'
    ? values.message
    : fallback || message.key
  return t(message.key, { ...values, defaultValue })
}

function translateNestedValue(
  values: Record<string, string | number>,
  sourceKey: string,
  targetKey: string,
  t: TFunction
) {
  const key = values[sourceKey]
  if (typeof key === 'string') values[targetKey] = String(t(key, { defaultValue: key }))
}
