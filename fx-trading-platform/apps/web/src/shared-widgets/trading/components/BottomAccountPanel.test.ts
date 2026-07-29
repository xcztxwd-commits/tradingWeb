import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { bottomAccountTabLabelKeys, bottomAccountTabs } from './bottomAccountTabs.ts'
import {
  bottomAccountPanelTestData,
  getBottomAccountBatchAction,
  getBottomAccountBatchActionError,
  getBottomAccountTabView,
  resolveBottomAccountPanelData
} from './bottomAccountPanelData.ts'
import * as positionDisplayModel from './positionDisplayModel.ts'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const currentDir = dirname(fileURLToPath(import.meta.url))
const projectRoot = resolve(currentDir, '../../../../../..')
const { createPositionDisplayRow } = positionDisplayModel
const source = readFileSync(join(currentDir, 'BottomAccountPanel.tsx'), 'utf8')
const contentSource = readFileSync(join(currentDir, 'BottomAccountContent.tsx'), 'utf8')
const positionResponseTypesSource = readFileSync(
  join(projectRoot, 'packages', 'frontend-core', 'src', 'models', 'account.ts'),
  'utf8'
)
const selectionSource = readFileSync(join(currentDir, 'bottomAccountPanelSelection.ts'), 'utf8')
const styles = readFileSync(join(currentDir, 'BottomAccountPanel.module.css'), 'utf8')
const expectedViewFiles = [
  'BottomAccountContent.tsx',
  'BottomAccountOrdersGrid.tsx',
  'BottomAccountPositionsGrid.tsx',
  'BottomAccountTradesGrid.tsx',
  'BottomAccountFundingGrid.tsx',
  'BottomAccountTransfersGrid.tsx',
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
  it('keeps missing and empty API arrays empty instead of injecting mock account data', () => {
    const data = resolveBottomAccountPanelData({
      orders: [],
      positions: [],
      trades: [],
      fundingSettlements: [],
      transfers: [],
      ledgerEntries: [],
      strategies: []
    })
    assert.equal(data.account, undefined)
    assert.deepEqual(data.orders, [])
    assert.deepEqual(data.positions, [])
    assert.deepEqual(data.trades, [])
    assert.deepEqual(data.fundingSettlements, [])
    assert.deepEqual(data.transfers, [])
    assert.deepEqual(data.ledgerEntries, [])
    assert.deepEqual(data.strategies, [])
  })
  it('keeps the requested bottom tab order', () => {
    assert.deepEqual([...bottomAccountTabs], [
      'currentOrders',
      'historicalOrders',
      'currentPositions',
      'historicalPositions',
      'trades',
      'funding',
      'transfers',
      'assets',
      'strategies'
    ])
    assert.equal(bottomAccountTabLabelKeys.currentOrders, 'orders.current')
    assert.equal(bottomAccountTabLabelKeys.trades, 'orders.trades')
    assert.equal(bottomAccountTabLabelKeys.funding, 'trading.fundingSettlements')
    assert.equal(bottomAccountTabLabelKeys.transfers, 'trading.accountTransfers')
    assert.equal(bottomAccountTabLabelKeys.strategies, 'trading.strategies')
  })

  it('opens the full-width bottom order area on current orders by default', () => {
    assert.match(source, /useState<BottomAccountTab>\('currentOrders'\)/)
  })

  it('uses the existing table skeleton while account data is still loading', () => {
    assert.match(source, /import \{ TableSkeleton \} from '\.\.\/\.\.\/\.\.\/components\/loading\/TerminalSkeleton'/)
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

  it('keeps the legacy tab display fixtures isolated from missing API collections', () => {
    const expectedVisibleCells = new Map([
      ['currentOrders', 'BTCUSDT'],
      ['historicalOrders', 'EURUSD'],
      ['currentPositions', 'XAUUSD'],
      ['historicalPositions', 'GBPUSD'],
      ['assets', 'Equity'],
      ['strategies', 'London Breakout']
    ])

    for (const tab of expectedVisibleCells.keys()) {
      const view = getBottomAccountTabView(tab, bottomAccountPanelTestData)
      const cells = view.rows.flat().map(String)

      assert.ok(view.rows.length > 0, `${tab} should render at least one mock row`)
      assert.ok(cells.includes(expectedVisibleCells.get(tab)), `${tab} should render its representative mock data`)
    }
  })

  it('keeps table and asset rendering in focused bottom account view components', () => {
    for (const fileName of expectedViewFiles) {
      assert.equal(existsSync(join(currentDir, fileName)), true, `${fileName} should exist`)
    }

    assert.match(source, /import \{ BottomAccountContent \} from '\.\/BottomAccountContent'/)
    assert.match(contentSource, /import \{ OrdersGrid \} from '\.\/BottomAccountOrdersGrid'/)
    assert.match(contentSource, /import \{ PositionsGrid \} from '\.\/BottomAccountPositionsGrid'/)
    assert.match(contentSource, /import \{ AssetView \} from '\.\/BottomAccountAssetView'/)
    assert.match(contentSource, /import \{ StrategiesGrid \} from '\.\/BottomAccountStrategiesGrid'/)
    assert.doesNotMatch(contentSource, /function OrdersGrid/)
    assert.doesNotMatch(contentSource, /function PositionsGrid/)
    assert.doesNotMatch(contentSource, /function AssetView/)
    assert.doesNotMatch(contentSource, /function StrategiesGrid/)
    assert.ok(source.split(/\r?\n/).length <= 125, 'BottomAccountPanel.tsx should stay a small orchestration file')
  })

  it('renders OKX-style current position columns through a focused display model', () => {
    const positionsGridSource = readFileSync(join(currentDir, 'BottomAccountPositionsGrid.tsx'), 'utf8')
    assert.match(positionsGridSource, /partialCloseClientOrderId/)
    assert.match(positionsGridSource, /setPartialCloseClientOrderId\(createClientOrderId\('partial-close'\)\)/)
    const actionDialogSource = readFileSync(join(currentDir, '../order-form/PositionActionDialog.tsx'), 'utf8')
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
    assert.match(positionsGridSource, /title=\{t\('positions\.closePosition'\)\}/)
    assert.match(contentSource, /mode=\{activeTab === 'historicalPositions' \? 'history' : 'current'\}/)
    assert.match(positionsGridSource, /PositionActionDialog/)
    assert.match(positionsGridSource, /onClosePosition\(selectedPosition, mutation\)/)
    assert.match(positionsGridSource, /selectedPosition\.version/)
    assert.doesNotMatch(positionsGridSource, /positionVersions/)
    assert.doesNotMatch(source, /positionVersions/)
    assert.match(actionDialogSource, /MultiLevelProtectionEditor/)
    assert.match(actionDialogSource, /marginAdjustmentSupported/)
    assert.match(actionDialogSource, /Position version is unavailable from the current positions contract\./)
  })

  it('offers account batch actions only for non-empty ready current collections', () => {
    const data = resolveBottomAccountPanelData({
      orders: [{
        id: 'order-1', symbol: 'BTCUSDT', side: 'BUY', orderType: 'LIMIT', status: 'PENDING',
        lots: 1, executionPrice: 60000, createdAt: '2026-07-13T00:00:00Z'
      }],
      positions: [{
        id: 'position-1', symbol: 'BTCUSDT-PERP', side: 'BUY', lots: 1, openPrice: 60000,
        currentPrice: 60100, floatingPnl: 100, realizedPnl: 0, marginHeld: 3000, status: 'OPEN'
      }]
    })
    const currentOrders = getBottomAccountTabView('currentOrders', data)
    const currentPositions = getBottomAccountTabView('currentPositions', data)
    const emptyOrders = getBottomAccountTabView('currentOrders', resolveBottomAccountPanelData())

    assert.deepEqual(getBottomAccountBatchAction('currentOrders', currentOrders, true, false), {
      kind: 'cancel-all-orders', disabled: false
    })
    assert.deepEqual(getBottomAccountBatchAction('currentPositions', currentPositions, true, false), {
      kind: 'close-all-positions', disabled: false
    })
    assert.equal(getBottomAccountBatchAction('historicalOrders', currentOrders, true, false), null)
    assert.equal(getBottomAccountBatchAction('currentOrders', emptyOrders, true, false)?.disabled, true)
    assert.equal(getBottomAccountBatchAction('currentOrders', currentOrders, false, false)?.disabled, true)
    assert.equal(getBottomAccountBatchAction('currentOrders', currentOrders, true, true)?.disabled, true)
  })

  it('summarizes failed items from a partially successful batch response', () => {
    assert.equal(getBottomAccountBatchActionError({
      items: [
        { status: 'CANCELED' },
        { status: 'FAILED', errorCode: 'INSUFFICIENT_MARGIN', message: 'Not enough margin' }
      ]
    }, 'Batch action failed.'), 'Batch action failed. 1/2: INSUFFICIENT_MARGIN: Not enough margin')
  })

  it('summarizes every failure when all batch items fail', () => {
    assert.equal(getBottomAccountBatchActionError({
      items: [
        { status: 'FAILED', errorCode: 'POSITION_NOT_OPEN' },
        { status: 'FAILED', message: 'Market unavailable' }
      ]
    }, 'Batch action failed.'), 'Batch action failed. 2/2: POSITION_NOT_OPEN; Market unavailable')
  })

  it('shares confirmed pending-safe batch controls across desktop and mobile terminals', () => {
    const batchSource = readFileSync(join(currentDir, 'BottomAccountBatchAction.tsx'), 'utf8')
    const desktopSource = readFileSync(join(currentDir, '../../../pc/pages/trading/PcTradingTerminal.tsx'), 'utf8')
    const mobileSource = readFileSync(join(currentDir, '../../../mobile/pages/trading/MobileTradingTerminal.tsx'), 'utf8')
    const positionsGridSource = readFileSync(join(currentDir, 'BottomAccountPositionsGrid.tsx'), 'utf8')

    assert.match(source, /<BottomAccountBatchAction/)
    assert.match(batchSource, /window\.confirm/)
    assert.match(batchSource, /setPending\(true\)/)
    assert.match(batchSource, /const response = await handler\(\)/)
    assert.match(batchSource, /getBottomAccountBatchActionError\(response, t\('orders\.batchActionFailed'\)\)/)
    assert.match(batchSource, /role="alert"/)
    assert.match(batchSource, /disabled=\{action\.disabled \|\| pending/)
    assert.match(batchSource, /useEffect\(\(\) => setError\(null\), \[action\?\.kind\]\)/)
    for (const viewSource of [desktopSource, mobileSource]) {
      assert.match(viewSource, /onCancelAllOrders=\{model\.accountPanel\.onCancelAllOrders\}/)
      assert.match(viewSource, /onCloseAllPositions=\{model\.accountPanel\.onCloseAllPositions\}/)
    }
    assert.doesNotMatch(positionsGridSource, /title=\{t\('positions\.closeAllMarket'\)\}/)
  })

  it('renders account trades, funding settlements, and transfers from API data', () => {
    const data = resolveBottomAccountPanelData({
      trades: [{ id: 'trade-1', symbol: 'BTCUSDT', side: 'BUY', lots: 0.1, price: 64000, executedAt: '2026-07-12T01:00:00Z' }],
      fundingSettlements: [
        {
          id: 'funding-1',
          symbol: 'BTCUSDT',
          fundingRate: 0.0001,
          amount: -0.64,
          asset: 'USDT',
          fundingTime: '2026-07-12T00:00:00Z'
        }
      ],
      transfers: [
        {
          transferId: 'transfer-1',
          direction: 'SPOT_TO_PERP',
          amount: 100,
          spotAvailable: 900,
          perpBalance: 100,
          createdAt: '2026-07-12T00:30:00Z'
        }
      ]
    })

    assert.deepEqual(getBottomAccountTabView('trades', data).rows[0]?.slice(0, 2), ['BTCUSDT', 'BUY'])
    assert.deepEqual(getBottomAccountTabView('funding', data).rows[0]?.slice(0, 2), ['BTCUSDT', 0.0001])
    assert.deepEqual(getBottomAccountTabView('transfers', data).rows[0]?.slice(0, 2), ['SPOT_TO_PERP', 100])
    assert.match(contentSource, /<TradesGrid/)
    assert.match(contentSource, /<FundingGrid/)
    assert.match(contentSource, /<TransfersGrid/)
  })

  it('formats crypto swap positions with OKX-style labels', () => {
    const btcPosition = bottomAccountPanelTestData.positions.find((position) => position.symbol === 'BTCUSDT')
    assert.ok(btcPosition)

    const row = createPositionDisplayRow(btcPosition, testT)

    assert.equal(row.instrument, 'BTCUSDT Perpetual')
    assert.equal(row.leverage, '100x')
    assert.equal(row.quantity, '0.15 contracts')
    assert.equal(row.marginMode, 'Cross')
    assert.equal(row.liquidationPrice, '--')
  })

  it('preserves generated position identity fields and distinguishes HEDGE position sides', () => {
    const row = createPositionDisplayRow({
      id: 'hedge-short',
      symbol: 'BTCUSDT-PERP',
      side: 'SELL',
      instrumentType: 'SWAP',
      productType: 'LINEAR_PERP',
      positionMode: 'HEDGE',
      positionSide: 'SHORT',
      marginMode: 'CROSS',
      leverage: 10,
      positionUnit: 'CONTRACT',
      lots: '0.25',
      openPrice: '60000',
      currentPrice: '59000',
      floatingPnl: '250',
      realizedPnl: '0',
      marginHeld: '1500',
      status: 'OPEN'
    }, testT)

    assert.equal(row.positionSide, 'SHORT')
    assert.match(positionResponseTypesSource, /PositionResponse as GeneratedPositionResponse/)
    assert.match(positionResponseTypesSource, /Omit<GeneratedPositionResponse/)
    const positionsGridSource = readFileSync(join(currentDir, 'BottomAccountPositionsGrid.tsx'), 'utf8')
    assert.match(positionsGridSource, /positions\.positionSide/)
    assert.match(positionsGridSource, /row\.positionSide/)
  })

  it('defaults position actions to the backend native unit without forbidding unit switches', () => {
    const resolvePositionQuantityUnit = (positionDisplayModel as Record<string, unknown>).resolvePositionQuantityUnit
    assert.equal(typeof resolvePositionQuantityUnit, 'function')
    assert.equal((resolvePositionQuantityUnit as Function)({ positionUnit: 'CONTRACT' }), 'CONTRACTS')
    assert.equal((resolvePositionQuantityUnit as Function)({ positionUnit: 'BTC' }), 'BASE')

    const positionsGridSource = readFileSync(join(currentDir, 'BottomAccountPositionsGrid.tsx'), 'utf8')
    const actionDialogSource = readFileSync(join(currentDir, '../order-form/PositionActionDialog.tsx'), 'utf8')
    const protectionSource = readFileSync(join(currentDir, '../order-form/MultiLevelProtectionEditor.tsx'), 'utf8')

    assert.match(positionsGridSource, /useState<QuantityUnit>/)
    assert.match(positionsGridSource, /setQuantityUnit\(resolvePositionQuantityUnit\(position\)\)/)
    assert.match(positionsGridSource, /quantityUnit,\s*$/m)
    assert.doesNotMatch(positionsGridSource, /quantityUnit:\s*'CONTRACTS'/)
    assert.match(actionDialogSource, /quantityUnit: QuantityUnit/)
    assert.match(actionDialogSource, /onQuantityUnitChange:/)
    assert.match(actionDialogSource, /value=\{quantityUnit\}/)
    assert.match(actionDialogSource, /<option value="BASE">/)
    assert.match(actionDialogSource, /<option value="QUOTE">/)
    assert.match(actionDialogSource, /<option value="CONTRACTS">/)
    assert.match(protectionSource, /quantityUnit\?: QuantityUnit/)
    assert.match(protectionSource, /Quantity \(\{quantityUnit\}\)/)
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
