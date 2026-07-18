import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const componentsDir = dirname(fileURLToPath(import.meta.url))
const webSrc = join(componentsDir, '..', '..', '..')
const tradePanel = readFileSync(join(componentsDir, 'TradePanel.tsx'), 'utf8')
const tradePanelController = readFileSync(join(componentsDir, 'useTradePanelController.ts'), 'utf8')
const orderTabs = readFileSync(join(componentsDir, 'OrderTypeTabs.tsx'), 'utf8')
const orderFormSide = readFileSync(join(componentsDir, 'OrderFormSide.tsx'), 'utf8')
const perpetualOptions = readFileSync(join(componentsDir, 'PerpetualOrderOptions.tsx'), 'utf8')
const desktopView = readFileSync(join(webSrc, 'pc', 'pages', 'trading', 'PcTradingTerminal.tsx'), 'utf8')
const mobileView = readFileSync(join(webSrc, 'mobile', 'pages', 'trading', 'MobileTradingTerminal.tsx'), 'utf8')
const orderSheet = readFileSync(join(webSrc, 'mobile', 'pages', 'trading', 'TradingOrderSheet.tsx'), 'utf8')

describe('P0 Spot and perpetual trading controls', () => {
  it('uses only the supported normal order entry points', () => {
    assert.match(orderTabs, /'trigger'/)
    assert.match(orderTabs, /'oco'/)
    assert.doesNotMatch(orderTabs, /StrategyDropdown/)
    assert.doesNotMatch(orderTabs, /post_only|\bfok\b|\bioc\b|iceberg|twap|trailing/i)
  })

  it('submits canonical settings and real OCO payloads without mock balances', () => {
    assert.doesNotMatch(tradePanel, /tradeFormTestFixtures/)
    assert.match(tradePanelController, /adapterSettings/)
    assert.match(tradePanelController, /onSubmitOco/)
    assert.match(orderFormSide, /PerpetualOrderOptions/)
    assert.match(orderFormSide, /form\.quantityUnit === 'QUOTE'/)
    assert.match(tradePanelController, /settingsReady/)
    assert.match(perpetualOptions, /form\.side === 'buy' \? 'LONG' : 'SHORT'/)
    assert.match(perpetualOptions, /quantityUnit=\{form\.quantityUnit\}/)
    assert.match(perpetualOptions, /protectedQuantity: protection\.quantity === undefined \? '' : String\(protection\.quantity\)/)
    assert.match(perpetualOptions, /const quantity = patch\.protectedQuantity === undefined/)
    assert.doesNotMatch(perpetualOptions, /showQuantity=\{false\}/)
  })

  it('shares perpetual settings and reference truth with desktop and mobile order entry', () => {
    assert.match(desktopView, /PerpetualTradingControls/)
    assert.match(mobileView, /<TradingOrderSheet/)
    assert.match(orderSheet, /<MobileOrderSheet[\s\S]*<PerpetualTradingControls[\s\S]*<TradePanel/)
  })
})
