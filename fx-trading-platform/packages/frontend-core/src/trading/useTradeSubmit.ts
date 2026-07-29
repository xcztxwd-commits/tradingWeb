import { useCallback, useRef, useState } from 'react'
import type { OcoOrderGroupResponse } from '@fx-platform/shared-types'

import { ApiClientError } from '../api/apiClient.ts'
import type { CoreMessage } from '../coreMessage.ts'
import type { OcoOrderPayload, OrderPayload, OrderResponse } from '../models/index.ts'
import { toOcoOrderPayload, toOrderPayload, type OrderAdapterSettings } from './orderAdapter.ts'
import type { OrderValidationResult, TradeFormState, TradeMarket, TradeSide } from './orderTypes.ts'

type UseTradeSubmitArgs = {
  accountId?: string
  backendReady: boolean
  canTrade: boolean
  adapterSettings: OrderAdapterSettings
  loginRequired: boolean
  market: TradeMarket
  onLoginRequired?: () => void
  onSubmitOrder?: (payload: OrderPayload) => Promise<OrderResponse | void>
  onSubmitOco?: (payload: OcoOrderPayload) => Promise<OcoOrderGroupResponse | void>
  sessionHasError: boolean
}

type SubmitOptions = {
  confirmed?: boolean
  payload?: CanonicalSubmitPayload
  onConfirmRequired?: (payload: CanonicalSubmitPayload) => void
}

export type CanonicalSubmitPayload = OrderPayload | OcoOrderPayload

export function useTradeSubmit({
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
}: UseTradeSubmitArgs) {
  const [attempted, setAttempted] = useState<Record<TradeSide, boolean>>({ buy: false, sell: false })
  const [notice, setNotice] = useState<CoreMessage | null>(null)
  const [submittingSide, setSubmittingSide] = useState<TradeSide | null>(null)
  const submitInFlight = useRef(false)

  const handleSubmit = useCallback(
    async (form: TradeFormState, validation: OrderValidationResult, reset: () => void, options: SubmitOptions = {}) => {
      setAttempted((current) => ({ ...current, [form.side]: true }))

      if (loginRequired) {
        setNotice({ key: 'trading.loginBeforeOrder' })
        onLoginRequired?.()
        return
      }
      if (!canTrade) {
        setNotice({ key: sessionHasError ? 'trading.sessionRetryFirst' : 'trading.backendNotReadyCannotSubmit' })
        return
      }
      if (!validation.canSubmit) {
        const firstError = validation.errors[0]
        setNotice(firstError ? validation.fieldErrors[firstError] ?? { key: 'trading.checkOrderParams' } : { key: 'trading.checkOrderParams' })
        return
      }
      if (submitInFlight.current) return

      if (!backendReady || !accountId || !onSubmitOrder) {
        setNotice({ key: 'trading.backendNotReadyCannotSubmit' })
        return
      }

      const payload = options.payload ?? createCanonicalPayload(accountId, form, market, adapterSettings)
      if (!options.confirmed) {
        setNotice({ key: 'trading.submitConfirmFirst' })
        options.onConfirmRequired?.(payload)
        return
      }

      submitInFlight.current = true
      setSubmittingSide(form.side)
      try {
        const response = isOcoPayload(payload)
          ? await submitOco(payload, onSubmitOco)
          : await onSubmitOrder(payload)
        reset()
        setAttempted((current) => ({ ...current, [form.side]: false }))
        const status = response && 'status' in response ? response.status : undefined
        setNotice(createOrderSubmitSuccessMessage(status))
      } catch (error) {
        setNotice(createOrderSubmitFailureMessage(error))
      } finally {
        submitInFlight.current = false
        setSubmittingSide(null)
      }
    },
    [accountId, adapterSettings, backendReady, canTrade, loginRequired, market, onLoginRequired, onSubmitOco, onSubmitOrder, sessionHasError]
  )

  return { attempted, handleSubmit, notice, setNotice, submittingSide }
}

export function createCanonicalPayload(
  accountId: string,
  form: TradeFormState,
  market: TradeMarket,
  adapterSettings: OrderAdapterSettings
) {
  return form.strategyType === 'oco'
    ? toOcoOrderPayload(accountId, form, market)
    : toOrderPayload(accountId, form, market, adapterSettings)
}

export function createOrderSubmitFailureMessage(error: unknown): CoreMessage {
  if (error instanceof ApiClientError) {
    const request = error.requestId ? `, request ${error.requestId}` : ''
    return {
      key: 'trading.backendOrderFailed',
      values: {
        error: `${error.message} (${error.code}, HTTP ${error.status}${request})`,
        message: error.message,
        code: error.code,
        status: error.status,
        ...(error.requestId ? { requestId: error.requestId } : {})
      }
    }
  }

  const message = error instanceof Error && error.message
    ? error.message
    : typeof error === 'string' && error
      ? error
      : null
  if (!message) return { key: 'trading.backendOrderFailed', values: { errorKey: 'errors.retryLater' } }
  return { key: 'trading.backendOrderFailed', values: { error: message, message } }
}

export function createOrderSubmitSuccessMessage(status?: string): CoreMessage {
  return {
    key: 'trading.backendOrderSuccess',
    values: status ? { status } : { statusKey: 'trading.submitted' }
  }
}

function isOcoPayload(payload: CanonicalSubmitPayload): payload is OcoOrderPayload {
  return 'limitPrice' in payload
}

async function submitOco(payload: OcoOrderPayload, onSubmitOco?: UseTradeSubmitArgs['onSubmitOco']) {
  if (!onSubmitOco) throw new Error('OCO order submission is unavailable')
  return onSubmitOco(payload)
}
