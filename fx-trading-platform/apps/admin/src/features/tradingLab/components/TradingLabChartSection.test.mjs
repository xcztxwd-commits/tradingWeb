import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const sectionUrl = new URL('./TradingLabChartSection.tsx', import.meta.url)
const workspaceUrl = new URL('./TradingLabDesktopWorkspace.tsx', import.meta.url)

test('chart section owns composite tabs and consumes only the bounded local window', async () => {
  const source = await readFile(sectionUrl, 'utf8')

  assert.match(source, /TradingLabChart/u)
  assert.match(source, /localTickWindow/u)
  assert.match(source, /productType/u)
  assert.match(source, /pricePrecision/u)
  assert.match(source, /visiblePrices/u)
  assert.match(source, /new Set/u)
  assert.match(source, /TICK/u)
  assert.match(source, /KLINE/u)
  assert.match(source, /BID/u)
  assert.match(source, /ASK/u)
  assert.match(source, /LAST/u)
  assert.match(source, /MARK/u)
  assert.match(source, /INDEX/u)
  assert.match(source, /尚无实际价格数据/u)
  assert.match(source, /价格或 actionId 无法无损证明/u)
  assert.match(source, /actualTicks/u)
  assert.match(source, /markers/u)
  assert.match(source, /selectVisibleTickWindow/u)
  assert.match(source, /EMPTY_MARKET_TICKS/u)
  assert.match(
    source,
    /'localTickWindow' in state[\s\S]*:\s*EMPTY_MARKET_TICKS/u,
  )

  assert.doesNotMatch(source, /generateMarketTicks/u)
  assert.doesNotMatch(source, /ticks=\{localTicks\}/u)
  assert.doesNotMatch(source, /key=\{selectedInstrument\.key\}/u)
  assert.doesNotMatch(source, /const ACTUAL_TICKS/u)
  assert.doesNotMatch(source, /const EXECUTED_MARKERS/u)
})

test('desktop workspace delegates the lower chart area instead of generating ticks', async () => {
  const source = await readFile(workspaceUrl, 'utf8')

  assert.match(
    source,
    /import \{ TradingLabChartSection \} from '\.\/TradingLabChartSection\.tsx'/u,
  )
  assert.match(
    source,
    /<TradingLabChartSection[\s\S]*actualTicks=\{chartEvidence\.actualTicks\}[\s\S]*markers=\{chartEvidence\.markers\}/u,
  )
  assert.doesNotMatch(source, /generateMarketTicks/u)
})
