import { useId, useState } from 'react'
import { useTranslation } from 'react-i18next'

import type { OrderResponse, PositionResponse } from '../../../components/tables/types'
import type { AccountSummary, LedgerEntry } from '../../../types/trading'
import { bottomAccountTabLabelKeys, bottomAccountTabs } from './bottomAccountTabs'
import type { BottomAccountTab } from './bottomAccountTabs'
import { getBottomAccountTabView, resolveBottomAccountPanelData } from './bottomAccountPanelData'
import type { StrategyRow } from './bottomAccountPanelData'
import { mergePositions, resolveAccountPanelSelection } from './bottomAccountPanelSelection'
import { AssetView } from './BottomAccountAssetView'
import { OrdersGrid } from './BottomAccountOrdersGrid'
import { PositionsGrid } from './BottomAccountPositionsGrid'
import { StrategiesGrid } from './BottomAccountStrategiesGrid'
import styles from './BottomAccountPanel.module.css'
import { TableSkeleton } from './TerminalSkeleton'

type Props = {
  account?: AccountSummary
  orders?: OrderResponse[]
  positions?: PositionResponse[]
  positionHistory?: PositionResponse[]
  ledgerEntries?: LedgerEntry[]
  strategies?: StrategyRow[]
  loading?: boolean
  sessionReady?: boolean
  currentSymbol?: string
  onClosePosition?: (position: PositionResponse) => Promise<unknown> | void
}

export function BottomAccountPanel({
  account,
  orders,
  positions,
  positionHistory,
  ledgerEntries,
  strategies,
  loading = false,
  sessionReady = false,
  currentSymbol,
  onClosePosition
}: Props = {}) {
  const { t } = useTranslation()
  const [activeTab, setActiveTab] = useState<BottomAccountTab>('currentOrders')
  const [onlyCurrentSymbol, setOnlyCurrentSymbol] = useState(false)
  const panelId = useId()
  const panelData = resolveBottomAccountPanelData({
    account,
    orders,
    positions: mergePositions(positions, positionHistory),
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
      </div>

      <div id={panelId} className={styles.body} role="tabpanel" aria-busy={loading}>
        {loading ? (
          <TableSkeleton />
        ) : (
          <>
            {activeView.kind === 'orders' ? <OrdersGrid emptyLabel={selection.orderEmptyLabel} orders={selection.orders} /> : null}
            {activeView.kind === 'positions' ? (
              <PositionsGrid
                emptyLabel={selection.positionEmptyLabel}
                mode={activeTab === 'historicalPositions' ? 'history' : 'current'}
                positions={selection.positions}
                onClosePosition={onClosePosition}
              />
            ) : null}
            {activeView.kind === 'asset' ? (
              <AssetView account={activeView.account} ledgerEntries={activeView.ledgerEntries} sessionReady={sessionReady} />
            ) : null}
            {activeView.kind === 'strategies' ? (
              <StrategiesGrid emptyLabel={activeView.emptyLabel} strategies={activeView.strategies} />
            ) : null}
          </>
        )}
      </div>
    </section>
  )
}
