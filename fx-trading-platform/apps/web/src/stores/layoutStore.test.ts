import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import {
  DEFAULT_TRADING_LAYOUT,
  applyTradingLayoutPreset,
  getTradingLayoutPanelIds,
  getTradingWorkspaceTemplates,
  loadTradingLayout,
  moveTradingPanel,
  resizeCenterLayout,
  resizeTradingSplit,
  resizeMainLayout,
  saveTradingLayout,
  TRADING_LAYOUT_STORAGE_KEY
} from './layoutStore.ts'

class MemoryStorage {
  private values = new Map<string, string>()

  getItem(key: string) {
    return this.values.get(key) ?? null
  }

  setItem(key: string, value: string) {
    this.values.set(key, value)
  }

  removeItem(key: string) {
    this.values.delete(key)
  }
}

describe('trading workspace layout store', () => {
  it('uses a default long-page layout with watchlist, symbol header, and trading panels in one workspace tree', () => {
    assert.deepEqual(DEFAULT_TRADING_LAYOUT, {
      root: {
        type: 'split',
        direction: 'row',
        sizes: [14, 86],
        children: [
          { type: 'panel', id: 'watchlist' },
          {
            type: 'split',
            direction: 'column',
            sizes: [4, 96],
            children: [
              { type: 'panel', id: 'header' },
              {
                type: 'split',
                direction: 'column',
                sizes: [76, 24],
                children: [
                  {
                    type: 'split',
                    direction: 'row',
                    sizes: [74, 26],
                    children: [
                      {
                        type: 'split',
                        direction: 'column',
                        sizes: [58, 42],
                        children: [
                          { type: 'panel', id: 'chart' },
                          { type: 'panel', id: 'trade' }
                        ]
                      },
                      { type: 'panel', id: 'market' }
                    ]
                  },
                  { type: 'panel', id: 'bottom' }
                ]
              }
            ]
          }
        ]
      }
    })
  })

  it('loads a saved layout and falls back to defaults for invalid storage data', () => {
    const storage = new MemoryStorage()
    const saved = moveTradingPanel(DEFAULT_TRADING_LAYOUT, 'trade', 'market', 'right')

    saveTradingLayout(storage, saved)
    assert.deepEqual(loadTradingLayout(storage), saved)

    assert.equal(TRADING_LAYOUT_STORAGE_KEY, 'fx.trading.workspace.layout.v8')

    storage.setItem(TRADING_LAYOUT_STORAGE_KEY, '{"main":{"chart":99},"center":{"chart":64,"bottom":36}}')
    assert.deepEqual(loadTradingLayout(storage), DEFAULT_TRADING_LAYOUT)
  })

  it('moves a panel to the requested side of another panel and keeps each component once', () => {
    const layout = moveTradingPanel(DEFAULT_TRADING_LAYOUT, 'trade', 'market', 'right')

    assert.deepEqual(getTradingLayoutPanelIds(layout).toSorted(), ['bottom', 'chart', 'header', 'market', 'trade', 'watchlist'])
  })

  it('moves a panel above the target when dropped on the top zone', () => {
    const layout = moveTradingPanel(DEFAULT_TRADING_LAYOUT, 'market', 'chart', 'top')

    assert.deepEqual(getTradingLayoutPanelIds(layout).toSorted(), ['bottom', 'chart', 'header', 'market', 'trade', 'watchlist'])
  })

  it('moves a panel to the left and bottom drop zones', () => {
    const leftLayout = moveTradingPanel(DEFAULT_TRADING_LAYOUT, 'trade', 'market', 'left')
    assert.deepEqual(getTradingLayoutPanelIds(leftLayout).toSorted(), ['bottom', 'chart', 'header', 'market', 'trade', 'watchlist'])

    const bottomLayout = moveTradingPanel(DEFAULT_TRADING_LAYOUT, 'market', 'chart', 'bottom')
    assert.deepEqual(getTradingLayoutPanelIds(bottomLayout).toSorted(), ['bottom', 'chart', 'header', 'market', 'trade', 'watchlist'])
  })

  it('moves the watchlist panel but keeps the symbol header fixed', () => {
    const movedWatchlist = moveTradingPanel(DEFAULT_TRADING_LAYOUT, 'watchlist', 'market', 'right')
    assert.deepEqual(getTradingLayoutPanelIds(movedWatchlist).toSorted(), ['bottom', 'chart', 'header', 'market', 'trade', 'watchlist'])

    assert.deepEqual(moveTradingPanel(DEFAULT_TRADING_LAYOUT, 'header', 'chart', 'bottom'), DEFAULT_TRADING_LAYOUT)
    assert.deepEqual(moveTradingPanel(DEFAULT_TRADING_LAYOUT, 'trade', 'header', 'bottom'), DEFAULT_TRADING_LAYOUT)
  })

  it('lets the bottom terminal split shrink to a compact drag target', () => {
    const resized = resizeTradingSplit(DEFAULT_TRADING_LAYOUT, 'root.1.1', 10)
    assert.equal(getTradingWorkspaceTemplates(resized).bottomColumns, 'minmax(0, 86fr) 8px minmax(0, 14fr)')

    const clamped = resizeTradingSplit(DEFAULT_TRADING_LAYOUT, 'root.1.1', 90)
    assert.equal(getTradingWorkspaceTemplates(clamped).bottomColumns, 'minmax(0, 96fr) 8px minmax(0, 4fr)')
  })

  it('keeps non-terminal split sides from collapsing while resizing', () => {
    const clamped = resizeTradingSplit(DEFAULT_TRADING_LAYOUT, 'root.1.1.0.0', 90)

    assert.equal(getTradingWorkspaceTemplates(clamped).centerRows, 'minmax(0, 86fr) 8px minmax(0, 14fr)')
  })

  it('builds long-page workspace grid templates from the split-tree layout', () => {
    assert.deepEqual(getTradingWorkspaceTemplates(DEFAULT_TRADING_LAYOUT), {
      mainColumns: 'minmax(0, 74fr) 8px minmax(0, 26fr)',
      centerRows: 'minmax(0, 58fr) 8px minmax(0, 42fr)',
      bottomColumns: 'minmax(0, 76fr) 8px minmax(0, 24fr)'
    })
  })

  it('keeps the resize hook facade mapped to the OKX-style workspace splits', () => {
    assert.equal(getTradingWorkspaceTemplates(resizeMainLayout(DEFAULT_TRADING_LAYOUT, 'chart-market', 4)).mainColumns, 'minmax(0, 78fr) 8px minmax(0, 22fr)')

    const centerResized = resizeCenterLayout(DEFAULT_TRADING_LAYOUT, -10)
    assert.equal(getTradingWorkspaceTemplates(centerResized).centerRows, 'minmax(0, 48fr) 8px minmax(0, 52fr)')

    const lowerResized = resizeMainLayout(DEFAULT_TRADING_LAYOUT, 'trade-bottom', 10)
    assert.equal(getTradingWorkspaceTemplates(lowerResized).bottomColumns, 'minmax(0, 86fr) 8px minmax(0, 14fr)')
  })

  it('offers OKX-style workspace presets for chart-first and order-entry-first work', () => {
    const chartFirst = applyTradingLayoutPreset('chart-focus')
    assert.deepEqual(getTradingWorkspaceTemplates(chartFirst), {
      mainColumns: 'minmax(0, 80fr) 8px minmax(0, 20fr)',
      centerRows: 'minmax(0, 68fr) 8px minmax(0, 32fr)',
      bottomColumns: 'minmax(0, 78fr) 8px minmax(0, 22fr)'
    })

    const orderFirst = applyTradingLayoutPreset('order-focus')
    assert.deepEqual(getTradingWorkspaceTemplates(orderFirst), {
      mainColumns: 'minmax(0, 70fr) 8px minmax(0, 30fr)',
      centerRows: 'minmax(0, 48fr) 8px minmax(0, 52fr)',
      bottomColumns: 'minmax(0, 74fr) 8px minmax(0, 26fr)'
    })
  })
})
