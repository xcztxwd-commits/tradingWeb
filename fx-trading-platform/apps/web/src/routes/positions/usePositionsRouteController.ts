import { useCallback, useMemo, useRef, useState } from 'react'
import type { TFunction } from 'i18next'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import {
  mutateTradingPosition,
  updateTradingPositionProtection,
  type PositionResponse,
  type UpdatePositionProtectionPayload
} from '@fx-platform/frontend-core'

import { formatApiError, type ApiErrorView } from '../../components/user-page/userPageModels'
import { useTranslatedAccountData } from '../shared/useTranslatedAccountData'
import { createPendingOperationGuard } from '../shared/pendingOperationGuard'
import {
  executePositionProtectionUpdate,
  resolvePositionProtectionPath
} from './positionProtectionPolicy'
import type { PositionsRouteModel, PositionView, ProtectionFormValues } from './positionsRoute.types'
import { parseProtectionPrice } from './positionsRouteModel'

export function usePositionsRouteController(): PositionsRouteModel {
  const navigate = useNavigate()
  const { t } = useTranslation()
  const {
    positions,
    positionHistory,
    sessionMode,
    sessionError,
    loginRequired,
    retrySession,
    refreshAccountData,
    token,
    accountId
  } = useTranslatedAccountData()
  const operationGuard = useRef(createPendingOperationGuard()).current
  const [view, setView] = useState<PositionView>('CURRENT')
  const [symbol, setSymbol] = useState('')
  const [editingPosition, setEditingPosition] = useState<PositionResponse | null>(null)
  const [protectionForm, setProtectionForm] = useState<ProtectionFormValues>({ stopLoss: '', takeProfit: '' })
  const [protectionError, setProtectionError] = useState<string | null>(null)
  const [pendingClosePosition, setPendingClosePosition] = useState<PositionResponse | null>(null)
  const [busyPositionId, setBusyPositionId] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [apiError, setApiError] = useState<ApiErrorView | null>(null)

  const visiblePositions = useMemo(() => {
    const source = view === 'CURRENT' ? positions : positionHistory
    const normalizedSymbol = symbol.trim().toUpperCase()
    return normalizedSymbol
      ? source.filter((position) => position.symbol.toUpperCase().includes(normalizedSymbol))
      : source
  }, [positionHistory, positions, symbol, view])

  const clearMessages = () => {
    setNotice(null)
    setApiError(null)
  }

  const closePositionApi = useCallback(async (position: PositionResponse) => {
    if (!token || !accountId) return
    try {
      await mutateTradingPosition(accountId, position.id, { type: 'FULL_CLOSE' }, token)
    } finally {
      await refreshAccountData().catch(() => undefined)
    }
  }, [accountId, refreshAccountData, token])

  const updatePositionProtection = useCallback(async (
    position: PositionResponse,
    payload: UpdatePositionProtectionPayload
  ) => {
    if (!token || !accountId) return
    await updateTradingPositionProtection(accountId, position.id, payload, token)
    await refreshAccountData()
  }, [accountId, refreshAccountData, token])

  const closePosition = (position: PositionResponse) => operationGuard.run(`position:close:${position.id}`, async () => {
    setBusyPositionId(position.id)
    clearMessages()
    try {
      await closePositionApi(position)
      await retrySession()
      setPendingClosePosition(null)
      setNotice(t('positions.closeSuccess'))
    } catch (error) {
      setApiError(formatApiError(error))
    } finally {
      setBusyPositionId(null)
    }
  })

  const startProtectionEdit = (position: PositionResponse) => {
    const canonicalPath = resolvePositionProtectionPath(position)
    if (canonicalPath) {
      navigate(canonicalPath)
      return
    }
    clearMessages()
    setProtectionError(null)
    setProtectionForm(createProtectionForm(position))
    setEditingPosition(position)
  }

  const submitProtection = async () => {
    if (!editingPosition) return
    const result = validateProtectionForm(editingPosition, protectionForm, t)
    if (!result.ok) {
      setProtectionError(result.message)
      return
    }
    return operationGuard.run(`position:protection:${editingPosition.id}`, async () => {
      setBusyPositionId(editingPosition.id)
      clearMessages()
      try {
        const outcome = await executePositionProtectionUpdate(editingPosition, result.payload, updatePositionProtection)
        setEditingPosition(null)
        setProtectionError(null)
        if (outcome.kind === 'canonical') {
          navigate(outcome.path)
          return
        }
        setNotice(t('positions.tpSlUpdated'))
      } catch (error) {
        setApiError(formatApiError(error))
      } finally {
        setBusyPositionId(null)
      }
    })
  }

  return {
    visiblePositions,
    view,
    symbol,
    sessionMode,
    sessionError,
    loginRequired,
    editingPosition,
    protectionForm,
    protectionError,
    pendingClosePosition,
    busyPositionId,
    notice,
    apiError,
    setView,
    setSymbol,
    refresh: retrySession,
    openLogin: () => navigate('/login?redirect=/positions'),
    openCloseDialog: setPendingClosePosition,
    dismissCloseDialog: () => setPendingClosePosition(null),
    closePosition,
    startProtectionEdit,
    openProtectionWorkflow: (position) => {
      const path = resolvePositionProtectionPath(position)
      if (path) navigate(path)
    },
    setProtectionField: (field, value) => {
      setProtectionForm((current) => ({ ...current, [field]: value }))
      setProtectionError(null)
    },
    dismissProtectionEdit: () => {
      setEditingPosition(null)
      setProtectionError(null)
    },
    submitProtection
  }
}

type ProtectionValidationResult =
  | { ok: true; payload: UpdatePositionProtectionPayload }
  | { ok: false; message: string }

function createProtectionForm(position: PositionResponse): ProtectionFormValues {
  return { stopLoss: String(position.stopLoss ?? ''), takeProfit: String(position.takeProfit ?? '') }
}

function validateProtectionForm(position: PositionResponse, values: ProtectionFormValues, t: TFunction): ProtectionValidationResult {
  const stopLossText = values.stopLoss.trim()
  const takeProfitText = values.takeProfit.trim()
  const stopLoss = stopLossText ? parseProtectionPrice(stopLossText) : null
  const takeProfit = takeProfitText ? parseProtectionPrice(takeProfitText) : null
  const entryPrice = parseProtectionPrice(position.openPrice)
  const side = position.side.toUpperCase()
  if (!stopLossText && !takeProfitText) return { ok: false, message: t('positions.validation.enterStopLossOrTakeProfit') }
  if (stopLossText && stopLoss === null) return { ok: false, message: t('positions.validation.stopLossPositive') }
  if (takeProfitText && takeProfit === null) return { ok: false, message: t('positions.validation.takeProfitPositive') }
  if (entryPrice !== null && side === 'BUY') {
    if (stopLoss !== null && stopLoss >= entryPrice) return { ok: false, message: t('positions.validation.buyStopLoss') }
    if (takeProfit !== null && takeProfit <= entryPrice) return { ok: false, message: t('positions.validation.buyTakeProfit') }
  }
  if (entryPrice !== null && side === 'SELL') {
    if (stopLoss !== null && stopLoss <= entryPrice) return { ok: false, message: t('positions.validation.sellStopLoss') }
    if (takeProfit !== null && takeProfit >= entryPrice) return { ok: false, message: t('positions.validation.sellTakeProfit') }
  }
  return {
    ok: true,
    payload: {
      ...(stopLossText ? { stopLoss: stopLossText } : {}),
      ...(takeProfitText ? { takeProfit: takeProfitText } : {})
    }
  }
}
