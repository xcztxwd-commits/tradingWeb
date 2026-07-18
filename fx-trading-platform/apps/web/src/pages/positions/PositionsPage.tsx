import { FormEvent, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { TFunction } from 'i18next'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'

import { AssetMark } from '../../components/asset/AssetMark'
import { DataTable, type DataTableColumn } from '../../components/user-page/DataTable'
import { ApiErrorState, LoadingState, LoginRequiredState } from '../../components/user-page/PageState'
import { formatApiError } from '../../components/user-page/userPageModels'
import { useTradingSession } from '../../features/trading-session/useTradingSession'
import type { PositionResponse, UpdatePositionProtectionPayload } from '@fx-platform/frontend-core'
import {
  canUseLegacyPositionProtection,
  executePositionProtectionUpdate,
  resolvePositionProtectionPath
} from './positionProtectionPolicy'

type PositionView = 'CURRENT' | 'HISTORY'
type ProtectionFormValues = {
  stopLoss: string
  takeProfit: string
}

type ProtectionValidationResult =
  | { ok: true; payload: UpdatePositionProtectionPayload }
  | { ok: false; message: string }

const viewOptions: Array<{ value: PositionView; labelKey: string }> = [
  { value: 'CURRENT', labelKey: 'positions.current' },
  { value: 'HISTORY', labelKey: 'positions.history' }
]

export function PositionsPage() {
  const navigate = useNavigate()
  const { t } = useTranslation()
  const {
    positions,
    positionHistory,
    sessionMode,
    sessionError,
    loginRequired,
    retrySession,
    closePosition,
    updatePositionProtection
  } = useTradingSession()
  const [view, setView] = useState<PositionView>('CURRENT')
  const [symbol, setSymbol] = useState('')
  const [editingPosition, setEditingPosition] = useState<PositionResponse | null>(null)
  const [protectionForm, setProtectionForm] = useState<ProtectionFormValues>({ stopLoss: '', takeProfit: '' })
  const [protectionError, setProtectionError] = useState<string | null>(null)
  const [pendingClosePosition, setPendingClosePosition] = useState<PositionResponse | null>(null)
  const [busyPositionId, setBusyPositionId] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [apiError, setApiError] = useState<ReturnType<typeof formatApiError> | null>(null)
  const closeDialogCancelButtonRef = useRef<HTMLButtonElement | null>(null)
  const closeDialogTriggerRef = useRef<HTMLButtonElement | null>(null)
  const protectionGuidance = editingPosition ? getProtectionGuidance(editingPosition, t) : null

  const visiblePositions = useMemo(() => {
    const source = view === 'CURRENT' ? positions : positionHistory
    const normalizedSymbol = symbol.trim().toUpperCase()
    if (!normalizedSymbol) return source
    return source.filter((position) => position.symbol.toUpperCase().includes(normalizedSymbol))
  }, [positionHistory, positions, symbol, view])
  const closeDialogIsBusy = pendingClosePosition ? busyPositionId === pendingClosePosition.id : false

  const dismissCloseDialog = useCallback(() => {
    setPendingClosePosition(null)
    window.requestAnimationFrame(() => {
      closeDialogTriggerRef.current?.focus()
      closeDialogTriggerRef.current = null
    })
  }, [])

  const openCloseDialog = useCallback((position: PositionResponse, trigger: HTMLButtonElement) => {
    closeDialogTriggerRef.current = trigger
    setPendingClosePosition(position)
  }, [])

  useEffect(() => {
    if (!pendingClosePosition) return
    closeDialogCancelButtonRef.current?.focus()
  }, [pendingClosePosition])

  useEffect(() => {
    if (!pendingClosePosition) return

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !closeDialogIsBusy) {
        event.preventDefault()
        dismissCloseDialog()
      }
    }

    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [closeDialogIsBusy, dismissCloseDialog, pendingClosePosition])

  const handleClose = async (position: PositionResponse) => {
    setBusyPositionId(position.id)
    clearMessages()
    try {
      await closePosition(position)
      await retrySession()
      setPendingClosePosition(null)
      setNotice(t('positions.closeSuccess'))
    } catch (error) {
      setApiError(formatApiError(error))
    } finally {
      setBusyPositionId(null)
    }
  }

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

  const openProtectionWorkflow = useCallback((position: PositionResponse) => {
    const path = resolvePositionProtectionPath(position)
    if (path) navigate(path)
  }, [navigate])

  const updateProtectionField = (field: keyof ProtectionFormValues, value: string) => {
    setProtectionForm((current) => ({ ...current, [field]: value }))
    setProtectionError(null)
  }

  const handleProtectionSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!editingPosition) return
    const result = validateProtectionForm(editingPosition, protectionForm, t)
    if (!result.ok) {
      setProtectionError(result.message)
      return
    }
    setBusyPositionId(editingPosition.id)
    clearMessages()
    try {
      const outcome = await executePositionProtectionUpdate(
        editingPosition,
        result.payload,
        updatePositionProtection
      )
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
  }

  if (loginRequired) {
    return (
      <section className="user-page">
        <LoginRequiredState message={t('positions.loginMessage')} onLogin={() => navigate('/login?redirect=/positions')} />
      </section>
    )
  }

  return (
    <section className="user-page">
      <header className="user-page__header">
        <div>
          <h1>{t('positions.center')}</h1>
          <p>{t('positions.centerSummary')}</p>
        </div>
        <button type="button" className="table-action table-action--secondary" onClick={() => void retrySession()}>
          {t('common.refresh')}
        </button>
      </header>

      <div className="user-page__tabs" role="tablist" aria-label={t('positions.viewAria')}>
        {viewOptions.map((option) => (
          <button
            key={option.value}
            type="button"
            className={view === option.value ? 'active' : ''}
            onClick={() => setView(option.value)}
          >
            {t(option.labelKey)}
          </button>
        ))}
      </div>

      <div className="user-page__toolbar" aria-label={t('positions.filterAria')}>
        <label>
          <span>Symbol</span>
          <input value={symbol} onChange={(event) => setSymbol(event.target.value)} placeholder="EURUSD" />
        </label>
      </div>

      {sessionMode === 'loading' ? <LoadingState message={t('positions.loading')} /> : null}
      {sessionMode === 'error' ? (
        <ApiErrorState error={{ title: 'POSITION_LOAD_FAILED', message: sessionError ?? t('positions.unavailable') }} onAction={() => void retrySession()} />
      ) : null}
      {apiError ? <ApiErrorState error={apiError} onAction={() => void retrySession()} /> : null}
      {notice ? <div className="user-page__notice">{notice}</div> : null}

      <DataTable
        rows={visiblePositions}
        columns={positionColumns(
          view,
          busyPositionId,
          openCloseDialog,
          startProtectionEdit,
          openProtectionWorkflow,
          t
        )}
        rowKey={(position) => position.id}
        emptyMessage={view === 'CURRENT' ? t('positions.emptyCurrent') : t('positions.emptyHistory')}
        emptyAction={{ label: t('dashboard.viewMarkets'), href: '/markets' }}
      />

      {editingPosition ? (
        <section className="user-page__events" aria-label={t('positions.modifyTpSl')}>
          <h2>{t('positions.modifyTpSl')}</h2>
          <div className="protection-form__summary" aria-label={t('positions.positionReference')}>
            <span>
              <strong>{editingPosition.symbol}</strong>
            </span>
            <span>{t('trading.side')} {editingPosition.side}</span>
            <span>{t('positions.costPrice')} {formatOptionalAmount(editingPosition.openPrice)}</span>
            <span>{t('dashboard.currentPrice')} {formatOptionalAmount(editingPosition.currentPrice)}</span>
          </div>
          {protectionGuidance ? (
            <p id="position-protection-hint" className="protection-form__hint">
              {protectionGuidance.hint}
            </p>
          ) : null}
          <form className="user-page__form protection-form" onSubmit={handleProtectionSubmit} noValidate>
            <label className="protection-form__input">
              <span>{t('positions.stopLossPrice')}</span>
              <input
                name="stopLoss"
                type="number"
                inputMode="decimal"
                min="0"
                step="0.00000001"
                value={protectionForm.stopLoss}
                placeholder={protectionGuidance?.stopLossPlaceholder}
                aria-invalid={protectionError?.includes(t('trading.stopLoss')) ? true : undefined}
                aria-describedby={protectionError ? 'position-protection-hint position-protection-error' : 'position-protection-hint'}
                onChange={(event) => updateProtectionField('stopLoss', event.target.value)}
              />
              <small>{t('positions.stopLossHelp')}</small>
            </label>
            <label className="protection-form__input">
              <span>{t('positions.takeProfitPrice')}</span>
              <input
                name="takeProfit"
                type="number"
                inputMode="decimal"
                min="0"
                step="0.00000001"
                value={protectionForm.takeProfit}
                placeholder={protectionGuidance?.takeProfitPlaceholder}
                aria-invalid={protectionError?.includes(t('trading.takeProfit')) ? true : undefined}
                aria-describedby={protectionError ? 'position-protection-hint position-protection-error' : 'position-protection-hint'}
                onChange={(event) => updateProtectionField('takeProfit', event.target.value)}
              />
              <small>{t('positions.takeProfitHelp')}</small>
            </label>
            {protectionError ? (
              <p id="position-protection-error" className="protection-form__error" role="alert">
                {protectionError}
              </p>
            ) : null}
            <div className="protection-form__actions">
              <button type="submit" className="table-action table-action--primary" disabled={busyPositionId === editingPosition.id}>
                {t('positions.saveTpSl')}
              </button>
              <button
                type="button"
                className="table-action table-action--secondary"
                onClick={() => {
                  setEditingPosition(null)
                  setProtectionError(null)
                }}
              >
                {t('common.cancel')}
              </button>
            </div>
          </form>
        </section>
      ) : null}

      {pendingClosePosition ? (
        <div
          className="confirm-dialog"
          role="dialog"
          aria-modal="true"
          aria-label={t('positions.closeConfirmDialog')}
          onMouseDown={(event) => {
            if (event.target === event.currentTarget && !closeDialogIsBusy) dismissCloseDialog()
          }}
        >
          <div className="confirm-dialog__panel">
            <h2>{t('positions.closeConfirmTitle')}</h2>
            <p>{t('positions.closeConfirmBody')}</p>
            <dl className="confirm-dialog__details">
              <div>
                <dt>{t('trading.symbol')}</dt>
                <dd>{pendingClosePosition.symbol}</dd>
              </div>
              <div>
                <dt>{t('trading.side')}</dt>
                <dd>{pendingClosePosition.side}</dd>
              </div>
              <div>
                <dt>{t('common.quantity')}</dt>
                <dd>{formatOptionalAmount(pendingClosePosition.lots)}</dd>
              </div>
              <div>
                <dt>{t('positions.costPrice')}</dt>
                <dd>{formatOptionalAmount(pendingClosePosition.openPrice)}</dd>
              </div>
              <div>
                <dt>{t('positions.unrealizedPnl')}</dt>
                <dd>{formatOptionalAmount(pendingClosePosition.floatingPnl)}</dd>
              </div>
            </dl>
            <p className="confirm-dialog__risk">{t('positions.closeRiskNotice')}</p>
            <div className="user-page__actions">
              <button
                type="button"
                className="table-action table-action--danger"
                onClick={() => void handleClose(pendingClosePosition)}
                disabled={busyPositionId === pendingClosePosition.id}
              >
                {t('positions.closeConfirmTitle')}
              </button>
              <button
                type="button"
                className="table-action table-action--secondary"
                ref={closeDialogCancelButtonRef}
                onClick={dismissCloseDialog}
                disabled={busyPositionId === pendingClosePosition.id}
              >
                {t('common.cancel')}
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </section>
  )

  function clearMessages() {
    setNotice(null)
    setApiError(null)
  }
}

function positionColumns(
  view: PositionView,
  busyPositionId: string | null,
  openCloseDialog: (position: PositionResponse, trigger: HTMLButtonElement) => void,
  startProtectionEdit: (position: PositionResponse) => void,
  openProtectionWorkflow: (position: PositionResponse) => void,
  t: TFunction
): Array<DataTableColumn<PositionResponse>> {
  const columns: Array<DataTableColumn<PositionResponse>> = [
    {
      key: 'symbol',
      label: 'Symbol',
      sortable: true,
      render: (position) => (
        <span className="asset-symbol-cell">
          <AssetMark symbol={position.symbol} size="sm" />
          <strong>{position.symbol}</strong>
        </span>
      )
    },
    { key: 'side', label: 'Side', sortable: true },
    { key: 'lots', label: 'Lots', sortable: true },
    { key: 'openPrice', label: t('positions.costPrice'), sortable: true, render: (position) => formatOptionalAmount(position.openPrice) },
    { key: 'currentPrice', label: 'Current', sortable: true },
    { key: 'floatingPnl', label: t('positions.unrealizedPnl'), sortable: true, render: (position) => formatOptionalAmount(position.floatingPnl) },
    { key: 'realizedPnl', label: t('positions.realizedPnl'), sortable: true, render: (position) => formatOptionalAmount(position.realizedPnl) },
    { key: 'marginHeld', label: 'Margin', sortable: true },
    { key: 'stopLoss', label: 'SL', render: (position) => canUseLegacyPositionProtection(position) ? position.stopLoss ?? '-' : '-' },
    { key: 'takeProfit', label: 'TP', render: (position) => canUseLegacyPositionProtection(position) ? position.takeProfit ?? '-' : '-' },
    { key: 'protectionStatus', label: t('positions.tpSlStatus'), render: (position) => formatProtectionStatus(position, t) },
    { key: 'status', label: t('common.status'), sortable: true, render: (position) => <StatusChip status={position.status} /> }
  ]

  if (view === 'CURRENT') {
    columns.push({
      key: 'actions',
      label: t('common.action'),
      render: (position) => {
        const canonicalPath = resolvePositionProtectionPath(position)
        return (
          <div className="user-page__actions" data-position-id={position.id} data-position-status={position.status}>
            <button
              type="button"
              className="table-action table-action--secondary"
              onClick={() => canonicalPath ? openProtectionWorkflow(position) : startProtectionEdit(position)}
              disabled={busyPositionId === position.id}
            >
              {canonicalPath ? t('positions.manageProtectionOrders') : 'TP/SL'}
            </button>
            <button type="button" className="table-action table-action--danger" onClick={(event) => openCloseDialog(position, event.currentTarget)} disabled={busyPositionId === position.id}>
              {t('positions.closePosition')}
            </button>
          </div>
        )
      }
    })
  }

  return columns
}

function StatusChip({ status }: { status: string }) {
  const tone = status === 'OPEN' ? 'positive' : status === 'CLOSED' ? 'negative' : ''
  return <span className={`status-chip${tone ? ` status-chip--${tone}` : ''}`}>{status}</span>
}

function createProtectionForm(position: PositionResponse): ProtectionFormValues {
  return {
    stopLoss: String(position.stopLoss ?? ''),
    takeProfit: String(position.takeProfit ?? '')
  }
}

function validateProtectionForm(position: PositionResponse, values: ProtectionFormValues, t: TFunction): ProtectionValidationResult {
  const stopLossText = values.stopLoss.trim()
  const takeProfitText = values.takeProfit.trim()
  const stopLoss = stopLossText ? parseProtectionPrice(stopLossText) : null
  const takeProfit = takeProfitText ? parseProtectionPrice(takeProfitText) : null
  const entryPrice = parseProtectionPrice(position.openPrice)
  const side = position.side.toUpperCase()

  if (!stopLossText && !takeProfitText) {
    return { ok: false, message: t('positions.validation.enterStopLossOrTakeProfit') }
  }
  if (stopLossText && stopLoss === null) {
    return { ok: false, message: t('positions.validation.stopLossPositive') }
  }
  if (takeProfitText && takeProfit === null) {
    return { ok: false, message: t('positions.validation.takeProfitPositive') }
  }
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

function getProtectionGuidance(position: PositionResponse, t: TFunction) {
  const entryPrice = parseProtectionPrice(position.openPrice)
  const side = position.side.toUpperCase()
  const isSell = side === 'SELL'
  const isBuy = side === 'BUY'
  const hint = isBuy
    ? t('positions.buyProtectionHint')
    : isSell
      ? t('positions.sellProtectionHint')
      : t('positions.genericProtectionHint')

  if (entryPrice === null) {
    return {
      hint,
      stopLossPlaceholder: t('positions.enterStopLossPrice'),
      takeProfitPlaceholder: t('positions.enterTakeProfitPrice')
    }
  }

  return {
    hint,
    stopLossPlaceholder: formatPriceExample(isSell ? entryPrice * 1.01 : entryPrice * 0.99),
    takeProfitPlaceholder: formatPriceExample(isSell ? entryPrice * 0.99 : entryPrice * 1.01)
  }
}

function parseProtectionPrice(value: unknown) {
  const text = String(value ?? '').trim()
  if (!text) return null
  if (!/^(?:\d+|\d+\.\d+|\.\d+)$/.test(text)) return null
  const numeric = Number(text)
  return Number.isFinite(numeric) && numeric > 0 ? numeric : null
}

function formatPriceExample(value: number) {
  return value.toFixed(8).replace(/\.?0+$/, '')
}

function formatProtectionStatus(position: PositionResponse, t: TFunction) {
  if (!canUseLegacyPositionProtection(position)) return t('positions.manageProtectionOrders')
  if (position.stopLoss && position.takeProfit) return t('positions.protectionBoth')
  if (position.stopLoss) return t('positions.protectionStopLossOnly')
  if (position.takeProfit) return t('positions.protectionTakeProfitOnly')
  return t('positions.protectionUnset')
}

function formatOptionalAmount(value: unknown) {
  if (value === null || value === undefined || value === '') return '-'
  return String(value)
}
