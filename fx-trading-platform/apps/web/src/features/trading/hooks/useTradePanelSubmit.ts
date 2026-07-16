import { useCallback, useState } from 'react'
import type { TFunction } from 'i18next'
import { useTranslation } from 'react-i18next'

import { toOcoOrderPayload, toOrderPayload } from '../services/orderAdapter'
import type { OrderAdapterSettings } from '../services/orderAdapter'
import type { OrderValidationResult, TradeFormState, TradeMarket, TradeSide } from '../types/order'
import type { OrderResponse } from '../../../components/tables/types'
import { ApiClientError } from '../../../services/apiClient'
import type { OcoOrderPayload, OrderPayload } from '../../../types/trading'
import type { OcoOrderGroupResponse } from '@fx-platform/shared-types'

type UseTradePanelSubmitArgs = {
  accountId?: string
  backendReady: boolean
  canTrade: boolean
  adapterSettings: OrderAdapterSettings
  loginRequired: boolean
  market: TradeMarket
  onLoginRequired?: () => void
  onSubmitOrder?: (payload: OrderPayload) => Promise<OrderResponse | void>
  onSubmitOco?: (payload: ReturnType<typeof toOcoOrderPayload>) => Promise<OcoOrderGroupResponse | void>
  sessionHasError: boolean
}

type SubmitOptions = {
  confirmed?: boolean
  payload?: CanonicalSubmitPayload
  onConfirmRequired?: (payload: CanonicalSubmitPayload) => void
}

export type CanonicalSubmitPayload = OrderPayload | OcoOrderPayload

export function useTradePanelSubmit({
  accountId,
  backendReady,
  canTrade,
  adapterSettings,
  loginRequired,
  market,
  onLoginRequired,
  onSubmitOrder,
  onSubmitOco,
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

      setSubmittingSide(form.side)
      try {
        if (!backendReady || !accountId || !onSubmitOrder) {
          setNotice(t('trading.backendNotReadyCannotSubmit'))
          return
        }

        const payload = options.payload ?? createCanonicalPayload(accountId, form, market, adapterSettings)
        if (!options.confirmed) {
          setNotice(t('trading.submitConfirmFirst'))
          options.onConfirmRequired?.(payload)
          return
        }
        const response = isOcoPayload(payload)
          ? await submitOco(payload, onSubmitOco)
          : await onSubmitOrder(payload)
        reset()
        setAttempted((current) => ({ ...current, [form.side]: false }))
        const status = response && 'status' in response ? response.status : undefined
        setNotice(t('trading.backendOrderSuccess', { status: status ?? t('trading.submitted') }))
      } catch (error) {
        const errorDetail = formatOrderError(error, t)
        setNotice(t('trading.backendOrderFailed', { error: errorDetail }))
      } finally {
        setSubmittingSide(null)
      }
    },
    [accountId, adapterSettings, backendReady, canTrade, loginRequired, market, onLoginRequired, onSubmitOco, onSubmitOrder, sessionHasError, t]
  )

  return { attempted, handleSubmit, notice, setNotice, submittingSide }
}

function createCanonicalPayload(
  accountId: string,
  form: TradeFormState,
  market: TradeMarket,
  adapterSettings: OrderAdapterSettings
) {
  return form.strategyType === 'oco'
    ? toOcoOrderPayload(accountId, form, market)
    : toOrderPayload(accountId, form, market, adapterSettings)
}

function isOcoPayload(payload: CanonicalSubmitPayload): payload is OcoOrderPayload {
  return 'limitPrice' in payload
}

async function submitOco(
  payload: OcoOrderPayload,
  onSubmitOco?: UseTradePanelSubmitArgs['onSubmitOco']
) {
  if (!onSubmitOco) throw new Error('OCO order submission is unavailable')
  return onSubmitOco(payload)
}

function formatOrderError(error: unknown, t: TFunction) {
  if (error instanceof ApiClientError) {
    const details = [t('errors.apiCode', { code: error.code }), t('errors.httpStatus', { status: error.status })]
    if (error.requestId) details.push(t('errors.requestId', { requestId: error.requestId }))
    return `${error.message} (${details.join(', ')})`
  }

  if (error instanceof Error && error.message) return error.message
  if (typeof error === 'string' && error) return error
  return t('errors.retryLater')
}
