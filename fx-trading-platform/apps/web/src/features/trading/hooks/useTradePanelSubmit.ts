import { useCallback, useState } from 'react'
import type { TFunction } from 'i18next'
import { useTranslation } from 'react-i18next'

import { toOrderPayload } from '../services/orderAdapter'
import type { OrderValidationResult, TradeFormState, TradeMarket, TradeSide } from '../types/order'
import type { OrderResponse } from '../../../components/tables/types'
import { ApiClientError } from '../../../services/apiClient'
import type { OrderPayload } from '../../../types/trading'

type UseTradePanelSubmitArgs = {
  accountId?: string
  backendReady: boolean
  canTrade: boolean
  leverage: number
  loginRequired: boolean
  market: TradeMarket
  onLoginRequired?: () => void
  onSubmitOrder?: (payload: OrderPayload) => Promise<OrderResponse | void>
  sessionHasError: boolean
}

type SubmitOptions = {
  confirmed?: boolean
  onConfirmRequired?: () => void
}

export function useTradePanelSubmit({
  accountId,
  backendReady,
  canTrade,
  leverage,
  loginRequired,
  market,
  onLoginRequired,
  onSubmitOrder,
  sessionHasError
}: UseTradePanelSubmitArgs) {
  const { t } = useTranslation()
  const [attempted, setAttempted] = useState<Record<TradeSide, boolean>>({ buy: false, sell: false })
  const [notice, setNotice] = useState('')
  const [submittingSide, setSubmittingSide] = useState<TradeSide | null>(null)

  const handleSubmit = useCallback(
    async (form: TradeFormState, validation: OrderValidationResult, reset: () => void, options: SubmitOptions = {}) => {
      setAttempted((current) => ({ ...current, [form.side]: true }))

      if (loginRequired) {
        setNotice(t('trading.loginBeforeOrder'))
        onLoginRequired?.()
        return
      }

      if (!canTrade) {
        setNotice(sessionHasError ? t('trading.sessionRetryFirst') : t('trading.backendNotReadyCannotSubmit'))
        return
      }

      if (!validation.canSubmit) {
        const firstError = validation.errors[0]
        setNotice(firstError ? validation.fieldErrors[firstError] ?? t('trading.checkOrderParams') : t('trading.checkOrderParams'))
        return
      }

      if (!options.confirmed) {
        setNotice(t('trading.submitConfirmFirst'))
        options.onConfirmRequired?.()
        return
      }

      setSubmittingSide(form.side)
      try {
        if (!backendReady || !accountId || !onSubmitOrder) {
          setNotice(t('trading.backendNotReadyCannotSubmit'))
          return
        }

        const payload = toOrderPayload(accountId, form, market, leverage)
        const response = await onSubmitOrder(payload)
        reset()
        setAttempted((current) => ({ ...current, [form.side]: false }))
        setNotice(t('trading.backendOrderSuccess', { status: response?.status ?? t('trading.submitted') }))
      } catch (error) {
        const errorDetail = formatOrderError(error, t)
        setNotice(t('trading.backendOrderFailed', { error: errorDetail }))
      } finally {
        setSubmittingSide(null)
      }
    },
    [accountId, backendReady, canTrade, leverage, loginRequired, market, onLoginRequired, onSubmitOrder, sessionHasError, t]
  )

  return { attempted, handleSubmit, notice, setNotice, submittingSide }
}

function formatOrderError(error: unknown, t: TFunction) {
  if (error instanceof ApiClientError) {
    const details = [t('errors.apiCode', { code: error.code }), t('errors.httpStatus', { status: error.status })]
    if (error.requestId) details.push(`Request ID：${error.requestId}`)
    return `${error.message} (${details.join(', ')})`
  }

  if (error instanceof Error && error.message) return error.message
  if (typeof error === 'string' && error) return error
  return t('errors.retryLater')
}
