import { useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { AccountTransferResponse, BatchActionResponse, FundingSettlement, Trade } from '@fx-platform/shared-types'

import type { OrderResponse, PositionResponse } from '../../../components/tables/types'
import type { AccountSummary, LedgerEntry } from '../../../types/trading'
import { bottomAccountTabLabelKeys, bottomAccountTabs } from './bottomAccountTabs'
import type { BottomAccountTab } from './bottomAccountTabs'
import { getBottomAccountTabView, resolveBottomAccountPanelData } from './bottomAccountPanelData'
import type { StrategyRow } from './bottomAccountPanelData'
import { mergePositions, resolveAccountPanelSelection } from './bottomAccountPanelSelection'
import { BottomAccountContent } from './BottomAccountContent'
import { BottomAccountBatchAction } from './BottomAccountBatchAction'
import type { PositionMutationHandler } from './BottomAccountPositionsGrid'
import styles from './BottomAccountPanel.module.css'
import { TableSkeleton } from './TerminalSkeleton'

type Props = {
  account?: AccountSummary
  orders?: OrderResponse[]
  positions?: PositionResponse[]
  positionHistory?: PositionResponse[]
  trades?: Trade[]
  fundingSettlements?: FundingSettlement[]
  transfers?: AccountTransferResponse[]
  ledgerEntries?: LedgerEntry[]
  strategies?: StrategyRow[]
  loading?: boolean
  sessionReady?: boolean
  currentSymbol?: string
  onClosePosition?: PositionMutationHandler
  onCancelAllOrders?: () => Promise<BatchActionResponse>
  onCloseAllPositions?: () => Promise<BatchActionResponse>
}

export function BottomAccountPanel({
  account,
  orders,
  positions,
  positionHistory,
  trades,
  fundingSettlements,
  transfers,
  ledgerEntries,
  strategies,
  loading = false,
  sessionReady = false,
  currentSymbol,
  onClosePosition,
  onCancelAllOrders,
  onCloseAllPositions
}: Props = {}) {
  const { t } = useTranslation()
  const [activeTab, setActiveTab] = useState<BottomAccountTab>('currentOrders')
  const [onlyCurrentSymbol, setOnlyCurrentSymbol] = useState(false)
  const panelId = useId()
  const panelData = resolveBottomAccountPanelData({
    account,
    orders,
    positions: mergePositions(positions, positionHistory),
    trades,
    fundingSettlements,
    transfers,
    ledgerEntries,
    strategies
  })
  const activeView = getBottomAccountTabView(activeTab, panelData, t)
  const selection = resolveAccountPanelSelection(activeView, onlyCurrentSymbol, currentSymbol, t)

  return (
    <section className={styles.panel} aria-label={t('trading.accountInfo')} aria-busy={loading}>
      <div className={styles.tabs} role="tablist" aria-label={t('trading.accountInfoTabs')}>
        {bottomAccountTabs.map((tab) => (
          <button
            key={tab}
            type="button"
            className={tab === activeTab ? styles.activeTab : ''}
            role="tab"
            aria-controls={panelId}
            aria-selected={tab === activeTab}
            onClick={() => setActiveTab(tab)}
          >
            {t(bottomAccountTabLabelKeys[tab])}
          </button>
        ))}
      </div>

      <div className={styles.accountToolbar} aria-label={t('trading.accountListTools')}>
        <label>
          <input
            type="checkbox"
            checked={onlyCurrentSymbol}
            disabled={!selection.canFilterCurrentSymbol}
            onChange={(event) => setOnlyCurrentSymbol(event.target.checked)}
          />
          {t('trading.onlyCurrentSymbol')}
          {currentSymbol ? <strong>{currentSymbol}</strong> : null}
        </label>
        <BottomAccountBatchAction
          activeTab={activeTab}
          activeView={activeView}
          sessionReady={sessionReady}
          onCancelAllOrders={onCancelAllOrders}
          onCloseAllPositions={onCloseAllPositions}
        />
      </div>

      <div id={panelId} className={styles.body} role="tabpanel" aria-busy={loading}>
        {loading ? (
          <TableSkeleton />
        ) : (
          <BottomAccountContent
            activeTab={activeTab}
            activeView={activeView}
            selection={selection}
            sessionReady={sessionReady}
            onClosePosition={onClosePosition}
          />
        )}
      </div>
    </section>
  )
}
