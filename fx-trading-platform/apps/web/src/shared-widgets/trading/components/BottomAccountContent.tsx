import type { BottomAccountTab } from './bottomAccountTabs'
import type { BottomAccountTabView } from './bottomAccountPanelData'
import type { AccountPanelSelection } from './bottomAccountPanelSelection'
import { AssetView } from './BottomAccountAssetView'
import { FundingGrid } from './BottomAccountFundingGrid'
import { OrdersGrid } from './BottomAccountOrdersGrid'
import { PositionsGrid } from './BottomAccountPositionsGrid'
import type { PositionMutationHandler } from './BottomAccountPositionsGrid'
import { StrategiesGrid } from './BottomAccountStrategiesGrid'
import { TradesGrid } from './BottomAccountTradesGrid'
import { TransfersGrid } from './BottomAccountTransfersGrid'

type Props = {
  activeTab: BottomAccountTab
  activeView: BottomAccountTabView
  selection: AccountPanelSelection
  sessionReady: boolean
  onClosePosition?: PositionMutationHandler
}

export function BottomAccountContent({ activeTab, activeView, selection, sessionReady, onClosePosition }: Props) {
  if (activeView.kind === 'orders') return <OrdersGrid emptyLabel={selection.orderEmptyLabel} orders={selection.orders} />
  if (activeView.kind === 'positions') {
    return (
      <PositionsGrid
        emptyLabel={selection.positionEmptyLabel}
        mode={activeTab === 'historicalPositions' ? 'history' : 'current'}
        positions={selection.positions}
        onClosePosition={onClosePosition}
      />
    )
  }
  if (activeView.kind === 'trades') return <TradesGrid emptyLabel={activeView.emptyLabel} trades={activeView.trades} />
  if (activeView.kind === 'funding') {
    return <FundingGrid emptyLabel={activeView.emptyLabel} settlements={activeView.settlements} />
  }
  if (activeView.kind === 'transfers') {
    return <TransfersGrid emptyLabel={activeView.emptyLabel} transfers={activeView.transfers} />
  }
  if (activeView.kind === 'asset') {
    return <AssetView account={activeView.account} ledgerEntries={activeView.ledgerEntries} sessionReady={sessionReady} />
  }
  return <StrategiesGrid emptyLabel={activeView.emptyLabel} strategies={activeView.strategies} />
}
