import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const sidePanelSource = readFileSync(join(currentDir, 'MarketSidePanel.tsx'), 'utf8')
const typesSource = readFileSync(join(currentDir, 'types.ts'), 'utf8')

describe('market runtime app boundary', () => {
  it('imports the market runtime from the core package without compatibility re-exports', () => {
    assert.equal(existsSync(join(currentDir, 'marketDataStore.ts')), false)
    assert.equal(existsSync(join(currentDir, 'quoteMarketDataAdapter.ts')), false)
    assert.match(sidePanelSource, /from '@fx-platform\/frontend-core'/)
    assert.match(typesSource, /from '@fx-platform\/frontend-core'/)
    assert.doesNotMatch(sidePanelSource, /features\/market/)
  })

  it('keeps the source-changing state explicit in the app-owned translated view', () => {
    assert.match(sidePanelSource, /marketStatus === 'source-changing'/)
    assert.match(sidePanelSource, /trading\.marketDataSourceChanging/)
  })
})
