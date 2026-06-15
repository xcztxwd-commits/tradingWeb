export const bottomAccountTabs = ['currentOrders', 'historicalOrders', 'currentPositions', 'historicalPositions', 'assets', 'strategies'] as const

export type BottomAccountTab = (typeof bottomAccountTabs)[number]

export const bottomAccountTabLabelKeys: Record<BottomAccountTab, string> = {
  currentOrders: 'orders.current',
  historicalOrders: 'orders.history',
  currentPositions: 'positions.current',
  historicalPositions: 'positions.history',
  assets: 'assets.walletTitle',
  strategies: 'trading.strategies'
}
