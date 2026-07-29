import { useRef } from 'react'
import type { TFunction } from 'i18next'
import { useTranslation } from 'react-i18next'
import { Dialog, type DataViewColumn } from '@fx-platform/ui'
import type { PositionResponse } from '@fx-platform/frontend-core'

import { ApiErrorState, LoadingState, LoginRequiredState } from '../data/PageState'
import {
  canUseLegacyPositionProtection,
  resolvePositionProtectionPath
} from '../../routes/positions/positionProtectionPolicy'
import { parseProtectionPrice } from '../../routes/positions/positionsRouteModel'
import type { PositionsRouteModel, PositionView } from '../../routes/positions/positionsRoute.types'
import { AssetMark } from '../asset/AssetMark'
import { cssModuleClasses as css } from '../data/cssModuleClasses'
import type { RouteDataCollectionRenderer } from '../data/RouteDataCollection'
import surfaceStyles from '../data/UserPageSurface.module.css'
import routeStyles from './PositionsRouteContent.module.css'

const styles = { ...surfaceStyles, ...routeStyles }

const viewOptions: Array<{ value: PositionView; labelKey: string }> = [
  { value: 'CURRENT', labelKey: 'positions.current' },
  { value: 'HISTORY', labelKey: 'positions.history' }
]

export function PositionsRouteContent({
  model,
  renderDataCollection
}: {
  model: PositionsRouteModel
  renderDataCollection: RouteDataCollectionRenderer
}) {
  const { t } = useTranslation()
  const closeDialogCancelButtonRef = useRef<HTMLButtonElement | null>(null)
  const closeDialogIsBusy = model.pendingClosePosition
    ? model.busyPositionId === model.pendingClosePosition.id
    : false

  if (model.loginRequired) {
    return <section className={css(styles, "user-page")}><LoginRequiredState message={t('positions.loginMessage')} onLogin={model.openLogin} /></section>
  }

  return (
    <section className={css(styles, "user-page", "positions-page")}>
      <header className={css(styles, "user-page__header")}>
        <div><h1>{t('positions.center')}</h1><p>{t('positions.centerSummary')}</p></div>
        <button type="button" className={css(styles, "table-action", "table-action--secondary")} onClick={() => void model.refresh()}>{t('common.refresh')}</button>
      </header>

      <div className={css(styles, "user-page__tabs")} role="tablist" aria-label={t('positions.viewAria')}>
        {viewOptions.map((option) => (
          <button key={option.value} type="button" className={model.view === option.value ? styles.active : undefined} onClick={() => model.setView(option.value)}>
            {t(option.labelKey)}
          </button>
        ))}
      </div>
      <div className={css(styles, "user-page__toolbar")} aria-label={t('positions.filterAria')}>
        <label><span>Symbol</span><input value={model.symbol} onChange={(event) => model.setSymbol(event.target.value)} placeholder="EURUSD" /></label>
      </div>

      {model.sessionMode === 'loading' ? <LoadingState message={t('positions.loading')} /> : null}
      {model.sessionMode === 'error' ? (
        <ApiErrorState error={{ title: 'POSITION_LOAD_FAILED', message: model.sessionError ?? t('positions.unavailable') }} onAction={() => void model.refresh()} />
      ) : null}
      {model.apiError ? <ApiErrorState error={model.apiError} onAction={() => void model.refresh()} /> : null}
      {model.notice ? <div className={css(styles, "user-page__notice")}>{model.notice}</div> : null}

      {renderDataCollection<PositionResponse>({
        rows: model.visiblePositions,
        columns: positionColumns(model, t, model.openCloseDialog),
        rowKey: (position) => position.id,
        emptyMessage: model.view === 'CURRENT' ? t('positions.emptyCurrent') : t('positions.emptyHistory'),
        emptyAction: { label: t('dashboard.viewMarkets'), href: '/markets' }
      })}

      {model.editingPosition ? <ProtectionForm model={model} /> : null}
      {model.pendingClosePosition ? (
        <Dialog
          open
          onClose={model.dismissCloseDialog}
          ariaLabel={t('positions.closeConfirmDialog')}
          closeLabel={t('common.cancel')}
          pending={closeDialogIsBusy}
          priority="critical"
          initialFocusRef={closeDialogCancelButtonRef}
          panelClassName={css(styles, "confirm-dialog__panel")}
        >
          <div>
            <h2>{t('positions.closeConfirmTitle')}</h2>
            <p>{t('positions.closeConfirmBody')}</p>
            <dl className={css(styles, "confirm-dialog__details")}>
              <Detail label={t('trading.symbol')} value={model.pendingClosePosition.symbol} />
              <Detail label={t('trading.side')} value={model.pendingClosePosition.side} />
              <Detail label={t('common.quantity')} value={formatOptionalAmount(model.pendingClosePosition.lots)} />
              <Detail label={t('positions.costPrice')} value={formatOptionalAmount(model.pendingClosePosition.openPrice)} />
              <Detail label={t('positions.unrealizedPnl')} value={formatOptionalAmount(model.pendingClosePosition.floatingPnl)} />
            </dl>
            <p className={css(styles, "confirm-dialog__risk")}>{t('positions.closeRiskNotice')}</p>
            <div className={css(styles, "user-page__actions")}>
              <button type="button" className={css(styles, "table-action", "table-action--danger")} onClick={() => void model.closePosition(model.pendingClosePosition!)} disabled={closeDialogIsBusy}>{t('positions.closeConfirmTitle')}</button>
              <button type="button" className={css(styles, "table-action", "table-action--secondary")} ref={closeDialogCancelButtonRef} onClick={model.dismissCloseDialog} disabled={closeDialogIsBusy}>{t('common.cancel')}</button>
            </div>
          </div>
        </Dialog>
      ) : null}
    </section>
  )
}

function ProtectionForm({ model }: { model: PositionsRouteModel }) {
  const { t } = useTranslation()
  const position = model.editingPosition
  if (!position) return null
  const guidance = getProtectionGuidance(position, t)
  return (
    <section className={css(styles, "user-page__events")} aria-label={t('positions.modifyTpSl')}>
      <h2>{t('positions.modifyTpSl')}</h2>
      <div className={css(styles, "protection-form__summary")} aria-label={t('positions.positionReference')}>
        <span><strong>{position.symbol}</strong></span>
        <span>{t('trading.side')} {position.side}</span>
        <span>{t('positions.costPrice')} {formatOptionalAmount(position.openPrice)}</span>
        <span>{t('dashboard.currentPrice')} {formatOptionalAmount(position.currentPrice)}</span>
      </div>
      <p id="position-protection-hint" className={css(styles, "protection-form__hint")}>{guidance.hint}</p>
      <form className={css(styles, "user-page__form", "protection-form")} onSubmit={(event) => { event.preventDefault(); void model.submitProtection() }} noValidate>
        <label className={css(styles, "protection-form__input")}>
          <span>{t('positions.stopLossPrice')}</span>
          <input name="stopLoss" type="number" inputMode="decimal" min="0" step="0.00000001" value={model.protectionForm.stopLoss} placeholder={guidance.stopLossPlaceholder} aria-invalid={model.protectionError?.includes(t('trading.stopLoss')) ? true : undefined} aria-describedby={model.protectionError ? 'position-protection-hint position-protection-error' : 'position-protection-hint'} onChange={(event) => model.setProtectionField('stopLoss', event.target.value)} />
          <small>{t('positions.stopLossHelp')}</small>
        </label>
        <label className={css(styles, "protection-form__input")}>
          <span>{t('positions.takeProfitPrice')}</span>
          <input name="takeProfit" type="number" inputMode="decimal" min="0" step="0.00000001" value={model.protectionForm.takeProfit} placeholder={guidance.takeProfitPlaceholder} aria-invalid={model.protectionError?.includes(t('trading.takeProfit')) ? true : undefined} aria-describedby={model.protectionError ? 'position-protection-hint position-protection-error' : 'position-protection-hint'} onChange={(event) => model.setProtectionField('takeProfit', event.target.value)} />
          <small>{t('positions.takeProfitHelp')}</small>
        </label>
        {model.protectionError ? <p id="position-protection-error" className={css(styles, "protection-form__error")} role="alert">{model.protectionError}</p> : null}
        <div className={css(styles, "protection-form__actions")}>
          <button type="submit" className={css(styles, "table-action", "table-action--primary")} disabled={model.busyPositionId === position.id}>{t('positions.saveTpSl')}</button>
          <button type="button" className={css(styles, "table-action", "table-action--secondary")} onClick={model.dismissProtectionEdit}>{t('common.cancel')}</button>
        </div>
      </form>
    </section>
  )
}

function positionColumns(
  model: PositionsRouteModel,
  t: TFunction,
  openCloseDialog: (position: PositionResponse) => void
): Array<DataViewColumn<PositionResponse>> {
  const columns: Array<DataViewColumn<PositionResponse>> = [
    { key: 'symbol', label: 'Symbol', sortable: true, render: (position) => <span className={css(styles, "asset-symbol-cell")}><AssetMark symbol={position.symbol} size="sm" /><strong>{position.symbol}</strong></span> },
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
  if (model.view === 'CURRENT') columns.push({
    key: 'actions',
    label: t('common.action'),
    render: (position) => {
      const canonicalPath = resolvePositionProtectionPath(position)
      return (
        <div className={css(styles, "user-page__actions")} data-position-id={position.id} data-position-status={position.status}>
          <button type="button" className={css(styles, "table-action", "table-action--secondary")} onClick={() => canonicalPath ? model.openProtectionWorkflow(position) : model.startProtectionEdit(position)} disabled={model.busyPositionId === position.id}>{canonicalPath ? t('positions.manageProtectionOrders') : 'TP/SL'}</button>
          <button type="button" className={css(styles, "table-action", "table-action--danger")} onClick={() => openCloseDialog(position)} disabled={model.busyPositionId === position.id}>{t('positions.closePosition')}</button>
        </div>
      )
    }
  })
  return columns
}

function Detail({ label, value }: { label: string; value: string }) { return <div><dt>{label}</dt><dd>{value}</dd></div> }
function StatusChip({ status }: { status: string }) {
  const tone = status === 'OPEN' ? 'positive' : status === 'CLOSED' ? 'negative' : ''
  return <span className={css(styles, 'status-chip', tone && `status-chip--${tone}`)}>{status}</span>
}
function getProtectionGuidance(position: PositionResponse, t: TFunction) {
  const entry = parseProtectionPrice(position.openPrice)
  const side = position.side.toUpperCase()
  const hint = side === 'BUY' ? t('positions.buyProtectionHint') : side === 'SELL' ? t('positions.sellProtectionHint') : t('positions.genericProtectionHint')
  if (entry === null) return { hint, stopLossPlaceholder: t('positions.enterStopLossPrice'), takeProfitPlaceholder: t('positions.enterTakeProfitPrice') }
  return {
    hint,
    stopLossPlaceholder: formatPriceExample(side === 'SELL' ? entry * 1.01 : entry * 0.99),
    takeProfitPlaceholder: formatPriceExample(side === 'SELL' ? entry * 0.99 : entry * 1.01)
  }
}
function formatProtectionStatus(position: PositionResponse, t: TFunction) {
  if (!canUseLegacyPositionProtection(position)) return t('positions.manageProtectionOrders')
  if (position.stopLoss && position.takeProfit) return t('positions.protectionBoth')
  if (position.stopLoss) return t('positions.protectionStopLossOnly')
  if (position.takeProfit) return t('positions.protectionTakeProfitOnly')
  return t('positions.protectionUnset')
}
function formatPriceExample(value: number) { return value.toFixed(8).replace(/\.?0+$/, '') }
function formatOptionalAmount(value: unknown) { return value === null || value === undefined || value === '' ? '-' : String(value) }
