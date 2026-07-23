import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { describe, it } from 'node:test'
import { fileURLToPath } from 'node:url'

import { chartColorWithAlpha, chartColorWithOpacity, chartTheme, futuresChartColor } from './chartTheme.ts'

const chartSourceUrls = [
  new URL('./chartSettings.ts', import.meta.url),
  new URL('./chartTradeMarkers.ts', import.meta.url),
  new URL('./chartDrawingPersistence.ts', import.meta.url),
  new URL('./components/KLineChartPanel.tsx', import.meta.url),
  new URL('../market/MarketsContent.tsx', import.meta.url),
  new URL('../../../../../packages/frontend-core/src/market/binanceMarketData.ts', import.meta.url)
]

describe('chart theme adapter', () => {
  it('owns stable terminal, drawing and futures chart colors', () => {
    assert.equal(chartTheme.palette.accent, '#fcd535')
    assert.equal(chartTheme.terminal.dark.candle.bar.upColor, '#2ebd85')
    assert.equal(chartTheme.terminal.light.candle.bar.downColor, '#be123c')
    assert.equal(futuresChartColor('buy'), '#0ecb81')
    assert.equal(futuresChartColor('neutral'), '#eaecef')
  })

  it('owns chart alpha conversion', () => {
    assert.equal(chartColorWithAlpha('#fcd535', 0.14), 'rgba(252, 213, 53, 0.14)')
    assert.equal(chartColorWithOpacity('#16c784', 50), 'rgba(22, 199, 132, 0.5)')
    assert.equal(chartColorWithAlpha('currentColor', 0.5), 'currentColor')
  })

  it('is the only chart source containing direct color literals', () => {
    const directColorPattern = /#[0-9a-f]{3,8}\b|\b(?:rgb|rgba|hsl|hsla)\s*\(/giu
    for (const sourceUrl of chartSourceUrls) {
      const source = readFileSync(sourceUrl, 'utf8')
      assert.deepEqual(source.match(directColorPattern), null, `${fileURLToPath(sourceUrl)} contains a direct chart color`)
    }
  })
})
