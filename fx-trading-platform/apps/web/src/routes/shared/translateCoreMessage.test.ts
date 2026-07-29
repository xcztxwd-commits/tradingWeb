import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import type { TFunction } from 'i18next'

import { translateCoreMessage } from './translateCoreMessage.ts'

describe('core message translation adapter', () => {
  it('translates nested status and error keys before interpolating the outer message', () => {
    const translations: Record<string, string> = {
      'trading.submitted': '已提交',
      'errors.retryLater': '请稍后重试'
    }
    const t = ((key: string, values?: Record<string, unknown>) => {
      if (key === 'trading.backendOrderSuccess') return `成功：${String(values?.status)}`
      if (key === 'trading.backendOrderFailed') return `失败：${String(values?.error)}`
      return translations[key] ?? String(values?.defaultValue ?? key)
    }) as unknown as TFunction

    assert.equal(translateCoreMessage({
      key: 'trading.backendOrderSuccess',
      values: { statusKey: 'trading.submitted' }
    }, t), '成功：已提交')
    assert.equal(translateCoreMessage({
      key: 'trading.backendOrderFailed',
      values: { errorKey: 'errors.retryLater' }
    }, t), '失败：请稍后重试')
  })
})
