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
    'positions.units.contract': 'contracts',
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
    const displayModelPath = join(currentDir, 'positionDisplayModel.ts')

    assert.equal(existsSync(displayModelPath), true, 'positionDisplayModel.ts should own position row formatting')
    assert.match(positionsGridSource, /positions\.instrument/)
    assert.match(positionsGridSource, /positions\.markPrice/)
    assert.match(positionsGridSource, /positions\.openAveragePrice/)
    assert.match(positionsGridSource, /positions\.estimatedLiquidationPrice/)
    assert.match(positionsGridSource, /positions\.breakEvenPrice/)
    assert.match(positionsGridSource, /positions\.floatingPnl/)
    assert.match(positionsGridSource, /positions\.maintenanceMarginRate/)
    assert.match(positionsGridSource, /positions\.takeProfitStopLoss/)
    assert.match(positionsGridSource, /positions\.closeAllMarket/)
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

  it('keeps bottom tabs and table actions keyboard-visible with smooth state changes', () => {
    assert.match(styles, /\.tabs button:focus-visible/)
    assert.match(styles, /\.tabs button\s*{[\s\S]*transition:/)
    assert.match(styles, /\.closeButton:active:not\(:disabled\)/)
    assert.doesNotMatch(styles, /\.tabs button:focus\s*{[\s\S]*outline:\s*none/)
  })
})
