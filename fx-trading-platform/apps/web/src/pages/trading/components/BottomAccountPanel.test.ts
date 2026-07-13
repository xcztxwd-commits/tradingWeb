import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { bottomAccountTabLabelKeys, bottomAccountTabs } from './bottomAccountTabs.ts'
import { getBottomAccountTabView, mockBottomAccountPanelData } from './bottomAccountPanelData.ts'
import { createPositionDisplayRow } from './positionDisplayModel.ts'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const currentDir = dirname(fileURLToPath(import.meta.url))
const source = readFileSync(join(currentDir, 'BottomAccountPanel.tsx'), 'utf8')
const selectionSource = readFileSync(join(currentDir, 'bottomAccountPanelSelection.ts'), 'utf8')
const styles = readFileSync(join(currentDir, 'BottomAccountPanel.module.css'), 'utf8')
const expectedViewFiles = [
  'BottomAccountOrdersGrid.tsx',
  'BottomAccountPositionsGrid.tsx',
  'BottomAccountAssetView.tsx',
  'BottomAccountStrategiesGrid.tsx',
  'bottomAccountFormatters.ts'
]
const testT = (key: string) =>
  ({
    'positions.instrumentTypes.swap': 'Perpetual',
    'positions.instrumentTypes.spot': 'Spot',
    'positions.instrumentTypes.forex': 'Forex',
    'positions.units.contract': 'contracts',
    'positions.units.lot': 'lots',
    'positions.marginModes.cross': 'Cross'
  })[key] ?? key

describe('bottom account panel tabs', () => {
  it('keeps the requested bottom tab order', () => {
    assert.deepEqual([...bottomAccountTabs], ['currentOrders', 'historicalOrders', 'currentPositions', 'historicalPositions', 'assets', 'strategies'])
    assert.equal(bottomAccountTabLabelKeys.currentOrders, 'orders.current')
    assert.equal(bottomAccountTabLabelKeys.strategies, 'trading.strategies')
  })

  it('opens the full-width bottom order area on current orders by default', () => {
    assert.match(source, /useState<BottomAccountTab>\('currentOrders'\)/)
  })

  it('uses the existing table skeleton while account data is still loading', () => {
    assert.match(source, /import \{ TableSkeleton \} from '\.\/TerminalSkeleton'/)
    assert.match(source, /loading\?: boolean/)
    assert.match(source, /loading = false/)
    assert.match(source, /aria-busy=\{loading\}/)
    assert.match(source, /loading \? \(\s*<TableSkeleton/)
  })

  it('adds a compact OKX-like table toolbar and current-symbol filter affordance', () => {
    assert.match(source, /accountToolbar/)
    assert.match(source, /trading\.onlyCurrentSymbol/)
    assert.match(selectionSource, /trading\.emptyCurrentSymbolOrders/)
    assert.match(source, /currentSymbol/)
    assert.match(source, /onlyCurrentSymbol/)
    assert.match(selectionSource, /normalizeSymbol\(order\.symbol\) === normalizeSymbol\(currentSymbol\)/)
    assert.doesNotMatch(source, /全部撤单/)
    assert.match(styles, /\.accountToolbar\s*{[\s\S]*display:\s*flex/)
    assert.match(styles, /\.body\s*{[^}]*background:\s*var\(--trading-surface-2\)/)
  })

  it('uses the same current-order lifecycle and action summaries as the Orders page', () => {
    const dataSource = readFileSync(join(currentDir, 'bottomAccountPanelData.ts'), 'utf8')
    const ordersGridSource = readFileSync(join(currentDir, 'BottomAccountOrdersGrid.tsx'), 'utf8')

    assert.match(dataSource, /isCurrentOrderStatus/)
    assert.match(ordersGridSource, /getOrderActionSummary/)
    assert.match(ordersGridSource, /orders\.actionStatus/)
    assert.match(ordersGridSource, /orders\.timeline/)
    assert.match(ordersGridSource, /function OrderTimeline/)
    assert.match(ordersGridSource, /getOrderTimelineItems/)
    assert.doesNotMatch(ordersGridSource, /全部撤单/)
  })

  it('switches every requested tab to a populated local mock data view', () => {
    const expectedVisibleCells = new Map([
      ['currentOrders', 'BTCUSDT'],
      ['historicalOrders', 'EURUSD'],
      ['currentPositions', 'XAUUSD'],
      ['historicalPositions', 'GBPUSD'],
      ['assets', 'Equity'],
      ['strategies', 'London Breakout']
    ])

    for (const tab of bottomAccountTabs) {
      const view = getBottomAccountTabView(tab, mockBottomAccountPanelData)
      const cells = view.rows.flat().map(String)

      assert.ok(view.rows.length > 0, `${tab} should render at least one mock row`)
      assert.ok(cells.includes(expectedVisibleCells.get(tab)), `${tab} should render its representative mock data`)
    }
  })

  it('keeps table and asset rendering in focused bottom account view components', () => {
    for (const fileName of expectedViewFiles) {
      assert.equal(existsSync(join(currentDir, fileName)), true, `${fileName} should exist`)
    }

    assert.match(source, /import \{ OrdersGrid \} from '\.\/BottomAccountOrdersGrid'/)
    assert.match(source, /import \{ PositionsGrid \} from '\.\/BottomAccountPositionsGrid'/)
    assert.match(source, /import \{ AssetView \} from '\.\/BottomAccountAssetView'/)
    assert.match(source, /import \{ StrategiesGrid \} from '\.\/BottomAccountStrategiesGrid'/)
    assert.doesNotMatch(source, /function OrdersGrid/)
    assert.doesNotMatch(source, /function PositionsGrid/)
    assert.doesNotMatch(source, /function AssetView/)
    assert.doesNotMatch(source, /function StrategiesGrid/)
    assert.ok(source.split(/\r?\n/).length <= 125, 'BottomAccountPanel.tsx should stay a small orchestration file')
  })

  it('renders OKX-style current position columns through a focused display model', () => {
    const positionsGridSource = readFileSync(join(currentDir, 'BottomAccountPositionsGrid.tsx'), 'utf8')
    assert.match(positionsGridSource, /partialCloseClientOrderId/)
    assert.match(positionsGridSource, /setPartialCloseClientOrderId\(createClientOrderId\('partial-close'\)\)/)
    const actionDialogSource = readFileSync(join(currentDir, '../../../features/trading/components/PositionActionDialog.tsx'), 'utf8')
    const displayModelPath = join(currentDir, 'positionDisplayModel.ts')

    assert.equal(existsSync(displayModelPath), true, 'positionDisplayModel.ts should own position row formatting')
    assert.match(positionsGridSource, /positions\.instrument/)
    assert.match(positionsGridSource, /positions\.markPrice/)
    assert.match(positionsGridSource, /positions\.openAveragePrice/)
    assert.match(positionsGridSource, /positions\.estimatedLiquidationPrice/)
    assert.match(positionsGridSource, /positions\.breakEvenPrice/)
    assert.match(positionsGridSource, /positions\.floatingPnl/)
    assert.match(positionsGridSource, /positions\.realizedPnl/)
    assert.match(positionsGridSource, /positions\.notional/)
    assert.match(positionsGridSource, /positions\.maintenanceMargin/)
    assert.match(positionsGridSource, /row\.maintenanceMargin/)
    assert.match(positionsGridSource, /positions\.takeProfitStopLoss/)
    assert.match(positionsGridSource, /positions\.closeAllMarket/)
    assert.match(source, /mode=\{activeTab === 'historicalPositions' \? 'history' : 'current'\}/)
    assert.match(positionsGridSource, /PositionActionDialog/)
    assert.match(positionsGridSource, /onClosePosition\(selectedPosition, mutation\)/)
    assert.match(positionsGridSource, /selectedPosition\.version/)
    assert.doesNotMatch(positionsGridSource, /positionVersions/)
    assert.doesNotMatch(source, /positionVersions/)
    assert.match(actionDialogSource, /MultiLevelProtectionEditor/)
    assert.match(actionDialogSource, /marginAdjustmentSupported/)
    assert.match(actionDialogSource, /Position version is unavailable from the current positions contract\./)
  })

  it('formats crypto swap positions with OKX-style labels', () => {
    const btcPosition = mockBottomAccountPanelData.positions.find((position) => position.symbol === 'BTCUSDT')
    assert.ok(btcPosition)

    const row = createPositionDisplayRow(btcPosition, testT)

    assert.equal(row.instrument, 'BTCUSDT Perpetual')
    assert.equal(row.leverage, '100x')
    assert.equal(row.quantity, '0.15 contracts')
    assert.equal(row.marginMode, 'Cross')
    assert.equal(row.liquidationPrice, '--')
  })

  it('formats forex, spot, linear perp, and inverse perp position rows with algorithm-aware units', () => {
    const forexRow = createPositionDisplayRow({
      id: 'fx-long',
      symbol: 'EURUSD',
      side: 'BUY',
      instrumentType: 'FOREX',
      marginMode: 'CROSS',
      leverage: 50,
      positionUnit: 'LOT',
      lots: '1.00',
      openPrice: '1.10002',
      currentPrice: '1.10100',
      floatingPnl: '98.00',
      floatingPnlRatio: '0.04454464',
      realizedPnl: '91.00',
      marginHeld: '2200.04',
      status: 'OPEN'
    }, testT)
    const spotRow = createPositionDisplayRow({
      id: 'spot-btc',
      symbol: 'BTCUSDT',
      side: 'BUY',
      instrumentType: 'SPOT',
      marginMode: null,
      leverage: null,
      positionUnit: 'BTC',
      lots: '0.1998',
      openPrice: '50050.05005',
      currentPrice: '55000',
      floatingPnl: '978.011',
      floatingPnlRatio: null,
      realizedPnl: '978.011',
      marginHeld: '10000',
      status: 'OPEN'
    }, testT)
    const inverseRow = createPositionDisplayRow({
      id: 'inverse-btc',
      symbol: 'BTCUSD',
      side: 'BUY',
      instrumentType: 'SWAP',
      marginMode: 'CROSS',
      leverage: 10,
      positionUnit: 'CONTRACT',
      lots: '100',
      openPrice: '50000',
      markPrice: '55000',
      currentPrice: '55000',
      floatingPnl: '0.01799091',
      floatingPnlRatio: '0.89954550',
      realizedPnl: '0.01799091',
      marginHeld: '0.02000000',
      status: 'OPEN'
    }, testT)

    assert.equal(forexRow.instrument, 'EURUSD Forex')
    assert.equal(forexRow.quantity, '1 lots')
    assert.equal(forexRow.floatingPnl, '98 USD (+4.45%)')
    assert.equal(forexRow.realizedPnl, '91 USD')
    assert.equal(spotRow.instrument, 'BTCUSDT Spot')
    assert.equal(spotRow.leverage, '--')
    assert.equal(spotRow.quantity, '0.1998 BTC')
    assert.equal(spotRow.floatingPnl, '978.011 USDT')
    assert.equal(inverseRow.instrument, 'BTCUSD Perpetual')
    assert.equal(inverseRow.quantity, '100 contracts')
    assert.equal(inverseRow.floatingPnl, '0.01799091 BTC (+89.95%)')
    assert.equal(inverseRow.margin, '0.02 BTC')
  })

  it('formats linear perp maintenance margin amounts', () => {
    const row = createPositionDisplayRow({
      id: 'linear-btc',
      symbol: 'BTCUSDT',
      side: 'BUY',
      instrumentType: 'LINEAR_PERP',
      marginMode: 'CROSS',
      leverage: 20,
      positionUnit: 'CONTRACT',
      lots: '0.50',
      openPrice: '60000',
      currentPrice: '60200',
      notional: '30100.00',
      floatingPnl: '100',
      realizedPnl: '0',
      marginHeld: '1505.00',
      maintenanceMargin: '120.40',
      maintenanceMarginRate: '0.004',
      status: 'OPEN'
    }, testT)

    assert.equal(row.showMaintenanceMargin, true)
    assert.equal(row.notional, '30,100 USDT')
    assert.equal(row.maintenanceMargin, '120.4 USDT')
  })

  it('does not display maintenance margin for spot positions', () => {
    const row = createPositionDisplayRow({
      id: 'spot-eth',
      symbol: 'ETHUSDT',
      side: 'BUY',
      instrumentType: 'SPOT',
      marginMode: null,
      leverage: null,
      positionUnit: 'ETH',
      lots: '1.25',
      openPrice: '3200',
      currentPrice: '3300',
      floatingPnl: '125',
      realizedPnl: '125',
      marginHeld: '4000',
      maintenanceMargin: '25',
      status: 'OPEN'
    }, testT)

    assert.equal(row.showMaintenanceMargin, false)
    assert.equal(row.maintenanceMargin, null)
    assert.equal(row.canClose, false)
  })

  it('falls back safely when maintenance margin is missing', () => {
    const row = createPositionDisplayRow({
      id: 'fx-missing-maintenance',
      symbol: 'EURUSD',
      side: 'BUY',
      instrumentType: 'FOREX',
      marginMode: 'CROSS',
      leverage: 50,
      positionUnit: 'LOT',
      lots: '1.00',
      openPrice: '1.10000',
      currentPrice: '1.10100',
      floatingPnl: '100',
      realizedPnl: '0',
      marginHeld: '2200',
      status: 'OPEN'
    }, testT)

    assert.equal(row.showMaintenanceMargin, true)
    assert.equal(row.maintenanceMargin, '--')
  })

  it('keeps bottom tabs and table actions keyboard-visible with smooth state changes', () => {
    assert.match(styles, /\.tabs button:focus-visible/)
    assert.match(styles, /\.tabs button\s*{[\s\S]*transition:/)
    assert.match(styles, /\.closeButton:active:not\(:disabled\)/)
    assert.doesNotMatch(styles, /\.tabs button:focus\s*{[\s\S]*outline:\s*none/)
  })
})
