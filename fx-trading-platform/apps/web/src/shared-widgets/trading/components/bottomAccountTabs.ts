export const bottomAccountTabs = [
  'currentOrders',
  'historicalOrders',
  'currentPositions',
  'historicalPositions',
  'trades',
  'funding',
  'transfers',
  'assets',
  'strategies'
] as const

export type BottomAccountTab = (typeof bottomAccountTabs)[number]

export const bottomAccountTabLabelKeys: Record<BottomAccountTab, string> = {
  currentOrders: 'orders.current',
  historicalOrders: 'orders.history',
  currentPositions: 'positions.current',
  historicalPositions: 'positions.history',
  trades: 'orders.trades',
  funding: 'trading.fundingSettlements',
  transfers: 'trading.accountTransfers',
  assets: 'assets.walletTitle',
  strategies: 'trading.strategies'
}
