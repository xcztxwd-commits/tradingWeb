import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const componentsDir = dirname(fileURLToPath(import.meta.url))
const webSrc = join(componentsDir, '..', '..', '..')
const tradePanel = readFileSync(join(componentsDir, 'TradePanel.tsx'), 'utf8')
const orderTabs = readFileSync(join(componentsDir, 'OrderTypeTabs.tsx'), 'utf8')
const orderFormSide = readFileSync(join(componentsDir, 'OrderFormSide.tsx'), 'utf8')
const perpetualOptions = readFileSync(join(componentsDir, 'PerpetualOrderOptions.tsx'), 'utf8')
const desktopView = readFileSync(join(webSrc, 'pages', 'trading', 'components', 'TradingDesktopView.tsx'), 'utf8')
const tradingPage = readFileSync(join(webSrc, 'pages', 'trading', 'TradingPage.tsx'), 'utf8')
const orderSheet = readFileSync(join(webSrc, 'pages', 'trading', 'components', 'TradingOrderSheet.tsx'), 'utf8')

describe('P0 Spot and perpetual trading controls', () => {
  it('uses only the supported normal order entry points', () => {
    assert.match(orderTabs, /'trigger'/)
    assert.match(orderTabs, /'oco'/)
    assert.doesNotMatch(orderTabs, /StrategyDropdown/)
    assert.doesNotMatch(orderTabs, /post_only|\bfok\b|\bioc\b|iceberg|twap|trailing/i)
  })

  it('submits canonical settings and real OCO payloads without mock balances', () => {
    assert.doesNotMatch(tradePanel, /useMockBalances/)
    assert.match(tradePanel, /adapterSettings/)
    assert.match(tradePanel, /onSubmitOco/)
    assert.match(orderFormSide, /PerpetualOrderOptions/)
    assert.match(orderFormSide, /form\.quantityUnit === 'QUOTE'/)
    assert.match(tradePanel, /settingsReady/)
    assert.match(perpetualOptions, /form\.side === 'buy' \? 'LONG' : 'SHORT'/)
  })

  it('shares perpetual settings and reference truth with desktop and mobile order entry', () => {
    assert.match(desktopView, /PerpetualTradingControls/)
    assert.match(tradingPage, /<TradingOrderSheet/)
    assert.match(orderSheet, /<MobileOrderSheet[\s\S]*<PerpetualTradingControls[\s\S]*<TradePanel/)
  })
})
