import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))

function source(relativePath) {
  return readFileSync(join(currentDir, relativePath), 'utf8')
}

const dataProvidersSource = source('DataProvidersPage.tsx')
const providerInstrumentsSource = source('ProviderInstrumentsPage.tsx')
const symbolBindingsSource = source('SymbolDataBindingsPage.tsx')
const typesSource = source('../types.ts')

describe('data source admin pages', () => {
  it('keeps the provider type picker aligned with seeded backend provider types', () => {
    for (const providerType of ['REST', 'REST_WS', 'LOCAL']) {
      assert.match(dataProvidersSource, new RegExp(`<option value="${providerType}">${providerType}</option>`))
    }
  })

  it('lets provider instruments be filtered before binding or inspection', () => {
    assert.match(providerInstrumentsSource, /assetClassFilter/)
    assert.match(providerInstrumentsSource, /instrumentSearch/)
    assert.match(providerInstrumentsSource, /filteredRows/)
    assert.match(providerInstrumentsSource, /搜索 provider symbol/)
  })

  it('can publish a provider instrument into platform symbol display and binding APIs', () => {
    assert.match(providerInstrumentsSource, /createSymbol/)
    assert.match(providerInstrumentsSource, /getSymbolsPage/)
    assert.match(providerInstrumentsSource, /updateSymbolDisplay/)
    assert.match(providerInstrumentsSource, /createSymbolProviderBinding/)
    assert.match(providerInstrumentsSource, /updateSymbolProviderBinding/)
    assert.match(providerInstrumentsSource, /publishProviderInstrument/)
    assert.match(providerInstrumentsSource, /derivePlatformSymbol/)
    assert.match(providerInstrumentsSource, /发布为平台品种/)
  })

  it('publishes provider instruments with explicit productType', () => {
    assert.match(typesSource, /productType: ProductType/)
    assert.match(providerInstrumentsSource, /productType: productTypeForAssetClass\(instrument\.assetClass\)/)
    assert.match(providerInstrumentsSource, /function productTypeForAssetClass/)
  })

  it('uses synced provider instruments as selectable binding inputs', () => {
    assert.match(symbolBindingsSource, /getProviderInstruments/)
    assert.match(symbolBindingsSource, /ProviderInstrumentRow/)
    assert.match(symbolBindingsSource, /handleProviderInstrumentChange/)
    assert.match(symbolBindingsSource, /<select value=\{bindingForm\.providerInstrumentId\}/)
  })

  it('shows forex icon urls as frontend generated while keeping backend overrides editable', () => {
    assert.match(symbolBindingsSource, /describeIconDisplaySource/)
    assert.match(symbolBindingsSource, /isForexSymbol/)
    assert.match(symbolBindingsSource, /前端自动生成/)
    assert.match(symbolBindingsSource, /updateDisplay\('iconUrl', blankToNull\(event\.target\.value\)\)/)
  })

  it('matches the provider instrument response shape returned by the backend', () => {
    assert.match(typesSource, /rawJson: string/)
  })
})
