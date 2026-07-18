import type { AccountSummary, LedgerEntry, OrderResponse, PositionResponse } from '@fx-platform/frontend-core'
import type { AccountTransferResponse, BatchActionResponse, FundingSettlement, Trade } from '@fx-platform/shared-types'
import { isCurrentOrderStatus } from '../../../routes/orders/orderActionPolicy.ts'
import type { BottomAccountTab } from './bottomAccountTabs.ts'

type Translate = (key: string, options?: Record<string, unknown>) => string

const defaultTranslate: Translate = (key) => key

export type StrategyRow = {
  id: string
  name: string
  symbol: string
  mode: string
  status: string
  exposure: string
  pnl: string
  updatedAt: string
}

export type BottomAccountPanelData = {
  account?: AccountSummary
  orders: OrderResponse[]
  positions: PositionResponse[]
  trades: Trade[]
  fundingSettlements: FundingSettlement[]
  transfers: AccountTransferResponse[]
  ledgerEntries: LedgerEntry[]
  strategies: StrategyRow[]
}

export type BottomAccountTabView =
  | {
      kind: 'orders'
      emptyLabel: string
      orders: OrderResponse[]
      rows: unknown[][]
    }
  | {
      kind: 'positions'
      emptyLabel: string
      positions: PositionResponse[]
      rows: unknown[][]
    }
  | {
      kind: 'trades'
      emptyLabel: string
      trades: Trade[]
      rows: unknown[][]
    }
  | {
      kind: 'funding'
      emptyLabel: string
      settlements: FundingSettlement[]
      rows: unknown[][]
    }
  | {
      kind: 'transfers'
      emptyLabel: string
      transfers: AccountTransferResponse[]
      rows: unknown[][]
    }
  | {
      kind: 'asset'
      account?: AccountSummary
      ledgerEntries: LedgerEntry[]
      metricRows: unknown[][]
      rows: unknown[][]
    }
  | {
      kind: 'strategies'
      emptyLabel: string
      strategies: StrategyRow[]
      rows: unknown[][]
    }

export type BottomAccountBatchAction = {
  kind: 'cancel-all-orders' | 'close-all-positions'
  disabled: boolean
}

export function getBottomAccountBatchAction(
  tab: BottomAccountTab,
  view: BottomAccountTabView,
  sessionReady: boolean,
  pending: boolean
): BottomAccountBatchAction | null {
  if (tab === 'currentOrders' && view.kind === 'orders') {
    return { kind: 'cancel-all-orders', disabled: !sessionReady || pending || view.orders.length === 0 }
  }
  if (tab === 'currentPositions' && view.kind === 'positions') {
    return { kind: 'close-all-positions', disabled: !sessionReady || pending || view.positions.length === 0 }
  }
  return null
}

export function getBottomAccountBatchActionError(response: BatchActionResponse, fallback: string) {
  const items = response.items ?? []
  const failures = items.filter((item) => item.status === 'FAILED')
  if (failures.length === 0) return null
  const details = failures
    .map(({ errorCode, message }) => [errorCode, message].filter(Boolean).join(': '))
    .filter(Boolean)
  return `${fallback} ${failures.length}/${items.length}${details.length > 0 ? `: ${details.join('; ')}` : ''}`
}

type BottomAccountPanelInput = {
  account?: AccountSummary
  orders?: OrderResponse[]
  positions?: PositionResponse[]
  trades?: Trade[]
  fundingSettlements?: FundingSettlement[]
  transfers?: AccountTransferResponse[]
  ledgerEntries?: LedgerEntry[]
  strategies?: StrategyRow[]
}

/** @internal Explicit display-model fixture; never used by the runtime resolver. */
export const bottomAccountPanelTestData: BottomAccountPanelData = {
  account: {
    id: 'mock-account-001',
    accountType: 'DEMO',
    baseCurrency: 'USD',
    balance: '100000.00',
    equity: '101342.18',
    usedMargin: '5480.20',
    freeMargin: '95961.98',
    marginLevel: '1849.57',
    leverage: 100,
    status: 'SIMULATION'
  },
  orders: [
    {
      id: 'mock-current-order-btc',
      symbol: 'BTCUSDT',
      side: 'BUY',
      orderType: 'LIMIT',
      status: 'PENDING',
      lots: '0.25',
      executionPrice: '68320.00',
      createdAt: '2026-06-07T01:18:00.000Z'
    },
    {
      id: 'mock-current-order-xau',
      symbol: 'XAUUSD',
      side: 'SELL',
      orderType: 'STOP',
      status: 'PARTIALLY_FILLED',
      lots: '0.80',
      executionPrice: '2334.50',
      createdAt: '2026-06-07T01:09:00.000Z'
    },
    {
      id: 'mock-history-order-eur',
      symbol: 'EURUSD',
      side: 'SELL',
      orderType: 'MARKET',
      status: 'FILLED',
      lots: '1.20',
      executionPrice: '1.0876',
      createdAt: '2026-06-06T14:42:00.000Z'
    },
    {
      id: 'mock-history-order-nas',
      symbol: 'NAS100',
      side: 'BUY',
      orderType: 'LIMIT',
      status: 'CANCELED',
      lots: '0.50',
      executionPrice: '18942.20',
      createdAt: '2026-06-06T09:24:00.000Z'
    }
  ],
  trades: [],
  fundingSettlements: [],
  transfers: [],
  positions: [
    {
      id: 'mock-current-position-xau',
      symbol: 'XAUUSD',
      side: 'BUY',
      lots: '0.80',
      openPrice: '2318.40',
      currentPrice: '2332.10',
      floatingPnl: '1096.00',
      realizedPnl: '0.00',
      marginHeld: '1854.72',
      status: 'OPEN'
    },
    {
      id: 'mock-current-position-btc',
      symbol: 'BTCUSDT',
      side: 'SELL',
      instrumentType: 'SWAP',
      marginMode: 'CROSS',
      leverage: 100,
      positionUnit: 'CONTRACT',
      lots: '0.15',
      openPrice: '69010.00',
      markPrice: '68480.00',
      currentPrice: '68480.00',
      liquidationPrice: null,
      breakEvenPrice: '69010.00',
      floatingPnl: '795.00',
      floatingPnlRatio: '0.774',
      realizedPnl: '0.00',
      marginHeld: '1027.20',
      maintenanceMarginRate: null,
      adlLevel: null,
      status: 'OPEN'
    },
    {
      id: 'mock-history-position-gbp',
      symbol: 'GBPUSD',
      side: 'BUY',
      lots: '1.00',
      openPrice: '1.2712',
      currentPrice: '1.2794',
      floatingPnl: '0.00',
      realizedPnl: '820.00',
      marginHeld: '0.00',
      status: 'CLOSED'
    }
  ],
  ledgerEntries: [
    {
      id: 'mock-ledger-deposit',
      accountId: 'mock-account-001',
      entryType: 'DEPOSIT',
      amount: '100000.00',
      balanceAfter: '100000.00',
      currency: 'USD',
      referenceType: null,
      referenceId: null,
      description: 'Mock deposit',
      createdAt: '2026-06-06T00:00:00.000Z'
    },
    {
      id: 'mock-ledger-realized-pnl',
      accountId: 'mock-account-001',
      entryType: 'REALIZED_PNL',
      amount: '820.00',
      balanceAfter: '100820.00',
      currency: 'USD',
      referenceType: 'POSITION',
      referenceId: 'mock-history-position-gbp',
      description: 'GBPUSD closed position',
      createdAt: '2026-06-06T14:50:00.000Z'
    }
  ],
  strategies: [
    {
      id: 'mock-strategy-london-breakout',
      name: 'London Breakout',
      symbol: 'GBPUSD',
      mode: 'Breakout',
      status: 'RUNNING',
      exposure: '1.00 lot',
      pnl: '+820.00',
      updatedAt: '2026-06-07T01:30:00.000Z'
    },
    {
      id: 'mock-strategy-gold-mean-reversion',
      name: 'Gold Mean Reversion',
      symbol: 'XAUUSD',
      mode: 'Mean reversion',
      status: 'PAUSED',
      exposure: '0.80 lot',
      pnl: '+1096.00',
      updatedAt: '2026-06-07T01:12:00.000Z'
    }
  ]
}

export function resolveBottomAccountPanelData(input: BottomAccountPanelInput = {}): BottomAccountPanelData {
  return {
    account: input.account,
    orders: input.orders ?? [],
    positions: input.positions ?? [],
    trades: input.trades ?? [],
    fundingSettlements: input.fundingSettlements ?? [],
    transfers: input.transfers ?? [],
    ledgerEntries: input.ledgerEntries ?? [],
    strategies: input.strategies ?? []
  }
}

export function getBottomAccountTabView(
  tab: BottomAccountTab,
  data: BottomAccountPanelData,
  t: Translate = defaultTranslate
): BottomAccountTabView {
  const currentOrders = data.orders.filter((order) => isCurrentOrderStatus(order.status))
  const historicalOrders = data.orders.filter((order) => !isCurrentOrderStatus(order.status))
  const currentPositions = data.positions.filter((position) => position.status.toUpperCase() !== 'CLOSED')
  const historicalPositions = data.positions.filter((position) => position.status.toUpperCase() === 'CLOSED')

  switch (tab) {
    case 'currentOrders':
      return {
        kind: 'orders',
        emptyLabel: t('orders.emptyCurrent'),
        orders: currentOrders,
        rows: currentOrders.map(orderRow)
      }
    case 'historicalOrders':
      return {
        kind: 'orders',
        emptyLabel: t('orders.emptyHistory'),
        orders: historicalOrders,
        rows: historicalOrders.map(orderRow)
      }
    case 'currentPositions':
      return {
        kind: 'positions',
        emptyLabel: t('positions.emptyCurrent'),
        positions: currentPositions,
        rows: currentPositions.map(positionRow)
      }
    case 'historicalPositions':
      return {
        kind: 'positions',
        emptyLabel: t('positions.emptyHistory'),
        positions: historicalPositions,
        rows: historicalPositions.map(positionRow)
      }
    case 'trades':
      return {
        kind: 'trades',
        emptyLabel: t('trading.emptyTrades'),
        trades: data.trades,
        rows: data.trades.map(tradeRow)
      }
    case 'funding':
      return {
        kind: 'funding',
        emptyLabel: t('trading.emptyFundingSettlements'),
        settlements: data.fundingSettlements,
        rows: data.fundingSettlements.map(fundingRow)
      }
    case 'transfers':
      return {
        kind: 'transfers',
        emptyLabel: t('trading.emptyAccountTransfers'),
        transfers: data.transfers,
        rows: data.transfers.map(transferRow)
      }
    case 'assets': {
      const metricRows = accountMetricRows(data.account)
      const ledgerRows = data.ledgerEntries.map(ledgerRow)
      return {
        kind: 'asset',
        account: data.account,
        ledgerEntries: data.ledgerEntries,
        metricRows,
        rows: [...metricRows, ...ledgerRows]
      }
    }
    case 'strategies':
      return {
        kind: 'strategies',
        emptyLabel: t('trading.emptyStrategies'),
        strategies: data.strategies,
        rows: data.strategies.map(strategyRow)
      }
  }
}

function orderRow(order: OrderResponse) {
  return [order.symbol, order.side, order.orderType, order.lots, order.status, order.executionPrice, order.createdAt]
}

function positionRow(position: PositionResponse) {
  return [
    position.symbol,
    position.side,
    position.lots,
    position.openPrice,
    position.currentPrice,
    position.floatingPnl,
    position.status
  ]
}

function tradeRow(trade: Trade) {
  return [trade.symbol, trade.side, trade.lots, trade.price, trade.fee, trade.feeAsset, trade.executedAt]
}

function fundingRow(settlement: FundingSettlement) {
  return [settlement.symbol, settlement.fundingRate, settlement.amount, settlement.asset, settlement.source, settlement.fundingTime]
}

function transferRow(transfer: AccountTransferResponse) {
  return [transfer.direction, transfer.amount, transfer.spotAvailable, transfer.perpBalance, transfer.replayed, transfer.createdAt]
}

function accountMetricRows(account?: AccountSummary) {
  if (!account) return []
  return [
    ['Balance', account.balance],
    ['Equity', account.equity],
    ['Used Margin', account.usedMargin],
    ['Free Margin', account.freeMargin],
    ['Leverage', `${account.leverage}x`],
    ['Status', account.status]
  ]
}

function ledgerRow(entry: LedgerEntry) {
  return [entry.entryType, entry.amount, entry.balanceAfter, entry.currency, entry.createdAt]
}

function strategyRow(strategy: StrategyRow) {
  return [strategy.name, strategy.symbol, strategy.mode, strategy.status, strategy.exposure, strategy.pnl, strategy.updatedAt]
}
